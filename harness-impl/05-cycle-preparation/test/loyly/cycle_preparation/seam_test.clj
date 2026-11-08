(ns loyly.cycle-preparation.seam-test
  "The seam (seam.md) and what the gate checks: the transport, the refusals in
   the order of the Allium requires, the commits re-read at every command, the
   boot guard, and the store read directly."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [loyly.cycle-preparation.candidate :as c]))

(def c1 "4f2a9c1e0b7d3a5f9c2e8b1d4a6f0c3e7b9d2a51")
(def c2 "9b07e2d4c8a1f6035e9d2b7c4a0f8e1d6b3c5a92")

(defn- pin! [cand cut-id commit tree-digest]
  (c/call! cand "cycle-preparation/pin-source-cut"
           {"cut-id" cut-id "commit" commit "tree-digest" tree-digest}))

(defn- cuts [cand]
  (get-in (c/call! cand "cycle-preparation/source-cuts" {}) ["result" "cuts"]))

(defn- refusal [resp] (get-in resp ["error" "data" "refusal"]))

(def model-dir "../../.pi/spec/cycle-preparation/event-model")

(defn- model-event-schemas []
  (->> (.listFiles (io/file model-dir))
       (filter #(.endsWith (.getName ^java.io.File %) ".edn"))
       (map (comp edn/read-string slurp))
       (mapcat #(get-in % [:cycle-preparation :events]))
       (into {})))

(defn- schema-fields [schema] (set (map first (rest schema))))

(defn- domain-fields [event]
  (set (remove #(= "event" (namespace %)) (keys event))))

(deftest gate-identity-and-store
  (testing "the gate's identity check, then the store read directly"
    (let [dir (c/temp-dir)
          commits (io/file dir "commits.json")
          store (io/file dir "store.db")
          _ (c/write-commits! commits {c1 "t-81" c2 "t-82"})
          started (System/nanoTime)
          cand (c/start! {:commits-file commits :store-file store})]
      (try
        (let [a (pin! cand "K-9" c1 "t-81")
              b (pin! cand "K-9" c2 "t-82")
              shown (cuts cand)
              secs (/ (- (System/nanoTime) started) 1e9)]
          (is (get-in a ["result" "events"]))
          (is (= "cut-already-pinned" (refusal b)))
          (is (= [["K-9" c1 "t-81"]] (mapv #(mapv (partial get %) ["cut-id" "commit" "tree-digest"]) shown)))
          (is (< secs 30) (str "identity check took " secs "s")))
        (finally (is (zero? (c/stop! cand)))))
      (let [events (c/store-events store)
            model-events (model-event-schemas)
            by-type (group-by :event/type events)
            sheet-id (some #(when (and (= :sheet/sheet-created (:event/type %))
                                       (= "cycle-preparation/cut-to-fence" (:name %)))
                              (:sheet-id %))
                           events)]
        (testing "every event is the model's, ORC's or Grain's own"
          (is (seq (get by-type :cycle-preparation/source-cut-pinned)))
          (doseq [t (keys by-type)]
            (is (or (contains? model-events t) (#{"sheet" "grain"} (namespace t)))
                (str "foreign event type " t))))
        (testing "each model event has exactly its schema's fields"
          (doseq [e events
                  :let [s (get-in model-events [(:event/type e) :schema])]
                  :when s]
            (is (= (schema-fields s) (domain-fields e)) (pr-str e))
            (is (inst? (:pinned-at e)))))
        (testing "each command event is written inside a successful run of cycle-preparation/cut-to-fence"
          (is (uuid? sheet-id))
          (doseq [[i e] (map-indexed vector events)
                  :when (= :cycle-preparation/source-cut-pinned (:event/type e))]
            (let [before (subvec events 0 i)
                  after (subvec events (inc i))
                  tick (:tick-id (last (filter #(= :sheet/tree-tick-started (:event/type %)) before)))
                  done (first (filter #(and (= :sheet/tree-tick-completed (:event/type %))
                                            (= tick (:tick-id %)))
                                      after))]
              (is (some? tick))
              (is (not-any? #(and (= :sheet/tree-tick-completed (:event/type %)) (= tick (:tick-id %))) before))
              (is (= :success (:root-status done)))
              (is (= sheet-id (:sheet-id done))))))
        (testing "each tick completes exactly once and is traced once"
          (let [started (frequencies (map :tick-id (by-type :sheet/tree-tick-started)))
                completed (frequencies (map :tick-id (by-type :sheet/tree-tick-completed)))
                traced (frequencies (map :trace-id (by-type :sheet/execution-traced)))]
            (is (= 2 (count started)) "two pins and nothing else ran ORC (the query runs none)")
            (doseq [[t _] started]
              (is (= 1 (get completed t)) (str "tick " t))
              (is (= 1 (get traced t)) (str "trace " t)))))
        (testing "Grain checkpoints every named todo processor"
          (is (seq (by-type :grain/todo-processor-checkpoint)))
          (is (every? keyword? (map :processor/name (by-type :grain/todo-processor-checkpoint)))))))))

(deftest refusals-follow-the-requires-order
  (testing "requires 1 is checked before 2 and 3; 2 before 3"
    (c/with-candidate [cand _ {c1 "t-81"}]
      (is (nil? (get (pin! cand "K-3" c1 "t-81") "error")))
      (is (= "cut-already-pinned" (refusal (pin! cand "K-3" "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef" "t-0"))))
      (is (= "cut-already-pinned" (refusal (pin! cand "K-3" c1 "t-99"))))
      (is (= "not-a-commit" (refusal (pin! cand "K-5" "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef" "t-99"))))
      (is (= 1 (count (cuts cand)))))))

(deftest commits-are-read-at-every-command
  (c/with-candidate [cand commits {}]
    (is (= "not-a-commit" (refusal (pin! cand "K-7" c2 "t-82"))))
    (c/write-commits! commits {c2 "t-82"})
    (is (nil? (get (pin! cand "K-7" c2 "t-82") "error")))
    (c/write-commits! commits {c2 "t-83"})
    (is (= "tree-digest-not-the-commits" (refusal (pin! cand "K-8" c2 "t-82"))))
    (is (= ["K-7"] (mapv #(get % "cut-id") (cuts cand))))))

(deftest no-commits-file-is-no-commit
  (let [dir (c/temp-dir)
        cand (c/start! {:commits-file nil :store-file (io/file dir "store.db")})]
    (try
      (is (= "not-a-commit" (refusal (pin! cand "K-1" c1 "t-81"))))
      (is (= [] (cuts cand)))
      (finally (c/stop! cand)))))

(deftest newest-first-and-two-cuts-of-one-commit
  (c/with-candidate [cand _ {c1 "t-81" c2 "t-82"}]
    (is (nil? (get (pin! cand "K-3" c1 "t-81") "error")))
    (Thread/sleep 5)
    (is (nil? (get (pin! cand "K-5" c1 "t-81") "error")))
    (Thread/sleep 5)
    (is (nil? (get (pin! cand "K-6" c2 "t-82") "error")))
    (is (= ["K-6" "K-5" "K-3"] (mapv #(get % "cut-id") (cuts cand))))))

(deftest transport-errors
  (c/with-candidate [cand _ {c1 "t-81"}]
    (testing "a short or upper-case commit does not match the schema"
      (let [r (pin! cand "K-4" "4f2a9c1" "t-81")]
        (is (= -32602 (get-in r ["error" "code"]))))
      (let [r (pin! cand "K-4" (clojure.string/upper-case c1) "t-81")]
        (is (= -32602 (get-in r ["error" "code"])))))
    (testing "a missing field does not match the schema"
      (is (= -32602 (get-in (c/call! cand "cycle-preparation/pin-source-cut" {"cut-id" "K-4" "commit" c1}) ["error" "code"]))))
    (testing "an unknown method"
      (is (= -32601 (get-in (c/call! cand "cycle-preparation/nope" {}) ["error" "code"]))))
    (testing "a line that is not JSON"
      (let [r (c/send-line! cand "{not json")]
        (is (= -32700 (get-in r ["error" "code"])))
        (is (contains? r "id"))))
    (testing "nothing was recorded"
      (is (= [] (cuts cand))))))

(deftest the-boot-guard-refuses-a-model-the-blocks-are-apart-from
  (testing "start refuses when the running blocks and the model are apart"
    (let [dir (c/temp-dir)
          mdir (io/file dir "model")
          _ (.mkdirs mdir)
          _ (doseq [f (.listFiles (io/file model-dir))
                    :when (.endsWith (.getName ^java.io.File f) ".edn")]
              (io/copy f (io/file mdir (.getName ^java.io.File f))))
          _ (spit (io/file mdir "zz-extra.edn")
                  (pr-str {:cycle-preparation
                           {:commands {:cycle-preparation/unpin-source-cut
                                       {:description "Not implemented by the running blocks."
                                        :schema [:map [:cut-id :string]]
                                        :produces #{:cycle-preparation/source-cut-pinned}}}}}))
          cand (c/start! {:commits-file nil
                          :store-file (io/file dir "store.db")
                          :env {"LOYLY_EVENT_MODEL_DIR" (.getCanonicalPath mdir)}})
          exit (c/stop! cand)]
      (is (not (zero? exit)))
      (is (re-find #"refusing to start" (c/stderr cand))))))
