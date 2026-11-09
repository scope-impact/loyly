(ns loyly.cycle-runner.start-test
  "Rule: A run starts once, one at a time, on a commit of the project
   repository, with its five contexts responding."
  (:require [clojure.test :refer [deftest is testing]]
            [loyly.cycle-runner.harness :as h]))

(deftest item-06-001-a-run-performs-the-cycles-effects-up-to-the-gates-verdict
  (h/with-product
    (let [w (h/world! "06-001" {})
          r (h/start-run w)
          run (h/progress "R-1")]
      (is (= "cycle-runner/run-started" (get-in r [:result :events 0 :type])) (pr-str r))
      (is (= "awaiting-verdicts" (:status run)) (pr-str run))
      (is (= ["stamp" "pin" "checklist" "self-check" "fence" "regenerate" "install" "self-check" "gate"]
             (h/effects-of run)))
      (is (= (range 1 10) (map :position (:effect-results run))))
      (is (every? #(= "recorded" (:outcome %)) (:effect-results run)))
      (is (= ["regeneration-cycle/gate-attempt-passed"] (h/event-types (last (:effect-results run))))))))

(defn- fail-at-fence-world [name]
  (h/world! name {:fenced "impl harness/nothing-here"}))

(deftest item-06-002-a-run-identity-is-started-once
  (h/with-product
    (let [w (fail-at-fence-world "06-002")]
      (h/start-run w)
      (is (= "failed" (:status (h/progress "R-1"))))
      (is (= "fence" (get-in (h/progress "R-1") [:cycle :failure :stage])))
      (let [r (h/start-run w :cycle-id "C-2")
            runs (get-in (h/rpc "cycle-runner/run-progress" {}) [:result :runs])]
        (is (= "run-already-started" (get-in r [:error :data :refusal])))
        (is (= -32000 (get-in r [:error :code])))
        (is (= [["R-1" "C-1"]] (mapv (juxt :run-id :cycle-id) runs)))))))

(deftest item-06-003-a-second-run-is-refused-while-the-first-awaits-its-verdicts
  (h/with-product
    (let [w (h/world! "06-003" {})]
      (h/start-run w)
      (is (= "awaiting-verdicts" (:status (h/progress "R-1"))))
      (let [launched (h/launch-count (:builder-dir w))
            r (h/start-run w :run-id "R-2" :cycle-id "C-2")]
        (is (= "run-in-progress" (get-in r [:error :data :refusal])))
        (is (nil? (h/progress "R-2")))
        (is (= "awaiting-verdicts" (:status (h/progress "R-1"))))
        (is (= launched (h/launch-count (:builder-dir w))))))))

(deftest item-06-004-a-cycle-is-run-once
  (h/with-product
    (let [w (h/world! "06-004" {})]
      (h/start-run w)
      (h/hand-in "R-1" (h/verdicts-file! w "v.json" [{:id "01-001" :verdict "pass" :evidence "ok"}
                                                     {:id "01-002" :verdict "pass" :evidence "ok"}]))
      (is (= "passed" (:status (h/progress "R-1"))))
      (let [r (h/start-run w :run-id "R-2" :cycle-id "C-1")]
        (is (= "cycle-already-run" (get-in r [:error :data :refusal])))
        (is (nil? (h/progress "R-2")))))))

(deftest item-06-005-a-run-of-something-that-is-not-a-commit-is-refused
  (h/with-product
    (let [w (h/world! "06-005" {})
          r (h/start-run w :commit "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef")]
      (is (= "not-a-commit" (get-in r [:error :data :refusal])))
      (is (nil? (h/progress "R-1")))
      (is (= 0 (h/launch-count (:builder-dir w))))
      (is (empty? (filter #(= "specification" (:context %)) (loyly.cycle-runner.standins/standins)))
          "the contexts were not asked"))))

(deftest item-06-006-a-run-whose-context-does-not-respond-is-refused
  (h/with-product
    (let [w (h/world! "06-006" {:exiting ["measurement"]})
          r (h/start-run w)]
      (is (= "contexts-not-responding" (get-in r [:error :data :refusal])))
      (is (= ["measurement"] (get-in r [:error :data :contexts])))
      (is (nil? (h/progress "R-1"))))))
