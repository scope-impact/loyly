# Cycle preparation (operator control)

R1: the source cut only. Selection, approval and submission come in a later round.

- `event-model/source-cut.edn`: aggregate SourceCut (`pin-source-cut` → `source-cut-pinned`).
- `source-cut-pinned` is folded by Measurement and the Regeneration cycle from the one store (spec round 18).
- `event-model/area.edn`: screen `preparation`; flow `cut-to-fence` (inside this context).
- `spec.feature`: the accepted scenarios (slice 05, `@item-05-001` to `@item-05-004`); `drafts.feature`: one deferred balance draft (`../decisions.md`, "Spec round 6" 4).
- `seam.md`: the public seam of this context's methods (slice 05). Since spec round 18 the context is built only inside slice 06's one candidate, never standalone, and has no identity probe of its own.
- `allium/`: `context.allium` (contract ProjectRepository, open questions), `source-cut.allium` (SourceCut, surface Preparation).
- `domain-design.md`: the Files table, decisions, storm mapping.
