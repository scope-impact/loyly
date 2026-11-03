(ns loyly.pi-harness.regeneration-event-model
  "Harness-owned Grain service area for selection, preparation, observations, and measurement.

   It runs inside ORC's Grain runtime. ORC :sheet events describe workflow
   execution and may feed harness projections without being duplicated. Löyly
   product events describe the regenerated application."
  (:require [ai.obney.grain.event-model.interface :refer [defeventmodel]]))

(defeventmodel :loyly.pi-harness
  {:description "Durable preparation approval and measurement records for session-agnostic ORC regeneration cycles."

   :commands
   {:loyly.pi-harness/record-selection
    {:description "Record the deterministic selection contract shown for review."
     :schema [:map [:contract :map]]
     :reads #{:loyly.pi-harness/selections}
     :produces #{:loyly.pi-harness/selection-recorded}
     :grain/allium [{:spec "model/pi-harness/regeneration-cycle.allium"
                     :kind :rule :name "PreviewSelection"}]}

    :loyly.pi-harness/approve-selection
    {:description "Approve only the exact digest of a recorded selection."
     :schema [:map [:selection-id :string] [:selection-digest :string]]
     :reads #{:loyly.pi-harness/selections}
     :produces #{:loyly.pi-harness/selection-approved}
     :grain/allium [{:spec "model/pi-harness/regeneration-cycle.allium"
                     :kind :rule :name "ApproveSelection"}]}

    :loyly.pi-harness/record-cycle-preparation
    {:description "Record the complete cycle preparation shown for review."
     :schema [:map [:preparation :map]]
     :reads #{:loyly.pi-harness/cycle-preparations}
     :produces #{:loyly.pi-harness/cycle-preparation-recorded}
     :grain/allium [{:spec "model/pi-harness/regeneration-cycle.allium"
                     :kind :rule :name "PrepareCycle"}]}

    :loyly.pi-harness/approve-cycle-preparation
    {:description "Approve only the exact digest of a recorded cycle preparation."
     :schema [:map [:preparation-id :string] [:preparation-digest :string]]
     :reads #{:loyly.pi-harness/cycle-preparations}
     :produces #{:loyly.pi-harness/cycle-preparation-approved}
     :grain/allium [{:spec "model/pi-harness/regeneration-cycle.allium"
                     :kind :rule :name "ApprovePreparation"}]}

    :loyly.pi-harness/record-approved-slice-route
    {:description "Persist the ORC-selected operation for an approved slice request."
     :schema [:map [:route :map]]
     :reads #{:loyly.pi-harness/approved-slice-operation-routes}
     :produces #{:loyly.pi-harness/approved-slice-operation-routed}
     :grain/allium [{:spec "model/pi-harness/initial-generation.allium"
                     :kind :rule :name "RouteApprovedSliceToInitialGeneration"}
                    {:spec "model/pi-harness/initial-generation.allium"
                     :kind :rule :name "RouteApprovedSliceToMeasuredRegeneration"}]}

    :loyly.pi-harness/record-generated-candidate
    {:description "Persist candidate lineage so ORC can route approved slices by prior component output."
     :schema [:map [:candidate :map]]
     :reads #{:loyly.pi-harness/component-candidates}
     :produces #{:loyly.pi-harness/generated-candidate-recorded}
     :grain/allium [{:spec "model/pi-harness/initial-generation.allium"
                     :kind :rule :name "RecordInitialGenerationCandidate"}
                    {:spec "model/pi-harness/initial-generation.allium"
                     :kind :rule :name "RecordMeasuredRegenerationCandidate"}]}

    :loyly.pi-harness/record-observation
    {:description "Record one evidence-backed item observation."
     :schema [:map [:observation :map]]
     :reads #{:loyly.pi-harness/cycle-observations}
     :produces #{:loyly.pi-harness/observation-recorded}
     :grain/allium [{:spec "model/pi-harness/regeneration-cycle.allium"
                     :kind :rule :name "CandidateGraded"}]}

    :loyly.pi-harness/record-cycle-measurement
    {:description "Record the strict reduction of one approved denominator."
     :schema [:map [:measurement :map]]
     :reads #{:loyly.pi-harness/cycle-measurements}
     :produces #{:loyly.pi-harness/cycle-measurement-recorded}
     :grain/allium [{:spec "model/pi-harness/regeneration-cycle.allium"
                     :kind :rule :name "RecordMeasurement"}]}}

   :events
   {:loyly.pi-harness/selection-recorded
    {:description "A deterministic selection contract was recorded for review."
     :schema [:map
              [:selectionId :string]
              [:selectionDigest :string]
              [:status [:enum "awaiting-approval" "approved"]]
              [:projectId :string]
              [:containerId :string]
              [:componentId :string]
              [:cut :string]
              [:slice :string]
              [:items
               [:vector
                [:map
                 [:id :string]
                 [:scenario-digest :string]
                 [:name :string]
                 [:rule :string]
                 [:source :string]]]]]}

    :loyly.pi-harness/selection-approved
    {:description "The exact digest of a recorded selection was approved."
     :schema [:map [:selectionId :string] [:selectionDigest :string]]}

    :loyly.pi-harness/cycle-preparation-recorded
    {:description "A complete, deterministic cycle preparation was recorded for review."
     :schema [:map
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
              [:max-outer-cycles :int]]}

    :loyly.pi-harness/cycle-preparation-approved
    {:description "The exact digest of a complete cycle preparation was approved."
     :schema [:map [:preparation-id :string] [:preparation-digest :string]]}

    :loyly.pi-harness/approved-slice-operation-routed
    {:description "ORC selected and durably pinned generation or regeneration for a request."
     :schema [:map
              [:request-id :string]
              [:request-digest :string]
              [:project-id :string]
              [:container-id :string]
              [:component-id :string]
              [:selection-id :string]
              [:operation [:enum "generation" "regeneration"]]
              [:parent-candidate-id {:optional true} :string]
              [:parent-candidate-root {:optional true} :string]
              [:parent-candidate-digest {:optional true} :string]]}

    :loyly.pi-harness/generated-candidate-recorded
    {:description "A generated candidate and its lineage were recorded for a project component."
     :schema [:map
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
              [:disposition [:enum "ungraded" "measured"]]]}

    :loyly.pi-harness/observation-recorded
    {:description "One identified producer recorded a purpose-scoped claim for one cycle item."
     :schema [:map
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
              [:oracle-digest :string]]}

    :loyly.pi-harness/cycle-measurement-recorded
    {:description "The immutable strict measurement for one cycle was recorded."
     :schema [:map
              [:cycle-id :string]
              [:selection-id :string]
              [:cut :string]
              [:backend [:enum "host" "gondolin"]]
              [:total :int]
              [:passed :int]
              [:failed :int]
              [:complete? :boolean]
              [:items [:vector :map]]]}}

   :read-models
   {:loyly.pi-harness/selections
    {:description "Recorded selections with approval status."
     :consumes #{:loyly.pi-harness/selection-recorded :loyly.pi-harness/selection-approved}
     :version 1}

    :loyly.pi-harness/cycle-preparations
    {:description "Complete reviewed cycle preparations with approval status."
     :consumes #{:loyly.pi-harness/cycle-preparation-recorded
                 :loyly.pi-harness/cycle-preparation-approved}
     :version 1}

    :loyly.pi-harness/component-candidates
    {:description "Generated candidate lineage ordered by project component for operation routing."
     :consumes #{:loyly.pi-harness/generated-candidate-recorded}
     :version 1}

    :loyly.pi-harness/approved-slice-operation-routes
    {:description "The immutable operation decision keyed by approved request identity."
     :consumes #{:loyly.pi-harness/approved-slice-operation-routed}
     :version 1}

    :loyly.pi-harness/cycle-progress
    {:description "Harness progress projected directly from authoritative ORC execution facts."
     :consumes #{:sheet/tree-tick-started
                 :sheet/node-execution-started
                 :sheet/node-execution-completed
                 :sheet/tree-tick-completed
                 :sheet/tick-cancelled}
     :version 1
     :grain/allium [{:spec "model/pi-harness/operator-control-plane.allium"
                     :kind :rule :name "ProjectCycleProgress"}]}

    :loyly.pi-harness/cycle-observations
    {:description "Evidence grouped by cycle, item, kind, and producer."
     :consumes #{:loyly.pi-harness/observation-recorded}
     :version 1}

    :loyly.pi-harness/cycle-measurements
    {:description "Strict cycle measurements keyed by cycle identity."
     :consumes #{:loyly.pi-harness/cycle-measurement-recorded}
     :version 1}}

   :queries
   {:loyly.pi-harness/selection
    {:description "Return one recorded selection with its approval status."
     :schema [:map [:selection-id :string]]
     :reads #{:loyly.pi-harness/selections}}

    :loyly.pi-harness/cycle-preparation
    {:description "Return one complete cycle preparation with its approval status."
     :schema [:map [:preparation-id :string]]
     :reads #{:loyly.pi-harness/cycle-preparations}}

    :loyly.pi-harness/cycle-progress
    {:description "Return the latest observational stage for one ORC tick."
     :schema [:map [:tick-id :string]]
     :reads #{:loyly.pi-harness/cycle-progress}}

    :loyly.pi-harness/cycle-observations
    {:description "Return all recorded observations for one cycle."
     :schema [:map [:cycle-id :string]]
     :reads #{:loyly.pi-harness/cycle-observations}}

    :loyly.pi-harness/cycle-measurement
    {:description "Return the strict measurement for one cycle."
     :schema [:map [:cycle-id :string]]
     :reads #{:loyly.pi-harness/cycle-measurements}}

    :loyly.pi-harness/component-candidates
    {:description "Return candidate lineage for one project component."
     :schema [:map [:project-id :string] [:container-id :string] [:component-id :string]]
     :reads #{:loyly.pi-harness/component-candidates}}

    :loyly.pi-harness/approved-slice-operation-route
    {:description "Return ORC's durably selected operation for one request."
     :schema [:map [:request-id :string]]
     :reads #{:loyly.pi-harness/approved-slice-operation-routes}}}

   :todo-processors
   {:loyly.pi-harness/start-approved-cycle
    {:description "Dispatch an approved preparation through ORC's Grain command boundary exactly once."
     :subscribes #{:loyly.pi-harness/cycle-preparation-approved}
     :reads #{:loyly.pi-harness/cycle-preparation}
     :produces #{:sheet/tick-tree}
     :grain/allium [{:spec "model/pi-harness/operator-control-plane.allium"
                     :kind :rule :name "DispatchApprovedPreparationToOrc"}]}}

   :screens
   {:loyly.pi-harness/pi-regeneration
    {:description "Session control boundary for preparation approval and measured regeneration."
     :queries #{:loyly.pi-harness/selection
                :loyly.pi-harness/component-candidates
                :loyly.pi-harness/approved-slice-operation-route
                :loyly.pi-harness/cycle-preparation
                :loyly.pi-harness/cycle-progress
                :loyly.pi-harness/cycle-observations
                :loyly.pi-harness/cycle-measurement}
     :commands #{:loyly.pi-harness/record-selection
                 :loyly.pi-harness/approve-selection
                 :loyly.pi-harness/record-approved-slice-route
                 :loyly.pi-harness/record-generated-candidate
                 :loyly.pi-harness/record-cycle-preparation
                 :loyly.pi-harness/approve-cycle-preparation
                 :loyly.pi-harness/record-observation
                 :loyly.pi-harness/record-cycle-measurement}
     :grain/allium [{:spec "model/pi-harness/operator-control-plane.allium"
                     :kind :surface :name "RegenerationControlPlane"}]}}

   :flows
   {:loyly.pi-harness/selection-approval
    {:description "A deterministic selection is recorded and approved by exact digest."
     :steps [{:from [:screen :loyly.pi-harness/pi-regeneration]
              :to [:command :loyly.pi-harness/record-selection]}
             {:from [:command :loyly.pi-harness/record-selection]
              :to [:event :loyly.pi-harness/selection-recorded]}
             {:from [:event :loyly.pi-harness/selection-recorded]
              :to [:read-model :loyly.pi-harness/selections]}
             {:from [:screen :loyly.pi-harness/pi-regeneration]
              :to [:command :loyly.pi-harness/approve-selection]}
             {:from [:command :loyly.pi-harness/approve-selection]
              :to [:event :loyly.pi-harness/selection-approved]}
             {:from [:event :loyly.pi-harness/selection-approved]
              :to [:read-model :loyly.pi-harness/selections]}]}

    :loyly.pi-harness/cycle-preparation-approval
    {:description "A complete cycle preparation is recorded and approved by exact digest."
     :steps [{:from [:screen :loyly.pi-harness/pi-regeneration]
              :to [:command :loyly.pi-harness/record-cycle-preparation]}
             {:from [:command :loyly.pi-harness/record-cycle-preparation]
              :to [:event :loyly.pi-harness/cycle-preparation-recorded]}
             {:from [:event :loyly.pi-harness/cycle-preparation-recorded]
              :to [:read-model :loyly.pi-harness/cycle-preparations]}
             {:from [:screen :loyly.pi-harness/pi-regeneration]
              :to [:command :loyly.pi-harness/approve-cycle-preparation]}
             {:from [:command :loyly.pi-harness/approve-cycle-preparation]
              :to [:event :loyly.pi-harness/cycle-preparation-approved]}
             {:from [:event :loyly.pi-harness/cycle-preparation-approved]
              :to [:read-model :loyly.pi-harness/cycle-preparations]}
             {:from [:read-model :loyly.pi-harness/cycle-preparations]
              :to [:query :loyly.pi-harness/cycle-preparation]}
             {:from [:query :loyly.pi-harness/cycle-preparation]
              :to [:todo-processor :loyly.pi-harness/start-approved-cycle]}
             {:from [:todo-processor :loyly.pi-harness/start-approved-cycle]
              :to [:command :sheet/tick-tree]}]}

    :loyly.pi-harness/progress-reconciliation
    {:description "Reconnectable harness progress is a projection of ORC execution facts."
     :steps [{:from [:event :sheet/tree-tick-started]
              :to [:read-model :loyly.pi-harness/cycle-progress]}
             {:from [:read-model :loyly.pi-harness/cycle-progress]
              :to [:query :loyly.pi-harness/cycle-progress]}
             {:from [:query :loyly.pi-harness/cycle-progress]
              :to [:screen :loyly.pi-harness/pi-regeneration]}]}

    :loyly.pi-harness/measurement-recording
    {:description "Independent observations are reduced and recorded as one strict measurement."
     :steps [{:from [:screen :loyly.pi-harness/pi-regeneration]
              :to [:command :loyly.pi-harness/record-observation]}
             {:from [:command :loyly.pi-harness/record-observation]
              :to [:event :loyly.pi-harness/observation-recorded]}
             {:from [:event :loyly.pi-harness/observation-recorded]
              :to [:read-model :loyly.pi-harness/cycle-observations]}
             {:from [:screen :loyly.pi-harness/pi-regeneration]
              :to [:command :loyly.pi-harness/record-cycle-measurement]}
             {:from [:command :loyly.pi-harness/record-cycle-measurement]
              :to [:event :loyly.pi-harness/cycle-measurement-recorded]}
             {:from [:event :loyly.pi-harness/cycle-measurement-recorded]
              :to [:read-model :loyly.pi-harness/cycle-measurements]}]}}})
