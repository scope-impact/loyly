# Cycle runner: the public seam

How a gate, a grader and an operator drive this context's candidate (slice 06). Derived from
`event-model/*.edn`; where this note and the event model disagree, the event model wins and this
note is wrong. Drafted 2026-11-07 (`../decisions.md`, "Slice 06 (draft)"), decided in spec round
7b, and rewritten in spec round 18 as the one tree `cycle-runner/cycle.v1` on one store
(`../decisions.md`, "Spec round 18"). The five contexts 01 to 05 are built only here, in this
slice's cycle, never on their own.

## What the candidate does, and what it is told

The runner is the one context that acts on the real world. It is one candidate with the five
contexts 01 to 05, on one Grain store: their rules, event models and queries are in it, and the
one event model is the union of every context's `event-model/*.edn`. For one cycle it runs one
tick of the ORC tree `cycle-runner/cycle.v1`: it reads the project repository at the cut, builds
and probes the clean room, runs a builder session and waits for it, installs the candidate in a
gate room and runs the gate, grades each checklist item with the sealed grader, and files the
score. After each effect a leaf records the result into the context that owns it, by that
context's command on the one store, exactly as the context's `seam.md` says its issuer does ("ORC
runtime issues the command", `../decisions.md`, "Slice 04" 4). There is no relay, hand-in method
or handoff contract: each event is appended once, by its producer's command, and every context
that folds it reads it from the one store.

It owns no rule of the five contexts. A refusal it shows is the context's reply, as heard; the run
ends as its cycle ends in the Regeneration cycle. The runner itself never judges an item: the
judge does, in the sealed grader `cycle-runner/grade.v1`.

## Transport

- `start.sh` starts the candidate reading JSON-RPC 2.0 requests on stdin and writing responses on
  stdout: one JSON object per line, one response per request, carrying the request's `id`. Nothing
  else goes to stdout; diagnostics go to stderr.
- A request is `{"jsonrpc": "2.0", "id": <id>, "method": <method>, "params": {...}}`.
- A success is `{"jsonrpc": "2.0", "id": <id>, "result": {...}}`.
- A failure is `{"jsonrpc": "2.0", "id": <id>, "error": {"code": <int>, "message": <text>, "data": {...}}}`:
  `-32700` a line that is not JSON, `-32601` an unknown method, `-32602` params that do not match
  the schema, `-32000` a refusal (below).
- The candidate replies to each request when it is done, by its `id`, and reads the next request
  meanwhile: a `run-progress`, or a second `start-run`, sent while a run runs is answered without
  waiting for that run.
- The five contexts' methods (each context's `seam.md`) are methods of this same candidate, on the
  same store.

## Methods

One method per command the operator issues and per query, named exactly as the block, without the
leading colon. Params are the block's `:schema`, field names verbatim (kebab-case). Values that are
instants travel as ISO-8601 UTC text.

| Method | Block | Result |
|---|---|---|
| `cycle-runner/start-run` | command `:cycle-runner/start-run` | `{"events": [<cycle-runner/run-started>]}`, once the run concluded or halted |
| `cycle-runner/run-progress` | query `:cycle-runner/run-progress` (`run-id`, optional) | with `run-id`: `{"run": ...}`, or `null` when no such run exists; without: `{"runs": [...]}`, in the order started |

- **The runner's own commands are not methods.** `record-effect`, `conclude-run` and `halt-run`
  are issued only by the leaves of the tree ("The tree", below), each after it performed its
  effect; a request naming one is `-32601`. The runner is the issuer; a grader observes what it
  did.
- **A start runs the run before it responds.** `start-run` runs one tick of `cycle.v1` and
  responds when the tick ended: the run concluded with its cycle (passed, or failed at any stage)
  or halted. The method is synchronous by decision (`../decisions.md`, "Spec round 7b" 5).
- **A run's progress** is the read model `:cycle-runner/runs` of that run, its fields verbatim
  (`run-id`, `cycle-id`, `cut-id`, `commit`, `status`, `effects`, `attempts`, `candidate-digest`,
  `cycle`, `halt`), plus `effect-results`: the rows of `:cycle-runner/effect-results` of that run in
  the order performed; `tick`: the run's tick of `cycle.v1` as ORC's own tick and node events in
  the one store show it, its `tick-id`, its `status`, and `nodes`, each node of the tick and of the
  child ticks it delegated, in the order completed, by its name, with the node running now last
  and marked running; and `scores`: each item's score as ORC's judge score events of the run show
  it (`item-id`, `verdict`, `reason`). `status` is `running`, `passed`, `failed` or `halted`;
  `cycle` is the Regeneration cycle's `cycle-progress` of the run's cycle as read at the
  conclusion, verbatim (its `stage`, `status`, `attempts`, `failure`), and absent for a run that
  did not conclude: a halted run has no `cycle`, and the runner records no `fail-cycle` for it, so
  it leaves its cycle as it was (`../decisions.md`, "Spec round 16b" D3); `halt` is the `effect`,
  `reason` and `detail` of `run-halted`.
- **Reading changes nothing.** A query records no event, performs no effect and runs no ORC
  workflow.

## The effects

Each effect is a leaf of the tree ("The tree", below). It performs the effect, then records the
result by one command of one context and records `effect-recorded` with the context's reply:
`outcome` `recorded` with `events` the events the command produced, verbatim (each with its
`type`), or `refused` with `refusal` the context's refusal code as heard. `method` is the command,
named as the context's seam names its method. `attempt` is the gate attempt the effect serves, 0
before the first. In order:

