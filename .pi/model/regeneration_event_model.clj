(ns loyly.pi-harness.regeneration-event-model
  "Harness-owned Grain service area for selection, preparation, observations, and measurement.

   It runs inside ORC's Grain runtime. ORC :sheet events describe workflow
   execution and feed harness projections; the :sheet area below declares only
   the ORC blocks this harness reads or dispatches, so every edge resolves."
  (:require [ai.obney.grain.event-model.interface :refer [defeventmodel]]))

(defeventmodel :loyly.pi-harness
  {:description "Durable preparation approval and measurement records for session-agnostic ORC regeneration cycles."

   :commands
   {:loyly.pi-harness/record-selection
    {:description "Record the deterministic selection contract shown for review."
     :schema [:map [:contract :map]]
     :reads #{:loyly.pi-harness/selections}
     :produces #{:loyly.pi-harness/selection-recorded}
     :grain/allium [{:spec ".pi/model/regeneration-cycle.allium"
                     :kind :rule :name "PreviewSelection"}]}

    :loyly.pi-harness/approve-selection
    {:description "Approve only the exact digest of a recorded selection."
     :schema [:map [:selection-id :string] [:selection-digest :string]]
     :reads #{:loyly.pi-harness/selections}
     :produces #{:loyly.pi-harness/selection-approved}
     :grain/allium [{:spec ".pi/model/regeneration-cycle.allium"
                     :kind :rule :name "ApproveSelection"}]}

    :loyly.pi-harness/record-cycle-preparation
    {:description "Record the complete cycle preparation shown for review."
     :schema [:map [:preparation :map]]
     :reads #{:loyly.pi-harness/cycle-preparations}
     :produces #{:loyly.pi-harness/cycle-preparation-recorded}
     :grain/allium [{:spec ".pi/model/regeneration-cycle.allium"
                     :kind :rule :name "PrepareCycle"}]}

    :loyly.pi-harness/approve-cycle-preparation
    {:description "Approve only the exact digest of a recorded cycle preparation."
     :schema [:map [:preparation-id :string] [:preparation-digest :string]]
     :reads #{:loyly.pi-harness/cycle-preparations}
     :produces #{:loyly.pi-harness/cycle-preparation-approved}
     :grain/allium [{:spec ".pi/model/regeneration-cycle.allium"
                     :kind :rule :name "ApprovePreparation"}]}

    :loyly.pi-harness/approve-grader
    {:description "Record an operator's approval of a sealed, calibrated grader's exact digest."
     :schema [:map [:approval :map]]
     :reads #{:loyly.pi-harness/approved-graders}
     :produces #{:loyly.pi-harness/grader-approved}
     :grain/allium [{:spec ".pi/model/instrument.allium"
                     :kind :rule :name "ApproveExactGrader"}]}

    :loyly.pi-harness/record-task-receipt
    {:description "Bind an issued task identity to its ORC tick so a replacement worker can answer for it."
     :schema [:map [:receipt :map]]
     :reads #{:loyly.pi-harness/task-receipts}
     :produces #{:loyly.pi-harness/task-receipt-issued}
     :grain/allium [{:spec ".pi/model/operator-control-plane.allium"
                     :kind :rule :name "StartHarnessTask"}]}

    :loyly.pi-harness/record-observation
    {:description "Record one evidence-backed item observation."
     :schema [:map [:observation :map]]
     :reads #{:loyly.pi-harness/cycle-observations}
     :produces #{:loyly.pi-harness/observation-recorded}
     :grain/allium [{:spec ".pi/model/runtime-evidence.allium"
                     :kind :rule :name "RecordRuntimeSignal"}]}

    :loyly.pi-harness/record-cycle-measurement
    {:description "Record the strict reduction of one approved denominator."
     :schema [:map [:measurement :map]]
     :reads #{:loyly.pi-harness/cycle-measurements}
     :produces #{:loyly.pi-harness/cycle-measurement-recorded}
     :grain/allium [{:spec ".pi/model/instrument.allium"
                     :kind :rule :name "FileScore"}]}}

   :events
   {:loyly.pi-harness/selection-recorded
    {:description "A deterministic selection contract was recorded for review."
     :schema [:map
              [:selection-id :string]
              [:selection-digest :string]
              [:status [:enum "awaiting-approval" "approved"]]
              [:project-id :string]
              [:container-id :string]
              [:component-id :string]
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
     :schema [:map [:selection-id :string] [:selection-digest :string]]}

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

    :loyly.pi-harness/task-receipt-issued
    {:description "A task identity was bound to its durable ORC tick."
     :schema [:map [:task-id :int] [:tick-id :string] [:cycle-id :string]]}

    :loyly.pi-harness/grader-approved
    {:description "An operator approved a sealed, calibrated grader by its exact digest."
     :schema [:map [:grader-digest :string] [:entry :string] [:items [:vector :string]]
              [:approved-by :string] [:approved-at :string]]}

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
              [:producer-authority [:enum "candidate" "harness-observer" "independent-validator"]]
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

    :loyly.pi-harness/task-receipts
    {:description "Issued task identities and the ORC ticks they name."
     :consumes #{:loyly.pi-harness/task-receipt-issued}
     :version 1}

    :loyly.pi-harness/approved-graders
    {:description "Graders an operator approved, by exact digest."
     :consumes #{:loyly.pi-harness/grader-approved}
     :version 1}

    :loyly.pi-harness/cycle-progress
    {:description "Harness progress projected directly from authoritative ORC execution facts."
     :consumes #{:sheet/tree-tick-started
                 :sheet/node-execution-started
                 :sheet/node-execution-completed
                 :sheet/tree-tick-completed
                 :sheet/tick-cancelled}
     :version 1
     :grain/allium [{:spec ".pi/model/operator-control-plane.allium"
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

    :loyly.pi-harness/task-receipt
    {:description "Return the ORC tick an issued task identity names."
     :schema [:map [:task-id :int]]
     :reads #{:loyly.pi-harness/task-receipts}}

    :loyly.pi-harness/highest-task-id
    {:description "Return the highest issued task identity."
     :schema [:map]
     :reads #{:loyly.pi-harness/task-receipts}}

    :loyly.pi-harness/approved-graders
    {:description "Return every grader an operator approved, in approval order."
     :schema [:map]
     :reads #{:loyly.pi-harness/approved-graders}}}

   :todo-processors
   {:loyly.pi-harness/start-approved-cycle
    {:description "Dispatch an approved preparation through ORC's Grain command boundary exactly once."
     :subscribes #{:loyly.pi-harness/cycle-preparation-approved}
     :reads #{:loyly.pi-harness/cycle-preparation}
     :produces #{:sheet/tick-tree}
     :grain/allium [{:spec ".pi/model/operator-control-plane.allium"
                     :kind :rule :name "DispatchApprovedPreparationToOrc"}]}}

   :screens
   {:loyly.pi-harness/pi-regeneration
    {:description "Session control boundary for preparation approval and measured regeneration."
     :queries #{:loyly.pi-harness/selection
                :loyly.pi-harness/task-receipt
                :loyly.pi-harness/highest-task-id
                :loyly.pi-harness/approved-graders
                :loyly.pi-harness/cycle-preparation
                :loyly.pi-harness/cycle-progress
                :loyly.pi-harness/cycle-observations
                :loyly.pi-harness/cycle-measurement}
     :commands #{:loyly.pi-harness/record-selection
                 :loyly.pi-harness/approve-selection
                 :loyly.pi-harness/record-task-receipt
                 :loyly.pi-harness/approve-grader
                 :loyly.pi-harness/record-cycle-preparation
                 :loyly.pi-harness/approve-cycle-preparation
                 :loyly.pi-harness/record-observation
                 :loyly.pi-harness/record-cycle-measurement}
     :grain/allium [{:spec ".pi/model/operator-control-plane.allium"
                     :kind :surface :name "RegenerationControlPlane"}]}}

   :flows
   {:loyly.pi-harness/record-selection
    {:description "A deterministic selection is recorded for review."
     :steps [{:from [:screen :loyly.pi-harness/pi-regeneration]
              :to [:command :loyly.pi-harness/record-selection]}
             {:from [:command :loyly.pi-harness/record-selection]
              :to [:event :loyly.pi-harness/selection-recorded]}
             {:from [:event :loyly.pi-harness/selection-recorded]
              :to [:read-model :loyly.pi-harness/selections]}
             {:from [:read-model :loyly.pi-harness/selections]
              :to [:query :loyly.pi-harness/selection]}
             {:from [:query :loyly.pi-harness/selection]
              :to [:screen :loyly.pi-harness/pi-regeneration]}]}

    :loyly.pi-harness/approve-selection
    {:description "A recorded selection is approved by exact digest."
     :steps [{:from [:screen :loyly.pi-harness/pi-regeneration]
              :to [:command :loyly.pi-harness/approve-selection]}
             {:from [:command :loyly.pi-harness/approve-selection]
              :to [:event :loyly.pi-harness/selection-approved]}
             {:from [:event :loyly.pi-harness/selection-approved]
              :to [:read-model :loyly.pi-harness/selections]}
             {:from [:read-model :loyly.pi-harness/selections]
              :to [:query :loyly.pi-harness/selection]}
             {:from [:query :loyly.pi-harness/selection]
              :to [:screen :loyly.pi-harness/pi-regeneration]}]}

    :loyly.pi-harness/record-cycle-preparation
    {:description "A complete cycle preparation is recorded for review."
     :steps [{:from [:screen :loyly.pi-harness/pi-regeneration]
              :to [:command :loyly.pi-harness/record-cycle-preparation]}
             {:from [:command :loyly.pi-harness/record-cycle-preparation]
              :to [:event :loyly.pi-harness/cycle-preparation-recorded]}
             {:from [:event :loyly.pi-harness/cycle-preparation-recorded]
              :to [:read-model :loyly.pi-harness/cycle-preparations]}
             {:from [:read-model :loyly.pi-harness/cycle-preparations]
              :to [:query :loyly.pi-harness/cycle-preparation]}
             {:from [:query :loyly.pi-harness/cycle-preparation]
              :to [:screen :loyly.pi-harness/pi-regeneration]}]}

    :loyly.pi-harness/approve-grader
    {:description "An operator approves a sealed grader by exact digest."
     :steps [{:from [:screen :loyly.pi-harness/pi-regeneration]
              :to [:command :loyly.pi-harness/approve-grader]}
             {:from [:command :loyly.pi-harness/approve-grader]
              :to [:event :loyly.pi-harness/grader-approved]}
             {:from [:event :loyly.pi-harness/grader-approved]
              :to [:read-model :loyly.pi-harness/approved-graders]}
             {:from [:read-model :loyly.pi-harness/approved-graders]
              :to [:query :loyly.pi-harness/approved-graders]}
             {:from [:query :loyly.pi-harness/approved-graders]
              :to [:screen :loyly.pi-harness/pi-regeneration]}]}

    :loyly.pi-harness/approve-cycle-preparation
    {:description "An approved preparation is dispatched to ORC exactly once."
     :steps [{:from [:screen :loyly.pi-harness/pi-regeneration]
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

    :loyly.pi-harness/record-task-receipt
    {:description "A task identity is bound to the ORC tick it names."
     :steps [{:from [:screen :loyly.pi-harness/pi-regeneration]
              :to [:command :loyly.pi-harness/record-task-receipt]}
             {:from [:command :loyly.pi-harness/record-task-receipt]
              :to [:event :loyly.pi-harness/task-receipt-issued]}
             {:from [:event :loyly.pi-harness/task-receipt-issued]
              :to [:read-model :loyly.pi-harness/task-receipts]}
             {:from [:read-model :loyly.pi-harness/task-receipts]
              :to [:query :loyly.pi-harness/task-receipt]}
             {:from [:query :loyly.pi-harness/task-receipt]
              :to [:screen :loyly.pi-harness/pi-regeneration]}]}

    :loyly.pi-harness/record-observation
    {:description "One evidence-backed item observation is recorded."
     :steps [{:from [:screen :loyly.pi-harness/pi-regeneration]
              :to [:command :loyly.pi-harness/record-observation]}
             {:from [:command :loyly.pi-harness/record-observation]
              :to [:event :loyly.pi-harness/observation-recorded]}
             {:from [:event :loyly.pi-harness/observation-recorded]
              :to [:read-model :loyly.pi-harness/cycle-observations]}
             {:from [:read-model :loyly.pi-harness/cycle-observations]
              :to [:query :loyly.pi-harness/cycle-observations]}
             {:from [:query :loyly.pi-harness/cycle-observations]
              :to [:screen :loyly.pi-harness/pi-regeneration]}]}

    :loyly.pi-harness/record-cycle-measurement
    {:description "The strict measurement for one cycle is recorded."
     :steps [{:from [:screen :loyly.pi-harness/pi-regeneration]
              :to [:command :loyly.pi-harness/record-cycle-measurement]}
             {:from [:command :loyly.pi-harness/record-cycle-measurement]
              :to [:event :loyly.pi-harness/cycle-measurement-recorded]}
             {:from [:event :loyly.pi-harness/cycle-measurement-recorded]
              :to [:read-model :loyly.pi-harness/cycle-measurements]}
             {:from [:read-model :loyly.pi-harness/cycle-measurements]
              :to [:query :loyly.pi-harness/cycle-measurement]}
             {:from [:query :loyly.pi-harness/cycle-measurement]
              :to [:screen :loyly.pi-harness/pi-regeneration]}]}

    :loyly.pi-harness/progress-reconciliation
    {:description "Reconnectable harness progress is a projection of ORC execution facts."
     :steps [{:from [:command :sheet/tick-tree]
              :to [:event :sheet/tree-tick-started]}
             {:from [:event :sheet/tree-tick-started]
              :to [:read-model :loyly.pi-harness/cycle-progress]}
             {:from [:read-model :loyly.pi-harness/cycle-progress]
              :to [:query :loyly.pi-harness/cycle-progress]}
             {:from [:query :loyly.pi-harness/cycle-progress]
              :to [:screen :loyly.pi-harness/pi-regeneration]}]}}})

(defeventmodel :sheet
  {:description "The ORC execution blocks this harness depends on, declared so the harness topology resolves. Schemas copied from obneyai/orc projects/orc-service at af086675 (orc_service/interface/schemas.clj); ORC owns them and registers the live definitions."

   :commands
   {:sheet/tick-tree
    {:description "Start one durable ORC tick of a sheet."
     :schema [:map
              [:sheet-id :uuid]
              [:tick-id {:optional true} :uuid]
              [:parent-tick-id {:optional true} :uuid]
              [:correlation-id {:optional true} :uuid]
              [:inputs {:optional true} :map]
              [:input-sources {:optional true} [:map-of :keyword [:map [:tick-id :uuid] [:event-id :uuid]]]]
              [:use-version {:optional true} :int]
              [:force-draft {:optional true} :boolean]
              [:options {:optional true} :map]
              [:tool-context {:optional true} :map]]
     :produces #{:sheet/tree-tick-started}
     :grain/allium [{:spec ".pi/model/operator-control-plane.allium"
                     :kind :rule :name "DispatchApprovedPreparationToOrc"}]}}

   :events
   {:sheet/tree-tick-started
    {:description "An ORC tick started."
     :schema [:map
              [:sheet-id :uuid]
              [:tick-id :uuid]
              [:parent-tick-id {:optional true} :uuid]
              [:correlation-id {:optional true} :uuid]
              [:iteration {:optional true} :int]
              [:seed-sources {:optional true} [:map-of :keyword [:map [:tick-id :uuid] [:event-id :uuid]]]]
              [:execution-snapshot {:optional true} :map]
              [:version-number {:optional true} :int]
              [:options {:optional true} :map]
              [:tool-context {:optional true} :map]]}

    :sheet/node-execution-started
    {:description "One node of an ORC tick started."
     :schema [:map
              [:sheet-id :uuid]
              [:tick-id :uuid]
              [:node-id :uuid]
              [:inputs [:map-of :keyword :any]]
              [:resumed-from-event-id {:optional true} :uuid]]}

    :sheet/node-execution-completed
    {:description "One node of an ORC tick completed."
     :schema [:map
              [:sheet-id :uuid]
              [:tick-id :uuid]
              [:node-id :uuid]
              [:status [:enum :success :failure :running :tree-generated :partial :timeout :blocked]]
              [:write-keys {:optional true} [:vector :keyword]]
              [:write-profile {:optional true} [:map-of :keyword :map]]
              [:rejected-write-keys {:optional true} [:vector :keyword]]
              [:rejected-write-profile {:optional true} [:map-of :keyword :map]]
              [:source-tick-id {:optional true} [:maybe :uuid]]
              [:timestamp [:fn inst?]]]}

    :sheet/tree-tick-completed
    {:description "An ORC tick completed with a root status."
     :schema [:map
              [:sheet-id :uuid]
              [:tick-id :uuid]
              [:correlation-id {:optional true} :uuid]
              [:iteration {:optional true} :int]
              [:root-status [:enum :success :failure :running :tree-generated :partial :timeout :blocked]]
              [:node-id {:optional true} :uuid]
              [:exec-context {:optional true} :map]
              [:input-seed? {:optional true} :boolean]]}

    :sheet/tick-cancelled
    {:description "An ORC tick was cancelled."
     :schema [:map
              [:sheet-id :uuid]
              [:tick-id :uuid]
              [:reason {:optional true} [:maybe :string]]]}}})
