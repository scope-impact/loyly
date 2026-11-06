# Cycle preparation (operator control)

R1: the source cut only. Selection, approval and submission come in a later round.

- `event-model/source-cut.edn`: aggregate SourceCut (`pin-source-cut` → `source-cut-pinned`).
- `event-model/handoffs.edn`: `source-cut-pinned`, consumed by Measurement and the Regeneration cycle.
- `event-model/area.edn`: screen `preparation`; flow `cut-to-fence`.
- `domain-design.md`: the Files table, decisions, storm mapping.
