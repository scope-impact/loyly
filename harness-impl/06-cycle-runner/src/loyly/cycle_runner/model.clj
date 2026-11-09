(ns loyly.cycle-runner.model
  "The room's event models, read as given: this context's own (registered with
   Grain) and the five contexts' contracts (their handoffs and consumers)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn home
  "The candidate's own directory: harness-impl/06-cycle-runner."
  []
  (or (System/getProperty "loyly.home")
      (System/getenv "LOYLY_CYCLE_RUNNER_HOME")
      (System/getProperty "user.dir")))

(defn spec-root
  "The room's spec, beside the candidate: ../../.pi/spec."
  []
  (str (.getCanonicalFile (io/file (home) ".." ".." ".pi" "spec"))))

(defn- deep-merge [& ms]
  (apply merge-with (fn [a b] (if (and (map? a) (map? b)) (deep-merge a b) b)) ms))

(defn read-fragments
  "Every event-model/*.edn of a context, merged."
  [context]
  (let [dir (io/file (spec-root) context "event-model")
        files (->> (.listFiles dir)
                   (filter #(str/ends-with? (.getName ^java.io.File %) ".edn"))
                   (sort-by #(.getName ^java.io.File %)))]
    (when (empty? files)
      (throw (ex-info (str "No event model found at " dir) {:dir (str dir)})))
    (apply deep-merge (map #(edn/read-string (slurp %)) files))))

(def model
  "This context's event model, the room's cycle-runner/event-model/*.edn merged."
  (delay (read-fragments "cycle-runner")))

(defn area [] (:cycle-runner @model))

(defn command-schema [k] (get-in (area) [:commands k :schema]))
(defn event-schema [k] (get-in (area) [:events k :schema]))
(defn query-schema [k] (get-in (area) [:queries k :schema]))
(defn read-model-schema [k] (get-in (area) [:read-models k :schema]))
(defn event-description [k] (get-in (area) [:events k :description]))

(defn event-fields
  "The top-level field names of an event's :schema, in order."
  [schema]
  (->> (rest schema)
       (remove map?)
       (mapv first)))

;; ---------------------------------------------------------------------------
;; The five contexts' contracts

(def contexts
  "The five contexts, in the order a relay visits producers."
  ["specification" "cycle-preparation" "grader-lifecycle" "regeneration-cycle" "measurement"])

(def ^:private context-models
  (delay (into {} (for [c contexts] [c (read-fragments c)]))))

(defn handoff-types
  "The events a context hands to other contexts: its event-model/handoffs.edn."
  [context]
  (let [f (io/file (spec-root) context "event-model" "handoffs.edn")]
    (if (.exists f)
      (->> (edn/read-string (slurp f)) vals (mapcat (comp keys :events)) set)
      #{})))

(defn consumed-by
  "The events a context's event model consumes: its read models' :consumes and
   its processors' :subscribes."
  [context]
  (let [areas (vals (get @context-models context))]
    (set (concat (mapcat :consumes (mapcat (comp vals :read-models) areas))
                 (mapcat :subscribes (mapcat (comp vals :todo-processors) areas))))))

(defn consumers
  "Every context, other than the producer, whose event model consumes the
   handoff, by name."
  [producer handoff-type]
  (->> contexts
       (remove #{producer})
       (filter #(contains? (consumed-by %) handoff-type))
       sort
       vec))
