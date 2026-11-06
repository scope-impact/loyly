# Pi harness architecture

The architecture the contract in [`.pi/model/`](model/) specifies. It describes the target,
derived from the model alone; it makes no claim about any implementation. Where this page and
the model disagree, the model wins.

## Context

```text
                    ┌───────────────────────── Pi harness ─────────────────────────┐
  Operator ────────►│ seam: JSON-RPC over stdio (affordances.yaml)                  │
  (person, or Pi /  │                                                               │
   Codex session)   │ :loyly.pi-harness service area ──► ORC (:sheet ticks) ────────┼──► Pi/Codex builder
  Gate ────────────►│   commands, events, read models,     the cycle, retries,      │    sessions
  Grader ──────────►│   queries, one dispatch processor    resume                   │
                    │            shared Grain runtime (tenant-scoped event store)   │──► Gondolin rooms
                    └───────────────────────────────────────────────────────────────┘──► Bedrock (reflection)
                                     │ PI_HARNESS_PROJECT_ROOT
                                     ▼
                       the project repository: commits to cut, its PLAN_DIR
```

- **Operator** (`actor Operator`, identified by its `OperatorSession`): approves selections,
  preparations, and graders, submits cycles, and reads outcomes. Exploratory transcripts,
  skills, and branches stay in the operator session; only an approved preparation crosses into
  ORC.
- **Gate** and **grader** drive the harness only through the seam, like the operator.
- **ORC** owns execution: the cycle's ticks, retries, termination, and durable resume. Its
  `:sheet/*` events are the authority for execution.
- **Grain** is the shared runtime. The harness is one service area in it; ORC's facts and the
  harness's facts are never duplicated into each other.

## The seam

`APP_DIR/start.sh` reads one JSON request per line on stdin and writes one response per line
on stdout; every request gets exactly one response carrying its id. `PI_HARNESS_PROJECT_ROOT`
names the repository worked on; `PI_HARNESS_STATE_ROOT` holds the harness's own state, and a
fresh directory means fresh state.

| Group | Actions (method) |
|---|---|
| Cycle | `CycleContract` (`cycle-contract`), `WorkflowContract` (`workflow-contract`), `SubmitCycle` (`submit-cycle`), `Wait` (`wait`), `ExecutionStatus` (`execution-status`) |
| Cut and selection | `PrepareCut` (`prepare-cut`), `SliceComposition` (`slice-composition`), `PreviewSelection` (`preview-selection`), `ApproveSelection` (`approve-selection`) |
| Preparation | `PrepareCycle` (`prepare-cycle`), `ApprovePreparation` (`approve-preparation`), `SubmitApprovedSlice` (`submit-approved-slice`) |
| Instrument | `ApproveGrader` (`approve-grader`), `Measurement` (`measurement`) |
| Builder sessions | `BuilderSession` (`builder-session`) |
| Prompt optimization | `OptimizePrompt` (`optimize-prompt`) |

Each action names the Allium rule its outcomes and rejections realise and the Grain command or
query it reaches. A rejection is a refused response naming what and why; the harness keeps
running.

## Control plane

```text
prepare-cut ─► slice-composition ─► preview-selection ─► approve-selection
                                                              │
                         prepare-cycle ─► approve-preparation ┘
                                │
              submit-approved-slice ──┐        submit-cycle (whole plan) ──┐
                                      ▼                                    ▼
                               task-submitted ─► dispatch-submitted-task ─► :sheet/tick-tree (ORC)
                                                                                 │
              execution-status ◄─ cycle-progress ◄─ :sheet/* events ◄────────────┤
              wait             ◄─ task-outcomes  ◄─ :sheet/tree-tick-completed ◄─┘
```

- Every identity is deterministic: previewing or preparing the same choices again returns the
  same identity and digest, and approval accepts only that exact digest.
- **Approval starts nothing.** ORC is dispatched once per submitted task.
- A **slice cycle** runs an approved preparation, which binds the selection, scope, target
  stack, execution profile, cut digest, grader digest, diagram digest, and outer-cycle budget.
- A **whole-plan cycle** (`submit-cycle`) runs the whole plan of the pinned cut under a fixed
  preparation (configured scope, stack, and profile, approved graders, one outer cycle);
  submitting it is that preparation's approval, as in the legacy shape.
- Repeating a request identity returns the original receipt; reusing it for a different
  request is refused. A replacement worker answers `wait` from ORC's durable state and never
  reruns a task.
- Progress and builder-session views are projections of ORC facts; they never advance ORC.

## The cycle

```text
fence → regenerate → gate → score → scoreboard
```

| Stage | Behaviour |
|---|---|
| fence | Builds a clean room from the pinned cut only after the harness self-check passes. The fence is physical: fenced paths are absent from the tree and the object graph. |
| regenerate | One fresh builder session per slice, in plan order, each extending the cycle's candidate; continued in the same session at most three times while its slice is uncarried. The builder reads the clean room read-only and writes only its declared scope. |
| gate | A room the builder does not control boots the candidate fresh and proves its suite green. A failed gate may start a fresh repair builder with only the previous candidate and the gate's evidence, at most three attempts. |
| score | Approved sealed graders file one verdict per checklist item through the seam. An unreached item fails and stays in the denominator. |
| scoreboard | Compares with the previous cycle on shared items, newly passing and newly failing separately; new items are the untuned holdout; never a net. |

