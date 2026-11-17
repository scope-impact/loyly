# Cycle 24 instrument shadow, 2026-11-17

The gate-passed cycle-24 candidate was driven on the same scratch Löyly HTTP cut and app as
cycles 17–23: cut `45ee37f`, with archived legacy evidence under ignored
`.pi/harness/.state/shadow/legacy-fence-s3` and `s3-c17-legacy-score`. The cycle-24 replay is
under ignored `.pi/harness/.state/shadow/runner-s12-c24`. Its stub judge returned ORC level 2
with reasoning and feedback, and the run was handed the grader image.

| Stage | Same-cut observation |
| --- | --- |
| Fence | The runner's clean tree is `a3c66c830ffd9559daefd3d7f002eb0453ba7efe`; legacy's is `e70996d111fd66da3b0b54c111a6785c60c62955`. Comparing tree entries, the runner omits exactly two non-fenced gitlinks, `orc` at `4f8ec152` and `grain` at `dbf5b522`; all other entries match. |
| Candidate | The runner's `candidate-1` folder is byte-identical to the shared fixture's `impl`. |
| Gate | The runner halted at `gate` with `effect-could-not-run`: `Built-in gate not implemented; LOYLY_GATE_CMD is required`. The legacy gate passed the same app. |
| Verdicts and score | The runner reached no grading tick and recorded no scores. The archived legacy cycle filed 11 passes. |

The runner process exited 0 because it reports a domain halt through its seam; that exit code
does not make the cycle pass. This comparison establishes two instrument regressions on the
shared candidate. Cycle 24's independent black-box grade scored 31/61 with a failed profile;
the build is not accepted or promoted.
