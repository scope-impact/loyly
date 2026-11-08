(ns loyly.cycle-runner.verdicts-test
  "Rule: The verdicts of the gated candidate are handed in, filed as given, and the run ends as
   its cycle ends."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [loyly.cycle-runner.fixtures :as f]))

(def both "[{\"id\":\"01-001\",\"verdict\":\"pass\",\"evidence\":\"booked\"},{\"id\":\"01-002\",\"verdict\":\"fail\",\"evidence\":\"judged by the main session\"}]")

(defn- awaiting [sys w]
  (f/start! sys w)
  (is (= "awaiting-verdicts" (:status (f/progress sys "R-1")))))

(deftest item-06-025-verdicts-handed-in-are-filed-as-given-and-the-run-passes-as-its-cycle-passes
  (let [w (f/world)]
    (f/with-runner [sys w]
      (awaiting sys w)
      (let [resp (f/hand-in! sys "R-1" (f/verdicts-file! w "v.json" both) "r-1")
            run (f/progress sys "R-1")
            [fv] (f/results-of run "file-verdicts")
            [fs] (f/results-of run "file-score")
            filed (first (filter #(= "measurement/measurement-filed" (:type %)) (:events fs)))]
        (is (= "cycle-runner/verdicts-handed-in" (get-in resp [:result :events 0 :type])) (pr-str resp))
        (is (= 2 (get-in resp [:result :events 0 :verdicts])))
        (is (= "recorded" (:outcome fv)))
        (is (= "recorded" (:outcome fs)))
        (is (= [1 2] ((juxt :passes :total) filed)))
        (is (= [{:item-id "01-001" :verdict "pass" :evidence "booked"}
                {:item-id "01-002" :verdict "fail" :evidence "judged by the main session"}]
               (mapv #(select-keys % [:item-id :verdict :evidence]) (:items filed))))
        (is (= "passed" (:status run)))
        (is (= "passed" (get-in run [:cycle :status])))
        (is (some? (:verdicts-digest run)))))))

(deftest item-06-026-an-item-the-verdicts-file-does-not-name-is-measurements-to-record-as-unreached
  (let [w (f/world)]
    (f/with-runner [sys w]
      (awaiting sys w)
      (f/hand-in! sys "R-1" (f/verdicts-file! w "v.json" "[{\"id\":\"01-001\",\"verdict\":\"pass\"}]") "r-1")
      (let [run (f/progress sys "R-1")
            [fs] (f/results-of run "file-score")
            filed (first (:events fs))
            [fv] (f/results-of run "file-verdicts")]
        (is (= 1 (count (get-in fv [:events 0 :verdicts]))) "the runner filed only what it was handed")
        (is (= {:item-id "01-002" :verdict "fail" :reason "unreached"}
               (select-keys (second (:items filed)) [:item-id :verdict :reason])))
        (is (= [1 2] ((juxt :passes :total) filed)))
        (is (= "passed" (:status run)))))))

(deftest item-06-027-verdicts-for-a-run-that-does-not-exist-are-refused
  (let [w (f/world)]
    (f/with-runner [sys w]
      (let [resp (f/hand-in! sys "R-9" (f/verdicts-file! w "v.json" both) "r-1")]
        (is (= "no-run" (f/refusal resp)))
        (is (nil? (f/progress sys "R-9")))))))

(deftest item-06-028-verdicts-for-a-run-whose-gate-never-passed-are-refused
  (let [base (f/temp-dir "cands")
        w (f/world :candidates [(f/candidate! (str base "/red") :suite-exit 1)])]
    (f/with-runner [sys w]
      (f/start! sys w :budget (f/budget-with :limit 1))
      (is (= "gate-attempts-exhausted" (get-in (f/progress sys "R-1") [:cycle :failure :reason])))
      (let [resp (f/hand-in! sys "R-1" (f/verdicts-file! w "v.json" both) "r-1")
            run (f/progress sys "R-1")]
        (is (= "gate-not-passed" (f/refusal resp)))
        (is (empty? (f/results-of run "file-verdicts")))
        (is (= "failed" (:status run)))))))

(deftest item-06-029-a-second-verdicts-file-is-refused-and-no-builder-follows-the-verdicts
  (let [w (f/world)]
    (f/with-runner [sys w]
      (awaiting sys w)
      (f/hand-in! sys "R-1" (f/verdicts-file! w "v1.json" "[{\"id\":\"09-001\",\"verdict\":\"pass\"}]") "r-1")
      (is (= "score-stage-failed" (get-in (f/progress sys "R-1") [:cycle :failure :reason])))
      (let [resp (f/hand-in! sys "R-1" (f/verdicts-file! w "v2.json" both) "r-1")
            run (f/progress sys "R-1")
            after (drop-while #(not= "file-verdicts" (:effect %)) (:effect-results run))]
        (is (= "verdicts-already-handed-in" (f/refusal resp)))
        (is (seq after))
        (is (not-any? #(#{"regenerate" "repair"} (:effect %)) after))))))

(deftest item-06-030-verdicts-under-a-grader-digest-the-run-did-not-approve-are-refused
  (let [w (f/world)]
    (f/with-runner [sys w]
      (awaiting sys w)
      (let [resp (f/hand-in! sys "R-1" (f/verdicts-file! w "v.json" both) "r-2")]
        (is (= "grader-not-approved" (f/refusal resp)))
        (is (= "awaiting-verdicts" (:status (f/progress sys "R-1"))))))))

(deftest item-06-031-a-verdicts-file-that-is-blank-is-refused-and-nothing-is-filed
  (let [w (f/world)]
    (f/with-runner [sys w]
      (awaiting sys w)
      (let [resp (f/hand-in! sys "R-1" (f/verdicts-file! w "blank.json" "  \n") "r-1")
            run (f/progress sys "R-1")]
        (is (= "verdicts-not-readable" (f/refusal resp)))
        (is (= "awaiting-verdicts" (:status run)))
        (is (empty? (f/results-of run "file-verdicts")))))))

(deftest item-06-032-verdicts-measurement-refuses-fail-the-score-stage-and-the-run
  (let [w (f/world)]
    (f/with-runner [sys w]
      (awaiting sys w)
      (let [launches (count (f/builder-log (:builder w)))
            _ (f/hand-in! sys "R-1" (f/verdicts-file! w "v.json" "[{\"id\":\"01-001\",\"verdict\":\"pass\"},{\"id\":\"09-001\",\"verdict\":\"pass\"}]") "r-1")
            run (f/progress sys "R-1")
            [fv] (f/results-of run "file-verdicts")
            [fss] (f/results-of run "fail-score-stage")]
        (is (= ["refused" "unknown-item" "measurement"] ((juxt :outcome :refusal :context) fv)))
        (is (= "recorded" (:outcome fss)))
        (is (= "failed" (:status run)))
        (is (= ["score" "score-stage-failed"] ((juxt :stage :reason) (get-in run [:cycle :failure]))))
        (is (= launches (count (f/builder-log (:builder w)))) "no builder after the verdicts")))))
