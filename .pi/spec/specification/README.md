# Specification

The scenarios and the item identity stamped on each; upstream of every cycle.

- `event-model/slice.edn`: aggregate Slice (`stamp-slice` → `scenario-stamped`).
- `event-model/handoffs.edn`: `scenario-stamped`, consumed by Measurement.
- `event-model/area.edn`: screen `spec-authoring`; flow `stamping`, which stays inside the context.
- `allium/`: `context.allium` (contract ScenarioReading: one reading of Gherkin for the stamper and the checklist), `slice.allium` (Slice, surface SpecAuthoring).
- `domain-design.md`: the Files table, decisions, storm mapping.
- `seam.md`: the public seam a gate, a grader and an operator drive: JSON-RPC over stdio, one
  method per command and query, and the Grain store the gate hands the candidate.
- `spec.feature`: the accepted scenarios, slice 01 (`@item-01-001` to `@item-01-015`) through
  `../../plan/01-specification`; `drafts.feature`: two deferred drafts (`../decisions.md`, "Spec round 6" 4).

Validate: `tools/spec/v2/README.md`.
