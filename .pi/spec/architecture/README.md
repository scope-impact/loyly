# Architecture

How the harness runs a regeneration cycle, and which ORC feature carries each part of the spec.
The decisions behind it are in `adr/`. The domain events and their flows are in the event storm
(`../event-storm.md`).

**What is settled and what is not.** The domain is accepted as is: the five contexts 01 to 05
(their scenarios, event models and Allium rules) and the method (fence, clean room, gate as
judge, at most three gate attempts, an honest score). How the cycle is driven (slice 06, the
cycle runner) is written into the spec as the tree below in spec round 18; ADR 0001 stays
proposed until a build of that tree runs a cycle, and the rules marked proposed below wait on
their ADRs. It is settled by the throwaway prototype on the branch `proto/orc-harness` and by the
product owner's decisions.

## The cycle is one ORC tree

The new harness is one small ORC workflow on one Grain store, and ORC's own features do the work
(ADR 0001). The contexts 01 to 05 are the domain; their commands are its leaves:

```text
cycle.v1 (one tick per run; one trace; one store)
├─ stamp       01  stamp the items (map-each)                     code leaf
├─ pin         05  pin the cut                                    code leaf
├─ checklist   02  derive the checklist                           code leaf
├─ grader      03  a sealed, approved judge version               code leaf
├─ prepare     04  self-check, fence                              code leaves
├─ render          the builder instruction (GEPA's target)        llm node
├─ build       04  pi-durable session in a Gondolin VM            code leaf
├─ gate ×≤3    04  delegate gate-attempt.v1: install, self-check, code leaves
│                  gate, repair (pi-durable, previous candidate + gate evidence)
├─ grade           map-each item: delegate grade.v1: clear evidence, a repl-researcher
│                  drives the seam (input: seam + event model), the judge scores pass/fail
└─ score       02  file every item; the scoreboard                code leaf
resume-in-progress!        a run that stopped picks up where it was (code leaves)
telemetry exporter         every ORC event as OpenTelemetry spans
self-improving loop        between cycles: GEPA on the builder and grader instructions
```

```text
               ┌──────────── host ─────────────┐        ┌──── Gondolin VM (builder image) ────┐
 operator ───► │ ORC tree (cycle.v1)            │        │ mounted: room, candidate, caches    │
               │  model calls ──► pi/Codex shim │        │ network: closed unless allowed      │
               │  tool calls ───────────────────┼──────► │ bash, files, the candidate's start  │
               │  events ──► OTLP exporter      │        └─────────────────────────────────────┘
               └────────────────────────────────┘
```

