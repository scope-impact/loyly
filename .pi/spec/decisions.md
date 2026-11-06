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
