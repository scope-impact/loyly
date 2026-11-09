# Cycle runner: the public seam

How a gate, a grader and an operator drive this context's candidate (slice 06). Derived from
`event-model/*.edn`; where this note and the event model disagree, the event model wins and this
note is wrong. Drafted 2026-11-07 (`../decisions.md`, "Slice 06 (draft)"), in the shape of
`../regeneration-cycle/seam.md`; decided in spec round 7b (`../decisions.md`, "Spec round 7b").

## What the candidate does, and what it is told

The runner is the one context that acts on the real world. For one cycle it reads the project
repository at the cut, builds and probes the clean room, launches a builder and waits for it,
installs the candidate in a gate room and runs the gate on the host, and files the verdicts it is
handed. After each effect it records the result into the context that owns it, through that
context's seam, exactly as the context's `seam.md` says an issuer does ("ORC runtime issues the
command", `../decisions.md`, "Slice 04" 4): it plays ORC runtime for the Regeneration cycle and
Measurement, and the operator for Specification and Cycle preparation. Then it relays: it takes the
producer's new handoffs from the producer's own store and hands each in, unchanged, to every
consumer (`../decisions.md`, "Spec round 5" 1 and 2; `harness/v2/assemble.clj`).

It owns no rule of the five contexts. A refusal it shows is the context's reply, as heard; the run
ends as its cycle ends in the Regeneration cycle. It never grades: the verdicts are handed in.

## Transport

- `start.sh` starts the candidate reading JSON-RPC 2.0 requests on stdin and writing responses on
  stdout: one JSON object per line, one response per request, carrying the request's `id`. Nothing
  else goes to stdout; diagnostics go to stderr.
- A request is `{"jsonrpc": "2.0", "id": <id>, "method": <method>, "params": {...}}`.
- A success is `{"jsonrpc": "2.0", "id": <id>, "result": {...}}`.
- A failure is `{"jsonrpc": "2.0", "id": <id>, "error": {"code": <int>, "message": <text>, "data": {...}}}`:
  `-32700` a line that is not JSON, `-32601` an unknown method, `-32602` params that do not match
  the schema, `-32000` a refusal (below).

## Methods

One method per command the operator issues and per query, named exactly as the block, without the
leading colon. Params are the block's `:schema`, field names verbatim (kebab-case). Values that are
instants travel as ISO-8601 UTC text.

| Method | Block | Result |
|---|---|---|
| `cycle-runner/start-run` | command `:cycle-runner/start-run` | `{"events": [<cycle-runner/run-started>]}`, once the run awaits its verdicts, concluded or halted |
| `cycle-runner/hand-in-verdicts` | command `:cycle-runner/hand-in-verdicts` | `{"events": [<cycle-runner/verdicts-handed-in>]}`, once the run concluded or halted |
| `cycle-runner/run-progress` | query `:cycle-runner/run-progress` (`run-id`, optional) | with `run-id`: `{"run": ...}`, or `null` when no such run exists; without: `{"runs": [...]}`, in the order started |
| `cycle-runner/runs-to-advance` | query `:cycle-runner/runs-to-advance` (no params) | `{"run-ids": [...]}`; `[]` after every method (below) |

- **The runner's own commands are not methods.** `record-effect`, `relay-handoff`, `conclude-run`
  and `halt-run` are issued only by the processor `advance-run`, after it performed the effect; a
  request naming one is `-32601`. This differs from the five contexts' seams on purpose: there a
  grader plays the issuer; here the runner is the issuer, and a grader observes what it did.
- **A method runs the run before it responds.** `start-run` records `run-started`, then the
  processor performs every effect up to the gate's verdict and responds when the run awaits its
  verdicts, concluded (a failed self-check, a refused fence, nothing regenerated, gate attempts
  exhausted, three gate refusals, a stage timeout) or halted. `hand-in-verdicts` likewise responds
  when the run concluded or halted. So `runs-to-advance` is `[]` after every method. The methods
  are synchronous by decision (`../decisions.md`, "Spec round 7b" 5): a live builder takes hours,
  and whoever watches a live run (the main session) reads the runner's Grain store and the pi
  session directly, not through this seam.
- **A run's progress** is the read model `:cycle-runner/runs` of that run, its fields verbatim
  (`run-id`, `cycle-id`, `cut-id`, `commit`, `status`, `effects`, `attempts`, `candidate-digest`,
  `verdicts-digest`, `cycle`, `halt`), plus `effect-results`: the rows of
  `:cycle-runner/effect-results` of that run in the order performed, and `relayed-handoffs`: the
  rows of `:cycle-runner/relayed-handoffs` of that run in the order relayed. `status` is `running`,
  `awaiting-verdicts`, `grading`, `passed`, `failed` or `halted`; `cycle` is the Regeneration
  cycle's `cycle-progress` of the run's cycle as read at the conclusion, verbatim (its `stage`,
  `status`, `attempts`, `failure`); `halt` is the `effect`, `reason` and `detail` of `run-halted`.
- **Reading changes nothing.** A query records no event, performs no effect and runs no ORC
  workflow.

## The effects

Each effect is one node of the processor `advance-run`. It performs the effect, then records the
result through one method of one context and records `effect-recorded` with the context's reply:
`outcome` `recorded` with `events` the events the method replied, verbatim (each with its `type`), or `refused` with `refusal` the
context's `data.refusal` as heard. `attempt` is the gate attempt the effect serves, 0 before the
first. In order:

| Triage | Effect | What the runner does | Recorded through |
|---|---|---|---|
| E1 | `stamp` | per selected slice of the plan at the cut, in slice order ("The plan and its slices", below): the spec file read with `git show`; a copy with the `@item-NN-SSS` tags of its tag lines removed (a tag line left empty goes; comments and other tags stay) written to the run's directory under a folder of the slice's own name, so Specification reads the same slice; that copy's unstamped scenarios and empty-title headings read and offered. After the recording, the identities the stamp handed out (its `scenario-stamped` events' `item-id`s, in file order) must equal the original file's own item tags, in file order; else the run halts, `refused-before-the-cycle` (below) | `specification/stamp-slice` |
| E1 | `pin` | the commit's tree digest read with `git rev-parse <commit>^{tree}` | `cycle-preparation/pin-source-cut` |
| E1 | `checklist` | the stamped scenarios of the cut's selected slices read with legacy `gen_checklist`'s reading (item identity from the ledger, scenario digest below, Rule, `source:line`, title; planned and backlog slices skipped), each slice's spec file read as the stamp reads it and its source the slice's own path, `PLAN_DIR/<slice>/spec.feature`, as legacy `gen_checklist` reads it on disk | `measurement/derive-checklist` |
| E2 | `self-check` | `LOYLY_SELF_CHECK_CMD` run in the project repository: exit 0 passed, any other failed; the last line it printed is the failing check; each line starting `SKIP` a skipped check, the check named by the rest of the line after `SKIP` and its whitespace, trimmed (legacy `check.sh` prints `SKIP  the oracle cannot judge this tree: …`; `../decisions.md`, "Spec round 7d") | `regeneration-cycle/record-harness-self-check` (before the fence: attempt 0, the run's budget; before the gate: the attempt) |
| E3 | `fence` | legacy `fence.sh` as-is, its one argument the clean room's path, which must not exist yet: a `file://` clone of depth 1 at the commit, `FENCED_PATHS` split on whitespace, each entry found (present in the clone's tree) or listed as matching nothing, removed, committed, squashed to one root commit, origin removed, reflog expired and pruned; then probed: root commits (exactly 1), commits (exactly 1), paths left in the tree, paths reachable in the object graph (a path of `git rev-list --objects --all` equal to the entry, its trailing `/` dropped, or beneath it) | `regeneration-cycle/prove-clean-room` |
| E3, E4 | `fail-cycle` | after a refused fence (`fence-refused`, the refusal as detail), a builder that left nothing (`candidate-not-regenerated`) or outlived the regenerate timeout (`budget`) | `regeneration-cycle/fail-cycle` |
| E4 | `regenerate` | a fresh builder room (the clean room, an empty candidate scope `APP_DIR`), `LOYLY_BUILDER_CMD <builder-room> <instruction>` launched and waited for within the regenerate timeout, with a third argument, the builder image's path, when the configuration names one (`BUILDER_IMAGE`, "Handed in"); the candidate digest below; the detail names the builder image and its digest, when there is one (`../decisions.md`, "Spec round 9" D1) | `regeneration-cycle/record-regenerated-candidate` (attempt 1, `fresh-generation`, no evidence) |
| E5 | `install` | a copy of the candidate in a fresh gate room under the run's directory, taken when the builder ended; the gate room is the gate's and out of the builder's reach, and writable by the candidate's own `test.sh` and `start.sh`, as legacy `verify.sh` runs the candidate in a writable tree; the candidate digest recorded is the digest of the copy as taken, before the gate runs, so what the candidate's own run writes does not change it (`../decisions.md`, "Spec round 7d"). For the `stdio` seam the gate room is a copy of the clean room's files (not its history) with the candidate's copy at `APP_DIR`, so the candidate reads the spec beside it in the gate room as it did in the builder room; the candidate digest is still the candidate scope's alone (`../decisions.md`, "Spec round 8") | `regeneration-cycle/install-candidate-in-gate-room` |
| E5 | `self-check` | as E2, before the gate, once per attempt (a gate again after a refusal does not self-check again) | `regeneration-cycle/record-harness-self-check` (`before-gate`) |
| E5 | `gate` (`http`) | for the `http` seam (`APP_SEAM` absent or `http`): legacy `verify.sh`'s order and arguments, on the host, in the gate room (its working directory), with `URL` the text `http://127.0.0.1:` followed by `PORT` (`http://127.0.0.1:8901` for the benchmark): `APP_DIR/test.sh` and `APP_DIR/start.sh` executable (else `contract-missing`); the identity probe `APP_PROBE` of the cut present and executable; nothing answering on `PORT` (else the seam answered before boot); `APP_DIR/test.sh` run with no argument, exit 0 (else `suite-red`); `APP_DIR/start.sh` run in the background with one argument, `PORT`, its output discarded, and `URL` answering an HTTP GET with a status below 400 (`curl -sf`) within 20 polls at half a second (10 seconds; else `boot-budget-exhausted`); the page at `URL` carrying `APP_MARKER`, ignoring case (`grep -qi`), and then the probe run as `APP_PROBE URL`, one argument, the URL and never the bare port, exiting 0 (else `identity-probe-failed`; the line it printed is its reason); no configuration value is put in the environment of `test.sh`, `start.sh` or the probe (legacy sources `config.sh`, it does not export it); its output is the gate evidence | `regeneration-cycle/record-gate-attempt` |
| E5 | `gate` (`stdio`) | for the `stdio` seam (`APP_SEAM=stdio`): `harness/v2/gate.sh`'s order and arguments, that is `harness/verify.sh` and then the event-store check, on the host, the gate room the working directory of the suite and the probe: 1. `APP_DIR/test.sh` and `APP_DIR/start.sh` executable (else `contract-missing`); 2. the identity probe `APP_PROBE` present and executable in the tree at the cut (git mode `100755`), taken from the cut into the run's directory outside the gate room, never from a room (else the gate refuses, `identity-probe-missing`); 3. the instrument checked: the self-check before the gate (the row above) is the `harness/check.sh --local` that `harness/verify.sh` runs here; 4. no port: the seam is the candidate's own stdin and stdout, on which nothing can respond before boot, so `seam-answered-before-boot` is false and `PORT` is not read; 5. `APP_DIR/test.sh` run with no argument, exit 0 (else `suite-red`); 6. the probe run as `APP_PROBE <gate-room>/APP_DIR/start.sh 30`, two arguments, the absolute path of the launch entrypoint in the gate room and the boot budget in seconds, with `GATE_STORE` in its environment naming the attempt's gate store, a path `events.sqlite` not yet existing in the attempt's gate evidence directory, outside the gate room; the probe launches `start.sh` itself, handing it that store as `LOYLY_GRAIN_STORE`, and exits 0 (else `identity-probe-failed`, the probe's output the detail); 7. the event-store check, `LOYLY_STORE_CHECK_CMD` run in the project repository as the self-check is, with one argument appended, the gate store's path, exiting 0 (else `identity-probe-failed`, the store check's output the detail: it reads only what the probe caused, so it is the rest of the identity check); 8. a pass records `boot-budget-seconds` 30, `boot-polls` 1 and `answered-on-poll` 1 (the probe holds the budget; its one run is the one poll) and the probe's output as `identity-probe-result`. The environment of `test.sh`, the probe and the store check is the runner's own with every variable whose name starts `LOYLY_` removed (the runner's own store is its `LOYLY_GRAIN_STORE`; `harness/v2/gate.sh` unsets it before the suite) and any `GATE_STORE` the runner itself inherited removed too (a runner gated inside `harness/v2/gate.sh` inherits that gate's store; `../decisions.md`, "Spec round 8b"), then the attempt's own `GATE_STORE` added for the probe only, and no configuration value put in. The probe's and the store check's output are the gate evidence. A store check that cannot be started (`LOYLY_STORE_CHECK_CMD` unset or empty, or its program absent or not executable) halts the run at the gate effect, `effect-could-not-run`, and nothing is recorded for the gate | `regeneration-cycle/record-gate-attempt` |
| E5 | `gate-refusal` | after `record-gate-attempt` was refused for infrastructure; once the refusal is recorded the runner gates the same attempt again. The attempt's self-check stands: the runner does not self-check again, as Regeneration cycle records one self-check per attempt and refuses a second (`self-check-already-recorded`; `../decisions.md`, "Spec round 8b") | `regeneration-cycle/record-gate-refusal` |
| E6 | `repair` | after a failed attempt with the cycle still running: a fresh builder room with the clean room, the previous candidate and this attempt's gate evidence, read-only, and the repair instruction; the builder launched as for E4, in the same builder image, its detail naming it and its digest | `regeneration-cycle/record-regenerated-candidate` (`verification-repair`, the gate evidence root mounted) |
| E7 | `file-verdicts` | the handed-in verdicts file read, each `id` filed as `item-id` with its verdict, evidence and reason, for the gated candidate, at the run's approved grader digests | `measurement/file-item-verdicts` |
| E7 | `file-score` | | `measurement/file-score` |
| E7 | `fail-score-stage` | after a refused `file-item-verdicts` or `file-score`, the refusal as heard | `measurement/fail-score-stage` |

