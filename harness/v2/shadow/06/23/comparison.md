# Cycle 23 instrument shadow, 2026-11-17

The gate-passed attempt-2 Astra candidate (`06-c23-a2`) was driven on the same
scratch Löyly HTTP cut and app used in the cycle 17–22 comparisons: cut
`45ee37f`, `impl` tree `357bb59f7716fb5d407bf9bcc86bc8b46319ebf7`.
The archived legacy result is under ignored
`.pi/harness/.state/shadow/legacy-fence-s3` and `s3-c17-legacy-score`; the new
runner replay is `runner-s11-c23`. The scratch rubric contained `shadow rubric`,
and the stub judge returned ORC binary level `2` with a reason and feedback.

| Stage | Result on the same cut and app |
| --- | --- |
| Fence | Both clean trees have tree ID `e70996d111fd66da3b0b54c111a6785c60c62955`. |
| Candidate | The installed `gate-1/impl` folder compared byte for byte with the shared fixture `s1-c17/impl`. Its recorded digest was `sha256:25863b60f5b3b3d206acdc21612a6aebc475071c6397696585ff7527588d9b95`. |
| Gate | The runner passed attempt 1, with boot margin 18 of 20 polls. The archived legacy gate passed the same app. |
| Verdicts and score | The runner filed 11 passes with reason `sauna`; the ordered `(item id, verdict, evidence)` rows match the archived legacy score exactly. The run and its cycle ended `passed`. |

This is one observed instrument comparison, not a claim of general equivalence.
Cycle 23's independent black-box grade was 59/61 with a clean profile. Items
06-047 and 06-066 failed, and 06-066's surprise records a possible conflict
between its scenario and the sealed instruction's wording. The build is not
accepted or promoted.
