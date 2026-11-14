# Regeneration cycle (harness: fence and gate)

One cycle in the legacy shape and its one conclusion.

- `event-model/harness-self-check.edn`: HarnessSelfCheck (`record-harness-self-check`).
- `event-model/clean-room.edn`: CleanRoom (`prove-clean-room`).
- `event-model/regeneration-cycle.edn`: RegenerationCycle (`record-regenerated-candidate`, `pass-cycle`, `fail-cycle`; policy `conclude-cycle`).
- `event-model/gate-attempt.edn`: GateAttempt (`install-candidate-in-gate-room`, `record-gate-attempt`).
- `gate-attempt-passed` is folded by Measurement from the one store (spec round 18).
- `event-model/area.edn`: screen `cycle-progress`; flows `fence-to-scoreboard`, `self-check-failed-fails-cycle`, `fence-refused-fails-cycle`, `gate-attempts-exhausted`, `gate-refusals-fail-cycle`, `stage-timeout-fails-cycle`, `refused-score-fails-cycle`, `gate-attempt-repaired` (R2), each inside this context (Measurement's facts enter from the one store).
- `spec.feature`: the accepted scenarios (slice 04, `@item-04-001` to `@item-04-076`); `drafts.feature`: the rest (`../decisions.md`, "Spec round 6" 4).
- `seam.md`: the public seam of this context's methods (slice 04). Since spec round 18 the context is built only inside slice 06's one candidate, never standalone, and has no identity probe of its own.
- `allium/`: `context.allium` (budget, enums, config, contracts HarnessSelfCheckRun, FenceProbe, GateRoom, open questions), `regeneration-cycle.allium` (RegenerationCycle, the conclude-cycle policy, surfaces CycleStages, CycleProgress, BuilderRoomBoundary, MeasurementEvents), `harness-self-check.allium`, `clean-room.allium`, `gate-attempt.allium`.
- `domain-design.md`: the Files table, decisions, storm mapping, open questions.
