# Decisions

The record of the product owner's decisions on spec v2, in the order they were taken
(2026-11-06 to 2026-11-08). Where two sections disagree, the later one wins; an italic
*Later:* note points from an earlier entry to the decision that superseded or settled it.

| Section | Date | In short |
|---|---|---|
| Hot spots; Questions raised by R1; Legacy behaviours | 2026-11-06 | R1 checked against the legacy harness |
| Questions raised by R2 | 2026-11-06 | budget, gate refusals, self-check key, reworded golden, empty title, same selection |
| Calls after R3 | 2026-11-06 | gate refusals as an event, unit level, structural obligations |
| First light | 2026-11-06 | one context per cycle with the legacy tools |
| Slice 02 to Slice 05 | 2026-11-07 | each context built alone, with its seam |
| Spec rounds 1 to 6 | 2026-11-07 | fixes and acceptances after the slice 01 to 05 cycles; round 5 the assembly as a relay |
| Slice 06 (draft); Spec round 7b | 2026-11-07 | the cycle runner drafted, its questions decided, accepted as slice 06 |
| Spec rounds 7c, 7d | 2026-11-08 | the shadow runs' gate gaps |
| The switch to the new harness | 2026-11-08 | the new harness runs the regeneration cycles; legacy is the reference |
| Spec round 8 | 2026-11-08 | the runner gains the stdio gate contract, to gate the harness's own contexts |
| Spec rounds 8b, 9 | 2026-11-09 | the self-check stands after a refusal; the builder image per stack and Measurement's history |

**How spec-v2 cycles are scored.** From 2026-11-07 (after Spec round 2), spec-v2 cycles are
scored the legacy way, the legacy README's step 5: a black-box grading agent drives the
candidate over its seam and writes a verdicts file, which `harness/v2/score.sh --verdicts`
files (`harness/README.md`). The Elixir graders, `graders.json` and their calibration are
retired, and the open grader run by ORC was reverted; its drafts are parked (OG1 = no, Spec
round 3, 4). Where a later round says a slice's grader "grows" or "stands", read: the grading
agent grades the grown checklist, and nothing is sealed or approved for it.

Decided by the product owner on 2026-11-06, after R1 was checked against the legacy harness
(`archive/legacy-harness/`). Each line names the hot spot or question it settles.

## Hot spots

- **H1** A recorded fact is the truth. A gate attempt counts once it is recorded, so a replay spends none.
- **H5** The gate-attempt limit (3) is part of the preparation's approved budget, with the maximum outer cycles, a timeout per stage and at most 3 continuations.
- **H6** Gate evidence and score evidence live in separate stores; only a repair room mounts gate evidence; score evidence never reaches a builder room. ORC's room policy enforces it; a fence-like check proves it.
- **H7** Each item verdict carries a `reason` beside it (unreached, inconclusive, outage); the verdict stays pass or fail. Legacy put the reason in the evidence text.
- **H8** A cycle is numbered at submission. A cycle that fails the gate exists and has no score.
- **H10** Whole-plan submission is the selection's approval (the legacy shape); it never skips the grader-digest review.
- **H13** The score binds the gated candidate's digest and re-checks the grader digest when it is filed.
- **H24** A self-check runs before the fence and again before the gate, as the legacy harness does; a passing one is never reused across a change of harness or golden digests.
- **H25** The scoring tools are fenced from the clean room (AGENTS.md: no grading harness in the room; saunavuoro's choice). The legacy left this open.
- **H26** Duplicate verdicts: the last wins, and the superseded ones are listed.

## Questions raised by R1

- **b** An identity probe or seam that answers before boot is an infrastructure fault: the gate refuses without grading and spends no attempt (legacy `verify.sh`).
- **g** The scoreboard compares with the last measured cycle of the same selection (legacy: cycle n−1, which made a cycle after an unscored one look like a baseline).
- **h** A golden carries the digest of the scenario it was written from.

## Legacy behaviours: changes accepted

Kept as changed: ids numbered per slice from a ledger that never goes down; a bad verdict value
stops the score before an unknown item does; superseded duplicates listed; a refused score is
never retaken within the cycle; the filed record joins the checklist by its digest; goldens are
written from the scenario; every unstamped file is listed; skipped slices are stored; 3 gate
attempts plus a repair builder; a fence failure fails the cycle.

Reverted to the legacy rule: a seam already answering is not a graded attempt (b); the
self-check runs again before the gate (H24).

## Legacy behaviours: to add in R2

1. Which Gherkin lines are scenarios and items (Outline yes, Template no, tag runs, empty vs whitespace title).
2. The stamper's "no spec found", distinct from "fully stamped".
3. A gate refusal for a missing identity probe.
4. The identity probe is the instrument's, out of the builder's reach.
5. The boot clock starts at launch, so building inside `start.sh` counts against the budget.
6. Scoreboard rates: one decimal, half to even; a zero denominator reports "n/a".
7. The self-check reports a missing tool as a loud skip, not a failure; a golden is required where its cycle exists.
8. Config completeness and the sibling-module check: carried or dropped with a recorded reason.

## Questions raised by R2 (decided 2026-11-06)

1. **Budget exhausted** (outer cycles, a stage timeout, continuations): the cycle fails with reason `budget`, the same shape as the gate-attempt limit.
2. **Gate refusals** spend no attempt; they are bounded by the gate stage's timeout, and three consecutive infrastructure refusals fail the cycle with reason `infrastructure`.
3. **A gate that passed without exercising the product** is the score's to catch, not the gate's (legacy README: the gate proves the suite green and says nothing about whether the suite exercises the product).
4. **The self-check key** covers the approved grader digests; the capture policy waits for runtime evidence.
5. **A golden whose scenario was reworded** fails the self-check, as legacy's oracle fails on any difference; the fix is to re-record it with a logged reason.
6. **An empty-title heading** is not an item (legacy @harness-021); the stamper reports it loudly.
7. **The same selection** means the same selected slices, by name, across cuts; whole-plan cycles compare with the last measured whole-plan cycle.

## Calls after R3 (decided 2026-11-06)

1. **Gate refusals are recorded as the event `gate-attempt-refused`.** It is a fact about the
   room, needed to count three consecutive infrastructure refusals (R2 question 2). This is
   an exception to "refusals are `requires`, not events", and stated as one: the refused
   `record-gate-attempt` still records nothing; ORC then records the refusal with
   `record-gate-refusal`, which spends no attempt.
2. **The unit-level rule is narrower than Halla Health's.** A status, stage or path refusal
   is unit level only on the rule's own aggregate (a concluded cycle refusing a gate verdict
   is behaviour); an `exists`/`not exists` pair is unit level only between sibling rules
   with the same trigger. A refusal that an earlier `requires` already makes unreachable is
   set aside only through a checked `guarded by: <Rule>.<n>` annotation
   (`tools/spec/v2/coverage.py`).
3. **Structural obligations are not drafted as scenarios.** A guarantee or contract
   invariant that a command cannot show says "Verified by: gate <name>", naming a gate of
   `tools/spec/v2` or a harness check; one that cannot exist yet says "(planned)" and is an
   open item of its context.

## First light (decided 2026-11-06)

How the first regeneration cycles of spec v2 run, with the legacy driver (`harness/`), before
the harness can rebuild itself.

1. **One context per cycle.** A cycle rebuilds ONE context from scratch, in a clean room that
   holds only that context's spec (`.pi/spec/<context>/`) plus the handoff contracts it
   consumes (the producer's `event-model/handoffs.edn`), no other spec and no earlier candidate.
   Each context has its own candidate, `harness-impl/NN-<context>/`. A context becomes a slice
   when it is accepted: `.pi/plan/NN-<context>` is a relative symlink to `../spec/<context>`,
   the legacy tools' `PLAN_DIR`, so they find it as slice `NN`, and `tools/spec/v2/stamp.clj`
   takes the slice number from the link. `SLICE=NN` selects the cycle (`harness/config.sh`).
2. **The builder is a person-run `pi` session** in the room, given the builder instructions
   `harness/v2/prompt.clj` renders (the legacy way). The durable Pi builder host is not
   needed for this and is not built. *Later:* since the switch the cycle runner launches `pi`
   with its Gondolin backend ("The switch to the new harness" 6).
3. **Architecture inside a context is enforced by the stack and by the gate.** The candidate
   is Clojure on ORC with Grain; it registers the room's event model as given, and Grain's
   boot guard refuses drift between the model and the running blocks. After the suite and
   the identity probe, the gate reads the Grain store it handed the candidate directly, not
   through the candidate's seam (`harness/v2/event-store-check.sh`): it holds events, every
   event type is an event of the slice's event model, and every event's fields are exactly
   its schema. Grain's own bookkeeping (`grain/*`, such as the `grain/tx` its appender
   writes) is the stack's and is listed, not refused. Each context's seam is its
   `seam.md`; so that a context's model validates on its own, a flow stays inside its
   context (`stamping-to-checklist` moved from Specification to Measurement).
4. **ORC use is verified through the store.** ORC runs on the Grain store the candidate is
   handed, not a store of its own, so the gate can see it: each command runs as the ORC
   workflow named after its flow, and the event-store check requires every event a command
   produced to lie inside a run of that workflow that completed with success. ORC's own
   events (`sheet/*`, `rlm/*`, `intervention/*`) are listed, not refused. A candidate whose
   ORC keeps its own store fails the gate: the gate cannot tell ORC was used.
5. **Assembly later.** A gate that wires the contexts together through their handoffs comes
   later, not now. *Later:* the assembly gate came (`harness/v2/assemble.sh`), and the assembly
   is the relay ("Spec round 5" 1).

## Slice 02: measurement (decided 2026-11-07)

How the second context, `measurement`, is built alone, in the shape of "First light". Taken while
preparing its cycle; each is a reading of the spec, not a change of behaviour.

1. **Accepted.** The 24 drafts that cite legacy behaviour and are tier minimum moved into
   `measurement/spec.feature` (`@item-02-001` to `@item-02-024`); `.pi/plan/02-measurement`
   makes it slice 02. Its 8 new minimum drafts (no `# Legacy:` line) and the balance drafts stay
   drafts.
2. **Its model validates alone with the handoffs it consumes.** `tools/spec/v2/grain-validate.clj`
   now also validates each accepted context's event model alone, plus only the handoff events
   it names, taken from each producer's `event-model/handoffs.edn` (Grain strict: 0 errors, 0
   warnings). Measurement needed no cut for that; specification still validates alone.
3. **A flow per command, inside the context.** `file-item-verdicts`, `file-score` and
   `fail-score-stage` belonged to no flow of measurement (only to the Regeneration cycle's
   `fence-to-scoreboard`), so they had no ORC workflow of their own context. The flow
   `measurement/scoring` (from `regeneration-cycle/gate-attempt-passed` to the filed
   measurement or the failed score stage) holds them; each command runs as the workflow named
   after its flow (`seam.md`). The producers' flows (`cycle-preparation/cut-to-fence`,
   `regeneration-cycle/fence-to-scoreboard`) still show their steps into measurement; they are cut, as
   specification's were, when those contexts become slices and must validate alone.
4. **Consumed handoffs are handed in through the seam.** In a cycle of one context the
   producers are stubbed from their contracts: the room holds each producer's
   `event-model/handoffs.edn` and nothing else of it (`harness/config.sh` finds them in the
   context's event model). The candidate's seam has one method per consumed event, named as the
   event, its params the contract's schema; the candidate appends exactly that event to its Grain
   store, outside any ORC run, and its read models fold it. A grader drives everything over the
   seam, from a fresh store per item, and the gate's event-store check allows exactly the
   consumed handoff types, each held to its contract's schema and never counted as the
   candidate's own; any other foreign type is refused. Rejected: the gate writing the producers'
   events into the store before the candidate starts (a grader in Elixir cannot write Grain's
   store, and a candidate reading its store only at boot would miss what is appended later).
   When the contexts are assembled (`First light` 5), the producers' own events replace these
   methods. *Later:* the assembly is a relay that hands the producers' events in through these
   methods ("Spec round 5" 1); a shared store that would replace them is deferred (A1).
5. **The reading of the spec is the issuer's.** The event model's `derive-checklist` takes the
   reading of the cut's spec files as its params (`items`, `unstamped`, `skipped-slices`) and
   names no place to read a cut from, and `DeriveChecklist` has no `requires` that compares the
   offer with a file (unlike `StampSlice`, requires 4 and 5). So the issuer reads with
   Specification's one reading of Gherkin (guarantee `OneReadingOfTheSpec` of the surface
   `InstrumentDesk`) and the candidate records what it is offered, in the offered order, never
   sorted and never rewritten; `ScenarioReading` is not in the room. A scenario's "slice 03's spec
   file holds ..." is the grader's fixture, read the one way. If the product owner wants the
   candidate to read the cut itself, the command needs a place to read it from and a refusal for
   an offer that differs; that is a change of the spec, not of the seam.
