# Cycle 25 instrument shadow, 2026-11-17

The gated cycle-25 repair candidate was run against the same scratch Löyly HTTP cut and app
as the legacy harness and cycle 23. The archived legacy evidence is under ignored
`.pi/harness/.state/shadow/legacy-fence-s3` and `s3-c17-legacy-score`; the runner replay is
`runner-s13-c25`. The stub judge returned ORC level 2 with reason `sauna`.

| Stage | Same-cut observation |
| --- | --- |
| Fence | The candidate, cycle 23 and legacy clean trees all have Git tree `e70996d111fd66da3b0b54c111a6785c60c62955`. |
| Candidate | The runner's installed `gate-1/impl` is byte-identical to the archived legacy fixture and cycle-23 installed app. |
| Gate | The runner passed attempt 1 and ended the run `passed`; the archived legacy gate passed the same app. |
| Verdicts and score | The runner filed 11 passes. Ordered `(item id, verdict, evidence)` rows match the archived legacy score exactly. |

This is one observed comparison on a shared cut and app. It establishes no broader
equivalence. Cycle 25's independent black-box score is 61/61, while its filed profile has
one retry-naming finding; that profile is investigated separately and remains failed.
