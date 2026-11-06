# Pi harness specification coverage

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
Gondolin runtime are provided, not generated (see `.pi/architecture.md`). The glossary is `GLOSSARY.md`.

## Event Model

Grain topology lives under `event-model/`, one service area per file, each namespace
matching its path:

| Area | File | Blocks |
|---|---|---|
| `:loyly.pi-harness` | `event-model/loyly/pi_harness/event_model.clj` | 12 commands, 10 events, 11 read models, 12 queries, 1 todo processor, 1 screen, 16 flows |
| `:sheet` | `event-model/orc/sheet/event_model.clj` | 1 command, 5 events: the ORC blocks the harness dispatches or projects |

ORC registers no Event Model of its own. The `:sheet` schemas are copied from
`obneyai/orc` `projects/orc-service` at `af086675` and must stay in step with that pin.

Every command links an Allium rule and the screen links an Allium surface:

| Command | Allium rule |
|---|---|
| `submit-cut-task` | `operator-control-plane.allium` `PrepareCutTask` |
| `record-selection` | `regeneration-cycle.allium` `PreviewSelection` |
| `approve-selection` | `regeneration-cycle.allium` `ApproveSelection` |
| `record-cycle-preparation` | `regeneration-cycle.allium` `PrepareCycle` |
| `approve-cycle-preparation` | `regeneration-cycle.allium` `ApprovePreparation` |
| `submit-cycle-task` | `operator-control-plane.allium` `SubmitCycleTask` |
| `submit-whole-plan-cycle` | `operator-control-plane.allium` `SubmitWholePlanCycle` |
| `record-task-receipt` | `operator-control-plane.allium` `StartHarnessTask` |
| `approve-grader` | `instrument.allium` `ApproveExactGrader` |
| `record-observation` | `runtime-evidence.allium` `RecordRuntimeSignal` |
| `record-cycle-measurement` | `instrument.allium` `FileScore` |
| `start-prompt-optimization` | `prompt-optimization.allium` `StartPromptOptimization` |
| `:sheet/tick-tree` | `operator-control-plane.allium` `DispatchSubmittedTaskToOrc` |
| screen `pi-regeneration` | `operator-control-plane.allium` surface `RegenerationControlPlane` |

Approval starts nothing. A submission records `task-submitted`; the todo processor
`dispatch-submitted-task` dispatches `:sheet/tick-tree` once per submitted task.

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
| `SubmitCycle` | `submit-cycle` | command `submit-whole-plan-cycle` | `operator-control-plane.allium` `SubmitWholePlanCycle`, `RepeatIdenticalSubmission`, `RefuseConflictingSubmission` |
| `Wait` | `wait` | query `task-outcome` | `operator-control-plane.allium` `WaitForHarnessTask` |
| `ExecutionStatus` | `execution-status` | query `cycle-progress` | `operator-control-plane.allium` `ReadCycleProgress` |
| `PrepareCut` | `prepare-cut` | command `submit-cut-task` | `operator-control-plane.allium` `PrepareCutTask` |
| `SliceComposition` | `slice-composition` | query `slice-composition` | `regeneration-cycle.allium` `ComposeCutSystemView` |
| `PreviewSelection` | `preview-selection` | command `record-selection` | `regeneration-cycle.allium` `PreviewSelection` |
| `ApproveSelection` | `approve-selection` | command `approve-selection` | `regeneration-cycle.allium` `ApproveSelection` |
| `PrepareCycle` | `prepare-cycle` | command `record-cycle-preparation` | `regeneration-cycle.allium` `PrepareCycle` |
| `ApprovePreparation` | `approve-preparation` | command `approve-cycle-preparation` | `regeneration-cycle.allium` `ApprovePreparation` |
| `SubmitApprovedSlice` | `submit-approved-slice` | command `submit-cycle-task` | `operator-control-plane.allium` `SubmitCycleTask`, `RepeatIdenticalSubmission` |
| `ApproveGrader` | `approve-grader` | command `approve-grader` | `instrument.allium` `ApproveExactGrader` |
| `Measurement` | `measurement` | query `cycle-measurement` | `operator-control-plane.allium` `ReadDurableMeasurement` |
| `BuilderSession` | `builder-session` | query `builder-session` | `operator-control-plane.allium` `ObserveBuilderSession` |
| `OptimizePrompt` | `optimize-prompt` | command `start-prompt-optimization` | `prompt-optimization.allium` `StartPromptOptimization` |

