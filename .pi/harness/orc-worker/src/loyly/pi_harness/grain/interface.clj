(ns loyly.pi-harness.grain.interface
  "Public API for the harness's Grain-backed domain model."
  (:require [loyly.pi-harness.grain.core.commands :as commands]
            [loyly.pi-harness.grain.core.read-models :as read-models]
            [loyly.pi-harness.grain.interface.schemas :as schemas]))

(def item-schema schemas/item-schema)
(def selection-contract-schema schemas/selection-contract-schema)
(def cycle-progress-schema schemas/cycle-progress-schema)
(def observation-schema schemas/observation-schema)
(def item-result-schema schemas/item-result-schema)
(def instrument-conformance-schema schemas/instrument-conformance-schema)
(def measurement-schema schemas/measurement-schema)

(def find-selection read-models/find-selection)
(def find-cycle read-models/find-cycle)
(def find-measurement read-models/find-measurement)
(def find-cycle-progress read-models/find-cycle-progress)

(def record-selection! commands/record-selection!)
(def approve-selection! commands/approve-selection!)
(def record-cycle-progress! commands/record-cycle-progress!)
(def record-observation! commands/record-observation!)
(def reduce-result commands/reduce-result)
(def record-measurement! commands/record-measurement!)
