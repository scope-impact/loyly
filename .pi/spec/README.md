# Spec v2

A from-scratch specification of the Pi harness, built in rounds.

| Round | What | Where |
|---|---|---|
| R0 | Big Picture EventStorming board | `event-storm.md`, `event-storm.edn` |
| R1 | Event Model (Grain, EDN) of the legacy core: the 31 storm events the legacy harness already has | `<context>/event-model/`, `<context>/domain-design.md` |
| R2 | Allium (behaviour, the refusals as `requires`) of the legacy core, the product owner's decisions applied, and the `:grain/allium` links | `<context>/allium/`, `legacy-trace.md`, `<context>/domain-design.md` (done) |
| R2.1 | The product owner's decisions on R2's questions applied: budget exhaustion fails the cycle (reason `budget`, the stage clock), gate refusals counted (three in a row: reason `infrastructure`), the self-check key covers the approved grader digests, a reworded golden fails the self-check, the stamper reports an empty-title heading loudly, "the same selection" is the same slices by name across cuts | `<context>/allium/`, `<context>/event-model/`, `<context>/domain-design.md` (done) |
| | Decisions of the product owner after R1 and R2 | `decisions.md` |

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

handoffs:
specification --scenario-stamped--> measurement
cycle-preparation --source-cut-pinned--> measurement, regeneration-cycle
grader-lifecycle --instrument-golden-written/moved--> regeneration-cycle
regeneration-cycle --gate-attempt-passed (one way)--> measurement
measurement --measurement-filed, score-stage-failed, scoreboard-reported--> regeneration-cycle (conclude only)
```

Each context folder has `README.md`, `domain-design.md` (Files table, decisions, which storm
event became which block or refusal, the refusals R2 must write, open questions) and
`event-model/` (one fragment per aggregate, `area.edn`, `handoffs.edn`).

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
