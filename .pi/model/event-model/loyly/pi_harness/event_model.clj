(ns loyly.pi-harness.event-model
  "The harness-owned Grain service area: selection, preparation, grader approval,
   task receipts, observations, and measurement.

   It runs inside ORC's Grain runtime. ORC :sheet events describe workflow
   execution and feed harness projections; orc.sheet.event-model declares the
   ORC blocks this area reads or dispatches, so every edge resolves.
   Behaviour lives in the linked .allium rules and surfaces, never here."
  (:require [ai.obney.grain.event-model.interface :refer [defeventmodel]]
            [orc.sheet.event-model]))

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

    :loyly.pi-harness/submit-cut-task
    {:description "Submit a source-cut preparation task for one full commit and backend."
     :schema [:map [:request-id :string] [:source-commit :string] [:execution-backend [:enum "host" "gondolin"]]]
     :reads #{:loyly.pi-harness/task-receipts}
     :produces #{:loyly.pi-harness/task-submitted}
     :grain/allium [{:spec ".pi/model/operator-control-plane.allium"
                     :kind :rule :name "PrepareCutTask"}]}

    :loyly.pi-harness/submit-cycle-task
    {:description "Submit a regeneration cycle for an approved preparation."
     :schema [:map [:request-id :string] [:preparation-id :string] [:parent-cycle-id {:optional true} :string]]
     :reads #{:loyly.pi-harness/cycle-preparations :loyly.pi-harness/task-receipts}
     :produces #{:loyly.pi-harness/task-submitted}
     :grain/allium [{:spec ".pi/model/operator-control-plane.allium"
                     :kind :rule :name "SubmitCycleTask"}]}

    :loyly.pi-harness/submit-whole-plan-cycle
    {:description "Submit the whole plan of the pinned cut; its fixed preparation is approved by submission."
     :schema [:map [:request-id :string]]
     :reads #{:loyly.pi-harness/task-receipts}
     :produces #{:loyly.pi-harness/cycle-preparation-recorded :loyly.pi-harness/cycle-preparation-approved :loyly.pi-harness/task-submitted}
     :grain/allium [{:spec ".pi/model/operator-control-plane.allium"
                     :kind :rule :name "SubmitWholePlanCycle"}]}

    :loyly.pi-harness/start-prompt-optimization
    {:description "Start optimizing the builder instruction for one purpose."
     :schema [:map
              [:purpose [:enum "fresh-generation" "verification-repair"]]
              [:reflection-provider :string]
              [:reflection-model :string]
              [:trainset [:vector :any]]
              [:valset [:vector :any]]]
     :reads #{:loyly.pi-harness/prompt-optimizations}
     :produces #{:loyly.pi-harness/prompt-optimization-started}
     :grain/allium [{:spec ".pi/model/prompt-optimization.allium"
                     :kind :rule :name "StartPromptOptimization"}]}

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
                     :kind :rule :name "FileScore"}]}

    :loyly.pi-harness/launch-builder-session
    {:description "Launch one durable Pi builder session for an ORC builder tick, in the room ORC chose."
     :schema [:map
              [:tick-id :string]
              [:role [:enum "slice-builder" "repair-builder" "grader-author"]]
              [:room [:map
                     [:image :string]
                     [:cut-read-only :boolean]
                     [:writable-scope [:set :string]]
                     [:evidence-root {:optional true} :string]
                     [:egress-allowed :boolean]
                     [:inherits-host-environment :boolean]]]
              [:prompt-digest :string]]
     :reads #{:loyly.pi-harness/builder-sessions}
     :produces #{:loyly.pi-harness/builder-session-launched}
     :grain/allium [{:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "LaunchDurableBuilderSession"}]}

    :loyly.pi-harness/relaunch-builder-session
    {:description "Relaunch the durable session of a builder tick; it resumes from its committed turns."
     :schema [:map
              [:tick-id :string]
              [:room [:map
                     [:image :string]
                     [:cut-read-only :boolean]
                     [:writable-scope [:set :string]]
                     [:evidence-root {:optional true} :string]
                     [:egress-allowed :boolean]
                     [:inherits-host-environment :boolean]]]
              [:prompt-digest :string]]
     :reads #{:loyly.pi-harness/builder-sessions}
     :produces #{:loyly.pi-harness/builder-session-resumed}
     :grain/allium [{:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "ResumeDurableBuilderSession"}]}

    :loyly.pi-harness/bind-workspace-generation
    {:description "Bind the workspace generation a committed builder tool result produced."
     :schema [:map [:tick-id :string] [:committed-result :string] [:generation :int]]
     :reads #{:loyly.pi-harness/builder-sessions}
     :produces #{:loyly.pi-harness/workspace-generation-bound}
     :grain/allium [{:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "BindWorkspaceGeneration"}]}}

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
              [:grader-digest :string]
              [:diagram-digest :string]
              [:max-outer-cycles :int]]}

    :loyly.pi-harness/cycle-preparation-approved
    {:description "The exact digest of a complete cycle preparation was approved."
     :schema [:map [:preparation-id :string] [:preparation-digest :string]]}

    :loyly.pi-harness/task-submitted
    {:description "A cut or regeneration task was submitted under a request identity."
     :schema [:map
              [:task-id :int]
              [:kind [:enum "cut-preparation" "regeneration-cycle"]]
              [:request-id :string]
              [:request-digest :string]
              [:preparation-id {:optional true} :string]
              [:parent-cycle-id {:optional true} :string]]}

    :loyly.pi-harness/prompt-optimization-started
    {:description "A builder-instruction optimization started for one purpose."
     :schema [:map
              [:optimization-id :string]
              [:purpose [:enum "fresh-generation" "verification-repair"]]
              [:reflection-provider :string]
              [:reflection-model :string]
              [:trainset-digest :string]
              [:valset-digest :string]]}

    :loyly.pi-harness/task-receipt-issued
    {:description "A task identity was bound to its durable ORC tick."
     :schema [:map [:task-id :int] [:tick-id :string] [:cycle-id :string]]}

    :loyly.pi-harness/grader-approved
    {:description "An operator approved a sealed, calibrated grader by its exact digest."
     :schema [:map [:grader-digest :string] [:entry :string] [:items [:vector :string]]
              [:approved-by :string] [:approved-at :string]]}

    :loyly.pi-harness/builder-session-launched
    {:description "A durable Pi builder session was launched for one ORC tick, in the room ORC chose."
     :schema [:map
              [:tick-id :string]
              [:role [:enum "slice-builder" "repair-builder" "grader-author"]]
              [:session-id :string]
              [:room [:map
                      [:image :string]
                      [:cut-read-only :boolean]
                      [:writable-scope [:set :string]]
                      [:evidence-root {:optional true} :string]
                      [:egress-allowed :boolean]
                      [:inherits-host-environment :boolean]]]
              [:prompt-digest :string]]}

    :loyly.pi-harness/builder-session-resumed
    {:description "The durable session of a builder tick was relaunched and resumed; never a second session."
     :schema [:map [:tick-id :string] [:session-id :string] [:launches :int]]}

    :loyly.pi-harness/workspace-generation-bound
    {:description "A committed builder tool result was bound to the workspace generation it produced."
     :schema [:map [:tick-id :string] [:committed-result :string] [:generation :int]]}

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
              [:grader-digest :string]]}

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
    {:description "Submitted tasks, their request identities, and the ORC ticks they name."
     :consumes #{:loyly.pi-harness/task-submitted :loyly.pi-harness/task-receipt-issued}
     :version 1}

    :loyly.pi-harness/task-outcomes
    {:description "Each task's terminal outcome, reconstructed from ORC's durable tick facts."
     :consumes #{:loyly.pi-harness/task-receipt-issued :sheet/tree-tick-completed :sheet/tick-cancelled}
     :version 1}

    :loyly.pi-harness/prepared-cuts
    {:description "Prepared cuts and their slice composition, from completed cut tasks."
     :consumes #{:loyly.pi-harness/task-submitted :loyly.pi-harness/task-receipt-issued :sheet/tree-tick-completed}
     :version 1}

    :loyly.pi-harness/builder-sessions
    {:description "Builder sessions: each ORC builder tick, its durable session, room, launches, and workspace generations."
     :consumes #{:loyly.pi-harness/task-receipt-issued :sheet/node-execution-started :sheet/node-execution-completed
                 :loyly.pi-harness/builder-session-launched :loyly.pi-harness/builder-session-resumed
                 :loyly.pi-harness/workspace-generation-bound}
     :version 1}

    :loyly.pi-harness/prompt-optimizations
    {:description "Builder-instruction optimizations and their winning instructions."
     :consumes #{:loyly.pi-harness/prompt-optimization-started :loyly.pi-harness/task-receipt-issued :sheet/tree-tick-completed}
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

    :loyly.pi-harness/task-outcome
    {:description "Return a task's one terminal outcome, without rerunning it."
     :schema [:map [:task-id :int]]
     :reads #{:loyly.pi-harness/task-outcomes}
     :grain/allium [{:spec ".pi/model/operator-control-plane.allium"
                     :kind :rule :name "WaitForHarnessTask"}]}

    :loyly.pi-harness/slice-composition
    {:description "Return a prepared cut's product slices and their stamped items."
     :schema [:map [:cut :string]]
     :reads #{:loyly.pi-harness/prepared-cuts}
     :grain/allium [{:spec ".pi/model/regeneration-cycle.allium"
                     :kind :rule :name "ComposeCutSystemView"}]}

    :loyly.pi-harness/builder-session
    {:description "Return a read-only view of one builder session."
     :schema [:map [:builder-session-ref :string]]
     :reads #{:loyly.pi-harness/builder-sessions}
     :grain/allium [{:spec ".pi/model/operator-control-plane.allium"
                     :kind :rule :name "ObserveBuilderSession"}]}

    :loyly.pi-harness/prompt-optimization
    {:description "Return an optimization's winning instruction and its measured score."
     :schema [:map [:optimization-id :string]]
     :reads #{:loyly.pi-harness/prompt-optimizations}}

    :loyly.pi-harness/approved-graders
    {:description "Return every grader an operator approved, in approval order."
     :schema [:map]
     :reads #{:loyly.pi-harness/approved-graders}}}

   :todo-processors
   {:loyly.pi-harness/dispatch-submitted-task
    {:description "Dispatch each submitted task through ORC's Grain command boundary exactly once."
     :subscribes #{:loyly.pi-harness/task-submitted}
     :reads #{:loyly.pi-harness/task-receipt}
     :produces #{:sheet/tick-tree}
     :grain/allium [{:spec ".pi/model/operator-control-plane.allium"
                     :kind :rule :name "DispatchSubmittedTaskToOrc"}]}

    :loyly.pi-harness/launch-builder-for-tick
    {:description "When ORC starts a builder node, launch its durable Pi session; when ORC resumes it, relaunch the same session."
     :subscribes #{:sheet/node-execution-started}
     :reads #{:loyly.pi-harness/builder-session}
     :produces #{:loyly.pi-harness/launch-builder-session :loyly.pi-harness/relaunch-builder-session}
     :grain/allium [{:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "LaunchDurableBuilderSession"}
                    {:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "ResumeDurableBuilderSession"}]}}

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
                :loyly.pi-harness/cycle-measurement
                :loyly.pi-harness/task-outcome
                :loyly.pi-harness/slice-composition
                :loyly.pi-harness/builder-session
                :loyly.pi-harness/prompt-optimization}
     :commands #{:loyly.pi-harness/submit-cut-task
                 :loyly.pi-harness/submit-cycle-task
                 :loyly.pi-harness/submit-whole-plan-cycle
                 :loyly.pi-harness/start-prompt-optimization
                 :loyly.pi-harness/record-selection
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
   {:loyly.pi-harness/submit-cut-task
    {:description "A source-cut task is submitted and dispatched to ORC once."
     :steps [{:from [:screen :loyly.pi-harness/pi-regeneration]
              :to [:command :loyly.pi-harness/submit-cut-task]}
             {:from [:command :loyly.pi-harness/submit-cut-task]
              :to [:event :loyly.pi-harness/task-submitted]}
             {:from [:event :loyly.pi-harness/task-submitted]
              :to [:read-model :loyly.pi-harness/task-receipts]}
             {:from [:read-model :loyly.pi-harness/task-receipts]
              :to [:query :loyly.pi-harness/task-receipt]}
             {:from [:query :loyly.pi-harness/task-receipt]
              :to [:todo-processor :loyly.pi-harness/dispatch-submitted-task]}
             {:from [:todo-processor :loyly.pi-harness/dispatch-submitted-task]
              :to [:command :sheet/tick-tree]}]}

    :loyly.pi-harness/slice-composition
    {:description "A prepared cut's slice composition is read from completed cut tasks."
     :steps [{:from [:event :sheet/tree-tick-completed]
              :to [:read-model :loyly.pi-harness/prepared-cuts]}
             {:from [:read-model :loyly.pi-harness/prepared-cuts]
              :to [:query :loyly.pi-harness/slice-composition]}
             {:from [:query :loyly.pi-harness/slice-composition]
              :to [:screen :loyly.pi-harness/pi-regeneration]}]}

    :loyly.pi-harness/record-selection
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

    :loyly.pi-harness/approve-cycle-preparation
    {:description "A recorded preparation is approved by exact digest; approval alone starts nothing."
     :steps [{:from [:screen :loyly.pi-harness/pi-regeneration]
              :to [:command :loyly.pi-harness/approve-cycle-preparation]}
             {:from [:command :loyly.pi-harness/approve-cycle-preparation]
              :to [:event :loyly.pi-harness/cycle-preparation-approved]}
             {:from [:event :loyly.pi-harness/cycle-preparation-approved]
              :to [:read-model :loyly.pi-harness/cycle-preparations]}
             {:from [:read-model :loyly.pi-harness/cycle-preparations]
              :to [:query :loyly.pi-harness/cycle-preparation]}
             {:from [:query :loyly.pi-harness/cycle-preparation]
              :to [:screen :loyly.pi-harness/pi-regeneration]}]}

    :loyly.pi-harness/submit-cycle-task
    {:description "A cycle is submitted for an approved preparation and dispatched to ORC once."
     :steps [{:from [:screen :loyly.pi-harness/pi-regeneration]
              :to [:command :loyly.pi-harness/submit-cycle-task]}
             {:from [:command :loyly.pi-harness/submit-cycle-task]
              :to [:event :loyly.pi-harness/task-submitted]}
             {:from [:event :loyly.pi-harness/task-submitted]
              :to [:read-model :loyly.pi-harness/task-receipts]}
             {:from [:read-model :loyly.pi-harness/task-receipts]
              :to [:query :loyly.pi-harness/task-receipt]}
             {:from [:query :loyly.pi-harness/task-receipt]
              :to [:todo-processor :loyly.pi-harness/dispatch-submitted-task]}
             {:from [:todo-processor :loyly.pi-harness/dispatch-submitted-task]
              :to [:command :sheet/tick-tree]}]}

    :loyly.pi-harness/submit-whole-plan-cycle
    {:description "The whole plan is submitted with its fixed preparation and dispatched to ORC once."
     :steps [{:from [:screen :loyly.pi-harness/pi-regeneration]
              :to [:command :loyly.pi-harness/submit-whole-plan-cycle]}
             {:from [:command :loyly.pi-harness/submit-whole-plan-cycle]
              :to [:event :loyly.pi-harness/cycle-preparation-recorded]}
             {:from [:command :loyly.pi-harness/submit-whole-plan-cycle]
              :to [:event :loyly.pi-harness/cycle-preparation-approved]}
             {:from [:command :loyly.pi-harness/submit-whole-plan-cycle]
              :to [:event :loyly.pi-harness/task-submitted]}
             {:from [:event :loyly.pi-harness/task-submitted]
              :to [:read-model :loyly.pi-harness/task-receipts]}
             {:from [:read-model :loyly.pi-harness/task-receipts]
              :to [:query :loyly.pi-harness/task-receipt]}
             {:from [:query :loyly.pi-harness/task-receipt]
              :to [:todo-processor :loyly.pi-harness/dispatch-submitted-task]}
             {:from [:todo-processor :loyly.pi-harness/dispatch-submitted-task]
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

    :loyly.pi-harness/progress-reconciliation
    {:description "Reconnectable harness progress is a projection of ORC execution facts."
     :steps [{:from [:command :sheet/tick-tree]
              :to [:event :sheet/tree-tick-started]}
             {:from [:event :sheet/tree-tick-started]
              :to [:read-model :loyly.pi-harness/cycle-progress]}
             {:from [:read-model :loyly.pi-harness/cycle-progress]
              :to [:query :loyly.pi-harness/cycle-progress]}
             {:from [:query :loyly.pi-harness/cycle-progress]
              :to [:screen :loyly.pi-harness/pi-regeneration]}]}

    :loyly.pi-harness/task-outcome
    {:description "A task's terminal outcome is reconstructed from ORC facts, never by rerunning it."
     :steps [{:from [:event :sheet/tree-tick-completed]
              :to [:read-model :loyly.pi-harness/task-outcomes]}
             {:from [:read-model :loyly.pi-harness/task-outcomes]
              :to [:query :loyly.pi-harness/task-outcome]}
             {:from [:query :loyly.pi-harness/task-outcome]
              :to [:screen :loyly.pi-harness/pi-regeneration]}]}

    :loyly.pi-harness/durable-builder-launch
    {:description "ORC starts a builder tick; its durable Pi session is launched once, in the room ORC chose, and observed read-only."
     :steps [{:from [:event :sheet/node-execution-started]
              :to [:read-model :loyly.pi-harness/builder-sessions]}
             {:from [:read-model :loyly.pi-harness/builder-sessions]
              :to [:query :loyly.pi-harness/builder-session]}
             {:from [:query :loyly.pi-harness/builder-session]
              :to [:todo-processor :loyly.pi-harness/launch-builder-for-tick]}
             {:from [:todo-processor :loyly.pi-harness/launch-builder-for-tick]
              :to [:command :loyly.pi-harness/launch-builder-session]}
             {:from [:command :loyly.pi-harness/launch-builder-session]
              :to [:event :loyly.pi-harness/builder-session-launched]}
             {:from [:event :loyly.pi-harness/builder-session-launched]
              :to [:read-model :loyly.pi-harness/builder-sessions]}]}

    :loyly.pi-harness/builder-session
    {:description "A builder session is observed read-only from ORC node facts."
     :steps [{:from [:event :sheet/node-execution-started]
              :to [:read-model :loyly.pi-harness/builder-sessions]}
             {:from [:read-model :loyly.pi-harness/builder-sessions]
              :to [:query :loyly.pi-harness/builder-session]}
             {:from [:query :loyly.pi-harness/builder-session]
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

    :loyly.pi-harness/start-prompt-optimization
    {:description "A builder-instruction optimization is started and its winner read."
     :steps [{:from [:screen :loyly.pi-harness/pi-regeneration]
              :to [:command :loyly.pi-harness/start-prompt-optimization]}
             {:from [:command :loyly.pi-harness/start-prompt-optimization]
              :to [:event :loyly.pi-harness/prompt-optimization-started]}
             {:from [:event :loyly.pi-harness/prompt-optimization-started]
              :to [:read-model :loyly.pi-harness/prompt-optimizations]}
             {:from [:read-model :loyly.pi-harness/prompt-optimizations]
              :to [:query :loyly.pi-harness/prompt-optimization]}
             {:from [:query :loyly.pi-harness/prompt-optimization]
              :to [:screen :loyly.pi-harness/pi-regeneration]}]}}})
