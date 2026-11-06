# Specification

The scenarios and the item identity stamped on each; upstream of every cycle.

- `event-model/slice.edn`: aggregate Slice (`stamp-slice` → `scenario-stamped`).
- `event-model/handoffs.edn`: `scenario-stamped`, consumed by Measurement.
- `event-model/area.edn`: screen `spec-authoring`; flow `stamping-to-checklist`.
- `allium/`: `context.allium` (contract ScenarioReading: one reading of Gherkin for the stamper and the checklist), `slice.allium` (Slice, surface SpecAuthoring).
- `domain-design.md`: the Files table, decisions, storm mapping.

Validate: `tools/spec/v2/README.md`.
