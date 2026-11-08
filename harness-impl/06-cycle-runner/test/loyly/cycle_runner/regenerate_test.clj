(ns loyly.cycle-runner.regenerate-test
  "Rule: A regenerate launches one fresh builder and records what it left in its candidate
   scope. Rule: A failed gate attempt is repaired by a fresh builder that sees only the
   previous candidate and the gate's evidence."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [loyly.cycle-runner.effects :as fx]
            [loyly.cycle-runner.fixtures :as f]))

(defn- red [w name] (f/candidate! (str (:base w) "/" name) :suite-exit 1))

(deftest item-06-014-each-regenerate-launches-one-builder-and-waits-for-it
  (let [base (f/temp-dir "cands")
        w (f/world :candidates [(f/candidate! (str base "/red") :suite-exit 1) (f/candidate! (str base "/good"))])]
    (f/with-runner [sys w]
      (f/start! sys w)
      (let [run (f/progress sys "R-1")]
        (is (= "awaiting-verdicts" (:status run)))
        (is (= ["start 1" "end 1" "start 2" "end 2"] (f/builder-log (:builder w)))
            "launched twice; the second began after the first had ended")
        (is (= 1 (count (f/results-of run "regenerate"))))
        (is (= 1 (count (f/results-of run "repair"))))))))

(deftest item-06-015-a-builder-that-exits-non-zero-but-leaves-a-candidate-is-gated
  (let [base (f/temp-dir "cands")
        good (f/candidate! (str base "/good"))
        w (f/world :candidates [good] :builder-opts {:exit 1})]
    (f/with-runner [sys w]
      (f/start! sys w)
      (let [run (f/progress sys "R-1")
            [rg] (f/results-of run "regenerate")]
        (is (= "recorded" (:outcome rg)))
        (is (= (fx/candidate-digest good) (get-in rg [:events 0 :candidate-digest])))
        (is (= "awaiting-verdicts" (:status run)))))))

(deftest item-06-016-a-builder-that-leaves-nothing-fails-the-cycle-at-regenerate
  (let [w (f/world :builder-opts {:leave-empty true})]
    (f/with-runner [sys w]
      (f/start! sys w)
      (let [run (f/progress sys "R-1")]
        (is (empty? (f/results-of run "regenerate")))
        (is (= "recorded" (:outcome (first (f/results-of run "fail-cycle")))))
        (is (= "failed" (:status run)))
        (is (= ["regenerate" "candidate-not-regenerated"] ((juxt :stage :reason) (get-in run [:cycle :failure]))))))))

(deftest item-06-017-a-builder-that-outlives-the-regenerate-timeout-is-stopped
  (let [w (f/world :builder-opts {:sleep 30})]
    (f/with-runner [sys w]
      (let [t0 (System/currentTimeMillis)]
        (f/start! sys w :budget (f/budget-with :regenerate 2))
        (is (< (- (System/currentTimeMillis) t0) 25000) "the run did not wait for the builder")
        (Thread/sleep 1500)
        (is (= ["start 1"] (f/builder-log (:builder w))) "the stub builder was stopped")
        (let [run (f/progress sys "R-1")]
          (is (= "failed" (:status run)))
          (is (= ["regenerate" "budget"] ((juxt :stage :reason) (get-in run [:cycle :failure])))))))))

(deftest item-06-023-a-repair-builder-sees-the-previous-candidate-and-the-gate-evidence-read-only
  (let [base (f/temp-dir "cands")
        w (f/world :candidates [(f/candidate! (str base "/red") :suite-exit 1) (f/candidate! (str base "/good"))])]
    (f/with-runner [sys w]
      (f/start! sys w)
      (let [run (f/progress sys "R-1")
            [rp] (f/results-of run "repair")
            [gate1] (filter #(= 1 (:attempt %)) (f/results-of run "gate"))
            d (get-in w [:builder :dir])
            paths (set (str/split-lines (slurp (io/file d "paths-2.txt"))))
            mounts (slurp (io/file d "mounts-2.txt"))]
        (is (= "recorded" (:outcome rp)))
        (is (= 2 (:attempt rp)))
        (is (= ["regeneration-cycle/gate-attempt-failed"] (f/event-types gate1)))
        (is (str/includes? (:detail rp) "gate evidence of attempt 1"))
        (is (str/includes? (:detail rp) (get-in gate1 [:events 0 :evidence-root])))
        (testing "the second launch saw the clean room, the previous candidate and the gate evidence"
          (is (contains? paths "./docs/plan/01-booking-core/spec.feature"))
          (is (contains? paths "./.repair/previous-candidate/test.sh"))
          (is (contains? paths "./.repair/gate-evidence/attempt-1/gate.log"))
          (is (str/includes? (slurp (io/file d "instruction-2.md")) "gate evidence of attempt 1")))
        (testing "and could write neither"
          (is (str/includes? mounts "read-only") mounts)
          (is (not (str/includes? mounts "writable")) mounts))))))

(deftest item-06-024-three-failed-attempts-fail-the-cycle-and-no-fourth-builder-is-launched
  (let [base (f/temp-dir "cands")
        w (f/world :candidates [(f/candidate! (str base "/red") :suite-exit 1)])]
    (f/with-runner [sys w]
      (f/start! sys w)
      (let [run (f/progress sys "R-1")]
        (is (= 3 (count (filter #(str/starts-with? % "start") (f/builder-log (:builder w))))))
        (is (= "failed" (:status run)))
        (is (= ["gate" "gate-attempts-exhausted"] ((juxt :stage :reason) (get-in run [:cycle :failure]))))))))
