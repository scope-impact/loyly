(ns loyly.pi-harness.grain.interface.schemas
  "Malli contracts for harness commands, events, and public projections."
  (:require [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [loyly.pi-harness.core.evidence.evidence-manifest :as evidence-manifest]))

(def item-schema
  [:map
   [:id :string]
   [:scenario-digest :string]
   [:name :string]
   [:rule :string]
   [:source :string]])

(def selection-contract-schema
  [:map
   [:selectionId :string]
   [:selectionDigest :string]
   [:status [:enum "awaiting-approval" "approved"]]
   [:projectId :string]
   [:containerId :string]
   [:componentId :string]
   [:cut :string]
   [:slice :string]
   [:items [:vector item-schema]]])

(def generated-candidate-schema
  [:map
   [:candidate-id :string]
   [:project-id :string]
   [:container-id :string]
   [:component-id :string]
   [:operation [:enum "fresh-generation" "measured-regeneration"]]
   [:parent-candidate-id {:optional true} :string]
   [:selection-id :string]
   [:cut :string]
   [:output-root :string]
   [:subject-digest :string]
   [:disposition [:enum "baseline-measured" "oracle-measured"]]
   [:oracle-descriptor-digest :string]])

(def oracle-proposal-schema
  [:map
   [:proposal-id :string]
   [:proposal-digest :string]
   [:generation-id :string]
   [:selection-id :string]
   [:selection-digest :string]
   [:scenario-digests [:vector :string]]
   [:subject-digest :string]
   [:judge-id :string]
   [:denominator :int]
   [:normalized-evidence-digest :string]
   [:status [:enum "proposed" "approved" "published"]]])

(def oracle-proposal-approval-schema
  [:map [:proposal-id :string] [:proposal-digest :string] [:approved-by :string]])

(def accepted-product-oracle-schema
  [:map
   [:proposal-id :string]
   [:proposal-digest :string]
   [:descriptor-digest :string]
   [:approved-by :string]
   [:published-at :string]])

(def approved-slice-route-schema
  [:map
   [:request-id :string]
   [:request-digest :string]
   [:project-id :string]
   [:container-id :string]
   [:component-id :string]
   [:selection-id :string]
   [:operation [:enum "fresh-generation" "measured-regeneration"]]
   [:parent-candidate-id {:optional true} :string]
   [:parent-candidate-root {:optional true} :string]
   [:parent-candidate-digest {:optional true} :string]])

(def task-receipt-schema
  [:map [:task-id :int] [:tick-id :string] [:cycle-id :string]])

(def grader-approval-schema
  [:map [:grader-digest :string] [:entry :string] [:items [:vector :string]]
   [:approved-by :string] [:approved-at :string]])

(def cycle-preparation-schema
  [:map
   [:preparation-id :string]
   [:preparation-digest :string]
   [:status [:enum "awaiting-approval" "approved"]]
   [:selection-id :string]
   [:selection-digest :string]
   [:scope [:vector :string]]
   [:target-stack :string]
   [:execution-profile [:enum "host-development" "gondolin-isolated" "automatic"]]
   [:cut-digest :string]
   [:oracle-digest :string]
   [:diagram-digest :string]
   [:max-outer-cycles :int]])

(def cycle-progress-schema
  [:map
   [:cycle-id :string]
   [:tick-id :string]
   [:stage :string]
   [:attempt :int]
   [:builder-session-ref {:optional true} :string]
   [:status [:enum "running" "completed" "failed"]]])

(def observation-schema
  [:map
   [:cycle-id :string]
   [:cut-digest :string]
   [:project-id :string]
   [:container-id :string]
   [:component-id :string]
   [:item-id :string]
   [:subject-digest :string]
   [:producer :string]
   [:producer-authority [:enum "candidate" "harness-observer" "independent-grader"]]
   [:evidence-purpose [:enum "assertion" "diagnostic" "calibration"]]
   [:claim-type :string]
   [:evaluation-id :string]
   [:evidence-digest :string]
   [:result [:enum "pass" "fail" "inconclusive"]]
   [:reached? :boolean]
   [:evidence :string]
   [:collection [:map
                 [:complete? :boolean]
                 [:sampled? :boolean]
                 [:dropped :int]
                 [:reason {:optional true} :string]]]
   [:correlation [:map
                  [:operation-id {:optional true} :string]
                  [:trace-id {:optional true} :string]
                  [:event-id {:optional true} :string]]]
   [:artifacts [:vector
                [:map
                 [:digest :string]
                 [:media-type :string]
                 [:location :string]]]]
   [:oracle-digest :string]])

(def item-result-schema
  [:map
   [:item-id :string]
   [:verdict [:enum "pass" "fail"]]
   [:evidence :string]
   [:graded? :boolean]])

(def instrument-conformance-schema
  [:map
   [:passed :boolean]
   [:total :int]
   [:passed-count :int]
   [:items [:vector item-result-schema]]])

(def measurement-schema
  [:map
   [:cycle-id :string]
   [:selection-id :string]
   [:cut :string]
   [:backend [:enum "host" "gondolin"]]
   [:total :int]
   [:passed :int]
   [:failed :int]
   [:complete? :boolean]
   [:items [:vector item-result-schema]]
   [:instrument-conformance {:optional true} instrument-conformance-schema]
   [:evidence-manifest {:optional true} evidence-manifest/manifest-schema]])

(def measurement-event-schema
  [:map
   [:cycle-id :string]
   [:selection-id :string]
   [:cut :string]
   [:backend [:enum "host" "gondolin"]]
   [:total :int]
   [:passed :int]
   [:failed :int]
   [:complete? :boolean]
   [:items [:vector :map]]])

(defschemas commands
  {:loyly.pi-harness/record-selection [:map [:contract selection-contract-schema]]
   :loyly.pi-harness/approve-selection [:map [:selection-id :string] [:selection-digest :string]]
   :loyly.pi-harness/record-cycle-preparation [:map [:preparation cycle-preparation-schema]]
   :loyly.pi-harness/approve-cycle-preparation [:map [:preparation-id :string]
                                                 [:preparation-digest :string]]
   :loyly.pi-harness/record-generated-candidate [:map [:candidate generated-candidate-schema]]
   :loyly.pi-harness/record-oracle-proposal [:map [:proposal oracle-proposal-schema]]
   :loyly.pi-harness/approve-oracle-proposal oracle-proposal-approval-schema
   :loyly.pi-harness/record-accepted-product-oracle [:map [:accepted-oracle accepted-product-oracle-schema]]
   :loyly.pi-harness/record-approved-slice-route [:map [:route approved-slice-route-schema]]
   :loyly.pi-harness/record-task-receipt [:map [:receipt task-receipt-schema]]
   :loyly.pi-harness/approve-grader [:map [:approval grader-approval-schema]]
   :loyly.pi-harness/record-observation [:map [:observation observation-schema]]
   :loyly.pi-harness/record-cycle-measurement [:map [:measurement measurement-schema]]})

(defschemas events
  {:loyly.pi-harness/selection-recorded selection-contract-schema
   :loyly.pi-harness/selection-approved [:map [:selectionId :string] [:selectionDigest :string]]
   :loyly.pi-harness/cycle-preparation-recorded cycle-preparation-schema
   :loyly.pi-harness/cycle-preparation-approved [:map [:preparation-id :string]
                                                 [:preparation-digest :string]]
   :loyly.pi-harness/generated-candidate-recorded generated-candidate-schema
   :loyly.pi-harness/oracle-proposal-recorded oracle-proposal-schema
   :loyly.pi-harness/oracle-proposal-approved oracle-proposal-approval-schema
   :loyly.pi-harness/product-oracle-accepted accepted-product-oracle-schema
   :loyly.pi-harness/approved-slice-operation-routed approved-slice-route-schema
   :loyly.pi-harness/task-receipt-issued task-receipt-schema
   :loyly.pi-harness/grader-approved grader-approval-schema
   :loyly.pi-harness/observation-recorded observation-schema
   :loyly.pi-harness/cycle-measurement-recorded measurement-event-schema})

(defschemas queries
  {:loyly.pi-harness/selection [:map [:selection-id :string]]
   :loyly.pi-harness/cycle-preparation [:map [:preparation-id :string]]
   :loyly.pi-harness/cycle-progress [:map [:tick-id :string]]
   :loyly.pi-harness/cycle-observations [:map [:cycle-id :string]]
   :loyly.pi-harness/cycle-measurement [:map [:cycle-id :string]]
   :loyly.pi-harness/component-candidates [:map [:project-id :string]
                                           [:container-id :string]
                                           [:component-id :string]]
   :loyly.pi-harness/oracle-proposal [:map [:proposal-id :string]]
   :loyly.pi-harness/approved-slice-operation-route [:map [:request-id :string]]
   :loyly.pi-harness/task-receipt [:map [:task-id :int]]
   :loyly.pi-harness/highest-task-id [:map]
   :loyly.pi-harness/approved-graders [:map]})
