(ns loyly.pi-harness.test-runner
  (:require [clojure.test :as test]
            [loyly.pi-harness.allure-evidence-test]
            [loyly.pi-harness.compose-backend-test]
            [loyly.pi-harness.cycle-measurement-test]
            [loyly.pi-harness.entrypoint-test]
            [loyly.pi-harness.harness-calibration-test]
            [loyly.pi-harness.independent-grader-test]
            [loyly.pi-harness.instrument-oracle-test]
            [loyly.pi-harness.model-resolver-test]
            [loyly.pi-harness.observation-test]
            [loyly.pi-harness.oracle-test]
            [loyly.pi-harness.orc-worker-test]
            [loyly.pi-harness.prompt-evaluation-test]
            [loyly.pi-harness.selection-registry-test]
            [loyly.pi-harness.slice-selection-test]))

(def test-namespaces
  '[loyly.pi-harness.allure-evidence-test
    loyly.pi-harness.compose-backend-test
    loyly.pi-harness.cycle-measurement-test
    loyly.pi-harness.entrypoint-test
    loyly.pi-harness.harness-calibration-test
    loyly.pi-harness.independent-grader-test
    loyly.pi-harness.instrument-oracle-test
    loyly.pi-harness.model-resolver-test
    loyly.pi-harness.observation-test
    loyly.pi-harness.oracle-test
    loyly.pi-harness.orc-worker-test
    loyly.pi-harness.prompt-evaluation-test
    loyly.pi-harness.selection-registry-test
    loyly.pi-harness.slice-selection-test])

(defn -main [& _]
  (let [result (apply test/run-tests test-namespaces)
        exit-code (if (zero? (+ (:fail result) (:error result))) 0 1)]
    (shutdown-agents)
    (System/exit exit-code)))
