(ns loyly.pi-harness.regeneration-event-model
  "Harness-owned Grain Event Model for selection, preparation, observations, and measurement.

   ORC :sheet events describe workflow execution. Löyly product events describe
   the regenerated application. Neither stream is duplicated here."
  (:require [ai.obney.grain.event-model.interface :refer [defeventmodel]]))

(defeventmodel :loyly.pi-harness
  {:description "Durable preparation approval and measurement records for session-agnostic ORC regeneration cycles."

   :commands
   {:loyly/record-selection
    {:description "Record the deterministic selection contract shown for review."
     :schema [:map [:contract :map]]
     :reads #{:loyly/selections}
     :produces #{:loyly/selection-recorded}
     :grain/allium [{:spec "model/pi-harness/regeneration-cycle.allium"
                     :kind :rule :name "PreviewSelection"}]}

    :loyly/approve-selection
    {:description "Approve only the exact digest of a recorded selection."
     :schema [:map [:selection-id :string] [:selection-digest :string]]
     :reads #{:loyly/selections}
     :produces #{:loyly/selection-approved}
     :grain/allium [{:spec "model/pi-harness/regeneration-cycle.allium"
                     :kind :rule :name "ApproveSelection"}]}

    :loyly/record-cycle-preparation
    {:description "Record the complete cycle preparation shown for review."
     :schema [:map [:preparation :map]]
     :reads #{:loyly/cycle-preparations}
     :produces #{:loyly/cycle-preparation-recorded}
     :grain/allium [{:spec "model/pi-harness/regeneration-cycle.allium"
                     :kind :rule :name "PrepareCycle"}]}

    :loyly/approve-cycle-preparation
    {:description "Approve only the exact digest of a recorded cycle preparation."
     :schema [:map [:preparation-id :string] [:preparation-digest :string]]
     :reads #{:loyly/cycle-preparations}
     :produces #{:loyly/cycle-preparation-approved}
     :grain/allium [{:spec "model/pi-harness/regeneration-cycle.allium"
                     :kind :rule :name "ApprovePreparation"}]}

    :loyly/record-approved-slice-route
    {:description "Persist the ORC-selected operation for an approved slice request."
     :schema [:map [:route :map]]
     :reads #{:loyly/approved-slice-operation-routes}
     :produces #{:loyly/approved-slice-operation-routed}
     :grain/allium [{:spec "model/pi-harness/initial-generation.allium"
                     :kind :rule :name "RouteApprovedSliceToInitialGeneration"}
                    {:spec "model/pi-harness/initial-generation.allium"
                     :kind :rule :name "RouteApprovedSliceToMeasuredRegeneration"}]}

    :loyly/record-generated-candidate
    {:description "Persist candidate lineage so ORC can route approved slices by prior component output."
     :schema [:map [:candidate :map]]
     :reads #{:loyly/component-candidates}
     :produces #{:loyly/generated-candidate-recorded}
     :grain/allium [{:spec "model/pi-harness/initial-generation.allium"
                     :kind :rule :name "RecordInitialGenerationCandidate"}
                    {:spec "model/pi-harness/initial-generation.allium"
                     :kind :rule :name "RecordMeasuredRegenerationCandidate"}]}

    :loyly/record-cycle-progress
    {:description "Record the latest observational stage for one durable ORC tick."
     :schema [:map [:progress :map]]
     :reads #{:loyly/cycle-progress}
     :produces #{:loyly/cycle-progress-recorded}
     :grain/allium [{:spec "model/pi-harness/operator-control-plane.allium"
                     :kind :rule :name "ObserveOrcExecutionState"}]}

    :loyly/record-observation
    {:description "Record one evidence-backed item observation."
     :schema [:map [:observation :map]]
     :reads #{:loyly/cycle-observations}
     :produces #{:loyly/observation-recorded}
     :grain/allium [{:spec "model/pi-harness/regeneration-cycle.allium"
                     :kind :rule :name "CandidateGraded"}]}

    :loyly/record-cycle-measurement
    {:description "Record the strict reduction of one approved denominator."
     :schema [:map [:measurement :map]]
     :reads #{:loyly/cycle-measurements}
     :produces #{:loyly/cycle-measurement-recorded}
     :grain/allium [{:spec "model/pi-harness/regeneration-cycle.allium"
                     :kind :rule :name "RecordMeasurement"}]}}

   :events
   {:loyly/selection-recorded
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
              [:items [:vector :map]]]}

    :loyly/selection-approved
    {:description "The exact digest of a recorded selection was approved."
     :schema [:map [:selectionId :string] [:selectionDigest :string]]}

    :loyly/cycle-preparation-recorded
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

    :loyly/cycle-preparation-approved
    {:description "The exact digest of a complete cycle preparation was approved."
     :schema [:map [:preparation-id :string] [:preparation-digest :string]]}

    :loyly/approved-slice-operation-routed
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

    :loyly/generated-candidate-recorded
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

    :loyly/cycle-progress-recorded
    {:description "An observational stage snapshot was recorded; ORC tick state remains authoritative."
     :schema [:map
              [:cycle-id :string]
              [:tick-id :string]
              [:stage :string]
              [:attempt :int]
              [:builder-session-ref {:optional true} :string]
              [:status [:enum "running" "completed" "failed"]]]}

    :loyly/observation-recorded
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

    :loyly/cycle-measurement-recorded
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
   {:loyly/selections
    {:description "Recorded selections with approval status."
     :consumes #{:loyly/selection-recorded :loyly/selection-approved}
     :version 1}

    :loyly/cycle-preparations
    {:description "Complete reviewed cycle preparations with approval status."
     :consumes #{:loyly/cycle-preparation-recorded
                 :loyly/cycle-preparation-approved}
     :version 1}

    :loyly/component-candidates
    {:description "Generated candidate lineage ordered by project component for operation routing."
     :consumes #{:loyly/generated-candidate-recorded}
     :version 1}

    :loyly/approved-slice-operation-routes
    {:description "The immutable operation decision keyed by approved request identity."
     :consumes #{:loyly/approved-slice-operation-routed}
     :version 1}

    :loyly/cycle-progress
    {:description "Latest observational stage and builder-session reference keyed by ORC tick identity."
     :consumes #{:loyly/cycle-progress-recorded}
     :version 1}

    :loyly/cycle-observations
    {:description "Evidence grouped by cycle, item, kind, and producer."
     :consumes #{:loyly/observation-recorded}
     :version 1}

    :loyly/cycle-measurements
    {:description "Strict cycle measurements keyed by cycle identity."
     :consumes #{:loyly/cycle-measurement-recorded}
     :version 1}}

   :queries
   {:loyly/selection
    {:description "Return one recorded selection with its approval status."
     :schema [:map [:selection-id :string]]
     :reads #{:loyly/selections}}

    :loyly/cycle-preparation
    {:description "Return one complete cycle preparation with its approval status."
     :schema [:map [:preparation-id :string]]
     :reads #{:loyly/cycle-preparations}}

    :loyly/cycle-progress
    {:description "Return the latest observational stage for one ORC tick."
     :schema [:map [:tick-id :string]]
     :reads #{:loyly/cycle-progress}}

    :loyly/cycle-observations
    {:description "Return all recorded observations for one cycle."
     :schema [:map [:cycle-id :string]]
     :reads #{:loyly/cycle-observations}}

    :loyly/cycle-measurement
    {:description "Return the strict measurement for one cycle."
     :schema [:map [:cycle-id :string]]
     :reads #{:loyly/cycle-measurements}}

    :loyly/component-candidates
    {:description "Return candidate lineage for one project component."
     :schema [:map [:project-id :string] [:container-id :string] [:component-id :string]]
     :reads #{:loyly/component-candidates}}

    :loyly/approved-slice-operation-route
    {:description "Return ORC's durably selected operation for one request."
     :schema [:map [:request-id :string]]
     :reads #{:loyly/approved-slice-operation-routes}}}

   :screens
   {:loyly/pi-regeneration
    {:description "Session control boundary for preparation approval and measured regeneration."
     :queries #{:loyly/selection
                :loyly/component-candidates
                :loyly/approved-slice-operation-route
                :loyly/cycle-preparation
                :loyly/cycle-progress
                :loyly/cycle-observations
                :loyly/cycle-measurement}
     :commands #{:loyly/record-selection
                 :loyly/approve-selection
                 :loyly/record-approved-slice-route
                 :loyly/record-generated-candidate
                 :loyly/record-cycle-preparation
                 :loyly/approve-cycle-preparation
                 :loyly/record-cycle-progress
                 :loyly/record-observation
                 :loyly/record-cycle-measurement}
     :grain/allium [{:spec "model/pi-harness/regeneration-cycle.allium"
                     :kind :surface :name "RegenerationHarness"}]}}

   :flows
   {:loyly/selection-approval
    {:description "A deterministic selection is recorded and approved by exact digest."
     :steps [{:from [:screen :loyly/pi-regeneration]
              :to [:command :loyly/record-selection]}
             {:from [:command :loyly/record-selection]
              :to [:event :loyly/selection-recorded]}
             {:from [:event :loyly/selection-recorded]
              :to [:read-model :loyly/selections]}
             {:from [:screen :loyly/pi-regeneration]
              :to [:command :loyly/approve-selection]}
             {:from [:command :loyly/approve-selection]
              :to [:event :loyly/selection-approved]}
             {:from [:event :loyly/selection-approved]
              :to [:read-model :loyly/selections]}]}

    :loyly/cycle-preparation-approval
    {:description "A complete cycle preparation is recorded and approved by exact digest."
     :steps [{:from [:screen :loyly/pi-regeneration]
              :to [:command :loyly/record-cycle-preparation]}
             {:from [:command :loyly/record-cycle-preparation]
              :to [:event :loyly/cycle-preparation-recorded]}
             {:from [:event :loyly/cycle-preparation-recorded]
              :to [:read-model :loyly/cycle-preparations]}
             {:from [:screen :loyly/pi-regeneration]
              :to [:command :loyly/approve-cycle-preparation]}
             {:from [:command :loyly/approve-cycle-preparation]
              :to [:event :loyly/cycle-preparation-approved]}
             {:from [:event :loyly/cycle-preparation-approved]
              :to [:read-model :loyly/cycle-preparations]}]}

    :loyly/progress-reconciliation
    {:description "ORC executors publish an observational stage projection for reconnecting clients."
     :steps [{:from [:screen :loyly/pi-regeneration]
              :to [:command :loyly/record-cycle-progress]}
             {:from [:command :loyly/record-cycle-progress]
              :to [:event :loyly/cycle-progress-recorded]}
             {:from [:event :loyly/cycle-progress-recorded]
              :to [:read-model :loyly/cycle-progress]}]}

    :loyly/measurement-recording
    {:description "Independent observations are reduced and recorded as one strict measurement."
     :steps [{:from [:screen :loyly/pi-regeneration]
              :to [:command :loyly/record-observation]}
             {:from [:command :loyly/record-observation]
              :to [:event :loyly/observation-recorded]}
             {:from [:event :loyly/observation-recorded]
              :to [:read-model :loyly/cycle-observations]}
             {:from [:screen :loyly/pi-regeneration]
              :to [:command :loyly/record-cycle-measurement]}
             {:from [:command :loyly/record-cycle-measurement]
              :to [:event :loyly/cycle-measurement-recorded]}
             {:from [:event :loyly/cycle-measurement-recorded]
              :to [:read-model :loyly/cycle-measurements]}]}}})
