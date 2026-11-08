(ns loyly.cycle-runner.run-start-test
  "Rule: A run starts once, one at a time, on a commit of the project repository, with its
   five contexts responding."
  (:require [clojure.test :refer [deftest is testing]]
            [loyly.cycle-runner.fixtures :as f]
            [loyly.cycle-runner.standins :as standins]))

(deftest item-06-001-a-run-performs-the-cycles-effects-up-to-the-gates-verdict
  (let [w (f/world)]
    (f/with-runner [sys w]
      (let [resp (f/start! sys w)
            run (f/progress sys "R-1")]
        (is (= "cycle-runner/run-started" (get-in resp [:result :events 0 :type])) (pr-str resp))
        (is (= "awaiting-verdicts" (:status run)) (pr-str (:halt run)))
        (is (= ["stamp" "pin" "checklist" "self-check" "fence" "regenerate" "install" "self-check" "gate"]
               (f/effects run)))
        (is (= [1 2 3 4 5 6 7 8 9] (mapv :position (:effect-results run))))
        (is (every? #(= "recorded" (:outcome %)) (:effect-results run)))
        (is (= ["regeneration-cycle/gate-attempt-passed"] (f/event-types (last (:effect-results run)))))))))

(deftest item-06-002-a-run-identity-is-started-once
  (let [w (f/world :fenced "impl harness/nothing-here")]
    (f/with-runner [sys w]
      (f/start! sys w)
      (is (= "failed" (:status (f/progress sys "R-1"))))
      (is (= "fence" (get-in (f/progress sys "R-1") [:cycle :failure :stage])))
      (let [resp (f/start! sys w :cycle-id "C-2")
            runs (get-in (f/call sys "cycle-runner/run-progress" {}) [:result :runs])]
        (is (= "run-already-started" (f/refusal resp)))
        (is (= [["R-1" "C-1"]] (mapv (juxt :run-id :cycle-id) runs)))))))

(deftest item-06-003-a-second-run-is-refused-while-the-first-awaits-its-verdicts
  (let [w (f/world)]
    (f/with-runner [sys w]
      (f/start! sys w)
      (is (= "awaiting-verdicts" (:status (f/progress sys "R-1"))))
      (let [launches (count (f/builder-log (:builder w)))
            resp (f/start! sys w :run-id "R-2" :cycle-id "C-2")]
        (is (= "run-in-progress" (f/refusal resp)))
        (is (nil? (f/progress sys "R-2")))
        (is (= "awaiting-verdicts" (:status (f/progress sys "R-1"))))
        (is (= launches (count (f/builder-log (:builder w)))))))))

(deftest item-06-004-a-cycle-is-run-once
  (let [w (f/world)]
    (f/with-runner [sys w]
      (f/start! sys w)
      (f/hand-in! sys "R-1" (f/verdicts-file! w "v.json" "[{\"id\":\"01-001\",\"verdict\":\"pass\",\"evidence\":\"ok\"},{\"id\":\"01-002\",\"verdict\":\"pass\",\"evidence\":\"ok\"}]") "r-1")
      (is (= "passed" (:status (f/progress sys "R-1"))))
      (let [resp (f/start! sys w :run-id "R-2")]
        (is (= "cycle-already-run" (f/refusal resp)))
        (is (nil? (f/progress sys "R-2")))))))

(deftest item-06-005-a-run-of-something-that-is-not-a-commit-is-refused
  (let [w (f/world)]
    (f/with-runner [sys w]
      (let [resp (f/start! sys w :commit "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef")]
        (is (= -32000 (get-in resp [:error :code])))
        (is (= "not-a-commit" (f/refusal resp)))
        (is (nil? (f/progress sys "R-1")))
        (is (empty? (f/builder-log (:builder w))) "no builder was launched")
        (is (empty? @standins/handed) "the contexts were not asked")))))

(deftest item-06-006-a-run-whose-context-does-not-respond-is-refused
  (let [w (f/world :contexts {"measurement" "#!/bin/bash\nexit 0\n"})]
    (f/with-runner [sys w]
      (let [resp (f/start! sys w)]
        (is (= "contexts-not-responding" (f/refusal resp)))
        (is (= ["measurement"] (get-in resp [:error :data :contexts])))
        (is (nil? (f/progress sys "R-1")))))))
