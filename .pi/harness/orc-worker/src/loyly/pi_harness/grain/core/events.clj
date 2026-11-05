(ns loyly.pi-harness.grain.core.events
  "Immutable Pi-harness event definitions. Harness facts retain complete history."
  (:require [ai.obney.grain.event-store-v3.interface.event-definition :refer [defevent]]
            [loyly.pi-harness.grain.interface.schemas :as schemas]))

(defevent :loyly.pi-harness/selection-recorded
  "A deterministic selection was recorded for review."
  {:schema schemas/selection-contract-schema})

(defevent :loyly.pi-harness/selection-approved
  "The exact digest of a reviewed selection was approved."
  {:schema [:map [:selectionId :string] [:selectionDigest :string]]})

(defevent :loyly.pi-harness/cycle-preparation-recorded
  "A complete cycle preparation was recorded for review."
  {:schema schemas/cycle-preparation-schema})

(defevent :loyly.pi-harness/cycle-preparation-approved
  "The exact digest of a cycle preparation was approved."
  {:schema [:map [:preparation-id :string] [:preparation-digest :string]]})

(defevent :loyly.pi-harness/task-receipt-issued
  "A task receipt was issued for a submitted cycle, binding its task identity to its ORC tick."
  {:schema schemas/task-receipt-schema})

(defevent :loyly.pi-harness/approved-slice-operation-routed
  "An approved request was durably routed to generation or regeneration."
  {:schema schemas/approved-slice-route-schema})

(defevent :loyly.pi-harness/generated-candidate-recorded
  "Candidate lineage was recorded for a generated component."
  {:schema schemas/generated-candidate-schema})

(defevent :loyly.pi-harness/oracle-proposal-recorded
  "A complete first-grade evidence proposal was recorded for review."
  {:schema schemas/oracle-proposal-schema})

(defevent :loyly.pi-harness/oracle-proposal-approved
  "The exact digest of an oracle proposal was manually approved."
  {:schema schemas/oracle-proposal-approval-schema})

(defevent :loyly.pi-harness/product-oracle-accepted
  "Approved evidence was published as an immutable product oracle."
  {:schema schemas/accepted-product-oracle-schema})

(defevent :loyly.pi-harness/observation-recorded
  "An independent evidence-backed observation was recorded."
  {:schema schemas/observation-schema})

(defevent :loyly.pi-harness/cycle-measurement-recorded
  "The strict terminal measurement for a cycle was recorded."
  {:schema schemas/measurement-event-schema})
