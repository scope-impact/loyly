# Cycle 20 instrument shadow, 2026-11-15

The gate-passed attempt-3 candidate (`06-c20-a3`) was driven on the same scratch
Löyly HTTP cut and app used in the cycle 17–19 comparisons: cut `45ee37f`, `impl`
tree `357bb59f7716fb5d407bf9bcc86bc8b46319ebf7`. The archived legacy
result is under ignored `.pi/harness/.state/shadow/legacy-fence-s3` and
`s3-c17-legacy-score`; the new runner replay is `runner-s8-c20`. The scratch
rubric file contained only `shadow rubric`; it did not define numeric bands.
The stub judge replied with level `2`, a reason and feedback. The pinned ORC
binary `discrete-scale` maps level `1` to score `0.0` and level `2` to `1.0`.

| Stage | Result on the same cut and app |
|---|---|
| Fence | Both clean trees are `e70996d111fd66da3b0b54c111a6785c60c62955`. Both retain `grain` and `orc` mode `160000` gitlinks at the same target commit IDs. |
| Checklist | Both derived `01-001` through `01-011` in the same order. |
| Gate | Both passed the same installed candidate. The installed `impl/` directory compared byte for byte with the shared fixture. The runner observed answer on poll 2 of 20 and reported margin 18. |
| Verdicts and score | **Difference:** legacy filed 11 passes. The runner recorded no judge scores, its `file-verdicts` effect was refused for `zero-verdicts`, and its run failed at the score stage. |

The cycle 20 attempt-3 repair recognized numeric marker levels `1` and `0` as
pass and fail. Under the now-explicit ORC binary scale, level `2` is pass;
the candidate did not score those replies. This shadow mismatch blocks
acceptance under `AGENTS.md`'s instrument exception, independently of the
61-item black-box grade and profile. The generated candidate was not edited.

A supplemental scratch replay kept the cut, candidate and model reply text
fixed and changed only the numeric level from `2` to `1` (`runner-s9-c20-level1`).
It filed 11 passes and ended `passed`. That confirms the adapter's numeric-band
mismatch; it does not make the level-`1` reply a pass under the ORC binary scale.