- **The relay (E8)** runs after every recording, as `harness/v2/assemble.clj` does: the producer's
  handoff events (its `event-model/handoffs.edn`) not yet relayed are read from its store file,
  within its tenant, in store order, and each is handed in through the consumer's hand-in method
  (its `type` the method, its other fields the params) to every consumer whose event model consumes
  it; `handoff-relayed` records the consumer's reply. Only handoffs appended during this run are
  relayed, and a handoff's position counts from the first of them: a measurement history store
  handed in (`LOYLY_MEASUREMENT_STORE`) holds earlier runs' handoffs, which are never relayed
  again (`../decisions.md`, "Spec round 9" D6). A handoff a policy issued (Measurement's
  `scoreboard-reported`) travels like any other.
- **The conclusion.** After every recording and relay the runner reads
  `regeneration-cycle/cycle-progress` of its cycle; once `status` is `passed` or `failed` it records
  `conclude-run` with that progress, and the run ends: its contexts are stopped. A failed cycle's
  stage, reason and detail are the Regeneration cycle's (the run's `cycle.failure`).
- **A halt.** A recording refused before the cycle opened (the stamp, the pin or the checklist:
  no self-check has named the cycle yet, so there is no cycle to fail) halts the run, reason
  `refused-before-the-cycle` (AGENTS.md: an unstamped scenario or an empty selection stops the
  cycle). So does a stamp whose identities are not the plan's own (E1): the stamp effect is
  recorded as Specification answered, and the run halts at it, its `detail` naming the plan's
  identities and the stamp's. An effect that could not be performed at all halts it too, recording nothing for that
  effect: a context that did not respond (`context-did-not-respond`) or a handed-in command that
  could not be started (`effect-could-not-run`). The contexts are stopped; a halt is never the
  cycle's conclusion.