| Triage | Effect | What the runner does | Recorded through |
|---|---|---|---|
| E1 | `stamp` | per selected slice of the plan at the cut, in slice order ("The plan and its slices", below): the spec file read with `git show`; a copy with the `@item-NN-SSS` tags of its tag lines removed (a tag line left empty goes; comments and other tags stay) written to the run's directory under a folder of the slice's own name, so Specification reads the same slice; that copy's unstamped scenarios and empty-title headings read and offered. After the recording, the identities the stamp handed out (its `scenario-stamped` events' `item-id`s, in file order) must equal the original file's own item tags, in file order; else the run halts, `refused-before-the-cycle` (below). Each slice's stamp is its own: a slice is stamped whatever an earlier slice's stamp came to, and the run halts at the first, in slice order, that halts it ("The tree"). A slice whose ledger the one store already holds (an earlier run on the same store stamped it) is not restamped: the stamp offers the original spec file's own unstamped scenarios, none for a stamped plan, so Specification records nothing new, and the identities compared with the file's own item tags are the slice's in `specification/stamped-items` (`../decisions.md`, "Spec round 18") | `specification/stamp-slice` |
| E1 | `pin` | the commit's tree digest read with `git rev-parse <commit>^{tree}` | `cycle-preparation/pin-source-cut` |
| E1 | `checklist` | the stamped scenarios of the cut's selected slices read with legacy `gen_checklist`'s reading (item identity from the ledger, scenario digest below, Rule, `source:line`, title; planned and backlog slices skipped), each slice's spec file read as the stamp reads it and its source the slice's own path, `PLAN_DIR/<slice>/spec.feature`, as legacy `gen_checklist` reads it on disk | `measurement/derive-checklist` |
| E2 | `self-check` | `LOYLY_SELF_CHECK_CMD` run in the project repository: exit 0 passed, any other failed; the last line it printed is the failing check; each line whose first word, after any leading whitespace, is `SKIP` a skipped check, the check named by the rest of the line after `SKIP` and its whitespace, trimmed (`../decisions.md`, "Spec round 13" D2) (legacy `check.sh` prints `SKIP  the oracle cannot judge this tree: …`; `../decisions.md`, "Spec round 7d") | `regeneration-cycle/record-harness-self-check` (before the fence: attempt 0, the run's budget; before the gate: the attempt) |
| E3 | `fence` | legacy `fence.sh` as-is, its one argument the clean room's path, which must not exist yet: a `file://` clone of depth 1 at the commit, `FENCED_PATHS` split on whitespace, each entry found (present in the clone's tree) or listed as matching nothing, removed, committed, squashed to one root commit, origin removed, reflog expired and pruned; then probed: root commits (exactly 1), commits (exactly 1), paths left in the tree, paths reachable in the object graph (a path of `git rev-list --objects --all` equal to the entry, its trailing `/` dropped, or beneath it) | `regeneration-cycle/prove-clean-room` |
| E3, E4 | `fail-cycle` | after a refused fence (`fence-refused`, the refusal as detail), a builder that left nothing (`candidate-not-regenerated`) or outlived the regenerate timeout (`budget`), or a gate command that outlived the gate stage's timeout (`budget`, stage `gate`; `../decisions.md`, "Spec round 12" D2), or any other program a leaf runs that outlived what was left of its stage's timeout (`budget`, that stage; "The tree", every wait has a bound) | `regeneration-cycle/fail-cycle` |
| E4 | `regenerate` | a fresh builder room (the clean room, an empty candidate scope `APP_DIR`; the configuration it holds is the configuration file the commit holds, at its path in the clean room unless fenced, and the runner copies no file handed in, `LOYLY_HARNESS_CONFIG` among them, into it; `../decisions.md`, "Spec round 20"), the builder instruction the render node wrote put into an instruction file in the run's directory, and one builder session run by the provided builder host, `LOYLY_BUILDER_HOST <builder-room> <instruction-file>`, with a third argument, the builder image's path, when the configuration names one (`BUILDER_IMAGE`, "Handed in"), and `LOYLY_BUILDER_SESSION_ID` in its environment, the session identity ("The builder session", below); waited for within the regenerate timeout; the candidate digest below; the detail names the session identity, the instruction's digest, and the builder image and its digest, when there is one (`../decisions.md`, "Spec round 9" D1) | `regeneration-cycle/record-regenerated-candidate` (attempt 1, `fresh-generation`, no evidence) |
| E5 | `install` | a copy of the candidate in a fresh gate room under the run's directory, taken when the builder ended; the gate room is the gate's and out of the builder's reach, and writable by the candidate's own `test.sh` and `start.sh`, as legacy `verify.sh` runs the candidate in a writable tree; the candidate digest recorded is the digest of the copy as taken, before the gate runs, so what the candidate's own run writes does not change it (`../decisions.md`, "Spec round 7d"). For either seam the gate room is a copy of the gated tree, as legacy `verify.sh` gates the tree it sits in: the clean room's files (not its history) plus the candidate's copy at `APP_DIR`, so the candidate reads the spec beside it in the gate room as it did in the builder room, and the gate room is the gate's `ROOT` (`verify.sh` line 23, `ROOT` the tree's root); the candidate digest is still the candidate scope's alone (`../decisions.md`, "Spec round 8" and "Spec round 11"). When a gate command is handed in (`LOYLY_GATE_CMD`, "Handed in"), the gate room is instead the cut's tree: every file of the commit, with no history (`git archive` of the commit), the fenced paths included and nothing at `APP_DIR`; and the candidate's copy, taken as above, is a folder of its own beside the gate room, never inside it. The gate is the judge and out of the builder's reach, so it may hold what the fence hid from the builder, such as an identity probe and the files it reads; the command mounts the candidate at `APP_DIR` itself. The candidate digest is the copy's as taken, as above (`../decisions.md`, "Spec round 12" D2) | `regeneration-cycle/install-candidate-in-gate-room` |
| E5 | `self-check` | as E2, before the gate, once per attempt (a gate again after a refusal does not self-check again) | `regeneration-cycle/record-harness-self-check` (`before-gate`) |
| E5 | `gate` (`http`) | for the `http` seam (`APP_SEAM` absent or `http`): legacy `verify.sh`'s order and arguments, on the host, in the gate room (its working directory), with `ROOT` the gate room (`verify.sh` line 23) and `URL` the text `http://127.0.0.1:` followed by `PORT` (`http://127.0.0.1:8901` for the benchmark): `APP_DIR/test.sh` and `APP_DIR/start.sh` executable (else `contract-missing`); the identity probe `$ROOT/$APP_PROBE` present and executable, `-x "$ROOT/$APP_PROBE"` (`verify.sh` line 36), the gate room's copy, which is the cut's (the fence removes only `FENCED_PATHS`); nothing answering on `PORT` (else the seam answered before boot); `APP_DIR/test.sh` run with no argument, exit 0 (else `suite-red`); `APP_DIR/start.sh` run in the background with one argument, `PORT`, its output discarded, and `URL` answering an HTTP GET with a status below 400 (`curl -sf`) within 20 polls at half a second (10 seconds; else `boot-budget-exhausted`), polled in `verify.sh`'s order (lines 117 to 121), a poll first and then the wait: poll 1 at once after `start.sh` is launched, with no wait before it, and each later poll half a second after the previous one, so poll n at (n − 1) × 0.5 seconds after the launch and poll 20 at 9.5 seconds; a pass records `boot-budget-seconds` 10, `boot-polls` 20 and `answered-on-poll` the number of the first poll that answered (`../decisions.md`, "Spec round 15"); the page at `URL` carrying `APP_MARKER`, ignoring case (`grep -qi`), and then the probe run as `"$ROOT/$APP_PROBE" "$URL"` (`verify.sh` line 154), with no change of directory and nothing else added, one argument, the URL and never the bare port, exiting 0 (else `identity-probe-failed`; the line it printed is its reason); no configuration value is put in the environment of `test.sh`, `start.sh` or the probe (legacy sources `config.sh`, it does not export it); its output is the gate evidence | `regeneration-cycle/record-gate-attempt` |
| E5 | `gate` (`stdio`) | for the `stdio` seam (`APP_SEAM=stdio`): `harness/v2/gate.sh`'s order and arguments, that is `harness/verify.sh` and then the event-store check, on the host, the gate room the working directory of the suite and the probe: 1. `APP_DIR/test.sh` and `APP_DIR/start.sh` executable (else `contract-missing`); 2. the identity probe `APP_PROBE` present and executable in the tree at the cut (git mode `100755`), taken from the cut into the run's directory outside the gate room, never from a room (else the gate refuses, `identity-probe-missing`); 3. the instrument checked: the self-check before the gate (the row above) is the `harness/check.sh --local` that `harness/verify.sh` runs here; 4. no port: the seam is the candidate's own stdin and stdout, on which nothing can respond before boot, so `seam-answered-before-boot` is false and `PORT` is not read; 5. `APP_DIR/test.sh` run with no argument, exit 0 (else `suite-red`); 6. the probe run as `APP_PROBE <gate-room>/APP_DIR/start.sh 30`, two arguments, the absolute path of the launch entrypoint in the gate room and the boot budget in seconds, with `GATE_STORE` in its environment naming the attempt's gate store, a path `events.sqlite` not yet existing in the attempt's gate evidence directory, outside the gate room; the probe launches `start.sh` itself, handing it that store as `LOYLY_GRAIN_STORE`, and exits 0 (else `identity-probe-failed`, the probe's output the detail); 7. the event-store check, `LOYLY_STORE_CHECK_CMD` run in the project repository as the self-check is, with one argument appended, the gate store's path, exiting 0 (else `identity-probe-failed`, the store check's output the detail: it reads only what the probe caused, so it is the rest of the identity check); 8. a pass records `boot-budget-seconds` 30, `boot-polls` 1 and `answered-on-poll` 1 (the probe holds the budget; its one run is the one poll) and the probe's output as `identity-probe-result`. The environment of `test.sh`, the probe and the store check is the runner's own with every variable whose name starts `LOYLY_` removed (the runner's own store is its `LOYLY_GRAIN_STORE`; `harness/v2/gate.sh` unsets it before the suite) and any `GATE_STORE` the runner itself inherited removed too (a runner gated inside `harness/v2/gate.sh` inherits that gate's store; `../decisions.md`, "Spec round 8b"), then the attempt's own `GATE_STORE` added for the probe only, and no configuration value put in. The probe's and the store check's output are the gate evidence. A store check that cannot be started (`LOYLY_STORE_CHECK_CMD` unset or empty, or its program absent or not executable) halts the run at the gate effect, `effect-could-not-run`, and nothing is recorded for the gate | `regeneration-cycle/record-gate-attempt` |
| E5 | `gate` (`command`) | when `LOYLY_GATE_CMD` is handed in, non-empty, for either seam, in place of the two rows above (`../decisions.md`, "Spec round 12" D2); the runner then reads none of `APP_SEAM`, `PORT` and `APP_MARKER` for the gate, nor `LOYLY_STORE_CHECK_CMD`, and starts no program of the candidate itself: 1. the identity probe `APP_PROBE` present and executable in the tree at the cut (git mode `100755`), else the gate refuses, `identity-probe-missing` ("A gate stopped by a refusal guard", below), and the command is not launched; 2. the command run as `LOYLY_GATE_CMD <gate-room> <candidate> <evidence-dir>`, in the project repository as the self-check is, with the runner's own environment, and waited for within what is left of the gate stage's timeout: `<gate-room>` the cut's tree and `<candidate>` the candidate's copy (the install row), `<evidence-dir>` the attempt's gate evidence directory, created empty in the run's directory outside both, which is the attempt's `evidence-root`; 3. its exit status and its standard output read as "The gate command's result" (below) says: exit 0 a pass, any other exit a failed attempt, a refusal to grade or a halt, by its last line; `identity-probe-present` true and `identity-probe-digest` the SHA-256 of `APP_PROBE` at the cut, as for the other seams; `seam-answered-before-boot` true only when its last line names it; 4. a command that outlives the gate stage's timeout is stopped, nothing is recorded for the gate, and the cycle is failed at the gate for `budget` (the `fail-cycle` row); a command whose program is absent or not executable halts the run at the gate effect, `effect-could-not-run`, and nothing is recorded for the gate. What the command does inside (a VM, a world, the candidate's own `test.sh` and `start.sh`, its readiness check, the probe) is the command's, provided and never regenerated, as the builder host is ("Handed in") | `regeneration-cycle/record-gate-attempt` |
| E5 | `gate-refusal` | after `record-gate-attempt` was refused for infrastructure, that is with one of the three codes `record-gate-refusal` takes (`seam-answered-before-boot`, `identity-probe-missing`, `identity-probe-not-the-instruments`), that code its `refusal` and the gate's output its `detail` ("A gate stopped by a refusal guard", below; `../decisions.md`, "Spec round 12" D3); the tree then gates the same attempt again. The attempt's self-check stands: the runner does not self-check again, as Regeneration cycle records one self-check per attempt and refuses a second (`self-check-already-recorded`; `../decisions.md`, "Spec round 8b") | `regeneration-cycle/record-gate-refusal` |
| E6 | `repair` | after a failed attempt with the cycle still running, as the last leaf of that attempt's subtree: a fresh builder room with the clean room, the previous candidate and this attempt's gate evidence, read-only, and the repair instruction (the rendered instruction and a closing paragraph naming the previous candidate and the gate evidence by their read-only paths in the builder room); the builder session run as for E4, in the same builder image, with a session identity of its own; its detail names the gate evidence directory it mounted, the failed attempt's `evidence-root` as its `gate-attempt-failed` carries it, then the session identity, and then the builder image and its digest, so the mount is read through `run-progress` (`../decisions.md`, "Spec round 16b" D2) | `regeneration-cycle/record-regenerated-candidate` (`verification-repair`, the gate evidence root mounted) |
| E7 | `file-verdicts` | after every item was graded ("Grading", below): the judge's scores offered as the verdicts of the gated candidate, each scored item's `item-id`, its `verdict` `pass` or `fail` and its `evidence` the judge's reason, in checklist order, an item with no score left out, at the sealed grader's digest and the run's approved grader digests | `measurement/file-item-verdicts` |
| E7 | `file-score` | | `measurement/file-score` |
| E7 | `fail-score-stage` | after a refused `file-item-verdicts` or `file-score`, the refusal as heard | `measurement/fail-score-stage` |

