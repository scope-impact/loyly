# Cycle runner (slice 06)

The proposed successor ORC tree, `cycle-runner/cycle.v2`, on one Grain store, that performs one cycle's effects in
the real world and records each effect's result into the context that owns it, by that context's
command on the one store. The five contexts 01 to 05 are in the same candidate: their commands are
the tree's leaves, and the one event model is the union of theirs. It owns no rule of the five
contexts. Drafted in spec round 7a, accepted as slice 06 in spec round 7b, grown in spec rounds 7c,
7d, 8, 9, 11, 12, 13, 14, 15 and 16, and rewritten as `cycle.v1` in spec round 18
(`../decisions.md`, "Spec round 18"): no relay, no verdicts handed in, the builder a pi-durable
session and a stopped run resumed. The accepted `cycle.v1` is the running build. The proposed
`cycle.v2` delegates grading to a separate Pi host for each item. The accepted runner will
build its successor in a bounded self-hosted cycle; independent black-box grading and a legacy
instrument shadow remain required before promotion. Contexts 01 to 05 are built only inside
this slice, never on their own.

- `event-model/cycle-run.edn`: aggregate CycleRun (`start-run`; the leaves' `record-effect`, `conclude-run`, `halt-run`).
- `event-model/area.edn`: screen `run-desk`; the one flow `cycle.v2`, whose steps name the leaf that writes each event (`:leaf`, spec round 14).
- `spec.feature`: 64 stamped slice-06 scenarios, including the proposed Pi grader host boundary.
- `seam.md`: the public seam (JSON-RPC over stdio), the effects (the gate for the `http` and the `stdio` seam, or a gate command), the builder session, grading, the tree with its blackboard and each node's reads and writes, its leaf names and bounds, resume, what is handed in, how a grader drives it without a live pi, the one store, and what the build carries.
- `allium/`: `context.allium` (RunBudget, contracts OneStore, ProjectRepository, BuilderSession, GateOnTheHost, TheJudge, enums, config, the decided questions Q1 to Q6), `cycle-run.allium` (CycleRun, EffectResult, surfaces RunDesk and EffectNodes).
- `domain-design.md`: the Files table, decisions, boundaries, the decided questions.