- **Repair and exhaustion are the cycle's.** After a failed attempt the runner repairs only while
  the cycle is still running; the Regeneration cycle's policy decides exhaustion.
- **Digests the runner computes.** A scenario digest is `"sha256:"` and the lowercase hexadecimal
  SHA-256 of the scenario's lines from its `Scenario:` heading to the line before the next heading,
  tag line or end of file, trailing blank lines dropped, joined by LF. A candidate digest is
  `"sha256:"` and the SHA-256 of one line per file of the candidate scope, `<path>` TAB `<sha256 hex
  of its bytes>` LF, by path. The goldens digest is `../regeneration-cycle/seam.md`'s formula over
  `grader-lifecycle/instrument-goldens`. The identity-probe digest is the SHA-256 of `APP_PROBE` at
  the cut; the harness digest is the SHA-256 of the configuration file, the self-check command
  and the identity probe, in that order (`../decisions.md`, "Spec round 7b" 4), and for the
  `stdio` seam of those three and then the store-check command, `LOYLY_STORE_CHECK_CMD` as
  handed in, so a store check changed between the self-check and the gate is a different harness
  (`../decisions.md`, "Spec round 8"). The builder image digest is the candidate digest's
  formula over the image's folder, each path relative to that folder (`../decisions.md`, "Spec
  round 9" D1). The scenario
  digest above is the runner's own reading, decided there too; Specification's `ScenarioReading`
  names none.

