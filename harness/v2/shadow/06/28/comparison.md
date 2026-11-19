# Cycle 28 instrument shadow, 2026-11-18

The gate-passed attempt-3 candidate was replayed on the preserved legacy HTTP cut
`45ee37facdef45cc067ec93d26964b3220cc0b4c` (`.pi/harness/.state/shadow/s1-c17`).
The archived legacy run is in ignored `legacy-fence-s3` and
`s3-c17-legacy-score`; the new ORC replay is ignored `runner-s15-c28`, driven by
`shadow_gate_c28.py`. The shadow used a fixture grader host that returned observed
`sauna` evidence for each item. It did not substitute for the independent 65-item
black-box grade or exercise the live Pi grader VM.

| Stage | Same-cut observation |
| --- | --- |
| Fence | The ORC and archived legacy clean rooms both have Git tree `e70996d111fd66da3b0b54c111a6785c60c62955`. |
| Installed candidate | Recursive comparison of `runner-s15-c28/runs/R-s15-c28/gate-1/impl` with `s1-c17/impl` found no differing file. ORC recorded candidate digest `sha256:7a94838f847dc067045b225056da7dda7aca27d927e6a6af3338434f9630e458`. |
| Gate | Both passed the HTTP app. The ORC gate recorded `BOOT 20 10`, `ANSWERED 2`, and the marker/probe evidence; its run passed with 11 effects, one attempt. |
| Score | The ORC Measurement receipt filed 11/11 passes. Its ordered `(item ID, verdict, evidence)` rows matched the legacy `scores.json` projection byte for byte. |
| Scoreboard | The ORC `measurement/scoreboard` query returned a baseline of 11/11, rate `100.0`, no previous cycle. The archived legacy `scoreboard.clj` over the same 11-row record reported `11/11 checklist items pass` and a baseline with no previous cycle. |
| Result projection | ORC wrote 11 tagged Allure result JSON files. Legacy has no Allure projection; it does not alter the shared score. |

The same candidate also passed the slice-06 independent gate on attempt 3. That gate's
identity fixture had a retired gap (`01-001`, `01-003`) and the Grain check found two
completed `cycle.v2` runs, two completed `grade.v2` child ticks, valid event fields
and processor checkpoints. Gate attempts 1 and 2 failed at, respectively, JSON event
projection and the missing built-in HTTP gate; their logs remain ignored under
`.pi/harness/.state/logs/`.

Evidence SHA-256: scratch driver `adc6be3f98128e9f0e0fe89ce2a76c549128df110ebd2a43ce54c9d7ded5eafd`;
ORC responses `e897063c601950415c0e12b74e4de0179a6c41751eea6ee3c470ae56e9ff7ac1`;
attempt-3 gate log `9789a0c1d5f26f57d3c394bf20a7b4cda238e6b6e04aaa19f23f3d44aefad3a0`.

This is one same-cut comparison, not a general equivalence claim. The builder's
`UNCARRIED.md` lists 22 of 65 items after attempt 3, so acceptance depends on the
independent full checklist score and profile. The accepted cycle-26 runner remains in
service while those are pending.
