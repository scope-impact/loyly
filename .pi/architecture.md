# Pi harness architecture

The architecture the contract in [`.pi/model/`](model/) specifies. It describes the target,
derived from the model alone; it makes no claim about any built harness. Where this page and
the model disagree, the model wins.

## C4 views

Three levels, following the `c4-diagrams` skill. Every element and relationship is taken from
the model: ORC is a library, so it runs inside the harness process and appears only as
components; Grain's event store is a data store; the builder, gate, and validation rooms are
runnable containers. "Technology bindings" below names the technology behind each domain term.

### Level 1: System Context

Who uses the harness and what it depends on.

```mermaid

flowchart TB
  operator(["<b>Operator</b><br/>[Person]<br/>A person, or a Pi or Codex session acting for one"])
  driver["<b>Regeneration driver</b><br/>[Software System, external]<br/>Legacy gate and sealed graders"]
  harness["<b>Pi harness</b><br/>[Software System]<br/>Regenerates a project from an approved slice of its contract in a fenced clean room, gates the candidate, and measures it with approved sealed graders"]
  repo["<b>Project repository</b><br/>[Software System, external]<br/>Commits and stamped Gherkin plan"]
  host["<b>Durable builder host</b><br/>[Software System, provided]<br/>TypeScript on pi-durable; one durable Pi session per ORC builder tick"]
  pi["<b>Codex</b><br/>[Software System, external]<br/>The model the builder session talks to"]
  gondolin["<b>Gondolin</b><br/>[Software System, external]<br/>Micro-VMs for isolated rooms"]
  bedrock["<b>Amazon Bedrock</b><br/>[Software System, external; deferred, slice 06]<br/>Pinned reflection model"]

  operator -- "Approves and submits work, reads outcomes<br/>[JSON-RPC over stdio]" --> harness
  driver -- "Starts it, proves identity, drives graded actions<br/>[JSON-RPC over stdio]" --> harness
  harness -- "Cuts a commit, reads its plan<br/>[git]" --> repo
  harness -- "Launches one durable session per builder tick<br/>(production prompt, room policy, tick as request id)" --> host
  harness -- "Starts and stops the room ORC chose;<br/>runs gate and validation rooms" --> gondolin
  host -- "Model turns; credentials stay on the host" --> pi
  host -- "Forwards each tool call into the room" --> gondolin
  harness -. "Asks for instruction candidates<br/>[HTTPS, deferred]" .-> bedrock

  class operator person
  class harness system
  class driver,repo,host,pi,gondolin,bedrock external
  classDef person fill:#08427b,stroke:#052e56,color:#fff
  classDef system fill:#1168bd,stroke:#0b4884,color:#fff
  classDef external fill:#8a8a8a,stroke:#6b6b6b,color:#fff
  classDef container fill:#438dd5,stroke:#2e6295,color:#fff
  classDef component fill:#85bbf0,stroke:#5d82a8,color:#000
  classDef boundary fill:none,stroke:#444,stroke-dasharray:6 4,color:#444
```

- The **operator** is `actor Operator`, identified by its `OperatorSession`. Exploratory
  transcripts, skills, and branches stay in the operator session; only an approved preparation
  crosses into ORC.
- The **regeneration driver** (the legacy gate and sealed graders) reaches the harness only
  through the seam, exactly like the operator.

### Level 2: Container

The runnable parts of the harness and the stores they use.