The stamp, the pin, the checklist and the grader come before the self-check: a refusal there
comes before the cycle opens, so the run halts with no cycle to fail. Slice 06's spec carries this
tree since spec round 18 (`../cycle-runner/seam.md`, "The tree"; `../decisions.md`, "Spec round
18"). Until the tree runs, the legacy shell harness drives cycles, and it builds this one tree from
all six contexts' specs in one cycle (ADR 0006).

## The spec against ORC's features

Status: **accepted** holds now; **proposed** waits on the prototype. Spec round 18 wrote slice 06
as the tree; a row it settled says so.

### 1. The cycle: slice 06's effects become the tree

| Spec today (06 seam) | ORC feature | Change | Status |
|---|---|---|---|
| `start-run`, the tree `run-to-gate` | the root workflow `cycle.v1`; a run is one execution | simplify | accepted (spec round 18) |
| E1 `stamp`, `pin`, `checklist` | `code` leaves; the stamps a `map-each` | none | accepted |
| E2 `self-check`, E3 `fence` | `code` leaves | none | accepted |
| E4 `regenerate` (a launched builder, `LOYLY_BUILDER_CMD`) | a `code` leaf running a pi-durable session in the VM, after an `llm` node that renders the instruction | rewrite | accepted (spec round 18) |
| E5 `install`, `gate`, `gate-refusal` | `delegate` to `gate-attempt.v1`; the gate's steps as `code` leaves in the VM | the gate moves into the VM | the delegate accepted (spec round 18); the gate in the VM proposed (ADR 0002) |
| E6 `repair` | the last leaf of the failed attempt's `gate-attempt.v1`, a pi-durable session with the previous candidate and the gate evidence | rewrite | accepted (spec round 18) |
| `hand-in-verdicts`, E7 `file-verdicts` | `map-each` over the items, each delegated to `grade.v1`: a `repl-researcher` drives the seam, the judge scores it | rewrite: verdicts made in the tree, not handed in | accepted (spec round 18) |
| E7 `file-score`, `fail-score-stage` | `code` leaf from the judge's scores to 02 | the command stays, its input changes | accepted (spec round 18) |
| E8 `relay` | none: one store, no handoffs (ADR 0001) | removed | accepted (spec round 18) |
| `run-progress` | read model over ORC's tick events | none | accepted |
| "a run cannot resume; keep the runner alive" | `resume-in-progress!` | reversed | accepted (spec round 18); a researcher in flight is not resumed at ORC's pin |
| wait bounds, a leaf per effect, blackboard keys (rounds 14 and 16) | ORC's principles as they are | none | accepted |
| (none) | the telemetry exporter, to OpenTelemetry | add | the seam line accepted (spec round 18, no stamped item); the backend proposed (ADR 0005) |

### 2. The contexts 01 to 05: each command is a leaf of `cycle.v1`

| Context | Commands; queries | ORC feature | Change | Status |
|---|---|---|---|---|
| 01 specification | `stamp-slice`; `stamped-items` | a leaf; a read model on the one store | none | accepted |
| 05 cycle-preparation | `pin-source-cut`; `source-cuts` | a leaf; a read model on the one store | none | accepted |
| 04 regeneration-cycle | `record-harness-self-check`, `prove-clean-room`, `record-regenerated-candidate`, `install-candidate-in-gate-room`, `record-gate-attempt`, `record-gate-refusal`, `pass-cycle`, `fail-cycle`; `cycle-progress`, `cycles-to-conclude` | leaves; the conclude policy a leaf after a `condition` | none to the rules | accepted |
| 02 measurement | `derive-checklist`, `file-item-verdicts`, `file-score`, `fail-score-stage`, `report-scoreboard`; `checklist`, `cycle-measurement`, `measurements-to-report`, `scoreboard` | leaves; the item verdicts come from the judge's scores | seam wording only; the rules stay | accepted (spec round 18) |
| 03 grader-lifecycle | `write-instrument-golden`, `move-instrument-golden`; `instrument-goldens` | a published, versioned judge workflow and its rubric digest are the golden | wording only | accepted (ADR 0003) |

### 3. Rules across the spec

| Rule today | With ORC's features | Status |
|---|---|---|
| graders in another functional language, sharing no runtime | the ORC judge on a model other than the builder's, reaching the candidate only over its seam | accepted (ADR 0003) |
| the builder is pi on Gondolin, a provided launcher | pi is the model behind ORC's nodes; pi's packages stay provided, never regenerated | proposed (ADR 0001) |
| tools of the builder and grader in a VM | Gondolin, one image for build, gate and grade | proposed (ADR 0002) |
| the builder instruction frozen, GEPA not run | GEPA improves the builder and the grader instruction per project context, as versioned workflows | accepted (ADR 0004) |
| a score never becomes builder input; the instrument reports to a person | the judge's scores feed GEPA between cycles, never a running build; the grader tuned on calibration only | accepted (ADR 0004) |
| telemetry: `watch.sh` over the builder's output | OpenTelemetry from ORC, pi and Claude Code | proposed (ADR 0005) |
| fence, clean room, gate as judge, at most three attempts, "not in the spec, it dies" | unchanged; ORC runs them | accepted |
| a Grain store per context, handoffs relayed | one Grain store, no relay, hand-in methods or handoff contracts | accepted (spec round 18) |
| the shell runbook drives cycles | the legacy shell harness drives cycles and builds `cycle.v1` | accepted (ADR 0006) |

## Decisions

| ADR | Title | Status |
|---|---|---|
| [0001](adr/0001-harness-is-one-orc-tree.md) | The harness is one ORC tree on one store | proposed |
| [0002](adr/0002-gondolin-for-build-gate-grade.md) | Gondolin for build, gate and grade | proposed |
| [0003](adr/0003-judge-scores-checklist-items.md) | The judge scores checklist items | accepted |
| [0004](adr/0004-self-improving-loop-scope.md) | GEPA improves the builder and the grader, per project context | accepted |
| [0005](adr/0005-opentelemetry.md) | OpenTelemetry | proposed |
| [0006](adr/0006-legacy-shell-harness.md) | The legacy shell harness drives cycles and builds the new harness | accepted |

An ADR is one page: Status, Context, Decision, Consequences, Evidence. A proposed ADR gains
evidence as prototypes and cycles report, and is accepted in a spec round (`../decisions.md`). A
superseded ADR is deleted, not kept beside its replacement; git history keeps it.
