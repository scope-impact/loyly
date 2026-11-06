# Grader lifecycle

R1: the instrument goldens only. The graders come in a later round.

- `event-model/instrument-golden.edn`: aggregate InstrumentGolden (`write-instrument-golden`, `move-instrument-golden`).
- `event-model/handoffs.edn`: both golden events, consumed by the Regeneration cycle's self-check.
- `event-model/area.edn`: screen `instrument-goldens`; flow `golden-written-then-moved`.
- `allium/`: `context.allium` (MoveReason, contract ScenarioDigests, open questions), `instrument-golden.allium` (InstrumentGolden, surface InstrumentGoldenDesk).
- `domain-design.md`: the Files table, decisions, storm mapping.
