# Cycle 18 instrument shadow, 2026-11-15

The cycle 18 candidate (`.pi/harness/.state/rooms/06-c18/harness-impl/06-cycle-runner/`)
was run against the same scratch Löyly HTTP cut and app used in the cycle 17
comparison: cut `45ee37f`, with the archived legacy harness, and `impl` tree
`357bb59f7716fb5d407bf9bcc86bc8b46319ebf7`. The scratch fixture files
are in `../17/fixture/good/`; its `impl/README.md` is the unchanged file at
`6d42c8e`. The historical scratch commit `249a90b` is unavailable in this clone.

The first full shadow attempt used the cycle 17 stub reply `level=2`; cycle 18
correctly rejected that value, so it filed no verdicts. The shadow model was
corrected to the public grader level `1` and the same cut and app were rerun as
`s5-c18`. This was a fixture correction, not a candidate edit.

| Stage | Result on the same cut and app |
|---|---|
| Fence | **Difference:** legacy's clean tree is `e70996d111fd66da3b0b54c111a6785c60c62955`; cycle 18's is `a3c66c830ffd9559daefd3d7f002eb0453ba7efe`. Both have one commit. The only path difference is that cycle 18 omitted the `grain` and `orc` gitlink entries (mode `160000`) retained by legacy. |
| Checklist | Both derived `01-001` through `01-011` in the same order. |
| Gate | Both passed the same candidate on poll 2 of 20 with a 10-second budget and identical probe text. Cycle 18 recorded candidate digest `sha256:25863b60f5b3b3d206acdc21612a6aebc475071c6397696585ff7527588d9b95`; its generated and installed candidate directories compared byte-for-byte. |
| Verdicts | Cycle 18 filed 11 passes with evidence `sauna`; its `{id, verdict, evidence}` rows were identical to the shared file scored by legacy. |
| Scoreboard | Both filed 11/11 and a baseline with no previous cycle; the cycle 18 run ended `passed`. |

The fence tree difference prevents acceptance under the instrument's side-by-side
rule, even though the other observed stages agree. The cycle 18 grading profile
also failed on 54 live-runner after captures, so this build is not accepted or
promoted. Raw scratch logs and stores remain in ignored
`.pi/harness/.state/shadow/` in this checkout.
