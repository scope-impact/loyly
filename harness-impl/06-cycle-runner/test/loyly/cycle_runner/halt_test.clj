(ns loyly.cycle-runner.halt-test
  "Rule: A run that cannot perform an effect halts, loudly, and a run that ended stays as it
   ended."
  (:require [clojure.test :refer [deftest is testing]]
            [loyly.cycle-runner.fixtures :as f]
            [loyly.cycle-runner.standins :as standins]))

(deftest item-06-033-a-context-that-stops-answering-halts-the-run
  (let [w (f/world :scripts {"measurement" {:exit-on #{"measurement/derive-checklist"}}})]
    (f/with-runner [sys w]
      (f/start! sys w)
      (let [run (f/progress sys "R-1")]
        (is (= "halted" (:status run)))
        (is (= {:effect "checklist" :reason "context-did-not-respond"} (select-keys (:halt run) [:effect :reason])))
        (is (empty? (f/results-of run "checklist")))))))

(deftest item-06-034-a-run-that-ended-stays-as-it-ended
  (let [w (f/world :fenced "impl harness/nothing-here")]
    (f/with-runner [sys w]
      (f/start! sys w)
      (let [r1 (f/progress sys "R-1")
            t1 (System/currentTimeMillis)]
        (is (= "failed" (:status r1)))
        (is (= "fence" (get-in r1 [:cycle :failure :stage])))
        (reset! standins/scripts {"measurement" {:exit-on #{"measurement/derive-checklist"}}})
        (f/start! sys w :run-id "R-2" :cycle-id "C-2")
        (let [r2 (f/progress sys "R-2")
              t2 (System/currentTimeMillis)]
          (is (= "halted" (:status r2)))
          (is (= "checklist" (get-in r2 [:halt :effect])))
          (Thread/sleep (max 0 (- (+ t1 5000) (System/currentTimeMillis))))
          (is (= r1 (f/progress sys "R-1")))
          (Thread/sleep (max 0 (- (+ t2 5000) (System/currentTimeMillis))))
          (is (= r2 (f/progress sys "R-2"))))))))

(deftest item-06-035-reading-a-runs-progress-changes-nothing
  (let [w (f/world)]
    (f/with-runner [sys w]
      (f/start! sys w)
      (let [a (f/call sys "cycle-runner/run-progress" {:run-id "R-1"})
            b (f/call sys "cycle-runner/run-progress" {:run-id "R-1"})]
        (is (= (:result a) (:result b)))
        (is (= "awaiting-verdicts" (get-in b [:result :run :status])))
        (is (= [] (get-in (f/call sys "cycle-runner/runs-to-advance" {}) [:result :run-ids])))))))
