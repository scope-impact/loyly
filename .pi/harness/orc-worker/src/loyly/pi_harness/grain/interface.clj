(ns loyly.pi-harness.grain.interface
  "Public API for the harness's Grain-backed domain model."
  (:require [loyly.pi-harness.grain.core.events]
            [loyly.pi-harness.grain.core.processors]
            [loyly.pi-harness.grain.core.commands :as commands]
            [loyly.pi-harness.grain.core.queries :as queries]
            [loyly.pi-harness.grain.interface.schemas :as schemas]))

(def item-schema schemas/item-schema)
(def selection-contract-schema schemas/selection-contract-schema)
(def cycle-progress-schema schemas/cycle-progress-schema)
(def generated-candidate-schema schemas/generated-candidate-schema)
(def oracle-proposal-schema schemas/oracle-proposal-schema)
(def approved-slice-route-schema schemas/approved-slice-route-schema)
(def cycle-preparation-schema schemas/cycle-preparation-schema)
(def observation-schema schemas/observation-schema)
(def item-result-schema schemas/item-result-schema)
(def instrument-conformance-schema schemas/instrument-conformance-schema)
(def measurement-schema schemas/measurement-schema)

(def query-selection queries/selection)
(def query-cycle-preparation queries/cycle-preparation)
(def find-selection queries/selection)
(def find-cycle queries/cycle-observations)
(def find-measurement queries/cycle-measurement)
(def find-cycle-progress queries/cycle-progress)
(defn find-latest-candidate [context project-id container-id component-id]
  (last (queries/component-candidates context project-id container-id component-id)))
(def find-approved-slice-route queries/approved-slice-operation-route)
(def query-oracle-proposal queries/oracle-proposal)

(def record-selection! commands/record-selection!)
(def approve-selection! commands/approve-selection!)
(def record-cycle-preparation! commands/record-cycle-preparation!)
(def approve-cycle-preparation! commands/approve-cycle-preparation!)
(def record-generated-candidate! commands/record-generated-candidate!)
(def record-oracle-proposal! commands/record-oracle-proposal!)
(def approve-oracle-proposal! commands/approve-oracle-proposal!)
(def record-accepted-product-oracle! commands/record-accepted-product-oracle!)
(def record-approved-slice-route! commands/record-approved-slice-route!)
(def record-task-receipt! commands/record-task-receipt!)
(def find-task-receipt queries/task-receipt)
(def highest-task-id queries/highest-task-id)
(def record-observation! commands/record-observation!)
(def reduce-result commands/reduce-result)
(def record-measurement! commands/record-measurement!)