```mermaid

flowchart LR
  operator(["<b>Operator</b><br/>[Person]"])
  driver["<b>Regeneration driver</b><br/>[Software System, external]"]

  subgraph h ["Pi harness [Software System]"]
    proc["<b>Harness process</b><br/>[Container: Clojure, ORC, Grain; launch entrypoint start.sh]<br/>Serves the seam; runs the :loyly.pi-harness service area and the ORC cycle workflow"]
    events[("<b>Event store</b><br/>[Container: Grain, tenant-scoped]<br/>Harness facts and ORC :sheet facts, never duplicated into each other")]
    state[("<b>Cycle state</b><br/>[Container: files under PI_HARNESS_STATE_ROOT]<br/>Clean rooms, candidates, gate evidence, per-cycle evidence volumes")]
    host["<b>Builder host</b><br/>[Container: TypeScript, pi-durable; provided, on the host]<br/>Runs the durable Pi loop for one tick; submits ORC's production prompt with the tick as request id; holds the Codex credentials"]
    builder["<b>Builder room</b><br/>[Container: Gondolin VM chosen by ORC, offline]<br/>Image for the target stack; clean room read-only, writes only the candidate scope; no host environment"]
    gate["<b>Gate room</b><br/>[Container: Gondolin VM, independent of the builder]<br/>Boots the candidate fresh through its launch entrypoint, proves its suite green"]
    validator["<b>Validation room</b><br/>[Container: Gondolin VM]<br/>Graders and independent invariant checks against the candidate's seam; capture policy read-only, one cycle evidence volume writable"]
  end

  subgraph ext ["External software systems"]
    direction TB
    repo["<b>Project repository</b><br/>[Software System]"]
    pi["<b>Codex</b><br/>[Software System]"]
    bedrock["<b>Amazon Bedrock</b><br/>[Software System; deferred, slice 06]"]
  end

  operator -- "Approves, submits, observes<br/>[JSON-RPC over stdio]" --> proc
  driver -- "Proves identity, drives graded actions<br/>[JSON-RPC over stdio]" --> proc
  proc -- "Appends facts, projects read models" --> events
  proc -- "Cuts a commit [git]" --> repo
  proc -- "Fences clean rooms, keeps candidates" --> state
  proc -- "Launches per builder tick: production prompt and digest, tick as request id" --> host
  proc -- "Starts and stops with the room policy" --> builder
  proc -- "Gates each candidate" --> gate
  proc -- "Scores with approved graders" --> validator
  host -- "Model turns" --> pi
  host -- "Forwards tool calls" --> builder
  builder -- "Reads clean room, writes scope" --> state
  validator -- "Writes evidence" --> state
  proc -. "Requests instruction candidates [HTTPS, deferred]" .-> bedrock

  class operator person
  class proc,host,builder,gate,validator container
  class events,state container
  class driver,repo,pi,bedrock external
  class h,ext boundary
  classDef person fill:#08427b,stroke:#052e56,color:#fff
  classDef system fill:#1168bd,stroke:#0b4884,color:#fff
  classDef external fill:#8a8a8a,stroke:#6b6b6b,color:#fff
  classDef container fill:#438dd5,stroke:#2e6295,color:#fff
  classDef component fill:#85bbf0,stroke:#5d82a8,color:#000
  classDef boundary fill:none,stroke:#444,stroke-dasharray:6 4,color:#444
```

- The **harness process** is one container: its launch entrypoint (`start.sh`) starts it, it
  serves the seam, and it runs both the `:loyly.pi-harness` service area and ORC's cycle
  workflow.
- The **event store** holds harness facts and ORC's `:sheet` facts; neither is copied into the
  other. **Cycle state** is the harness's file state under `PI_HARNESS_STATE_ROOT`; a fresh
  directory means fresh state.
- **Rooms** (builder, gate, validation) are the ones ORC starts; each is isolated from the
  others, the builder never controls the gate room, and score evidence never reaches a
  builder room. The clean room is not a room ORC starts: it is the read-only cut a builder
  room mounts.

### Level 3: Component (harness process)

The responsibilities inside the harness process.

