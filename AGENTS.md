# Löyly Agent Instructions

The maintained assets are the behavioral specifications under `.pi/spec/` (spec v2, one folder
per bounded context, 187 stamped items; `.pi/model/` is spec v1, history, kept for its
glossary); generated
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
| The harness implementation | Clojure on ORC, with Grain for the event store; each context registers its room's event model as given | `harness-impl/NN-<context>/`, one per context, generated from `.pi/spec` by the regeneration cycle |
| The builder and Gondolin rooms | `pi` with its Gondolin backend, on the existing Codex login, launched by the cycle runner (`LOYLY_BUILDER_CMD`, `.pi/spec/cycle-runner/seam.md`). Provided, like ORC and Grain: never regenerated. The durable host on `@earendil-works/pi-durable` (TypeScript) is not built | `.pi/harness/`, `harness/Makefile`, `harness/<project-id>/` |
| The graders | Elixir scripts (`.exs`), driving the candidate only over its JSON-RPC seam, sealed, calibrated and approved: the product rule (OG1 = no). Not built for spec v2 yet; until then a v2 cycle is graded by legacy step 5, a black-box grading agent's verdicts filed by `harness/v2/score.sh --verdicts` (`harness/README.md`) | sealed under `.pi/harness/.state/graders/` |
| The legacy driver | Babashka and shell, the legacy harness tools: the reference since the switch, never deleted | `harness/`, `archive/legacy-harness/` |

Graders use a functional language other than the product's Clojure, so they share no code or
runtime with what they measure. The Elixir graders that scored the first spec-v2 cycles are
retired (cb8f5c7); the three Python graders in `harness/graders.approved.json` were written for
spec v1 and cover no spec-v2 item.

## The regeneration cycle

ORC runs one cycle, in the shape of the legacy harness (`archive/legacy-harness/`). Since
2026-11-08 the new harness runs the regeneration cycles: the slice-06 cycle runner driving the
five contexts of slices 01-05. The legacy harness (`harness/`, `archive/legacy-harness/`) is
the reference and is never deleted. Until a run can resume after a restart, keep the runner
alive from start to the filed score. The switch rule, the five shadow runs and what stays open:
`.pi/spec/decisions.md`, "The switch to the new harness".

A spec-v2 cycle rebuilds **one context** from scratch, into `harness-impl/NN-<context>/`
("First light"). The assembly is a relay: each context keeps its own Grain store, and a
producer's handoffs reach a consumer only through the consumer's hand-in methods, one per
consumed event; one shared store is deferred ("Spec round 5").

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

## How the harness improves itself

The running harness is `harness-impl/`, the promoted build (`harness-impl/README.md`). It
improves only through a spec round on `.pi/spec`, then a regeneration cycle of the affected
context: a fenced clean room, a fresh builder, the gate, grading, the score. Never edit
`harness-impl/` by hand; each context's `PROVENANCE.md` names the cycle that produced it.

- **An accepted build is the running build.** When a cycle's candidate passes the gate, its
  score is filed, and the final judge (the operating session) accepts it, the cycle's last
  step writes it into `harness-impl/NN-<context>/` with its `PROVENANCE.md`. There is no
  separate promotion; the next cycle runs on it.
- **The instrument judging itself is the one exception.** When the rebuilt context is part of
  the instrument (the cycle runner's gate, measurement, scoring), compare the new build once
  against the previous instrument (the previous promoted build or the legacy harness) on the
  same candidates before it is used. The fence keeps the builder honest, not the judge: a gate
  bug would pass every later build, its own successor included. Shadow runs 1 and 2 are the
  precedent (a probe handed the port, not the URL; a read-only gate room).
- **Clear contradictions before new items.** Before a spec round accepts new items for a
  context, it clears that context's inbox items that are contradictions: two spec statements
  that cannot both hold, within or across contexts. Readings and gaps may wait. Every cycle is
  a fresh builder, so an open contradiction is decided again each cycle and becomes a
  regression (runner cycle 04, `@item-06-018`; `.pi/spec/decisions.md`, "Spec round 8b").
- **The builder never sees `harness-impl/`**: `harness/config.sh` fences it from every clean
  room.
- **Self-hosting is not done yet**: the runner's gate speaks only the HTTP app contract, so the
  harness's own stdio contexts are still gated by `harness/v2/gate.sh` until the runner gains
  that contract (`.pi/spec/decisions.md`, "The switch to the new harness", Self-hosting).
