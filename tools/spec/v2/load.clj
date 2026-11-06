;; Merge one context's .pi/spec/<context>/event-model/*.edn fragments into one Grain model.
;; Refuses a block defined in two fragments, so each block has one home.
;; Adapted from Halla Health's tools/grain-specs/load.clj.
(ns load (:require [clojure.edn :as edn] [clojure.java.io :as io] [clojure.string :as str]))

(defn fragments [dir]
  (->> (.listFiles (io/file dir))
       (filter #(str/ends-with? (.getName %) ".edn"))
       (sort-by #(.getName %))))

(defn merge-model
  "Returns {:model <grain model> :homes {[area field block] file-name}}."
  [dir]
  (reduce
   (fn [{:keys [model homes]} f]
     (let [frag (edn/read-string (slurp f))]
       (reduce-kv
        (fn [acc area am]
          (reduce-kv
           (fn [acc k v]
             (if (map? v)
               (reduce-kv
                (fn [acc bk bv]
                  (when-let [h (get-in acc [:homes [area k bk]])]
                    (throw (ex-info (str bk " (" (name k) ") is defined in both " h " and " (.getName f)) {})))
                  (-> acc
                      (assoc-in [:model area k bk] bv)
                      (assoc-in [:homes [area k bk]] (.getName f))))
                acc v)
               (assoc-in acc [:model area k] v)))
           acc am))
        {:model model :homes homes} frag)))
   {:model {} :homes {}}
   (fragments dir)))
