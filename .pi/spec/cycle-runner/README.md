# Cycle runner (slice 06)

The ORC workflow that performs one cycle's effects in the real world and records each effect's
result into the context that owns it, through that context's seam, relaying every handoff from its
producer's store. It owns no rule of the five contexts. Drafted in spec round 7a, accepted as slice
06 in spec round 7b and grown in spec rounds 7c and 7d (`../decisions.md`). Since the switch its
cycle 03 candidate runs the regeneration cycles (`../decisions.md`, "The switch to the new harness").

- `event-model/cycle-run.edn`: aggregate CycleRun (`start-run`, `hand-in-verdicts`; the processor `advance-run` and its `record-effect`, `relay-handoff`, `conclude-run`, `halt-run`).
- `event-model/area.edn`: screen `run-desk`; flows `run-to-gate`, `verdicts-to-conclusion`, `cycle-failed-ends-run`, `effect-not-performed-halts-run`. No handoff produced or consumed.
- `spec.feature`: the accepted scenarios (slice 06, `@item-06-001` to `@item-06-038`); `drafts.feature`: none left.
- `seam.md`: the public seam (JSON-RPC over stdio), the effects E1 to E8, what is handed in, how a grader drives it without a live pi, and how the candidate's own suite stands the five contexts in.
- `allium/`: `context.allium` (RunBudget, contracts ContextSeams, ProjectRepository, BuilderLaunch, GateOnTheHost, enums, config, the decided questions Q1 to Q6), `cycle-run.allium` (CycleRun, EffectResult, RelayedHandoff, surfaces RunDesk and EffectNodes).
- `domain-design.md`: the Files table, decisions, boundaries, the decided questions.
