# Cycle preparation (operator control)

R1: the source cut only. Selection, approval and submission come in a later round.

- `event-model/source-cut.edn`: aggregate SourceCut (`pin-source-cut` → `source-cut-pinned`).
- `event-model/handoffs.edn`: `source-cut-pinned`, consumed by Measurement and the Regeneration cycle.
- `event-model/area.edn`: screen `preparation`; flow `cut-to-fence` (inside this context).
- `spec.feature`: the accepted scenarios (slice 05, `@item-05-001` to `@item-05-004`); `drafts.feature`: one deferred balance draft (`../decisions.md`, "Spec round 6" 4).
- `seam.md`: the public seam of this context's candidate (slice 05).
- `allium/`: `context.allium` (contract ProjectRepository, open questions), `source-cut.allium` (SourceCut, surface Preparation).
- `domain-design.md`: the Files table, decisions, storm mapping.