`prepare-cycle` and `approve-preparation` are new; the 14 earlier methods keep their names,
params, and result shapes, so the sealed graders stay valid.

The queries `task-receipt`, `highest-task-id`, `selection`, `cycle-preparation`,
`approved-graders`, `cycle-observations`, and `prompt-optimization` have no seam action of
their own: dispatch, task numbering, and the actions' results read them.

## Legacy parity

The legacy harness (`archive/legacy-harness/`, its `spec.feature` and README) is the
behavioural parity reference. Its behaviour is carried as follows.

| Legacy behaviour | Here |
|---|---|
| fence → regenerate → gate → score → scoreboard; fence and gate feed the loop, score and scoreboard never do | slice 02, `execution-pipeline.allium` |
| Physical fence proved on the object graph; a fenced path matching nothing stops it; self-check first | `FenceCleanRoom` |
| Gate: fresh boot, suite green, a named boot budget with its margin, `start.sh` timed and `test.sh` not | slice 02 |
| Gate guards: missing test tool, empty suite discovery, an answer from a process the gate did not start | 02-015 to 02-017, `GatePassRequiresObservedRun`, `FailedGateNamesItsReason` |
| Identity proved by domain behaviour | `cycle-contract` (02-010) |
| Stamper: next free number, own indentation, Outlines alike, never renumbered, re-derived, or reused; idempotent | 03-010, 03-011, `StampScenario`, `StableItemIdentity` |
| Checklist derived, one item per stamped scenario; unstamped or empty stops it | 03-001, `DeriveChecklist` |
| Checklist item carries rule, source line, stripped title; ordered by file then position; empty title is no item | 03-012, `ChecklistItem` |
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
| A builder session is a durable Pi session keyed by its ORC tick; a relaunch resumes it | 02-018, `LaunchDurableBuilderSession`, `ResumeDurableBuilderSession`, `DurableSessionIsKeyedByTick`; Grain `launch-builder-for-tick` → `launch-builder-session` / `relaunch-builder-session` → `builder-session-launched` / `builder-session-resumed` → `builder-sessions` |
| ORC alone chooses the room; the host only forwards tool calls | 02-019, `BuilderRoomPolicy`, `OrcAloneChoosesTheRoom` |
| A grader author's room holds only the selected Gherkin and the calibration protocol | 02-019, 03-002, `GraderAuthorRoomHoldsOnlySpecAndProtocol` |
| A restored transcript is paired only with its own workspace | 04-017, `BindWorkspaceGeneration`, `BuilderWorkspaceMatchesCommittedSession`; Grain `bind-workspace-generation` → `workspace-generation-bound` |
| Every production prompt is ORC's, for all three roles | 06-007, `ProductionPromptRole` |

## Ownership

Each concept is declared once. `regeneration-cycle.allium` owns the domain: cuts, their
composition, selections, preparations, and cycles. `operator-control-plane.allium` owns the
control plane: tasks and receipts, idempotent submission, dispatch to ORC, progress, builder
sessions, and measurement reads. It imports the domain with
`use "./regeneration-cycle.allium" as cycle`. One operator act may fire a rule in each, for
example `SubmitWholePlanRegeneration` creates the approved whole-plan preparation and cycle
(`ApproveWholePlanBySubmission`) and its task (`SubmitWholePlanCycle`).

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
```

Run from the repository root. Without an implementation loaded, the composition gate reports
every modelled block as `:block/undeclared` and then skips the Allium links; that is
expected. Any other finding is drift. Strict runtime validation needs the implementation.

## Allium diagnostic baseline

`allium check .pi/model` and `allium analyse .pi/model` report no errors and no warnings. Any
warning is drift. The operator is one `actor Operator`, identified by its `OperatorSession`,
declared in `regeneration-cycle.allium` and imported by every spec whose surface faces it.

Check the folder, not single files: the imports resolve only within the check set.
Info-level diagnostics (unused fields and similar) are not tracked.
