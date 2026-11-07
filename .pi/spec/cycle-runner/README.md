# Cycle runner (DRAFT, slice 06)

The ORC workflow that performs one cycle's effects in the real world and records each effect's
result into the context that owns it, through that context's seam, relaying every handoff from its
producer's store. It owns no rule of the five contexts. Drafted in spec round 7a for the product
owner's review: no `spec.feature`, nothing stamped, no `.pi/plan/06-*` link yet.

- `event-model/cycle-run.edn`: aggregate CycleRun (`start-run`, `hand-in-verdicts`; the processor `advance-run` and its `record-effect`, `relay-handoff`, `conclude-run`, `halt-run`).
- `event-model/area.edn`: screen `run-desk`; flows `run-to-gate`, `verdicts-to-conclusion`, `cycle-failed-ends-run`, `effect-not-performed-halts-run`. No handoff produced or consumed.
- `drafts.feature`: 34 draft scenarios, every must-cover obligation of `allium/` tagged.
- `seam.md`: the public seam (JSON-RPC over stdio), the effects E1 to E8, what is handed in, and how a grader drives it without a live pi.
- `allium/`: `context.allium` (RunBudget, contracts ContextSeams, ProjectRepository, BuilderLaunch, GateOnTheHost, enums, config, open questions Q1 to Q4), `cycle-run.allium` (CycleRun, EffectResult, RelayedHandoff, surfaces RunDesk and EffectNodes).
- `domain-design.md`: the Files table, decisions, boundaries, open questions.
