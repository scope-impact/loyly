# Cycle 26 instrument shadow, 2026-11-18

The gate-passed cycle-26 candidate was replayed on the same scratch HTTP cut and app as
the archived legacy harness and cycle 25. The project cut is `45ee37f`; the archived
legacy result is under ignored `.pi/harness/.state/shadow/legacy-fence-s3` and
`s3-c17-legacy-score`. The cycle-26 replay is `runner-s14-c26`, driven by
`shadow_gate_c26.py` with the same stub rubric and judge reply (`sauna`, ORC level 2).

| Stage | Same-cut observation |
| --- | --- |
| Fence | The runner's clean tree and archived legacy clean tree both have Git tree `e70996d111fd66da3b0b54c111a6785c60c62955`. |
| Installed candidate | Recursive file comparison of `runner-s14-c26/runs/R-s14-c26/gate-1/impl` with the shared legacy fixture `s1-c17/impl` found no difference. The runner recorded candidate digest `sha256:25863b60f5b3b3d206acdc21612a6aebc475071c6397696585ff7527588d9b95`. |
| Gate | Both passed the app. The runner's gate passed attempt 1 with boot margin 18 of 20 polls, and the run ended passed with eleven effects. |
| Score | The runner filed 11/11 passes. Its ordered `(item id, verdict, evidence)` rows matched the archived legacy `scores.json` byte for byte after both were projected to those fields. |
| New result projection | The runner wrote exactly 11 Allure result JSON files; all had `passed` status and an `item-01-NNN` tag. Legacy has no Allure output. This is the instrument extension specified by 06-074 and does not change the shared score. |

This is one same-cut observation, not a general equivalence claim. Cycle 26's independent
score is 62/62 with a clean reconciled profile; the originally failed 06-070 watcher
capture, its fresh retry, and the 06-061 fixture retry are separately preserved in the
cycle record. The cycle-25 profile exception does not transfer to this candidate.
