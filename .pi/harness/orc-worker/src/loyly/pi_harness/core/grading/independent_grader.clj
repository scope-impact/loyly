(ns loyly.pi-harness.core.grading.independent-grader
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

(defn- failed [message verdicts]
  {:passed false
   :exit-code 1
   :total (count verdicts)
   :passed-count (count (filter #(= "pass" (:verdict %)) verdicts))
   :verdicts verdicts
   :stdout ""
   :stderr message})

(defn interpret-verdicts [expected-item-ids output]
  (let [parsed (try
                 (json/read-str (or output "") :key-fn keyword)
                 (catch Exception _ ::invalid-json))]
    (cond
      (= ::invalid-json parsed)
      (failed "independent grader returned invalid JSON" [])

      (not (vector? parsed))
      (failed "independent grader must return a list of verdicts" [])

      (empty? parsed)
      (failed "independent grader graded zero items" [])

      :else
      (let [verdicts (mapv (fn [verdict]
                             {:item-id (:itemId verdict)
                              :verdict (:verdict verdict)
                              :evidence (:evidence verdict)})
                           parsed)
            expected (set expected-item-ids)
            ids (mapv :item-id verdicts)
            unknown (seq (remove expected ids))
            duplicate (some (fn [[item-id count]] (when (> count 1) item-id))
                            (frequencies ids))
            missing (seq (remove (set ids) expected-item-ids))
            invalid (some #(when-not (#{"pass" "fail"} (:verdict %)) (:item-id %)) verdicts)
            no-evidence (some #(when (str/blank? (:evidence %)) (:item-id %)) verdicts)
            failed-items (filter #(= "fail" (:verdict %)) verdicts)]
        (cond
          unknown (failed (str "independent grader returned unknown item " (first unknown)) verdicts)
          duplicate (failed (str "independent grader returned duplicate item " duplicate) verdicts)
          missing (failed (str "independent grader did not reach item " (first missing)) verdicts)
          invalid (failed (str "independent grader returned invalid verdict for " invalid) verdicts)
          no-evidence (failed (str "independent grader returned missing evidence for " no-evidence) verdicts)
          (seq failed-items) (failed (str/join "\n" (map #(str (:item-id %) " failed: " (:evidence %)) failed-items)) verdicts)
          :else {:passed true
                 :exit-code 0
                 :total (count verdicts)
                 :passed-count (count verdicts)
                 :verdicts verdicts
                 :stdout (str "independent grader: " (count verdicts) "/" (count verdicts) " passed\n")
                 :stderr ""})))))
