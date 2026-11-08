(ns loyly.specification.model
  "Loads the room's Grain Event Model as given (event-model/*.edn), merges the
   fragments, and registers each area with Grain."
  (:require [ai.obney.grain.event-model.interface :as event-model]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn impl-dir
  "The candidate's own directory, harness-impl/01-specification."
  ^java.io.File []
  (io/file (or (System/getenv "LOYLY_IMPL_DIR")
               (System/getProperty "loyly.impl.dir")
               ".")))

(defn model-dir ^java.io.File []
  (io/file (impl-dir) "../../.pi/spec/specification/event-model"))

(defn- merge-area [acc-area area-map file]
  (reduce-kv
   (fn [acc field v]
     (if (map? v)
       (update acc field
               (fn [existing]
                 (reduce-kv
                  (fn [m k block]
                    (when (contains? m k)
                      (throw (ex-info (str "Block " k " is defined twice in the event model")
                                      {:block k :file (str file)})))
                    (assoc m k block))
                  (or existing {})
                  v)))
       (if (and (contains? acc field) (not= (get acc field) v))
         (throw (ex-info (str "Area field " field " is defined twice in the event model")
                         {:field field :file (str file)}))
         (assoc acc field v))))
   acc-area
   area-map))

(defn load-model
  "The merged event model of every .edn fragment in `dir`."
  ([] (load-model (model-dir)))
  ([dir]
   (let [files (->> (.listFiles (io/file dir))
                    (filter #(.endsWith (.getName ^java.io.File %) ".edn"))
                    (sort-by #(.getName ^java.io.File %)))]
     (when (empty? files)
       (throw (ex-info "No event model found" {:dir (str (.getCanonicalPath (io/file dir)))})))
     (reduce
      (fn [model f]
        (reduce-kv (fn [m area area-map]
                     (update m area #(merge-area (or % {}) area-map f)))
                   model
                   (edn/read-string (slurp f))))
      {}
      files))))

(defn register!
  "Registers every area of the room's event model with Grain. Returns the model."
  ([] (register! (load-model)))
  ([model]
   (doseq [[area area-map] model]
     (event-model/register-event-model! area area-map))
   model))