### The plan and its slices

The plan is `PLAN_DIR` in the tree at the cut. A slice is an entry of `PLAN_DIR` whose name starts
with its number, `NN-…`, read in slice order. A slice that is a symbolic link in that tree (git
mode `120000`, such as spec v2's `.pi/plan/05-cycle-preparation -> ../spec/cycle-preparation`) is
read at its target, resolved from `PLAN_DIR` within the same tree, with `git show
<commit>:<target>/spec.feature`; it keeps the link's name, so its number is the link's and its
stamped copy goes under a folder of the link's name. `PLAN_SLICES`, when the configuration
names it, selects the slices by number; absent or empty, every slice is selected, as before.
Only the selected slices are stamped and checklisted. A selection that names no slice of the
plan stamps nothing and leaves the checklist empty, which Measurement refuses (`empty-checklist`):
the run halts before the cycle (`../decisions.md`, "Spec round 8").

## Handed in

Read when `start-run` runs, never only at boot, so a grader changes them between runs.

- `LOYLY_GRAIN_STORE`: the runner's own store, as every context's `seam.md` says.
- `LOYLY_RUNS_DIR`: where each run's directory goes: the five contexts' stores, the clean room, the
  builder rooms, the gate rooms and the gate evidence. Never inside the project repository.
- `LOYLY_PROJECT_REPO`: the project repository (git) that holds the cut's commit. `is_commit` is
  `git cat-file -e <commit>^{commit}`.
- `LOYLY_HARNESS_CONFIG`: the project's harness configuration, legacy `config.sh` as-is
  (`KEY=value` lines: `APP_DIR`, `PORT`, `PLAN_DIR`, `APP_MARKER`, `APP_PROBE`, `FENCED_PATHS`),
  paths relative to the repository at the cut, and two optional keys (`../decisions.md`, "Spec
  round 8"): `APP_SEAM`, the gate's contract, `http` or `stdio` (absent, `http`; for `stdio`,
  `PORT` and `APP_MARKER` are not read), and `PLAN_SLICES`, the selected slice numbers separated
  by whitespace (absent or empty, every slice); and one optional key (`../decisions.md`, "Spec
  round 9" D1): `BUILDER_IMAGE`, the builder image for the configuration's stack, the name of a
  folder of `LOYLY_BUILDER_IMAGES` (absent or empty, none). The harness's own configuration,
  `harness/config.sh`, computes a context's values in shell when `SLICE` is set, which the
  runner never runs: a cycle of one of its contexts is handed that file's values for the slice,
  written out as `KEY=value` lines. The Löyly benchmark's is its own: the archived
  Löyly configuration (`archive/legacy-harness/config.sh`: `APP_DIR=impl`, `PORT=8901`,
  `PLAN_DIR=docs/plan`, `APP_MARKER=sauna`, `APP_PROBE=harness/probe.sh`) with
  `FENCED_PATHS="impl .pi archive/legacy-harness/cycles"` (`../decisions.md`, "Spec round 7b" 3
  and "The switch to the new harness" 2). The runner reads the file it is handed; it holds no benchmark value itself.
- `LOYLY_CONTEXTS`: a JSON file naming each context's candidate by its launch entrypoint:
  `{"specification": ".../harness-impl/01-specification/start.sh", ...}`, the five gated candidates
  of slices 01 to 05. The runner starts each for the run with a fresh `LOYLY_GRAIN_STORE` in the
  run's directory (but Measurement on `LOYLY_MEASUREMENT_STORE` when it is set) and the fixtures it wrote there (`LOYLY_PROJECT_COMMITS`: the commit and its tree;
  `LOYLY_SCENARIO_DIGESTS`: the cut's scenario digests; `LOYLY_SCORING_TOOL_PATHS` passed through),
  and stops them when the run ends. A context responds when it replies to a query of its own seam
  (such as `cycle-preparation/source-cuts`) with a JSON-RPC response carrying the request's `id`.
  A context not named, or not responding, refuses the start (`contexts-not-responding`), and
  `data.contexts` names every such context. A context that stops responding later halts the run
  (`context-did-not-respond`).
- `LOYLY_SELF_CHECK_CMD`: the harness self-check, legacy `harness/check.sh --local`.
- `LOYLY_STORE_CHECK_CMD`: for the `stdio` seam, the gate's event-store check, run in the
  project repository with the gate store's path appended; exit 0 passed, any other failed, its
  output the evidence. For the harness's own contexts, `harness/v2/event-store-check.sh NN`.
  Not read for the `http` seam.
- `LOYLY_BUILDER_CMD`: the builder launcher, run as `<cmd> <builder-room> <instruction-file>` and
  waited for, with a third argument, `<builder-image>`, the path `LOYLY_BUILDER_IMAGES/BUILDER_IMAGE`,
  when the configuration names a builder image. Live: pi with its Gondolin backend, the image its
  guest (`GONDOLIN_GUEST_DIR`), one builder per run at a time. Its exit status is
  not evidence: the candidate is what it left in `<builder-room>/<APP_DIR>`.
- `LOYLY_BUILDER_IMAGES`: the directory holding the builder images, one folder per image, outside
  the project repository (live: `.pi/harness/.state/assets`, built by `harness/Makefile`). Read
  only when the configuration names a builder image; a named image that is not a folder there
  refuses the start (`builder-image-missing`). The image named for a stack carries that stack's
  toolchain, the same tools the gate's host runs the candidate with (for Clojure: a JDK, the
  Clojure CLI, git and bb): the provided image build proves it, not the runner
  (`../decisions.md`, "Spec round 9" D2, D4).
- `LOYLY_MEASUREMENT_STORE`: optional, Measurement's history store: when set, Measurement is
  started on that file for every run instead of a fresh one, and it is kept when the run ends, so
  a scoreboard compares a cycle with the last measured cycle of the same selection across runs.
  Never inside the project repository. Absent, Measurement starts fresh and every scoreboard is a
  baseline (`../decisions.md`, "Spec round 9" D6).
- `LOYLY_BUILDER_INSTRUCTION`: the fresh-generation instruction (the rendered builder instruction). The repair
  instruction is that text and a closing paragraph naming the previous candidate and the gate
  evidence, read-only, in the builder room.
- `LOYLY_SCORING_TOOL_PATHS`: passed through to the Regeneration cycle (its `seam.md`).

### How a grader drives it without a live pi

Everything the runner acts on is a fixture a grader states, so no item waits for a model:

- a fixture git repository (`LOYLY_PROJECT_REPO`) whose commit holds a plan with stamped
  scenarios, the configuration file, a self-check script and an identity probe;
- `LOYLY_SELF_CHECK_CMD` a script that passes, fails naming a check, or prints `SKIP` lines;
- `LOYLY_BUILDER_CMD` a stub builder that copies a fixture candidate into the candidate scope (a
  candidate whose `test.sh` and `start.sh` serve a page with the marker on `PORT`, or one with a red
  suite, a slow boot, no marker, no `start.sh`), leaves it empty, sleeps past the timeout, or records
  the paths it was handed;
- a `PORT` the grader chooses, and holds itself to see the seam respond before boot;
- for the `stdio` seam, a configuration with `APP_SEAM=stdio`, fixture candidates whose `test.sh`
  and `start.sh` record their arguments and environment, a cut whose identity probe is a stub
  that records its arguments and environment and exits 0 or 1 with a line, and a
  `LOYLY_STORE_CHECK_CMD` stub that records its arguments and exits 0 or 1 with a line, so no
  item needs a Grain store (the store check's own rules are `harness/v2/event-store-check-test.sh`'s);
- a plan whose slice is a symbolic link in the commit's tree, and a configuration naming
  `PLAN_SLICES`;
- a configuration naming `BUILDER_IMAGE` and a `LOYLY_BUILDER_IMAGES` directory that holds a
  folder of that name (any files) or does not;
- a `LOYLY_MEASUREMENT_STORE` file the grader names, kept across two runs;
- the five gated candidates of slices 01 to 05 as `LOYLY_CONTEXTS` (the relay reads their Grain
  stores), or an entrypoint that never responds, exits at once, or wraps a gated candidate and
  exits when asked one method, to see a refusal or a halt;
- a verdicts file in legacy `score.clj`'s shape (a list of `{"id", "verdict", "evidence"}`, an
  optional `"reason"`), handed in by `hand-in-verdicts`.

