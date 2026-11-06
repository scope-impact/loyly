(ns loyly.pi-harness.event-model
  "The harness-owned Grain service area: selection, preparation, the regeneration
   cycle's stage facts, the instrument, task receipts and outcomes, builder
   sessions, runtime evidence, and prompt optimization.

   It runs inside ORC's Grain runtime. ORC owns the workflow and decides when a
   stage runs; this area owns the cycle's facts and their invariants (ADR 0004).
   An ORC workflow node records a typed stage fact by issuing one of this area's
   commands (a flow entry). Where the harness reacts to a node starting or
   completing, it reads the anti-corruption projection node-stages, which
   translates ORC's :sheet node facts into a CycleStage and a builder role, and
   never a raw node id. orc.sheet.event-model declares the ORC blocks this area
   reads or dispatches, so every edge resolves.

   The screen issues operator intents only. Facts the system reports are issued
   by ORC's workflow nodes, by the provided builder host, or by todo processors
   reading to-do queries, never by the screen.
   Behaviour lives in the linked .allium rules and surfaces, never here."
  (:require [ai.obney.grain.event-model.interface :refer [defeventmodel]]
            [orc.sheet.event-model]))

;; Shared schemas. Every cycle is named by :cycle-id (its request identity) and
;; every ORC tick by :tick-id, a :uuid as in ORC.

(def ^:private cycle-stage
  [:enum "fence" "regenerate" "gate" "score" "scoreboard" "result"])

(def ^:private stage-failure-kind
  [:enum "refused" "skipped" "timed-out" "errored" "missing" "inconclusive"])

(def ^:private builder-role
  [:enum "slice-builder" "repair-builder" "grader-author"])

(def ^:private execution-backend
  [:enum "host" "gondolin"])

(def ^:private execution-profile
  [:enum "host-development" "gondolin-isolated" "automatic"])

(def ^:private builder-instruction-purpose
  [:enum "fresh-generation" "verification-repair"])

(def ^:private producer-authority
  [:enum "candidate" "harness-observer" "independent-validator"])

(def ^:private grader-replacement-reason
  [:enum "instrument-behavior-changed" "wording-replaced" "spec-grew"])

(def ^:private room-policy
  [:map
   [:target-stack :string]
   [:cut-read-only :boolean]
   [:writable-scope [:set :string]]
   [:evidence-root {:optional true} :string]
   [:egress-allowed :boolean]
   [:inherits-host-environment :boolean]])

(def ^:private observation
  [:map
   [:cycle-id :string]
   [:cut-digest :string]
   [:project-id :string]
   [:container-id :string]
   [:component-id :string]
   [:item-id :string]
   [:subject-digest :string]
   [:producer :string]
   [:producer-authority producer-authority]
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
   [:grader-digest :string]])

(def ^:private measurement
  [:map
   [:cycle-id :string]
   [:selection-id :string]
   [:grader-digest :string]
   [:cut :string]
   [:backend execution-backend]
   [:total :int]
   [:passed :int]
   [:failed :int]
   [:complete? :boolean]
   [:items [:vector [:map
                     [:item :string]
                     [:verdict [:enum "pass" "fail"]]
                     [:reached? :boolean]
                     [:evidence :string]]]]])

(def ^:private scoreboard
  [:map
   [:cycle-id :string]
   [:previous-cycle-id {:optional true} :string]
   [:pass-rate {:optional true} :double]
   [:denominator :int]
   [:shared-items [:vector :string]]
   [:newly-passing [:vector :string]]
   [:newly-failing [:vector :string]]
   [:new-items [:vector :string]]
   [:dropped-items [:vector :string]]])

(def ^:private gate-attempt
  [:map
   [:cycle-id :string]
   [:attempt :int]
   [:subject-digest :string]
   [:target-stack :string]
   [:passed? :boolean]
   [:evidence-root :string]
   [:suites-run :int]
   [:fresh-state-root? :boolean]
   [:answered-by-booted-candidate? :boolean]
   [:failure-reason {:optional true} :string]])

