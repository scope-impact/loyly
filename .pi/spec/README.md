# Spec v2

A from-scratch specification of the Pi harness, built in rounds.

| Round | What | Where |
|---|---|---|
| R0 | Big Picture EventStorming board | `event-storm.md`, `event-storm.edn` |
| R1 | Event Model (Grain, EDN) of the legacy core: the 31 storm events the legacy harness already has | `<context>/event-model/`, `<context>/domain-design.md` |
| R2 | Allium (behavior, the refusals as `requires`) and the `:grain/allium` links | next |

## Contexts in R1

```text
specification       Slice                      1 command  1 event
cycle-preparation   SourceCut                  1 command  1 event
grader-lifecycle    InstrumentGolden           2 commands 2 events
regeneration-cycle  HarnessSelfCheck, CleanRoom,
                    RegenerationCycle, GateAttempt
                                               7 commands 9 events  policy conclude-cycle
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

The 31 legacy storm events became:

| Became | Count | Storm events |
|---|---|---|
| an event | 19 | every happy event, plus self-check failed, gate attempt failed, score stage failed, cycle failed |
| a reason on `gate-attempt-failed` | 5 | the gate guards: tool missing or no-op suite, suite discovery empty, seam answered before boot, boot budget exhausted, identity probe failed |
| a refusal, written in R2 | 5 | unstamped scenario found, empty selection refused, the two fence refusals, score refused |
| a field of `measurement-filed` | 2 | unreached item recorded as fail, duplicate verdict superseded |

Validate: `tools/spec/v2/README.md`.