The grader observes through `run-progress` only: the effect results with the contexts' replies, the
relayed handoffs, the cycle's progress at the conclusion, and the run's status. The one exception
is a measurement history store, the grader's own file: after the run ended, the grader may start
the gated Measurement candidate on it and read `measurement/scoreboard` through Measurement's own
seam. What the stub
builder and the fixture candidates record (the paths a builder room held, each launch, whether a
launch entrypoint ran) is the grader's own fixture, read from where the grader told it to write.

The verdicts a grader of the runner hands in are its own fixtures too. In the live benchmark they
are shared black-box verdicts: one legacy-step-5 grading subagent per candidate writes one
verdicts file, which goes unchanged to this runner and to legacy `score.clj` in a shadow run; the
main session judges `@item-01-011` of the Löyly app itself and hands that verdict in with the
others, and is the final judge of every shadow run's comparison (`../decisions.md`, "Spec round
7b" 6). The grading subagent drives the gated candidate in a room built as the install effect
builds the passed attempt's gate room, from fresh state: for the `stdio` seam the clean room's
files with the gated candidate's copy at `APP_DIR`, never a copy of the candidate alone, which
cannot read the spec beside it (`../decisions.md`, "Spec round 9" D5).

### How the candidate's own suite drives it without the five candidates

The builder's clean room holds no other context's candidate. It holds each of the five contexts'
contract, `.pi/spec/<context>/seam.md` and `.pi/spec/<context>/event-model/*.edn` (the methods,
params, results, refusal codes and handoffs the runner speaks), and nothing else of them. So the
candidate's own tests stand the five contexts in themselves:

