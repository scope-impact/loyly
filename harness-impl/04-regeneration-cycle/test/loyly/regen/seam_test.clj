(ns loyly.regen.seam-test
  "The transport of the seam, and start.sh played as the gate plays it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [loyly.regen.support :as s :refer [rpc refusal recorded!]]
            [loyly.regen.seam :as seam]
            [loyly.regen.model :as model]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface])
  (:import [java.util.concurrent TimeUnit]))

(defn- in-product [f] (s/reset-goldens!) (s/with-fresh-product {:scoring-tools []} f))

(deftest transport-errors
  (in-product
   (fn []
     (let [r (json/read-str (seam/handle-line "{not json"))]
       (is (= -32700 (get-in r ["error" "code"]))))
     (is (= -32601 (get-in (rpc "regeneration-cycle/no-such-method" {}) ["error" "code"])))
     (testing "a null for a field that is not maybe is a schema mismatch"
       (s/pin-cut! "K-3")
       (is (= -32602 (get-in (s/self-check {"harness-digest" nil}) ["error" "code"])))
       (is (nil? (s/progress "C-7"))))
     (testing "an enum value outside its enumeration"
       (is (= -32602 (get-in (s/self-check {"occasion" "after-lunch"}) ["error" "code"]))))
     (testing "a handed-in event must match its contract exactly"
       (is (= -32602 (get-in (rpc "cycle-preparation/source-cut-pinned"
                                  {"cut-id" "K-5" "commit" "not-a-sha" "tree-digest" "t"
                                   "pinned-at" "2026-11-07T09:00:00Z"})
                             ["error" "code"])))
       (is (= -32602 (get-in (rpc "cycle-preparation/source-cut-pinned"
                                  {"cut-id" "K-5" "commit" (apply str (repeat 40 "a")) "tree-digest" "t"
                                   "pinned-at" "2026-11-07T09:00:00Z" "extra" 1})
                             ["error" "code"])))
       (is (= -32602 (get-in (rpc "cycle-preparation/source-cut-pinned"
                                  {"cut-id" "K-5" "commit" (apply str (repeat 40 "a")) "tree-digest" "t"
                                   "pinned-at" "yesterday"})
                             ["error" "code"]))))
     (testing "a handed-in event is appended exactly as given"
       (let [r (recorded! (rpc "cycle-preparation/source-cut-pinned"
                               {"cut-id" "K-5" "commit" (apply str (repeat 40 "b")) "tree-digest" "t"
                                "pinned-at" "2026-11-07T09:00:00Z"}))]
         (is (= [{"type" "cycle-preparation/source-cut-pinned" "cut-id" "K-5"
                  "commit" (apply str (repeat 40 "b")) "tree-digest" "t"
                  "pinned-at" "2026-11-07T09:00:00Z"}]
                (s/events r))))))))

;; ---------------------------------------------------------------------------
;; start.sh, as the gate plays it
;; ---------------------------------------------------------------------------

(defn- start-product [store-file env]
  (let [pb (ProcessBuilder. ^java.util.List [(str (io/file (model/impl-dir) "start.sh"))])]
    (.directory pb (io/file "/"))
    (.put (.environment pb) "LOYLY_GRAIN_STORE" store-file)
    (doseq [[k v] env] (.put (.environment pb) k v))
    (.redirectError pb (java.lang.ProcessBuilder$Redirect/appendTo (io/file (str store-file ".stderr"))))
    (.start pb)))

