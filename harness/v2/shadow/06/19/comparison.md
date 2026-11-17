# Cycle 19 instrument shadow, 2026-11-15

The cycle 19 candidate was driven on the same scratch Löyly HTTP cut and app used
in the cycle 17 and 18 comparisons: cut `45ee37f`, `impl` tree
`357bb59f7716fb5d407bf9bcc86bc8b46319ebf7`. The archived legacy run and
its score are preserved under ignored `.pi/harness/.state/shadow/legacy-fence-s3`
and `s3-c17-legacy-score`; the new runner replay is `runner-s7-c19`. The
historical scratch commit `249a90b` is unavailable in this checkout.

| Stage | Result on the same cut and app |
|---|---|
| Fence | The legacy and cycle 19 clean trees are both `e70996d111fd66da3b0b54c111a6785c60c62955`. The new room has one commit. Both trees retain `grain` and `orc` as mode `160000` gitlinks at the same target commit IDs. |
| Checklist | Both derived `01-001` through `01-011` in the same order. |
| Gate | Both passed the same candidate. The installed `impl/` directory compared byte for byte with the shared fixture. The runner recorded candidate digest `sha256:25863b60f5b3b3d206acdc21612a6aebc475071c6397696585ff7527588d9b95` and the sauna identity probe passed. |
| Verdicts | The runner filed 11 passes with evidence `sauna`; its `{id, verdict, evidence}` rows matched the legacy score file exactly. |
| Scoreboard | Both filed 11/11 as a baseline; the runner ended `passed`. |

The judge stub returned level `2`, the fixture rubric's pass band. An initial
exploratory replay with level `1` produced 11 fails as that same rubric requires;
it was a fixture setting, not a candidate change. This comparison closes the
cycle 18 gitlink difference on this shared candidate. It is one observed
instrument comparison, not a claim of general equivalence or acceptance of the
cycle 19 build. Acceptance also depends on the cycle 19 black-box score and
profile.
