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

This is the flow the repo exists to demonstrate. The new harness runs it: the cycle runner,
`harness-impl/06-cycle-runner/`, drives the five contexts of `harness-impl/01-*` to `05-*`
(`harness-impl/README.md`). It has done so since 2026-11-08 (`.pi/spec/decisions.md`, "The switch
to the new harness"). On 2026-11-10 it built the Löyly app end to end: Löyly run-03, the gate
passed on attempt 1, blind grading 11/11, the run passed in 17 minutes
([replay](https://scope-impact.github.io/loyly/replay/loyly-run-03.html)).

The runner is one process that speaks JSON-RPC 2.0 over stdio (`.pi/spec/cycle-runner/seam.md`,
"Transport"). A person, or the session acting for one, starts it, calls its methods, grades, and
hands the verdicts in. The legacy harness (`archive/legacy-harness/`) did the same stages with
separate scripts; it stays as the reference and is never deleted (step 7 below).

### 0. Before a run

The runner reads everything from its environment when a run starts
(`.pi/spec/cycle-runner/seam.md`, "Handed in"):

| Variable | What it is |
|---|---|
| `LOYLY_GRAIN_STORE` | the runner's own Grain store (SQLite) |
| `LOYLY_RUNS_DIR` | where each run's directory goes: the contexts' stores, the clean room, the builder and gate rooms, the gate evidence. Never inside the project repository |
| `LOYLY_PROJECT_REPO` | the project repository (git) that holds the cut's commit |
| `LOYLY_HARNESS_CONFIG` | the harness configuration, legacy `config.sh` as-is. For Löyly, `archive/legacy-harness/config.sh` with `FENCED_PATHS="impl .pi archive/legacy-harness/cycles"` |
| `LOYLY_CONTEXTS` | a JSON file naming each context's `start.sh`: `harness-impl/01-specification/start.sh` and the other four |
| `LOYLY_SELF_CHECK_CMD` | the harness self-check, legacy `harness/check.sh --local` of the project repository |
| `LOYLY_BUILDER_CMD` | the builder, run as `<cmd> <builder-room> <instruction-file>`: `.pi/harness/bin/builder.sh`, a pi-durable session whose tools run in a Gondolin VM, on the existing Codex login (`.pi/harness/README.md`) |
| `LOYLY_BUILDER_INSTRUCTION` | the instruction the fresh builder gets |
| `LOYLY_SCORING_TOOL_PATHS` | passed through to the Regeneration cycle (`[]` for Löyly) |

Optional: `LOYLY_MEASUREMENT_STORE`, Measurement's history store, kept across runs so a scoreboard
compares with the last run; without it every scoreboard is a baseline. `LOYLY_BUILDER_IMAGES` and
`LOYLY_STORE_CHECK_CMD` only matter for a configuration that names `BUILDER_IMAGE` or the `stdio`
seam; Löyly's names neither.

For Löyly the project repository is a clone of this repository with one commit on top whose
`harness/` is the archived legacy harness with that configuration (`.pi/spec/decisions.md`, "The
switch to the new harness" 2). The run-03 driver in `.pi/harness/.state/loyly/run-03/` is the
worked example: `driver/driver.py` sets these variables and owns the runner's stdin and stdout,
`driver/send.sh` sends one request, `contexts.json`, `benchmark-config.sh` and `bin/*.sh` are its
inputs. It is run state, not committed. Open: there is no committed launcher for a Löyly run yet.

### 1. Build from the docs

Read the slice (`docs/plan/01-booking-core/`): eleven Gherkin scenarios covering the
weekly schedule, standing-slot booking with reasoned refusals, the single-source-of-truth
rule from the story above, two households racing for one slot, and whether a refusal is
usable. In the loop the builder is a fresh `pi` session in a fenced clean room. It builds
into `impl/`, in any stack, test-first (the harness doc's RED/GREEN discipline,
`docs/wow/harness.md`). You can also build it by hand, to learn the slice. The only hard
requirement is the gate's contract: `impl/test.sh` and `impl/start.sh PORT` (`APP_DIR=impl`,
`PORT=8901` in `archive/legacy-harness/config.sh`).

A run starts with one call:

```text
$ harness-impl/06-cycle-runner/start.sh    # with the environment above; then, one line on its stdin:
{"jsonrpc":"2.0","id":1,"method":"cycle-runner/start-run","params":{"run-id":"…","cycle-id":"…","cut-id":"…","commit":"<40-hex>","selection-id":"…","budget":{"gate-attempt-limit":3,"max-outer-cycles":1,"max-continuations":0,"stage-timeouts-seconds":{"fence":1800,"regenerate":14400,"gate":3600,"score":28800}},"grader-digests":["…"]}}
```

`start-run` is synchronous (`.pi/spec/cycle-runner/seam.md`, "Methods"). It stamps the slices,
pins the cut, derives the checklist, runs the self-check, fences the clean room, launches the
builder, installs the candidate in a gate room and gates it, and answers when the run awaits its
verdicts, or concluded, or halted. For Löyly that took about 11 minutes. A run cannot resume after
a restart, so keep the runner alive from this call to the filed score.

### 2. Ratify

"Done" is a claim, and claims are ratified by a gate the claimant does not control. The runner's
gate (`.pi/spec/cycle-runner/seam.md`, E5, `http`) is legacy `verify.sh`'s, in a gate room out of
the builder's reach: `impl/test.sh` exits 0, `impl/start.sh 8901` boots, `http://127.0.0.1:8901`
answers within 10 seconds with a page carrying `sauna`, and the identity probe
(`archive/legacy-harness/probe.sh`) passes against that URL. A failed gate starts a fresh builder
with the previous candidate and the gate's evidence, at most three attempts. If nothing was built,
the gate says so and fails, which is correct behaviour for a gate.

### 3. Measure

The checklist is derived from the spec inside the run (never hand-kept), one item per stamped
scenario. A black-box grading agent drives the gated candidate in a copy of the gate room, from
fresh state, and writes a verdicts file: a list of `{"id", "verdict", "evidence"}`, with an
optional `"reason"` (`.pi/spec/cycle-runner/seam.md`, the verdicts file; `harness/README.md`,
"Scoring a spec-v2 cycle (legacy step 5)"). Then, on the same runner:

```text
{"jsonrpc":"2.0","id":2,"method":"cycle-runner/hand-in-verdicts","params":{"run-id":"…","verdicts-path":"/abs/verdicts.json","grader-digest":"…"}}
{"jsonrpc":"2.0","id":3,"method":"cycle-runner/run-progress","params":{"run-id":"…"}}
```

`grader-digest` must be one of the run's `grader-digests`. The runner files the verdicts and the
score in Measurement, and `run-progress` shows every effect's result, the score and the run's
status. The instrument's honesty rules hold: zero verdicts means no score is filed, and an item
the grader never reached is filed as a fail, so the denominator cannot quietly shrink.

### 4. Regenerate

This is the heart of the method, and every run does it: the fence makes a clean-room clone of the
cut in which `impl/`, `.pi` and all measurements are deleted from the working tree **and from git
history**, then proves it (one root commit, object-graph probes; seam E3). The builder sees only
that room. To regenerate, start another run on the same cut with a new `run-id` and `cycle-id`:
a fresh builder builds the slice again from the docs alone.

### 5. Study the delta

The scoreboard compares the run with the previous one on the items both scored, prints
got-better and got-worse counts rather than a net, and says out loud when the denominator moved
(it compares across runs only with `LOYLY_MEASUREMENT_STORE` kept). Every difference between two
builds is information: a rule the docs failed to carry, a decision the spec left open, an
invention that crept in. **The delta measures the spec, not the builder.**

To watch a run, build its replay page from the run directory and publish it:

```bash
tools/spec/replay/build.sh <run-dir> tools/spec/replay/<name>.html   # tools/spec/replay/README.md
tools/spec/publish.sh                                                 # gh-pages, as replay/<name>.html
```

### 6. Fold the learning back

Whatever the delta taught you goes into `raw/` (if it is new source knowledge),
`docs/wiki/` (if it is domain), or the slice (if it is scope), as a recorded decision.
Never only into the code: the next regeneration deletes the code, and anything living
only there dies with it. That destructive step is not a cost of the method, it is the
mechanism: it forces learning into the documents, because nothing else survives.

Then cut, regenerate, and measure again. On a production project this loop runs weekly.

### 7. The legacy harness, for comparison

The legacy harness ran the same stages as separate commands: `verify.sh` (the gate),
`gen_checklist.clj`, `score.clj` and `scoreboard.clj` (the instrument), and `fence.sh` (the
clean room), in `archive/legacy-harness/` (its `README.md`). It is the reference: the switch was
made only after two shadow runs agreed with it at every stage (`.pi/spec/decisions.md`, "The
switch to the new harness"), and it is never deleted. In this repo `harness/` holds the same
tools adapted to measure the harness itself (`harness/README.md`).

## The harness

The harness has its own spec: spec v2, under `.pi/spec/`, six bounded contexts (specification,
cycle-preparation, grader-lifecycle, measurement, regeneration-cycle, cycle-runner), 187 stamped
items, each context a slice through its link `.pi/plan/NN-<context>`; `.pi/model/` (spec v1) is
history. Every decision is in `.pi/spec/decisions.md`. Each context was built by a regeneration
cycle into `harness-impl/NN-<context>/`, Clojure on ORC with Grain, and each folder's
`PROVENANCE.md` names its cycle. The harness builds Löyly and is not asked to rebuild itself
(`.pi/spec/decisions.md`, "Self-hosting stopped"). Until a sealed grader exists, a run is graded
the legacy way, by a black-box grading agent's verdicts (step 3).

To look at the spec, open the event-model page: every context's Allium, Event Model,
scenarios and coverage, and the handoffs between contexts, on one static page published to
the `gh-pages` branch of the `scope-impact` remote by `tools/spec/publish.sh`. To rebuild the
page and check the spec:

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
