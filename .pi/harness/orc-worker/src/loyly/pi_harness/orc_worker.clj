(ns loyly.pi-harness.orc-worker
  "Compatibility façade. New code should use loyly.pi-harness.interface."
  (:require [loyly.pi-harness.core.worker :as worker]
            [loyly.pi-harness.entrypoint :as entrypoint]))

;; Runtime compatibility
(def start-runtime! worker/start-runtime!)
(def ensure-runtime! worker/ensure-runtime!)
(def runtime-context worker/runtime-context)
(def stop-runtime! worker/stop-runtime!)

;; Public operations
(def prepare-cut! worker/prepare-cut!)
(def submit-approved-slice! worker/submit-approved-slice!)
(def wait! worker/wait!)
(def optimize-builder-prompt! worker/optimize-builder-prompt!)
(def start-external-prompt-optimization! worker/start-external-prompt-optimization!)
(def submit-external-prompt-candidate! worker/submit-external-prompt-candidate!)
(def respond! worker/respond!)
(def workflow worker/workflow)
(def cut-workflow worker/cut-workflow)

;; Executor compatibility for downstream tests and extensions.
(def project-root worker/project-root)
(def cuts-root worker/cuts-root)
(def project-adapter worker/project-adapter)
(def builder-id worker/builder-id)
(def calibrate-harness! worker/calibrate-harness!)
(def run-builder-adapter worker/run-builder-adapter)
(def run-grader-adapter worker/run-grader-adapter)
(def run-oracle worker/run-oracle)
(def product-oracle-options worker/product-oracle-options)
(def evaluate-prompt worker/evaluate-prompt)
(def emit-cycle-observation! worker/emit-cycle-observation!)
(def emit-grader-observations! worker/emit-grader-observations!)
(def prepare-cut-stage worker/prepare-cut-stage)
(def cut-stage worker/cut-stage)
(def harness-snapshot-stage worker/harness-snapshot-stage)
(def oracle-stage worker/oracle-stage)
(def builder-stage worker/builder-stage)
(def instrument-stage worker/instrument-stage)
(def verify-stage worker/verify-stage)
(def repair-verification-stage worker/repair-verification-stage)
(def affordance-conformance-stage worker/affordance-conformance-stage)
(def grade-stage worker/grade-stage)
(def instrument-conformance-stage worker/instrument-conformance-stage)
(def repair-evaluation-stage worker/repair-evaluation-stage)
(def conclude-stage worker/conclude-stage)

(defn -main [& args]
  (apply entrypoint/-main args))
