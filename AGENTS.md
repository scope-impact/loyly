# Löyly Agent Instructions

The maintained assets are the behavioral specifications under `.pi/spec/` (spec v2, one folder
per bounded context; `.pi/model/` is spec v1, kept for its glossary); generated
implementation is a reproducible build product. Keep specification planning and
implementation in separate rounds. Never repair a specification from inside a fenced
builder run.

## Pi and ORC ownership

- Pi, Codex, or another operator session explores intent, composes the system view,
  proposes a slice, and presents the exact deterministic selection for human approval.
- ORC executes only an immutable approved contract. It owns bounded prompts, constrained
  tools, retries, termination, verification/repair, grading, and durable evidence.
- Exploratory transcripts, skills, branches, prior candidates, and grading evidence do not
  cross into a fresh builder unless an explicit workflow contract permits that input.

## Languages and tools

Each part has one language. Do not mix them.

| Part | Language and tools | Where |
|---|---|---|
| The model: behaviour | Gherkin scenarios, one stamped `@item-NN-SSS` per checklist item | `.pi/spec/<context>/spec.feature`; an accepted context is slice `NN` through its link `.pi/plan/NN-<context>` |
| The model: event storming and rules | Grain Event Model (commands, events, read models, queries, processors) and Allium (entities, rules, invariants, surfaces); the public seam | `.pi/spec/<context>/event-model/*.edn`, `.pi/spec/<context>/allium/`, `.pi/spec/<context>/seam.md` |
| The harness implementation | Clojure on ORC, with Grain for the event store; each context registers its room's event model as given | `harness-impl/NN-<context>/`, one per context, generated from the model |
| The builder host and Gondolin rooms | TypeScript on `@earendil-works/pi-durable`: one durable Pi session per ORC tick, tool calls forwarded into a Gondolin room ORC chose. Provided, like ORC and Grain: ORC launches it, never regenerates it. Not needed for first light: the builder is a person-run `pi` session | `.pi/harness/`, `harness/Makefile`, `harness/<project-id>/` |
| The graders | Elixir scripts (`.exs`), driving the candidate only over its JSON-RPC seam | sealed under `.pi/harness/.state/graders/` |
| The legacy driver | Babashka and shell, the legacy harness tools | `harness/` |

Graders use a functional language other than the product's Clojure, so they share no code or
runtime with what they measure. The three graders sealed before this choice are Python and
stay in use until they are ported, re-calibrated, and re-approved.

## The regeneration cycle

ORC runs one cycle, in the shape of the legacy harness (`archive/legacy-harness/`). Until the
harness can rebuild itself (first light, `.pi/spec/decisions.md`), the legacy driver runs it:
`SLICE=NN harness/fence.sh ROOM`, a person-run `pi` session in ROOM with the prompt
`bb harness/v2/prompt.clj NN` renders, then `harness/v2/gate.sh NN ROOM`. A cycle rebuilds
**one context** from scratch, into `harness-impl/NN-<context>/`; an assembly gate wiring the
contexts through their handoffs comes later; until then the handoffs a context consumes are
handed to its candidate through its seam, one method per consumed event (`.pi/spec/decisions.md`,
"Slice 02").

```text
fence → regenerate → gate → score → scoreboard
```

- **Fence**: build a clean room from the approved cut and prove it. The fence is physical:
  every fenced path is absent from the working tree and the object graph, and a fenced path
  that matches nothing stops the fence. No room is built until the harness self-check passes.
- **Regenerate**: a fresh builder session rebuilds the selection from the clean room only.
- **Gate**: a room the builder does not control boots the candidate fresh and proves its
  suite green and its identity over its seam, then reads the Grain store it handed the
  candidate directly, not through the seam: it holds events, each is an event of the
  context's event model, or a handoff it consumes, with exactly that event's fields, or ORC's
  own, and each event a
  command produced lies inside a completed run of its ORC workflow in that same store. Done is the gate's verdict, never the builder's claim. A failed gate may start
  a fresh builder with only the previous candidate and the gate's evidence, at most three
  attempts.
- **Score**: the approved sealed grader files one verdict per checklist item.
- **Scoreboard**: report the pass rate and compare with the previous cycle on shared items.

The fence and the gate are the **harness**: they feed the build loop. The score and the
scoreboard are the **instrument**: they report to a person, once a cycle, and never into the
loop. Anything not written into the spec dies with the implementation.

## The clean room

The clean room holds the spec of the one context being rebuilt, the handoff contracts it
consumes, the glossary, the domain sources, and its public seam. It holds no other context's
spec, no spec v1, no prior implementation, recorded measurement, report, grader, calibration fixture, or
grading harness. The builder reads it read-only and writes only its declared scope.

## The instrument

- The **checklist** is one item per stamped scenario of the approved selection. It is
  derived, never written; an unstamped scenario or an empty selection stops the cycle.
- The **grader** is written once from the selected Gherkin and the public calibration
  protocol. Its author cannot read any candidate, transcript, prior verdict, or host
  credential. It is calibrated so carried behavior passes and uncarried behavior fails,
  sealed by digest, and used only after an operator approves that exact digest. It is reused
  until its scenarios change and replaced only for a recorded reason: instrument behavior
  changed, wording replaced, or spec grew (additions only). Never replace a grader to turn a
  failing score green.
- The **score** is honest: zero verdicts mean no score was filed, and an unreached item
  fails and stays in the denominator.
- The **scoreboard** compares shared items, newly passing and newly failing separately,
  counts new items as the untuned holdout, and never computes a net.

## Ratification

The builder cannot declare completion. The gate and the score drive the real public seam
from fresh state, preserve the approved denominator, and report failed, missing, unreached,
or inconclusive items explicitly. Score evidence never becomes builder input; a failed score
is measured, not retried within the cycle.
