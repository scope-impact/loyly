# Cycle runner (slice 06)

The ORC workflow that performs one cycle's effects in the real world and records each effect's
result into the context that owns it, through that context's seam, relaying every handoff from its
producer's store. It owns no rule of the five contexts. Drafted in spec round 7a, accepted as slice
06 in spec round 7b and grown in spec rounds 7c, 7d, 8, 9, 11, 12, 13, 14, 15 and 16 (`../decisions.md`); round 8 added the
stdio gate contract, so it can gate the harness's own contexts, and round 12 a handed-in gate
command, so a provided command can gate a project in a VM with its world. Since the switch its
cycle 03 candidate runs the regeneration cycles (`../decisions.md`, "The switch to the new harness").

- `event-model/cycle-run.edn`: aggregate CycleRun (`start-run`, `hand-in-verdicts`; the leaves' `record-effect`, `relay-handoff`, `conclude-run`, `halt-run`).
- `event-model/area.edn`: screen `run-desk`; flows `run-to-gate`, `verdicts-to-conclusion`, `cycle-failed-ends-run`, `effect-not-performed-halts-run`, each an ORC tree whose steps name the leaf that writes each event (`:leaf`, spec round 14). No handoff produced or consumed.
- `spec.feature`: the accepted scenarios (slice 06, `@item-06-001` to `@item-06-063`); `drafts.feature`: none left.
- `seam.md`: the public seam (JSON-RPC over stdio), the effects E1 to E8 (the gate for the `http` and the `stdio` seam), the flows' ORC trees, their blackboard and each leaf's reads and writes, their leaf names and bounds, what the build carries, the plan and its slices, what is handed in, how a grader drives it without a live pi, and how the candidate's own suite stands the five contexts in.
- `allium/`: `context.allium` (RunBudget, contracts ContextSeams, ProjectRepository, BuilderLaunch, GateOnTheHost, enums, config, the decided questions Q1 to Q6), `cycle-run.allium` (CycleRun, EffectResult, RelayedHandoff, surfaces RunDesk and EffectNodes).
- `domain-design.md`: the Files table, decisions, boundaries, the decided questions.