- each **stand-in** is a program the test names in its own `LOYLY_CONTEXTS` file: it speaks its
  context's seam (JSON-RPC 2.0 over stdio, the methods, results and refusal codes its `seam.md`
  names) and appends the handoff events it produces, with exactly the fields of its
  `event-model/handoffs.edn`, to a Grain store (`event-store-v3`, SQLite) at the
  `LOYLY_GRAIN_STORE` the runner hands it, so the relay reads a stand-in's store as it reads a real
  producer's;
- a stand-in replies as its test scripts it (record and reply with events, refuse with a code,
  stop answering, exit) and keeps what it was handed, so the suite checks the order of the
  effects, the relay (what was handed in, to whom, in which order, once), a refusal shown as heard,
  a halt, and a conclusion read from the stand-in Regeneration cycle's `cycle-progress`;
- the project repository, the self-check, the builder and the verdicts file are fixtures, as
  above.

The stand-ins are the suite's, never the product's: the product holds no rule of the five
contexts and reaches them only through `LOYLY_CONTEXTS`. The black-box grader of this slice uses
the real gated candidates of slices 01 to 05 instead.

## Refusals

A refusal records nothing and is the error `-32000` with `data.refusal`, one of the codes below.
The numbers are the `requires` of the rule in `allium/cycle-run.allium`, checked in that order.

