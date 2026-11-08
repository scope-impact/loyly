(ns measurement.model
  "The room's event model, loaded as given: the measurement fragments under
   .pi/spec/measurement/event-model/ merged with the consumed handoff events of
   the producers' contracts. Nothing here copies or edits a model file; every
   schema the running blocks use is read from these files."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn spec-root
  "The room's .pi/spec directory. The candidate runs at harness-impl/02-measurement,
   so the spec is two levels up; LOYLY_SPEC_ROOT (or the system property
   loyly.spec.root) overrides it."
  []
  (or (System/getProperty "loyly.spec.root")
      (System/getenv "LOYLY_SPEC_ROOT")
      "../../.pi/spec"))

(def fragment-files
  ["measurement/event-model/area.edn"
   "measurement/event-model/checklist.edn"
   "measurement/event-model/cycle-measurement.edn"
   "measurement/event-model/scoreboard.edn"
   "measurement/event-model/handoffs.edn"])

(def contract-files
  ["specification/event-model/handoffs.edn"
   "cycle-preparation/event-model/handoffs.edn"
   "regeneration-cycle/event-model/handoffs.edn"])

(def consumed-handoffs
  "The events this context consumes from other contexts."
  #{:specification/scenario-stamped
    :cycle-preparation/source-cut-pinned
    :regeneration-cycle/gate-attempt-passed})

(defn- read-edn [rel]
  (let [f (io/file (spec-root) rel)]
    (when-not (.exists f)
      (throw (ex-info (str "Event model file not found: " (.getPath f)) {:file (.getPath f)})))
    (edn/read-string (slurp f))))

(defn- merge-area [a b]
  (merge-with (fn [x y] (if (and (map? x) (map? y)) (merge x y) y)) a b))

(defn- consumed-only
  "A producer contract reduced to the events this context consumes."
  [contract]
  (into {}
        (keep (fn [[area area-map]]
                (let [events (select-keys (:events area-map) consumed-handoffs)]
                  (when (seq events) [area {:events events}]))))
        contract))

(defn load-model
  "The merged model: {area -> service-area}."
  []
  (let [fragments (map read-edn fragment-files)
        contracts (map (comp consumed-only read-edn) contract-files)]
    (reduce (fn [acc m] (merge-with merge-area acc m)) {} (concat fragments contracts))))

(def model (load-model))

(defn block
  "The model block of `kind` (:commands :events :read-models :queries :todo-processors)."
  [kind block-name]
  (get-in model [(keyword (namespace block-name)) kind block-name]))

(defn schema-of [kind block-name]
  (or (:schema (block kind block-name))
      (throw (ex-info (str "No schema in the model for " block-name) {:kind kind :block block-name}))))

(defn flow-of-command
  "The flow (ORC workflow name) a command belongs to, from the model's :flows."
  [command-name]
  (some (fn [[_ area-map]]
          (some (fn [[flow-name flow]]
                  (when (some (fn [{:keys [from to]}]
                                (or (= from [:command command-name]) (= to [:command command-name])))
                              (:steps flow))
                    flow-name))
                (:flows area-map)))
        model))