- **A gate stopped by a refusal guard** (`../decisions.md`, "Spec round 12" D3, carried from round
  10's draft D2). The gate's infrastructure guards (for `http` nothing answering on `PORT`, for
  every gate the identity probe present, for a gate command a last line naming one of them) stop
  the gate before it grades, so there is no outcome to offer. The runner still records the gate
  through `record-gate-attempt`, so the refusal is Regeneration cycle's, as heard: `outcome`
  `failed` with the `reason` of the step the guard stands before, `boot-budget-exhausted` when the
  seam answered before boot and `identity-probe-failed` when the identity probe is missing;
  `seam-answered-before-boot` and `identity-probe-present` as the gate found them;
  `boot-budget-seconds` and `boot-polls` the gate's budget (10 and 20 for `http`, 30 and 1 for
  `stdio`, the `BOOT` line's for a gate command, else 10 and 20), no `answered-on-poll` and no
  `identity-probe-result`; `detail` the guard's line. Every `requires` before the infrastructure
  guards then holds (`RecordGateAttempt` 1 to 7: installed, awaiting, a failed outcome with a
  reason, no poll, the installed candidate, the attempt's self-check), so Regeneration cycle
  refuses it with the guard's own code (8, 9 or 10) and records nothing: the offered reason never
  becomes a fact.
- **The gate command's result** (`../decisions.md`, "Spec round 12" D2). The runner reads the
  command's standard output as lines, each trimmed; its standard error it does not read (the
  command keeps both in the evidence directory). Two lines carry numbers: `BOOT <boot-polls>
  <boot-budget-seconds>`, the boot budget the command gave the candidate, two positive integers,
  printed before the boot whatever happens after it; and `ANSWERED <poll>`, the poll on which the
  candidate first answered, a positive integer not above `<boot-polls>`. The last line non-empty
  is the result:
  - *exit 0*, a pass: `outcome` `passed`, `boot-budget-seconds` and `boot-polls` the `BOOT` line's,
    `answered-on-poll` the `ANSWERED` line's, and the last line the `identity-probe-result` (the
    probe's own line, as legacy `verify.sh` prints it last). Exit 0 without a `BOOT` and an
    `ANSWERED` line that parse is not a result: the run halts at the gate effect,
    `effect-could-not-run`.
  - *any other exit*: the last line's first word is a code and the rest of the line, trimmed, its
    text, recorded as `detail`. A reason of `gate-attempt-failed` (`contract-missing`,
    `suite-discovery-empty`, `suite-red`, `boot-budget-exhausted`, `identity-probe-failed`) is a
    failed attempt with that reason, `boot-budget-seconds` and `boot-polls` the `BOOT` line's
    (else 10 and 20) and no `answered-on-poll`. `seam-answered-before-boot` or
    `identity-probe-missing` is a refusal to grade, recorded as "A gate stopped by a refusal
    guard" says (`identity-probe-not-the-instruments` is never the command's: the runner computes
    the probe's digest from the cut itself). Any other last line, an empty output among them, says
    the gate could not be performed (Halla's command prints `infrastructure` and a line when its
    world or its VM did not come up): the run halts at the gate effect, `effect-could-not-run`,
    `detail` the line, and nothing is recorded for the gate. It is never a failed attempt: the
    candidate is not to blame, and Regeneration cycle has no reason for it.
- **Digests the runner computes.** A scenario digest is `"sha256:"` and the lowercase hexadecimal
  SHA-256 of the scenario's lines from its `Scenario:` heading to the line before the next heading,
  tag line or end of file, trailing blank lines dropped, joined by LF. A candidate digest is
  `"sha256:"` and the SHA-256 of one line per file of the candidate scope, `<path>` TAB `<sha256 hex
  of its bytes>` LF, by path. The goldens digest is `../regeneration-cycle/seam.md`'s formula over
  `grader-lifecycle/instrument-goldens`. The identity-probe digest is the SHA-256 of `APP_PROBE` at
  the cut; the harness digest is the SHA-256 of the configuration file, the self-check command
  and the identity probe, in that order (`../decisions.md`, "Spec round 7b" 4); the self-check
  command is `LOYLY_SELF_CHECK_CMD` as handed in, its text, never the bytes of the program it
  names, as the store-check and gate commands below (`../decisions.md`, "Spec round 20"); and for the
  `stdio` seam of those three and then the store-check command, `LOYLY_STORE_CHECK_CMD` as
  handed in, so a store check changed between the self-check and the gate is a different harness
  (`../decisions.md`, "Spec round 8"). When a gate command is handed in, the harness digest is
  of the configuration file, the self-check command and the identity probe and then the gate
  command, `LOYLY_GATE_CMD` as handed in (the store-check command is then not read, so it is not
  covered); with none handed in, the digest is as before, so a run without one keeps the harness
  digest it has today (`../decisions.md`, "Spec round 12" D5). The grader digest is `"sha256:"` and the SHA-256 of the line `cycle-runner/grade.v1` LF followed by the bytes of the rubric file handed in (`LOYLY_GRADER_RUBRIC`), so a new judge version or a new rubric is a new grader. The instruction digest is the SHA-256 of the instruction file a builder was handed. The builder image digest is the candidate digest's
  formula over the image's folder, each path relative to that folder (`../decisions.md`, "Spec
  round 9" D1). The scenario
  digest above is the runner's own reading, decided there too; Specification's `ScenarioReading`
  names none.

### The builder session

The builder is pi's own coding loop as a pi-durable session, its tools in a Gondolin VM made from
the builder image, run by the provided builder host on the existing Codex login; the leaf that
runs it is a `code` leaf. Its session identity is
`<run-id>/<tick-id>/<attempt>`: the run, the run's tick of `cycle.v1` and the attempt the session
opens, never drawn at random. So the leaf that runs again in the same tick after a stopped runner
resumed the run ("Resume", below) runs the same session again, and the builder host reopens it
where it stopped; a new attempt is a new session. The builder host allows one writer per
session: before it resumes a session, the runner makes sure no builder host of the stopped run
is still alive.

The builder instruction is written by the `llm` node `render`, from the instruction version the
tree carries (seeded from `LOYLY_BUILDER_INSTRUCTION`) and the selected slices; GEPA improves that
instruction between cycles and publishes a winner as a new version, never during a run.
The repair instruction is the rendered text and the closing paragraph of the E6 row, nothing else.

### Grading

When an attempt passed, every checklist item is graded, one at a time, in checklist order: a
`map-each` over the checklist delegates each item to the sealed grader, the versioned judge
workflow `cycle-runner/grade.v1`, as a child tick of its own:

- **`clear-evidence`** (`code`) writes the item's evidence empty, so no item sees another's.
- **`drive-seam`** (`repl-researcher`) is given the item (its identity and scenario), and the
  seam it drives: the `seam.md` and `event-model/*.edn` beside the item's spec file in the cut,
  when there are any. Its one tool is a shell in a Gondolin VM whose only mount is the item's
  grading room: a fresh copy of the passed attempt's gate room, the clean room's files with the
  gated candidate's copy at `APP_DIR`, never a builder room, gate evidence or a store.
  It drives the candidate over its seam from fresh state and writes what it set up, sent and
  observed as the item's evidence ("The researcher's VM", below).
- **`judge`** (`code`) scores the item on that evidence on one binary scale, pass or fail, the
  reason first and then the score, with the rubric handed in (`LOYLY_GRADER_RUBRIC`). A reply with
  no reason, or a score that is not pass or fail, scores nothing. ORC's own banded-output check
  reads only the `level` field, so the judge checks the `reasoning` field itself: a reply with a
  `level` and no `reasoning`, or an empty one, scores nothing (`@item-06-067`). The score is recorded as ORC's
  judge score event, its judge name `checklist/<item-id>`, so each item keeps its own score.
- The researcher and the judge call the model at `LOYLY_MODEL_API_BASE` as `LOYLY_GRADER_MODEL`,
  another model than the one the builder session runs on.

**The researcher's VM** (`@item-06-066`; `../decisions.md`, "Spec round 20"). Each item has a VM
of its own, made from the guest image `LOYLY_GRADER_IMAGE` names, with the `gondolin` CLI found on
`PATH`. The runner starts it when the item's `drive-seam` begins, keeps it for every call of that
item's researcher, and stops it when the item's grading ends, scored or not, before the next
item's begins. One VM per item, not one per call: a researcher makes several calls, and what one
call starts in the background (the candidate, a stub) must still run in the next.
- The item's grading room is a folder of its own in the run's directory, made fresh for the item.
  It is mounted at its own host path and is the VM's only folder of the host: its siblings (the
  other items' grading rooms, the builder rooms, the gate rooms and evidence, the store) and every
  other folder of the host are absent in the VM.
- The VM is started as `gondolin bash --image <LOYLY_GRADER_IMAGE> --mount-hostfs <room>:<room>
  --allow-host egress-blocked.invalid -- sleep <seconds>`, in the background with an empty
  standard input, `<seconds>` the grading delegate's bound ("Every wait has a bound"), so a VM the
  runner lost ends by itself. Egress is blocked by allowing one host that cannot exist, because the
  CLI leaves egress open when no host is allowed (ADR 0002). Its session is the row of `gondolin
  list` whose PID is the CLI's.
- Each shell call is `gondolin exec --sock <session-id> -- sh -c 'cd <room> && <command>'`: the
  grading room is its working folder, and its exit status, standard output and standard error are
  the tool's reply.
- To stop it, the runner kills the CLI and every program the CLI started, its qemu among them:
  killing the CLI alone orphans its qemu (ADR 0002).
- A VM that cannot be started halts the run before any item is graded. After a passed gate, the
  leaf `grading-vm` starts one VM as above, with no mount, runs `true` in it and stops it, and
  writes `run` `gate-passed` again when it ran. No folder
  at `LOYLY_GRADER_IMAGE`, no `gondolin` on `PATH`, or a VM that does not run `true` is an effect
  that could not be performed: `halting`, effect `drive-seam`, `effect-could-not-run`, the detail
  naming what failed, and no verdict is filed. An item whose own VM then fails to start leaves its
  researcher failed, so the item has no score.

An item whose child tick did not end with a score (a researcher or a judge that failed, a reply
that scored nothing) has no score. The `grader` leaf, before the self-check, holds the run to the
sealed grader: its digest ("Digests the runner computes") must be one of the run's approved
grader digests, or the run halts before the cycle, `refused-before-the-cycle`, the detail naming
the digest; no clean room is built and no builder runs.

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

## The tree

The run is one ORC tree, built with ORC's DSL as the workflow `cycle-runner/cycle.v1`, and
`start-run` runs one tick of it (`../decisions.md`, "Spec round 14", "Spec round 18"). Each effect
and each command of the runner is a named leaf of its own (`code`), and the tree holds their order;
the leaves hold the domain rules ("The effects", above). The tree follows ORC's own principles
(ORC's `docs/ORC-PRINCIPLES.md`; `../decisions.md`, "Spec round 16"): the blackboard is the run's
state, each leaf is a function of its own, a subtree used more than once is one versioned workflow
delegated per use (a gate attempt, an item's grading), and every wait has a bound.

**A leaf always completes.** It performs its one effect or command, writes what came of it to the
blackboard and completes with success. A refusal, a failed attempt, a cycle that ended and an
effect that could not be performed are outcomes a leaf writes, never an ORC failure. The tree
branches on them with `condition` nodes, so a run that fails or halts still ends its tick with
success, through a branch of the root `fallback`. The two nodes that call the model, `render` and
an item's grading, can fail as ORC nodes: each stands under a `fallback` whose second branch writes
the outcome (`render-failed` writes `halting`; a failed item's grading leaves the item with no
score). What ORC does for every tree (ticks, traces, a leaf completed once, resume, retries, a
delegate's child tick and its own blackboard) is ORC's, and not restated here.

**The blackboard is the run's state.** Every value a leaf reads is a key of the blackboard or
comes from the world (the project repository, the run's directory, the one store, a program's
exit, the model's reply), and everything a leaf hands on is a key it writes: a leaf's `:reads` and
`:writes` are its whole contract with the tree, and its function computes from its inputs and the
world only. The runner keeps no run state beside the blackboard; only live handles (a builder
session, a gate's program, an item's grading VM) are kept outside it, by the runner, under the run's id.
The keys, as `blackboard` declares them (Malli; ORC takes no unconstrained schema, so a value with
no shape of its own travels as text):

| Key | Shape | What |
|---|---|---|
| `params` | `start-run`'s `:schema` (`event-model/cycle-run.edn`) | the start's params |
| `handed-in` | `[:map-of :string :string]` | each variable of "Handed in" by its name, as `start` read it; one not handed in is absent |
| `config` | `[:map-of :string :string]` | the configuration file's `KEY=value` lines |
| `run-dir` | `:string` | the run's directory |
| `slices` | `[:vector Slice]`, Slice `[:map [:slice :string] [:path :string]]` | the selected slices in slice order: the slice's name and its spec file's path in the cut, a link resolved |
| `slice` | Slice | the slice a `stamp` leaf stamps (`map-each`'s item) |
| `stamp` | Stamp, `[:map [:slice :string] [:run [:enum "running" "halting"]] [:halt {:optional true} Halt]]` | what one slice's stamp came to |
| `stamps` | `[:vector Stamp]` | the stamps', in slice order (`map-each`'s result) |
| `items` | `[:vector Item]`, Item `[:map [:item-id :string] [:slice :string] [:source :string] [:title :string]]` | the checklist's items, in checklist order, as `checklist-derived` gave them |
| `grader` | `[:map [:version :string] [:digest :string]]` | the sealed grader: `cycle-runner/grade.v1` and its digest |
| `builder-instruction` | `:string` | the instruction `render` wrote |
| `attempt` | `[:int {:min 0 :max 3}]` | the gate attempt: 0 before the first candidate, then the attempt the last builder opened |
| `builder-room` | `:string` | the last builder's room |
| `candidate` | `[:map [:path :string] [:digest :string]]` | the candidate's copy the install took, and its digest |
| `gate-room` | `:string` | the attempt's gate room |
| `evidence-root` | `:string` | the last gate's evidence directory |
| `gate-refusal` | `[:map [:refusal :string] [:detail :string]]` | a gate refused with one of the three codes `record-gate-refusal` takes |
| `item` | Item | the item a grading delegate grades (`map-each`'s item) |
| `evidence` | `:string` | what the item's researcher set up, sent and observed |
| `score` | Score, `[:map [:item-id :string] [:verdict [:enum "pass" "fail"]] [:reason :string]]` | the judge's score of the item |
| `scores` | `[:vector Score]` | every item's score, in checklist order, an item with no score left out (`map-each`'s result) |
| `cycle` | `:string` | the `cycle` of the last `regeneration-cycle/cycle-progress` reply, its JSON text verbatim |
| `run` | `[:enum "running" "refused" "gate-passed" "gate-refused" "failing" "scoring-failed" "cycle-passed" "cycle-failed" "halting"]` | the run as the last leaf left it (below) |
| `refusal` | `[:map [:refusal :string]]` | the method's own refusal ("Refusals") |
| `failure` | Failure, `[:map [:stage :string] [:reason :string] [:detail :string]]` | the cycle to be failed |
| `halt` | Halt, `[:map [:effect :string] [:reason :string] [:detail :string]]` | the run to be halted |

`run` is `running`; `refused` (the method refused, nothing recorded); `gate-passed` (a gate
attempt passed); `gate-refused` (a gate stopped by a refusal guard, recorded and refused as
heard); `failing` (the cycle is to be failed, `failure` its stage, reason and detail);
`scoring-failed` (Measurement refused the verdicts or the score, recorded as heard); `cycle-passed`
or `cycle-failed` (the Regeneration cycle's `cycle-progress` shows the cycle concluded);
`halting` (the run is to halt, `halt` its effect, reason and detail).

**Each leaf is a function of its own.** A `code` leaf names its own function (`:fn`), one per
effect or command; the leaves of one effect in different places of the tree (`self-check:0` and an
attempt's `self-check`) share their effect's function. No function serves several effects by the
node's name, and no leaf reads its own name: the attempt is the blackboard's `attempt`. Each
node's contract:

| Node | Reads | Writes |
|---|---|---|
| `start` | `params` | `handed-in`, `config`, `run-dir`, `slices`, `attempt`, `run`, `refusal` |
| `stamp` | `params`, `handed-in`, `config`, `run-dir`, `slice` | `stamp` |
| `stamped` | `stamps` | `run`, `halt` |
| `pin` | `params`, `handed-in` | `run`, `halt` |
| `checklist` | `params`, `handed-in`, `config`, `run-dir`, `slices` | `items`, `run`, `halt` |
| `grader` | `params`, `handed-in` | `grader`, `run`, `halt` |
| `self-check` | `params`, `handed-in`, `config`, `attempt` | `cycle`, `run`, `failure`, `halt` |
| `fence` | `params`, `handed-in`, `config`, `run-dir` | `run`, `failure`, `halt` |
| `render` (`llm`) | `config`, `slices` | `builder-instruction` |
| `render-failed` | `params` | `run`, `halt` |
| `regenerate` | `params`, `handed-in`, `config`, `run-dir`, `builder-instruction` | `builder-room`, `attempt`, `run`, `failure`, `halt` |
| `install` | `params`, `handed-in`, `config`, `run-dir`, `attempt`, `builder-room` | `candidate`, `gate-room`, `run`, `halt` |
| `gate` | `params`, `handed-in`, `config`, `run-dir`, `attempt`, `candidate`, `gate-room` | `evidence-root`, `gate-refusal`, `cycle`, `run`, `failure`, `halt` |
| `gate-refusal` | `params`, `attempt`, `gate-refusal` | `cycle`, `run`, `halt` |
| `repair` | `params`, `handed-in`, `config`, `run-dir`, `attempt`, `candidate`, `evidence-root`, `builder-instruction` | `builder-room`, `attempt`, `run`, `failure`, `halt` |
| `grading-vm` | `handed-in` | `run`, `halt` |
| `clear-evidence` | | `evidence` |
| `drive-seam` (`repl-researcher`) | `item`, `gate-room`, `handed-in` | `evidence` |
| `judge` | `item`, `evidence`, `grader` | `score` |
| `file-verdicts`, `file-score` | `params`, `grader`, `items`, `scores`, `attempt`, `candidate` | `cycle`, `run`, `failure`, `halt` |
| `fail-cycle`, `fail-score-stage` | `params`, `attempt`, `failure` | `cycle`, `run`, `halt` |
| `conclude` | `params`, `cycle` | `run` |
| `halt` | `params`, `halt` | `run` |

A delegate maps the same keys in and out: `gate-attempt:N` reads `params`, `handed-in`, `config`,
`run-dir`, `attempt`, `builder-room`, `builder-instruction`, `cycle` and `run`, and writes
`attempt`, `builder-room`, `candidate`, `gate-room`, `evidence-root`, `cycle`, `run`, `failure` and
`halt`; a grading delegate reads `item`, `gate-room`, `grader` and `handed-in`, and writes `score`,
the `map-each` collecting each item's into `scores`.

A leaf writes every key it declares, since ORC fails a leaf that leaves one out. A key its outcome
does not set is written blank: an empty text, an empty vector, or its map with every text empty.
A blank key is no fact: `failure`, `halt`, `gate-refusal` and `refusal` are read only when `run`
is `failing`, `halting`, `gate-refused` or `refused` (`../decisions.md`, "Spec round 20").

**Leaf names.** A leaf is named for its effect (E1 to E7: `stamp`, `pin`, `checklist`,
`self-check`, `fence`, `fail-cycle`, `regenerate`, `install`, `gate`, `gate-refusal`, `repair`,
`file-verdicts`, `file-score`, `fail-score-stage`) or its command (`start` for `start-run`,
`conclude` for `conclude-run`, `halt` for `halt-run`); `stamped`, `grader`, `render-failed`,
`grading-vm`, `clear-evidence` and `judge` record no effect. A name is unique in its workflow, so a leaf that
stands more than once in one workflow carries a qualifier after a `:`: the first self-check
(`self-check:0`), a gate's try within its attempt (`gate:2`, `gate-refusal:2`), a conclusion's
branch (`conclude:passed`). The attempt is never part of a leaf's name: it is `attempt` on the
blackboard, recorded with every effect result, and the qualifier of the delegate that runs it
(`gate-attempt:2`). The name before the first `:` is the one each step's `:leaf` declares in
`event-model/area.edn`, and the gate's store check holds every event to it, in the run's tick or a
child tick it delegated (`EachEffectInItsOwnLeaf`).

**What a leaf writes.**
- A recording the context records: `running` (the verdicts and the score among them), or
  `gate-passed` for a gate attempt that passed,
  `gate-refused` for a gate refused with one of the three codes `record-gate-refusal` takes ("A
  gate stopped by a refusal guard", above), with `gate-refusal`.
- A refused fence, a builder that left nothing: the refusal recorded as heard, then `failing`
  (the `fail-cycle` row). A refused `file-item-verdicts` or `file-score`: recorded as heard, then
  `scoring-failed` (the `fail-score-stage` row).
- A leaf that runs a program holds what is left of its stage's timeout as its bound (below): it
  stops the program when the bound passes and writes `failing`, reason `budget`, stage the
  leaf's (a builder at `regenerate`, `fence.sh` at `fence`, the self-check before the gate, the
  gate's own programs or a gate command at `gate`), recording nothing for the effect.
- A recording refused before the cycle opened (the stamp, the pin or the checklist: no self-check
  has named the cycle yet, so there is no cycle to fail): `halting`, `refused-before-the-cycle`
  (AGENTS.md: an unstamped scenario or an empty selection stops the cycle). So does a stamp whose
  identities are not the plan's own (E1), and a sealed grader the run did not approve
  ("Grading"). Each selected slice's stamp is its own leaf run (`map-each`), so a slice is stamped
  whatever an earlier slice's stamp came to; `stamped` then writes the run as the first stamp, in
  slice order, that did not leave it running left it, and so the run halts at that stamp.
- A refusal of `record-gate-attempt` with any other code, or of `record-gate-refusal`: recorded as
  heard, then `halting` at that effect, `effect-could-not-run`, the `detail` naming the refusal
  (gating again would offer the same again); but `cycle-concluded` means the cycle ended (its
  stage clock), and the leaf reads the cycle's progress instead.
- An effect that could not be performed at all records nothing for it and writes `halting`,
  `effect-could-not-run`: a handed-in command that could not be started, a gate command whose
  result says the gate could not be performed ("The gate command's result", above), a `render`
  that failed (`render-failed`, the effect `render`), a grading VM that could not be started
  (`grading-vm`, the effect `drive-seam`; "The researcher's VM", above).
- After each recording that may conclude the cycle (the self-check, the fence, `fail-cycle`, a
  gate, a gate refusal, the score), the leaf reads the Regeneration cycle's `cycle-progress` of the
  run's cycle into `cycle` and writes `cycle-passed` or `cycle-failed` when its `status` says so;
  a cycle not opened yet and a cycle still running leave `run` as the recording left it.
- `conclude` records `conclude-run` with that progress: a failed cycle's stage, reason and detail
  are the Regeneration cycle's (the run's `cycle.failure`). `halt` records `halt-run` with `halt`.
  Either way the run ends; a halt is never the cycle's conclusion.

**Every wait has a bound.** ORC bounds a tick and a delegate, never a leaf, so a leaf holds the
bound of what it waits for, and each delegate and each tick is bounded by the stages its subtree
spends (the run's `budget`, its `stage-timeouts-seconds`):
- a program a leaf runs: what is left of the timeout of the stage it runs in (the builder in
  `regenerate`, a repair's in `regenerate` too; legacy `fence.sh` in `fence`; the self-check
  before the gate, the gate's own programs or the gate command in `gate`; `grading-vm`'s VM in
  `score`); the first self-check
  runs before the cycle has a stage, and only its tick bounds it;
- one model reply: `model_reply_seconds`, 600 (`context.allium`, config), held by the provider
  ORC calls;
- the delegate `gate-attempt:N`: the gate stage's timeout and the regenerate stage's, for the
  repair that may end it; a grading delegate: the score stage's timeout divided by the number of
  items, so the grading of every item fits in the score stage;
- a tick: the run's budget, `fence + gate-attempt-limit × (regenerate + gate) + score` seconds,
  and `tick_margin_seconds`, 3600, more for what no stage clock times (the start, the stamps, the
  pin, the checklist, the grader, the first self-check, the render).

A delegate's bound is part of its tree as ORC builds it, so `cycle.v1` is built when a run starts,
with that run's bounds (ORC builds a workflow by its name and content: a run whose budget is the
last run's builds nothing new). A tick that does not end with success, because its bound or a
delegate's passed before the leaves wrote an outcome, is no leaf's outcome: the runner then halts
the run, recording `halt-run` with the effect of the leaf that had not completed (or, when none,
of the last that did), `effect-could-not-run`, and the bound that passed as the `detail`.

**The tree.** `ok?` is the condition `run` equals `running`, `<value>?` the condition `run`
equals that value, `not gate-refused?` the condition `run` does not equal `gate-refused`,
and `not running?` the condition `run` does not equal `running`. A gate attempt is one subtree, the workflow
`cycle-runner/gate-attempt.v1`, delegated once per attempt (`gate-attempt:N`); an item's grading
is the sealed grader `cycle-runner/grade.v1`, delegated once per item. Each is versioned by its
name, so a changed one is a new workflow (`gate-attempt.v2`, `grade.v2`) and `cycle.v1` names the
version it delegates to; a changed `cycle.v1` is `cycle.v2`.

```text
cycle-runner/cycle.v1                fallback
├─ sequence
│  ├─ start, ok?
│  ├─ map-each slices as slice into stamps, in slice order: stamp
│  ├─ stamped, ok?
│  ├─ pin, ok?
│  ├─ checklist, ok?
│  ├─ grader, ok?
│  ├─ self-check:0, ok?
│  ├─ fence, ok?
│  ├─ fallback: render, render-failed
│  ├─ ok?, regenerate, ok?
│  ├─ gate-attempt:1
│  ├─ fallback: not running?, gate-attempt:2
│  ├─ fallback: not running?, gate-attempt:3
│  ├─ gate-passed?
│  ├─ grading-vm, gate-passed?
│  ├─ map-each items as item into scores, in checklist order:
│  │     fallback: delegate cycle-runner/grade.v1, (no score)
│  ├─ file-verdicts, ok?, file-score, cycle-passed?
│  └─ conclude:passed
├─ sequence: failing?, fail-cycle, cycle-failed?, conclude:failed
├─ sequence: scoring-failed?, fail-score-stage, cycle-failed?, conclude:score
├─ sequence: cycle-failed?, conclude
├─ sequence: halting?, halt
└─ refused?

cycle-runner/gate-attempt.v1         fallback
├─ sequence
│  ├─ install, ok?
│  ├─ self-check, ok?
│  ├─ gate:1
│  ├─ fallback: not gate-refused?,
│  │            sequence: gate-refusal:1, ok?, gate:2,
│  │                      fallback: not gate-refused?,
│  │                                sequence: gate-refusal:2, ok?, gate:3,
│  │                                          fallback: not gate-refused?, gate-refusal:3
│  └─ fallback: not running?, repair
└─ not running?

cycle-runner/grade.v1                sequence
├─ clear-evidence
├─ drive-seam                        repl-researcher
└─ judge
```

So a repair follows a failed attempt only while the cycle is still running, and the Regeneration
cycle's policy decides exhaustion and the third gate refusal in a row (`ok?` before each try, `not
running?` before each repair and each later attempt); an attempt is gated at most three times, and
no fourth attempt exists. An attempt's subtree always ends its child tick with success: every `ok?`
that stops it leaves `run` not `running`, and `not running?` then holds. No item is graded unless an
attempt passed, and no builder runs once `file-verdicts` ran (`NoBuilderAfterTheScoring`). The
stage clock fails a stage on the wall clock; a leaf that finds the cycle already concluded
(`cycle-concluded`, or a `cycle-progress` that says so) writes `cycle-failed`, and the run
concludes with its cycle (`../decisions.md`, "Spec round 12" D4).

**Resume.** When the runner starts on the one store, it resumes every run in progress there with
ORC's `resume-in-progress!`, in the background, before it replies to a `start-run`: the run continues
in its own tick, where it stopped, what it recorded before stands, and the leaf that was running
runs again (a builder leaf reopens its session, "The builder session"). A run is never started
again to recover it. At ORC's pin a `repl-researcher` in flight is not resumed (ORC's
`runtime.clj` resumes leaves only); a run stopped while grading is a known gap, until ORC resumes
researcher nodes.

## Handed in

Read when `start-run` runs, never only at boot, so a grader changes them between runs.

- `LOYLY_GRAIN_STORE`: the one store, as every context's `seam.md` says: the runner's, ORC's and
  the five contexts' events, kept across runs (`MeasurementKeepsItsHistory`). Read when the
  candidate starts, since resume reads it. On one store a cut is pinned once and its checklist
  derived once, so each run names a cut identity of its own: a run at a cut identity an earlier
  run pinned halts at its pin, refused `cut-already-pinned`, before the cycle.
- `LOYLY_RUNS_DIR`: where each run's directory goes: the clean room, the builder rooms, the gate
  rooms, the gate evidence, the instruction files and the grading rooms. Never inside the project
  repository.
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
  and "The switch to the new harness" 2). The runner reads the file it is handed; it holds no benchmark value itself. It
  reads only the keys named here; any other `KEY=value` line is read by no one, but it is part of
  the file the harness digest covers. So a project whose fence comes from an approved fence plan
  writes the plan's digest into its configuration as a line of its own (Halla:
  `FENCE_PLAN=sha256:…`), and every self-check and gate attempt then carries it in its harness
  digest (`../decisions.md`, "Spec round 12" D1).
- `LOYLY_SELF_CHECK_CMD`: the harness self-check, legacy `harness/check.sh --local`.
- `LOYLY_STORE_CHECK_CMD`: for the `stdio` seam, the gate's event-store check, run in the
  project repository with the gate store's path appended; exit 0 passed, any other failed, its
  output the evidence. For the harness's own contexts, `harness/v2/event-store-check.sh NN`.
  Not read for the `http` seam.
- `LOYLY_BUILDER_HOST`: the provided pi-durable builder host's launcher (live
  `.pi/harness/bin/builder.sh`, its tools in Gondolin, on the existing Codex login), run as `<host>
  <builder-room> <instruction-file>` and waited for, with a third argument, `<builder-image>`, the
  path `LOYLY_BUILDER_IMAGES/BUILDER_IMAGE`, when the configuration names a builder image, and
  `LOYLY_BUILDER_SESSION_ID` in its environment ("The builder session"). One builder per run at a
  time. Its exit status is not evidence: the candidate is what it left in
  `<builder-room>/<APP_DIR>`. Provided, like ORC and Grain, and never regenerated.
- `LOYLY_GATE_CMD`: optional, the gate command (`../decisions.md`, "Spec round 12" D2). Absent
  or empty, the runner gates on the host, by the `http` or the `stdio` row of E5, exactly as
  before. Set, it is run for every gate attempt as `<cmd> <gate-room> <candidate> <evidence-dir>`
  and waited for, in the project repository, with the runner's own environment: `<gate-room>`
  the cut's tree with nothing at `APP_DIR`, `<candidate>` the candidate's copy, `<evidence-dir>`
  an empty directory the command writes its evidence into, all three absolute paths in the run's
  directory. The runner reads only its exit status and its standard output ("The gate command's
  result", above). What the command does is its own, provided like the builder host and never
  regenerated; the contract it keeps for the runner:
  - it judges the candidate at `<candidate>` as if it stood at `APP_DIR` in `<gate-room>`, fresh:
    nothing of an earlier attempt, a builder or the runner reaches it, and no variable of the
    runner's environment whose name starts `LOYLY_` reaches the candidate's own programs;
  - it changes nothing in `<gate-room>`, and what the candidate's own run writes into its copy
    does not change the candidate digest, which the runner took before it ran;
  - it prints `BOOT <boot-polls> <boot-budget-seconds>` before the boot, `ANSWERED <poll>` when
    the candidate first answered, and last the line that is its result: on exit 0 the identity
    probe's line; otherwise a code and its text, the code one of `contract-missing`,
    `suite-discovery-empty`, `suite-red`, `boot-budget-exhausted`, `identity-probe-failed`,
    `seam-answered-before-boot` and `identity-probe-missing`, or any other word when the gate could
    not be performed (`infrastructure`, by convention);
  - it writes its evidence into `<evidence-dir>` and nowhere else on the host: its whole output,
    and whatever a repair builder should read (E6 mounts that directory read-only);
  - it ends within the gate stage's timeout, or the runner stops it.
  Live (Halla, `../decisions.md`, "Spec round 12" D2): `gate.mjs`, a Node script on Gondolin's
  API, `VM.create` with a mount spec of `<gate-room>` read-only as the VM's root folder, `<candidate>` at
  `APP_DIR` and the world's image cache, egress only to the allow list; then in the VM, in order,
  the world up, the contract's two entry points, the suite, the candidate's boot polled at its
  readiness path within its budget, the identity probe, the world down; the evidence written from
  the host. No `LOYLY_*` key names the VM image, the world or the allow list: they are the
  command's own configuration.
- `LOYLY_BUILDER_IMAGES`: the directory holding the builder images, one folder per image, outside
  the project repository (live: `.pi/harness/.state/assets`, built by `harness/Makefile`). Read
  only when the configuration names a builder image; a named image that is not a folder there
  refuses the start (`builder-image-missing`). The image named for a stack carries that stack's
  toolchain, the same tools the gate's host runs the candidate with (for Clojure: a JDK, the
  Clojure CLI, git and bb): the provided image build proves it, not the runner
  (`../decisions.md`, "Spec round 9" D2, D4).
- `LOYLY_BUILDER_INSTRUCTION`: the seed of the builder instruction: the `render` node's
  instruction until GEPA publishes another version, between cycles.
- `LOYLY_MODEL_API_BASE`: the OpenAI-compatible endpoint of the model ORC's nodes call (live, a
  small OpenAI-compatible shim over pi-ai on the Codex login, provided and never regenerated), with `LOYLY_RENDER_MODEL`, the model
  `render` calls, and `LOYLY_GRADER_MODEL`, the model the researcher and the judge call, another
  than the one the builder session runs on. The runner registers that endpoint as ORC's model
  provider under a name of its own, never `:openai` (live: `:pi-codex`, an `:openai` provider
  with `:api-base` the endpoint and a 600-second timeout), so ORC frames every request and reply
  of its nodes in its marker format, each field under a `[[ ## <field> ## ]]` line, and uses no
  function calling.
- `LOYLY_GRADER_RUBRIC`: the sealed grader's rubric file: the judge's criteria, stance and the two
  bands, pass and fail. Its digest, with the judge workflow's version, is the grader digest
  ("Digests the runner computes"); GEPA improves it on calibration only, and a new one is used only
  once the operator approves its digest.
- `LOYLY_GRADER_IMAGE`: the folder of a Gondolin guest image (live: the builder image
  `.pi/harness/.state/assets/builder-clojure`, built by `harness/Makefile`), outside the project
  repository. Each item's researcher runs its shell in a VM made from it ("The researcher's VM",
  above). The `gondolin` CLI is found on `PATH`, provided like the builder host and never
  regenerated (`../decisions.md`, "Spec round 20").
- `LOYLY_SCORING_TOOL_PATHS`: read by the Regeneration cycle (its `seam.md`).
- `OTEL_EXPORTER_OTLP_ENDPOINT`: optional. Set, the runner exports ORC's events of every run as
  OpenTelemetry spans over OTLP/HTTP JSON to it: one trace per run, a span per tick and per node,
  the judge's scores as spans of their node, and passes `TRACEPARENT` to the builder host. Diagnostic only: no stamped item reads it, and a run is the same with it or without it.

### How a grader drives it without a live pi

Everything the runner acts on is a fixture a grader states, so no item waits for a live model:

- a fixture git repository (`LOYLY_PROJECT_REPO`) whose commit holds a plan with stamped
  scenarios, a `seam.md` and an event model beside them, the configuration file, a self-check
  script and an identity probe;
- `LOYLY_SELF_CHECK_CMD` a script that passes, fails naming a check, or prints `SKIP` lines;
- `LOYLY_BUILDER_HOST` a stub builder that copies a fixture candidate into the candidate scope (a
  candidate whose `test.sh` and `start.sh` serve a page with the marker on `PORT`, or one with a red
  suite, a slow boot, no marker, no `start.sh`), leaves it empty, sleeps past the timeout, or records
  the paths, the arguments, the instruction file and the session identity it was handed;
- `LOYLY_MODEL_API_BASE` a stub model, an OpenAI-compatible endpoint the grader scripts: it
  renders the instruction as a text the grader names, replies to the researcher and the judge of each
  item as scripted (a score with a reason, a score with none, a failure), and records every request
  it is sent. It replies in ORC's marker format (`[[ ## <field> ## ]]` per field, then
  `[[ ## completed ## ]]`), since the provider is registered under a name other than `:openai`
  ("Handed in"). A request sent for the researcher shows the item's shape, never its value, so a
  scripted researcher's code reads `(get-input :item)` and branches on it. Requests carry tick ids
  and timestamps, and ORC may retry a request, so a stub matches a request on stable substrings
  (the node's output contract, the item's text, the reply fragments that matter), never on the
  whole text, a hash or a count;
- `LOYLY_GRADER_RUBRIC` a fixture rubric whose digest the grader computes and names, or not, in the
  run's approved grader digests;
- a `PORT` the grader chooses, and holds itself to see the seam respond before boot;
- for the `http` seam, a cut whose identity probe reads a file of the cut relative to its own
  location, as Löyly's `harness/probe.sh` sources `$(dirname $0)/../harness/config.sh`;
- for the `stdio` seam, a configuration with `APP_SEAM=stdio`, fixture candidates whose `test.sh`
  and `start.sh` record their arguments and environment, a cut whose identity probe is a stub
  that records its arguments and environment and exits 0 or 1 with a line, and a
  `LOYLY_STORE_CHECK_CMD` stub that records its arguments and exits 0 or 1 with a line, so no
  item needs a Grain store (the store check's own rules are `harness/v2/event-store-check-test.sh`'s);
- a plan whose slice is a symbolic link in the commit's tree, and a configuration naming
  `PLAN_SLICES`;
- a configuration naming `BUILDER_IMAGE` and a `LOYLY_BUILDER_IMAGES` directory that holds a
  folder of that name (any files) or does not;
- a `LOYLY_GRAIN_STORE` file the grader names, kept across two runs, or across a runner it stops
  and starts again;
- a `LOYLY_GATE_CMD` stub that records its arguments, its working directory and what it found at
  each (the files of `<gate-room>`, the candidate's digest), writes a file into `<evidence-dir>`,
  prints the `BOOT` and `ANSWERED` lines and a last line as the grader scripts it, and exits 0 or
  1, or sleeps past the gate timeout; so no item of the gate command needs a VM or a world;
- `LOYLY_GRADER_IMAGE` a real Gondolin guest image and `gondolin` on `PATH`, for every run that
  reaches grading: the one fixture that is not a stub, since `grading-vm` starts a VM and
  `@item-06-066` observes what the researcher's shell sees in it (the system it runs, its working
  folder, the folders of the host it can list).

The grader observes through `run-progress` and the five contexts' queries on the same candidate.
There are two exceptions, each the grader's own. The one store (`LOYLY_GRAIN_STORE`): after the
method responded, the grader may read it, as the gate's store check does, to see each effect
inside its own leaf, each gate attempt and each item's grading in a child tick of its own, and
each judge score ("The tree", above; `@item-06-059`, `@item-06-061`, `@item-06-064`). And a copy
of the candidate the grader made, in a folder of its own outside the project repository, to see it
run with no spec around it ("The build", below; `@item-06-062`). What the stub builder, the stub
model and the fixture candidates record is the grader's own fixture, read from where the grader
told it to write.

### How the candidate's own suite drives it

The five contexts are in the candidate, so its own suite needs no stand-in for them: it drives
the tree with the fixtures above (a fixture repository, a stub self-check, a stub builder host, a
stub model, fixture candidates) and reads the one store. The stubs are the suite's, never the
product's.

The spine comes first. A build makes one run go through the whole tree before it widens: start,
stamp, pin, checklist, grader, self-check, fence, render, regenerate, a gate attempt, each item's
grading (`clear-evidence`, `drive-seam`, `judge`), `file-verdicts`, `file-score` and `conclude`,
with the stubs above. Only then does it carry the other items one at a time. The gate's
identity probe drives such a run to a filed score (`@item-06-064`), so a candidate that halts
before the score fails the gate, however many other items it carries.

The suite is bounded. `test.sh` runs the whole suite in at most five minutes on the gate's host.
It starts one JVM, and one ORC and one store per fixture run inside it, never a new JVM per test.
(`../decisions.md`, "Spec round 19".)

## Refusals

A refusal records nothing and is the error `-32000` with `data.refusal`, one of the codes below.
The numbers are the `requires` of the rule in `allium/cycle-run.allium`, checked in that order.

| Method | Allium rule | `data.refusal` by requires |
|---|---|---|
| `cycle-runner/start-run` | `StartRun` | 1 `run-already-started`, 2 `run-in-progress`, 3 `cycle-already-run`, 4 `not-a-commit`, 5 `builder-image-missing` |

"Records nothing" means no event of `event-model/` is appended and no effect is performed by the
refused method. A context's refusal is not the runner's: it is recorded as an effect result
(`outcome` `refused`).

## The event store

The one store: Grain's `event-store-v3`, SQLite, at `LOYLY_GRAIN_STORE`, holding the runner's
events, ORC's own, and the five contexts' events, appended by their commands; ORC shares its file
and tenant. Each command of the runner is issued by a leaf of the run's tick of `cycle.v1`, or of a
child tick it delegated, and the gate's store check holds each event to its leaf
(`event-model/area.edn`, `:leaf`):

| Command | ORC workflow | Leaf |
|---|---|---|
| `start-run`; `record-effect` of the stamps, the pin, the checklist, the first self-check, the fence, the regenerate and the score | `cycle-runner/cycle.v1` | `start`; the effect's |
| `record-effect` of an attempt's install, self-check, gates, gate refusals and repair | `cycle-runner/gate-attempt.v1`, a child tick of the run's tick | the effect's |
| `conclude-run`, `halt-run` | `cycle-runner/cycle.v1` | `conclude`, `halt` |

The five contexts' commands a leaf issues are issued inside that leaf, in the same tick, on the
same store. A flow's events may lie in a workflow its tree delegates to: a delegate runs as a
child tick of the flow's tick (ORC's), and the store check follows that lineage (`../decisions.md`,
"Spec round 16" D8).

## The build

What the candidate's folder (`APP_DIR`) holds, beyond its seam (`../decisions.md`, "Spec round
16" D6, D7):

- **Its own event models.** The runner registers the one event model as its room gives it
  (AGENTS.md): every context's event model, merged. It carries a copy of each, byte for byte as
  its room holds them at the cut: every `.pi/spec/<context>/event-model/*.edn` of `cycle-runner`
  and of the five contexts, at `event-model/<context>/<file>` in its own folder, and it reads them
  only there. It reads no
  file outside its own folder to run: a copy of the candidate started anywhere, with no spec
  beside it, runs as the candidate does in its room, and a later change to the repository's spec
  changes nothing in a build until a cycle rebuilds it.
- **Only its build.** Its launch entrypoint and suite (`start.sh`, `test.sh`), its build file, its
  sources, its tests and the fixtures they read, its copy of the event models, and its notes
  (`README.md`, `UNCARRIED.md`). No copy of ORC's or Grain's sources: the room's `orc/` and
  `grain/` are read in place, and the build takes them as dependencies at their pinned commits.
  No builder scratch: every file on the build's paths is one its launch or its suite loads. What
  a build or a run leaves behind (`.cpcache/`, `classes/`, `target/`, output files, stores) is
  not the build, and the source digest recorded at acceptance (`PROVENANCE.md`) leaves it out.
  The final judge checks it at acceptance; no gate checks it yet (`TheCandidateHoldsOnlyItsBuild`,
  planned `candidate-contents`).

## Not in this slice

The gate in a Gondolin VM as the runner's own behaviour (proposed, not decided): it gates on the
host, or hands the gate to a provided command (`LOYLY_GATE_CMD`, `../decisions.md`, "Spec round
12" D2) that may run it in a VM with its world; a fence other than legacy `fence.sh`'s (a
mount-table fence, a candidate scope of more than one folder; "Spec round 12", later); GEPA itself,
which runs between cycles and publishes versions this tree names; the room policy check
(`TheGateRoomIsNotTheBuilders`, planned `room-policy`); a check of each leaf's declared contract
and bounds as ORC recorded them (`TheBlackboardIsTheRunsState`, `EveryWaitHasABound`, planned
`leaf-contract`), of what the candidate's folder holds (`TheCandidateHoldsOnlyItsBuild`, planned
`candidate-contents`) and of the grader's model (`AModelOtherThanTheBuildersOwn`, planned
`model-independence`); resuming a run stopped while a researcher was in flight.
