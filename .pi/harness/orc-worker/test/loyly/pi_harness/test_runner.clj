(ns loyly.pi-harness.test-runner
  (:require [clojure.test :as test]
            [loyly.pi-harness.core.runtime.adapters-test]
            [loyly.pi-harness.core.evidence.allure-evidence-test]
            [loyly.pi-harness.core.evidence.evidence-projection-test]
            [loyly.pi-harness.core.grading.compose-backend-test]
            [loyly.pi-harness.grain.cycle-progress-test]
            [loyly.pi-harness.grain.cycle-measurement-test]
            [loyly.pi-harness.grain.candidate-lineage-test]
            [loyly.pi-harness.entrypoint-test]
            [loyly.pi-harness.core.oracle.harness-calibration-test]
            [loyly.pi-harness.core.grading.independent-grader-test]
            [loyly.pi-harness.core.oracle.instrument-oracle-test]
            [loyly.pi-harness.core.runtime.json-process-test]
            [loyly.pi-harness.core.oracle.model-resolver-test]
            [loyly.pi-harness.grain.observation-test]
            [loyly.pi-harness.core.oracle.oracle-test]
            [loyly.pi-harness.orc-worker-test]
            [loyly.pi-harness.core.build.prompt-evaluation-test]
            [loyly.pi-harness.grain.selection-test]
            [loyly.pi-harness.core.selection.slice-selection-test]))

(def test-namespaces
  '[loyly.pi-harness.core.runtime.adapters-test
    loyly.pi-harness.core.evidence.allure-evidence-test
    loyly.pi-harness.core.evidence.evidence-projection-test
    loyly.pi-harness.core.grading.compose-backend-test
    loyly.pi-harness.grain.cycle-progress-test
    loyly.pi-harness.grain.cycle-measurement-test
    loyly.pi-harness.grain.candidate-lineage-test
    loyly.pi-harness.entrypoint-test
    loyly.pi-harness.core.oracle.harness-calibration-test
    loyly.pi-harness.core.grading.independent-grader-test
    loyly.pi-harness.core.oracle.instrument-oracle-test
    loyly.pi-harness.core.runtime.json-process-test
    loyly.pi-harness.core.oracle.model-resolver-test
    loyly.pi-harness.grain.observation-test
    loyly.pi-harness.core.oracle.oracle-test
    loyly.pi-harness.orc-worker-test
    loyly.pi-harness.core.build.prompt-evaluation-test
    loyly.pi-harness.grain.selection-test
    loyly.pi-harness.core.selection.slice-selection-test])

(defn -main [& _]
  (let [result (apply test/run-tests test-namespaces)
        exit-code (if (zero? (+ (:fail result) (:error result))) 0 1)]
    (shutdown-agents)
    (System/exit exit-code)))
