# Löyly

> **Löyly** (noun): the steam or vapour that rises when water is thrown onto the hot
> stones of a sauna stove. By extension it also refers to the heat and humidity of the
> sauna itself, which is why you can talk about good or bad löyly.

Löyly is a sauna-slot booking system for Nordic housing cooperatives. But the app is not
the point. This repo is a small, complete, working demonstration of a way of building
software in which **documentation is the source of truth and code is a build product**:
the implementation is deliberately disposable, and deleting and regenerating it is not a
catastrophe but a measurement. What this repo actually ships is a specification you can
build from and an instrument that tells you how good the build was.

Published at <https://scope-impact.github.io/loyly/>: the
[spec](https://scope-impact.github.io/loyly/), a
[run replay](https://scope-impact.github.io/loyly/replay/loyly-run-03.html) and the
[eval page](https://scope-impact.github.io/loyly/eval/loyly.html).

Everything in it is real. The domain documents are redacted transcriptions of a real
Finnish housing cooperative's 2022 email threads plus verified public sources from
Finland, Sweden and Norway. Nothing is invented scaffolding, and that is a rule with an
enforcement point, not a preference (see `docs/wow/invariants.md`, INV-1).

## The story this repo is built around

In 2022, a resident of a Finnish housing cooperative booked a standing sauna slot,
Tuesdays at 17:00. The booking became three things maintained by hand: a line on the
posted slot list, a programme on the stove timer, and a charge on the monthly invoice.
Five months later two of the three had been changed and one had not, and the resident
stood in a cold sauna at a time they still believed was theirs.

Nobody malfunctioned. The three representations were never one thing, so they drifted.
The incident is in this repo verbatim
(`raw/domain/soukanvuori/2022-work-order-103016-and-drift.md`), and it is the whole
argument in one image: **when the same fact lives in several hand-kept copies, the copies
diverge, and the failure is silent until someone is standing in the cold.** That is as
true of documentation and code as it is of slot lists and stove timers. This method is
what taking that seriously looks like.

Two rules fall straight out of the story:

- **One record, derived views.** In the app: the booking record is the single source of
  truth, and the list, the timer schedule and the billing signal are readings of it,
  never copies. In the repo: the docs are the single source of truth, and the code is a
  reading of them.
- **One product, many houses.** Every documented cooperative runs the sauna differently
  (different slot lengths, different caps, different bookers, different billing). The
  model must express each as configuration; the first documented house whose rules
  cannot be expressed is a design bug, not an exception to hard-code.

## How to read the repo

Read in this order. Each layer is derived from the one above it, and nothing may drift
from what precedes it.

| order | path | what it is |
|---|---|---|
| 1 | `raw/` | the sources: real booking mail, work orders, public house rules, one interview. Immutable: never edited, only cited |
| 2 | `docs/wiki/` | the domain, distilled from the sources. Every claim footnotes the source it came from. Start at `index.md` |
| 3 | `docs/wow/` | the method itself: the way of working, five invariants each with a named enforcement point, and the agent harness |
| 4 | `docs/plan/01-booking-core/` | one slice of work: a plan, an executable Gherkin spec, tickets, and recorded decisions |
| 5 | `harness-impl/` | the harness that runs the loop: the cycle runner and the five contexts it drives (the fence, the gate, the derived checklist, the score, the scoreboard). Start at its `README.md`. The legacy harness it replaced, `archive/legacy-harness/`, is the reference |
| 6 | `impl/` | the build product. Intentionally empty: you generate it |

The harness is itself specified and was built the same way; see "The harness" below.

Two conventions you will notice everywhere:

- **Every document cites another document.** A wiki page cites a raw source, a plan cites
  wiki pages, a ticket cites spec scenarios, a decision cites the plan it diverged from.
  A claim that rests on nothing is not finished.
- **The docs never name a stack.** No language, framework or database appears anywhere
  under `docs/`. You could build Löyly as a web app, a mobile app, or a phone-in voice
  service without changing a line of them.

## The tutorial: one full loop

This is the flow the repo exists to demonstrate. Since 2026-11-08 the new harness runs it: the
cycle runner, `harness-impl/06-cycle-runner/`, driving `harness-impl/01-*` to `05-*`. On
2026-11-10 it built Löyly end to end: run-03, gate passed on attempt 1, blind grading 11/11, 17
minutes ([replay](https://scope-impact.github.io/loyly/replay/loyly-run-03.html)).

### 0. Before a run

The runner is one JSON-RPC 2.0 process over stdio. It reads its `LOYLY_*` variables (stores, runs
directory, project repository, harness config, contexts, self-check, builder) from the environment;
`.pi/spec/cycle-runner/seam.md`, "Handed in", lists them. The launcher `harness/loyly-run` (bb;
`--help`) sets them as Löyly run-03 did, derives the cut from a base commit, and keeps the runner
alive in a detached daemon:

```bash
harness/loyly-run start --run-id loyly-run-04 --commit <base>   # blocks until awaiting verdicts
harness/loyly-run send cycle-runner/run-progress '{"run-id":"loyly-run-04"}'
harness/loyly-run send cycle-runner/hand-in-verdicts '{"run-id":"loyly-run-04","verdicts-path":"/abs/verdicts.json","grader-digest":"loyly-run-04-grading-subagent"}'
harness/loyly-run stop
```

Run state goes to `.pi/harness/.state/loyly/<run-id>/` (not committed).

### 1. Build from the docs

Read the slice (`docs/plan/01-booking-core/`): eleven Gherkin scenarios. In the loop a fresh `pi`
builder in a fenced clean room builds it into `impl/`, in any stack, test-first
(`docs/wow/harness.md`); you can also build it by hand. The gate's contract: `impl/test.sh` and
`impl/start.sh PORT`. A run starts with one call to `harness-impl/06-cycle-runner/start.sh`:

```text
{"jsonrpc":"2.0","id":1,"method":"cycle-runner/start-run","params":{"run-id":"…","cycle-id":"…","cut-id":"…","commit":"<40-hex>","selection-id":"…","budget":{…},"grader-digests":["…"]}}
```

It is synchronous (about 17 minutes for Löyly run-03), and a run cannot resume after a restart: keep the
runner alive until the score is filed.

### 2. Ratify

"Done" is a claim, ratified by a gate the claimant does not control: in a gate room out of the
builder's reach, the suite passes, the app boots and answers, and the identity probe passes. A
failed gate starts a fresh builder, at most three attempts.

### 3. Measure

The checklist is derived from the spec, one item per stamped scenario. A black-box grading agent
drives the gated candidate and writes a verdicts file; `cycle-runner/hand-in-verdicts` files it
and `cycle-runner/run-progress` shows the score. Zero verdicts means no score; an unreached item
is a fail, so the denominator cannot shrink.

### 4. Regenerate

The heart of the method, in every run: the fence deletes `impl/` and all measurements from the
working tree **and from git history**, and proves it. Start another run on the same cut and a
fresh builder builds the slice again from the docs alone.

### 5. Study the delta

The scoreboard compares runs on shared items, got-better and got-worse, never a net. Every
difference is a rule the docs failed to carry, a decision left open, or an invention.
**The delta measures the spec, not the builder.** A run's replay page:
`tools/spec/replay/build.sh`, published by `tools/spec/publish.sh`.

### 6. Fold the learning back

Whatever the delta taught you goes into `raw/` (if it is new source knowledge),
`docs/wiki/` (if it is domain), or the slice (if it is scope), as a recorded decision.
Never only into the code: the next regeneration deletes the code, and anything living
only there dies with it. That destructive step is not a cost of the method, it is the
mechanism: it forces learning into the documents, because nothing else survives.

Then cut, regenerate, and measure again. On a production project this loop runs weekly.

The legacy harness (`archive/legacy-harness/`: `verify.sh`, `gen_checklist.clj`, `score.clj`,
`scoreboard.clj`, `fence.sh`) ran the same stages as scripts; it is the reference, never deleted.

## The harness

The harness has its own spec: spec v2, under `.pi/spec/`, six bounded contexts (specification,
cycle-preparation, grader-lifecycle, measurement, regeneration-cycle, cycle-runner), 187 stamped
items, each context a slice through its link `.pi/plan/NN-<context>`; `.pi/model/` (spec v1) is
history. Every decision is in `.pi/spec/decisions.md`. Each context was built by a regeneration
cycle into `harness-impl/NN-<context>/`, Clojure on ORC with Grain; its `PROVENANCE.md` names the
cycle. The harness builds Löyly and is not asked to rebuild itself.

To look at the spec, open the event-model page, published at
<https://scope-impact.github.io/loyly/> (Architecture at
[#c4](https://scope-impact.github.io/loyly/#c4)): every context's Allium, Event Model, scenarios
and coverage, and the handoffs between contexts. `tools/spec/publish.sh` publishes it to the
`gh-pages` branch. To rebuild the page and check the spec:

```bash
tools/spec/views/build.sh   # writes tools/spec/views/dist/event-model.html
tools/spec/v2/gates.sh      # Allium, Grain, coverage and the lints: ALL GATES PASS
```

## What to steal

If you take nothing else from this repo, take these five, each of which exists because
its absence produced a specific silent lie (the stories are in `harness/README.md`):

1. The checklist is **derived** from the spec, never written by hand.
2. A "done" claim is **ratified** by a script the claimant does not control.
3. The fence is **physical and proved**, not an honour system.
4. The instrument **refuses to record a measurement it did not make**, and an unreached
   item is a fail, never a smaller denominator.
5. Learning that is not written into the spec **dies with the implementation**, on
   purpose.

Good löyly.
