(ns loyly.regen.model
  "The room's event model, loaded as given: the regeneration-cycle fragments in
   `.pi/spec/regeneration-cycle/event-model/*.edn`, merged with the events this
   context consumes from the producers' handoff contracts. Nothing here is copied
   or edited: every schema the product uses is read from these files."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [ai.obney.grain.event-model.interface :as event-model]
            [ai.obney.grain.schema-util.interface :as schema-util]))

(def area :regeneration-cycle)

(defn impl-dir
  "The candidate's own directory (harness-impl/04-regeneration-cycle). The scripts
   hand it in; tests run from it."
  []
  (or (System/getProperty "loyly.impl.dir")
      (System/getenv "LOYLY_IMPL_DIR")
      (System/getProperty "user.dir")))

(defn spec-dir [] (io/file (impl-dir) ".." ".." ".pi" "spec"))

(defn- read-edn [f]
  (with-open [r (java.io.PushbackReader. (io/reader f))]
    (edn/read {:readers {}} r)))

(defn- deep-merge [& ms]
  (apply merge-with (fn [a b] (if (and (map? a) (map? b)) (deep-merge a b) b)) ms))

(def consumed-handoffs
  "The six events this context consumes, by the contract that defines each."
  {:cycle-preparation/source-cut-pinned "cycle-preparation"
   :grader-lifecycle/instrument-golden-written "grader-lifecycle"
   :grader-lifecycle/instrument-golden-moved "grader-lifecycle"
   :measurement/measurement-filed "measurement"
   :measurement/score-stage-failed "measurement"
   :measurement/scoreboard-reported "measurement"})

(defn load-model
  "The merged event model: {:regeneration-cycle {...} :cycle-preparation {:events ...} ...}."
  []
  (let [own-dir (io/file (spec-dir) "regeneration-cycle" "event-model")
        fragments (->> (.listFiles own-dir)
                       (filter #(.endsWith (.getName ^java.io.File %) ".edn"))
                       (sort-by #(.getName ^java.io.File %))
                       (map read-edn))
        own (apply deep-merge fragments)
        handoffs (for [[event-type ctx] consumed-handoffs
                       :let [contract (read-edn (io/file (spec-dir) ctx "event-model" "handoffs.edn"))
                             a (keyword (namespace event-type))
                             block (get-in contract [a :events event-type])]]
                   (do (when-not block
                         (throw (ex-info "Consumed handoff missing from its contract"
                                         {:event event-type :contract ctx})))
                       {a {:events {event-type block}}}))]
    (apply deep-merge own handoffs)))

(defonce ^:private model* (delay (load-model)))

(defn model [] @model*)

(defn block
  "The model block of `kind` (:commands :events :read-models :queries ...) named `nm`."
  [kind nm]
  (get-in (model) [(keyword (namespace nm)) kind nm]))

(defn schema-of [kind nm]
  (or (:schema (block kind nm))
      (throw (ex-info "No schema in the event model" {:kind kind :block nm}))))

(defn event-schema [nm] (schema-of :events nm))
(defn command-schema [nm] (schema-of :commands nm))
(defn query-schema [nm] (schema-of :queries nm))
(defn read-model-schema [nm] (:schema (block :read-models nm)))

(defn all-of [kind]
  (into {} (mapcat (fn [[_ area-map]] (get area-map kind))) (model)))

(defn register!
  "Registers every area of the merged model with Grain's event-model registry and
   every command, query and event schema with Grain's schema registry."
  []
  (doseq [[a area-map] (model)]
    (event-model/register-event-model! a area-map))
  (schema-util/register!
   (merge (into {} (map (fn [[k b]] [k (:schema b)])) (all-of :events))
          (into {} (map (fn [[k b]] [k (:schema b)])) (all-of :commands))
          (into {} (map (fn [[k b]] [k (:schema b)])) (all-of :queries)))))
