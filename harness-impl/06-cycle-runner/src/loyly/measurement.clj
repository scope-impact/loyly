(ns loyly.measurement (:require [loyly.domain :as d] [loyly.cycle :as cycle] [clojure.string :as str] [clojure.set :as set]))
(defn rate [passes total] (if (zero? total) "n/a" (str (.setScale (.divide (bigdec (* 100 passes)) (bigdec total) 12 java.math.RoundingMode/HALF_EVEN) 1 java.math.RoundingMode/HALF_EVEN))))
(defn receipt [ctx c] (d/row ctx :measurement/cycle-measurements :cycle-id c))
(defn command [id ctx]
  (let [p (:command ctx) c (:cycle-id p) gated (d/row ctx :measurement/gated-cycles :cycle-id c)
        checklist (when gated (d/row ctx :measurement/checklists :cut-id (:cut-id gated))) r (receipt ctx c)]
    (case id
      :measurement/file-item-verdicts
      (cond
        (or (not gated) (not= (:candidate-digest gated) (:candidate-digest p))) (d/refuse "not-the-gated-candidate")
        (not checklist) (d/refuse "no-checklist")
        (empty? (:verdicts p)) (d/refuse "zero-verdicts")
        (some #(not (#{"pass" "fail"} (:verdict %))) (:verdicts p)) (d/refuse "bad-verdict-value")
        (some #(not (contains? (set (map :item-id (:items checklist))) (:item-id %))) (:verdicts p)) (d/refuse "unknown-item")
        (= "filed" (:status r)) (d/refuse "already-filed")
        (= "score-stage-failed" (:status r)) (d/refuse "stage-failed")
        (not (some #{(:grader-digest p)} (:approved-grader-digests p))) (d/refuse "grader-not-approved")
        (some #(and (:reason %) (not (#{"inconclusive" "outage"} (:reason %)))) (:verdicts p)) (d/refuse "bad-verdict-reason")
        :else (cycle/emit :measurement/item-verdicts-filed (assoc p :filed-at (d/now) :verdicts (mapv #(merge {:evidence ""} %) (:verdicts p)))))
      :measurement/file-score
      (let [filings (filter #(= c (:cycle-id %)) (d/rows ctx :measurement/item-verdicts))
            verdicts (mapcat :verdicts filings) last-verdict (into {} (map (juxt :item-id identity) verdicts))]
        (cond
          (not gated) (d/refuse "not-the-gated-candidate")
          (empty? verdicts) (d/refuse "zero-verdicts")
          (= "filed" (:status r)) (d/refuse "already-filed")
          (= "score-stage-failed" (:status r)) (d/refuse "stage-failed")
          (some #(not (some #{(:grader-digest %)} (:approved-grader-digests p))) filings) (d/refuse "grader-not-approved")
          :else (let [items (mapv (fn [item] (or (get last-verdict (:item-id item)) {:item-id (:item-id item) :verdict "fail" :reason "unreached" :evidence ""})) (:items checklist))]
            (cycle/emit :measurement/measurement-filed {:cycle-id c :cut-id (:cut-id gated) :selection-id (:selection-id checklist)
              :candidate-digest (:candidate-digest gated) :checklist-digest (:checklist-digest checklist) :grader-digests (vec (distinct (map :grader-digest filings)))
              :total (:total checklist) :passes (count (filter #(= "pass" (:verdict %)) items)) :unreached (count (filter #(= "unreached" (:reason %)) items))
              :items items :superseded [] :filed-at (d/now)}))))
      :measurement/fail-score-stage
      (cond (not gated) (d/refuse "not-the-gated-candidate") r (d/refuse "already-filed")
        :else (cycle/emit :measurement/score-stage-failed (assoc p :failed-at (d/now))))
      :measurement/report-scoreboard
      (cond
        (not= "filed" (:status r)) (d/refuse "no-measurement")
        (d/row ctx :measurement/scoreboards :cycle-id c) (d/refuse "already-reported")
        :else
        (let [previous (last (sort-by :filed-at (filter #(and (= "filed" (:status %)) (= (:selection-id r) (:selection-id %)) (not= c (:cycle-id %)) (neg? (compare (:filed-at %) (:filed-at r)))) (d/rows ctx :measurement/cycle-measurements))))
              items (into {} (map (juxt :item-id :verdict) (:items r))) prior (into {} (map (juxt :item-id :verdict) (:items previous)))
              shared (set/intersection (set (keys items)) (set (keys prior)))
              added (vec (sort (set/difference (set (keys items)) (set (keys prior)))))
              dropped (vec (sort (set/difference (set (keys prior)) (set (keys items)))))
              passes (count (filter #(= "pass" (items %)) shared)) previous-passes (count (filter #(= "pass" (prior %)) shared))
              report (cond-> {:cycle-id c :selection-id (:selection-id r) :passes (:passes r) :total (:total r)
                :rate (rate (:passes r) (:total r)) :previous-cycle-id (:cycle-id previous) :previous-selection-id (:selection-id previous)
                :new-items added :dropped-items dropped :reported-at (d/now)}
                previous (assoc :shared {:items (count shared) :previous-passes previous-passes :passes passes
                  :previous-rate (rate previous-passes (count shared)) :rate (rate passes (count shared))
                  :newly-passing (vec (sort (filter #(and (= "fail" (prior %)) (= "pass" (items %))) shared)))
                  :newly-failing (vec (sort (filter #(and (= "pass" (prior %)) (= "fail" (items %))) shared)))}))]
          {:command-result/events (vec (concat
            (when (and previous (or (seq added) (seq dropped))) [(d/event :measurement/moved-item-set-restated {:cycle-id c :previous-cycle-id (:cycle-id previous) :previous-total (:total previous) :total (:total r) :added (count added) :dropped (count dropped) :restated-at (d/now)})])
            [(d/event :measurement/scoreboard-reported report)]))})))))
(defn fold [id state e]
  (let [s (or state {}) b (d/body e) c (:cycle-id e)]
    (case id
      :measurement/item-verdicts (assoc s (:event/id e) b)
      :measurement/cycle-measurements (assoc s c (assoc b :status (if (= :measurement/measurement-filed (:event/type e)) "filed" "score-stage-failed")))
      :measurement/scoreboards (if (= :measurement/moved-item-set-restated (:event/type e)) (assoc-in s [c :restatement] b) (assoc s c (assoc b :restatement (get-in s [c :restatement]))))
      (assoc s c b))))
