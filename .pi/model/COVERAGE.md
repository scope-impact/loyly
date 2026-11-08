# Pi harness specification coverage

> **Spec v1, kept as history.** The maintained spec is spec v2 in `.pi/spec/` ([README](../spec/README.md)); the harness that runs the cycles now is in its decisions, ["The switch to the new harness"](../spec/decisions.md#the-switch-to-the-new-harness-decided-2026-11-08).

This ledger defines "every part of the Pi harness" against the slices in `plan/`. A domain
is complete only when its behaviour is in a checked Allium specification, its checklist
items are stamped scenarios in its slice, and every Grain block that changes its state links
an Allium rule.

## Domains

| Slice | Domain | Spec | Items | Status |
|---|---|---|---:|---|
| `01-regeneration-cycle` | Cuts, selection, preparation and their approval | `regeneration-cycle.allium` | 15 | Specified and checked |
| `02-execution-pipeline` | Fence, regenerate, gate, score, scoreboard, durable builder sessions | `execution-pipeline.allium` | 19 | Specified and checked |
| `03-instrument` | Stamping, checklist, graders, score, scoreboard | `instrument.allium` | 20 | Specified and checked |
| `04-operator-control-plane` | Submission, receipts, progress, builder sessions | `operator-control-plane.allium` | 17 | Specified and checked |
| `05-runtime-evidence` | Runtime signals and independent verdicts | `runtime-evidence.allium` | 10 | Specified and checked |
| `06-prompt-optimization` | Builder strategy optimization | `prompt-optimization.allium` | 9 | Specified and checked |
| `09-rpc-seam` | JSON-RPC over stdio | `affordances.yaml` (`seam`) | 4 | Specified as the seam; no Allium spec by design |

94 items in total. Slices 07 and 08 are not used: the durable Pi builder host and its
Gondolin runtime are provided, not generated (see `.pi/architecture.md`). The glossary is
`GLOSSARY.md`, the authority for the model's words (ADR 0005); `tools/spec/glossary-lint.py`
enforces its `_Avoid_` lists.

## Event Model

Grain topology lives under `event-model/`, one service area per file, each namespace
matching its path:

| Area | File | Blocks |
|---|---|---|
| `:loyly.pi-harness` | `event-model/loyly/pi_harness/event_model.clj` | 46 commands, 48 events, 16 read models, 19 queries, 14 todo processors, 1 screen, 11 flows |
| `:sheet` | `event-model/orc/sheet/event_model.clj` | 1 command, 5 events: the ORC blocks the harness dispatches or projects |

ORC registers no Event Model of its own. The `:sheet` schemas are copied from
`obneyai/orc` `projects/orc-service` at `af086675` and must stay in step with that pin; the
boot-guard risk of a partial copy and its upstream fix are in ADR 0002 ("Known risk").

The cycle's stage facts are harness events recorded by commands ORC's workflow nodes issue
(ADR 0004). A transition an Allium rule makes on a state trigger is a policy: a todo processor
reading a to-do query and issuing a command. Where the harness reacts to an ORC node, it reads
the anti-corruption read model `node-stages`, which yields the harness's `CycleStage` and
builder role per tick and node, never a raw node id.

### Commands and the rules they realise

Every command links the Allium rule or rules it realises. "ORC node" means a flow entry issued
by a node of the workflow ORC runs for the harness.

| Command | Issued by | Allium rule |
|---|---|---|
| `record-selection` | screen | `regeneration-cycle.allium` `PreviewSelection`, `ProposeSelectionFromIntent` |
| `approve-selection` | screen | `regeneration-cycle.allium` `ApproveSelection` |
| `record-cycle-preparation` | screen | `regeneration-cycle.allium` `PrepareCycle` |
| `approve-cycle-preparation` | screen | `regeneration-cycle.allium` `ApprovePreparation` |
| `submit-cut-task` | screen | `operator-control-plane.allium` `PrepareCutTask` |
| `record-prepared-cut` | ORC cut-preparation node | `regeneration-cycle.allium` `PrepareCut`, `ComposeCutSystemView` |
| `submit-cycle-task` | screen | `regeneration-cycle.allium` `SubmitCycle`; `operator-control-plane.allium` `OpenCycleTask`, `RepeatIdenticalSubmission`, `RefuseConflictingSubmission` |
| `submit-whole-plan-cycle` | screen | `regeneration-cycle.allium` `ApproveWholePlanBySubmission`; `operator-control-plane.allium` `OpenCycleTask`, `RepeatIdenticalSubmission`, `RefuseConflictingSubmission` |
| `record-task-receipt` | processor `issue-task-receipt` | `operator-control-plane.allium` `StartWorkflowTask` |
| `complete-task` | processor `record-task-outcome` | `operator-control-plane.allium` `CompleteWorkflowTask` |
| `fail-task` | processor `record-task-outcome` | `operator-control-plane.allium` `FailWorkflowTask` |
| `record-child-cycle` | screen | `operator-control-plane.allium` `ContinueWithChildCycle` |
| `start-cycle` | processor `start-dispatched-cycle` | `regeneration-cycle.allium` `StartCycle` |
| `record-harness-self-check` | ORC fence node | `execution-pipeline.allium` `RunHarnessSelfCheck` |
| `prove-clean-room` | ORC fence node | `execution-pipeline.allium` `FenceCleanRoom` |
| `record-slice-build` | ORC regenerate node | `execution-pipeline.allium` `BuildSlice` |
| `record-candidate` | ORC regenerate node | `execution-pipeline.allium` `RegenerateCandidate` |
| `record-gate-attempt` | ORC gate node | `execution-pipeline.allium` `RecordGateAttempt` |
| `advance-cycle-stage` | processors `route-gate-verdict`, `route-filed-score`, `close-reported-cycle` | `execution-pipeline.allium` `PassGate`, `RepairAfterFailedGate`, `AdvanceToScoreboard`, `RecordScoreboard` |
| `fail-cycle-stage` | processors `route-gate-verdict`, `route-filed-score`; ORC (a stage timed out, errored, or was skipped) | `execution-pipeline.allium` `FailCycleStage`, `FailAfterThreeGateAttempts`, `FailRefusedScore` |
| `conclude-cycle` | processor `conclude-cycle-result` | `execution-pipeline.allium` `PassCycle`, `FailCycle` |
| `launch-builder-session` | processor `launch-builder-for-tick` | `execution-pipeline.allium` `LaunchDurableBuilderSession` |
| `relaunch-builder-session` | processor `launch-builder-for-tick` | `execution-pipeline.allium` `ResumeDurableBuilderSession` |
| `commit-builder-tool-result` | the provided builder host | `execution-pipeline.allium` `BindWorkspaceGeneration` |
| `end-builder-session` | the provided builder host | `execution-pipeline.allium` `EndBuilderSession` |
| `bind-workspace-generation` | processor `bind-committed-result` | `execution-pipeline.allium` `BindWorkspaceGeneration` |
| `intervene-in-builder-session` | screen | `operator-control-plane.allium` `InterveneInBuilderSession` |
| `derive-checklist` | screen | `instrument.allium` `DeriveChecklist` |
| `request-grader` | screen | `instrument.allium` `RequestGraderAuthoring`, `ReuseApprovedGrader` |
| `record-authored-grader` | ORC grader-author node | `instrument.allium` `AuthorGrader` |
| `seal-grader` | ORC calibration node | `instrument.allium` `SealCalibratedGrader` |
| `discard-grader` | ORC calibration node | `instrument.allium` `ReauthorUncalibratedGrader`, `StopAfterThreeUncalibratedGraders` |
| `approve-grader` | screen | `instrument.allium` `ApproveExactGrader` |
| `retire-grader` | processor `retire-replaced-grader` | `instrument.allium` `RetireReplacedGrader` |
| `file-score` | processor `file-cycle-score` | `instrument.allium` `FileScore`, `RefuseEmptyScore`, `RefuseUnknownVerdicts`, `RefuseInvalidVerdicts` |
| `report-scoreboard` | processor `report-cycle-scoreboard` | `instrument.allium` `ReportFirstScoreboard`, `ReportComparedScoreboard` |
| `record-observation` | processor `record-validation-evidence` | `runtime-evidence.allium` `RecordRuntimeSignal`, `EvaluateCompleteInvariant`, `MarkIncompleteInvariantInconclusive` |
| `record-validation-room` | ORC validation node | `runtime-evidence.allium` `OpenValidationRoom` |
| `bind-baseline-trace` | ORC validation node | `runtime-evidence.allium` `BindSuccessfulBaselineTrace` |
| `start-prompt-optimization` | screen | `prompt-optimization.allium` `StartPromptOptimization` |
| `record-production-prompt` | ORC | `prompt-optimization.allium` `RenderProductionPrompt` |
| `propose-instruction-candidate` | ORC | `prompt-optimization.allium` `ProposeInstructionCandidate` |
| `measure-instruction-candidate` | ORC | `prompt-optimization.allium` `MeasureInstructionCandidate` |
| `reject-instruction-candidate` | ORC | `prompt-optimization.allium` `RejectInstructionCandidate` |
| `select-winning-instruction` | ORC | `prompt-optimization.allium` `SelectWinningInstruction` |
| `fail-prompt-optimization` | ORC | `prompt-optimization.allium` `FailPromptOptimization` |
| `:sheet/tick-tree` | processor `dispatch-submitted-task` | `operator-control-plane.allium` `DispatchSubmittedTaskToOrc` |
| screen `pi-regeneration` | — | surfaces `RegenerationControlPlane`, `RegenerationHarness`, `Instrument`, `BuilderPromptOptimization` |

Pure reads are queries, linked to the rule they realise: `cycle-progress` (`ReadCycleProgress`),
`cycle-measurement` (`ReadDurableMeasurement`), `task-outcome` (`WaitForWorkflowTask`),
`builder-session` (`ObserveBuilderSession`), `slice-composition` (`ComposeCutSystemView`), and
`cycle-observations` (`ProjectRuntimeEvidence`). The read model `node-stages` links
`ProjectCycleProgress`; `graders` links the entity `Grader`.

### Policies

| Todo processor | Reacts to | Reads (to-do query) | Issues | Allium rule |
|---|---|---|---|---|
| `dispatch-submitted-task` | `task-submitted` | `task-receipt` | `:sheet/tick-tree` | `DispatchSubmittedTaskToOrc` |
| `issue-task-receipt` | `:sheet/tree-tick-started` | `task-receipt` | `record-task-receipt` | `StartWorkflowTask` |
| `start-dispatched-cycle` | `task-receipt-issued` | `regeneration-cycle` | `start-cycle` | `StartCycle` |
| `record-task-outcome` | `:sheet/tree-tick-completed`, `:sheet/tick-cancelled` | `tick-task` | `complete-task`, `fail-task` | `CompleteWorkflowTask`, `FailWorkflowTask` |
| `launch-builder-for-tick` | `:sheet/node-execution-started` | `node-stage`, `builder-session` | `launch-builder-session`, `relaunch-builder-session` | `LaunchDurableBuilderSession`, `ResumeDurableBuilderSession` |
| `bind-committed-result` | `builder-tool-result-committed` | `builder-session` | `bind-workspace-generation` | `BindWorkspaceGeneration` |
| `retire-replaced-grader` | `grader-approved` | `grader` | `retire-grader` | `RetireReplacedGrader` |
| `route-gate-verdict` | `gate-attempt-recorded` | `gate-attempt` | `advance-cycle-stage`, `fail-cycle-stage` | `PassGate`, `RepairAfterFailedGate`, `FailAfterThreeGateAttempts` |
| `file-cycle-score` | `:sheet/node-execution-completed` | `node-stage`, `cycle-preparation`, `approved-graders` | `file-score` | `FileScore` |
| `route-filed-score` | `cycle-measurement-recorded`, `score-refused` | `cycle-measurement` | `advance-cycle-stage`, `fail-cycle-stage` | `AdvanceToScoreboard`, `FailRefusedScore` |
| `report-cycle-scoreboard` | `cycle-stage-advanced` | `regeneration-cycle`, `cycle-measurement` | `report-scoreboard` | `ReportFirstScoreboard`, `ReportComparedScoreboard` |
| `close-reported-cycle` | `scoreboard-reported` | `scoreboard` | `advance-cycle-stage` | `RecordScoreboard` |
| `conclude-cycle-result` | `cycle-stage-advanced` | `regeneration-cycle` | `conclude-cycle` | `PassCycle`, `FailCycle` |
| `record-validation-evidence` | `:sheet/node-execution-completed` | `node-stage` | `record-observation` | `RecordRuntimeSignal` |

### Rules not realised by a Grain block

71 of the 75 Allium rules are linked from a Grain block. The other four are not runtime state
changes of the harness:

| Rule | Why no Grain block |
|---|---|
| `instrument.allium` `StampScenario` | Stamping writes an `@item` tag into a Gherkin file; the file in the repository is the record, written by the stamper tool, not by the running harness. |
| `instrument.allium` `LeaveStampedSliceUnwritten` | The stamper's idempotence: a stamped slice is left untouched, so nothing is recorded. |
| `instrument.allium` `WriteInstrumentGolden` | Goldens are instrument files written from the scenarios and fenced from every clean room; the running harness only checks them, through `record-harness-self-check`. |
| `operator-control-plane.allium` `ValidatePiHarnessServiceArea` | The startup check itself: Grain's strict boot guard (`verify-or-throw!`) against the registered model, not a domain fact. |

### Read models

| Read model | Folds | Schema |
|---|---|---|
| `regeneration-cycles` | `cycle-submitted`, `task-receipt-issued`, `cycle-started`, `clean-room-proved`, `slice-built`, `candidate-regenerated`, `cycle-stage-advanced`, `cycle-passed`, `cycle-failed`, `child-cycle-lineage-recorded` | yes: status, stage, slices built, attempt, candidate, failure, child preparations |
| `builder-sessions` | launched, resumed, tool result committed, ended, workspace generation bound, intervention recorded | yes: status, room, launches, interventions, workspace generations |
| `task-outcomes` | `task-receipt-issued`, `task-completed`, `task-failed` | yes |
| `cycle-measurements` | `cycle-measurement-recorded`, `score-refused` | yes |
| `node-stages` | the five `:sheet` events (the anti-corruption translation) | yes |
| `selections`, `cycle-preparations`, `task-receipts`, `prepared-cuts`, `harness-self-checks`, `gate-attempts`, `checklists`, `graders`, `scoreboards`, `cycle-observations`, `prompt-optimizations` | their own events | not yet |

`graders` (version 3: authoring, recalibration, authored, replaced) replaces `approved-graders` as the read model (the query `approved-graders` keeps
its name and reads it). `node-stages` replaces the read model `cycle-progress` (the query
`cycle-progress` keeps its name and reads it). Read models that changed what they fold carry
`:version 2`.

### Flows

Eleven end-to-end slices: `selection-and-preparation-approval`, `cut-preparation`,
`cycle-submission` (submit → dispatch → receipt → start), `durable-builder-session` (launch →
commit → bind workspace → observe or intervene), `cycle-harness` (self-check → clean room →
slice builds → candidate → gate → verdict policy), `cycle-instrument` (score → route →
scoreboard → result), `grader-preparation` (checklist → grader request → authored → sealed or
discarded → approved → predecessor retired), `task-outcome`, `runtime-evidence`,
`prompt-optimization`, and `child-cycle`. Every step follows the grammar screen → command →
event → read model → query → todo processor or screen, todo processor → command.

The screen issues operator intents only: selection, preparation, approvals, submissions,
checklist derivation, grader request and approval, builder intervention, child cycles, and
optimization.
It issues no system fact. Approval starts nothing. Every `tick-id` is a `:uuid`, as in ORC,
and every cycle is named by `:cycle-id`, its request identity.

## Affordances

`affordances.yaml` is the public seam, in the same format as the Löyly product's affordance contract.
The harness has no UI, so it declares a `seam` (JSON-RPC over stdio) instead of `screens` and
`ui`, and each action's `call` names an `rpc` method. It is validated by
`.pi/affordances.schema.json`. Every outcome and rejection names the Allium rule or surface it
realises, `grain` names the Event Model command or query it reaches, and `steps` maps the
Gherkin wording that drives each action.

| Action | Method | Grain | Allium |
|---|---|---|---|
| `CycleContract` | `cycle-contract` | — | `execution-pipeline.allium` surface `RegenerationCycleExecution` |
| `WorkflowContract` | `workflow-contract` | — | `execution-pipeline.allium` surface `RegenerationCycleExecution` |
| `SubmitCycle` | `submit-cycle` | command `submit-whole-plan-cycle` | `regeneration-cycle.allium` `ApproveWholePlanBySubmission`; `operator-control-plane.allium` `RepeatIdenticalSubmission`, `RefuseConflictingSubmission` |
| `Wait` | `wait` | query `task-outcome` | `operator-control-plane.allium` `WaitForWorkflowTask` |
| `ExecutionStatus` | `execution-status` | query `cycle-progress` | `operator-control-plane.allium` `ReadCycleProgress` |
| `PrepareCut` | `prepare-cut` | command `submit-cut-task` | `operator-control-plane.allium` `PrepareCutTask` |
| `SliceComposition` | `slice-composition` | query `slice-composition` | `regeneration-cycle.allium` `ComposeCutSystemView` |
| `PreviewSelection` | `preview-selection` | command `record-selection` | `regeneration-cycle.allium` `PreviewSelection` |
| `ApproveSelection` | `approve-selection` | command `approve-selection` | `regeneration-cycle.allium` `ApproveSelection` |
| `PrepareCycle` | `prepare-cycle` | command `record-cycle-preparation` | `regeneration-cycle.allium` `PrepareCycle` |
| `ApprovePreparation` | `approve-preparation` | command `approve-cycle-preparation` | `regeneration-cycle.allium` `ApprovePreparation` |
| `SubmitApprovedSlice` | `submit-approved-slice` | command `submit-cycle-task` | `regeneration-cycle.allium` `SubmitCycle`; `operator-control-plane.allium` `RepeatIdenticalSubmission` |
| `ApproveGrader` | `approve-grader` | command `approve-grader` | `instrument.allium` `ApproveExactGrader` |
| `Measurement` | `measurement` | query `cycle-measurement` | `operator-control-plane.allium` `ReadDurableMeasurement` |
| `BuilderSession` | `builder-session` | query `builder-session` | `operator-control-plane.allium` `ObserveBuilderSession` |
| `OptimizePrompt` | `optimize-prompt` | command `start-prompt-optimization` | `prompt-optimization.allium` `StartPromptOptimization` |

`prepare-cycle` and `approve-preparation` are new; the 14 earlier methods keep their names,
params, and result shapes, so the sealed graders stay valid.

The queries `task-receipt`, `tick-task`, `highest-task-id`, `selection`, `cycle-preparation`,
`approved-graders`, `checklist`, `regeneration-cycle`, `gate-attempt`, `scoreboard`,
`node-stage`, `grader`, `cycle-observations`, and `prompt-optimization` have no seam action of
their own: the screen, the policies, and the actions' results read them. The seam is unchanged
by ADR 0004 and ADR 0005, except that one `allium:` reference follows a rename
(`WaitForWorkflowTask`).

## Legacy parity

The legacy harness (`archive/legacy-harness/`, its `spec.feature` and README) is the
behavioural parity reference. Its behaviour is carried as follows.

| Legacy behaviour | Here |
|---|---|
| fence → regenerate → gate → score → scoreboard; fence and gate feed the loop, score and scoreboard never do | slice 02, `execution-pipeline.allium` |
| Physical fence proved on the object graph; a fenced path matching nothing stops it; self-check first | `FenceCleanRoom` |
| Gate: fresh boot, suite green, a named boot budget with its margin, the launch entrypoint (`start.sh`) timed and `test.sh` not | slice 02 |
| Gate guards: missing test tool, empty suite discovery, a response from a candidate the gate did not boot | 02-015 to 02-017, `GatePassRequiresObservedRun`, `FailedGateNamesItsReason` |
| Identity proved by domain behaviour | `cycle-contract` (02-010) |
| Stamper: next free number, own indentation, Outlines alike, never renumbered, re-derived, or reused; idempotent | 03-010, 03-011, `StampScenario`, `StableItemIdentity` |
| Checklist derived, one item per stamped scenario; unstamped or empty stops it | 03-001, `DeriveChecklist` |
| Checklist item carries rule, source line, stripped title; ordered by file then position; empty title is no item | 03-012, `StampedItem`, `SelectedItemsAreTitledStampedScenarios` |
| Zero verdicts is no score; unreached fails and stays counted | 03-005, 03-006, `FileScore` |
| Unknown item fatal, then non pass/fail fatal; last duplicate wins; evidence optional | 03-013 to 03-015, `RefuseUnknownVerdicts`, `RefuseInvalidVerdicts` |
| Scoreboard: both directions on shared items, never a net; new items the holdout; baseline cycle | 03-008, 03-009 |
| Half-to-even rounding; zero denominator has no percentage; moved item set stated only when it moved; no shared items still compared | 03-016, 03-017, `FixedArithmetic` |
| Golden oracle: the instrument's tools held to exact expected output, refusals included, before anything runs | 03-019, 03-020, `InstrumentGolden`, `RunHarnessSelfCheck`, `GoldensFromTheSpec` |
| Golden change reasons: behaviour changed, wording replaced, spec grew (additions only) | golden and grader replacement reasons, 03-004, 03-020 |
| Refusals write nothing; UTF-8 written literally | `affordances.yaml` conformance |

Changed on purpose: the boot budget is thirty seconds over JSON-RPC on stdio instead of ten
seconds of HTTP polls; the scoreboard compares with the previous measurement of the same
selection instead of the numeric predecessor; scope is an operator-approved selection
instead of a slice marked planned; instrument goldens are written from the scenarios instead
of recorded from an implementation; and calibrated, sealed, approved graders measure the
product, which the legacy harness left to whoever graded.

Not carried, because they belong to the repository rather than the harness: `modules.sh`
(one text across three hand-copied repositories; the Pi harness is one regenerated product)
and the commit hooks of `check.sh` (the regenerated harness does not own the repository's
git hooks; the fence still refuses to build a room on a red self-check).

## ORC evaluation is not the instrument

ORC's `evaluation` component scores node completions from 0 to 1 with LLM and custom judges,
aggregates them into a weighted composite, and feeds instruction optimization. The
instrument is the opposite: one pass/fail verdict per checklist item from one sealed,
approved grader, an honest denominator, never a net, never a builder input. ORC runs the
sealed grader as a bounded score-stage step; `FileScore` and its refusals file the verdicts.
Judge scores are diagnostic runtime evidence only (03-018, `SealedGraderIsTheOnlyJudge`,
`DiagnosticSignalCannotDetermineVerdict`).

## Durable builder sessions

| Behaviour | Spec |
|---|---|
| A builder session is one aggregate, `BuilderSession`: a durable Pi session keyed by its ORC tick; a relaunch resumes it; it ends completed or failed | 02-018, `LaunchDurableBuilderSession`, `ResumeDurableBuilderSession`, `EndBuilderSession`, `DurableSessionIsKeyedByTick`; Grain `launch-builder-for-tick` → `launch-builder-session` / `relaunch-builder-session` → `builder-session-launched` / `builder-session-resumed` → `builder-sessions` |
| ORC alone chooses the room; the host only forwards tool calls | 02-019, `BuilderRoomPolicy`, `OrcAloneChoosesTheRoom` |
| A grader author's room holds only the selected Gherkin and the calibration protocol | 02-019, 03-002, `GraderAuthorRoomHoldsOnlySpecAndProtocol` |
| A restored transcript is paired only with its own workspace generation | 04-017, `BindWorkspaceGeneration`, `WorkspaceGenerationMatchesCommittedResult`; Grain `commit-builder-tool-result` (builder host) → `builder-tool-result-committed` → `bind-committed-result` → `bind-workspace-generation` → `workspace-generation-bound` |
| An operator observes a builder session read-only; an intervention marks its work assisted | 04, `ObserveBuilderSession`, `InterveneInBuilderSession`, `BuilderObservationDoesNotAssist`; Grain query `builder-session` over the projection `builder-sessions` |
| Every production prompt is ORC's, for all three roles | 06-007, `BuilderSessionRole`, `OrcOwnsEveryProductionPrompt` |
| A grader is written by a grader author session, and an approved successor retires the grader it replaces | 03-002, 03-004, `AuthorGrader`, `GraderIsAuthoredByAGraderAuthorSession`, `RetireReplacedGrader` |

## Ownership

Each concept is declared once (ADR 0003):

| Concept | Owner | Declared as |
|---|---|---|
| Cycle | `regeneration-cycle.allium` | entity `RegenerationCycle` (status `CycleStatus`, stage `CycleStage`), moved through its stages by `execution-pipeline.allium` |
| Measurement | `instrument.allium` | entity `CycleMeasurement` of `ItemScore` values, filed only by `FileScore`; refused by `RefuseEmptyScore`, `RefuseUnknownVerdicts`, `RefuseInvalidVerdicts` |
| Builder session | `execution-pipeline.allium` | entity `BuilderSession` |
| Workflow task | `operator-control-plane.allium` | entity `WorkflowTask` (a task is not a cycle; it may prepare a cut) |
| Stamped item | `regeneration-cycle.allium` | value `StampedItem`, carried by a selection and its checklist |
| Execution backend and profile | `regeneration-cycle.allium` | enums `ExecutionBackend` (the one execution concept) and `ExecutionProfile` (which backends a preparation admits) |
| Builder session role | `execution-pipeline.allium` | enum `BuilderSessionRole`, used by builder sessions and production prompts alike |
| Filed verdict | `instrument.allium` | enum `ItemVerdict { pass \| fail }` |
| Evidence verdict | `runtime-evidence.allium` | enum `EvidenceVerdict { pass \| fail \| inconclusive }` |
| Producer authority, evidence purpose, evidence projection | `runtime-evidence.allium` | `ProducerAuthority`, `EvidencePurpose`, `EvidenceProjectionCreated`, invariant `EvidenceProjectionIsNotAuthority` |
| Actors | `regeneration-cycle.allium` | `actor Operator`, `actor OrcRuntime` |

Other specs import these with `use … as cycle`, `as execution`, `as instrument`. Durable
measurement receipts and builder-session views are read-side projections (Grain read models
`cycle-measurements` and `builder-sessions`), not Allium entities. The cycle's Grain read
model is `regeneration-cycles`, folded only from harness events (ADR 0004).

Each trigger name is provided once; an operator's or ORC's command is imperative and a
reported fact is past tense (ADR 0005). The operator's domain acts (`PrepareSourceCut`,
`SubmitRegenerationCycle`, `SubmitWholePlanRegeneration`) are provided by
`RegenerationHarness`; the control plane's cut task has its own trigger,
`SubmitCutPreparationTask`. Submission idempotence lives in `SubmitCycle` and
`ApproveWholePlanBySubmission` alone; the control plane opens the cycle's task from the
created cycle (`OpenCycleTask`, a state trigger).

