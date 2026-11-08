# Measurement (instrument: score and scoreboard)

The checklist, the one filed measurement per cycle, and the scoreboard to a person.

- `event-model/checklist.edn`: Checklist (`derive-checklist`).
- `event-model/cycle-measurement.edn`: CycleMeasurement (`file-item-verdicts`, `file-score`, `fail-score-stage`).
- `event-model/scoreboard.edn`: Scoreboard (`report-scoreboard`; policy `report-scoreboard-once`).
- `event-model/handoffs.edn`: `measurement-filed`, `score-stage-failed`, `scoreboard-reported`, consumed by the Regeneration cycle to conclude it.
- `event-model/area.edn`: screen `instrument-desk`; flows `stamping-to-checklist` (from a stamped scenario to the checklist), `scoring` (from a passed gate attempt to the filed measurement or the failed score stage) and `scoreboard-restates-moved-item-set`.
- `spec.feature`: the accepted scenarios (slice 02, `@item-02-001` to `@item-02-043`); `drafts.feature`: the rest, two waiting on a seam or schema change and the open grader drafts, parked (`../decisions.md`, "Spec round 6" 4).
- `seam.md`: the public seam of this context's candidate (slice 02).
- `allium/`: `context.allium` (values, contracts Scoring, RateFormat, Comparison, open questions), `checklist.allium` (surface InstrumentDesk), `cycle-measurement.allium` (surfaces VerdictFilingSeam, ScoreStage, MeasurementReceipt), `scoreboard.allium` (policy ReportScoreboardOnce, surface ScoreboardReport).
- `domain-design.md`: the Files table, decisions, storm mapping, open questions.
