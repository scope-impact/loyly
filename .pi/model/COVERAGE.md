# Pi harness specification coverage

This ledger defines "every part of the Pi harness" against the slices in `plan/`. A domain
is complete only when its behaviour is in a checked Allium specification, its checklist
items are stamped scenarios in its slice, and every Grain block that changes its state links
an Allium rule.

## Domains

| Slice | Domain | Spec | Items | Status |
|---|---|---|---:|---|
| `01-regeneration-cycle` | Cuts, selection, preparation and their approval | `regeneration-cycle.allium` | 15 | Specified and checked |
| `02-execution-pipeline` | Fence, regenerate, gate, score, scoreboard | `execution-pipeline.allium` | 14 | Specified and checked |
| `03-instrument` | Checklist, graders, score, scoreboard | `instrument.allium` | 9 | Specified and checked |
| `04-operator-control-plane` | Submission, receipts, progress, builder sessions | `operator-control-plane.allium` | 17 | Specified and checked |
| `05-runtime-evidence` | Runtime signals and independent verdicts | `runtime-evidence.allium` | 10 | Specified and checked |
| `06-prompt-optimization` | Builder strategy optimization | `prompt-optimization.allium` | 9 | Specified and checked |
| `07-harness-bootstrap` | Local runtime build | `harness-bootstrap.allium` | 3 | Specified and checked |
| `08-project-vm-config` | Project runtime file resolution | `project-vm-config.allium` | 2 | Specified and checked |
| `09-rpc-seam` | JSON-RPC over stdio | `affordances.yaml` (`seam`) | 4 | Specified as the seam; no Allium spec by design |

83 items in total. The glossary is `GLOSSARY.md`.

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

`affordances.yaml` is the public seam, in the same format as `model/loyly/affordances.yaml`.
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
| `PrepareCycle` | `prepare-cycle` | command `record-cycle-preparation` | `operator-control-plane.allium` `PrepareCycle` |
| `ApprovePreparation` | `approve-preparation` | command `approve-cycle-preparation` | `operator-control-plane.allium` `ApproveCyclePreparation` |
| `SubmitApprovedSlice` | `submit-approved-slice` | command `submit-cycle-task` | `operator-control-plane.allium` `SubmitCycleTask`, `RepeatIdenticalSubmission` |
| `ApproveGrader` | `approve-grader` | command `approve-grader` | `instrument.allium` `ApproveExactGrader` |
| `Measurement` | `measurement` | query `cycle-measurement` | `operator-control-plane.allium` `ReadDurableMeasurement` |
| `BuilderSession` | `builder-session` | query `builder-session` | `operator-control-plane.allium` `ObserveBuilderSession` |
| `OptimizePrompt` | `optimize-prompt` | command `start-prompt-optimization` | `prompt-optimization.allium` `StartPromptOptimization` |

`prepare-cycle` and `approve-preparation` are new; the 14 earlier methods keep their names,
params, and result shapes, so the sealed graders stay valid.

Open:

- `regeneration-cycle.allium` and `operator-control-plane.allium` both declare
  `CyclePreparation` and its prepare/approve rules (`ApprovePreparation` and
  `ApproveCyclePreparation`). One of them should own it.
- The queries `task-receipt`, `highest-task-id`, `selection`, `cycle-preparation`,
  `approved-graders`, `cycle-observations`, and `prompt-optimization` have no seam action of
  their own: dispatch, task numbering, and the actions' results read them.

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

`allium check` and `allium analyse` report no errors and these 8 warnings. Compare results
with this list, not merely the exit code; a new warning is drift until justified here.

| Spec | Line | Code | Interpretation |
|---|---:|---|---|
| `harness-bootstrap.allium` | 11 | `allium.entity.unused` | `HarnessRuntimeBootstrap` appears only in invariants over the collection; no rule creates it. Open. |
| `operator-control-plane.allium` | 13 | `allium.externalEntity.missingSourceHint` | `OperatorSession` is the Pi or Codex session; no spec governs it. Accepted. |
| `operator-control-plane.allium` | 21 | `allium.definition.unused` | `InterveneInBuilderSession` takes an untyped `intervention_kind`, not `InterventionKind`. Open. |
| `prompt-optimization.allium` | 10 | `allium.externalEntity.missingSourceHint` | `HarnessOperator` is a person; no spec governs it. Accepted. |
| `regeneration-cycle.allium` | 11 | `allium.externalEntity.missingSourceHint` | `OperatorSession`, as above. Accepted. |
| `runtime-evidence.allium` | 66 | `allium.entity.unused` | `OtelCapturePolicy` is not yet used by a rule; open. |
| `runtime-evidence.allium` | 73 | `allium.entity.unused` | `CycleEvidenceVolume` is not yet used by a rule; open. |
| `runtime-evidence.allium` | 80 | `allium.entity.unused` | `BrowserTraceArtifact` is not yet used by a rule; open. |

Info-level diagnostics (unused fields and similar) are not tracked.
