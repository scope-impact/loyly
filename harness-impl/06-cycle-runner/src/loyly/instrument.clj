(ns loyly.instrument
  (:require [clojure.data.json :as json] [clojure.java.io :as io] [clojure.string :as str]
            [loyly.domain :as d] [loyly.cycle :as cycle]))
(defn fact-file [sys key]
  (let [f (get ((:environment sys)) key)] (when (and f (.isFile (io/file f))) (json/read-str (slurp f)))))
(defn write-golden [sys {:keys [golden-id scenario scenario-digest] :as p}]
  (cond (some #(= golden-id (:golden-id %)) (d/rows sys :grader-lifecycle/instrument-golden-written)) (d/refusal "golden-exists")
        (not= scenario-digest (get (fact-file sys "LOYLY_SCENARIO_DIGESTS") scenario)) (d/refusal "scenario-digest-mismatch")
        :else ((d/record-one :grader-lifecycle/instrument-golden-written :written-at) sys p)))
(defn move-golden [sys {:keys [golden-id digest reason additions-only note scenario-digest] :as p}]
  (let [written (some #(when (= golden-id (:golden-id %)) %) (d/rows sys :grader-lifecycle/instrument-golden-written))
        moves (filter #(= golden-id (:golden-id %)) (d/rows sys :grader-lifecycle/instrument-golden-moved))
        old (or (:to-digest (last moves)) (:digest written))]
    (cond (nil? written) (d/refusal "no-such-golden") (= old digest) (d/refusal "same-digest")
          (and (= "spec-grew" reason) (not additions-only)) (d/refusal "spec-grew-not-additions-only")
          (str/blank? note) (d/refusal "empty-note")
          (not= scenario-digest (get (fact-file sys "LOYLY_SCENARIO_DIGESTS") (:scenario written))) (d/refusal "stale-scenario-digest")
          :else ((d/record-one :grader-lifecycle/instrument-golden-moved :moved-at) sys (assoc (dissoc p :digest) :from-digest old :to-digest digest)))))
(defn rate [n total]
  (if (zero? total) "n/a" (str (.toPlainString (.divide (bigdec (* 100 n)) (bigdec total) 1 java.math.RoundingMode/HALF_EVEN)) "%")))
(defn report [sys {:keys [cycle-id]}]
  (let [receipts (d/rows sys :measurement/measurement-filed)
        row (some #(when (= cycle-id (:cycle-id %)) %) receipts)
        previous (last (filter #(= (:selection-id row) (:selection-id %)) (take-while #(not= cycle-id (:cycle-id %)) receipts)))
        items #(into {} (map (juxt :item-id :verdict)) (:items %)) a (items previous) b (items row)
        shared (filterv #(contains? a %) (map :item-id (:items row)))
        added (filterv #(not (contains? a %)) (map :item-id (:items row)))
        dropped (filterv #(not (contains? b %)) (map :item-id (:items previous)))
        passes (fn [m] (count (filter #(= "pass" (m %)) shared)))
        changed (fn [v] (filterv #(and (= v (b %)) (not= v (a %))) shared))]
    (cond (nil? row) (d/refusal "no-measurement") (seq (cycle/of sys cycle-id :measurement/scoreboard-reported)) (d/refusal "scoreboard-already-reported")
          :else {:command-result/events
                 (cond-> [(d/event :measurement/scoreboard-reported
                                   (cond-> (merge (select-keys row [:cycle-id :selection-id :total :passes])
                                                  {:rate (rate (:passes row) (:total row)) :previous-cycle-id (:cycle-id previous)
                                                   :previous-selection-id (:selection-id previous) :new-items added :dropped-items dropped :reported-at (d/now)})
                                     previous (assoc :shared {:items (count shared) :previous-passes (passes a) :passes (passes b)
                                                              :previous-rate (rate (passes a) (count shared)) :rate (rate (passes b) (count shared))
                                                              :newly-passing (changed "pass") :newly-failing (changed "fail")})))]
                   (and previous (or (seq added) (seq dropped)))
                   (conj (d/event :measurement/moved-item-set-restated {:cycle-id cycle-id :previous-cycle-id (:cycle-id previous)
                                                                      :previous-total (:total previous) :total (:total row)
                                                                      :added (count added) :dropped (count dropped) :restated-at (d/now)})))})))
(def handlers {:grader-lifecycle/write-instrument-golden write-golden :grader-lifecycle/move-instrument-golden move-golden
               :measurement/report-scoreboard report :measurement/fail-score-stage (d/record-one :measurement/score-stage-failed :failed-at)})
