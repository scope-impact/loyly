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
   :loyly/record-observation [:map [:observation observation-schema]]
   :loyly/record-cycle-measurement [:map [:measurement measurement-schema]]})

(defschemas events
  {:loyly/selection-recorded selection-contract-schema
   :loyly/selection-approved [:map [:selectionId :string] [:selectionDigest :string]]
   :loyly/observation-recorded observation-schema
   :loyly/cycle-measurement-recorded measurement-schema})