```mermaid

flowchart TB
  operator(["<b>Operator / driver</b><br/>[Person, external system]"])

  subgraph p ["Harness process [Container]"]
    seam["<b>Seam</b><br/>[Component: JSON-RPC over stdio]<br/>Maps each of 16 actions to a command or query; refuses with a named reason"]
    control["<b>Harness commands</b><br/>[Component: Grain defcommand]<br/>Operator intents: selections, preparations, submissions, checklists, grader approval. Stage facts from ORC's nodes: self-check, clean room, slices, candidate, gate, authored and calibrated graders. System facts from policies"]
    views["<b>Projections and queries</b><br/>[Component: Grain defreadmodel, defquery]<br/>Cycles, selections, preparations, receipts, outcomes, cuts, gate attempts, graders, measurements, scoreboards, builder sessions; node-stages translates ORC"]
    dispatch["<b>Policies</b><br/>[Component: Grain defprocessor]<br/>One ORC tick per task-submitted; receipts and outcomes; start the cycle; route the gate verdict and the score; report the scoreboard; conclude the cycle; file the score; record evidence; bind workspace generations; retire replaced graders"]
    launcher["<b>Builder launcher</b><br/>[Component: Grain defprocessor launch-builder-for-tick]<br/>Launches, or relaunches, the durable session of each builder tick in the room ORC chose"]
    workflow["<b>Cycle workflow</b><br/>[Component: ORC sheet]<br/>fence, regenerate, gate, score, scoreboard; continuation, verification repair, durable resume"]
    strategy["<b>Instruction strategy</b><br/>[Component: ORC, GEPA]<br/>Renders every production prompt; optimizes builder instructions per purpose"]
    instrument["<b>Instrument</b><br/>[Component: approved sealed graders]<br/>Checklist, one verdict per item, scoreboard"]
    guard["<b>Event Model guard</b><br/>[Component: Grain verify-or-throw!]<br/>Refuses to start on any drift from the registered model"]
  end

  events[("<b>Event store</b><br/>[Container: Grain]")]
  rooms["<b>Builder, gate, validation rooms</b><br/>[Containers: Gondolin VMs]"]
  host["<b>Builder host</b><br/>[Container: pi-durable, provided]"]

  operator -- "One request per line [stdio]" --> seam
  seam -- "Issues commands" --> control
  seam -- "Runs queries" --> views
  control -- "Appends harness events" --> events
  events -- "harness facts, :sheet facts (via node-stages), committed tool results" --> dispatch
  dispatch -- "receipts, outcomes, stage advances and failures, file-score, report-scoreboard, conclude-cycle" --> control
  workflow -- "Stage facts: record-gate-attempt, prove-clean-room, ..." --> control
  dispatch -- ":sheet/tick-tree" --> workflow
  workflow -- "Appends :sheet events" --> events
  views -- "Fold harness and :sheet events from" --> events
  strategy -- "Supplies every production prompt to" --> workflow
  events -- ":sheet/node-execution-started" --> launcher
  launcher -- "launch-builder-session, one per tick" --> host
  host -- "Forwards tool calls into" --> rooms
  workflow -- "Starts rooms; runs gates and validators in" --> rooms
  workflow -- "Scores a gated candidate with" --> instrument
  instrument -- "Returns verdicts to the score node of" --> workflow
  guard -. "Checks registered blocks at startup" .-> control

  class operator person
  class seam,control,views,dispatch,launcher,workflow,strategy,instrument,guard component
  class events,rooms,host container
  class p boundary
  classDef person fill:#08427b,stroke:#052e56,color:#fff
  classDef system fill:#1168bd,stroke:#0b4884,color:#fff
  classDef external fill:#8a8a8a,stroke:#6b6b6b,color:#fff
  classDef container fill:#438dd5,stroke:#2e6295,color:#fff
  classDef component fill:#85bbf0,stroke:#5d82a8,color:#000
  classDef boundary fill:none,stroke:#444,stroke-dasharray:6 4,color:#444
```

- **Approval starts nothing.** Commands append facts; the task dispatcher turns each
  `task-submitted` into exactly one ORC tick.
- Projections and queries only read; progress and builder-session views never advance ORC.
- The instrument reports to a person and never feeds the build loop.
- The Event Model guard refuses to start when the registered model and the live registries
  disagree.

