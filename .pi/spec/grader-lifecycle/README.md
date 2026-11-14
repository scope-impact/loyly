# Grader lifecycle

R1: the instrument goldens only. The graders come in a later round.

- `event-model/instrument-golden.edn`: aggregate InstrumentGolden (`write-instrument-golden`, `move-instrument-golden`).
- Both golden events are folded by the Regeneration cycle's self-check from the one store (spec round 18).
- `event-model/area.edn`: screen `instrument-goldens`; flow `golden-written-then-moved` (inside this context).
- `spec.feature`: the accepted scenarios (slice 03, `@item-03-001` to `@item-03-011`); `drafts.feature`: the open grader drafts, parked (`../decisions.md`, "Spec round 3" 4).
- `seam.md`: the public seam of this context's methods (slice 03). Since spec round 18 the context is built only inside slice 06's one candidate, never standalone, and has no identity probe of its own.
- `allium/`: `context.allium` (MoveReason, contract ScenarioDigests, open questions), `instrument-golden.allium` (InstrumentGolden, surface InstrumentGoldenDesk).
- `domain-design.md`: the Files table, decisions, storm mapping.
