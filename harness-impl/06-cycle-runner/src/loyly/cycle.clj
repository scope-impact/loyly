(ns loyly.cycle
  (:require [loyly.domain :as d] [clojure.string :as str]))
(defn of [ctx id type] (filter #(= id (:cycle-id %)) (d/rows ctx type)))
(defn write-result [ctx type at keys p]
  ((d/record-one type at) ctx (select-keys p keys)))
(defn self-check [ctx p]
  (if (= "passed" (:outcome p))
    (write-result ctx :regeneration-cycle/harness-self-check-passed :checked-at
                  [:cycle-id :cut-id :occasion :attempt :harness-digest :goldens-digest :identity-probe-digest :skipped-checks :grader-digests :budget] p)
    (write-result ctx :regeneration-cycle/harness-self-check-failed :checked-at
                  [:cycle-id :cut-id :occasion :attempt :harness-digest :budget :failing-check :detail :reworded-goldens] p)))
(defn fence [ctx p]
  (cond (empty? (of ctx (:cycle-id p) :regeneration-cycle/harness-self-check-passed)) (d/refusal "self-check-not-passed")
        (seq (:unmatched-paths p)) (d/refusal "fenced-path-matched-nothing")
        (not= 1 (:root-commits p)) (d/refusal "root-commits-not-one")
        (not= 1 (:commits p)) (d/refusal "commits-not-one")
        (seq (:paths-left-in-tree p)) (d/refusal "fenced-path-left-in-tree")
        (seq (:paths-reachable-in-object-graph p)) (d/refusal "fenced-path-reachable-in-object-graph")
        :else (write-result ctx :regeneration-cycle/clean-room-proved :proved-at [:cycle-id :cut-id :clean-room-commit :fenced-paths] p)))
(defn candidate [ctx p]
  (if (seq (of ctx (:cycle-id p) :regeneration-cycle/clean-room-proved))
    (write-result ctx :regeneration-cycle/candidate-regenerated :regenerated-at [:cycle-id :attempt :purpose :candidate-digest :parent-candidate-digest] p)
    (d/refusal "no-clean-room")))
(defn gate [ctx p]
  (let [id (:cycle-id p)
        check (last (of ctx id :regeneration-cycle/harness-self-check-passed))]
    (cond (:seam-answered-before-boot p) (d/refusal "seam-answered-before-boot")
          (not (:identity-probe-present p)) (d/refusal "identity-probe-missing")
          (not= (:identity-probe-digest p) (:identity-probe-digest check)) (d/refusal "identity-probe-not-the-instruments")
          (= "passed" (:outcome p)) (write-result ctx :regeneration-cycle/gate-attempt-passed :passed-at
                                                 [:cycle-id :cut-id :attempt :candidate-digest :boot-budget-seconds :boot-polls :answered-on-poll :identity-probe-result :evidence-root]
                                                 (assoc p :cut-id (:cut-id check)))
          :else (write-result ctx :regeneration-cycle/gate-attempt-failed :failed-at
                              [:cycle-id :attempt :candidate-digest :reason :boot-budget-seconds :boot-polls :evidence-root :detail] p))))
(defn verdicts [ctx p]
  (let [gate (last (of ctx (:cycle-id p) :regeneration-cycle/gate-attempt-passed))]
    (cond (not= (:candidate-digest p) (:candidate-digest gate)) (d/refusal "not-the-gated-candidate")
          (empty? (:verdicts p)) (d/refusal "zero-verdicts")
          (not (some #{(:grader-digest p)} (:approved-grader-digests p))) (d/refusal "grader-not-approved")
          :else (write-result ctx :measurement/item-verdicts-filed :filed-at [:cycle-id :candidate-digest :grader-digest :verdicts] p))))
(defn score [ctx p]
  (let [id (:cycle-id p) gate (last (of ctx id :regeneration-cycle/gate-attempt-passed))
        list (some #(when (= (:cut-id gate) (:cut-id %)) %) (d/rows ctx :measurement/checklist-derived))
        filings (of ctx id :measurement/item-verdicts-filed)
        verdicts (into {} (map (juxt :item-id identity)) (mapcat :verdicts filings))
        items (mapv #(get verdicts (:item-id %) {:item-id (:item-id %) :verdict "fail" :reason "unreached" :evidence "unreached"}) (:items list))]
    (if (empty? filings) (d/refusal "zero-verdicts")
        ((d/record-one :measurement/measurement-filed :filed-at)
         ctx (merge (select-keys list [:cut-id :selection-id :checklist-digest :total])
                    {:cycle-id id :candidate-digest (:candidate-digest gate) :grader-digests (vec (distinct (map :grader-digest filings)))
                     :items items :unreached (count (filter #(= "unreached" (:reason %)) items))
                     :passes (count (filter #(= "pass" (:verdict %)) items)) :superseded []})))))
(def handlers {:regeneration-cycle/record-harness-self-check self-check :regeneration-cycle/prove-clean-room fence
               :regeneration-cycle/record-regenerated-candidate candidate
               :regeneration-cycle/install-candidate-in-gate-room (d/record-one :regeneration-cycle/candidate-installed-in-gate-room :installed-at)
               :regeneration-cycle/record-gate-refusal (d/record-one :regeneration-cycle/gate-attempt-refused :refused-at)
               :regeneration-cycle/record-gate-attempt gate :measurement/file-item-verdicts verdicts :measurement/file-score score
               :regeneration-cycle/pass-cycle (fn [ctx p] ((d/record-one :regeneration-cycle/cycle-passed :concluded-at)
                                                          ctx (assoc p :candidate-digest (:candidate-digest (last (of ctx (:cycle-id p) :regeneration-cycle/gate-attempt-passed))))))
               :regeneration-cycle/fail-cycle (d/record-one :regeneration-cycle/cycle-failed :concluded-at)
               :cycle-runner/conclude-run (fn [ctx p] ((d/record-one :cycle-runner/run-concluded :concluded-at) ctx
                                                     (assoc p :cycle-id (:cycle-id (some #(when (= (:run-id p) (:run-id %)) %) (d/rows ctx :cycle-runner/run-started))))))})
