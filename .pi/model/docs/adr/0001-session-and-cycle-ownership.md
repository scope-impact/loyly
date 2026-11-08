---
status: accepted
---

# Keep the ORC inner cycle independent from operator and builder sessions

> **Spec v1, kept as history.** The maintained spec is spec v2 in `.pi/spec/` ([README](../../../spec/README.md)); the harness that runs the cycles now is in its decisions, ["The switch to the new harness"](../../../spec/decisions.md#the-switch-to-the-new-harness-decided-2026-11-08).

ORC owns the complete cut, build, verification-repair, grading, and measurement cycle;
Codex, Pi, or another operator session owns preparation approval and the bounded decision to
start a distinct child cycle. Builder sessions are separately identified effectors behind ORC
stages: they may be observed read-only, while pause, steer, or fork is explicit and marks the
result as assisted. This keeps ORC reusable and evidence-independent without giving up durable,
inspectable agent work or session-agnostic operation.

The routing rule is the execution contract, not whether an LLM happens to be used:

- A fixed, bounded contract belongs to ORC. Its production prompt or builder instruction version, inputs, output
  schema, tool surface, retries, and termination conditions are approved before execution. An
  ORC stage may still invoke an LLM with constrained tools as a bounded effector.
- Exploratory, model-directed work belongs to an operator session. The session may use skills,
  discover or combine tools, branch, and accept human direction. It may be durable when its
  transcript and task state must survive interruption.
- The handoff is an immutable approved preparation. The exploratory transcript is never ORC
  builder input and never becomes authority over ORC's workflow.

## Consequences

- Live progress and builder-session views never advance ORC's workflow.
- A cycle continues when its requesting operator session disconnects.
- Same-cycle repair remains ORC-owned and cannot consume independent grader evidence.
- A new outer attempt receives a new cycle identity and records parent-cycle lineage.
- Reusing the same approved preparation starts a distinct child ORC cycle. Changing the slice,
  production prompt contract, skills, or tools returns control to an exploratory operator session and
  requires a newly approved preparation.
- Durable builder recovery must bind committed transcript and tool state to the matching
  workspace generation; restoring either side alone is invalid.
