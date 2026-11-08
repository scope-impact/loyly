# Regeneration cycle (harness: fence and gate)

One cycle in the legacy shape and its one conclusion.

- `event-model/harness-self-check.edn`: HarnessSelfCheck (`record-harness-self-check`).
- `event-model/clean-room.edn`: CleanRoom (`prove-clean-room`).
- `event-model/regeneration-cycle.edn`: RegenerationCycle (`record-regenerated-candidate`, `pass-cycle`, `fail-cycle`; policy `conclude-cycle`).
- `event-model/gate-attempt.edn`: GateAttempt (`install-candidate-in-gate-room`, `record-gate-attempt`).
- `event-model/handoffs.edn`: `gate-attempt-passed`, consumed by Measurement.
- `event-model/area.edn`: screen `cycle-progress`; flows `fence-to-scoreboard`, `self-check-failed-fails-cycle`, `fence-refused-fails-cycle`, `gate-attempts-exhausted`, `gate-refusals-fail-cycle`, `stage-timeout-fails-cycle`, `refused-score-fails-cycle`, `gate-attempt-repaired` (R2), each inside this context (Measurement's facts enter as handoffs).
- `spec.feature`: the accepted scenarios (slice 04, `@item-04-001` to `@item-04-076`); `drafts.feature`: the rest (`../decisions.md`, "Spec round 6" 4).
- `seam.md`: the public seam of this context's candidate (slice 04).
- `allium/`: `context.allium` (budget, enums, config, contracts HarnessSelfCheckRun, FenceProbe, GateRoom, open questions), `regeneration-cycle.allium` (RegenerationCycle, the conclude-cycle policy, surfaces CycleStages, CycleProgress, BuilderRoomBoundary, MeasurementHandoffs), `harness-self-check.allium`, `clean-room.allium`, `gate-attempt.allium`.
- `domain-design.md`: the Files table, decisions, storm mapping, open questions.
