(ns loyly.grader-lifecycle.process-test
  "start.sh as the gate runs it: a separate process, a fresh store file, JSON-RPC
   over stdio, and the store read directly afterwards."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [loyly.grader-lifecycle.support :as s]
            [loyly.grader-lifecycle.system :as system])
  (:import [java.io File]
           [java.util.concurrent TimeUnit]))

(defn- start-script []
  (.getCanonicalPath (File. "start.sh")))

(deftest start-sh-speaks-the-seam-and-records-into-the-handed-store
  (let [dir (s/temp-dir)
        store (File. dir "fresh.db")
        digests (File. dir "digests.json")
        _ (spit digests (json/write-str {"@item-03-001" "s-12"}))
        pb (doto (ProcessBuilder. ^java.util.List [(start-script)])
             (.directory (File. "/"))
             (.redirectError (File. dir "stderr.log")))
        _ (doto (.environment pb)
            (.put "LOYLY_GRAIN_STORE" (str store))
            (.put "LOYLY_SCENARIO_DIGESTS" (str digests)))
        t0 (System/currentTimeMillis)
        proc (.start pb)
        requests [{"jsonrpc" "2.0" "id" 1 "method" "grader-lifecycle/write-instrument-golden"
                   "params" {"golden-id" "G-score-unknown" "scenario" "@item-03-001"
                             "scenario-digest" "s-12" "path" "goldens/score-unknown.out"
                             "digest" "g-40"}}
                  {"jsonrpc" "2.0" "id" 2 "method" "grader-lifecycle/move-instrument-golden"
                   "params" {"golden-id" "G-score-unknown" "digest" "g-41"
                             "reason" "instrument-behavior-changed" "additions-only" false
                             "note" "the refusal now names the grader digest"
                             "scenario-digest" "s-12"}}
                  {"jsonrpc" "2.0" "id" 3 "method" "grader-lifecycle/move-instrument-golden"
                   "params" {"golden-id" "G-score-unknown" "digest" "g-42"
                             "reason" "wording-replaced" "additions-only" false
                             "note" "" "scenario-digest" "s-12"}}
                  {"jsonrpc" "2.0" "id" 4 "method" "grader-lifecycle/instrument-goldens"
                   "params" {}}]]
    (with-open [w (io/writer (.getOutputStream proc))
                r (io/reader (.getInputStream proc))]
      (doseq [req requests]
        (.write w (str (json/write-str req) "\n")))
      (.flush w)
      (let [lines (vec (repeatedly (count requests) #(.readLine ^java.io.BufferedReader r)))
            resps (mapv json/read-str lines)
            elapsed (- (System/currentTimeMillis) t0)]
        (testing "one JSON response per request, carrying its id"
          (is (= [1 2 3 4] (mapv #(get % "id") resps))))
        (testing "within thirty seconds of launch"
          (is (< elapsed 30000) (str "took " elapsed "ms")))
        (is (= "grader-lifecycle/instrument-golden-written"
               (get-in resps [0 "result" "events" 0 "type"])))
        (is (= "g-40" (get-in resps [1 "result" "events" 0 "from-digest"])))
        (is (= "empty-note" (get-in resps [2 "error" "data" "refusal"])))
        (is (= [{"golden-id" "G-score-unknown" "path" "goldens/score-unknown.out"
                 "digest" "g-41" "scenario-digest" "s-12"
                 "moves" [{"from-digest" "g-40" "to-digest" "g-41"
                           "reason" "instrument-behavior-changed"
                           "note" "the refusal now names the grader digest"}]}]
               (get-in resps [3 "result" "goldens"]))))
      (.close w)
      (is (.waitFor proc 30 TimeUnit/SECONDS))
      (testing "nothing else on stdout"
        (is (nil? (.readLine ^java.io.BufferedReader r)))))
    (testing "the handed store holds the facts, read directly"
      (let [store-es (es/start {:conn {:type :sqlite :database-file (str store)}})
            events (try (vec (es/read store-es {:tenant-id system/tenant-id}))
                        (finally (es/stop store-es)))
            types (frequencies (map :event/type events))]
        (is (= 1 (types :grader-lifecycle/instrument-golden-written)))
        (is (= 1 (types :grader-lifecycle/instrument-golden-moved)))
        (is (= 3 (types :sheet/tree-tick-completed)))
        (is (= 3 (types :sheet/execution-traced)))
        (is (pos? (get types :grain/todo-processor-checkpoint 0)))))))