Assumptions: the model names the execution backends `host` and `gondolin` but no deployment
topology, so there is no deployment view. ORC's internals are out of scope (ORC's own
`specs/orc-service.allium`).

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
              execution-status ◄─ cycle-progress ◄─ node-stages ◄─ :sheet/* ◄───┤
              wait ◄─ task-outcomes ◄─ complete-task / fail-task ◄─ record-task-outcome ◄─┘
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
  request is refused. A replacement ORC worker responds to `wait` from ORC's durable state and
  never reruns a task.
- Progress is a projection of ORC facts through the translation `node-stages`; it never
  advances ORC. A task's outcome is recorded once, by a policy, when ORC completes its tick.

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

Each stage's outcome is a typed harness fact that ORC's stage node records
(`record-harness-self-check`, `prove-clean-room`, `record-slice-build`, `record-candidate`,
`record-gate-attempt`), or that a policy derives (`route-gate-verdict`, `route-filed-score`,
`report-cycle-scoreboard`, `close-reported-cycle`, `conclude-cycle-result`). The cycle ends as
`cycle-passed` or `cycle-failed` (ADR 0004).

The fence and the gate are the **harness** and feed the build loop. The score and the
scoreboard are the **instrument** and never do: no score evidence reaches a builder, and a
failed score is measured, not retried within the cycle.

## Grain service area

`:loyly.pi-harness` (`event-model/loyly/pi_harness/event_model.clj`). The cycle's stage facts
are harness events, recorded by commands ORC's workflow nodes issue; transitions the Allium
rules make on a state are policies (ADR 0004).

| Concern | Commands | Events | Read models → queries |
|---|---|---|---|
| Selection | `record-selection`, `approve-selection` | `selection-recorded`, `selection-approved` | `selections` → `selection` |
| Preparation | `record-cycle-preparation`, `approve-cycle-preparation` | `cycle-preparation-recorded`, `cycle-preparation-approved` | `cycle-preparations` → `cycle-preparation` |
| Cuts | `submit-cut-task`; `record-prepared-cut` (ORC node) | `cut-prepared` | `prepared-cuts` → `slice-composition` |
| Tasks | `submit-cycle-task`, `submit-whole-plan-cycle`; `record-task-receipt`, `complete-task`, `fail-task` (processors) | `task-submitted`, `task-receipt-issued`, `task-completed`, `task-failed` | `task-receipts` → `task-receipt`, `tick-task`, `highest-task-id`; `task-outcomes` → `task-outcome` |
| Cycle | `start-cycle`, `advance-cycle-stage`, `fail-cycle-stage`, `conclude-cycle` (processors); `record-harness-self-check`, `prove-clean-room`, `record-slice-build`, `record-candidate`, `record-gate-attempt` (ORC nodes); `record-child-cycle` | `cycle-submitted`, `cycle-started`, `harness-self-check-recorded`, `clean-room-proved`, `slice-built`, `candidate-regenerated`, `gate-attempt-recorded`, `cycle-stage-advanced`, `cycle-passed`, `cycle-failed`, `child-cycle-lineage-recorded` | `regeneration-cycles` → `regeneration-cycle`; `harness-self-checks`; `gate-attempts` → `gate-attempt` |
| ORC translation | | | `node-stages` → `node-stage`, `cycle-progress` |
| Builder sessions | `launch-builder-session`, `relaunch-builder-session`, `bind-workspace-generation` (processors); `commit-builder-tool-result`, `end-builder-session` (builder host); `intervene-in-builder-session` | `builder-session-launched`, `builder-session-resumed`, `builder-tool-result-committed`, `builder-session-ended`, `workspace-generation-bound`, `builder-intervention-recorded` | `builder-sessions` → `builder-session` |
| Instrument | `derive-checklist`, `request-grader`, `approve-grader`; `record-authored-grader`, `seal-grader`, `discard-grader` (ORC nodes); `retire-grader`, `file-score`, `report-scoreboard` (processors) | `checklist-derived`, `grader-authoring-requested`, `grader-recalibration-requested`, `grader-authored`, `grader-sealed`, `grader-discarded`, `instrument-declared-unavailable`, `grader-approved`, `grader-replaced`, `cycle-measurement-recorded`, `score-refused`, `scoreboard-reported` | `checklists` → `checklist`; `graders` → `grader`, `approved-graders`; `cycle-measurements` → `cycle-measurement`; `scoreboards` → `scoreboard` |
| Runtime evidence | `record-observation` (processor); `record-validation-room`, `bind-baseline-trace` (ORC node) | `observation-recorded`, `validation-room-opened`, `baseline-trace-bound` | `cycle-observations` → `cycle-observations` |
| Prompt optimization | `start-prompt-optimization`; `record-production-prompt`, `propose-instruction-candidate`, `measure-instruction-candidate`, `reject-instruction-candidate`, `select-winning-instruction`, `fail-prompt-optimization` (ORC) | `prompt-optimization-started`, `production-prompt-rendered`, `instruction-candidate-proposed`, `instruction-candidate-measured`, `instruction-candidate-rejected`, `winning-instruction-selected`, `prompt-optimization-failed` | `prompt-optimizations` → `prompt-optimization` |

The screen `pi-regeneration` issues operator intents only: selection, preparation, approvals,
submissions, checklist derivation, grader request and approval, builder intervention, child
cycles, and optimization. It links the surfaces `RegenerationControlPlane`, `RegenerationHarness`,
`Instrument`, and `BuilderPromptOptimization`. Every command links the Allium rule or rules it
realises. Every `tick-id` is a `:uuid`, as in ORC, and every cycle is named by `:cycle-id`.

Fourteen todo processors issue system facts, each reading a to-do query:

| Processor | When | Issues |
|---|---|---|
| `dispatch-submitted-task` | a task is submitted | `:sheet/tick-tree`, once per task |
| `issue-task-receipt` | ORC starts the dispatched tick | `record-task-receipt` |
| `start-dispatched-cycle` | a cycle's task is bound to its tick | `start-cycle` |
| `record-task-outcome` | ORC completes or cancels the tick | `complete-task` or `fail-task` |
| `launch-builder-for-tick` | ORC starts a node `node-stage` names a builder role | `launch-builder-session` or `relaunch-builder-session` |
| `bind-committed-result` | the builder host commits a tool result | `bind-workspace-generation` |
| `retire-replaced-grader` | an operator approves a grader that replaces another | `retire-grader` |
| `route-gate-verdict` | a gate attempt is recorded | `advance-cycle-stage` (score, or regenerate under three attempts) or `fail-cycle-stage` |
| `file-cycle-score` | ORC completes a node `node-stage` names the score stage | `file-score` |
| `route-filed-score` | a measurement is filed or a score refused | `advance-cycle-stage` (scoreboard) or `fail-cycle-stage` |
| `report-cycle-scoreboard` | a cycle reaches the scoreboard | `report-scoreboard` |
| `close-reported-cycle` | the scoreboard is reported | `advance-cycle-stage` (result) |
| `conclude-cycle-result` | a cycle reaches its result | `conclude-cycle` (passed or failed) |
| `record-validation-evidence` | ORC completes a validation node | `record-observation` |

`:sheet` (`event-model/orc/sheet/event_model.clj`) declares the ORC blocks the area uses: the
command `tick-tree` and the events `tree-tick-started`, `node-execution-started`,
`node-execution-completed`, `tree-tick-completed`, and `tick-cancelled`, copied from ORC's
pinned `orc-service` schemas. Only `node-stages` and the processors' triggers touch them.
Startup validates the registered model strictly against the live registries and refuses to
start on drift.

## Context map

The harness is one bounded context. Every other context meets it at a named boundary.

```text
                    ┌───────────────────────────────┐
                    │ ORC (upstream, Grain library) │
                    │ :sheet workflow facts         │
                    └───────────────┬───────────────┘
                    Conformist; ACL │ :sheet/tick-tree ▲  :sheet/* events ▼
                    = node-stages   │ stage commands from ORC's workflow nodes ▼
┌──────────────┐    ┌───────────────┴───────────────┐    ┌──────────────────────────┐
│ Operator /   │───►│ Pi harness  :loyly.pi-harness │───►│ Builder host (provided)  │
│ driver       │seam│ cycle, instrument, tasks,     │OHS │ launch / resume /        │
└──────────────┘    │ builder sessions, evidence    │◄───│ commit tool result       │
                    └──────┬─────────────────┬──────┘    └────────────┬─────────────┘
       Published Language: │                 │ rooms ORC chose        │ tool calls
       affordances seam    ▼                 ▼                        ▼
           ┌────────────────────┐    ┌──────────────────────────────────────────┐
           │ Regenerated product│    │ Gondolin (provided infrastructure,       │
           │ (candidate)        │    │ no domain model)                         │
           └────────────────────┘    └──────────────────────────────────────────┘
       Bedrock / OpenRouter: model providers for reflection and builders (deferred)
```

| Context | Relationship to the harness | Where it is modelled |
|---|---|---|
| **Pi harness** (`:loyly.pi-harness`) | The context this model specifies: cuts, selections, preparations, the regeneration cycle and its stage facts, the instrument, tasks, builder sessions, runtime evidence, prompt optimization. | `.pi/model/*.allium`, `event-model/loyly/pi_harness/` |
| **ORC** | Upstream. The harness is **Conformist** to ORC's `:sheet` model: it dispatches `:sheet/tick-tree` and consumes `:sheet/*` events as ORC defines them, and translates them through an **anti-corruption layer**, the read model `node-stages`, into its own `CycleStage` and builder role. ORC's workflow nodes record stage facts by issuing harness commands (ADR 0004). In Allium, ORC is `actor OrcRuntime`, and every fact it reports reaches a surface facing it, already in the harness's terms (stage and role, never a node id). | `event-model/orc/sheet/` (a declared copy; see ADR 0002 "Known risk") |
| **Builder host** (provided, `@earendil-works/pi-durable`) | **Open Host Service**: launch a durable session for a tick, resume it, and report a committed tool result (`commit-builder-tool-result`) and the session's end (`end-builder-session`). It runs the model loop and forwards tool calls; it decides nothing about rooms, production prompts, or stages. | `launch-builder-session`, `relaunch-builder-session`, `commit-builder-tool-result`; `execution-pipeline.allium` `BuilderSession` |
| **Gondolin** (provided) | Infrastructure with no domain model: micro-VMs for builder, gate, and validation rooms. The harness names a room only through `BuilderRoomPolicy`, which ORC chooses. | `BuilderRoomPolicy`, `OrcAloneChoosesTheRoom` |
| **Regenerated product** | **Published Language**: the product is reached only through its affordance seam (`affordances.yaml`-style JSON-RPC over stdio), by the gate and by sealed graders. It shares no code or runtime with the harness, and the harness shares no events with it. | `model/<project>/affordances.yaml`; this harness's own seam is `.pi/model/affordances.yaml` |
| **Bedrock / OpenRouter** | Model providers, deferred: the pinned Bedrock reflection model for prompt optimization (slice 06) and the builder's model provider behind the builder host. No harness block talks to them directly. | `prompt-optimization.allium` `ReflectionModelBinding` |

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
inconclusive, and a diagnostic signal never decides a verdict. Validation runs in a
validation room with a pinned read-only capture policy and a per-cycle writable evidence
volume; a passing baseline is bound to its complete trace artifact. Reports are projections
and never authority.

## Prompt optimization

ORC owns every production prompt. A builder instruction is optimized per purpose (fresh
generation, verification repair) as a complete strategy: GEPA proposes candidates through a
pinned Bedrock reflection model, each candidate is measured by independent regeneration, and
the winner is activated durably with its provenance, superseding the purpose's previous
winner. The builder cannot author its own metric, and enforcement never depends on the
builder instruction being followed.

## Durable builder sessions

Every agent step of the cycle is a durable Pi builder session that ORC launches: the slice
builder (and its continuations), the repair builder after a failed gate, and the grader author.
The work is split so that ORC decides everything and the provided builder host only runs the
model loop.

| ORC (in the harness process) | Provided builder host (TypeScript, `@earendil-works/pi-durable`) |
|---|---|
| The stages, one tick per builder attempt, limits, timeouts, cancellation, resume | Runs the durable Pi agent loop: model turns and tool calls are committed before they count |
| The production prompt for each role and its digest | Submits ORC's production prompt with the tick as its request identity, so a relaunch resumes |
| The room policy: builder image for the stack, cut read-only, scope-only writes, gate evidence read-only for a repair, only Gherkin and protocol for a grader author, no egress, no host environment | Connects to the model (Codex) with credentials that stay on the host |
| Starts and stops the Gondolin room | Forwards each tool call into that room; it cannot widen it |
| Records session ↔ tick, progress, and outcome as Grain facts (`builder-sessions`) | Reports committed tool results and the session's end (`end-builder-session`) |
| Binds a workspace generation to each committed tool result | |

A relaunch of the same tick resumes the same session from its committed turns and never starts a
second one (`02-018`). ORC alone chooses the room (`02-019`). The builder host and the Gondolin
images (`harness/Makefile`, `harness/<project-id>/`, `.pi/harness/config/`) are provided, like
ORC and Grain, and checked by their own tests, not by the checklist. The gate and validation
rooms use Gondolin directly, without Pi.

## Technology bindings

The model names domain terms; this table binds each to the technology that realises it. A
binding may change without changing the model, except where the seam fixes a value (the
backend value `gondolin`, the profile value `gondolin-isolated`, the seam's `start`).

| Domain term (glossary) | Technology |
|---|---|
| Execution backend `gondolin`; builder, gate, and validation rooms | Gondolin micro-VMs, offline, with no host environment; backend `host` runs on the host, for development |
| Builder room's target stack (`BuilderRoomPolicy.target_stack`) | The Gondolin image for that stack (`harness/<project-id>/`, `.pi/harness/config/`) |
| Builder host | `@earendil-works/pi-durable` (TypeScript), one durable Pi session per ORC tick; the model is Codex, whose credentials stay on the host |
| Launch entrypoint | `APP_DIR/start.sh`, speaking JSON-RPC over stdio (the seam's `start`) |
| Uncarried list | The slice builder's `UNCARRIED.md` in its candidate scope |
| Reflection provider (`prompt-optimization.allium` `config.reflection_provider`) | Amazon Bedrock, with a pinned model; AWS credentials never enter a production prompt |
| Capture policy, runtime signals | An OpenTelemetry (OTel) capture policy and its probes, mounted read-only at `/observability`; the cycle evidence volume at `/evidence` |
| Trace artifact (`config.baseline_trace_path`) | Recorded by Playwright, at `playwright/baseline/trace.zip` in the cycle evidence volume |
| Event store | Grain's tenant-scoped event store, shared with ORC |
| Harness process | One JVM running Clojure, ORC, and the `:loyly.pi-harness` Grain service area |

## Where the model states it

| Concern | Gherkin slice | Allium | Grain | Seam |
|---|---|---|---|---|
| Cuts, selection, preparation, cycles | `01-regeneration-cycle` | `regeneration-cycle.allium` | selection, preparation, cuts | cut and selection, preparation |
| The cycle's stages | `02-execution-pipeline` | `execution-pipeline.allium` | cycle, ORC translation | `CycleContract`, `WorkflowContract` |
| Instrument | `03-instrument` | `instrument.allium` | instrument | `ApproveGrader`, `Measurement` |
| Control plane | `04-operator-control-plane` | `operator-control-plane.allium` | tasks, progress, dispatch | cycle, builder sessions |
| Runtime evidence | `05-runtime-evidence` | `runtime-evidence.allium` | `record-observation`, `record-validation-evidence` | |
| Prompt optimization | `06-prompt-optimization` | `prompt-optimization.allium` | prompt optimization | `OptimizePrompt` |
| The seam | `09-rpc-seam` | | | `affordances.yaml` `seam` |

[`model/COVERAGE.md`](model/COVERAGE.md) holds the exact mappings, the ownership between Allium
files, the verification commands, and the diagnostic baseline (no Allium errors or warnings;
Grain's composition gate clean). The vocabulary is [`model/GLOSSARY.md`](model/GLOSSARY.md);
the decisions are in [`model/docs/adr/`](model/docs/adr/).