Every surface faces an actor. Operator acts reach `RegenerationHarness`,
`RegenerationControlPlane`, `Instrument`, and `BuilderPromptOptimization`, facing
`Operator`. Facts ORC reports reach `OrcCycleStart`, `RegenerationCycleExecution`,
`GraderRuns`, `OrcTaskReporting`, `RuntimeEvidenceReporting`, and
`OrcInstructionOptimization`, facing `OrcRuntime`. Internal chaining between rules uses state
triggers (`GateAttempt.created`, `RegenerationCycle.stage becomes result`,
`CycleMeasurement.created`) or events another rule emits (`ScoreRefused`,
`CycleStageDidNotSucceed`), never an actor-less surface.

The cycle is wired end to end: `SubmitCycle` creates it at the fence; ORC starts it
(`StartCycle`); `FenceCleanRoom`, `BuildSlice`, `RegenerateCandidate`, `RecordGateAttempt`,
`PassGate` / `RepairAfterFailedGate` / `FailAfterThreeGateAttempts`, `FileScore` →
`AdvanceToScoreboard`, the scoreboard rules → `RecordScoreboard`, and `PassCycle` /
`FailCycle` move it to a terminal status with its candidate digest. A refused score fails the
score stage (`FailRefusedScore` → `FailCycleStage`). In Grain the same path is the
`cycle-harness` and `cycle-instrument` flows.

