(ns loyly.grader-lifecycle.model
  "Loads the room's Event Model of the grader-lifecycle area, as given: the
   fragments under .pi/spec/grader-lifecycle/event-model/*.edn are read from the
   room itself, merged, and registered with Grain. Nothing here copies or edits
   them; every schema the blocks use is taken from the loaded model."
  (:require [ai.obney.grain.event-model.interface :as event-model]
            [ai.obney.grain.schema-util.interface :as schema-util]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def area :grader-lifecycle)

(defn model-dir
  "The directory holding the room's event-model fragments. The candidate runs at
   harness-impl/03-grader-lifecycle, so by default they are at
   ../../.pi/spec/grader-lifecycle/event-model/ from there. The system property
   loyly.event-model-dir overrides it."
  []
  (io/file (or (System/getProperty "loyly.event-model-dir")
               "../../.pi/spec/grader-lifecycle/event-model")))

(defn- merge-area [a b]
  (merge-with (fn [x y] (if (and (map? x) (map? y)) (merge x y) y)) a b))

(defn load-model
  "Reads and merges every *.edn fragment of the model directory into one model
   map {area service-area}."
  ([] (load-model (model-dir)))
  ([dir]
   (let [files (->> (.listFiles (io/file dir))
                    (filter #(.endsWith (.getName ^java.io.File %) ".edn"))
                    (sort-by #(.getName ^java.io.File %)))]
     (when (empty? files)
       (throw (ex-info (str "No event-model fragments found in " dir) {:dir (str dir)})))
     (reduce (fn [acc f]
               (merge-with merge-area acc (edn/read-string (slurp f))))
             {}
             files))))

(def model
  "The merged model of the room, loaded once."
  (delay (load-model)))

(defn area-map [] (get @model area))

(defn block [kind block-name]
  (get-in (area-map) [kind block-name]))

(defn schema-of [kind block-name]
  (or (:schema (block kind block-name))
      (throw (ex-info "Block not in the event model" {:kind kind :block block-name}))))

(defn command-schema [c] (schema-of :commands c))
(defn event-schema [e] (schema-of :events e))
(defn query-schema [q] (schema-of :queries q))
(defn read-model-schema [r] (schema-of :read-models r))

(defn event-fields
  "The field names of an event's :schema, in order."
  [e]
  (->> (event-schema e) rest (map first) vec))

(defn register-schemas!
  "Registers every command, event and query :schema of the model in Grain's
   schema registry under the block's own name, exactly as given."
  []
  (let [am (area-map)]
    (schema-util/register!
     (into {}
           (for [kind [:commands :events :queries]
                 [block-name {:keys [schema]}] (get am kind)]
             [block-name schema])))))

(defn register-event-model!
  "Registers the merged area with Grain's event-model registry."
  []
  (event-model/register-event-model! area (area-map)))
