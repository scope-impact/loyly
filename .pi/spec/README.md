# Spec v2

A from-scratch specification of the Pi harness, built in rounds.

Today (2026-11-14): six contexts are accepted, each a slice under `../plan/`, 210 stamped items
(specification 15, measurement 43, grader-lifecycle 11, regeneration-cycle 76,
cycle-preparation 4, cycle-runner 62). Since spec round 18 the six contexts are one candidate,
the ORC tree `cycle-runner/cycle.v1` on one store; until it runs, the legacy shell harness drives
cycles and builds it, in one cycle with all six specs. The contexts 01 to 05 are built only
inside slice 06, never on their own. Every decision is in `decisions.md`, in
order; where two disagree the later one wins.

| Round | What | Where |
|---|---|---|
| R0 | Big Picture EventStorming board | `event-storm.md`, `event-storm.edn` |
| R1 | Event Model (Grain, EDN) of the legacy core: the 31 storm events the legacy harness already has | `<context>/event-model/`, `<context>/domain-design.md` |
| R2 | Allium (behaviour, the refusals as `requires`) of the legacy core, the product owner's decisions applied, and the `:grain/allium` links | `<context>/allium/`, `legacy-trace.md`, `<context>/domain-design.md` (done) |
| R2.1 | The product owner's decisions on R2's questions applied: budget exhaustion fails the cycle (reason `budget`, the stage clock), gate refusals counted (three in a row: reason `infrastructure`), the self-check key covers the approved grader digests, a reworded golden fails the self-check, the stamper reports an empty-title heading loudly, "the same selection" is the same slices by name across cuts | `<context>/allium/`, `<context>/event-model/`, `<context>/domain-design.md` (done) |
| R3 | Draft scenarios for every must-cover Allium obligation (239 obligations, 175 drafts, 0 uncovered), the coverage gate, the Given/When/Then lint and the glossary lint ported to `tools/spec/v2/` | `<context>/drafts.feature`, `<context>/domain-design.md` (done) |
| R3.1 | Trimmed before review: structural obligations reclassified (11 refusals guarded upstream, set aside by a checked `guarded by:`; 4 guarantees and contract invariants "Verified by: gate", one real check in `tools/spec/v2/structural.py`, one planned), six pairs of drafts merged; 175 drafts → 161, must-cover 239 → 224 | `<context>/drafts.feature`, `<context>/allium/`, `decisions.md` ("Calls after R3"), `tools/spec/v2/` (done) |
| R4 | The product owner's acceptance, one context per cycle, each context one slice, numbered by its link `.pi/plan/NN-<context> -> ../spec/<context>`, added when the context is accepted (planned: 01 specification, 02 measurement, 03 grader-lifecycle, 04 regeneration-cycle, 05 cycle-preparation). First the drafts that cite legacy behaviour and are tier minimum: each moves into `<context>/spec.feature` under its Rule, `@draft` becomes `@minimum`, and `tools/spec/v2/stamp.clj` (the legacy stamper's rules; only tag lines count toward the next number) gives it an `@item` id. R4.1 specification: 15 accepted, `@item-01-001` to `@item-01-015`; R4.2 measurement: 24 accepted, `@item-02-001` to `@item-02-024`; R4.3 grader-lifecycle: 4 accepted, `@item-03-001` to `@item-03-004`; R4.4 regeneration-cycle: 27 accepted, `@item-04-001` to `@item-04-027`; R4.5 cycle-preparation: its drafts cite no legacy behaviour (all new), and its 4 of tier minimum are accepted, reworded with full 40-hex commits, `@item-05-001` to `@item-05-004` | `<context>/spec.feature`, `../plan/` (R4.1 to R4.5 done) |
| First light | One context per cycle with the legacy driver: the room holds one context's spec, a person-run `pi` builds `harness-impl/NN-<context>/`, the gate checks the Grain store directly. Specification first: its seam, and its flow kept inside the context | `decisions.md` ("First light"), `specification/seam.md`, `../plan/` |
| Slice 02 | Measurement alone: its seam, the consumed handoffs handed in through it (one method per consumed event), the flow `scoring`, and the reading of the spec left to the issuer | `decisions.md` ("Slice 02"), `measurement/seam.md` |
| Slice 03 | Grader lifecycle alone: its seam, its flow cut to its own context, the scenario digests handed in (`LOYLY_SCENARIO_DIGESTS`), a reason outside the three heard as a schema mismatch | `decisions.md` ("Slice 03"), `grader-lifecycle/seam.md` |
| Slice 04 | Regeneration cycle alone: its seam, its flows cut to its own context with Measurement's facts as consumed handoffs, the real world's results handed in as command params (it records, it does not act), the goldens digest by a stated formula, the scoring tools handed in (`LOYLY_SCORING_TOOL_PATHS`), the policy and the stage clock through the seam | `decisions.md` ("Slice 04"), `regeneration-cycle/seam.md` |
| Slice 05 | Cycle preparation alone: its seam, its flow cut to its own context, the repository's commits and tree digests handed in (`LOYLY_PROJECT_COMMITS`), full 40-hex commits in its scenarios | `decisions.md` ("Slice 05"), `cycle-preparation/seam.md` |
| Slice 06 (draft) | Cycle runner, spec round 7a, drafts for review: the ORC workflow that performs a cycle's effects (E1 to E8: stamp, pin, checklist, self-check, fence, regenerate, gate on the host, repair, file the handed-in verdicts) and records each into its context through its seam, relaying every handoff from its producer's store; 34 drafts, no `spec.feature`, no `.pi/plan/06-*` yet | `decisions.md` ("Slice 06 (draft)"), `cycle-runner/` |
| Slice 06 | Cycle runner accepted in spec round 7b: the open questions decided (Q1 restamp a stripped copy, else halt; Q2/Q5 the benchmark's own configuration, handed in; Q3, Q6 the drafted digests; Q4 synchronous methods), all 34 drafts and one added for Q1 stamped `@item-06-001` to `@item-06-035`, `.pi/plan/06-cycle-runner`; its suite stands the five contexts in (`seam.md`) | `decisions.md` ("Spec round 7b"), `cycle-runner/` |
| Spec rounds 1 to 6 | Fixes and acceptances after the cycles of slices 01 to 05: 21 new minimum items (round 2), 4 (round 3), wording and readings (rounds 4, 5; round 5: the assembly is a relay of each producer's handoffs, `harness/v2/assemble.sh`), 50 balance items (round 6) | `decisions.md` ("Spec round 1" to "Spec round 6") |
| Spec rounds 7c, 7d | The shadow runs' gate gaps: the probe gets the served URL (`@item-06-036`), the gate room writable by the candidate's own run and SKIP lines read whole (`@item-06-037`, `@item-06-038`) | `decisions.md` ("Spec round 7c", "Spec round 7d"), `cycle-runner/seam.md` |
| The switch | Not a spec round: the new harness runs the regeneration cycles after shadow runs 4 and 5 agreed with the legacy harness at every stage; legacy stays the reference; what stays open | `decisions.md` ("The switch to the new harness") |
| Spec round 18 | One tree on one store: slice 06 rewritten as `cycle-runner/cycle.v1` (the builder a pi-durable session, the judge grading each item, resume, OTLP export), the relay, hand-in methods and handoff contracts removed from 01 to 05, and 01 to 05 built only inside slice 06; 212 → 210 items | `decisions.md` ("Spec round 18"), `cycle-runner/` |
| | Every decision of the product owner, R1 to the switch | `decisions.md` |

## Contexts in R1

```text
specification       Slice                      1 command  2 events
cycle-preparation   SourceCut                  1 command  1 event
grader-lifecycle    InstrumentGolden           2 commands 2 events
regeneration-cycle  HarnessSelfCheck, CleanRoom,
                    RegenerationCycle, GateAttempt
                                               8 commands 10 events policy conclude-cycle,
                                                                    clock stage-clock
measurement         Checklist, CycleMeasurement,
                    Scoreboard                 5 commands 6 events  policy report-scoreboard-once

events folded by another context, from the one store (spec round 18; before it, handoffs):
specification --scenario-stamped--> measurement
cycle-preparation --source-cut-pinned--> measurement, regeneration-cycle
grader-lifecycle --instrument-golden-written/moved--> regeneration-cycle
regeneration-cycle --gate-attempt-passed (one way)--> measurement
measurement --measurement-filed, score-stage-failed, scoreboard-reported--> regeneration-cycle (conclude only)
```

Each context folder has `README.md`, `domain-design.md` (Files table, decisions, which storm
event became which block or refusal, the refusals R2 must write, open questions) and
`event-model/` (one fragment per aggregate, and `area.edn`; `handoffs.edn` until spec round 18).

The 31 legacy storm events became (after R2 and `decisions.md` b):

| Became | Count | Storm events |
|---|---|---|
| an event | 19 | every happy event, plus self-check failed, gate attempt failed, score stage failed, cycle failed |
| a reason on `gate-attempt-failed` | 4 | the gate guards the candidate can fix: tool missing or no-op suite, suite discovery empty, boot budget exhausted, identity probe failed |
| a refusal (`requires`), written in R2 | 6 | unstamped scenario found, empty selection refused, the two fence refusals, score refused, seam answered before boot (no attempt spent) |
| a field of `measurement-filed` | 2 | unreached item recorded as fail, duplicate verdict superseded |

## R2: the Allium

One Allium file per aggregate plus `context.allium` per context, under
`<context>/allium/`. Refusals are `requires`, numbered in order; contexts meet only through
handoff events (the consumer declares the producer as an `external entity` and a surface
that `provides` the event). Every command links its rule, every policy its rule, every
screen its surface, every aggregate read model its entity (`:grain/allium`).

```text
context             files  rules  requires (of commands)  invariants  must-cover (allium plan)
specification       2      2      10 (10)                3           15
cycle-preparation   2      1       3  (3)                1            5
grader-lifecycle    2      2       7  (7)                3           12
measurement         4      9      32 (28)               12           53
regeneration-cycle  5     26      76 (56)               12          114
```

(After R2.1. R2 had specification 8 requires, 13 must-cover; measurement 11 invariants, 52;
regeneration-cycle 22 rules, 65 (49) requires, 99.)

Rules count every rule: commands, policies, and the folds of handoffs and sibling events.
Requires count every `requires`; in brackets those of the commands' rules, the refusals an
issuer hears. Must-cover = each rule's success, each `requires`, each invariant.

Event-model changes for `decisions.md` (details in each `domain-design.md`): the self-check
runs before the fence and before every gate attempt (H24) and carries the budget (H5); a
seam that answered before boot and a missing identity probe are refusals of
`record-gate-attempt`, no longer reasons on `gate-attempt-failed` (b); a repair room mounts
only gate evidence (H6); verdicts carry a `reason` (H7); the score binds the gated
candidate and re-checks the approved grader digests (H13); the scoreboard compares with
the last measured cycle of the same selection (g); goldens carry their scenario digest (h).

`legacy-trace.md` traces every legacy behaviour (all 70 `@harness-NNN` items and the
scripts): 33 preserved, 16 changed as decided, 11 added, 10 dropped with a reason.

Validate: `tools/spec/v2/README.md` (`tools/spec/v2/gates.sh`).

## R3: the drafts

```text
context             drafts  must-cover  unit-level  gate-verified  obligations per draft
specification       17      24           2          0              1.4
cycle-preparation    5       6           1          0              1.2
grader-lifecycle    11      14           1          0              1.3
measurement         45      66          10          1              1.5
regeneration-cycle  83     114          24          3              1.4
total              161     224          38          4              1.4 (233 tags)
```

(After R3.1. R3 had 175 drafts, 239 must-cover, 27 unit-level, 248 tags; measurement 47
and 69, regeneration-cycle 95 and 126.)

Must-cover (`tools/spec/v2/coverage.py`) = each rule's success, each `requires` less the
unit-level ones (the rule's own aggregate's status, stage or path; one half of an
`exists`/`not exists` pair between sibling rules of one trigger; the guard of an idempotent
creation; a refusal guarded upstream, by a checked `guarded by:`), each invariant, each
contract invariant, and each guarantee not "Verified by: gate" (`decisions.md`, "Calls
after R3"). Suggested tiers: 98 minimum (every legacy-preserving behaviour), 58 balance, 5
deferred (R3: 104, 60, 11). Nothing in `drafts.feature` is read by the harness.
