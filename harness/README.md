# The legacy harness tools, measuring the Pi/ORC harness

These are the legacy fence, gate, and instrument tools from
[`archive/legacy-harness/`](../archive/legacy-harness/README.md), adopted here to regenerate
and measure the Pi/ORC harness itself. The product they measure is the harness; its spec is
spec v2, `.pi/plan/NN-context/spec.feature` (`PLAN_DIR` in `config.sh`), each `.pi/plan/NN-context`
a symlink to `../spec/<context>`. ORC runs the cycle and calls
these tools from its stages: fence (`fence.sh`), regenerate (a Pi builder session), gate
(`verify.sh`), score (the sealed grader and `score.clj`), and scoreboard (`scoreboard.clj`).
The archive stays untouched as the parity reference.

## First light: one context per cycle (`v2/`)

Spec v2 is rebuilt one context at a time (`.pi/spec/decisions.md`, "First light"). `SLICE=NN`
in the environment switches `config.sh` to that context: `APP_DIR` is `harness-impl/NN-context`,
`APP_PROBE` is `v2/probe-NN.sh`, and `FENCED_PATHS` also hides spec v1, every other context, the
legacy driver itself (the earlier answer of every context) and `v2/`. The shared module files
are unchanged. One cycle, by hand:

```bash
SLICE=01 harness/fence.sh ROOM                  # the room, proved
bb harness/v2/prompt.clj 01 OUT                 # OUT/system.md and OUT/prompt.md, outside the room
(cd ROOM && pi --append-system-prompt OUT/system.md @OUT/prompt.md)   # a person runs the builder
harness/v2/gate.sh 01 ROOM                      # the gate, from this checkout
```

The room holds no instrument, so the gate runs here: `CANDIDATE_ROOM=ROOM` points `APP_DIR` at
the room's candidate, `verify.sh` runs unchanged (with `v2/probe-01.sh`), and then
`v2/event-store-check.sh` reads the Grain store the gate handed the candidate
(`LOYLY_GRAIN_STORE`) directly, ORC's workflow runs included: ORC runs on that store, and each
event a command produced must lie inside a completed run of its workflow. `v2/event-store-check-test.sh` judges that check on hand-made
stores.

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

After the gate, `harness/v2/score.sh 01 ROOM` scores the cycle: the sealed graders approved for
spec v2 in `v2/graders.approved.json` (never v1's `graders.approved.json`, whose `01-005`
is a v1 scenario) run against `ROOM/harness-impl/01-specification/start.sh`, and the legacy
`score.clj` and `scoreboard.clj` file and report into `v2/cycles/01/MM/`, cycle `MM` of slice
01. With no approved
grader covering the slice it refuses: "score refused: no approved sealed grader". The grader
of slice 01, `v2/graders/grade_specification.exs`, was authored from the spec alone,
calibrated by `v2/graders/calibration/calibrate.sh` (a reference stub passes all 15 items,
each mutant fails the item it breaks), and sealed; `v2/graders.pending.json` asks the
operator to approve its digest. The grader of slice 02, `v2/graders/grade_measurement.exs`,
was written the same way: it hands the consumed handoffs in through their seam methods,
offers its own reading of its fixture spec files to `measurement/derive-checklist`, and is
calibrated by `v2/graders/calibration-measurement/calibrate.sh` (a reference stub passes
all 24 items, each mutant fails the item it breaks); `v2/graders.pending.json` asks for its
digest too. The grader of slice 03, `v2/graders/grade_grader_lifecycle.exs`, hands its
candidate the current scenario digests in the `LOYLY_SCENARIO_DIGESTS` file after the
candidate first answers, and is calibrated by `v2/graders/calibration-grader-lifecycle/calibrate.sh`
(a reference stub passes all 4 items, each mutant fails the item it breaks);
`v2/graders.pending.json` asks for its digest as well. Until the operator approves a slice's
grader, its cycle ends at the gate.

**Where a spec-v2 cycle is filed (2026-11-07).** Each slice numbers its own cycles under
`v2/cycles/NN/` (`01`, `02`, ...), never under `harness/cycles/`. `oracle.sh`, a shared module
file, reads `harness/cycles/01` as this repository's recorded cycle 01 and judges it against
`oracle/scoreboard-01.golden`, recorded from spec v1's cycle 01; the first v2 score filed as
`harness/cycles/01` would have collided with that and turned the oracle red with no instrument
defect, and moving the golden needs a recorded reason this is not. `score.clj` and
`scoreboard.clj` find `harness/cycles` from their own location, not from an argument, so
`v2/score.sh` runs unchanged copies of them in its scratch root, whose `harness/cycles` is a
link to `v2/cycles/NN`; no shared module file changed and no golden moved. One root per slice
also makes the legacy scoreboard's "previous cycle" (the number minus one) the last cycle of
the same selection, the slice, as `.pi/spec/decisions.md` g asks.

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

## The goldens, and why they moved

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
