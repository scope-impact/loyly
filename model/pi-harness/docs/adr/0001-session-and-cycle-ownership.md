---
status: accepted
---

# Keep the ORC inner cycle independent from operator and builder sessions

ORC owns the complete cut, build, verification-repair, grading, and measurement cycle;
Codex, Pi, or another operator session owns preparation approval and the bounded decision to
start a distinct child cycle. Builder sessions are separately identified effectors behind ORC
stages: they may be observed read-only, while pause, steer, or fork is explicit and marks the
result as assisted. This keeps ORC reusable and evidence-independent without giving up durable,
inspectable agent work or session-agnostic operation.

## Consequences

- Live progress and builder-session views never advance ORC workflow state.
- A cycle continues when its requesting operator session disconnects.
- Same-cycle repair remains ORC-owned and cannot consume independent grader evidence.
- A new outer attempt receives a new cycle identity and records parent-cycle lineage.
- Durable builder recovery must bind committed transcript/tool state to the matching workspace
  generation; restoring either side alone is invalid.