6. **Cycles are filed per slice.** A spec-v2 cycle is filed under `harness/v2/cycles/NN/MM`, never
   `harness/cycles/`, whose `01` the legacy harness's own check (`harness/oracle.sh`) reads as spec v1's recorded cycle
   (`harness/README.md`, "Where a spec-v2 cycle is filed"). The legacy scoreboard's previous
   cycle is then the slice's previous cycle, the same selection (g).

## Slice 03: grader-lifecycle (decided 2026-11-07)

How the third context, `grader-lifecycle`, is built alone, in the shape of "First light" and
"Slice 02". Taken while preparing its cycle; each is a reading of the spec, not a change of
behaviour.

1. **Accepted.** The 4 drafts that cite legacy behaviour and are tier minimum moved into
   `grader-lifecycle/spec.feature` (`@item-03-001` to `@item-03-004`, all under the Rule of
   `MoveInstrumentGolden`); `.pi/plan/03-grader-lifecycle` makes it slice 03. Its 4 new minimum
   drafts (no `# Legacy:` line) and the 3 balance drafts stay drafts. No Scenario Outline, so
   no Examples table to lose.
2. **Its model validates alone.** The flow `golden-written-then-moved` ended in two steps of
   the Regeneration cycle (its read model `instrument-goldens` and its command
   `record-harness-self-check`): 3 dangling references when the context is validated alone. They
   are cut, as specification's were ("First light" 3); the Regeneration cycle's read model
   still consumes both golden events (`harness-self-check.edn`), so nothing of the handoff is
   lost. Grader lifecycle consumes no handoff: the room holds no other context's file.