| Method | Allium rule | `data.refusal` by requires |
|---|---|---|
| `cycle-runner/start-run` | `StartRun` | 1 `run-already-started`, 2 `run-in-progress`, 3 `cycle-already-run`, 4 `not-a-commit`, 5 `contexts-not-responding` (`data.contexts`), 6 `builder-image-missing` |
| `cycle-runner/hand-in-verdicts` | `HandInVerdicts` | 1 `no-run`, 2 `gate-not-passed`, 3 `verdicts-already-handed-in`, 4 `run-not-awaiting-verdicts`, 5 `grader-not-approved`, 6 `verdicts-not-readable` |

"Records nothing" means no event of `event-model/` is appended and no effect is performed. A
context's refusal is not the runner's: it is recorded as an effect result (`outcome` `refused`).

## The event store

As every context's `seam.md`: Grain's `event-store-v3`, SQLite, at `LOYLY_GRAIN_STORE`; the runner's
store holds only its own events (it consumes no handoff); ORC shares this candidate's file and
tenant. Each method runs as the ORC workflow of its flow, and the processor's commands as the
workflow of a flow holding them:

| Command, by its issuer | ORC workflow |
|---|---|
| `start-run`; `record-effect` and `relay-handoff` before the verdicts | `cycle-runner/run-to-gate` |
| `hand-in-verdicts`; `record-effect`, `relay-handoff` and `conclude-run` after the verdicts | `cycle-runner/verdicts-to-conclusion` |
| `conclude-run` of a failed cycle | `cycle-runner/cycle-failed-ends-run` |
| `halt-run` | `cycle-runner/effect-not-performed-halts-run` |

## Not in this slice

The gate in a Gondolin room (stage 1 gates on the host); a sealed Löyly grader (OG1 = no; still
open after the switch, `../decisions.md`, "The switch to the new harness" 7); one shared store (A1, deferred in Spec round 5); the room policy check
(`TheGateRoomIsNotTheBuilders`, planned `room-policy`).
