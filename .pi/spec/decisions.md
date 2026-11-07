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
3. **Open, for the assembly gate: was a cycle that failed at the score stage measured?** A
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
