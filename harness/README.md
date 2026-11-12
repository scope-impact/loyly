# The harness tools: the legacy reference and the spec-v2 cycle tools

## Current state (2026-11-08)

- **What runs the regeneration cycles: the new harness.** The slice-06 cycle runner (its cycle
  03 candidate) drives the five contexts of slices 01-05, with `pi` and its Gondolin backend as
  the builder. Until a run can resume after a restart, the runner is kept alive from start to
  the filed score. See "The switch to the new harness", below.
- **What these tools are for: the reference.** The legacy fence, gate and instrument, adopted
  here to measure the Pi/ORC harness, and the `v2/` tools that built and scored spec v2 one
  context per cycle. They are never deleted; the shadow runs compared the new harness with
  them stage by stage.
- **How a spec-v2 cycle is scored: legacy step 5.** A black-box grading agent drives the
  candidate over its seam and writes a verdicts file; `harness/v2/score.sh --verdicts` files
  it with the legacy `score.clj` and `scoreboard.clj`. No Elixir grader, no calibration, no
  approval. See "Scoring a spec-v2 cycle", below.
- **What happened before:** every move of the goldens is under "History", in date order.

## What these tools are

These are the legacy fence, gate, and instrument tools from
[`archive/legacy-harness/`](../archive/legacy-harness/README.md), adopted here to regenerate
and measure the Pi/ORC harness itself. The product they measure is the harness; its spec is
spec v2, `.pi/plan/NN-context/spec.feature` (`PLAN_DIR` in `config.sh`), each `.pi/plan/NN-context`
a symlink to `../spec/<context>`. The stages are the legacy ones: fence (`fence.sh`),
regenerate (a Pi builder session), gate (`verify.sh`), score (a verdicts file and `score.clj`)
and scoreboard (`scoreboard.clj`). Until the switch a person ran them, one context per cycle
(below). The archive stays untouched as the parity reference.

## Spec v2: one context per cycle (`v2/`)

