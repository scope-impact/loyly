(ns loyly.specification.gate-test
  "Plays the gate: start.sh on a fresh SQLite store, a fresh spec file stamped
   and reported over stdio, then the store read directly with Grain."
  (:require [ai.obney.grain.event-model-validator.interface :as event-model-validator]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [loyly.specification.model :as model]
            [loyly.specification.system :as system]))

(defn- run-start-sh
  "Run start.sh from `cwd` with `store`, feed it `requests`, close stdin.
   Returns {:stdout [lines] :exit n :millis n}."
  [cwd store requests]
  (let [pb (doto (ProcessBuilder. ^java.util.List [(str (.getAbsolutePath (io/file "start.sh")))])
             (.directory (io/file cwd))
             (.redirectError (io/file cwd "stderr.log")))
        _ (.put (.environment pb) "LOYLY_GRAIN_STORE" (str store))
        t0 (System/currentTimeMillis)
        p (.start pb)]
    (with-open [w (io/writer (.getOutputStream p))]
      (doseq [r requests]
        (.write w (str (json/write-str r) "\n"))))
    (let [out (slurp (.getInputStream p))
          exit (.waitFor p)]
      {:stdout (str/split-lines out) :exit exit :millis (- (System/currentTimeMillis) t0)})))

(defn- schema-keys [schema]
  (set (keep #(when (vector? %) (first %)) (rest schema))))

(deftest gate-identity-check-over-stdio-and-the-store
  (let [cwd (doto (.getAbsoluteFile (io/file "target" "test-work" (str "gate-" (random-uuid)))) .mkdirs)
        store (io/file cwd "store.db")
        rel "plan/09-fresh/spec.feature"]
    (io/make-parents (io/file cwd rel))
    (spit (io/file cwd rel) "Feature: fresh\n\n  Scenario: a fresh scenario\n    Given nothing\n")
    (let [{:keys [stdout exit millis]}
          (run-start-sh cwd store
                        [{"jsonrpc" "2.0" "id" "s1" "method" "specification/stamp-slice"
                          "params" {"slice" "09" "source" rel
                                    "unstamped" [{"line" 3 "title" "a fresh scenario"}]
                                    "empty-title-lines" []}}
                         {"jsonrpc" "2.0" "id" 2 "method" "specification/stamped-items"
                          "params" {"slice" "09"}}])
          responses (mapv json/read-str stdout)]
      (testing "the seam: one JSON response per request, carrying its id, nothing else"
        (is (zero? exit))
        (is (< millis 30000) "stamped and reported within thirty seconds of launch")
        (is (= ["s1" 2] (mapv #(get % "id") responses)))
        (is (= ["09-001"] (mapv #(get % "item-id") (get-in responses [0 "result" "events"]))))
        (is (= [{"item-id" "09-001" "source" rel "line" 3 "title" "a fresh scenario"}]
               (get-in responses [1 "result" "slices" 0 "items"])))
        (is (str/includes? (slurp (io/file cwd rel)) "  @item-09-001\n  Scenario: a fresh scenario")))
      (testing "the store, read directly"
        (let [es (es/start {:conn {:type :sqlite :database-file (str store)} :event-pubsub nil :logger nil})]
          (try
            (let [events (into [] (es/read es {:tenant-id system/tenant-id}))
                  model-events (get-in (model/load-model) [:specification :events])
                  ours (filterv #(contains? model-events (:event/type %)) events)
                  ticks-started (filterv #(= :sheet/tree-tick-started (:event/type %)) events)
                  ticks-completed (filterv #(= :sheet/tree-tick-completed (:event/type %)) events)
                  traced (filterv #(= :sheet/execution-traced (:event/type %)) events)]
              (is (seq ours) "it holds the product's events")
              (is (every? #(or (contains? model-events (:event/type %))
                               (#{"sheet" "grain"} (namespace (:event/type %))))
                          events)
                  "every event is the model's, ORC's or Grain's own")
              (doseq [e ours]
                (is (= (schema-keys (get-in model-events [(:event/type e) :schema]))
                       (set (remove #(= "event" (namespace %)) (keys e))))
                    (str "exactly the fields of " (:event/type e))))
              (doseq [e ours]
                (is (some (fn [start]
                            (let [done (first (filter #(= (:tick-id start) (:tick-id %)) ticks-completed))]
                              (and done
                                   (= :success (:root-status done))
                                   (neg? (compare (:event/id start) (:event/id e)))
                                   (neg? (compare (:event/id e) (:event/id done))))))
                          ticks-started)
                    "written inside a run of specification/stamping that completed with success"))
              (is (= 1 (count ticks-started)) "the query ran no workflow")
              (doseq [start ticks-started]
                (is (= 1 (count (filter #(= (:tick-id start) (:tick-id %)) ticks-completed))))
                (is (= 1 (count (filter #(= (:tick-id start) (:trace-id %)) traced)))))
              (is (some #(= :grain/todo-processor-checkpoint (:event/type %)) events)))
            (finally (es/stop es))))))))

(deftest boot-guard-refuses-when-blocks-and-model-are-apart
  (let [model (model/load-model)]
    (testing "the room's model and the running blocks agree"
      (model/register! model)
      (is (:valid? (event-model-validator/verify-event-model!))))
    (testing "a model the blocks do not implement stops the start"
      (try
        (model/register! (assoc-in model [:specification :events :specification/scenario-stamped :schema]
                                   [:map [:item-id :string]]))
        (is (thrown? clojure.lang.ExceptionInfo (system/verify-model!)))
        (model/register! (assoc-in model [:specification :commands :specification/stamp-slice-twice]
                                   (get-in model [:specification :commands :specification/stamp-slice])))
        (is (thrown? clojure.lang.ExceptionInfo (system/verify-model!)))
        (finally (model/register! model))))))
