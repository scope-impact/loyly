(ns loyly.goldens (:require [loyly.domain :as d] [loyly.cycle :as c] [clojure.data.json :as json] [clojure.string :as str]))
(defn current-digest [scenario]
  (when-let [path (System/getenv "LOYLY_SCENARIO_DIGESTS")]
    (get (json/read-str (slurp path)) scenario)))
(defn command [id ctx]
  (let [p (:command ctx) g (d/row ctx :grader-lifecycle/instrument-goldens :golden-id (:golden-id p))]
    (case id
      :grader-lifecycle/write-instrument-golden
      (cond g (d/refuse "golden-exists") (not= (current-digest (:scenario p)) (:scenario-digest p)) (d/refuse "scenario-digest-mismatch")
        :else (c/emit :grader-lifecycle/instrument-golden-written (assoc p :written-at (d/now))))
      :grader-lifecycle/move-instrument-golden
      (cond (not g) (d/refuse "no-such-golden") (= (:digest p) (:digest g)) (d/refuse "same-digest")
        (and (= "spec-grew" (:reason p)) (not (:additions-only p))) (d/refuse "spec-grew-not-additions-only")
        (str/blank? (:note p)) (d/refuse "empty-note")
        (not= (current-digest (:scenario g)) (:scenario-digest p)) (d/refuse "stale-scenario-digest")
        :else (c/emit :grader-lifecycle/instrument-golden-moved (assoc p :from-digest (:digest g) :to-digest (:digest p) :moved-at (d/now)))))))
(defn fold [state e]
  (let [id (:golden-id e) b (d/body e)]
    (if (= :grader-lifecycle/instrument-golden-written (:event/type e)) (assoc (or state {}) id (assoc b :moves []))
      (-> state (update-in [id :moves] conj b) (assoc-in [id :digest] (:to-digest e)) (assoc-in [id :scenario-digest] (:scenario-digest e))))))
