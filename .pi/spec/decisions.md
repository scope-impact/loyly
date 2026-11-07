# Decisions

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
   needed for this and is not built.
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
   later, not now.

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
   methods.
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
