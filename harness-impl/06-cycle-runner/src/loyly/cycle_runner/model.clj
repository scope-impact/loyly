(ns loyly.cycle-runner.model
  "The room's Grain Event Model of the cycle runner, loaded as given from
   .pi/spec/cycle-runner/event-model/*.edn (never copied or edited), merged and registered
   with Grain. Every block's schema comes from here."
  (:require [ai.obney.grain.event-model.interface :as event-model]
            [ai.obney.grain.schema-util.interface :as schema-util]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def area :cycle-runner)

(defn model-dir
  "The candidate runs at harness-impl/06-cycle-runner, so the model is two levels up."
  []
  (or (System/getProperty "loyly.model.dir")
      (str (.getCanonicalPath (io/file "../../.pi/spec/cycle-runner/event-model")))))

(defn- merge-area [a b]
  (merge-with (fn [x y]
                (if (and (map? x) (map? y))
                  (merge-with (fn [_ _] (throw (ex-info "Block defined twice" {}))) x y)
                  y))
              a b))

(defn load-model
  "Read every .edn fragment of the model directory and merge them into one model."
  ([] (load-model (model-dir)))
  ([dir]
   (let [files (->> (.listFiles (io/file dir))
                    (filter #(.endsWith (.getName ^java.io.File %) ".edn"))
                    (sort-by #(.getName ^java.io.File %)))]
     (when (empty? files)
       (throw (ex-info (str "No event model found in " dir) {:dir dir})))
     (reduce (fn [m f]
               (merge-with merge-area m (edn/read-string (slurp f))))
             {} files))))

(defonce model* (atom nil))

(defn model [] (or @model* (reset! model* (load-model))))

(defn area-map [] (get (model) area))

(defn block [kind k] (get-in (area-map) [kind k]))

(defn schema-of [kind k] (:schema (block kind k)))

(defn register-model!
  "Register the area with Grain and every command, event and query schema in Grain's
   schema registry, each under its block name, exactly as the model states it."
  []
  (let [am (area-map)]
    (event-model/register-event-model! area am)
    (schema-util/register!
     (merge (into {} (map (fn [[k b]] [k (:schema b)])) (:events am))
            (into {} (map (fn [[k b]] [k (:schema b)])) (:commands am))
            (into {} (map (fn [[k b]] [k (:schema b)])) (:queries am))))
    am))

(defn flow-of-command
  "The flows (ORC workflows) whose steps hold the command."
  [command]
  (->> (:flows (area-map))
       (filter (fn [[_ f]] (some #(or (= [:command command] (:to %))
                                       (= [:command command] (:from %)))
                                 (:steps f))))
       (map key)
       set))