The fence and the gate are the **harness** and feed the build loop. The score and the
scoreboard are the **instrument** and never do: no score evidence reaches a builder, and a
failed score is measured, not retried within the cycle.

## Grain service area

`:loyly.pi-harness` (`event-model/loyly/pi_harness/event_model.clj`):

| Concern | Commands | Events | Read models → queries |
|---|---|---|---|
| Selection | `record-selection`, `approve-selection` | `selection-recorded`, `selection-approved` | `selections` → `selection` |
| Preparation | `record-cycle-preparation`, `approve-cycle-preparation` | `cycle-preparation-recorded`, `cycle-preparation-approved` | `cycle-preparations` → `cycle-preparation` |
| Tasks | `submit-cut-task`, `submit-cycle-task`, `submit-whole-plan-cycle`, `record-task-receipt` | `task-submitted`, `task-receipt-issued` | `task-receipts` → `task-receipt`, `highest-task-id`; `task-outcomes` → `task-outcome` |
| Cuts | | | `prepared-cuts` → `slice-composition` |
| Progress | | | `cycle-progress` → `cycle-progress`; `builder-sessions` → `builder-session` |
| Instrument | `approve-grader`, `record-observation`, `record-cycle-measurement` | `grader-approved`, `observation-recorded`, `cycle-measurement-recorded` | `approved-graders`, `cycle-observations`, `cycle-measurements` → same-named queries |
| Prompt optimization | `start-prompt-optimization` | `prompt-optimization-started` | `prompt-optimizations` → `prompt-optimization` |

One todo processor, `dispatch-submitted-task`, turns each `task-submitted` into ORC's
`:sheet/tick-tree`. One screen, `pi-regeneration`, is the operator's control surface. Every
command links an Allium rule and the screen links the `RegenerationControlPlane` surface.

`:sheet` (`event-model/orc/sheet/event_model.clj`) declares the ORC blocks the area uses: the
command `tick-tree` and the events `tree-tick-started`, `node-execution-started`,
`node-execution-completed`, `tree-tick-completed`, and `tick-cancelled`, copied from ORC's
pinned `orc-service` schemas. Startup validates the registered model strictly against the live
registries and refuses to start on drift.

## Instrument

- The **checklist** is one item per stamped scenario of the approved selection, derived and
  never written; an unstamped scenario or an empty selection stops the cycle.
- A **grader** is written once from the selected Gherkin and the public calibration protocol by
  an author who cannot read any candidate, transcript, or prior verdict. It is calibrated so
  carried behaviour passes and uncarried behaviour fails, sealed by digest, discarded and
  re-authored at most three times if uncalibrated, and used only after the operator approves
  its exact digest. It is reused until its scenarios change and replaced only for a recorded
  reason; never to turn a score green.

## Runtime evidence

Producers record runtime signals with their authority (candidate, harness observer,
independent validator) and purpose (assertion, diagnostic, calibration). Only an independent
validator files a verdict, only from complete, unsampled collection; incomplete collection is
inconclusive, and a diagnostic signal never decides a verdict. Validation runs in an
independent room with a pinned read-only capture policy and a per-cycle writable evidence
volume; a passing baseline is bound to its complete Playwright trace. Reports are projections
and never authority.

## Prompt optimization

ORC owns every production prompt. A builder instruction is optimized per purpose (fresh
generation, verification repair) as a complete strategy: GEPA proposes candidates through a
pinned Bedrock reflection model, each candidate is measured by independent regeneration, and
the winner is activated durably with its provenance. The builder cannot author its own metric,
and enforcement never depends on the prompt being followed.

## Bootstrap and project runtime

`harness/Makefile`'s bootstrap installs dependencies, builds and verifies the harness and
supported-stack runtime assets, and binds them to a snapshot manifest and calibration digest;
it needs no grader and regenerates nothing. A project's VM configuration and stack Dockerfiles
are resolved by convention from `harness/<project-id>/`, never across projects, and an unknown
or mismatched stack fails closed.

## Where the model states it

| Concern | Gherkin slice | Allium | Grain | Seam |
|---|---|---|---|---|
| Cuts, selection, preparation, cycles | `01-regeneration-cycle` | `regeneration-cycle.allium` | selection, preparation, cuts | cut and selection, preparation |
| The cycle's stages | `02-execution-pipeline` | `execution-pipeline.allium` | | `CycleContract`, `WorkflowContract` |
| Instrument | `03-instrument` | `instrument.allium` | instrument | `ApproveGrader`, `Measurement` |
| Control plane | `04-operator-control-plane` | `operator-control-plane.allium` | tasks, progress, dispatch | cycle, builder sessions |
| Runtime evidence | `05-runtime-evidence` | `runtime-evidence.allium` | `record-observation` | |
| Prompt optimization | `06-prompt-optimization` | `prompt-optimization.allium` | prompt optimization | `OptimizePrompt` |
| Bootstrap | `07-harness-bootstrap` | `harness-bootstrap.allium` | | |
| Project runtime | `08-project-vm-config` | `project-vm-config.allium` | | |
| The seam | `09-rpc-seam` | | | `affordances.yaml` `seam` |

[`model/COVERAGE.md`](model/COVERAGE.md) holds the exact mappings, the ownership between Allium
files, the verification commands, and the diagnostic baseline (no Allium errors or warnings;
Grain's composition gate clean). The vocabulary is [`model/GLOSSARY.md`](model/GLOSSARY.md);
the decisions are in [`model/docs/adr/`](model/docs/adr/).
