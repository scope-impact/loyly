# Cycle 22 instrument shadow, 2026-11-16

The gate-passed cycle 22 candidate (`06-c22`) was driven on the same scratch
Löyly HTTP cut and app used in the cycle 17–20 comparisons: cut `45ee37f`,
`impl` tree `357bb59f7716fb5d407bf9bcc86bc8b46319ebf7`. The archived legacy
result is under ignored `.pi/harness/.state/shadow/legacy-fence-s3` and
`s3-c17-legacy-score`; the new runner replay is `runner-s10-c22`. The scratch
rubric contained `shadow rubric`, and the stub judge returned ORC binary level
`2` with a reason and feedback.

| Stage | Result on the same cut and app |
| --- | --- |
| Fence | Both clean trees have tree ID `e70996d111fd66da3b0b54c111a6785c60c62955`. |
| Candidate | The installed candidate folder compared byte for byte with the shared fixture `s1-c17/impl`. Its recorded digest was `sha256:25863b60f5b3b3d206acdc21612a6aebc475071c6397696585ff7527588d9b95`. |
| Gate | The runner passed attempt 1, with boot margin 18 of 20 polls. The archived legacy gate passed the same app. |
| Verdicts and score | The runner filed 11 passes with reason `sauna`; the ordered `(item id, verdict, evidence)` rows match the archived legacy score exactly. The run and its cycle ended `passed`. |

This replay closes cycle 20's observed judge-level mismatch on the shared
fixture. It is one instrument comparison, not a claim of general equivalence.
Cycle 22's independent black-box grade was 55/61 and its profile failed on two
capture gaps; the build is not accepted or promoted.
