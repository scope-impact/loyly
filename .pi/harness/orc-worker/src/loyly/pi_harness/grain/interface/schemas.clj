(ns loyly.pi-harness.grain.interface.schemas
  "Malli contracts for harness commands, events, and public projections."
  (:require [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [loyly.pi-harness.core.evidence.evidence-manifest :as evidence-manifest]))

(def item-schema
  [:map [:id :string] [:name :string] [:rule :string] [:source :string]])

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
   [:operation [:enum "generation" "regeneration"]]
   [:parent-candidate-id {:optional true} :string]
   [:selection-id :string]
   [:cut :string]
   [:output-root :string]
   [:subject-digest :string]
   [:disposition [:enum "ungraded" "measured"]]])

(def approved-slice-route-schema
  [:map
   [:request-id :string]
   [:request-digest :string]
   [:project-id :string]
   [:container-id :string]
   [:component-id :string]
   [:selection-id :string]
   [:operation [:enum "generation" "regeneration"]]
   [:parent-candidate-id {:optional true} :string]
   [:parent-candidate-root {:optional true} :string]
   [:parent-candidate-digest {:optional true} :string]])

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
   [:evidence-kind [:enum "tool" "grader"]]
   [:evaluation-id {:optional true} :string]
   [:evidence-digest {:optional true} :string]
   [:artifact-digests {:optional true} [:vector :string]]
   [:result [:enum "pass" "fail"]]
   [:reached? :boolean]
   [:evidence :string]
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

(defschemas commands
  {:loyly/record-selection [:map [:contract selection-contract-schema]]
   :loyly/approve-selection [:map [:selection-id :string] [:selection-digest :string]]
   :loyly/record-generated-candidate [:map [:candidate generated-candidate-schema]]
   :loyly/record-approved-slice-route [:map [:route approved-slice-route-schema]]
   :loyly/record-cycle-progress [:map [:progress cycle-progress-schema]]
   :loyly/record-observation [:map [:observation observation-schema]]
   :loyly/record-cycle-measurement [:map [:measurement measurement-schema]]})

(defschemas events
  {:loyly/selection-recorded selection-contract-schema
   :loyly/selection-approved [:map [:selectionId :string] [:selectionDigest :string]]
   :loyly/generated-candidate-recorded generated-candidate-schema
   :loyly/approved-slice-operation-routed approved-slice-route-schema
   :loyly/cycle-progress-recorded cycle-progress-schema
   :loyly/observation-recorded observation-schema
   :loyly/cycle-measurement-recorded measurement-schema})