(defeventmodel :loyly.pi-harness
  {:description "Durable preparation approval, the regeneration cycle's stage facts, and measurement records for session-agnostic ORC regeneration cycles."

   :commands
   {;; ---- selection and preparation (screen)
    :loyly.pi-harness/record-selection
    {:description "Record the deterministic selection contract shown for review: a named slice and items of a prepared cut, or a slice proposal from the operator's intent resolved against it."
     :schema [:map
              [:cut :string]
              [:project-id :string]
              [:slice {:optional true} :string]
              [:item-ids {:optional true} [:vector :string]]
              [:operator-intent {:optional true} :string]]
     :reads #{:loyly.pi-harness/selections :loyly.pi-harness/prepared-cuts}
     :produces #{:loyly.pi-harness/selection-recorded}
     :grain/allium [{:spec ".pi/model/regeneration-cycle.allium"
                     :kind :rule :name "PreviewSelection"}
                    {:spec ".pi/model/regeneration-cycle.allium"
                     :kind :rule :name "ProposeSelectionFromIntent"}]}

    :loyly.pi-harness/approve-selection
    {:description "Approve only the exact digest of a recorded selection."
     :schema [:map [:selection-id :string] [:selection-digest :string]]
     :reads #{:loyly.pi-harness/selections}
     :produces #{:loyly.pi-harness/selection-approved}
     :grain/allium [{:spec ".pi/model/regeneration-cycle.allium"
                     :kind :rule :name "ApproveSelection"}]}

    :loyly.pi-harness/record-cycle-preparation
    {:description "Record the complete cycle preparation shown for review."
     :schema [:map
              [:selection-id :string]
              [:scope [:vector :string]]
              [:target-stack :string]
              [:execution-profile execution-profile]
              [:max-outer-cycles :int]]
     :reads #{:loyly.pi-harness/selections :loyly.pi-harness/cycle-preparations :loyly.pi-harness/graders}
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

    ;; ---- cuts and tasks
    :loyly.pi-harness/submit-cut-task
    {:description "Submit a source-cut preparation task for one full commit and backend."
     :schema [:map [:request-id :string] [:source-commit :string] [:execution-backend execution-backend]]
     :reads #{:loyly.pi-harness/task-receipts}
     :produces #{:loyly.pi-harness/task-submitted}
     :grain/allium [{:spec ".pi/model/operator-control-plane.allium"
                     :kind :rule :name "PrepareCutTask"}]}

    :loyly.pi-harness/record-prepared-cut
    {:description "Record the fenced cut and its system composition that a cut-preparation task produced. Issued by ORC's cut-preparation workflow node, never by the screen."
     :schema [:map
              [:task-id :int]
              [:source-commit :string]
              [:backend execution-backend]
              [:tree-digest :string]
              [:slices [:vector [:map
                                 [:slice :string]
                                 [:component :string]
                                 [:container :string]
                                 [:items [:vector :string]]]]]]
     :reads #{:loyly.pi-harness/task-receipts :loyly.pi-harness/prepared-cuts}
     :produces #{:loyly.pi-harness/cut-prepared}
     :grain/allium [{:spec ".pi/model/regeneration-cycle.allium"
                     :kind :rule :name "PrepareCut"}
                    {:spec ".pi/model/regeneration-cycle.allium"
                     :kind :rule :name "ComposeCutSystemView"}]}

    :loyly.pi-harness/submit-cycle-task
    {:description "Submit a regeneration cycle for an approved preparation, on a backend its execution profile admits. A repeated request identity returns the original receipt; one reused for a different request is refused."
     :schema [:map [:request-id :string] [:preparation-id :string] [:execution-backend execution-backend] [:parent-cycle-id {:optional true} :string]]
     :reads #{:loyly.pi-harness/cycle-preparations :loyly.pi-harness/task-receipts}
     :produces #{:loyly.pi-harness/cycle-submitted :loyly.pi-harness/task-submitted}
     :grain/allium [{:spec ".pi/model/regeneration-cycle.allium"
                     :kind :rule :name "SubmitCycle"}
                    {:spec ".pi/model/operator-control-plane.allium"
                     :kind :rule :name "OpenCycleTask"}
                    {:spec ".pi/model/operator-control-plane.allium"
                     :kind :rule :name "RepeatIdenticalSubmission"}
                    {:spec ".pi/model/operator-control-plane.allium"
                     :kind :rule :name "RefuseConflictingSubmission"}]}

    :loyly.pi-harness/submit-whole-plan-cycle
    {:description "Submit the whole plan of the pinned cut; its fixed preparation is approved by submission."
     :schema [:map [:request-id :string]]
     :reads #{:loyly.pi-harness/task-receipts}
     :produces #{:loyly.pi-harness/cycle-preparation-recorded :loyly.pi-harness/cycle-preparation-approved
                 :loyly.pi-harness/cycle-submitted :loyly.pi-harness/task-submitted}
     :grain/allium [{:spec ".pi/model/regeneration-cycle.allium"
                     :kind :rule :name "ApproveWholePlanBySubmission"}
                    {:spec ".pi/model/operator-control-plane.allium"
                     :kind :rule :name "OpenCycleTask"}
                    {:spec ".pi/model/operator-control-plane.allium"
                     :kind :rule :name "RepeatIdenticalSubmission"}
                    {:spec ".pi/model/operator-control-plane.allium"
                     :kind :rule :name "RefuseConflictingSubmission"}]}

    :loyly.pi-harness/record-task-receipt
    {:description "Bind an issued task identity to the ORC tick that started it, so a replacement ORC worker can respond for it. Issued by issue-task-receipt, never by the screen."
     :schema [:map [:task-id :int] [:tick-id :uuid] [:cycle-id {:optional true} :string]]
     :reads #{:loyly.pi-harness/task-receipts}
     :produces #{:loyly.pi-harness/task-receipt-issued}
     :grain/allium [{:spec ".pi/model/operator-control-plane.allium"
                     :kind :rule :name "StartWorkflowTask"}]}

    :loyly.pi-harness/complete-task
    {:description "Record that ORC completed a task's tick, with its outcome. Issued by record-task-outcome."
     :schema [:map [:task-id :int] [:tick-id :uuid] [:outcome :string]]
     :reads #{:loyly.pi-harness/task-outcomes}
     :produces #{:loyly.pi-harness/task-completed}
     :grain/allium [{:spec ".pi/model/operator-control-plane.allium"
                     :kind :rule :name "CompleteWorkflowTask"}]}

    :loyly.pi-harness/fail-task
    {:description "Record that ORC failed or cancelled a task's tick, with the stage and reason. Issued by record-task-outcome."
     :schema [:map [:task-id :int] [:tick-id :uuid] [:stage {:optional true} cycle-stage] [:reason :string]]
     :reads #{:loyly.pi-harness/task-outcomes}
     :produces #{:loyly.pi-harness/task-failed}
     :grain/allium [{:spec ".pi/model/operator-control-plane.allium"
                     :kind :rule :name "FailWorkflowTask"}]}

    :loyly.pi-harness/record-child-cycle
    {:description "Record that an operator continues a terminal task's cycle with an approved child preparation, within its outer-cycle budget."
     :schema [:map [:parent-task-id :int] [:preparation-id :string] [:reason :string]]
     :reads #{:loyly.pi-harness/task-outcomes :loyly.pi-harness/cycle-preparations :loyly.pi-harness/regeneration-cycles}
     :produces #{:loyly.pi-harness/child-cycle-lineage-recorded}
     :grain/allium [{:spec ".pi/model/operator-control-plane.allium"
                     :kind :rule :name "ContinueWithChildCycle"}]}

    ;; ---- the regeneration cycle (ORC workflow nodes and stage policies)
    :loyly.pi-harness/start-cycle
    {:description "Start a submitted cycle at the fence when ORC starts the tick dispatched for it. Issued by start-dispatched-cycle."
     :schema [:map [:cycle-id :string] [:tick-id :uuid]]
     :reads #{:loyly.pi-harness/regeneration-cycles}
     :produces #{:loyly.pi-harness/cycle-started}
     :grain/allium [{:spec ".pi/model/regeneration-cycle.allium"
                     :kind :rule :name "StartCycle"}]}

    :loyly.pi-harness/record-harness-self-check
    {:description "Record the harness self-check: its calibration fixtures and every instrument golden reproduced, or the named failing checks. Issued by ORC's fence node."
     :schema [:map
              [:harness-digest :string]
              [:golden-digests [:vector :string]]
              [:failing-checks [:vector :string]]]
     :reads #{:loyly.pi-harness/harness-self-checks}
     :produces #{:loyly.pi-harness/harness-self-check-recorded}
     :grain/allium [{:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "RunHarnessSelfCheck"}]}

    :loyly.pi-harness/prove-clean-room
    {:description "Record a clean room proved physical for a running cycle at the fence; refused without a passing self-check for the same harness digest, an approved sealed grader covering the selection, or a fenced path that matched something. Issued by ORC's fence node."
     :schema [:map
              [:cycle-id :string]
              [:cut-digest :string]
              [:fenced-paths [:vector :string]]
              [:harness-digest :string]]
     :reads #{:loyly.pi-harness/regeneration-cycles
              :loyly.pi-harness/harness-self-checks
              :loyly.pi-harness/cycle-preparations
              :loyly.pi-harness/graders}
     :produces #{:loyly.pi-harness/clean-room-proved}
     :grain/allium [{:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "FenceCleanRoom"}]}

    :loyly.pi-harness/record-slice-build
    {:description "Record one slice builder's completed work, in plan order, on the first regenerate attempt. Issued by ORC's regenerate node."
     :schema [:map
              [:cycle-id :string]
              [:slice :string]
              [:position :int]
              [:tick-id :uuid]
              [:generated-paths [:vector :string]]]
     :reads #{:loyly.pi-harness/regeneration-cycles :loyly.pi-harness/builder-sessions}
     :produces #{:loyly.pi-harness/slice-built}
     :grain/allium [{:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "BuildSlice"}]}

    :loyly.pi-harness/record-candidate
    {:description "Record the candidate a regenerate attempt produced; the cycle moves to the gate. Issued by ORC's regenerate node."
     :schema [:map
              [:cycle-id :string]
              [:attempt :int]
              [:tick-id :uuid]
              [:subject-digest :string]
              [:generated-paths [:vector :string]]
              [:tool-calls :int]]
     :reads #{:loyly.pi-harness/regeneration-cycles :loyly.pi-harness/builder-sessions}
     :produces #{:loyly.pi-harness/candidate-regenerated}
     :grain/allium [{:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "RegenerateCandidate"}]}

    :loyly.pi-harness/record-gate-attempt
    {:description "Record what the gate room, which the builder does not control, observed for one candidate. Issued by ORC's gate node."
     :schema gate-attempt
     :reads #{:loyly.pi-harness/regeneration-cycles :loyly.pi-harness/gate-attempts}
     :produces #{:loyly.pi-harness/gate-attempt-recorded}
     :grain/allium [{:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "RecordGateAttempt"}]}

    :loyly.pi-harness/advance-cycle-stage
    {:description "Move a running cycle to its next stage in the declared order: a passed gate to the score, a failed gate under three attempts back to regenerate, a filed measurement to the scoreboard, a reported scoreboard to the result. Issued by the stage policies."
     :schema [:map [:cycle-id :string] [:from-stage cycle-stage] [:to-stage cycle-stage]]
     :reads #{:loyly.pi-harness/regeneration-cycles}
     :produces #{:loyly.pi-harness/cycle-stage-advanced}
     :grain/allium [{:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "PassGate"}
                    {:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "RepairAfterFailedGate"}
                    {:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "AdvanceToScoreboard"}
                    {:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "RecordScoreboard"}]}

    :loyly.pi-harness/fail-cycle-stage
    {:description "Fail a running cycle at its current stage with a kind and reason, preserving every approved denominator item. Issued by the stage policies (a gate failed three times, a refused score) or by ORC's workflow (a stage timed out, errored, or was skipped)."
     :schema [:map
              [:cycle-id :string]
              [:stage cycle-stage]
              [:failure-kind stage-failure-kind]
              [:reason :string]]
     :reads #{:loyly.pi-harness/regeneration-cycles :loyly.pi-harness/cycle-preparations}
     :produces #{:loyly.pi-harness/cycle-failed}
     :grain/allium [{:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "FailCycleStage"}
                    {:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "FailAfterThreeGateAttempts"}
                    {:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "FailRefusedScore"}]}

    :loyly.pi-harness/conclude-cycle
    {:description "Make a cycle that reached its result terminal: passed when its one measurement is complete with no failure, otherwise failed; either way naming the candidate its gate passed. Issued by conclude-cycle-result."
     :schema [:map [:cycle-id :string]]
     :reads #{:loyly.pi-harness/regeneration-cycles
              :loyly.pi-harness/cycle-measurements
              :loyly.pi-harness/gate-attempts
              :loyly.pi-harness/cycle-preparations}
     :produces #{:loyly.pi-harness/cycle-passed :loyly.pi-harness/cycle-failed}
     :grain/allium [{:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "PassCycle"}
                    {:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "FailCycle"}]}

    ;; ---- builder sessions (processors and the provided builder host)
    :loyly.pi-harness/launch-builder-session
    {:description "Launch one durable Pi builder session for an ORC builder tick, in the room ORC chose."
     :schema [:map
              [:tick-id :uuid]
              [:cycle-id {:optional true} :string]
              [:attempt {:optional true} :int]
              [:role builder-role]
              [:room room-policy]
              [:production-prompt-digest :string]]
     :reads #{:loyly.pi-harness/builder-sessions}
     :produces #{:loyly.pi-harness/builder-session-launched}
     :grain/allium [{:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "LaunchDurableBuilderSession"}]}

    :loyly.pi-harness/relaunch-builder-session
    {:description "Relaunch the durable session of a builder tick; it resumes from its committed turns."
     :schema [:map
              [:tick-id :uuid]
              [:room room-policy]
              [:production-prompt-digest :string]]
     :reads #{:loyly.pi-harness/builder-sessions}
     :produces #{:loyly.pi-harness/builder-session-resumed}
     :grain/allium [{:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "ResumeDurableBuilderSession"}]}

    :loyly.pi-harness/commit-builder-tool-result
    {:description "The provided builder host reports that it committed one tool result of a durable session and the workspace generation it left."
     :schema [:map [:tick-id :uuid] [:committed-result :string] [:generation :int]]
     :reads #{:loyly.pi-harness/builder-sessions}
     :produces #{:loyly.pi-harness/builder-tool-result-committed}
     :grain/allium [{:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "BindWorkspaceGeneration"}]}

    :loyly.pi-harness/end-builder-session
    {:description "The provided builder host reports that a durable session's loop ended: finished (completed) or stopped without finishing (failed)."
     :schema [:map [:tick-id :uuid] [:finished? :boolean]]
     :reads #{:loyly.pi-harness/builder-sessions}
     :produces #{:loyly.pi-harness/builder-session-ended}
     :grain/allium [{:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "EndBuilderSession"}]}

    :loyly.pi-harness/bind-workspace-generation
    {:description "Bind the workspace generation a committed builder tool result produced. Issued by bind-committed-result."
     :schema [:map [:tick-id :uuid] [:committed-result :string] [:generation :int]]
     :reads #{:loyly.pi-harness/builder-sessions}
     :produces #{:loyly.pi-harness/workspace-generation-bound}
     :grain/allium [{:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "BindWorkspaceGeneration"}]}

    :loyly.pi-harness/intervene-in-builder-session
    {:description "Record an operator's pause, steer, or fork of a builder session at a safe boundary; the session's work is marked assisted."
     :schema [:map [:tick-id :uuid] [:kind [:enum "pause" "steer" "fork"]]]
     :reads #{:loyly.pi-harness/builder-sessions}
     :produces #{:loyly.pi-harness/builder-intervention-recorded}
     :grain/allium [{:spec ".pi/model/operator-control-plane.allium"
                     :kind :rule :name "InterveneInBuilderSession"}]}

    ;; ---- instrument
    :loyly.pi-harness/derive-checklist
    {:description "Derive the checklist of an approved selection: one item per stamped scenario, ordered by file then position; refused when empty or when a scenario is unstamped."
     :schema [:map [:selection-id :string]]
     :reads #{:loyly.pi-harness/selections :loyly.pi-harness/checklists}
     :produces #{:loyly.pi-harness/checklist-derived}
     :grain/allium [{:spec ".pi/model/instrument.allium"
                     :kind :rule :name "DeriveChecklist"}]}

    :loyly.pi-harness/request-grader
    {:description "Ask for a grader for a derived checklist: an approved grader that covers it is recalibrated; otherwise a grader author is requested."
     :schema [:map [:selection-id :string]]
     :reads #{:loyly.pi-harness/checklists :loyly.pi-harness/graders}
     :produces #{:loyly.pi-harness/grader-authoring-requested :loyly.pi-harness/grader-recalibration-requested}
     :grain/allium [{:spec ".pi/model/instrument.allium"
                     :kind :rule :name "RequestGraderAuthoring"}
                    {:spec ".pi/model/instrument.allium"
                     :kind :rule :name "ReuseApprovedGrader"}]}

    :loyly.pi-harness/record-authored-grader
    {:description "Record the grader a grader author session wrote; it is calibrating. A replacement names the grader it replaces and its recorded reason. Issued by ORC's grader-author node."
     :schema [:map
              [:grader-digest :string]
              [:scenario-digests [:vector :string]]
              [:author-session-id :string]
              [:attempt :int]
              [:replaces {:optional true} :string]
              [:replacement-reason {:optional true} grader-replacement-reason]]
     :reads #{:loyly.pi-harness/graders :loyly.pi-harness/builder-sessions}
     :produces #{:loyly.pi-harness/grader-authored}
     :grain/allium [{:spec ".pi/model/instrument.allium"
                     :kind :rule :name "AuthorGrader"}]}

    :loyly.pi-harness/seal-grader
    {:description "Seal a calibrating grader whose carried cases passed and uncarried cases failed, authored from the spec and protocol only. Issued by ORC's calibration node."
     :schema [:map
              [:grader-digest :string]
              [:carried-passed? :boolean]
              [:uncarried-failed? :boolean]]
     :reads #{:loyly.pi-harness/graders}
     :produces #{:loyly.pi-harness/grader-sealed}
     :grain/allium [{:spec ".pi/model/instrument.allium"
                     :kind :rule :name "SealCalibratedGrader"}]}

    :loyly.pi-harness/discard-grader
    {:description "Discard a grader that failed calibration; under three attempts its re-authoring is requested, at the third the instrument is declared unavailable. Issued by ORC's calibration node."
     :schema [:map
              [:grader-digest :string]
              [:failed-cases [:vector :string]]]
     :reads #{:loyly.pi-harness/graders}
     :produces #{:loyly.pi-harness/grader-discarded
                 :loyly.pi-harness/grader-authoring-requested
                 :loyly.pi-harness/instrument-declared-unavailable}
     :grain/allium [{:spec ".pi/model/instrument.allium"
                     :kind :rule :name "ReauthorUncalibratedGrader"}
                    {:spec ".pi/model/instrument.allium"
                     :kind :rule :name "StopAfterThreeUncalibratedGraders"}]}

    :loyly.pi-harness/approve-grader
    {:description "Record an operator's approval of a sealed, calibrated grader's exact digest."
     :schema [:map [:grader-digest :string] [:approved-by :string]]
     :reads #{:loyly.pi-harness/graders}
     :produces #{:loyly.pi-harness/grader-approved}
     :grain/allium [{:spec ".pi/model/instrument.allium"
                     :kind :rule :name "ApproveExactGrader"}]}

    :loyly.pi-harness/retire-grader
    {:description "Retire the approved grader an approved successor replaces, for the successor's recorded reason. Issued by retire-replaced-grader."
     :schema [:map [:grader-digest :string] [:successor-digest :string]]
     :reads #{:loyly.pi-harness/graders}
     :produces #{:loyly.pi-harness/grader-replaced}
     :grain/allium [{:spec ".pi/model/instrument.allium"
                     :kind :rule :name "RetireReplacedGrader"}]}

    :loyly.pi-harness/file-score
    {:description "File the approved sealed grader's verdicts for one cycle, one per checklist item; unreached items fail. A verdict that is neither pass nor fail is refused by the schema; an unknown item or no verdicts at all is refused as score-refused. Issued by file-cycle-score when ORC's score node completes, never by the screen."
     :schema [:map
              [:cycle-id :string]
              [:grader-digest :string]
              [:verdicts [:vector [:map [:item :string] [:verdict [:enum "pass" "fail"]] [:evidence {:optional true} :string]]]]]
     :reads #{:loyly.pi-harness/selections
              :loyly.pi-harness/cycle-preparations
              :loyly.pi-harness/graders
              :loyly.pi-harness/regeneration-cycles
              :loyly.pi-harness/cycle-measurements}
     :produces #{:loyly.pi-harness/cycle-measurement-recorded :loyly.pi-harness/score-refused}
     :grain/allium [{:spec ".pi/model/instrument.allium"
                     :kind :rule :name "FileScore"}
                    {:spec ".pi/model/instrument.allium"
                     :kind :rule :name "RefuseEmptyScore"}
                    {:spec ".pi/model/instrument.allium"
                     :kind :rule :name "RefuseUnknownVerdicts"}
                    {:spec ".pi/model/instrument.allium"
                     :kind :rule :name "RefuseInvalidVerdicts"}]}

    :loyly.pi-harness/report-scoreboard
    {:description "Report a scored cycle's scoreboard to a person: compared with the previous measurement of the same selection on shared items, newly passing and newly failing separately, never a net. Issued by report-cycle-scoreboard."
     :schema [:map [:cycle-id :string]]
     :reads #{:loyly.pi-harness/regeneration-cycles
              :loyly.pi-harness/cycle-measurements
              :loyly.pi-harness/scoreboards}
     :produces #{:loyly.pi-harness/scoreboard-reported}
     :grain/allium [{:spec ".pi/model/instrument.allium"
                     :kind :rule :name "ReportFirstScoreboard"}
                    {:spec ".pi/model/instrument.allium"
                     :kind :rule :name "ReportComparedScoreboard"}]}

    ;; ---- runtime evidence
    :loyly.pi-harness/record-observation
    {:description "Record one evidence-backed item observation a validation room produced. Issued by record-validation-evidence when ORC reports a validation node completed, never by the screen."
     :schema observation
     :reads #{:loyly.pi-harness/cycle-observations :loyly.pi-harness/node-stages}
     :produces #{:loyly.pi-harness/observation-recorded}
     :grain/allium [{:spec ".pi/model/runtime-evidence.allium"
                     :kind :rule :name "RecordRuntimeSignal"}
                    {:spec ".pi/model/runtime-evidence.allium"
                     :kind :rule :name "EvaluateCompleteInvariant"}
                    {:spec ".pi/model/runtime-evidence.allium"
                     :kind :rule :name "MarkIncompleteInvariantInconclusive"}]}

    :loyly.pi-harness/record-validation-room
    {:description "Record a validation room opened for one cycle, with its pinned read-only capture policy and its own writable evidence volume. Issued by ORC's validation node."
     :schema [:map
              [:cycle-id :string]
              [:capture-policy [:map [:digest :string] [:mount-path :string] [:read-only? :boolean]]]
              [:evidence-volume [:map [:volume-id :string] [:mount-path :string] [:writable? :boolean]]]]
     :reads #{:loyly.pi-harness/cycle-observations}
     :produces #{:loyly.pi-harness/validation-room-opened}
     :grain/allium [{:spec ".pi/model/runtime-evidence.allium"
                     :kind :rule :name "OpenValidationRoom"}]}

    :loyly.pi-harness/bind-baseline-trace
    {:description "Bind a passing baseline grade to its complete trace artifact from the independent validator. Issued by ORC's validation node."
     :schema [:map
              [:cycle-id :string]
              [:evaluation-id :string]
              [:evidence-digest :string]
              [:trace [:map
                       [:path :string]
                       [:digest :string]
                       [:complete? :boolean]
                       [:producer-authority producer-authority]]]]
     :reads #{:loyly.pi-harness/cycle-observations}
     :produces #{:loyly.pi-harness/baseline-trace-bound}
     :grain/allium [{:spec ".pi/model/runtime-evidence.allium"
                     :kind :rule :name "BindSuccessfulBaselineTrace"}]}

    ;; ---- prompt optimization
    :loyly.pi-harness/start-prompt-optimization
    {:description "Start optimizing the builder instruction for one purpose."
     :schema [:map
              [:purpose builder-instruction-purpose]
              [:reflection-provider :string]
              [:reflection-model :string]
              [:trainset [:vector :string]]
              [:valset [:vector :string]]]
     :reads #{:loyly.pi-harness/prompt-optimizations}
     :produces #{:loyly.pi-harness/prompt-optimization-started}
     :grain/allium [{:spec ".pi/model/prompt-optimization.allium"
                     :kind :rule :name "StartPromptOptimization"}]}

    :loyly.pi-harness/record-production-prompt
    {:description "Record a production prompt ORC rendered for one builder session role from its own instructions and the runtime task context, with its exact digest. Issued by ORC."
     :schema [:map
              [:role builder-role]
              [:system-instruction-digest :string]
              [:task-instruction-digest :string]
              [:runtime-task-context-digest :string]]
     :reads #{:loyly.pi-harness/prompt-optimizations}
     :produces #{:loyly.pi-harness/production-prompt-rendered}
     :grain/allium [{:spec ".pi/model/prompt-optimization.allium"
                     :kind :rule :name "RenderProductionPrompt"}]}

    :loyly.pi-harness/propose-instruction-candidate
    {:description "Record an instruction candidate the pinned reflection model proposed for an evaluating optimization, with its call digest. Issued by ORC."
     :schema [:map
              [:optimization-id :string]
              [:candidate-id :string]
              [:instruction-id :string]
              [:purpose builder-instruction-purpose]
              [:text-digest :string]
              [:reflection-provider :string]
              [:reflection-model :string]
              [:proposal-call-digest :string]]
     :reads #{:loyly.pi-harness/prompt-optimizations}
     :produces #{:loyly.pi-harness/instruction-candidate-proposed}
     :grain/allium [{:spec ".pi/model/prompt-optimization.allium"
                     :kind :rule :name "ProposeInstructionCandidate"}]}

    :loyly.pi-harness/measure-instruction-candidate
    {:description "Record a proposed candidate's independent, purpose-specific measurement against the exact production prompt it ran under. Issued by ORC."
     :schema [:map
              [:candidate-id :string]
              [:production-prompt-digest :string]
              [:score :double]
              [:evidence-digest :string]]
     :reads #{:loyly.pi-harness/prompt-optimizations}
     :produces #{:loyly.pi-harness/instruction-candidate-measured}
     :grain/allium [{:spec ".pi/model/prompt-optimization.allium"
                     :kind :rule :name "MeasureInstructionCandidate"}]}

    :loyly.pi-harness/reject-instruction-candidate
    {:description "Record that GEPA dropped a proposed or measured candidate of an evaluating optimization. Issued by ORC."
     :schema [:map [:candidate-id :string]]
     :reads #{:loyly.pi-harness/prompt-optimizations}
     :produces #{:loyly.pi-harness/instruction-candidate-rejected}
     :grain/allium [{:spec ".pi/model/prompt-optimization.allium"
                     :kind :rule :name "RejectInstructionCandidate"}]}

    :loyly.pi-harness/select-winning-instruction
    {:description "Select the highest measured candidate as the purpose's one active instruction, durably, superseding the purpose's previous winner. Issued by ORC."
     :schema [:map [:optimization-id :string] [:candidate-id :string]]
     :reads #{:loyly.pi-harness/prompt-optimizations}
     :produces #{:loyly.pi-harness/winning-instruction-selected}
     :grain/allium [{:spec ".pi/model/prompt-optimization.allium"
                     :kind :rule :name "SelectWinningInstruction"}]}

    :loyly.pi-harness/fail-prompt-optimization
    {:description "Record that an evaluating optimization ended with no measured winner. Issued by ORC."
     :schema [:map [:optimization-id :string] [:reason :string]]
     :reads #{:loyly.pi-harness/prompt-optimizations}
     :produces #{:loyly.pi-harness/prompt-optimization-failed}
     :grain/allium [{:spec ".pi/model/prompt-optimization.allium"
                     :kind :rule :name "FailPromptOptimization"}]}}

   :events
   {;; ---- selection and preparation
    :loyly.pi-harness/selection-recorded
    {:description "A deterministic selection contract was recorded for review."
     :schema [:map
              [:selection-id :string]
              [:selection-digest :string]
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
              [:selection-id :string]
              [:selection-digest :string]
              [:scope [:vector :string]]
              [:target-stack :string]
              [:execution-profile execution-profile]
              [:cut-digest :string]
              [:grader-digest :string]
              [:diagram-digest :string]
              [:max-outer-cycles :int]]}

    :loyly.pi-harness/cycle-preparation-approved
    {:description "The exact digest of a complete cycle preparation was approved."
     :schema [:map [:preparation-id :string] [:preparation-digest :string]]}

    ;; ---- cuts and tasks
    :loyly.pi-harness/task-submitted
    {:description "A cut or regeneration task was submitted under a request identity."
     :schema [:map
              [:task-id :int]
              [:kind [:enum "cut-preparation" "regeneration-cycle"]]
              [:request-id :string]
              [:request-digest :string]
              [:preparation-id {:optional true} :string]
              [:parent-cycle-id {:optional true} :string]]}

    :loyly.pi-harness/cut-prepared
    {:description "A fenced cut was prepared, with the system composition that maps every stamped item to exactly one slice, component, and container."
     :schema [:map
              [:cut-id :string]
              [:task-id :int]
              [:source-commit :string]
              [:backend execution-backend]
              [:tree-digest :string]
              [:composition-digest :string]
              [:slices [:vector [:map
                                 [:slice :string]
                                 [:component :string]
                                 [:container :string]
                                 [:items [:vector :string]]]]]]}

    :loyly.pi-harness/task-receipt-issued
    {:description "A task identity was bound to its durable ORC tick."
     :schema [:map [:task-id :int] [:tick-id :uuid] [:cycle-id {:optional true} :string]]}

    :loyly.pi-harness/task-completed
    {:description "ORC completed a task's tick; its outcome is now stable."
     :schema [:map [:task-id :int] [:tick-id :uuid] [:outcome :string]]}

    :loyly.pi-harness/task-failed
    {:description "ORC failed or cancelled a task's tick; its outcome names the stage and reason."
     :schema [:map [:task-id :int] [:tick-id :uuid] [:stage {:optional true} cycle-stage] [:reason :string]]}

    :loyly.pi-harness/child-cycle-lineage-recorded
    {:description "A terminal cycle was continued with an approved child preparation, for a recorded reason."
     :schema [:map [:parent-cycle-id :string] [:preparation-id :string] [:reason :string]]}

    ;; ---- the regeneration cycle
    :loyly.pi-harness/cycle-submitted
    {:description "A regeneration cycle was created at the fence for an approved preparation, under its request identity."
     :schema [:map
              [:cycle-id :string]
              [:request-digest :string]
              [:preparation-id :string]
              [:parent-cycle-id {:optional true} :string]]}

    :loyly.pi-harness/cycle-started
    {:description "ORC started a submitted cycle from the tick dispatched for it; the cycle runs from the fence."
     :schema [:map [:cycle-id :string] [:tick-id :uuid]]}

    :loyly.pi-harness/harness-self-check-recorded
    {:description "The harness self-check ran for one harness digest; it passed only when every golden was reproduced."
     :schema [:map
              [:harness-digest :string]
              [:golden-digests [:vector :string]]
              [:passed? :boolean]
              [:failing-checks [:vector :string]]]}

    :loyly.pi-harness/clean-room-proved
    {:description "A clean room was proved for a cycle: a repository of the cut's one commit with every fenced path absent from the tree and the object graph. The cycle moves to regenerate."
     :schema [:map
              [:cycle-id :string]
              [:cut-digest :string]
              [:fenced-paths [:vector :string]]
              [:harness-digest :string]]}

    :loyly.pi-harness/slice-built
    {:description "One fresh slice builder completed its slice, in plan order, extending the cycle's candidate."
     :schema [:map
              [:cycle-id :string]
              [:slice :string]
              [:position :int]
              [:tick-id :uuid]
              [:generated-paths [:vector :string]]]}

    :loyly.pi-harness/candidate-regenerated
    {:description "A regenerate attempt produced a candidate subject; the cycle moves to the gate."
     :schema [:map
              [:cycle-id :string]
              [:attempt :int]
              [:tick-id :uuid]
              [:subject-digest :string]
              [:generated-paths [:vector :string]]
              [:tool-calls :int]]}

    :loyly.pi-harness/gate-attempt-recorded
    {:description "A gate room the builder does not control booted one candidate fresh and recorded what it observed."
     :schema gate-attempt}

    :loyly.pi-harness/cycle-stage-advanced
    {:description "A running cycle moved to its next stage in the declared order."
     :schema [:map [:cycle-id :string] [:from-stage cycle-stage] [:to-stage cycle-stage]]}

    :loyly.pi-harness/cycle-passed
    {:description "A cycle reached its result with a complete measurement and no failed item; it names the candidate its gate passed."
     :schema [:map [:cycle-id :string] [:candidate-digest :string]]}

    :loyly.pi-harness/cycle-failed
    {:description "A cycle became terminal without passing: a stage did not succeed (named with its kind and reason) or its measurement was incomplete or failed an item. Every approved denominator item is preserved."
     :schema [:map
              [:cycle-id :string]
              [:failure-stage {:optional true} cycle-stage]
              [:failure-kind {:optional true} stage-failure-kind]
              [:failure-reason {:optional true} :string]
              [:candidate-digest {:optional true} :string]
              [:denominator [:vector :string]]]}

    ;; ---- builder sessions
    :loyly.pi-harness/builder-session-launched
    {:description "A durable Pi builder session was launched for one ORC tick, in the room ORC chose."
     :schema [:map
              [:tick-id :uuid]
              [:cycle-id {:optional true} :string]
              [:attempt {:optional true} :int]
              [:role builder-role]
              [:session-id :string]
              [:room room-policy]
              [:production-prompt-digest :string]]}

    :loyly.pi-harness/builder-session-resumed
    {:description "The durable session of a builder tick was relaunched and resumed; never a second session."
     :schema [:map [:tick-id :uuid] [:session-id :string] [:launches :int]]}

    :loyly.pi-harness/builder-tool-result-committed
    {:description "The builder host committed one tool result of a durable session, leaving one workspace generation."
     :schema [:map [:tick-id :uuid] [:committed-result :string] [:generation :int]]}

    :loyly.pi-harness/workspace-generation-bound
    {:description "A committed builder tool result was bound to the workspace generation it produced."
     :schema [:map [:tick-id :uuid] [:committed-result :string] [:generation :int]]}

    :loyly.pi-harness/builder-session-ended
    {:description "A durable builder session ended, completed or failed, as the builder host reported."
     :schema [:map [:tick-id :uuid] [:session-id :string] [:status [:enum "completed" "failed"]]]}

    :loyly.pi-harness/builder-intervention-recorded
    {:description "An operator paused, steered, or forked a builder session; its work is assisted."
     :schema [:map [:tick-id :uuid] [:session-id :string] [:kind [:enum "pause" "steer" "fork"]]]}

    ;; ---- instrument
    :loyly.pi-harness/checklist-derived
    {:description "A checklist was derived from an approved selection: one item per stamped scenario, ordered by file then position."
     :schema [:map
              [:selection-id :string]
              [:items [:vector [:map
                                [:item :string]
                                [:scenario-digest :string]
                                [:rule {:optional true} :string]
                                [:source :string]
                                [:name :string]]]]]}

    :loyly.pi-harness/grader-authoring-requested
    {:description "A grader author was requested for scenarios no approved grader covers, or to re-author an uncalibrated grader, with the public-protocol cases it failed."
     :schema [:map
              [:scenario-digests [:vector :string]]
              [:attempt :int]
              [:failed-cases [:vector :string]]]}

    :loyly.pi-harness/grader-recalibration-requested
    {:description "The approved grader covering a checklist was asked to recalibrate before it scores."
     :schema [:map [:grader-digest :string] [:selection-id :string]]}

    :loyly.pi-harness/grader-authored
    {:description "A grader author session wrote a grader; it is calibrating."
     :schema [:map
              [:grader-digest :string]
              [:scenario-digests [:vector :string]]
              [:author-session-id :string]
              [:attempt :int]
              [:replaces {:optional true} :string]
              [:replacement-reason {:optional true} grader-replacement-reason]]}

    :loyly.pi-harness/grader-sealed
    {:description "A calibrated grader was sealed by its digest; it awaits an operator's approval."
     :schema [:map [:grader-digest :string]]}

    :loyly.pi-harness/grader-discarded
    {:description "A grader that failed calibration was discarded, with the public-protocol cases it failed."
     :schema [:map
              [:grader-digest :string]
              [:scenario-digests [:vector :string]]
              [:attempt :int]
              [:failed-cases [:vector :string]]]}

    :loyly.pi-harness/instrument-declared-unavailable
    {:description "Three graders in a row failed calibration for the same scenarios; the instrument was declared unavailable for them."
     :schema [:map [:scenario-digests [:vector :string]]]}

    :loyly.pi-harness/grader-replaced
    {:description "An approved grader was retired because an approved successor replaces it, for a recorded reason."
     :schema [:map [:grader-digest :string] [:successor-digest :string] [:replacement-reason grader-replacement-reason]]}

    :loyly.pi-harness/grader-approved
    {:description "An operator approved a sealed, calibrated grader by its exact digest."
     :schema [:map [:grader-digest :string] [:entry :string] [:items [:vector :string]]
              [:approved-by :string] [:approved-at :string]]}

    :loyly.pi-harness/cycle-measurement-recorded
    {:description "The one measurement of one cycle was filed from the approved sealed grader's verdicts."
     :schema measurement}

    :loyly.pi-harness/score-refused
    {:description "A returned score was refused and nothing was filed; the cycle's score stage fails."
     :schema [:map [:cycle-id :string] [:named-items [:vector :string]]]}

    :loyly.pi-harness/scoreboard-reported
    {:description "A scored cycle's scoreboard was reported to a person; it never reaches a builder."
     :schema scoreboard}

    ;; ---- runtime evidence
    :loyly.pi-harness/observation-recorded
    {:description "One identified producer recorded a purpose-scoped claim for one cycle item."
     :schema observation}

    :loyly.pi-harness/validation-room-opened
    {:description "A validation room was opened for one cycle with a pinned read-only capture policy and its own evidence volume."
     :schema [:map
              [:cycle-id :string]
              [:capture-policy [:map [:digest :string] [:mount-path :string] [:read-only? :boolean]]]
              [:evidence-volume [:map [:volume-id :string] [:mount-path :string] [:writable? :boolean]]]]}

    :loyly.pi-harness/baseline-trace-bound
    {:description "A passing baseline grade was bound to its complete trace artifact."
     :schema [:map
              [:cycle-id :string]
              [:evaluation-id :string]
              [:evidence-digest :string]
              [:trace [:map
                       [:path :string]
                       [:digest :string]
                       [:complete? :boolean]
                       [:producer-authority producer-authority]]]]}

    ;; ---- prompt optimization
    :loyly.pi-harness/prompt-optimization-started
    {:description "A builder-instruction optimization started for one purpose."
     :schema [:map
              [:optimization-id :string]
              [:purpose builder-instruction-purpose]
              [:reflection-provider :string]
              [:reflection-model :string]
              [:trainset-digest :string]
              [:valset-digest :string]]}

    :loyly.pi-harness/production-prompt-rendered
    {:description "ORC rendered a production prompt for one builder session role; its exact digest is what the builder host transports."
     :schema [:map
              [:production-prompt-id :string]
              [:role builder-role]
              [:system-instruction-digest :string]
              [:task-instruction-digest :string]
              [:runtime-task-context-digest :string]
              [:digest :string]]}

    :loyly.pi-harness/instruction-candidate-proposed
    {:description "The pinned reflection model proposed an instruction candidate for an evaluating optimization."
     :schema [:map
              [:optimization-id :string]
              [:candidate-id :string]
              [:instruction-id :string]
              [:purpose builder-instruction-purpose]
              [:text-digest :string]
              [:reflection-provider :string]
              [:reflection-model :string]
              [:proposal-call-digest :string]]}

    :loyly.pi-harness/instruction-candidate-measured
    {:description "A proposed candidate was measured independently for its purpose, under the exact production prompt it ran with."
     :schema [:map
              [:candidate-id :string]
              [:production-prompt-digest :string]
              [:score :double]
              [:evidence-digest :string]]}

    :loyly.pi-harness/instruction-candidate-rejected
    {:description "GEPA dropped a candidate of an evaluating optimization."
     :schema [:map [:optimization-id :string] [:candidate-id :string]]}

    :loyly.pi-harness/winning-instruction-selected
    {:description "The highest measured candidate became its purpose's one active instruction, superseding the previous winner for that purpose; the optimization is optimized."
     :schema [:map
              [:optimization-id :string]
              [:candidate-id :string]
              [:superseded-candidate-id {:optional true} :string]
              [:instruction-id :string]
              [:purpose builder-instruction-purpose]
              [:text-digest :string]]}

    :loyly.pi-harness/prompt-optimization-failed
    {:description "An evaluating optimization ended with no measured winner."
     :schema [:map [:optimization-id :string] [:reason :string]]}}

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
    {:description "Each task's status and, once terminal, its one stable outcome."
     :consumes #{:loyly.pi-harness/task-receipt-issued
                 :loyly.pi-harness/task-completed
                 :loyly.pi-harness/task-failed}
     :schema [:map-of :int
              [:map
               [:task-id :int]
               [:tick-id :uuid]
               [:status [:enum "running" "completed" "failed"]]
               [:outcome {:optional true} :string]]]
     :version 2}

    :loyly.pi-harness/prepared-cuts
    {:description "Prepared cuts and their slice composition."
     :consumes #{:loyly.pi-harness/cut-prepared}
     :version 2}

    :loyly.pi-harness/regeneration-cycles
    {:description "The regeneration cycle read model: each cycle's status, stage, slice builds, candidate, failure, and child lineage, folded from the harness's own cycle facts."
     :consumes #{:loyly.pi-harness/cycle-submitted
                 :loyly.pi-harness/task-receipt-issued
                 :loyly.pi-harness/cycle-started
                 :loyly.pi-harness/clean-room-proved
                 :loyly.pi-harness/slice-built
                 :loyly.pi-harness/candidate-regenerated
                 :loyly.pi-harness/cycle-stage-advanced
                 :loyly.pi-harness/cycle-passed
                 :loyly.pi-harness/cycle-failed
                 :loyly.pi-harness/child-cycle-lineage-recorded}
     :schema [:map-of :string
              [:map
               [:cycle-id :string]
               [:request-digest :string]
               [:preparation-id :string]
               [:parent-cycle-id {:optional true} :string]
               [:tick-id {:optional true} :uuid]
               [:status [:enum "submitted" "running" "passed" "failed"]]
               [:stage cycle-stage]
               [:slices-built :int]
               [:attempt :int]
               [:candidate-digest {:optional true} :string]
               [:failure-stage {:optional true} cycle-stage]
               [:failure-kind {:optional true} stage-failure-kind]
               [:failure-reason {:optional true} :string]
               [:child-preparations [:vector :string]]]]
     :version 1
     :grain/allium [{:spec ".pi/model/regeneration-cycle.allium"
                     :kind :entity :name "RegenerationCycle"}]}

    :loyly.pi-harness/harness-self-checks
    {:description "Harness self-checks by harness digest; a passing one is reused only for an identical digest."
     :consumes #{:loyly.pi-harness/harness-self-check-recorded}
     :version 1}

    :loyly.pi-harness/gate-attempts
    {:description "Every gate attempt of every cycle, by cycle and attempt."
     :consumes #{:loyly.pi-harness/gate-attempt-recorded}
     :version 1}

    :loyly.pi-harness/builder-sessions
    {:description "Builder sessions: each ORC builder tick's durable session, builder room, status, launches, interventions, and workspace generations."
     :consumes #{:loyly.pi-harness/builder-session-launched
                 :loyly.pi-harness/builder-session-resumed
                 :loyly.pi-harness/builder-tool-result-committed
                 :loyly.pi-harness/builder-session-ended
                 :loyly.pi-harness/workspace-generation-bound
                 :loyly.pi-harness/builder-intervention-recorded}
     :schema [:map-of :uuid
              [:map
               [:tick-id :uuid]
               [:session-id :string]
               [:role builder-role]
               [:cycle-id {:optional true} :string]
               [:attempt {:optional true} :int]
               [:room room-policy]
               [:production-prompt-digest :string]
               [:status [:enum "running" "completed" "failed"]]
               [:launches :int]
               [:assisted? :boolean]
               [:last-intervention {:optional true} [:enum "pause" "steer" "fork"]]
               [:workspace-generations [:vector [:map [:committed-result :string] [:generation :int]]]]]]
     :version 3
     :grain/allium [{:spec ".pi/model/execution-pipeline.allium"
                     :kind :entity :name "BuilderSession"}]}

    :loyly.pi-harness/node-stages
    {:description "The anti-corruption translation of ORC's execution facts: for each tick and node, the harness's CycleStage and builder role, and each tick's latest stage and status. Processors and the progress view read this, never a raw node id."
     :consumes #{:sheet/tree-tick-started
                 :sheet/node-execution-started
                 :sheet/node-execution-completed
                 :sheet/tree-tick-completed
                 :sheet/tick-cancelled}
     :schema [:map-of :uuid
              [:map
               [:tick-id :uuid]
               [:stage {:optional true} cycle-stage]
               [:status [:enum "running" "completed" "failed"]]
               [:nodes [:map-of :uuid
                        [:map
                         [:stage cycle-stage]
                         [:role {:optional true} builder-role]
                         [:status [:enum "started" "completed" "failed"]]]]]]]
     :version 1
     :grain/allium [{:spec ".pi/model/operator-control-plane.allium"
                     :kind :rule :name "ProjectCycleProgress"}]}

    :loyly.pi-harness/prompt-optimizations
    {:description "Builder-instruction optimizations, their production prompts, candidates, measurements, and winning instructions."
     :consumes #{:loyly.pi-harness/prompt-optimization-started
                 :loyly.pi-harness/production-prompt-rendered
                 :loyly.pi-harness/instruction-candidate-proposed
                 :loyly.pi-harness/instruction-candidate-measured
                 :loyly.pi-harness/instruction-candidate-rejected
                 :loyly.pi-harness/winning-instruction-selected
                 :loyly.pi-harness/prompt-optimization-failed}
     :version 3}

    :loyly.pi-harness/checklists
    {:description "Derived checklists by selection."
     :consumes #{:loyly.pi-harness/checklist-derived}
     :version 1}

    :loyly.pi-harness/graders
    {:description "Graders by digest and status (calibrating, sealed, approved, replaced, discarded), the authoring and recalibration requests, and the scenarios with no instrument."
     :consumes #{:loyly.pi-harness/grader-authoring-requested
                 :loyly.pi-harness/grader-recalibration-requested
                 :loyly.pi-harness/grader-authored
                 :loyly.pi-harness/grader-sealed
                 :loyly.pi-harness/grader-discarded
                 :loyly.pi-harness/instrument-declared-unavailable
                 :loyly.pi-harness/grader-approved
                 :loyly.pi-harness/grader-replaced}
     :version 3
     :grain/allium [{:spec ".pi/model/instrument.allium"
                     :kind :entity :name "Grader"}]}

    :loyly.pi-harness/cycle-observations
    {:description "Evidence grouped by cycle, item, kind, and producer, with each cycle's validation room and bound baseline trace."
     :consumes #{:loyly.pi-harness/observation-recorded
                 :loyly.pi-harness/validation-room-opened
                 :loyly.pi-harness/baseline-trace-bound}
     :version 2}

    :loyly.pi-harness/cycle-measurements
    {:description "Durable measurement receipts keyed by cycle identity: a projection of the one filed measurement, or of the refusal that filed none."
     :consumes #{:loyly.pi-harness/cycle-measurement-recorded :loyly.pi-harness/score-refused}
     :schema [:map-of :string
              [:map
               [:cycle-id :string]
               [:filed? :boolean]
               [:measurement {:optional true} measurement]
               [:refused-items {:optional true} [:vector :string]]]]
     :version 2
     :grain/allium [{:spec ".pi/model/instrument.allium"
                     :kind :entity :name "CycleMeasurement"}]}

    :loyly.pi-harness/scoreboards
    {:description "Reported scoreboards by cycle."
     :consumes #{:loyly.pi-harness/scoreboard-reported}
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
    {:description "Return the latest observational stage for one ORC tick, from the translated node stages."
     :schema [:map [:tick-id :uuid]]
     :reads #{:loyly.pi-harness/node-stages}
     :grain/allium [{:spec ".pi/model/operator-control-plane.allium"
                     :kind :rule :name "ReadCycleProgress"}]}

    :loyly.pi-harness/node-stage
    {:description "Return the harness stage and builder role of one ORC node of one tick: the to-do list of the processors that react to ORC nodes."
     :schema [:map [:tick-id :uuid] [:node-id :uuid]]
     :reads #{:loyly.pi-harness/node-stages}}

    :loyly.pi-harness/regeneration-cycle
    {:description "Return one cycle's status, stage, candidate, and failure."
     :schema [:map [:cycle-id :string]]
     :reads #{:loyly.pi-harness/regeneration-cycles}}

    :loyly.pi-harness/gate-attempt
    {:description "Return one gate attempt of one cycle."
     :schema [:map [:cycle-id :string] [:attempt :int]]
     :reads #{:loyly.pi-harness/gate-attempts}}

    :loyly.pi-harness/cycle-observations
    {:description "Return all recorded observations for one cycle; a report projection that never becomes verdict authority."
     :schema [:map [:cycle-id :string]]
     :reads #{:loyly.pi-harness/cycle-observations}
     :grain/allium [{:spec ".pi/model/runtime-evidence.allium"
                     :kind :rule :name "ProjectRuntimeEvidence"}]}

    :loyly.pi-harness/cycle-measurement
    {:description "Return the strict measurement for one cycle."
     :schema [:map [:cycle-id :string]]
     :reads #{:loyly.pi-harness/cycle-measurements}
     :grain/allium [{:spec ".pi/model/operator-control-plane.allium"
                     :kind :rule :name "ReadDurableMeasurement"}]}

    :loyly.pi-harness/scoreboard
    {:description "Return one cycle's reported scoreboard."
     :schema [:map [:cycle-id :string]]
     :reads #{:loyly.pi-harness/scoreboards}}

    :loyly.pi-harness/checklist
    {:description "Return the derived checklist of one selection."
     :schema [:map [:selection-id :string]]
     :reads #{:loyly.pi-harness/checklists}}

    :loyly.pi-harness/task-receipt
    {:description "Return the ORC tick an issued task identity names."
     :schema [:map [:task-id :int]]
     :reads #{:loyly.pi-harness/task-receipts}}

    :loyly.pi-harness/tick-task
    {:description "Return the task an ORC tick was dispatched for: the to-do list of the outcome processor."
     :schema [:map [:tick-id :uuid]]
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
                     :kind :rule :name "WaitForWorkflowTask"}]}

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

    :loyly.pi-harness/grader
    {:description "Return one grader by digest with its status and the grader it replaces: the to-do list of the retirement policy."
     :schema [:map [:grader-digest :string]]
     :reads #{:loyly.pi-harness/graders}}

    :loyly.pi-harness/approved-graders
    {:description "Return every grader an operator approved, in approval order."
     :schema [:map]
     :reads #{:loyly.pi-harness/graders}}}

   :todo-processors
   {:loyly.pi-harness/dispatch-submitted-task
    {:description "Dispatch each submitted task through ORC's Grain command boundary exactly once."
     :subscribes #{:loyly.pi-harness/task-submitted}
     :reads #{:loyly.pi-harness/task-receipt}
     :produces #{:sheet/tick-tree}
     :grain/allium [{:spec ".pi/model/operator-control-plane.allium"
                     :kind :rule :name "DispatchSubmittedTaskToOrc"}]}

    :loyly.pi-harness/issue-task-receipt
    {:description "When ORC starts the tick dispatched for a submitted task, bind the task to that tick."
     :subscribes #{:sheet/tree-tick-started}
     :reads #{:loyly.pi-harness/task-receipt}
     :produces #{:loyly.pi-harness/record-task-receipt}
     :grain/allium [{:spec ".pi/model/operator-control-plane.allium"
                     :kind :rule :name "StartWorkflowTask"}]}

    :loyly.pi-harness/start-dispatched-cycle
    {:description "When a cycle's task is bound to its ORC tick, start the submitted cycle at the fence."
     :subscribes #{:loyly.pi-harness/task-receipt-issued}
     :reads #{:loyly.pi-harness/regeneration-cycle}
     :produces #{:loyly.pi-harness/start-cycle}
     :grain/allium [{:spec ".pi/model/regeneration-cycle.allium"
                     :kind :rule :name "StartCycle"}]}

    :loyly.pi-harness/record-task-outcome
    {:description "When ORC completes or cancels a task's tick, record the task's one terminal outcome."
     :subscribes #{:sheet/tree-tick-completed :sheet/tick-cancelled}
     :reads #{:loyly.pi-harness/tick-task}
     :produces #{:loyly.pi-harness/complete-task :loyly.pi-harness/fail-task}
     :grain/allium [{:spec ".pi/model/operator-control-plane.allium"
                     :kind :rule :name "CompleteWorkflowTask"}
                    {:spec ".pi/model/operator-control-plane.allium"
                     :kind :rule :name "FailWorkflowTask"}]}

    :loyly.pi-harness/launch-builder-for-tick
    {:description "When ORC starts a node the translation names a builder role, launch its durable Pi session; when ORC resumes it, relaunch the same session."
     :subscribes #{:sheet/node-execution-started}
     :reads #{:loyly.pi-harness/node-stage :loyly.pi-harness/builder-session}
     :produces #{:loyly.pi-harness/launch-builder-session :loyly.pi-harness/relaunch-builder-session}
     :grain/allium [{:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "LaunchDurableBuilderSession"}
                    {:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "ResumeDurableBuilderSession"}]}

    :loyly.pi-harness/bind-committed-result
    {:description "When the builder host commits a tool result, bind the workspace generation it left to it."
     :subscribes #{:loyly.pi-harness/builder-tool-result-committed}
     :reads #{:loyly.pi-harness/builder-session}
     :produces #{:loyly.pi-harness/bind-workspace-generation}
     :grain/allium [{:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "BindWorkspaceGeneration"}]}

    :loyly.pi-harness/route-gate-verdict
    {:description "Route a recorded gate attempt: a pass moves the cycle to the score; a failure under three attempts back to regenerate; a third failure fails the gate stage."
     :subscribes #{:loyly.pi-harness/gate-attempt-recorded}
     :reads #{:loyly.pi-harness/gate-attempt}
     :produces #{:loyly.pi-harness/advance-cycle-stage :loyly.pi-harness/fail-cycle-stage}
     :grain/allium [{:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "PassGate"}
                    {:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "RepairAfterFailedGate"}
                    {:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "FailAfterThreeGateAttempts"}]}

    :loyly.pi-harness/retire-replaced-grader
    {:description "When an operator approves a grader that replaces another, retire the one it replaces."
     :subscribes #{:loyly.pi-harness/grader-approved}
     :reads #{:loyly.pi-harness/grader}
     :produces #{:loyly.pi-harness/retire-grader}
     :grain/allium [{:spec ".pi/model/instrument.allium"
                     :kind :rule :name "RetireReplacedGrader"}]}

    :loyly.pi-harness/file-cycle-score
    {:description "When the translation names a completed node the score stage of a cycle, file the sealed grader's returned verdicts."
     :subscribes #{:sheet/node-execution-completed}
     :reads #{:loyly.pi-harness/node-stage
              :loyly.pi-harness/cycle-preparation
              :loyly.pi-harness/approved-graders}
     :produces #{:loyly.pi-harness/file-score}
     :grain/allium [{:spec ".pi/model/instrument.allium"
                     :kind :rule :name "FileScore"}]}

    :loyly.pi-harness/route-filed-score
    {:description "Route a score: a filed measurement moves the cycle to the scoreboard; a refused score fails the score stage."
     :subscribes #{:loyly.pi-harness/cycle-measurement-recorded :loyly.pi-harness/score-refused}
     :reads #{:loyly.pi-harness/cycle-measurement}
     :produces #{:loyly.pi-harness/advance-cycle-stage :loyly.pi-harness/fail-cycle-stage}
     :grain/allium [{:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "AdvanceToScoreboard"}
                    {:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "FailRefusedScore"}]}

    :loyly.pi-harness/report-cycle-scoreboard
    {:description "When a cycle reaches the scoreboard, report its scoreboard against the previous measurement of the same selection."
     :subscribes #{:loyly.pi-harness/cycle-stage-advanced}
     :reads #{:loyly.pi-harness/regeneration-cycle :loyly.pi-harness/cycle-measurement}
     :produces #{:loyly.pi-harness/report-scoreboard}
     :grain/allium [{:spec ".pi/model/instrument.allium"
                     :kind :rule :name "ReportFirstScoreboard"}
                    {:spec ".pi/model/instrument.allium"
                     :kind :rule :name "ReportComparedScoreboard"}]}

    :loyly.pi-harness/close-reported-cycle
    {:description "When a cycle's scoreboard is reported, move the cycle to its result."
     :subscribes #{:loyly.pi-harness/scoreboard-reported}
     :reads #{:loyly.pi-harness/scoreboard}
     :produces #{:loyly.pi-harness/advance-cycle-stage}
     :grain/allium [{:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "RecordScoreboard"}]}

    :loyly.pi-harness/conclude-cycle-result
    {:description "When a cycle reaches its result, derive its terminal status from its one measurement."
     :subscribes #{:loyly.pi-harness/cycle-stage-advanced}
     :reads #{:loyly.pi-harness/regeneration-cycle}
     :produces #{:loyly.pi-harness/conclude-cycle}
     :grain/allium [{:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "PassCycle"}
                    {:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "FailCycle"}]}

    :loyly.pi-harness/record-validation-evidence
    {:description "When the translation names a completed node an independent validation step, record its observations."
     :subscribes #{:sheet/node-execution-completed}
     :reads #{:loyly.pi-harness/node-stage}
     :produces #{:loyly.pi-harness/record-observation}
     :grain/allium [{:spec ".pi/model/runtime-evidence.allium"
                     :kind :rule :name "RecordRuntimeSignal"}]}}

   :screens
   {:loyly.pi-harness/pi-regeneration
    {:description "Session control boundary: operator intents (selection, preparation, approvals, submissions, checklist derivation, grader request and approval, builder intervention, child cycles, optimization) and read-only queries. It issues no system fact."
     :queries #{:loyly.pi-harness/selection
                :loyly.pi-harness/task-receipt
                :loyly.pi-harness/highest-task-id
                :loyly.pi-harness/approved-graders
                :loyly.pi-harness/cycle-preparation
                :loyly.pi-harness/cycle-progress
                :loyly.pi-harness/regeneration-cycle
                :loyly.pi-harness/gate-attempt
                :loyly.pi-harness/checklist
                :loyly.pi-harness/scoreboard
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
                 :loyly.pi-harness/derive-checklist
                 :loyly.pi-harness/request-grader
                 :loyly.pi-harness/approve-grader
                 :loyly.pi-harness/record-cycle-preparation
                 :loyly.pi-harness/approve-cycle-preparation
                 :loyly.pi-harness/intervene-in-builder-session
                 :loyly.pi-harness/record-child-cycle}
     :grain/allium [{:spec ".pi/model/operator-control-plane.allium"
                     :kind :surface :name "RegenerationControlPlane"}
                    {:spec ".pi/model/regeneration-cycle.allium"
                     :kind :surface :name "RegenerationHarness"}
                    {:spec ".pi/model/instrument.allium"
                     :kind :surface :name "Instrument"}
                    {:spec ".pi/model/prompt-optimization.allium"
                     :kind :surface :name "BuilderPromptOptimization"}]}}

   :flows
   {:loyly.pi-harness/selection-and-preparation-approval
    {:description "An operator records a selection and approves its exact digest, then records the cycle preparation that binds it and approves that digest. Approval alone starts nothing."
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
              :to [:read-model :loyly.pi-harness/selections]}
             {:from [:read-model :loyly.pi-harness/selections]
              :to [:query :loyly.pi-harness/selection]}
             {:from [:query :loyly.pi-harness/selection]
              :to [:screen :loyly.pi-harness/pi-regeneration]}
             {:from [:screen :loyly.pi-harness/pi-regeneration]
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
              :to [:screen :loyly.pi-harness/pi-regeneration]}]
     :grain/allium [{:spec ".pi/model/regeneration-cycle.allium"
                     :kind :surface :name "RegenerationHarness"}]}

    :loyly.pi-harness/cut-preparation
    {:description "A source-cut task is submitted and dispatched to ORC once; ORC's cut workflow records the prepared cut, whose slice composition is read back."
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
              :to [:command :sheet/tick-tree]}
             {:from nil
              :to [:command :loyly.pi-harness/record-prepared-cut]}
             {:from [:command :loyly.pi-harness/record-prepared-cut]
              :to [:event :loyly.pi-harness/cut-prepared]}
             {:from [:event :loyly.pi-harness/cut-prepared]
              :to [:read-model :loyly.pi-harness/prepared-cuts]}
             {:from [:read-model :loyly.pi-harness/prepared-cuts]
              :to [:query :loyly.pi-harness/slice-composition]}
             {:from [:query :loyly.pi-harness/slice-composition]
              :to [:screen :loyly.pi-harness/pi-regeneration]}]}

    :loyly.pi-harness/cycle-submission
    {:description "A cycle is submitted (for an approved preparation, or the whole plan approved by submission) and dispatched to ORC once; when ORC starts its tick, a processor binds the task to the tick and another starts the cycle at the fence."
     :steps [{:from [:screen :loyly.pi-harness/pi-regeneration]
              :to [:command :loyly.pi-harness/submit-cycle-task]}
             {:from [:screen :loyly.pi-harness/pi-regeneration]
              :to [:command :loyly.pi-harness/submit-whole-plan-cycle]}
             {:from [:command :loyly.pi-harness/submit-whole-plan-cycle]
              :to [:event :loyly.pi-harness/cycle-preparation-recorded]}
             {:from [:command :loyly.pi-harness/submit-whole-plan-cycle]
              :to [:event :loyly.pi-harness/cycle-preparation-approved]}
             {:from [:command :loyly.pi-harness/submit-whole-plan-cycle]
              :to [:event :loyly.pi-harness/cycle-submitted]}
             {:from [:command :loyly.pi-harness/submit-whole-plan-cycle]
              :to [:event :loyly.pi-harness/task-submitted]}
             {:from [:command :loyly.pi-harness/submit-cycle-task]
              :to [:event :loyly.pi-harness/cycle-submitted]}
             {:from [:command :loyly.pi-harness/submit-cycle-task]
              :to [:event :loyly.pi-harness/task-submitted]}
             {:from [:event :loyly.pi-harness/cycle-submitted]
              :to [:read-model :loyly.pi-harness/regeneration-cycles]}
             {:from [:event :loyly.pi-harness/task-submitted]
              :to [:read-model :loyly.pi-harness/task-receipts]}
             {:from [:read-model :loyly.pi-harness/task-receipts]
              :to [:query :loyly.pi-harness/task-receipt]}
             {:from [:query :loyly.pi-harness/task-receipt]
              :to [:todo-processor :loyly.pi-harness/dispatch-submitted-task]}
             {:from [:todo-processor :loyly.pi-harness/dispatch-submitted-task]
              :to [:command :sheet/tick-tree]}
             {:from [:command :sheet/tick-tree]
              :to [:event :sheet/tree-tick-started]}
             {:from [:event :sheet/tree-tick-started]
              :to [:read-model :loyly.pi-harness/node-stages]}
             {:from [:read-model :loyly.pi-harness/node-stages]
              :to [:query :loyly.pi-harness/cycle-progress]}
             {:from [:query :loyly.pi-harness/cycle-progress]
              :to [:screen :loyly.pi-harness/pi-regeneration]}
             {:from [:query :loyly.pi-harness/task-receipt]
              :to [:todo-processor :loyly.pi-harness/issue-task-receipt]}
             {:from [:todo-processor :loyly.pi-harness/issue-task-receipt]
              :to [:command :loyly.pi-harness/record-task-receipt]}
             {:from [:command :loyly.pi-harness/record-task-receipt]
              :to [:event :loyly.pi-harness/task-receipt-issued]}
             {:from [:event :loyly.pi-harness/task-receipt-issued]
              :to [:read-model :loyly.pi-harness/regeneration-cycles]}
             {:from [:read-model :loyly.pi-harness/regeneration-cycles]
              :to [:query :loyly.pi-harness/regeneration-cycle]}
             {:from [:query :loyly.pi-harness/regeneration-cycle]
              :to [:todo-processor :loyly.pi-harness/start-dispatched-cycle]}
             {:from [:todo-processor :loyly.pi-harness/start-dispatched-cycle]
              :to [:command :loyly.pi-harness/start-cycle]}
             {:from [:command :loyly.pi-harness/start-cycle]
              :to [:event :loyly.pi-harness/cycle-started]}
             {:from [:event :loyly.pi-harness/cycle-started]
              :to [:read-model :loyly.pi-harness/regeneration-cycles]}
             {:from [:query :loyly.pi-harness/regeneration-cycle]
              :to [:screen :loyly.pi-harness/pi-regeneration]}]
     :grain/allium [{:spec ".pi/model/regeneration-cycle.allium"
                     :kind :rule :name "SubmitCycle"}
                    {:spec ".pi/model/regeneration-cycle.allium"
                     :kind :rule :name "StartCycle"}]}

    :loyly.pi-harness/durable-builder-session
    {:description "ORC starts a node the translation names a builder role; its durable Pi session is launched once in the builder room ORC chose (or the same session relaunched), the builder host commits tool results, each is bound to its workspace generation, the host reports the session's end, and an operator observes the session read-only or intervenes."
     :steps [{:from [:event :sheet/node-execution-started]
              :to [:read-model :loyly.pi-harness/node-stages]}
             {:from [:read-model :loyly.pi-harness/node-stages]
              :to [:query :loyly.pi-harness/node-stage]}
             {:from [:query :loyly.pi-harness/node-stage]
              :to [:todo-processor :loyly.pi-harness/launch-builder-for-tick]}
             {:from [:todo-processor :loyly.pi-harness/launch-builder-for-tick]
              :to [:command :loyly.pi-harness/launch-builder-session]}
             {:from [:command :loyly.pi-harness/launch-builder-session]
              :to [:event :loyly.pi-harness/builder-session-launched]}
             {:from [:event :loyly.pi-harness/builder-session-launched]
              :to [:read-model :loyly.pi-harness/builder-sessions]}
             {:from [:todo-processor :loyly.pi-harness/launch-builder-for-tick]
              :to [:command :loyly.pi-harness/relaunch-builder-session]}
             {:from [:command :loyly.pi-harness/relaunch-builder-session]
              :to [:event :loyly.pi-harness/builder-session-resumed]}
             {:from [:event :loyly.pi-harness/builder-session-resumed]
              :to [:read-model :loyly.pi-harness/builder-sessions]}
             {:from nil
              :to [:command :loyly.pi-harness/commit-builder-tool-result]}
             {:from [:command :loyly.pi-harness/commit-builder-tool-result]
              :to [:event :loyly.pi-harness/builder-tool-result-committed]}
             {:from [:event :loyly.pi-harness/builder-tool-result-committed]
              :to [:read-model :loyly.pi-harness/builder-sessions]}
             {:from nil
              :to [:command :loyly.pi-harness/end-builder-session]}
             {:from [:command :loyly.pi-harness/end-builder-session]
              :to [:event :loyly.pi-harness/builder-session-ended]}
             {:from [:event :loyly.pi-harness/builder-session-ended]
              :to [:read-model :loyly.pi-harness/builder-sessions]}
             {:from [:read-model :loyly.pi-harness/builder-sessions]
              :to [:query :loyly.pi-harness/builder-session]}
             {:from [:query :loyly.pi-harness/builder-session]
              :to [:todo-processor :loyly.pi-harness/bind-committed-result]}
             {:from [:todo-processor :loyly.pi-harness/bind-committed-result]
              :to [:command :loyly.pi-harness/bind-workspace-generation]}
             {:from [:command :loyly.pi-harness/bind-workspace-generation]
              :to [:event :loyly.pi-harness/workspace-generation-bound]}
             {:from [:event :loyly.pi-harness/workspace-generation-bound]
              :to [:read-model :loyly.pi-harness/builder-sessions]}
             {:from [:query :loyly.pi-harness/builder-session]
              :to [:screen :loyly.pi-harness/pi-regeneration]}
             {:from [:screen :loyly.pi-harness/pi-regeneration]
              :to [:command :loyly.pi-harness/intervene-in-builder-session]}
             {:from [:command :loyly.pi-harness/intervene-in-builder-session]
              :to [:event :loyly.pi-harness/builder-intervention-recorded]}
             {:from [:event :loyly.pi-harness/builder-intervention-recorded]
              :to [:read-model :loyly.pi-harness/builder-sessions]}]
     :grain/allium [{:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "LaunchDurableBuilderSession"}
                    {:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "BindWorkspaceGeneration"}
                    {:spec ".pi/model/execution-pipeline.allium"
                     :kind :rule :name "EndBuilderSession"}]}

    :loyly.pi-harness/cycle-harness
    {:description "The harness half of the cycle, which feeds the build loop: ORC's fence node records the self-check and proves the clean room; its regenerate node records each slice build and the candidate; its gate node records the gate attempt, and a policy routes the verdict to the score, back to regenerate, or to a failed gate after three attempts."
     :steps [{:from nil
              :to [:command :loyly.pi-harness/record-harness-self-check]}
             {:from [:command :loyly.pi-harness/record-harness-self-check]
              :to [:event :loyly.pi-harness/harness-self-check-recorded]}
             {:from [:event :loyly.pi-harness/harness-self-check-recorded]
              :to [:read-model :loyly.pi-harness/harness-self-checks]}
             {:from nil
              :to [:command :loyly.pi-harness/prove-clean-room]}
             {:from [:command :loyly.pi-harness/prove-clean-room]
              :to [:event :loyly.pi-harness/clean-room-proved]}
             {:from [:event :loyly.pi-harness/clean-room-proved]
              :to [:read-model :loyly.pi-harness/regeneration-cycles]}
             {:from nil
              :to [:command :loyly.pi-harness/record-slice-build]}
             {:from [:command :loyly.pi-harness/record-slice-build]
              :to [:event :loyly.pi-harness/slice-built]}
             {:from [:event :loyly.pi-harness/slice-built]
              :to [:read-model :loyly.pi-harness/regeneration-cycles]}
             {:from nil
              :to [:command :loyly.pi-harness/record-candidate]}
             {:from [:command :loyly.pi-harness/record-candidate]
              :to [:event :loyly.pi-harness/candidate-regenerated]}
             {:from [:event :loyly.pi-harness/candidate-regenerated]
              :to [:read-model :loyly.pi-harness/regeneration-cycles]}
             {:from nil
              :to [:command :loyly.pi-harness/record-gate-attempt]}
             {:from [:command :loyly.pi-harness/record-gate-attempt]
              :to [:event :loyly.pi-harness/gate-attempt-recorded]}
             {:from [:event :loyly.pi-harness/gate-attempt-recorded]
              :to [:read-model :loyly.pi-harness/gate-attempts]}
             {:from [:read-model :loyly.pi-harness/gate-attempts]
              :to [:query :loyly.pi-harness/gate-attempt]}
             {:from [:query :loyly.pi-harness/gate-attempt]
              :to [:todo-processor :loyly.pi-harness/route-gate-verdict]}
             {:from [:todo-processor :loyly.pi-harness/route-gate-verdict]
              :to [:command :loyly.pi-harness/advance-cycle-stage]}
             {:from [:command :loyly.pi-harness/advance-cycle-stage]
              :to [:event :loyly.pi-harness/cycle-stage-advanced]}
             {:from [:event :loyly.pi-harness/cycle-stage-advanced]
              :to [:read-model :loyly.pi-harness/regeneration-cycles]}
             {:from [:todo-processor :loyly.pi-harness/route-gate-verdict]
              :to [:command :loyly.pi-harness/fail-cycle-stage]}
             {:from [:command :loyly.pi-harness/fail-cycle-stage]
              :to [:event :loyly.pi-harness/cycle-failed]}
             {:from [:event :loyly.pi-harness/cycle-failed]
              :to [:read-model :loyly.pi-harness/regeneration-cycles]}
             {:from [:read-model :loyly.pi-harness/regeneration-cycles]
              :to [:query :loyly.pi-harness/regeneration-cycle]}
             {:from [:query :loyly.pi-harness/regeneration-cycle]
              :to [:screen :loyly.pi-harness/pi-regeneration]}]
     :grain/allium [{:spec ".pi/model/execution-pipeline.allium"
                     :kind :surface :name "RegenerationCycleExecution"}]}

    :loyly.pi-harness/cycle-instrument
    {:description "The instrument half of the cycle, which reports to a person and never feeds the loop: when the translation names ORC's score node completed, a processor files the sealed grader's verdicts; a policy moves a filed measurement to the scoreboard or fails a refused score; the scoreboard is reported; and the result policy makes the cycle passed or failed."
     :steps [{:from [:event :sheet/node-execution-completed]
              :to [:read-model :loyly.pi-harness/node-stages]}
             {:from [:read-model :loyly.pi-harness/node-stages]
              :to [:query :loyly.pi-harness/node-stage]}
             {:from [:query :loyly.pi-harness/node-stage]
              :to [:todo-processor :loyly.pi-harness/file-cycle-score]}
             {:from [:todo-processor :loyly.pi-harness/file-cycle-score]
              :to [:command :loyly.pi-harness/file-score]}
             {:from [:command :loyly.pi-harness/file-score]
              :to [:event :loyly.pi-harness/cycle-measurement-recorded]}
             {:from [:command :loyly.pi-harness/file-score]
              :to [:event :loyly.pi-harness/score-refused]}
             {:from [:event :loyly.pi-harness/cycle-measurement-recorded]
              :to [:read-model :loyly.pi-harness/cycle-measurements]}
             {:from [:event :loyly.pi-harness/score-refused]
              :to [:read-model :loyly.pi-harness/cycle-measurements]}
             {:from [:read-model :loyly.pi-harness/cycle-measurements]
              :to [:query :loyly.pi-harness/cycle-measurement]}
             {:from [:query :loyly.pi-harness/cycle-measurement]
              :to [:todo-processor :loyly.pi-harness/route-filed-score]}
             {:from [:todo-processor :loyly.pi-harness/route-filed-score]
              :to [:command :loyly.pi-harness/advance-cycle-stage]}
             {:from [:todo-processor :loyly.pi-harness/route-filed-score]
              :to [:command :loyly.pi-harness/fail-cycle-stage]}
             {:from [:command :loyly.pi-harness/advance-cycle-stage]
              :to [:event :loyly.pi-harness/cycle-stage-advanced]}
             {:from [:command :loyly.pi-harness/fail-cycle-stage]
              :to [:event :loyly.pi-harness/cycle-failed]}
             {:from [:event :loyly.pi-harness/cycle-stage-advanced]
              :to [:read-model :loyly.pi-harness/regeneration-cycles]}
             {:from [:event :loyly.pi-harness/cycle-failed]
              :to [:read-model :loyly.pi-harness/regeneration-cycles]}
             {:from [:read-model :loyly.pi-harness/regeneration-cycles]
              :to [:query :loyly.pi-harness/regeneration-cycle]}
             {:from [:query :loyly.pi-harness/regeneration-cycle]
              :to [:todo-processor :loyly.pi-harness/report-cycle-scoreboard]}
             {:from [:query :loyly.pi-harness/cycle-measurement]
              :to [:todo-processor :loyly.pi-harness/report-cycle-scoreboard]}
             {:from [:todo-processor :loyly.pi-harness/report-cycle-scoreboard]
              :to [:command :loyly.pi-harness/report-scoreboard]}
             {:from [:command :loyly.pi-harness/report-scoreboard]
              :to [:event :loyly.pi-harness/scoreboard-reported]}
             {:from [:event :loyly.pi-harness/scoreboard-reported]
              :to [:read-model :loyly.pi-harness/scoreboards]}
             {:from [:read-model :loyly.pi-harness/scoreboards]
              :to [:query :loyly.pi-harness/scoreboard]}
             {:from [:query :loyly.pi-harness/scoreboard]
              :to [:todo-processor :loyly.pi-harness/close-reported-cycle]}
             {:from [:todo-processor :loyly.pi-harness/close-reported-cycle]
              :to [:command :loyly.pi-harness/advance-cycle-stage]}
             {:from [:query :loyly.pi-harness/regeneration-cycle]
              :to [:todo-processor :loyly.pi-harness/conclude-cycle-result]}
             {:from [:todo-processor :loyly.pi-harness/conclude-cycle-result]
              :to [:command :loyly.pi-harness/conclude-cycle]}
             {:from [:command :loyly.pi-harness/conclude-cycle]
              :to [:event :loyly.pi-harness/cycle-passed]}
             {:from [:command :loyly.pi-harness/conclude-cycle]
              :to [:event :loyly.pi-harness/cycle-failed]}
             {:from [:event :loyly.pi-harness/cycle-passed]
              :to [:read-model :loyly.pi-harness/regeneration-cycles]}
             {:from [:query :loyly.pi-harness/scoreboard]
              :to [:screen :loyly.pi-harness/pi-regeneration]}
             {:from [:query :loyly.pi-harness/cycle-measurement]
              :to [:screen :loyly.pi-harness/pi-regeneration]}
             {:from [:query :loyly.pi-harness/regeneration-cycle]
              :to [:screen :loyly.pi-harness/pi-regeneration]}]
     :grain/allium [{:spec ".pi/model/instrument.allium"
                     :kind :surface :name "GraderRuns"}]}

    :loyly.pi-harness/grader-preparation
    {:description "An operator derives a selection's checklist and asks for its grader: an approved grader that covers it is recalibrated, otherwise a grader author is requested; ORC's grader-author node records the authored grader; its calibration node seals a calibrated grader or discards an uncalibrated one (re-authoring it, or declaring the instrument unavailable after three); the operator approves a sealed grader's exact digest, and a policy retires the grader it replaces."
     :steps [{:from [:screen :loyly.pi-harness/pi-regeneration]
              :to [:command :loyly.pi-harness/derive-checklist]}
             {:from [:command :loyly.pi-harness/derive-checklist]
              :to [:event :loyly.pi-harness/checklist-derived]}
             {:from [:event :loyly.pi-harness/checklist-derived]
              :to [:read-model :loyly.pi-harness/checklists]}
             {:from [:read-model :loyly.pi-harness/checklists]
              :to [:query :loyly.pi-harness/checklist]}
             {:from [:query :loyly.pi-harness/checklist]
              :to [:screen :loyly.pi-harness/pi-regeneration]}
             {:from [:screen :loyly.pi-harness/pi-regeneration]
              :to [:command :loyly.pi-harness/request-grader]}
             {:from [:command :loyly.pi-harness/request-grader]
              :to [:event :loyly.pi-harness/grader-authoring-requested]}
             {:from [:command :loyly.pi-harness/request-grader]
              :to [:event :loyly.pi-harness/grader-recalibration-requested]}
             {:from [:event :loyly.pi-harness/grader-authoring-requested]
              :to [:read-model :loyly.pi-harness/graders]}
             {:from [:event :loyly.pi-harness/grader-recalibration-requested]
              :to [:read-model :loyly.pi-harness/graders]}
             {:from nil
              :to [:command :loyly.pi-harness/record-authored-grader]}
             {:from [:command :loyly.pi-harness/record-authored-grader]
              :to [:event :loyly.pi-harness/grader-authored]}
             {:from [:event :loyly.pi-harness/grader-authored]
              :to [:read-model :loyly.pi-harness/graders]}
             {:from nil
              :to [:command :loyly.pi-harness/seal-grader]}
             {:from [:command :loyly.pi-harness/seal-grader]
              :to [:event :loyly.pi-harness/grader-sealed]}
             {:from [:event :loyly.pi-harness/grader-sealed]
              :to [:read-model :loyly.pi-harness/graders]}
             {:from nil
              :to [:command :loyly.pi-harness/discard-grader]}
             {:from [:command :loyly.pi-harness/discard-grader]
              :to [:event :loyly.pi-harness/grader-discarded]}
             {:from [:command :loyly.pi-harness/discard-grader]
              :to [:event :loyly.pi-harness/grader-authoring-requested]}
             {:from [:command :loyly.pi-harness/discard-grader]
              :to [:event :loyly.pi-harness/instrument-declared-unavailable]}
             {:from [:event :loyly.pi-harness/grader-discarded]
              :to [:read-model :loyly.pi-harness/graders]}
             {:from [:event :loyly.pi-harness/instrument-declared-unavailable]
              :to [:read-model :loyly.pi-harness/graders]}
             {:from [:screen :loyly.pi-harness/pi-regeneration]
              :to [:command :loyly.pi-harness/approve-grader]}
             {:from [:command :loyly.pi-harness/approve-grader]
              :to [:event :loyly.pi-harness/grader-approved]}
             {:from [:event :loyly.pi-harness/grader-approved]
              :to [:read-model :loyly.pi-harness/graders]}
             {:from [:read-model :loyly.pi-harness/graders]
              :to [:query :loyly.pi-harness/grader]}
             {:from [:query :loyly.pi-harness/grader]
              :to [:todo-processor :loyly.pi-harness/retire-replaced-grader]}
             {:from [:todo-processor :loyly.pi-harness/retire-replaced-grader]
              :to [:command :loyly.pi-harness/retire-grader]}
             {:from [:command :loyly.pi-harness/retire-grader]
              :to [:event :loyly.pi-harness/grader-replaced]}
             {:from [:event :loyly.pi-harness/grader-replaced]
              :to [:read-model :loyly.pi-harness/graders]}
             {:from [:read-model :loyly.pi-harness/graders]
              :to [:query :loyly.pi-harness/approved-graders]}
             {:from [:query :loyly.pi-harness/approved-graders]
              :to [:screen :loyly.pi-harness/pi-regeneration]}]
     :grain/allium [{:spec ".pi/model/instrument.allium"
                     :kind :surface :name "Instrument"}]}

    :loyly.pi-harness/task-outcome
    {:description "When ORC completes or cancels a task's tick, a processor records the task's one terminal outcome; a wait reads it and never reruns the task."
     :steps [{:from [:event :loyly.pi-harness/task-receipt-issued]
              :to [:read-model :loyly.pi-harness/task-receipts]}
             {:from [:read-model :loyly.pi-harness/task-receipts]
              :to [:query :loyly.pi-harness/tick-task]}
             {:from [:query :loyly.pi-harness/tick-task]
              :to [:todo-processor :loyly.pi-harness/record-task-outcome]}
             {:from [:todo-processor :loyly.pi-harness/record-task-outcome]
              :to [:command :loyly.pi-harness/complete-task]}
             {:from [:todo-processor :loyly.pi-harness/record-task-outcome]
              :to [:command :loyly.pi-harness/fail-task]}
             {:from [:command :loyly.pi-harness/complete-task]
              :to [:event :loyly.pi-harness/task-completed]}
             {:from [:command :loyly.pi-harness/fail-task]
              :to [:event :loyly.pi-harness/task-failed]}
             {:from [:event :loyly.pi-harness/task-completed]
              :to [:read-model :loyly.pi-harness/task-outcomes]}
             {:from [:event :loyly.pi-harness/task-failed]
              :to [:read-model :loyly.pi-harness/task-outcomes]}
             {:from [:read-model :loyly.pi-harness/task-outcomes]
              :to [:query :loyly.pi-harness/task-outcome]}
             {:from [:query :loyly.pi-harness/task-outcome]
              :to [:screen :loyly.pi-harness/pi-regeneration]}]
     :grain/allium [{:spec ".pi/model/operator-control-plane.allium"
                     :kind :surface :name "OrcTaskReporting"}]}

    :loyly.pi-harness/runtime-evidence
    {:description "ORC's validation node opens a validation room and binds a passing baseline to its trace artifact; when the translation names a completed validation node, a processor records its observations, read back as a report projection."
     :steps [{:from [:event :sheet/node-execution-completed]
              :to [:read-model :loyly.pi-harness/node-stages]}
             {:from nil
              :to [:command :loyly.pi-harness/record-validation-room]}
             {:from [:command :loyly.pi-harness/record-validation-room]
              :to [:event :loyly.pi-harness/validation-room-opened]}
             {:from [:event :loyly.pi-harness/validation-room-opened]
              :to [:read-model :loyly.pi-harness/cycle-observations]}
             {:from [:read-model :loyly.pi-harness/node-stages]
              :to [:query :loyly.pi-harness/node-stage]}
             {:from [:query :loyly.pi-harness/node-stage]
              :to [:todo-processor :loyly.pi-harness/record-validation-evidence]}
             {:from [:todo-processor :loyly.pi-harness/record-validation-evidence]
              :to [:command :loyly.pi-harness/record-observation]}
             {:from [:command :loyly.pi-harness/record-observation]
              :to [:event :loyly.pi-harness/observation-recorded]}
             {:from [:event :loyly.pi-harness/observation-recorded]
              :to [:read-model :loyly.pi-harness/cycle-observations]}
             {:from nil
              :to [:command :loyly.pi-harness/bind-baseline-trace]}
             {:from [:command :loyly.pi-harness/bind-baseline-trace]
              :to [:event :loyly.pi-harness/baseline-trace-bound]}
             {:from [:event :loyly.pi-harness/baseline-trace-bound]
              :to [:read-model :loyly.pi-harness/cycle-observations]}
             {:from [:read-model :loyly.pi-harness/cycle-observations]
              :to [:query :loyly.pi-harness/cycle-observations]}
             {:from [:query :loyly.pi-harness/cycle-observations]
              :to [:screen :loyly.pi-harness/pi-regeneration]}]
     :grain/allium [{:spec ".pi/model/runtime-evidence.allium"
                     :kind :surface :name "RuntimeEvidenceReporting"}]}

    :loyly.pi-harness/prompt-optimization
    {:description "An operator starts an optimization; ORC records the production prompts it renders, the candidates the reflection model proposes, their independent measurements, the candidates it drops, and the winner or its absence; the winner is read back."
     :steps [{:from [:screen :loyly.pi-harness/pi-regeneration]
              :to [:command :loyly.pi-harness/start-prompt-optimization]}
             {:from [:command :loyly.pi-harness/start-prompt-optimization]
              :to [:event :loyly.pi-harness/prompt-optimization-started]}
             {:from [:event :loyly.pi-harness/prompt-optimization-started]
              :to [:read-model :loyly.pi-harness/prompt-optimizations]}
             {:from nil
              :to [:command :loyly.pi-harness/record-production-prompt]}
             {:from [:command :loyly.pi-harness/record-production-prompt]
              :to [:event :loyly.pi-harness/production-prompt-rendered]}
             {:from [:event :loyly.pi-harness/production-prompt-rendered]
              :to [:read-model :loyly.pi-harness/prompt-optimizations]}
             {:from nil
              :to [:command :loyly.pi-harness/propose-instruction-candidate]}
             {:from [:command :loyly.pi-harness/propose-instruction-candidate]
              :to [:event :loyly.pi-harness/instruction-candidate-proposed]}
             {:from [:event :loyly.pi-harness/instruction-candidate-proposed]
              :to [:read-model :loyly.pi-harness/prompt-optimizations]}
             {:from nil
              :to [:command :loyly.pi-harness/measure-instruction-candidate]}
             {:from [:command :loyly.pi-harness/measure-instruction-candidate]
              :to [:event :loyly.pi-harness/instruction-candidate-measured]}
             {:from [:event :loyly.pi-harness/instruction-candidate-measured]
              :to [:read-model :loyly.pi-harness/prompt-optimizations]}
             {:from nil
              :to [:command :loyly.pi-harness/reject-instruction-candidate]}
             {:from [:command :loyly.pi-harness/reject-instruction-candidate]
              :to [:event :loyly.pi-harness/instruction-candidate-rejected]}
             {:from [:event :loyly.pi-harness/instruction-candidate-rejected]
              :to [:read-model :loyly.pi-harness/prompt-optimizations]}
             {:from nil
              :to [:command :loyly.pi-harness/select-winning-instruction]}
             {:from [:command :loyly.pi-harness/select-winning-instruction]
              :to [:event :loyly.pi-harness/winning-instruction-selected]}
             {:from [:event :loyly.pi-harness/winning-instruction-selected]
              :to [:read-model :loyly.pi-harness/prompt-optimizations]}
             {:from nil
              :to [:command :loyly.pi-harness/fail-prompt-optimization]}
             {:from [:command :loyly.pi-harness/fail-prompt-optimization]
              :to [:event :loyly.pi-harness/prompt-optimization-failed]}
             {:from [:event :loyly.pi-harness/prompt-optimization-failed]
              :to [:read-model :loyly.pi-harness/prompt-optimizations]}
             {:from [:read-model :loyly.pi-harness/prompt-optimizations]
              :to [:query :loyly.pi-harness/prompt-optimization]}
             {:from [:query :loyly.pi-harness/prompt-optimization]
              :to [:screen :loyly.pi-harness/pi-regeneration]}]
     :grain/allium [{:spec ".pi/model/prompt-optimization.allium"
                     :kind :surface :name "OrcInstructionOptimization"}]}

    :loyly.pi-harness/child-cycle
    {:description "An operator continues a terminal task's cycle with an approved child preparation; the lineage is read back with the cycle."
     :steps [{:from [:screen :loyly.pi-harness/pi-regeneration]
              :to [:command :loyly.pi-harness/record-child-cycle]}
             {:from [:command :loyly.pi-harness/record-child-cycle]
              :to [:event :loyly.pi-harness/child-cycle-lineage-recorded]}
             {:from [:event :loyly.pi-harness/child-cycle-lineage-recorded]
              :to [:read-model :loyly.pi-harness/regeneration-cycles]}
             {:from [:read-model :loyly.pi-harness/regeneration-cycles]
              :to [:query :loyly.pi-harness/regeneration-cycle]}
             {:from [:query :loyly.pi-harness/regeneration-cycle]
              :to [:screen :loyly.pi-harness/pi-regeneration]}]}}})