(deftest start-sh-takes-the-identity-check-within-thirty-seconds
  (let [file (str (java.io.File/createTempFile "loyly-regen-gate" ".sqlite"))
        _ (io/delete-file file)
        t0 (System/nanoTime)
        p (start-product file {"LOYLY_SCORING_TOOL_PATHS" "[\"harness/score.clj\", \"harness/scoreboard.clj\"]"})
        w (io/writer (.getOutputStream p))
        r (io/reader (.getInputStream p))
        send! (fn [id method params]
                (.write w (json/write-str {"jsonrpc" "2.0" "id" id "method" method "params" params}))
                (.write w "\n")
                (.flush w)
                (let [line (.readLine ^java.io.BufferedReader r)]
                  (json/read-str line)))
        golden-digest (str "sha256:" (s/sha256 "checklist\tg-1\n"))]
    (try
      (let [pin (send! 1 "cycle-preparation/source-cut-pinned"
                       {"cut-id" "K-1" "commit" (apply str (repeat 40 "c")) "tree-digest" "T-1"
                        "pinned-at" "2026-11-07T09:00:00Z"})
            golden (send! 2 "grader-lifecycle/instrument-golden-written"
                          {"golden-id" "checklist" "scenario" "s" "scenario-digest" "S-1"
                           "path" "harness/goldens/checklist.edn" "digest" "g-1" "written-at" "2026-11-07T09:00:00Z"})
            check (send! 3 "regeneration-cycle/record-harness-self-check"
                         (s/self-check-params {"cycle-id" "ID-1" "cut-id" "K-1" "goldens-digest" golden-digest}))
            unmatched (send! 4 "regeneration-cycle/prove-clean-room"
                             (s/clean-room-params {"cycle-id" "ID-1" "cut-id" "K-1"
                                                   "fenced-paths" ["harness/score.clj" "harness/scoreboard.clj" "harness/nothing"]
                                                   "unmatched-paths" ["harness/nothing"]}))
            proved (send! 5 "regeneration-cycle/prove-clean-room"
                          (s/clean-room-params {"cycle-id" "ID-1" "cut-id" "K-1"
                                                "fenced-paths" ["harness/score.clj" "harness/scoreboard.clj"]}))
            unfenced (send! 6 "regeneration-cycle/prove-clean-room"
                            (s/clean-room-params {"cycle-id" "ID-1" "cut-id" "K-1"
                                                  "fenced-paths" ["harness/score.clj"]}))
            progress (send! 7 "regeneration-cycle/cycle-progress" {"cycle-id" "ID-1"})
            elapsed-s (/ (- (System/nanoTime) t0) 1e9)]
        (is (< elapsed-s 30) (str "identity check took " elapsed-s "s"))
        (is (= [1 2 3 4 5 6 7] (map #(get % "id") [pin golden check unmatched proved unfenced progress])))
        (is (= ["cycle-preparation/source-cut-pinned"] (map #(get % "type") (get-in pin ["result" "events"]))))
        (is (= ["regeneration-cycle/harness-self-check-passed"]
               (map #(get % "type") (get-in check ["result" "events"]))))
        (is (= "fenced-path-matched-nothing" (refusal unmatched)))
        (is (= ["regeneration-cycle/clean-room-proved"] (map #(get % "type") (get-in proved ["result" "events"]))))
        (is (= "clean-room-already-proved" (refusal unfenced)))
        (is (= ["running" "regenerate" "K-1"]
               (map #(get-in progress ["result" "cycle" %]) ["status" "stage" "cut-id"])))
        (.close w)
        (testing "nothing but responses on stdout"
          (is (nil? (.readLine ^java.io.BufferedReader r)))))
      (finally
        (.waitFor p 20 TimeUnit/SECONDS)
        (.destroy p)))
    (testing "the store the gate reads holds the product's events and ORC's runs"
      (let [store (es/start {:conn {:type :sqlite :database-file file}})]
        (try
          (let [types (set (map :event/type (into [] (es/read store {:tenant-id loyly.regen.system/tenant-id}))))]
            (is (contains? types :regeneration-cycle/clean-room-proved))
            (is (contains? types :sheet/tree-tick-completed))
            (is (contains? types :grain/todo-processor-checkpoint)))
          (finally (es/stop store)))))
    (doseq [f [file (str file "-wal") (str file "-shm") (str file ".stderr")]]
      (io/delete-file f true))))

(deftest start-sh-refuses-a-scoring-tool-not-fenced-from-its-environment
  (let [file (str (java.io.File/createTempFile "loyly-regen-tools" ".sqlite"))
        _ (io/delete-file file)
        p (start-product file {"LOYLY_SCORING_TOOL_PATHS" "[\"harness/score.clj\", \"harness/scoreboard.clj\"]"})
        w (io/writer (.getOutputStream p))
        r (io/reader (.getInputStream p))
        lines ["{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"cycle-preparation/source-cut-pinned\",\"params\":{\"cut-id\":\"K-3\",\"commit\":\"3333333333333333333333333333333333333333\",\"tree-digest\":\"t\",\"pinned-at\":\"2026-11-07T09:00:00Z\"}}"
               (json/write-str {"jsonrpc" "2.0" "id" 2 "method" "regeneration-cycle/record-harness-self-check"
                                "params" (s/self-check-params {"goldens-digest" (str "sha256:" (s/sha256 ""))})})
               (json/write-str {"jsonrpc" "2.0" "id" 3 "method" "regeneration-cycle/prove-clean-room"
                                "params" (s/clean-room-params {"fenced-paths" ["harness/score.clj"]})})]]
    (try
      (doseq [l lines] (.write w (str l "\n")))
      (.close w)
      (let [responses (mapv json/read-str (line-seq r))]
        (is (= [1 2 3] (map #(get % "id") responses)))
        (is (= "scoring-tool-not-fenced" (refusal (last responses))))
        (is (= ["harness/scoreboard.clj"] (get-in (last responses) ["error" "data" "paths"]))))
      (finally
        (.waitFor p 20 TimeUnit/SECONDS)
        (.destroy p)
        (doseq [f [file (str file "-wal") (str file "-shm") (str file ".stderr")]]
          (io/delete-file f true))))))