Spec v2 is rebuilt one context at a time (`.pi/spec/decisions.md`, "First light"). `SLICE=NN`
in the environment switches `config.sh` to that context: `APP_DIR` is `harness-impl/NN-context`,
`APP_PROBE` is `v2/probe-NN.sh`, and `FENCED_PATHS` also hides spec v1, every other context, the
legacy driver itself (the earlier answer of every context), `v2/`, and all of `harness-impl/`
(the promoted build, `../harness-impl/README.md`, so the room's candidate scope starts empty). The shared module files
are unchanged. One cycle, by hand (cycle MM of slice 01, its room and prompt under the run
state, "Where a spec-v2 cycle's run state goes", below):

```bash
STATE=${PI_HARNESS_STATE_ROOT:-.pi/harness/.state}; mkdir -p $STATE/rooms $STATE/logs
ROOM=$STATE/rooms/01-cMM; OUT=$STATE/prompts/01-cMM
SLICE=01 harness/fence.sh $ROOM > $ROOM.fence.log 2>&1   # the room, proved
bb harness/v2/prompt.clj 01 $OUT                # OUT/system.md and OUT/prompt.md, outside the room
(cd $ROOM && pi --append-system-prompt $OUT/system.md @$OUT/prompt.md)   # a person runs the builder
harness/v2/gate.sh 01 $ROOM 2>&1 | tee $STATE/logs/gate-cMM-slice01.log  # the gate, from this checkout
```

The room holds no instrument, so the gate runs here: `CANDIDATE_ROOM=ROOM` points `APP_DIR` at
the room's candidate, `verify.sh` runs unchanged (with `v2/probe-01.sh`), and then
`v2/event-store-check.sh` reads the Grain store the gate handed the candidate
(`LOYLY_GRAIN_STORE`) directly, ORC's workflow runs included: ORC runs on that store, and each
event a command produced must lie inside a completed run of its workflow.
`v2/event-store-check-test.sh` judges that check on hand-made stores.

A context that consumes other contexts' handoffs is built alone too: its room keeps each
producer's `event-model/handoffs.edn` and nothing else of it (`config.sh` finds them in the
context's event model), and the gate and the grader hand those events in through the
candidate's seam, one method per consumed event (`.pi/spec/decisions.md`, "Slice 02"). The
event-store check allows exactly the consumed handoff types, each held to its contract's
schema and never counted as the candidate's own, and refuses any other foreign type. Slice 02,
`measurement`, consumes `specification/scenario-stamped`, `cycle-preparation/source-cut-pinned`
and `regeneration-cycle/gate-attempt-passed`; its probe is `v2/probe-02.sh`.

Slice 03, `grader-lifecycle`, consumes no handoff: its room holds no other context's file. Its
candidate is also handed the current scenario digests, a JSON file `LOYLY_SCENARIO_DIGESTS`
names (`.pi/spec/decisions.md`, "Slice 03"); its probe, `v2/probe-03.sh`, writes one, then a
golden from that scenario, moves it, has a move without a note refused and reads it back.

Slice 04, `regeneration-cycle`, consumes six handoffs (`cycle-preparation/source-cut-pinned`,
`grader-lifecycle/instrument-golden-written` and `-moved`, `measurement/measurement-filed`,
`score-stage-failed` and `scoreboard-reported`): its room keeps those three producers'
`handoffs.edn`. Its candidate records what ORC runtime's nodes did in a room, handed in as
command params, and acts on nothing outside its store; it is also handed the scoring tools'
paths, `LOYLY_SCORING_TOOL_PATHS` (`.pi/spec/decisions.md`, "Slice 04"). Its probe,
`v2/probe-04.sh`, hands in a pinned cut and a golden, opens a cycle on a self-check at the goldens
digest `seam.md` defines, has a fence whose path matched nothing refused, proves the clean room
and reads the cycle back.

Slice 05, `cycle-preparation`, consumes no handoff and is handed the repository's commits
(`LOYLY_PROJECT_COMMITS`); its probe is `v2/probe-05.sh`. Slice 06, `cycle-runner`, keeps the
five other contexts' `seam.md` and `event-model/` in its room, the contracts the runner speaks
(`harness/config.sh`); its probe is `v2/probe-06.sh`.

### Scoring a spec-v2 cycle (legacy step 5)

After the gate, `harness/v2/score.sh NN ROOM --verdicts FILE [--by "who/how graded"]` scores
the cycle the legacy way, the legacy README's step 5: grade each checklist item against the
running app, however you grade, then file. The grading happens before `score.sh` and outside
it: an agent drives the candidate `ROOM/harness-impl/NN-<context>/start.sh` only over its public
seam, as the context's `seam.md` describes it, grades each item of the slice's spec however it
judges best, and writes `FILE`, `[{"id", "verdict": "pass"|"fail", "evidence"}]`. `score.sh`
derives the checklist from the slice's spec alone, copies `FILE` into the cycle as its
`verdicts.json`, records `verdicts: sha256:<hex> <basename>` and `by: <text>` (default
`unspecified`) in the cycle's `graders.txt` as provenance, and the legacy `score.clj` and
`scoreboard.clj` file and report into `v2/cycles/NN/MM/`: an item without a verdict is
unreached, fails and stays in the denominator; zero verdicts are refused. A missing or
unreadable `FILE` is refused before a cycle is claimed; `SCORE_WHICH=1` prints what would be
recorded. Nobody approves a grading and nothing is calibrated or sealed (decided 2026-11-07).
The Elixir graders that scored cycles 01 and 02 of slice 01 and 01 to 03 of slices 02 to 04 are
retired with `graders.json` and their calibration (cb8f5c7); git history keeps them. An open
grader run by ORC was tried in this tool and reverted (4616d13, 1f03fe8): it is a new-harness
feature, drafted in the spec (`.pi/spec/grader-lifecycle/drafts.feature`,
`.pi/spec/measurement/drafts.feature`) and parked while OG1 is no (`.pi/spec/decisions.md`,
"Spec round 3" 4).

### The assembly gate (2026-11-07)

`harness/v2/assemble.sh ROOM01 ROOM02 ROOM03 ROOM04 ROOM05` (with `v2/assemble.clj` and
`v2/store_reader.clj`) wires the five gated candidates together through their handoffs
(`.pi/spec/decisions.md`, "First light" 5 and "Spec round 5"), driving each only through its
`start.sh` seam. It first tries one shared `LOYLY_GRAIN_STORE` for producers and consumers, one
after the other and concurrently, and reports, not as a check, whether a consumer sees a
producer's facts with nothing handed in (it did not: each candidate writes its own Grain
tenant; the shared store is the deferred end state, "Spec round 5" 1). The assembly is the
relay, as in the legacy harness, whose tools pass files: each candidate runs on its own fresh
store and one story runs end to end: stamp, pin a cut, write and move a golden, derive the
checklist, a regeneration cycle to a passed gate attempt, item verdicts, the score, the
scoreboard, the cycle passed. After each command the relay reads the producer's store file
directly, with Grain's own reader on a copy (as `v2/event_store_check.clj` does), takes the
events of the producer's handoff types (its `event-model/handoffs.edn`) within its tenant, in
store order, not from command results ("Spec round 5" 2), and hands each new one in unchanged,
in that order, to every consumer the event models name (a read model's `:consumes` or a
processor's `:subscribes` of a foreign event); so `scoreboard-reported`, which Measurement's
policy issues inside `file-score`, travels like any other handoff. It checks the contracts fit,
each producer's handoffs lie in one tenant, every handoff a command result carries is in the
producer's store as given, no hand-in is refused, each is echoed as given, the order is kept,
the cycle passed measured and reported, and the cut id, candidate digest, goldens digest and
item ids agree across contexts. Last, a reading ("Spec round 5" 3, option c): a cycle the stage
clock failed at the score stage and Measurement measured anyway stays failed in the
Regeneration cycle while Measurement keeps its measurement filed. Exit 0 only when every check
passes; it stops every process it starts.

### Where a spec-v2 cycle is filed (2026-11-07)

Each slice numbers its own cycles under `v2/cycles/NN/` (`01`, `02`, ...), never under
`harness/cycles/`. `oracle.sh`, a shared module file, reads `harness/cycles/01` as this
repository's recorded cycle 01 and judges it against `oracle/scoreboard-01.golden`, recorded
from spec v1's cycle 01; the first v2 score filed as `harness/cycles/01` would have collided
with that and turned the oracle red with no instrument defect, and moving the golden needs a
recorded reason this is not. `score.clj` and `scoreboard.clj` find `harness/cycles` from their
own location, not from an argument, so `v2/score.sh` runs unchanged copies of them in its
scratch root, whose `harness/cycles` is a link to `v2/cycles/NN`; no shared module file changed
and no golden moved. One root per slice also makes the legacy scoreboard's "previous cycle"
(the number minus one) the last cycle of the same selection, the slice, as
`.pi/spec/decisions.md` g asks.

### Where a spec-v2 cycle's run state goes (2026-11-08)

The scores go into `v2/cycles/` and are committed; the run state behind them never is. It lives
under `PI_HARNESS_STATE_ROOT`, by default `.pi/harness/.state/` of this checkout (ignored by
`.pi/harness/.gitignore`), where `regen.clj` keeps its own:

| Under the state root | What it holds |
|---|---|
| `rooms/NN-cMM/`, `rooms/NN-cMM.fence.log` | slice NN's clean room in cycle MM (a git repository, the candidate inside) and its fence's output |
| `prompts/NN-cMM/` | the `system.md` and `prompt.md` that `v2/prompt.clj` rendered for that room's builder |
| `grading/NN-cMM/` | the grading session's working files and the verdicts file handed to `v2/score.sh` |
| `logs/` | `gate-cMM-sliceNN.log`, `score-cMM-sliceNN.log`, `assemble-NN.log` |
| `gate/`, `assemble/` | the work directories `v2/gate.sh` (the candidate's Grain store) and `v2/assemble.clj` make, one per run |
| `runs/` | the cycle runner's runs directory, `LOYLY_RUNS_DIR`: each run's stores, clean room, builder and gate rooms, gate evidence |
| `shadow/run-0N/` | the five shadow runs of the switch: `evidence/`, `runner-store.sqlite`, `runs/`, both sides' rooms |
| `audit/` | the grading audits of slices 02 to 04: each explorer's verdicts and the stores it drove |
| `assets/`, `runtime-images/` | the Gondolin VM images `harness/Makefile` builds; in a worktree, a link to the main checkout's |

`MAP.md` at the state root records where each tree written before 2026-11-08 was first kept.

## The switch to the new harness (2026-11-08)

The new harness, the slice-06 cycle-runner (the cycle 03 candidate) driving the five contexts
of slices 01-05 with `pi` and its Gondolin backend as the builder, now runs the regeneration
cycles. It did what this harness did on the full Löyly app (11 items) at every stage, fence to
scoreboard, in shadow runs 4 and 5, and the user confirmed the switch. These tools stay as the
reference and are never deleted. The rule, the five runs, and what stays open:
`.pi/spec/decisions.md`, "The switch to the new harness". Each run's evidence is kept, not
committed, at `.pi/harness/.state/shadow/run-01` to `run-05`.

## Differences from the archive, each with its reason

- `hjson.clj` resolves the plan tree from the `PLAN_DIR` line of `harness/config.sh` instead of
  hard-coding `docs/plan`. This repository already uses `docs/plan` for the Löyly product, so
  the harness spec needs its own tree. Reading the file, not the environment, keeps the tools
  and `modules.sh` agreeing and stops a parent shell leaking its own value.
- `spec.feature` `@harness-054` and its oracle check follow that change: the tools resolve the
  declared tree, so the line reads ok and the exit code is 0.
- `modules.sh` declares `harness/Makefile` as a local file: it is this repository's runtime
  bootstrap, not part of the instrument.
- `verify.sh` and `probe.sh` drive the harness's real seam, JSON-RPC over stdio
  (`.pi/model/affordances.yaml`), instead of HTTP: the harness is not a web product, and a page served
  only for the gate is exactly what a builder can fake (harness-regen-3 served a static page).
  The legacy rules stay: the instrument is checked first, the suite is trusted by exit code
  only, the boot is fresh and timed with its margin reported, and identity is proved by domain
  behavior (the cycle contract's name and stage order). The boot budget is thirty seconds, not
  ten (`@item-02-011`): the harness starts a JVM and ORC's durable system, 11.0s cold.
- `config.sh` declares no `PORT`; the harness has none.
- `config.sh` declares no `SIBLING_REPOS`: no other repository carries this harness, so
  `modules.sh` compares nothing and says so.

## History

### The goldens, and why they moved

Since 2026-11-07 no spec-v2 cycle has a sealed grader ("Scoring a spec-v2 cycle", above): where
an entry from then on says a slice's grader grows, the grading agent grades the grown checklist.
"Approved grader" in these entries means `graders.approved.json`, written for spec v1.

**2026-11-04: the instrument was adopted for a different product.** The checklist golden in
`oracle/` had been recorded for the Löyly product spec under `docs/plan/`. Here the same tools
measure the harness spec under `.pi/model/plan/`, so the derived checklist is a different list
(74 items). This is neither an instrument behavior change nor a reworded spec: the measured
spec itself changed. The golden was re-recorded with `harness/oracle.sh record` for that reason
and no other.

**2026-11-04: the spec grew.** `@item-02-010` (the running harness proves its identity by
serving its cycle contract) was added, so the derived checklist gained one item (75). The
golden's diff is additions plus the one `total` line, which is the test for this case.

**2026-11-05: an item's requirement changed, and the spec grew.** `@item-02-010` now proves
identity over the stdio seam instead of an HTTP page, so its name changed; that is a recorded
change to what the spec requires, not an instrument change. Five items were added
(`@item-02-011`, `@item-09-001` to `@item-09-004`). The golden's diff removes only that one
name line and the `total` line (75 → 80).

**2026-11-05: the first scored cycle.** Cycle 01 (`harness-regen-5`) was scored by the approved
grader `sha256:b9db122a…` (7 items, calibrated carried/uncarried): 6/80 pass, 73 unreached and
recorded as fail. `harness/oracle.sh record` recorded `oracle/scoreboard-01.golden` for it; the
checklist golden did not move.

**2026-11-05: the spec grew.** The first regenerate attempt now runs one fresh builder per
selected slice: `@item-02-012` and `@item-02-013` were appended to the end of slice 02. The
golden's diff is additions plus the one `total` line (80 → 82). The approved grader's scenarios
did not change, so it stays approved.

**2026-11-05: an item was reworded.** `@item-01-007` read as if `submit-cycle` took a
selection, which contradicted `rpc.yaml`; cycle 05's builder followed it and refused every bare
submit. It now names the slice path ("An unapproved partial selection cannot start a slice
cycle"), and `rpc.yaml` states that `submit-cycle` regenerates the whole plan as its approved
selection. That is wording replaced, a recorded reason; the golden's diff is that one name line.
The approved grader does not cover `01-007`, so it stays approved.

**2026-11-05: the spec grew.** Cycle 08's nine slice builders stopped after 6 to 26 tool calls
each, leaving most of their slices listed in `UNCARRIED.md`. `@item-02-014` was appended to the
end of slice 02: ORC continues a slice builder's session, at most three times, while it lists
its own slice's items uncarried. The golden's diff is additions plus the one `total` line
(82 → 83). No approved grader's scenarios changed, so all three stay approved.

**2026-11-06: the spec grew.** Comparing the contract with the legacy harness found behaviour
it had not carried. Appended at the end of their slices: `@item-02-015` to `@item-02-017`
(the gate passes only on a suite it saw run, against the process it started, and names why it
failed) and `@item-03-010` to `@item-03-017` (the stamper, what a checklist item carries,
refused verdicts, last duplicate wins, half-to-even rounding, the moved item set). The golden's
diff is additions plus the one `total` line (83 → 94). No approved grader's scenarios changed,
so all three stay approved; the eleven new items have no grader yet.

**2026-11-06: the spec grew.** `@item-03-018` was appended to the end of slice 03: ORC's
evaluation judges are diagnostics, never the instrument. The golden's diff is additions plus
the one `total` line (94 → 95). No approved grader's scenarios changed.

**2026-11-06: the spec grew.** `@item-03-019` and `@item-03-020` were appended to the end of
slice 03: instrument goldens are written from the spec, never recorded, and the harness
self-check reproduces them before any clean room. The golden's diff is additions plus the one
`total` line (95 → 97). No approved grader's scenarios changed.

**2026-11-06: wording replaced.** Slices 07 (harness bootstrap) and 08 (project VM config)
left the spec: the builder adapter and its Gondolin runtime are provided, like ORC and Grain,
not generated. The golden's diff removes exactly their five items (`07-001` to `07-003`,
`08-001`, `08-002`) and the one `total` line (97 → 92). No approved grader covered them.

**2026-11-06: the spec grew.** `@item-02-018` and `@item-02-019` were appended to the end of
slice 02: ORC launches every builder as a durable Pi session keyed by its tick, in a Gondolin
room ORC alone chooses. The golden's diff is additions plus the one `total` line (92 → 94). No
approved grader's scenarios changed.

**2026-11-06: wording replaced.** Every `@skip` tag left on the spec by an earlier executable
suite was removed. Tags carry no verdict, so item ids, names, rules, and the total are
unchanged; the golden's diff is only the `source` line numbers of the items below each removed
tag line. No approved grader's scenarios changed.

**2026-11-06: the measured spec changed: spec v2.** The plan root moved from `.pi/model/plan/`
(spec v1) to `.pi/plan/`, which holds one relative symlink per accepted spec-v2 context, named
`NN-context`, pointing at `../spec/<context>` (`.pi/spec/decisions.md`, "First light"). Only
`specification` is accepted so far, so the derived checklist is its 15 items, `01-001` to
`01-015`. As on 2026-11-04 this is the measured spec changing, not the instrument: the golden
was re-recorded with `harness/oracle.sh record` for that reason and no other. The approved
graders in `graders.approved.json` were written for spec v1 and cover none of these items.

**2026-11-06: wording restored.** R4.1 moved the Scenario Outlines `@item-01-008` and
`@item-01-010` into `specification/spec.feature` and left their `Examples:` tables behind in
`drafts.feature`. The tables moved to their Outlines; ids, names, rules and the total are
unchanged, and the golden's diff is only the `source` line numbers of `01-009` to `01-015`.

**2026-11-07: the spec grew: slice 02, `measurement`, accepted.** The product owner's
acceptance of measurement's legacy minimum drafts added `.pi/plan/02-measurement` and its 24
stamped items, `02-001` to `02-024`; the derived checklist is slice 01's 15 and those 24. The
spec grew (additions only), so the golden was re-recorded with `harness/oracle.sh record`; its
diff is additions plus the one `total` line (15 → 39). No approved grader's scenarios changed.

**2026-11-07: the spec grew: slice 03, `grader-lifecycle`, accepted.** The product owner's
acceptance of grader-lifecycle's legacy minimum drafts added `.pi/plan/03-grader-lifecycle` and
its 4 stamped items, `03-001` to `03-004`; the derived checklist is slice 01's 15, slice 02's 24
and those 4. The spec grew (additions only), so the golden was re-recorded with
`harness/oracle.sh record`; its diff is additions plus the one `total` line (39 → 43). No
approved grader's scenarios changed.

**2026-11-07: the spec grew: slice 04, `regeneration-cycle`, accepted.** The product owner's
acceptance of regeneration-cycle's legacy minimum drafts added `.pi/plan/04-regeneration-cycle`
and its 27 stamped items, `04-001` to `04-027`; the derived checklist is slice 01's 15, slice
02's 24, slice 03's 4 and those 27. The spec grew (additions only), so the golden was
re-recorded with `harness/oracle.sh record`; its diff is additions plus the one `total` line
(43 → 70). No approved grader's scenarios changed.

**2026-11-07: wording replaced: spec round 1.** The product owner's spec round 1
(`.pi/spec/decisions.md`, "Spec round 1") reworded `@item-02-002` and `@item-02-018`; one line
left slice 02's `spec.feature`, so the golden was re-recorded with `harness/oracle.sh record`
and its diff is only the `source` line numbers of `02-019` to `02-024`, each up one; no id, name
or Rule moved (10ec776). *(Entry added 2026-11-08 from the commit; it was missing.)*

**2026-11-07: the spec grew: spec round 2.** The product owner's spec round 2
(`.pi/spec/decisions.md`, "Spec round 2") appended 21 stamped minimum items at the end of their
slices' `spec.feature`: `02-025` to `02-031`, `03-005` to `03-007` and `04-028` to `04-038`; the
derived checklist is the 70 and those 21. The spec grew (additions only), so the golden was
re-recorded with `harness/oracle.sh record`; its diff is additions plus the one `total` line
(70 → 91) (337546b). *(Entry added 2026-11-08 from the commit; it was missing.)*

**2026-11-07: the spec grew: slice 05, `cycle-preparation`, accepted.** The product owner's
acceptance of cycle-preparation's new minimum drafts (none cites legacy behaviour) added
`.pi/plan/05-cycle-preparation` and its 4 stamped items, `05-001` to `05-004`; the derived
checklist is slices 01 to 04's 91 and those 4. The spec grew (additions only), so the golden was
re-recorded with `harness/oracle.sh record`; its diff is additions plus the one `total` line
(91 → 95). No approved grader's scenarios changed.

**2026-11-07: the spec grew: spec round 3.** The product owner's spec round 3
(`.pi/spec/decisions.md`, "Spec round 3") appended 4 stamped minimum items at the end of their
slices' `spec.feature`: `02-032`, `03-008`, `04-039` and `04-040`; the derived checklist is the
95 and those 4. The spec grew (additions only), so the golden was re-recorded with
`harness/oracle.sh record`; its diff is additions plus the one `total` line (95 → 99). No
approved grader's scenarios changed.

**2026-11-07: the spec grew: spec round 6.** The product owner's spec round 6
(`.pi/spec/decisions.md`, "Spec round 6") appended 50 stamped balance items at the end of their
slices' `spec.feature`: `02-033` to `02-043`, `03-009` to `03-011` and `04-041` to `04-076`; the
derived checklist is the 99 and those 50. The spec grew (additions only), so the golden was
re-recorded with `harness/oracle.sh record`; its diff is additions plus the one `total` line
(99 → 149). No approved grader's scenarios changed.

**2026-11-07: the spec grew: slice 06, `cycle-runner`, accepted.** The product owner's spec round
7b (`.pi/spec/decisions.md`, "Spec round 7b") added `.pi/plan/06-cycle-runner` and its 35 stamped
items, `06-001` to `06-035` (the 34 drafts of round 7a and one added for Q1); the derived
checklist is the 149 and those 35. The spec grew (additions only), so the golden was re-recorded
with `harness/oracle.sh record`; its diff is additions plus the one `total` line (149 → 184). No
approved grader's scenarios changed. A slice-06 room keeps the five other contexts' `seam.md` and
`event-model/` (`harness/config.sh`), the contracts the runner speaks.

**2026-11-08: the spec grew: spec round 7c.** Shadow run 1 found the runner's gate calling the
identity probe with the bare port where legacy `verify.sh` passes `http://127.0.0.1:PORT`; the
product owner's spec round 7c (`.pi/spec/decisions.md`, "Spec round 7c") stated legacy
`verify.sh`'s and `fence.sh`'s arguments in `cycle-runner/seam.md` and appended one stamped
minimum item at the end of slice 06's `spec.feature`: `06-036`; the derived checklist is the 184
and that one. The spec grew (additions only), so the golden was re-recorded with
`harness/oracle.sh record`; its diff is additions plus the one `total` line (184 → 185). The
slice-06 grader grows by that item for the recorded reason "spec grew"; no other approved
grader's scenarios changed. `probe-06.sh` and `prompt.clj` state no probe call, so neither
changed.

**2026-11-08: the spec grew: spec round 7d.** Shadow run 2 found the runner's gate failing a
candidate's own suite (`EACCES` inside its directory) because the gate room was read-only, where
legacy `verify.sh` runs the candidate in a writable tree, and the runner reading a `SKIP` line of
legacy `check.sh` by its first word. The product owner's spec round 7d (`.pi/spec/decisions.md`,
"Spec round 7d") made the gate room writable by the candidate's own run (the install digest the
copy's as taken) and the skipped check the whole rest of the line, and appended two stamped
minimum items at the end of slice 06's `spec.feature`: `06-037` and `06-038`; the derived
checklist is the 185 and those two. The spec grew (additions only), so the golden was
re-recorded with `harness/oracle.sh record`; its diff is additions plus the one `total` line
(185 → 187). The slice-06 grader grows by those items for the recorded reason "spec grew"; no
other approved grader's scenarios changed.

**2026-11-08: the spec grew: spec round 8.** The runner's gate spoke only legacy `verify.sh`'s
HTTP contract, so it could not gate a context of its own spec. The product owner's spec round 8
(`.pi/spec/decisions.md`, "Spec round 8") gave it the stdio contract `v2/gate.sh` applies
(`APP_SEAM=stdio`: the suite with no store, the slice's probe from the cut with `GATE_STORE`, then
the handed-in store check `LOYLY_STORE_CHECK_CMD` on that store), a gate room holding the clean
room, a plan slice that is a git symlink read at its target, and `PLAN_SLICES`; it appended ten
stamped minimum items at the end of slice 06's `spec.feature`: `06-039` to `06-048`; the derived
checklist is the 187 and those ten. The spec grew (additions only), so the golden was re-recorded
with `harness/oracle.sh record`; its diff is additions plus the one `total` line (187 → 197). The
slice-06 grader grows by those items for the recorded reason "spec grew"; no other approved
grader's scenarios changed. `prompt.clj`'s slice-06 list of handed-in values now names
`LOYLY_STORE_CHECK_CMD`, so it stays the seam's list; `probe-06.sh` is unchanged, because the
slice-06 identity probe asks nothing of the gate.

**2026-11-09: the spec grew: spec round 9.** The first self-hosted cycle (`selfhost-01`) ran its
Clojure builder in the TypeScript image and its runner scoreboard had no previous cycle. The
product owner's spec round 9 (`.pi/spec/decisions.md`, "Spec round 9") had the configuration name
the builder image (`BUILDER_IMAGE`, a folder of the handed-in `LOYLY_BUILDER_IMAGES`, handed to the
builder command as a third argument; `builder-image-missing` when it is not there) and gave
Measurement an optional history store (`LOYLY_MEASUREMENT_STORE`) kept across runs; it appended
three stamped minimum items at the end of slice 06's `spec.feature`: `06-049` to `06-051`; the
derived checklist is the 197 and those three. The spec grew (additions only), so the golden was
re-recorded with `harness/oracle.sh record`; its diff is additions plus the one `total` line
(197 → 200). The slice-06 grader grows by those items for the recorded reason "spec grew"; no
other approved grader's scenarios changed. `prompt.clj`'s slice-06 list of handed-in values now
names `LOYLY_BUILDER_IMAGES` and `LOYLY_MEASUREMENT_STORE`, so it stays the seam's list;
`probe-06.sh` is unchanged, because with `BUILDER_IMAGE` and the history store absent the runner
behaves as before.

**2026-11-09: self-hosting stopped.** The product owner stopped the harness rebuilding its own
contexts (`.pi/spec/decisions.md`, "Self-hosting stopped"). The harness builds Löyly: runner
cycle 05 in `harness-impl/` gates over HTTP when `APP_SEAM` is unset, and its stdio gate stays
built in but unused. Spec round 10 was not applied; nothing in the spec, the golden or any grader
changed. The next work is Löyly cycles.

**2026-11-09: the spec grew: spec round 11.** Löyly run-01 failed under runner cycle 05,
`identity-probe-failed` on all three attempts: the runner ran the http identity probe from a copy
outside the gated tree, where Löyly's `harness/probe.sh` could not find the `harness/config.sh`
beside it. The product owner's spec round 11 (`.pi/spec/decisions.md`, "Spec round 11"), "as close
to legacy", made the http gate room a copy of the gated tree (the clean room's files plus the
candidate at `APP_DIR`), with the probe run as legacy `verify.sh` runs it, `"$ROOT/$APP_PROBE"
"$URL"` with `ROOT` the gate room; it wrote down how a relayed handoff's instants travel; and it
appended one stamped minimum item at the end of slice 06's `spec.feature`: `06-052`; the derived
checklist is the 200 and that one. The spec grew (additions only), so the golden was re-recorded
with `harness/oracle.sh record`; its diff is additions plus the one `total` line (200 → 201). The
slice-06 grader grows by that item for the recorded reason "spec grew"; no other approved grader's
scenarios changed. `prompt.clj` and `probe-06.sh` are unchanged: there is no new handed-in value,
and the slice-06 probe asks nothing of where the http probe runs.

**2026-11-12: the spec grew: spec round 12.** The product owner's spec round 12
(`.pi/spec/decisions.md`, "Spec round 12") lets a handed-in gate command (`LOYLY_GATE_CMD`) run the
gate, so Halla Health can gate in a Gondolin VM with its world, and writes a fence plan as
configuration. It appended five stamped minimum items at the end of slice 06's `spec.feature`:
`06-053` to `06-057`. The spec grew (additions only), so the golden was re-recorded with
`harness/oracle.sh record`; its diff is additions plus the one `total` line (201 → 206). The
slice-06 grader grows by those items for the recorded reason "spec grew". `harness/loyly-run`
(added in `c8d4384`) is declared `LOCAL` in `harness/modules.sh`.
