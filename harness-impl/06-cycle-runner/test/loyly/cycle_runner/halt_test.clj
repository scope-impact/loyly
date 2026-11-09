(ns loyly.cycle-runner.halt-test
  "Rule: A run that cannot perform an effect halts, loudly, and a run that
   ended stays as it ended."
  (:require [clojure.test :refer [deftest is testing]]
            [loyly.cycle-runner.harness :as h]))

(deftest item-06-033-a-context-that-stops-answering-halts-the-run
  (h/with-product
    (let [w (h/world! "06-033" {})]
      (swap! h/script assoc-in ["measurement" "measurement/derive-checklist"] :exit)
      (h/start-run w)
      (let [run (h/progress "R-1")]
        (is (= "halted" (:status run)))
        (is (= {:effect "checklist" :reason "context-did-not-respond"} (select-keys (:halt run) [:effect :reason])))
        (is (empty? (h/effect-rows run "checklist")))
        (is (= ["stamp" "pin"] (h/effects-of run)))))))

(deftest item-06-034-a-run-that-ended-stays-as-it-ended
  (h/with-product
    (let [w1 (h/world! "06-034-a" {:fenced "impl harness/nowhere"})
          _ (h/start-run w1)
          r1 (h/progress "R-1")
          w2 (h/world! "06-034-b" {})
          _ (swap! h/script assoc-in ["measurement" "measurement/derive-checklist"] :exit)
          _ (h/start-run w2 :run-id "R-2" :cycle-id "C-2" :cut-id "K-2")
          r2 (h/progress "R-2")]
      (is (= "failed" (:status r1)))
      (is (= "fence" (get-in r1 [:cycle :failure :stage])))
      (is (= "halted" (:status r2)))
      (is (= "checklist" (get-in r2 [:halt :effect])))
      (Thread/sleep 5000)
      (is (= r1 (h/progress "R-1")))
      (is (= r2 (h/progress "R-2")))
      (is (= [] (get-in (h/rpc "cycle-runner/runs-to-advance" {}) [:result :run-ids]))))))

(deftest item-06-035-reading-a-runs-progress-changes-nothing
  (h/with-product
    (let [w (h/world! "06-035" {})
          _ (h/start-run w)
          a (h/rpc "cycle-runner/run-progress" {:run-id "R-1"})
          b (h/rpc "cycle-runner/run-progress" {:run-id "R-1"})]
      (is (= (:result a) (:result b)))
      (is (= "awaiting-verdicts" (get-in b [:result :run :status])))
      (is (= [] (get-in (h/rpc "cycle-runner/runs-to-advance" {}) [:result :run-ids]))))))