3. **The scenario digests are handed in.** Both rules compare the offered scenario digest with
   `digest_of_scenario(scenario)` (contract `ScenarioDigests`, which the surface fulfils), and
   the event model gives the candidate no place to read a scenario from. So the current
   scenario digests are the issuer's reading of the cut, as the checklist's is ("Slice 02" 5),
   handed to the candidate in a JSON file named by `LOYLY_SCENARIO_DIGESTS` (scenario as named
   in `scenario` → its current digest), read at every command so a reworded scenario shows;
   a scenario it does not name has no digest (`seam.md`). The candidate never computes a
   digest. Rejected: a seam method per scenario digest (no event or command of the model
   carries one, and the gate's store check would refuse it), and the candidate hashing the
   scenario's text (the cut's text is not in the room, and a grader could not offer the
   scenarios' digests "s-12"). If the product owner wants the candidate to read the cut itself,
   the commands need a place to read it from; that is a change of the spec, not of the seam.
4. **A reason outside the three is a schema mismatch.** `@item-03-004` ("never moved for a
   reason outside the three") has no `requires`: the enumeration `MoveReason` is the event
   model's `:enum`, so the move is heard as `-32602` and records nothing. An empty note is the
   refusal `empty-note` (`MoveInstrumentGolden` requires 4), not `-32602`, although the schema's
   `{:min 1}` says the same: the Allium names it, and orders it.

## Slice 04: regeneration-cycle (decided 2026-11-07)

How the fourth context, `regeneration-cycle`, is built alone, in the shape of "First light",
"Slice 02" and "Slice 03". Taken while preparing its cycle; each is a reading of the spec, not a
change of behaviour.

1. **Accepted.** The 27 drafts that cite legacy behaviour and are tier minimum moved into
   `regeneration-cycle/spec.feature` (`@item-04-001` to `@item-04-027`) under their Rules;
   `.pi/plan/04-regeneration-cycle` makes it slice 04. Its 12 new minimum drafts (no `# Legacy:`
   line) and the balance and deferred drafts stay drafts (56). No accepted draft is a Scenario
   Outline; the two Outlines stay in `drafts.feature` with their `Examples:` tables.
2. **Its model validates alone with the handoffs it consumes.** Two flows ran through
   Measurement's blocks (20 dangling references alone): `fence-to-scoreboard` from the passed
   gate attempt through `measurement/gated-cycles`, `file-item-verdicts`, `file-score`,
   `measurements-to-report`, `report-scoreboard-once` and `report-scoreboard`, and
   `refused-score-fails-cycle` from `measurement/fail-score-stage`. Those steps are cut, as
   specification's and grader-lifecycle's were; Measurement's own flow `scoring` keeps its side.
   Here the passed gate attempt now folds into `regeneration-cycles`, and Measurement's facts
   are the handoffs this context consumes: `measurement-filed` and `scoreboard-reported` enter
   `fence-to-scoreboard` from nil, as a command ORC runtime issues does, and
   `score-stage-failed` starts `refused-score-fails-cycle`. `grain-validate.clj`: regeneration-
   cycle alone with its 6 handoffs, 0 errors, 0 warnings; slices 01 to 03 still valid; the
   merged model still valid.
3. **Consumed handoffs are handed in through the seam** ("Slice 02" 4): one method per consumed
   event, `cycle-preparation/source-cut-pinned`, `grader-lifecycle/instrument-golden-written`
   and `-moved`, `measurement/measurement-filed`, `score-stage-failed` and
   `scoreboard-reported`. The room keeps the three producers' `handoffs.edn` and nothing else of
   them. A fold whose rule does not take a handed-in event (a duplicate, a golden never written,
   an unknown cycle: each guarded upstream) ignores it.
4. **The candidate records the real world; it does not act on it.** Every command of this
   context is issued by ORC runtime from what one of its nodes did in a room, and the event
   model gives each command that result as params: the self-check run's outcome, failing check,
   skipped checks, missing and reworded goldens; the fence probe's commit counts and the paths
   it found; the gate room's boot budget, the poll that answered, whether the seam answered
   before boot, and the identity probe it held. So the contracts `HarnessSelfCheckRun`,
   `FenceProbe` and `GateRoom` describe results the issuer hands in, and the candidate records
   them and guards them; it runs no `git`, builds no room, runs no self-check and boots nothing.
   A grader plays ORC runtime. Read so, the scenarios that state a contract invariant
   (`@item-04-003` reproduces every golden, `@item-04-006` a golden with no cycle beside it,
   `@item-04-007` a missing tool is a loud skip, `@item-04-010` and `@item-04-014` the probe's
   paths, `@item-04-019` the boot clock starts at launch, `@item-04-022` the probe is the
   instrument's) are carried by recording the offered result faithfully and refusing what the
   `requires` refuse; what the boot clock covers (`@item-04-019`'s "a build inside the suite
   would not have counted") is the gate's own doing, and in first light that is still
   `harness/verify.sh`. Rejected: the candidate building and probing a clean room, running the
   self-check or booting a candidate itself (the event model has no command or param for any of
   them: a command records a node's result, "ORC runs, the harness records", `domain-design.md`
   1). The ORC nodes that act in rooms come with ORC's room policy and the builder host; if the
   product owner wants this context to own them, the model needs the commands and their
   issuers, a change of the spec, not of the seam.
5. **The goldens digest is the candidate's, by a stated formula.** `RecordHarnessSelfCheck`
   (requires 2) and `RecordGateAttempt` (requires 7) compare a goldens digest with
   `goldens_digest_of(CurrentGoldens)`, a function of `HarnessSelfCheckRun` the surface
   fulfils, over the goldens the candidate folds from the handed-in golden events. Unlike a
   scenario digest ("Slice 03" 3), everything it needs is in the candidate's store, so it
   computes it, and `seam.md` fixes the formula (SHA-256 over one `<golden-id> TAB <digest>` line
   per current golden, by golden id) so that a grader offers the same digest.
6. **The scoring tools are handed in.** `ProveCleanRoom` (requires 9) reads
   `config.scoring_tool_paths`, which each repository declares and the event model gives no
   place: `LOYLY_SCORING_TOOL_PATHS`, a JSON array, read at start; unset, there is none.
7. **A fence with no fenced paths is a schema mismatch** (`@item-04-016`): invariant
   `AFenceHidesSomething` has no `requires`, and `fenced-paths` is `[:vector {:min 1} :string]`,
   so it is heard as `-32602` and records nothing, as a reason outside the three is in
   "Slice 03" 4.
8. **The policy and the clock, through the seam.** The conclude-cycle policy concludes a cycle
   before the method whose event made it due responds, as Measurement's policy does ("Slice
   02", `seam.md`); its events are read through `cycle-progress`. Each command runs as the ORC
   workflow of a flow that holds it, by its issuer (`seam.md` names one per issuer; the gate
   takes any flow holding the command). The stage clock runs on the wall clock at the model's
   30-second schedule and is observed through `cycle-progress`; no accepted scenario waits for
   it, and the identity probe's budgets outlast its run.
9. **"No score" is a failed cycle's status.** The view holds no item verdict; a failed cycle
   never had a measurement (`FailedBeforeScoreHasNoMeasurement`, and `FailCycle` never fails a
   cycle at the scoreboard), so `cycle-progress` shows its failure and nothing of a score.
10. **Room policy stays out.** `TheClaimantNeverControlsTheGate`, `ProbeOutOfReach` and
    Measurement's `PublicSeamOnly` are verified by the planned gate `room-policy`; they are not
    in this slice's builder scope, and no accepted scenario names them.

## Spec round 1 (decided 2026-11-07)

Three fixes the operator decided after the baseline cycle of slices 01 to 04. A spec round:
no builder ran, no item identity changed (`stamp.clj`: total stamped 0).

1. **`@item-02-002` holds a file the one reading allows.** It put stamped scenario 03-001 under
   no Rule after 03-002 under the Rule "Stamping" in one file, but under `ScenarioReading`
   (specification) a Rule runs to the end of its file, so no file holds that. Now the file
   holds 03-002 under no Rule first, then 03-001 under the Rule "Stamping" on line 6 with the
   title "  a padded title  "; the first stamped item is 03-002 with no Rule, the second 03-001
   with the Rule, source line 6 and the trimmed title. File order still differs from item
   identity, so the scenario still proves the items are never sorted. Its title is unchanged.
2. **`@item-02-018` drops "no command of the build loop reads it".** The step states a property
   of the build loop, not an outcome of reporting this scoreboard. The guarantee
   `ToAPersonNeverIntoTheLoop` (`scoreboard.allium`) already says "no command of the build
   loop reads it", unchanged; the scenario keeps its tag, since it still reports the
   scoreboard to the operator.
3. **`regeneration-cycle/cycles-to-conclude` consumes `harness-self-check-passed`.** The
   conclude-cycle policy decides "the last gate attempt its budget allows failed" and so needs
   the cycle's budget, which a cycle first carries on the self-check that opens it; the read
   model consumed only the failed self-check. "Refused three times in a row" needs nothing
   more: the gate attempt events it already consumes count and reset the refusals. No event
   schema, command, flow or scenario changed. Reported by the slice-04 builder.

Grader 02 (`sha256:3efb14e2…`) must be replaced, reason "wording replaced", for `02-002` and
`02-018`; the derived checklist of slice 02 keeps its 24 items, ids, titles and Rules (only the
source lines of `02-019` to `02-024` move up one). Slice 04's grader is unaffected (no scenario
changed), but slice 04 must be rebuilt from the changed read model.

## Spec round 2 (decided 2026-11-07)

The operator accepted the new minimum drafts of slices 02, 03 and 04 (tier minimum, no
`# Legacy:` line: measurement 8, grader-lifecycle 4, regeneration-cycle 12), and one model fix.
A spec round: no builder ran, no existing item identity, title or Rule changed.

1. **Accepted: 21 of the 24.** Each moved from `drafts.feature` into `spec.feature` under its
   Rule, unworded, `@draft` → `@minimum`, the `# Suggested tier` line dropped, its `@allium-*`
   tags kept, and stamped by `tools/spec/v2/stamp.clj`:
   - measurement 7, `@item-02-025` to `@item-02-031`: 02-025 to 02-028 under "The approved sealed
     grader files ...", 02-029 and 02-030 under "The score files one measurement ...", 02-031
     under the new Rule "A refused score fails the score stage, once, ...";
   - grader-lifecycle 3, `@item-03-005` to `@item-03-007`: 03-005 under the new Rule "An
     instrument golden is written from its scenario ...", 03-006 and 03-007 under "A golden
     moves only with one recorded reason ...";
   - regeneration-cycle 11, `@item-04-028` to `@item-04-038`: 04-028 under "No clean room is
     built over an unchecked instrument ...", 04-029 and 04-030 under the new Rule "The gate
     installs the regenerated candidate in a fresh gate room ORC chose", 04-031 and 04-032 under
     "The gate records one verdict ...", 04-033 to 04-035 under "A candidate is regenerated
     ...", 04-036 and 04-037 under "A cycle concludes once ...", 04-038 under the new Rule "The
     instrument's facts only conclude the cycle, ...", whose draft Rule, now empty, left
     `drafts.feature`. 04-035 is a Scenario Outline; its `Examples:` table (3 rows) moved with
     it.
   Where they sit in the file is item 6.
2. **Refused: 3, left in `drafts.feature`.** Each has a step that is not gradeable through its
   own context's seam: it states what another context or the room policy does, as
   `@item-02-018`'s dropped step did (Spec round 1, 2). Not reworded here; a later round may
   drop the step and accept the rest.
   - measurement "Score evidence never reaches a builder, and a failed score stage is not
     retaken": "no builder room of C-7 mounts the item verdicts or the score evidence".
     Measurement has no builder room; the Regeneration cycle refuses such a mount
     (`RecordRegeneratedCandidate` 9, `@item-04-035`).
   - grader-lifecycle "The instrument maintainer writes a golden from its scenario": "the
     harness self-check of the next cycle must reproduce it". The self-check is the
     Regeneration cycle's; grader lifecycle's seam cannot observe it.
   - regeneration-cycle "A failed attempt leaves gate evidence a repair builder may read, and
     never score evidence": "E-1 holds no item verdict and no score evidence" is the content of
     an evidence root, which no command records (`gate-attempt-failed` carries only the root,
     `candidate-regenerated` no mount), and its When ("the repair builder's room ... mounts its
     evidence") is no command of the seam. What is gradeable of it, only this cycle's gate
     evidence mounted, is `@item-04-035`.
3. **Readings of the accepted scenarios** (no seam change; a grader reads them so):
   `@item-04-030`'s gate attempt 4 is outside `attempt`'s `[:int {:min 1 :max 3}]`, so it is
   heard as `-32602` and records nothing, as in "Slice 04" 7. "Spent" (`@item-04-031`,
   `@item-04-032`) is an attempt with a recorded verdict, not the progress view's
   `gate-attempts` (candidates regenerated). `@item-04-033`'s builder report has no param, so
   nothing of it is recorded, and "a gate room the builder does not control", like
   `@item-04-029`'s "read-only", is the installation `install-candidate-in-gate-room` records
   ("Slice 04" 4). `@item-04-036`'s "no score is retaken" is the cycle's `failed` status
   ("Slice 04" 9). `@item-03-007`'s golden was written with the digest "g-40", the grader's
   fixture.
4. **`measurement/fail-score-stage` reads `measurement/gated-cycles`.** `FailScoreStage`
   requires 1 refuses a cycle that is not gated, which only `gated-cycles` (folding
   `regeneration-cycle/gate-attempt-passed`) knows; its `:reads` named only
   `cycle-measurements`. Now `#{:measurement/gated-cycles :measurement/cycle-measurements}`,
   as `file-item-verdicts` and `file-score` read. No event schema, flow or scenario changed.
5. **No seam changed.** Every accepted scenario is driven by methods and inputs `seam.md`
   already names.
6. **Appended at the end of each file, so the spec grew by additions only.** The legacy rule
   for "spec grew" (`archive/legacy-harness/README.md`, "The goldens, and the three reasons one
   can change") lets no existing item's id, name, rule or source line move. So the new
   scenarios are appended after each `spec.feature`'s last existing scenario, in id order, each
   group under a Rule header with the exact text of its Rule; where that Rule already sits
   higher in the file, its header is repeated at the end (consecutive scenarios of one Rule
   share one repeated header). `ScenarioReading` and the legacy `gen_checklist` take a
   scenario's Rule from the last `Rule:` line above it, so a repeated header gives each new
   item its own Rule and changes no existing item's. The checklist golden (not recorded here)
   grows 70 → 91: the 70 existing items byte-identical, 21 added, and the only removed line
   the old `total`.

Graders 02, 03 and 04 must grow by the new items, reason "spec grew" (additions only: no
existing scenario's wording changed), and slices 02 to 04 must be rebuilt (slice 02 also from
the changed `:reads`). The 21 new items are the next cycle's untuned holdout.

## Slice 05: cycle-preparation (decided 2026-11-07)

How the fifth context, `cycle-preparation`, is built alone, in the shape of "Slice 03". Taken
while preparing its cycle; each is a reading of the spec, not a change of behaviour.

1. **Accepted.** None of this context's drafts cites legacy behaviour (no `# Legacy:` line: the
   legacy harness pins no cut), so all are new. The 4 of tier minimum moved into
   `cycle-preparation/spec.feature` under the Rule "A source cut is one commit and its tree,
   pinned once and never changed", `@draft` → `@minimum`, the `# Suggested tier` line dropped,
   the `@allium-*` tags kept (Spec round 2, 1), and stamped `@item-05-001` to `@item-05-004`;
   `.pi/plan/05-cycle-preparation` makes it slice 05. The balance draft
   (`TreeContentIsTheCommits`) stays a draft, its When now carrying the tree digest the
   command's schema requires. No Scenario Outline.
2. **Full 40-hex commits.** The drafts quoted short commits ("4f2a9c1"), which the event
   model's `commit` (`[:re "^[0-9a-f]{40,64}$"]`) refuses as `-32602`, and "not-a-commit",
   which the schema refuses before `PinSourceCut` requires 2 is reached. The accepted
   scenarios quote full 40-hex commits, and the not-a-commit scenario a well-formed hex string
   the repository does not name ("deadbeef…"), so each is refused by the `requires` it names.
   The success and re-pin scenarios also read the cut back through the pinned source cuts
   (the query), in place of "the checklist derivation and the regeneration cycle can each bind
   to source cut K-3", which other contexts do and this seam cannot observe (Spec round 2, 2).
3. **Its model validates alone.** The flow `cut-to-fence` ran on into Measurement
   (`pinned-cuts`, `derive-checklist`, `checklist-derived`) and the Regeneration cycle
   (`pinned-cuts`, `record-harness-self-check`, `harness-self-check-passed`): 6 dangling
   references alone. They are cut, as specification's, grader-lifecycle's and
   regeneration-cycle's were; both consumers' read models still consume
   `source-cut-pinned`, so nothing of the handoff is lost. Cycle preparation consumes no
   handoff. `grain-validate.clj`: cycle-preparation alone, 0 errors, 0 warnings.
4. **The repository's facts are handed in.** `PinSourceCut` asks the contract
   `ProjectRepository` (`is_commit`, `tree_of`), and the event model gives the candidate no
   place to read a repository from. So, like the scenario digests ("Slice 03" 3), they are
   handed in: `LOYLY_PROJECT_COMMITS` names a JSON object mapping each commit to its tree
   digest, re-read at every command; a commit it does not name is not a commit; tree digests
   are opaque text ("t-81"), never computed (`seam.md`). Rejected: the candidate running `git`
   (the scenarios' repository is a fixture a grader states, not a repository in the room).

## Spec round 3 (decided 2026-11-07)

The operator accepted the triage package of the slice 01 to 05 cycles (OG1 = no): one spec gap
closed, three refused drafts of round 2 settled, readings recorded, and seam wording made
explicit. A spec round: no builder ran, no existing item identity, title or Rule changed.

1. **The Regeneration cycle's gap: a measurement fact is folded only where the cycle can take
   it.** The folds of Measurement's handoffs took any event for an existing cycle, so a
   scoreboard handed in with no measurement filed made a cycle due to pass, and a measurement
   filed after the cycle failed moved its stage to the scoreboard. Now
   (`regeneration-cycle.allium`): `NoteMeasurementFiled` and `NoteScoreStageFailed` requires 2,
   `cycle.status = running and cycle.stage = score`; `NoteScoreboardReported` requires 2,
   `cycle.status = running and cycle.measurement_filed`; `awaits_passing` is `status = running
   and measurement_filed and scoreboard_reported`; `PassCycle` requires 2 reads both flags.
   `cycles-to-conclude` now consumes `measurement/measurement-filed` (it needs the flag to know
   a cycle is due to pass); the read models' and the policy's descriptions and `seam.md`'s list
   of what a fold ignores say the same. No event schema, command or flow changed.
2. **Accepted: 4, appended at the end of each `spec.feature`** (Spec round 2, 6: additions
   only, a repeated Rule header where the Rule sits higher), tier minimum, stamped by
   `tools/spec/v2/stamp.clj`:
   - regeneration-cycle 2, under a repeated "A cycle concludes once, passed after its
     scoreboard or failed at a named stage for a named reason": `@item-04-039` "A scoreboard
     reported with no measurement filed does not pass the cycle" (`NoteScoreboardReported.2`,
     invariant `PassedOnlyMeasuredAndReported`) and `@item-04-040` "A measurement filed after
     the cycle failed leaves it where it failed" (`NoteMeasurementFiled.2`). The third new
     `requires`, `NoteScoreStageFailed.2`, is drafted (balance) in `drafts.feature`.
   - measurement 1, `@item-02-032` "Item verdicts after a failed score stage are refused"
     (`FileItemVerdicts.8`), round 2's refused "Score evidence never reaches a builder, and a
     failed score stage is not retaken" with its step "no builder room of C-7 mounts the item
     verdicts or the score evidence" dropped, under its Rule "A refused score fails the score
     stage, once, ...", already the file's last.
   - grader-lifecycle 1, `@item-03-008` "The instrument maintainer writes a golden from its
     scenario" (`WriteInstrumentGolden`, guarantee `WrittenFromTheScenarioNeverFromARun`), its
     step "the harness self-check of the next cycle must reproduce it" replaced by "the
     instrument goldens show G-score-unknown with the digest "g-40" and the scenario digest
     "s-12"" (the values of `@item-03-001`), under a repeated "An instrument golden is written
     from its scenario and carries that scenario's digest".
3. **Refused for good: the regeneration-cycle draft "A failed attempt leaves gate evidence a
   repair builder may read, and never score evidence"** (Spec round 2, 2), deleted. Its
   guarantee `GateEvidenceOnly` now says "Verified by: gate room-policy (planned)", and so does
   measurement's `NeverIntoTheLoop`; both name `RecordRegeneratedCandidate` requires 9
   (`@item-04-035`) as what the spec verifies of them, the mount. The planned `room-policy`
   check lists both (`regeneration-cycle/domain-design.md`, "Checks").
4. **OG1 is no.** The product Grader stays sealed, calibrated and approved (AGENTS.md, "The
   instrument"); legacy-style subagent grading is harness practice outside the spec. The 12
   open-grader drafts stay in `drafts.feature`, parked; OG2 and OG3 are moot while OG1 is no
   (`grader-lifecycle/domain-design.md`, `measurement/domain-design.md`).
5. **Readings of accepted scenarios** (no seam change; a grader reads them so):
   - `@item-02-026`'s "gate attempt 2 of cycle C-7 passed" implies gate attempt 1 failed.
   - `@item-02-027`'s cut's checklist holds the items the grader files, so requires 6
     (`unknown-item`) holds and requires 9 (`grader-not-approved`) is the refusal.
   - `@item-02-028`'s two bad reasons in one call are refused whole: one refusal
     `bad-verdict-reason`, its `data.item-ids` naming both 03-001 and 03-002.
   - `@item-03-007`'s "the scenario digest it was written with" is "s-12".
   - "C-7's measurement has no item verdicts filed" (`@item-02-026` to `@item-02-028`) is
     observed as `measurement/file-score` of C-7 refused `zero-verdicts` and
     `measurement/cycle-measurement` of C-7 returning `null`.
   - "A refusal records nothing" means no event of `event-model/` is appended; ORC's and
     Grain's own run events may still be written. Each context's `seam.md` says so, and that a
     `null` for a field that is not `[:maybe ...]` is `-32602`.
   - `@item-04-030` and `@item-04-032` require nothing of a gate attempt's `last-refusal`: their
     refusals are refused commands (`-32602`, Spec round 2, 3; `RecordGateAttempt` requires
     10), not a gate refusal; `last_refusal` is set only by `RecordGateRefusal`
     (`gate-attempt.allium`).
   - Slice 05: the commits file is re-read at every pin, and a re-pin with the same content is
     `cut-already-pinned`; both already specified (`cycle-preparation/seam.md`).
6. **Measurement wording.** `item-verdicts-filed`'s description and `seam.md`: a verdict offered
   without evidence is filed with empty evidence (`EvidenceDefaultsEmpty`). The surface
   `MeasurementReceipt` exposes `candidate_digest` only when filed. The surface also exposes
   `superseded`, which the read model `:measurement/cycle-measurements` does not hold; the read
   model is unchanged (slice 02 keeps its schema), and `seam.md` says a query row carries at
   least the read model's fields and a grader reads no others.
7. **Grader lifecycle wording.** The read model `instrument-goldens` described rows carrying
   "its scenario"; the surface `InstrumentGoldenDesk` exposes only the scenario digest, and so
   does the description now.

Graders 02, 03 and 04 must grow by the new items, reason "spec grew" (additions only). Slice 04
must be rebuilt from the changed folds and read model; slices 02 and 03 gain one item each
(their seams changed only in wording); slices 01 and 05 changed only in `seam.md` wording. The
checklist golden grows 95 → 99. The 4 new items are the next cycle's untuned holdout.

## Spec round 4 (decided 2026-11-07)

The operator accepted the triage package of the cycle 04 inbox (`harness/v2/cycles/02/04`,
`03/04` and `04/04`, `surprises.json`): wording and readings only. A spec round: no builder
ran, no accepted scenario's text or tag, no Allium `requires` or `ensures`, no event-model
schema and no golden changed.

1. **Round 3's guard stays; Measurement's handoffs are handed in in the producer's order.**
   `NoteScoreboardReported` keeps requires 2 (`@item-04-039`). The inbox (04) found that after
   an ignored scoreboard and a later measurement the cycle waits for a second
   `scoreboard-reported`. Reading: in the assembled system `scoreboard-reported` cannot precede
   `measurement-filed`. Both are Measurement's, in the same store; its rule `ReportScoreboard`
   requires 1 a filed measurement and 2 no scoreboard yet, and its policy
   `ReportScoreboardOnce` is triggered by `measurement-filed` and reports once
   (`measurement/allium/scoreboard.allium:44-61`, `measurement/event-model/scoreboard.edn:47-52`;
   legacy `@harness-043`, `archive/legacy-harness/scoreboard.clj`: no scoreboard without a
   filed score). The "needs a second scoreboard" path is a stub's artefact only.
   `regeneration-cycle/seam.md`, "The consumed handoffs, handed in", says so.
2. **A measured cycle at the scoreboard stage has no timeout.** The stage clock times out
   `fence`, `regenerate`, `gate` and `score` only; a scoreboard follows its measurement, and the
   legacy harness keeps no cycle state to time out. No change.
3. **Open, for the assembly gate: was a cycle that failed at the score stage measured?**
   *Later:* closed, leave it ("Spec round 5" 3). A
   measurement filed after the cycle failed at the score stage leaves the cycle failed
   (`NoteMeasurementFiled` requires 2, `@item-04-040`) but stays Measurement's record, and can
   be the next cycle's baseline (`last_measured_before`). Assembled, the two contexts may
   disagree on whether that cycle was measured. The candidate fix, a `cycle-failed` handoff to
   Measurement, is deferred. Recorded in the open questions of
   `regeneration-cycle/domain-design.md` and `measurement/domain-design.md`.
4. **Readings of accepted scenarios and the seams** (no scenario text changes; a grader reads
   them so):
   - Slice 04: "1 failed gate attempt" (`@item-04-018`, `@item-04-024`) is the attempt rows
     with `verdict` `failed`; the surface `CycleProgress` exposes `failed_attempts`, the read
     model does not, and no schema changes. The policies' to-do queries
     (`regeneration-cycle/cycles-to-conclude`, `measurement/measurements-to-report`) are
     observed empty after every method, since the policy runs before the method responds; both
     `seam.md` say so.
   - Slice 04, not requirements: Givens a contract invariant states and the seam cannot observe
     (a 30-second build, tools answering "unknown id") are the issuer's, read as "Slice 04" 4; a
     handed-in event a fold ignores is still appended and echoed (the seam already says so).
   - Slice 02: `@item-02-020`'s "C-6 failed at the gate" in Measurement alone is "no
     `gate-attempt-passed` handed in for C-6". `item_set_moved` is "added or dropped is
     non-empty", so 1 added and 1 dropped is restated (legacy `scoreboard.clj`,
     `@harness-047`); a comment on the contract `Comparison` in
     `measurement/allium/context.allium` says so.
   - Slice 02, not requirements: a padded title is stored verbatim (trimming is the issuer's
     reading, "Slice 02" 5); a whitespace-only title is accepted; `file-score` after a failed
     score stage with no filings refuses `zero-verdicts` (`FileScore` requires 2 before 4); a
     row field beyond the read model's (`detail`); a baseline's scoreboard leaving `shared` out.
   - Slice 03: `@item-03-001`'s "recorded with ... the scenario digest" is checked through the
     command's event result and the golden's current scenario digest (the read model's move
     entries carry no scenario digest). Not a requirement: with no digests file every write is
     refused; `grader-lifecycle/seam.md` says the context is inert without it.
   - Slice 05: already specified (round 3).
5. **The round 3 balance draft "A score stage failed before the gate passed is not the
   cycle's"** (`regeneration-cycle/drafts.feature`) gains the step "And cycles-to-conclude does
   not list cycle C-7" and a comment: it is reachable only through the stub (measurement
   `FailScoreStage` requires 1). It stays a draft.

No grader changes: no item was added, reworded or dropped, so every sealed grader stands and the
checklist golden stays at 99.

## Spec round 5 (decided 2026-11-07)

The operator decided the three gaps of the first assembly gate (`harness/v2/assemble.sh`,
commit 3f5249f, the five gated candidates of slices 01 to 05): wording and readings only. A
spec round: no builder ran, no accepted scenario's text or tag, no Allium `requires` or
`ensures`, no event-model schema and no golden changed.

1. **Gap 1: the assembly is the relay, for now (option B).** As in the legacy harness, whose
   tools pass files and share nothing else, each context keeps its own Grain store, and ORC
   shares that candidate's store and tenant; the contexts do not share one store. A handoff
   reaches a consumer only through the consumer's hand-in methods (each `seam.md`, "The
   consumed handoffs, handed in"), which the relay calls with the producer's event unchanged,
   in the producer's order. Every `seam.md` now says ORC shares this candidate's file and
   tenant, not one across contexts, and the hand-in methods say they are how the producers'
   facts reach a context assembled, replacing "until a gate assembles the contexts".
   Deferred, the end state (option A1), to be taken up when ORC hosts all contexts: one shared
   store with one fixed tenant id (such as `6c6f796c-7900-4000-8000-000000000000`) named in
   every `seam.md`; a consumer's read models project from that store, and its policies on
   foreign events poll it (Grain's `start-polling` or `start-tenant-poller`, every 250 ms).
   The evidence behind deferring it: the gate's shared-store experiment ran the five
   candidates on one store file, one after the other and concurrently; the file held five
   Grain tenants, one per candidate, and the consumers refused `cut-not-pinned` for a cut the
   producer had pinned in the same file. The Grain facts: a todo processor's `start` takes
   its events from Grain's pubsub inside its own JVM, so it never sees another candidate's
   appends; the polling variants read the store and do; read models re-read the store when
   queried.
2. **Gap 2: the relay's artefact is the producer's store.** The relay takes every handoff from
   the producer's store file: the events of the producer's handoff types
   (`event-model/handoffs.edn`), within the producer's tenant, in store order, not from
   command results. A handoff a policy issues, such as Measurement's
   `measurement/scoreboard-reported` (`report-scoreboard-once`, inside `file-score`), is
   recorded in the producer's store and is in no command's result; round 4's wording
   (`regeneration-cycle/seam.md`, "Measurement's handoffs are handed in in the producer's
   order") stands. No spec change.
3. **Gap 3, the round-4 open question, closed: leave it (option c).** A measurement filed after
   its cycle failed at the score stage stays Measurement's record and may be the next cycle's
   baseline (`last_measured_before`), while the Regeneration cycle keeps the cycle failed
   (`NoteMeasurementFiled` requires 2, `@item-04-040`). They settle different questions:
   Measurement's record says what was scored, the cycle's status how the cycle ended. Legacy
   precedent: `archive/legacy-harness/score.clj` files whatever was scored, and the legacy
   harness keeps no cycle state. Deferred: a `cycle-failed` handoff to Measurement. Closed in
   the open questions of `regeneration-cycle/domain-design.md` and
   `measurement/domain-design.md`; the assembly gate checks it as a reading.

No grader changes: no item was added, reworded or dropped, so every sealed grader stands and the
checklist golden stays at 99.

## Spec round 6 (decided 2026-11-07)

The operator accepted the triage of the remaining drafts ("Core 50"): 50 balance drafts of
slices 02, 03 and 04, nine of them reworded so that every step is observable through the
context's seam. A spec round: no builder ran, no accepted scenario's text or tag, no Allium
`requires` or `ensures`, no event-model schema and no seam changed.

1. **Accepted: 50, appended at the end of each `spec.feature`** (Spec round 2, 6: additions
   only, a repeated Rule header where the Rule sits higher), `@draft` → `@balance` (their
   suggested tier, as accepted minimum drafts carry `@minimum`), the `# Suggested tier` line
   dropped, the `@allium-*` tags and `# Legacy:` lines kept, stamped by
   `tools/spec/v2/stamp.clj`:
   - measurement 11, `@item-02-033` to `@item-02-043`: the refusals `DeriveChecklist` 1 and 5,
     `FileItemVerdicts` 1 and 7, `FileScore` 1, `FailScoreStage` 1 and 2; the invariants
     `OneChecklistPerCut`, `OneScoreboardPerCycle`, `ComparedWithinTheSelection`; the holdout
     (`NewItemsAreTheHoldout`).
   - grader-lifecycle 3, `@item-03-009` to `@item-03-011`: `OneGoldenPerIdentity`,
     `MoveInstrumentGolden` 1, `DigestChangesWithTheBehaviour`.
   - regeneration-cycle 36, `@item-04-041` to `@item-04-076`: `RecordHarnessSelfCheck` 1, 4,
     5, 7, 10 and `OneSelfCheckPerOccasion`; `OneCleanRoomPerCycle`, `ProveCleanRoom` 8 and 10;
     `InstallCandidateInGateRoom` 1, 2, 4; `RecordGateAttempt` 1, 3, 4, 5, 6, 11, 12; the gate
     refusals (`RecordGateRefusal` and its 1 to 3, three in a row fail for infrastructure, a
     verdict resets the count) under the new Rule "Gate refusals spend no attempt, and three in
     a row fail the cycle for infrastructure"; `RecordRegeneratedCandidate` 1, 3, 5 (a Scenario
     Outline, its `Examples:` table moved with it), 7, 8; `PassCycle` 2, `OneConclusion`,
     `FailCycle` 3 to 5; `ObservingChangesNothing` under the new Rule "The operator observes a
     cycle, and observing changes nothing", whose draft Rule, now empty, left `drafts.feature`.
2. **Reworded before acceptance** (each a step the seam cannot observe, or a Given the model
   cannot hold):
   - `@item-03-011`: the read model `instrument-goldens` holds no scenario (Spec round 3, 7), so
     "the scenario it was written from is still @item-03-004" is dropped; the reworded scenario's
     digest is named, "s-13", and the Then reads the golden's digest and scenario digest.
   - `@item-02-041`: "the measurement-filed fact of C-7 is delivered again" is no seam action
     (a context's own event has no hand-in method); now the scoreboard of C-7 is reported again
     and refused (`scoreboard-already-reported`).
   - `@item-02-042`: C-5 (slices 03 and 04) moved from source cut K-3 to K-4. A cut has one
     checklist (`OneChecklistPerCut`), and a cycle's selection is its cut's checklist's, so two
     selections at one cut cannot both hold.
   - `@item-04-043`: "cycle C-7's harness self-checks are unchanged" dropped: the harness
     self-checks have no query (`seam.md`).
   - `@item-04-044`: gains "the clean room of cycle C-7 was proved" (no candidate is regenerated
     before it, `@item-04-025`); its Then is the refusal and an unchanged progress view, in place
     of "exactly one harness self-check", which no query shows.
   - `@item-04-051` and `@item-04-052`: "C-7 has no gate attempt" became "gate attempt 1 of C-7
     is not installed": a regenerated candidate already has its attempt row, `installed?` false.
   - `@item-04-076`: "no stage fact is recorded" became "both readings are identical": a grader
     does not read the store.
3. **Readings of the accepted scenarios** (no seam change; a grader reads them so):
   - `@item-04-045`: a budget of 4 gate attempts is outside `gate-attempt-limit`'s
     `[:int {:min 1 :max 3}]`, so it is heard as `-32602` and records nothing, as in "Slice 04"
     7 and Spec round 2, 3; `RecordHarnessSelfCheck` requires 7 cannot be reached through the
     seam. "Refused" covers it.
   - "The conclude-cycle policy passes cycle C-7" (`@item-04-071`) is the method
     `regeneration-cycle/pass-cycle`, held to the policy's `requires`.
   - "Awaits its verdict" (`@item-04-054` to `@item-04-059`, `@item-04-060`) is an attempt row
     with no `verdict`; "C-7 has no gate attempt verdict" (`@item-04-053`) the same; "spent" is
     an attempt with a recorded verdict (Spec round 2, 3). "C-7 has no gate refusal" is
     `consecutive-gate-refusals` 0 and no `last-refusal`.
   - "Cycle C-7's clean room is still commit R-1" (`@item-04-047`) is the progress view's
     `clean-room-commit`; "nothing is recorded" there is the refusal `clean-room-already-proved`.
   - `@item-04-065`, `@item-04-068` (its second row) and `@item-04-069` need a budget of at least
     2 gate attempts, which their regenerated or refused attempt 2 implies.
   - Measurement: "no checklist exists for K-9" is `measurement/checklist` returning `null`;
     "C-8 has no measurement" is `measurement/cycle-measurement` returning `null`; "C-7 has one
     scoreboard" is `measurement/scoreboard` unchanged; `@item-02-043`'s new, dropped and shared
     items are the scoreboard's `new-items`, `dropped-items` and `shared`; `@item-02-042`'s
     previous selection is its `previous-selection-id`.
4. **Stay drafts** (one line each):
   - measurement "An item identity handed over twice is known once": no query shows
     `item-identities`; it needs a query, a seam change.
   - measurement "The measurement receipt shows every verdict and reason, …": its steps are
     `@item-02-013` to `@item-02-015`'s, and "the receipt shows the superseded verdict" needs
     `superseded` in `cycle-measurements`, a schema change.
   - regeneration-cycle "The clean room keeps the spec … loses every prior candidate": the
     room's content is the ORC fence node's doing; the candidate only records the probe
     ("Slice 04" 4).
   - regeneration-cycle's two `ConcludeOnStageTimeout` drafts and "The stage clock does not fail
     a cycle waiting at the scoreboard": the stage clock runs on the wall clock (30-second
     period), and a scenario in minutes cannot be graded; restated in seconds they cost a grader
     a wait per item.
   - regeneration-cycle "No harness policy starts a stage" (deferred): waits on the clock too.
   - regeneration-cycle "A passing harness self-check before a gate attempt opens no cycle",
     "Passing a cycle that never opened is refused" and "Failing a cycle that never opened is
     refused": tier deferred.
   - regeneration-cycle "A score stage failed before the gate passed is not the cycle's":
     reachable only through the stub (Spec round 4, 5).
   - specification (2) and cycle-preparation (1): deferred, so that slices 01 and 05 are not
     rebuilt for them.
   - The 12 open-grader drafts: parked while OG1 is no (Spec round 3, 4).

Graders 02, 03 and 04 must grow by the new items, reason "spec grew" (additions only: no
existing scenario's wording changed), and slices 02 to 04 must be rebuilt for them; slices 01
and 05 are unchanged. The checklist golden grows 99 → 149. The 50 new items are the next
cycle's untuned holdout.

## Slice 06 (draft): cycle-runner (spec round 7a, 2026-11-07)

Stage 1 of the switch: a sixth context, `cycle-runner`, the ORC workflow that performs a cycle's
effects in the real world and records each result into the context that owns it, through that
context's seam. Drafts only, for the product owner's review: no `spec.feature`, nothing stamped,
no `.pi/plan/06-*` link, no existing context changed. Decided by the product owner before drafting:

1. **The benchmark is the full Löyly app as planned** (the main working copy's `docs/plan`, unchanged;
   today 01-booking-core, 11 items), with the legacy settings of
   `archive/legacy-harness/config.sh` (`PORT=8901`, `PLAN_DIR=docs/plan`, `APP_MARKER=sauna`,
   `APP_PROBE=harness/probe.sh`, `FENCED_PATHS="impl harness/cycles"`), the probe kept as-is (a
   name check, for parity), the gate on the host for stage 1. *Later:* the fence changed
   ("Spec round 7b" 3, then "The switch to the new harness" 2).
2. **One builder per run**: the runner launches pi with its Gondolin backend and waits for it.
3. **The verdicts are handed in.** One legacy-step-5 grading subagent per candidate writes one
   verdicts file, handed to the runner (and to legacy `score.clj` in a shadow run); the main
   session judges `@item-01-011` of the Löyly app and hands that verdict in like the others. The
   sealed Löyly grader comes after the switch (OG1 = no stands for the product).
4. **No legacy reference cycle**: shadow runs compare live; the main session is the final judge
   of every comparison, subagents produce evidence packs, legacy is the reference.
5. **No shared store** (A1 stays deferred, Spec round 5): the runner is the relay, as
   `harness/v2/assemble.clj` is.

The draft (`cycle-runner/`): one aggregate `CycleRun`; the operator's `start-run` and
`hand-in-verdicts` are the only command methods, the processor `advance-run` issues
`record-effect`, `relay-handoff`, `conclude-run` and `halt-run` after it performed each effect;
the effects E1 to E8 of the triage are listed in `cycle-runner/seam.md` with the context method
each is recorded through; everything the runner acts on is handed in (`LOYLY_PROJECT_REPO`,
`LOYLY_HARNESS_CONFIG`, `LOYLY_CONTEXTS`, `LOYLY_SELF_CHECK_CMD`, `LOYLY_BUILDER_CMD`,
`LOYLY_BUILDER_INSTRUCTION`, `LOYLY_RUNS_DIR`), so a grader drives it black-box with fixtures, a
stub builder and the five gated candidates. 34 drafts cover its 40 must-cover obligations.

Open, for the product owner (`cycle-runner/domain-design.md`; *Later:* all six decided in "Spec
round 7b"): Q1 the already-stamped Löyly plan
against a fresh Specification ledger (Measurement refuses the checklist `unknown-item` as things
stand); Q2 `harness/cycles` does not exist at today's cut, so the legacy fence refuses
`fenced-path-matched-nothing`; Q3 the harness digest; Q4 synchronous methods against a builder
that takes hours; Q5 what the Löyly clean room holds beyond `docs/plan`; Q6 the scenario digest
formula.

## Spec round 7b (decided 2026-11-07)

The product owner decided the six open questions of "Slice 06 (draft)" and accepted the cycle
runner as slice 06: `.pi/plan/06-cycle-runner -> ../spec/cycle-runner`. A spec round: no builder
ran; no other context's `spec.feature`, Allium `requires` or event-model schema changed.

1. **Accepted: all 34 drafts, and one added** (item 2), in a new `cycle-runner/spec.feature`
   under the drafts' Rule headers, in the drafts' order, `@draft` → their suggested tier
   (`@minimum` 21, `@balance` 14), the `# Suggested tier` line dropped, the `@allium-*` tags and
   `# Legacy:` lines kept, stamped by `tools/spec/v2/stamp.clj`, `@item-06-001` to
   `@item-06-035`. Each is black-box gradeable through the runner's seam with what a grader
   states (`cycle-runner/seam.md`, "How a grader drives it without a live pi"): a fixture
   repository, a stub self-check, a stub builder and fixture candidates, a port it holds, the
   five gated candidates of slices 01 to 05 as `LOYLY_CONTEXTS`, and an entrypoint that exits at
   once or wraps a gated candidate and exits on one method. `drafts.feature` holds none.
   Reworded before acceptance:
   - `@item-06-008` "A checklist Measurement refuses halts the run with Measurement's refusal":
     its plan held a scenario with no item identity, which item 2 now halts at the stamp, so the
     checklist was never reached. Its plan now holds only a spec file with no scenario, which
     Measurement refuses `empty-checklist` (`DeriveChecklist` 3; AGENTS.md "an empty selection
     stops the cycle"); the run halts at the checklist effect.
   - `@item-06-034` "A run that ended stays as it ended": "its contexts were stopped" left the
     Given (no query shows it); "a minute later" became "5 seconds after each start responded"
     (a grader waits seconds, not minutes; Spec round 6, 4), and its Then reads the effect
     results and relayed handoffs against those when each start responded, which the seam
     shows, in place of "after it ended".
   - `@item-06-006`: "does not respond its seam" → "does not respond on its seam".
2. **Q1: restamp a stripped copy.** For each slice of the plan at the cut, the runner stamps a
   copy of the spec file with the `@item` tags of its tag lines removed, under a folder of the
   slice's own name, through `specification/stamp-slice` on Specification's fresh ledger; the
   identities the stamp hands out, in file order, must equal the file's own tags, else the run
   halts at the stamp effect, reason `refused-before-the-cycle`, after the stamp effect is
   recorded as Specification answered. No change to slice 01. New scenario `@item-06-009` "A
   plan whose item identities a fresh stamp does not give back halts the run before the cycle"
   (`@minimum`, `HaltRun`). A plan whose numbering has a gap halts too; the Löyly plan's
   `@item-01-001` to `011` has none. Allium: comments of `HaltReason` and `HaltRun`;
   event-model: `halt-run`'s description.
3. **Q2 and Q5: the Löyly benchmark has its own configuration**, the archived Löyly
   configuration (`archive/legacy-harness/config.sh`: `PORT=8901`, `PLAN_DIR=docs/plan`,
   `APP_MARKER=sauna`, `APP_PROBE=harness/probe.sh`) with `FENCED_PATHS="impl harness-impl .pi
   archive/legacy-harness/cycles"`: the paths that hold a prior candidate or evidence; `docs/`,
   `raw/` and the harness tools stay in the room. The archived value `"impl harness/cycles"`
   stops at the fence today (`fenced-path-matched-nothing`), because `harness/cycles` does not
   exist in the main working copy. The configuration is handed in (`LOYLY_HARNESS_CONFIG`), so
   the spec holds only its reading (`cycle-runner/seam.md`, "Handed in"). **Open, found while
   recording:** `harness-impl` is tracked in no branch today (the main working copy at `6d42c8e`
   has none), so at today's cut that entry stops the fence the same way; the value holds once
   `harness-impl` is committed at the cut, else that entry is dropped. No scenario depends on it.
   *Later:* dropped; the benchmark's fence is `FENCED_PATHS="impl .pi
   archive/legacy-harness/cycles"` ("The switch to the new harness" 2).
4. **Q3 and Q6: the drafted readings.** The harness digest of a self-check is the SHA-256 of the
   configuration file, the self-check command and the identity probe, in that order; the
   scenario digest is the runner's reading in `cycle-runner/seam.md` (Specification's
   `ScenarioReading` names none).
5. **Q4: synchronous methods.** `start-run` responds once the run awaits its verdicts, concluded
   or halted; `hand-in-verdicts` once it concluded or halted. The judge watches a long run
   through the runner's Grain store and the pi session directly, not through the seam.
6. **Who judges.** The main session is the final judge of shadow runs. Grading is shared
   black-box verdicts: one legacy-step-5 grading subagent per candidate writes one verdicts
   file, handed unchanged to both harnesses (this runner and legacy `score.clj`). `@item-01-011`
   of the Löyly app is judged by the main session, and its verdict handed in with the others.
7. **The builder's room and its own suite.** A slice-06 builder's clean room holds no other
   context's candidate; it holds the five contexts' `seam.md` and `event-model/` (the contracts
   the runner speaks) and nothing else of them (`harness/config.sh`). The candidate's own tests
   stand the five contexts in: programs named in the test's own `LOYLY_CONTEXTS` that speak
   each seam as the test scripts them and append the handoffs they produce to a Grain store of
   their own, which the relay reads as a real producer's (`cycle-runner/seam.md`, "How the
   candidate's own suite drives it without the five candidates"). The black-box grader uses the
   real gated candidates of slices 01 to 05.
8. **Seam wording.** A context responds when it replies to a query of its own seam with a response
   carrying the request's `id`; `contexts-not-responding` names every context not named or not
   responding in `data.contexts`; a context that stops responding later halts the run.
9. **Event schemas the gate can hold a candidate to.** The gate's event-store check closes every
   map of an event's schema, so a bare `:map` admits only `{}`. `run-started`'s `budget` now
   spells out `start-run`'s budget schema; `effect-recorded`'s `events` and `run-concluded`'s
   `cycle`, the contexts' replies carried verbatim, are `[:map {:closed false}]`. No field was
   added or removed.

A grader for slice 06 is written once from these 35 items ("spec grew": a new slice). The
checklist golden grows 149 → 184. The 35 new items are the next cycle's untuned holdout.

## Spec round 7c (decided 2026-11-08)

A spec round: no builder ran; no other context's `spec.feature`, Allium `requires` or
event-model schema changed; no existing scenario's text changed.

1. **The finding: shadow run 1** (its evidence kept at `.pi/harness/.state/shadow/run-01`).
   On the same candidate (one digest, three copies) legacy
   `verify.sh` passed and the runner's gate failed attempt 1 for `identity-probe-failed`, so the
   cycle failed at the gate (`gate-attempts-exhausted`, limit 1). Every earlier step agreed. The
   runner ran the identity probe as `harness/probe.sh 8901`; legacy `verify.sh` runs
   `"$ROOT/$APP_PROBE" "$URL"` with `URL="http://127.0.0.1:$PORT"`. The legacy probe runs
   `curl -s "$URL"`; handed `8901`, curl reaches nothing, so the probe reports the page lacks the
   marker. A replay with a logging curl confirmed it, and the legacy probe passed 100 of 100
   against the same candidate served on the host.
2. **The judge's decision (main session).** The gate stage disagreed; the new harness was wrong;
   the cause is a spec gap: `cycle-runner/seam.md` E5 said "legacy `verify.sh`'s order" and never
   stated the probe's argument, so a candidate built from the spec could pass its own suite and
   still call the probe with the port.
3. **The fix.** E5 now states legacy `verify.sh`'s arguments, not only its order: `URL` is
   `http://127.0.0.1:` followed by `PORT`; `test.sh` runs with no argument; `start.sh` with one,
   `PORT`, in the background, output discarded; the boot is done once an HTTP GET of `URL` returns a status below 400
   (`curl -sf`); the marker is matched ignoring case; the probe runs as `APP_PROBE URL`, never
   with the bare port, its printed line the reason; no configuration value is exported to any of
   them; the gate room is the working directory. `GateOnTheHost`'s `TheLegacyGateOrder` says the
   same. Checked while there, every other effect that cites a legacy tool: E3 (`fence.sh` as-is)
   left unstated its one argument (the clean room's path, which must not exist yet), the split of
   `FENCED_PATHS` on whitespace, what "found" means (present in the cloned tree), the reflog
   expiry and prune, the probe's expected counts (one root commit, one commit) and what
   "reachable" means (a path of `git rev-list --objects --all` equal to the entry without its
   trailing `/`, or beneath it); all now stated. E1's `gen_checklist` reading, E2's
   `check.sh --local` and E4's builder launch already state their arguments.
4. **One new scenario**, `@item-06-036` "The identity probe is handed the served URL, and passes
   a candidate that serves the marker" (`@minimum`, `TheLegacyGateOrder`), appended under a
   repeat of the gate's Rule header: a probe that passes only on the served URL and fails on a
   bare port is passed by the gate on a candidate that serves the marker.

The slice-06 grader grows by this one item ("spec grew", additions only). The checklist golden
grows 184 → 185. `@item-06-036` is the next cycle's untuned holdout.

## Spec round 7d (decided 2026-11-08)

A spec round: no builder ran; no other context's `spec.feature`, Allium `requires` or
event-model schema changed; no existing scenario's text changed.

1. **The findings: shadow run 2** (its evidence kept at `.pi/harness/.state/shadow/run-02`).
   The fence and the checklist agreed with legacy, as in shadow run 1. Two gaps remained in slice 06:
   - *The gate room was read-only.* On the same candidate legacy `verify.sh` passed (suite 16/16)
     and the runner's gate failed attempt 1 for `suite-red` (0/16): the candidate's own suite
     failed with `EACCES` on `mkdtemp` inside its directory, because `cycle-runner/seam.md` E5
     said the installed copy is "read-only". Legacy `verify.sh` runs the candidate's `test.sh`
     and `start.sh` in place, in a writable tree.
   - *SKIP lines were read by their first word.* E2 said "each line `SKIP <check>` a skipped
     check"; legacy `check.sh` prints `SKIP  the oracle cannot judge this tree: …` and
     `SKIP  <sibling> is declared in SIBLING_REPOS …`, so the runner recorded the skipped check
     as "the" or similar, not the check.
2. **The judge's decisions (main session).** The gate stage disagreed; the spec was wrong:
   "read-only" was meant to keep the builder out of the gate room, which "a copy … taken when
   the builder ended" already guarantees; it blocked the candidate's own run, which legacy runs
   writable. The SKIP reading is a spec gap: the skipped check is the rest of the line.
3. **The fix.** E5 and `GateOnTheHost` (`TheGateGradesACopy`, `TheLegacyGateOrder`): the gate
   room is a fresh copy owned by the gate, taken when the builder ended, out of the builder's
   reach, and writable by the candidate's own `test.sh` and `start.sh`; the candidate digest
   recorded at install is the copy's as taken, before the gate runs, so what the candidate's run
   writes does not change it. E2: the skipped check named by a line starting `SKIP` is the rest
   of the line after `SKIP` and its whitespace, trimmed. Checked while there, every other
   "read-only" in slice 06: E6, `TheBuilderSeesOnlyItsMounts`, the repair instruction and
   `@item-06-023` concern the repair builder's mounts of the previous candidate and the gate
   evidence, and stay read-only; no other statement makes the gate room read-only.
4. **Two new scenarios**, appended under repeats of their Rule headers, both `@minimum`:
   `@item-06-037` "A candidate whose suite writes inside its own directory passes the gate"
   (`TheGateGradesACopy`): its gate attempt passes and the install digest equals the regenerate
   digest; `@item-06-038` "A skipped check is recorded with the whole text after SKIP"
   (`RecordEffect`): legacy `check.sh`'s oracle SKIP line is recorded with its whole text.

The slice-06 grader grows by these two items ("spec grew", additions only). The checklist golden
grows 185 → 187. `@item-06-037` and `@item-06-038` are the next cycle's untuned holdout.

## The switch to the new harness (decided 2026-11-08)

Not a spec round: no `spec.feature`, Allium file or event-model schema changed. The record of
the switch and of what it leaves open.

1. **The rule (decided earlier by the operator).** Switch when the new harness does what the legacy
   harness did, on the same work with the same results: per stage on shared input (the fence
   room's contents, the checklist, the gate verdict on the same candidate, the per-item
   verdicts, the score, the scoreboard), in two consecutive shadow runs the main session, the
   final judge, accepts at every stage.
2. **The benchmark.** The full Löyly app: the main repository's `docs/plan` at `6d42c8e`,
   unchanged, 11 items. Both sides fence the same cut, `249a90b`: a scratch-only commit on
   `6d42c8e` whose `harness/` is the archived Löyly legacy harness with the benchmark
   configuration, `FENCED_PATHS="impl .pi archive/legacy-harness/cycles"`; `docs/` is the same
   as at `6d42c8e`. The new side is the slice-06 cycle-runner (the cycle 03 candidate) driving
   the five contexts of slices 01-05; the builder on both sides is the same `pi` command with
   its Gondolin backend on the existing Codex login, and one builder run per shadow run gives
   the one candidate both gates judge.
3. **The five shadow runs.** Each run's evidence is kept, not committed, at
   `.pi/harness/.state/shadow/run-0N` (N the run).

   | Run | Fence | Checklist | Gate | Verdicts and score | Scoreboard | Outcome |
   |---|---|---|---|---|---|---|
   | 1 | agree | agree | disagree: the probe was handed the port, not the URL | not reached | not reached | spec round 7c |
   | 2 | agree | agree | disagree: the read-only gate room blocked the candidate's own suite | not reached | not reached | spec round 7d |
   | 3 | agree | agree | agree (both pass) | not compared: the runner was stopped between gate and grading, and a run cannot resume in a restarted runner | not compared | procedure: keep the runner alive |
   | 4 | agree | agree | agree (both pass) | agree, 11/11 both sides | agree, both baseline | accepted at every stage |
   | 5 | agree | agree | agree (both pass) | agree, 11/11 both sides | agree, both baseline | accepted at every stage |

4. **Runs 4 and 5, the accepted pair.** Shared input, the same in both runs and checked by
   byte comparison: configuration `51288e81…` (equal to the cut's `harness/config.sh`), builder
   instruction `d72223df…`, contexts `debea8ae…`, cut `249a90b`, fenced tree `ce2a1ebd…` (98
   paths, both rooms one commit each, differing only in commit metadata).
   - *Checklist*: legacy `gen_checklist` and the derived checklist project to the same 11 items
     (`{id, title, rule, source}` digest `5cde9808…` on both sides, both runs); checklist digest
     `af764810…`.
   - *Gate*: run 4's candidate `sha256:756829d1…` (21 files), suite 15/15; run 5's candidate
     `sha256:11456ad8…` (30 files), suite 17/17. In each run both sides judged the same bytes:
     legacy `verify.sh` passed (`verify OK`) and the runner's gate passed attempt 1 ("suite
     green, booted on poll 2, identity proved"); the candidate's digest was unchanged after
     both gates.
   - *Verdicts and score*: one verdicts file handed to both sides (run 4 `bdb76215…`, run 5
     `14cf771b…`, 11 items, all pass); per-item verdicts and evidence equal on both sides,
     11/11; score 11/11 = 100.0% on both sides.
   - *Scoreboard*: both baseline (no previous cycle). The two differ only in form: legacy prints
     text; the measurement event records the rate, new and dropped items and nil previous ids.
   - *Cycle*: the run ended `passed`; the regeneration cycle concluded `cycle-passed` on the
     candidate gated; no run left to advance.

   The judge accepted both runs at every stage, and the operator confirmed the switch ("Switch").
5. **A judging note.** `@item-01-011` was judged by the main session from the refusal text alone
   in runs 3 to 5, with a conflict declared: it had seen a spec comment hinting at an
   alternative. Run 5's refusal named a different alternative from the one hinted, and passed.
6. **From now on.** The new harness runs the regeneration cycles: the slice-06 cycle-runner (the
   cycle 03 candidate) driving the five contexts of slices 01-05, the builder `pi` with its
   Gondolin backend on the existing Codex login. The legacy harness stays as the reference and
   is never deleted. Until a run can resume after a restart, the runner is kept alive from
   start to the filed score.
7. **Open, for later rounds.**
   - *Resume a run after a runner restart*: run 3 halted (`context-did-not-respond`) when its
     verdicts reached a restarted runner; deferred to the inbox. Still open after "Spec round 9".
   - *Room isolation and network egress as spec*: Gondolin enforces isolation for builders today;
     the spec does not yet state it, nor the glossary's "no network egress" for a builder room,
     which `selfhost-01`'s builder broke (it apk-installed and fetched dependencies). Still open
     after "Spec round 9".
   - *The repair's paths* (from "Spec round 9"): seam E6 names the previous candidate and the gate
     evidence as host paths; inside the VM they are elsewhere, and the builder found them by
     relative path. Optional wording: name them relative to the builder room.
   - *A sealed Löyly grader (OG1) and the product Grader*: the shadow runs' verdicts came from
     a grading session, not a sealed, approved digest.
   - *The spec-round inbox*: the surprises recorded in `cycles/0{2,3,4}/05` and
     `cycles/06/0{1,2,3}`; `record-gate-attempt`'s undeclared read of `instrument-goldens`;
     `@item-03-010` and `@item-03-011`, whose Whens omit a note the schema requires; the
     runner's self-check after a refusal against regeneration-cycle's one-per-attempt rule.
     *Later:* the last cleared in "Spec round 8b".
   - *Self-hosting* (2026-11-08): the cycle-runner's gate (`cycle-runner/seam.md` E5) is legacy
     `verify.sh`'s HTTP contract: `APP_DIR/test.sh`, `APP_DIR/start.sh PORT`,
     `http://127.0.0.1:PORT` answering an HTTP GET, the page carrying `APP_MARKER`, and the
     identity probe run as `APP_PROBE URL`. So the new harness regenerates HTTP apps that keep
     that contract, the Löyly app, and was proved on them (shadow runs 4 and 5). It cannot yet
     gate a context of its own spec: the six spec-v2 contexts are JSON-RPC services over stdio,
     gated by `harness/v2/gate.sh`: `harness/verify.sh` (the instrument, the whole suite, a
     fresh boot and the slice's stdio identity probe `harness/v2/probe-NN.sh`), then a direct
     read of the Grain store (`harness/v2/event-store-check.sh`): each event one of the slice's
     event model or a handoff it consumes, with exactly its fields; each command's events
     inside a completed run of its ORC workflow; one terminal completion per tick; processors
     checkpointed. Until the runner gains that second contract (a spec round on cycle-runner
     E5), rebuilding the harness's own contexts still uses `harness/v2/gate.sh` and
     `harness/v2/score.sh`. The coupling to Löyly beyond that is cosmetic: the `LOYLY_*` names.
     *Later:* specified in "Spec round 8"; until its build is cross-checked and promoted,
     `harness/v2/gate.sh` still gates them.

### The builder command the shadow runs used

Recorded 2026-11-08 from the shadow runs' evidence (`.pi/harness/.state/shadow/run-01` to
`run-05`, `evidence/inputs.txt` and `bin/builder.sh`); the same in all five runs. `LOYLY_BUILDER_CMD <builder-room>
<instruction-file>` ran `pi` 1.4.0 with its Gondolin extension, on the existing Codex login: the
`openai-codex` model at high thinking, skills and templates off, its session in the run's
builder session directory, the instruction file attached, and the builder room as its working
directory. The wrapper only recorded the launch and pi's output beside the run's evidence; it
changed nothing pi saw. The exact command, its environment and versions are in
[`../harness/README.md`](../harness/README.md), "The builder command".

## Spec round 8 (decided 2026-11-08)

A spec round on slice 06 only: no builder ran; no other context's `spec.feature`, Allium
`requires` or event-model schema changed; no existing scenario's text changed; the runner's event
model is unchanged (everything new travels in the handed-in configuration, the handed-in
commands and the fields `record-gate-attempt` already has).

1. **The goal: self-hosting** (AGENTS.md, "How the harness improves itself"; "The switch to the
   new harness" 7). The runner's gate spoke only legacy `verify.sh`'s HTTP contract, so it could
   not gate a context of its own spec. It gains a second contract, the one `harness/v2/gate.sh`
   applies to the six spec-v2 contexts (JSON-RPC over stdio, an identity probe over stdin and
   stdout, then a direct read of the Grain store), so the new harness can run a whole cycle on
   one of its own contexts: fence, builder, gate, grading, score, conclusion.
2. **Three blockers found while triaging**, besides the gate itself:
   - *E1 could not read a spec-v2 plan.* `.pi/plan/NN-<context>` is a symbolic link in git
     (mode `120000`), and `git show <commit>:.pi/plan/05-cycle-preparation/spec.feature` fails
     ("exists on disk, but not in 'HEAD'"). And E1 stamped and checklisted every slice of the
     plan: a context's cycle would carry all six slices' items, where `harness/v2/score.sh`
     derives one slice's.
   - *The gate room's contents were unstated.* E5 said only "a copy of the candidate". A spec-v2
     candidate loads its event model from the spec beside it (`../../.pi/spec/<context>/
     event-model/`, as its builder instruction says), and `harness/v2/gate.sh` gates it inside its room.
   - *Where the probe comes from was unstated.* `harness/v2/probe-NN.sh` is fenced from every
     spec-v2 clean room (`harness/config.sh`), so it can only come from the cut, never a room.
3. **Decided (D1 to D8), each the smallest option:**
   - *D1, the contract is selected by the configuration*: `APP_SEAM=http|stdio`, absent `http`,
     so the Löyly configuration and `@item-06-001` to `038` stand as written. For `stdio`,
     `PORT` and `APP_MARKER` are not read.
   - *D2, the store check is handed in*, `LOYLY_STORE_CHECK_CMD` (for the harness's own contexts
     `harness/v2/event-store-check.sh NN`), run in the project repository as the self-check is,
     the gate store's path appended. The runner does not restate the check's rules; they stay
     where `harness/v2/event-store-check-test.sh` tests them, and the cross-check of the new
     gate against `harness/v2/gate.sh` then compares one script's calls, not two
     checks. A store check that cannot be started halts the run (`effect-could-not-run`).
   - *D3, a failed store check is `identity-probe-failed`*, its output the detail: it reads only
     what the probe's candidate wrote, so it completes the identity check, and `gate.sh` itself
     fails every step with the same exit. Regeneration-cycle's reasons are unchanged.
   - *D4, the stdio boot budget is 30 seconds and one poll, answered on poll 1*: the probe holds
     the budget (`harness/verify.sh`), and its one run is the one poll. A poll derived from
     timing the probe could exceed the last poll (the probe's run includes stopping the
     candidate), which `RecordGateAttempt` refuses (5).
   - *D5, E1 reads a slice link at its target and `PLAN_SLICES` selects the slices* (absent or
     empty, every slice), rather than a scratch cut per cycle with a one-slice plan folder.
   - *D6, the stdio gate room is a copy of the clean room's files with the candidate at
     `APP_DIR`*; the candidate digest is still the scope's alone. The HTTP gate room is unchanged.
   - *D7, the builder instruction stays handed in*: rendered for slice NN by the instrument's
     renderer in `harness/v2`, its two files joined into the `LOYLY_BUILDER_INSTRUCTION` file, or
     passed by the `LOYLY_BUILDER_CMD` wrapper. No runner change.
   - *D8, the first self-hosted context is slice 05, cycle-preparation*: it consumes no handoff,
     its probe is the smallest, and it is not part of the instrument.
4. **The stdio gate** (`cycle-runner/seam.md` E5, `GateOnTheHost`'s `TheStdioGateOrder`,
   `TheStoreCheckReadsTheProbesStore`, `TheGateRoomHoldsTheCleanRoom`): `test.sh` and `start.sh`
   executable, else `contract-missing`; the probe present and executable at the cut, taken from
   the cut, else the refusal `identity-probe-missing`; the instrument checked by the self-check
   before the gate; no port; `test.sh` with no argument and no `LOYLY_*` variable in its
   environment, else `suite-red`; the probe as `APP_PROBE <gate-room>/APP_DIR/start.sh 30` with
   `GATE_STORE` naming a fresh gate store outside the gate room, else `identity-probe-failed`;
   the store check with that store's path, else `identity-probe-failed`. The harness digest of a
   stdio run covers the store-check command after the other three (`TheHarnessDigestCoversTheStoreCheck`).
   A cycle of one of the harness's own contexts is handed `harness/config.sh`'s values for the
   slice written out as `KEY=value` lines, with `APP_SEAM=stdio` and `PLAN_SLICES=NN`: the runner
   reads a configuration and never runs one.
5. **Ten new scenarios**, all `@minimum`, appended at the end of `cycle-runner/spec.feature` under
   a new Rule for the stdio gate and a repeat of the stamp Rule, stamped `@item-06-039` to
   `@item-06-048`: eight on the stdio gate (a pass; the probe's arguments and the store it and
   the store check share; a red suite handed no store; a failing probe; a failing store check; the
   spec beside the candidate in the gate room; a store check that cannot start; the harness
   digest), two on E1 (a slice link read at its target; `PLAN_SLICES`). Each is gradeable through
   the runner's seam with stub probes and a stub store check (`seam.md`, "How a grader drives
   it without a live pi"), so none needs a Grain store.
6. **Next, after the slice-06 build: one cross-check** (AGENTS.md: an instrument change is
   compared once with the previous instrument on the same candidates before it is used). The new
   runner's stdio gate (gate-attempt limit 1, a stub builder copying the candidate into its
   scope) and `harness/v2/gate.sh 05 ROOM` judge the same bytes:
   - C1, the promoted `harness-impl/05-cycle-preparation`: both pass;
   - C2, a copy whose `test.sh` exits 1: `suite-red` and "suite red";
   - C3, a copy without `start.sh`: `contract-missing` and the contract's FATAL;
   - C4, a copy whose `start.sh` never responds: `identity-probe-failed` and the probe's FATAL;
   - C5, a copy whose `start.sh` sends `LOYLY_GRAIN_STORE` elsewhere before starting: the probe
     passes and the store check fails on both sides ("no event store"), which shows the runner
     hands the probe's store to the store check;
   - optionally the promoted slices 04 and 06 (consumed handoffs; the heaviest probe).
   They must agree on the verdict, the failing step (each `gate.sh` FATAL against the reason
   recorded), the probe's line, the store check's OK or FAIL lines with a positive event count,
   the candidate digest before and after both gates, and the self-check with and without
   `SLICE`. `harness/verify.sh` checks the contract and the probe before the instrument, the
   runner self-checks first: keep the self-check green so only the candidate differs. The main
   session judges; only then does the first self-hosted cycle run, on slice 05.

The slice-06 grader grows by these ten items ("spec grew", additions only). The checklist golden
grows 187 → 197. `@item-06-039` to `@item-06-048` are the next cycle's untuned holdout.

## Spec round 8b (decided 2026-11-09)

A wording round on slice 06 only: no `spec.feature` text changed, no scenario added, no
Allium `requires` and no event-model schema changed, no other context touched. The golden
stays at 197.

1. **The regression.** Runner cycle 04 scored 47/48 (`harness/v2/cycles/06/04`): `@item-06-018`
   newly failing. Its gate was refused `seam-answered-before-boot`, the refusal was recorded,
   and the runner then self-checked attempt 1 again; Regeneration cycle refused that second
   self-check (`self-check-already-recorded`, `RecordHarnessSelfCheck` 3) and the run halted
   (`effect-could-not-run`) instead of gating again. Runner cycle 03 had met the same refusal,
   recorded it and carried on, so `@item-06-018` passed there.
2. **Five whys.** The run halted because the runner self-checked a second time. It did because
   `cycle-runner/seam.md` E5 said "the runner then self-checks and gates the same attempt
   again", while Regeneration cycle accepts one self-check per attempt: two spec statements
   that cannot both hold. The contradiction was known: cycles 01 and 02 reported it as a
   surprise, and "The switch to the new harness" 7 left it in the inbox. It stayed there
   because spec round 8 accepted new items without clearing it. And every cycle is a fresh
   builder, so an open contradiction is decided again each cycle: cycle 03's builder chose to
   carry on, cycle 04's to halt. Regeneration turns an ambiguity into a coin flip.
3. **Wording fix 1, the self-check stands** (`cycle-runner/seam.md` E5 `self-check` and
   `gate-refusal`; `GateOnTheHost`'s `TheLegacyGateOrder`): once a refusal to grade is
   recorded, the runner gates the same attempt again on the attempt's self-check, and does not
   self-check again. Regeneration cycle accepts that gate: `GateAttemptRefused` leaves the
   attempt `awaiting` (`RecordGateRefusal`), and `RecordGateAttempt` requires only that a
   passed self-check exists for the attempt at the same harness digest, goldens, identity
   probe and grader digests (`regeneration-cycle/allium/gate-attempt.allium`, requires 2, 7, 9
   and 12), all of which the standing self-check still meets.
4. **Wording fix 2, an inherited `GATE_STORE`** (`cycle-runner/seam.md` E5 `gate` (`stdio`);
   `TheStoreCheckReadsTheProbesStore`): a `GATE_STORE` the runner itself inherited is removed
   from the environment of `test.sh`, the probe and the store check, with every `LOYLY_*`
   variable; the attempt's own `GATE_STORE` is then added for the probe only. Runner cycle 04's
   own gate attempt 1, inside `harness/v2/gate.sh`, failed on this gate-in-a-gate leak.
5. **The inbox sweep (cycle-runner)**, against "a contradiction is two spec statements that
   cannot both hold". Only item 2's was one. The rest are readings or gaps, left as they are:
   - the repair's mounted gate evidence appears only in the effect's detail, not in
     `candidate-regenerated` (cycles 01-04): a gap; `@item-06-023` asks for the effect;
   - read-only rooms enforced by permission bits only, and a writable clean room in the
     builder room (cycles 01, 02): a gap in enforcement, not two statements;
   - how "when the builder ended" is detected, and a builder's late children (cycles 01-03,
     `@item-06-022`): a gap;
   - a port-held refusal's detail text naming `boot-budget-exhausted`, and a refused
     `file-verdicts` detail saying "filed as given" (cycle 02): the candidate's wording;
   - the spec's literal commit that a fixture repository cannot reproduce, the regenerate
     timeout against the time `start-run` takes to respond, `LOYLY_SCORING_TOOL_PATHS`'s fixture
     value, load-sensitive context timeouts (cycles 02-04): readings of the fixtures;
   - an empty plan's stamp recorded with zero events, the checklist's `source:line` read from
     the stripped copy, a second runner on one store (cycle 04): readings, as specified;
   - resume after a runner restart, room isolation as spec (The switch 7): gaps.
   Outside cycle-runner and not swept here: `record-gate-attempt`'s undeclared read of
   `instrument-goldens` (regeneration-cycle) and `@item-03-010`, `@item-03-011` (grader-lifecycle);
   each is cleared before its own context next accepts new items, if it is a contradiction.
6. **Outcome.** Runner cycle 04 is not accepted; the cycle 03 candidate stays the running
   runner. Runner cycle 05 is next, against this wording. No new item: `@item-06-018` already
   fails a run that halts after a refusal, and the denominator stays 48.
7. **The rule** (AGENTS.md): before a spec round accepts new items for a context, it clears
   that context's inbox items that are contradictions; readings and gaps may wait.

## Spec round 9 (decided 2026-11-09)

A spec round on slice 06 only, from the first self-hosted cycle (`selfhost-01`: slice 05,
cycle-preparation, run by the runner's cycle 05 build over the stdio gate; evidence kept, not
committed, at `.pi/harness/.state/selfhost-01`, the triage at `.pi/harness/.state/rounds/09`). No
other context's `spec.feature`, Allium `requires` or event-model schema changed; no existing
scenario's text changed; the runner's event-model schema is unchanged (everything new travels in
the configuration, the environment handed in and the `detail` `record-effect` already has; one
description in `cycle-run.edn` changed). The product owner accepted the triage as recommended.

1. **The evidence.** The wrapper `bin/builder.sh` launched builder 1 in `assets/builder`, the
   TypeScript image with no JVM, for a Clojure slice. It installed an Alpine aarch64 JDK, bash and
   git into its own scope, and gate attempt 1 was `suite-red` on the macOS host (`test.sh` exited
   126, "cannot execute binary file"). The repair made `test.sh` and `start.sh` use the host's
   tools, gate attempt 2 passed, and the judge accepted the candidate, 4/4, attempt 2 of 3
   (`harness-impl/05-cycle-preparation/PROVENANCE.md`). Nothing in the run's evidence named the
   image a builder ran in. The first grading copied the candidate out of its room, where it could
   not boot, and was voided. The runner's scoreboard was a baseline (no previous cycle) where
   legacy `scoreboard.clj`, on the same verdicts, compared with cycle 01.
2. **Decided (D1 to D8):**
   - *D1, the builder image per stack: accepted.* The configuration names the image,
     `BUILDER_IMAGE` (beside `APP_STACK`), so the harness digest changes when the image does; the
     environment names where images live, `LOYLY_BUILDER_IMAGES` (images are build products outside
     the cut). The runner hands the image's path to `LOYLY_BUILDER_CMD` as a third argument and
     names the image and its digest (the candidate digest's formula over the image's folder) in the
     regenerate and repair effects' `detail`, never in the harness digest, so `@item-06-046` and
     the self-check binding keep their meaning. Absent `BUILDER_IMAGE`, two arguments as before.
     `@item-06-049`; `BuilderLaunch.TheBuilderRunsInTheConfiguredImage`; seam E4, E6, "Handed in".
   - *D2, the image carries the stack's toolchain: accepted, as a contract.* `StartRun` requires 6:
     a configured image that is not a folder of `LOYLY_BUILDER_IMAGES` refuses the start,
     `builder-image-missing`, before anything is recorded (as `contexts-not-responding`). The
     toolchain (a JDK, the Clojure CLI, git and bb, the tools the gate's host runs) is a plain
     contract comment on `BuilderLaunch`, not an invariant: the runner never learns what a stack
     needs; the provided image build proves it (D4). `@item-06-050`.
   - *D3, a candidate that depends on the builder VM: left as it is.* The gate checks behaviour on
     the host, and it caught attempt 1 as `suite-red`, the correct verdict; the repair fixed it in
     one attempt, and D1 and D2 remove the cause. A foreign-executable gate rule would open
     regeneration-cycle (its inbox still holds an undeclared read), is platform-specific, and would
     have failed the accepted candidate, which still carries its unused `.runtime/`. A builder
     instruction sentence stays the operator's fallback, in the instrument (spec round 8 D7).
   - *D4, the image build stays provided: operator work, not spec* (AGENTS.md: the builder host
     and Gondolin rooms are provided, never regenerated). The operator's follow-ups, in the main
     repository: O1 `harness/loyly/Dockerfile.clojure` (JDK no newer than the host's, the Clojure CLI
     pinned to the host's version, static bb, ending in a `RUN` of the four version commands);
     O2 `.pi/harness/config/build/builder-clojure.json`; O3 `harness/Makefile` `bootstrap` builds
     and verifies `builder-clojure`; O4 `harness/config.sh` `BUILDER_IMAGE=builder-clojure`; O5 the
     `LOYLY_BUILDER_CMD` wrapper takes the third argument as its guest directory, and the runner's
     environment gains `LOYLY_BUILDER_IMAGES` and `LOYLY_MEASUREMENT_STORE`.
   - *D5, the grader gets the gate room, never a bare candidate: accepted, wording only.*
     `cycle-runner/seam.md`'s live-benchmark paragraph: the grading subagent drives the gated
     candidate in a room built as the install effect builds the passed attempt's gate room, from
     fresh state (for `stdio`, the clean room's files with the gated candidate's copy at
     `APP_DIR`), never a copy of the candidate alone. No item: nothing the runner does changes.
   - *D6, the scoreboard had no previous cycle: accepted.* Measurement's spec is right ("the last
     cycle whose measurement was filed before this one's, with the same `selection-id`", looked up
     in its own store); the runner's was wrong: it started every context on a fresh store, so every
     runner cycle was a baseline, against AGENTS.md ("compare with the previous cycle on shared
     items") and the glossary's Scoreboard. **The premise corrected:** had cycle 01 been seen, its
     4 items would have been *shared* (legacy: 0 newly passing, 0 newly failing), not new; the
     holdout would be 0. What was wrong is the missing comparison, not the holdout count, and
     Measurement needs no change. Now an optional `LOYLY_MEASUREMENT_STORE`: when set, Measurement
     is started on that one file for every run and it is kept, the other four contexts stay fresh,
     and the relay hands on only the handoffs appended during this run, positions counted from the
     first of them. History starts at the first run on the file (slice 05's cycle 01 is in no
     Measurement store); the operator keeps `selection-id` stable per selection and cut and cycle
     ids unique across runs on one file. The grader may start the gated Measurement candidate on
     its own history file after the run to read the scoreboard. `@item-06-051`;
     `ContextSeams.MeasurementKeepsItsHistory`; `StartRun`, `RelayedHandoff.position`,
     `InTheProducersOrder` wording; seam E8 and "Handed in". **D6 is the first scoreboard-stage
     disagreement between the runner and the legacy harness since the switch** (shadow runs 4 and
     5 were baseline on both sides).
   - *D7, a refused pin still runs a full ORC workflow: left as it is.* `cycle-preparation/seam.md`
     already allows it ("records nothing" means no event of `event-model/`; ORC's and Grain's own
     run events may still be written), and the store check passed.
   - *D8, a refusal's `message` is the bare refusal code: left as it is.* Only `data.refusal` (and
     the few named messages) is the contract; `message` is free text, and the runner never reads it.
3. **The inbox (cycle-runner)**, per AGENTS.md. The one contradiction, fresh stores per run
   against the scoreboard's previous cycle (in effect across cycle-runner and measurement), is
   cleared by D6; the gap "a builder room provisioned for the target stack" (glossary) is closed by
   D1. The rest are gaps and wait: room isolation and network egress (the glossary's builder room
   has "no network egress", while builder 1 apk-installed and fetched dependencies); the repair's
   paths, which are host paths the builder found by relative path inside the VM; and resume after a
   runner restart. Outside cycle-runner, not this round: `record-gate-attempt`'s undeclared read of
   `instrument-goldens` (regeneration-cycle), `@item-03-010` and `@item-03-011` (grader-lifecycle).
4. **Three new scenarios**, all `@minimum`, appended at the end of `cycle-runner/spec.feature`,
   stamped `@item-06-049` to `@item-06-051`. Cycle-runner goes from 48 to 51 items and from 46 to
   49 must-cover obligations.
5. **Next.** Runner cycle 06 against this spec, gated by `harness/v2/gate.sh 06` until it is
   cross-checked; the cross-check repeats spec round 8's C1 to C5 with `BUILDER_IMAGE` absent
   (both builds must agree), and the two new behaviours alone on the new build. Then the operator
   follow-ups O1 to O5 and `selfhost-02` (slice 05 in `builder-clojure`, with a history store), and
   a `selfhost-03` of the same selection, the first runner scoreboard with a previous cycle.
   Slices 01 to 05 need no cycle: their specs are untouched.

The slice-06 grader grows by these three items ("spec grew", additions only). The checklist golden
grows 197 → 200. `@item-06-049` to `@item-06-051` are the next cycle's untuned holdout.