## Verification

```bash
allium check .pi/model           # structural checks, every spec
allium analyse .pi/model         # process and invariant analysis
clojure -Sdeps '{:paths [".pi/model/event-model"]
                 :deps {g/cat {:local/root "grain/projects/grain-code-agent-tools"}}}' \
  -M -e "(require '[ai.obney.grain.event-model.interface :as em]
                  '[ai.obney.grain.code-agent-tools.interface :as tools]
                  'loyly.pi-harness.event-model)
         (tools/validate-spec-composition (em/registered-model) {:project-root \".\"})"
python3 tools/spec/glossary-lint.py   # no avoided glossary term in the model; plan/ hits reported
bash tools/spec/gates.sh               # every spec gate in one run
```

Run from the repository root. Without an implementation loaded, the composition gate reports
every modelled block as `:block/undeclared` and then skips the Allium links; that is
expected. Any other finding is drift. Strict runtime validation needs the implementation.

## Allium diagnostic baseline

`allium check .pi/model` and `allium analyse .pi/model` report no errors, no warnings, and no
info diagnostics. Any warning is drift. Every helper a rule or invariant reads is a typed
signature in a `contract` (17 of them, each `demands`-ed by the surface that relies on it), so
no function is used undeclared. The operator is one `actor Operator`, identified by its `OperatorSession`, and
ORC's runtime is one `actor OrcRuntime`, identified by its `OrcWorker`; both are declared in
`regeneration-cycle.allium` and imported by every spec whose surface faces them.

Check the folder, not single files: the imports resolve only within the check set.
`OperatorSession` and `OrcWorker` stay plain entities, the identities of the two actors, though
the model never creates them: Allium 3.6 warns (`externalEntity.missingSourceHint`) on an
`external entity` that no rule of its own file names in an expression.
