(ns loyly.pi-harness.interface
  "Public API for the ORC regeneration harness.

   Core and workflow namespaces are implementation details. Entrypoints and
   consumers should depend on this namespace, matching ORC's component style."
  (:require [loyly.pi-harness.core.worker :as worker]
            [loyly.pi-harness.workflows.cut :as cut]
            [loyly.pi-harness.workflows.regeneration :as regeneration]))

(def start-runtime! worker/start-runtime!)
(def ensure-runtime! worker/ensure-runtime!)
(def runtime-context worker/runtime-context)
(def stop-runtime! worker/stop-runtime!)

(def prepare-cut! worker/prepare-cut!)
(def submit! worker/submit!)
(def start-cycle! worker/start-cycle!)
(def execution-status! worker/execution-status!)
(def wait! worker/wait!)

(def optimize-builder-prompt! worker/optimize-builder-prompt!)
(def start-external-prompt-optimization! worker/start-external-prompt-optimization!)
(def submit-external-prompt-candidate! worker/submit-external-prompt-candidate!)

(def cut-workflow cut/workflow)
(def regeneration-workflow regeneration/workflow)
(def respond! worker/respond!)
