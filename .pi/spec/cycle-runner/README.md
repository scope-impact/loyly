# Cycle runner (slice 06)

The one ORC tree, `cycle-runner/cycle.v1`, on one Grain store, that performs one cycle's effects in
the real world and records each effect's result into the context that owns it, by that context's
command on the one store. The five contexts 01 to 05 are in the same candidate: their commands are
the tree's leaves, and the one event model is the union of theirs. It owns no rule of the five
contexts. Drafted in spec round 7a, accepted as slice 06 in spec round 7b, grown in spec rounds 7c,
7d, 8, 9, 11, 12, 13, 14, 15 and 16, and rewritten as `cycle.v1` in spec round 18
(`../decisions.md`, "Spec round 18"): no relay, no verdicts handed in, the builder a pi-durable
session, each item graded by the judge, a stopped run resumed. Until the tree runs, the legacy
shell harness drives cycles and builds it, in one cycle with all six contexts' specs: the five
contexts 01 to 05 are built only inside this slice, never on their own.

- `event-model/cycle-run.edn`: aggregate CycleRun (`start-run`; the leaves' `record-effect`, `conclude-run`, `halt-run`).
- `event-model/area.edn`: screen `run-desk`; the one flow `cycle.v1`, whose steps name the leaf that writes each event (`:leaf`, spec round 14).
- `spec.feature`: the accepted scenarios (slice 06, `@item-06-001` to `@item-06-073`, 61 items: the stamps spec round 18 removed are not reused); `drafts.feature`: none left.
- `seam.md`: the public seam (JSON-RPC over stdio), the effects (the gate for the `http` and the `stdio` seam, or a gate command), the builder session, grading, the tree with its blackboard and each node's reads and writes, its leaf names and bounds, resume, what is handed in, how a grader drives it without a live pi, the one store, and what the build carries.
- `allium/`: `context.allium` (RunBudget, contracts OneStore, ProjectRepository, BuilderSession, GateOnTheHost, TheJudge, enums, config, the decided questions Q1 to Q6), `cycle-run.allium` (CycleRun, EffectResult, surfaces RunDesk and EffectNodes).
- `domain-design.md`: the Files table, decisions, boundaries, the decided questions.
