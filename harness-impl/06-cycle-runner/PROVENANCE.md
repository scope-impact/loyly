# Provenance: cycle runner, accepted cycle 26

The operator accepted the complete fresh cycle-26 candidate on 2026-11-18. The generated
39-file source was copied byte for byte from the fenced room; only this provenance was
added during promotion. Cycle 25 remains recoverable in Git history.

| Evidence | Result |
| --- | --- |
| Spec cut | `a0c700f7cf969fd660fdf108be09ce881c99f412`, slice 06 with 62 stamped items including Allure item 06-074 |
| Fenced room | `.pi/harness/.state/rooms/06-c26/harness-impl/06-cycle-runner/` |
| Candidate source | 39 files excluding `.cpcache/`, `target/` and this provenance; SHA-256 `08ec27b707c9ef338288f7215caf8d5cafdd030c2b7e3baed4aa5ff3d8348e07` in both room and installed copy, over sorted `relative-path<TAB>file-sha256<LF>` rows; recursive file comparison is identical |
| Independent gate | `.pi/harness/.state/logs/gate-c26-slice06.log`, passed on attempt 1 with suite, fresh stdio identity and Grain/ORC store checks |
| Installed-copy suite | `.pi/harness/.state/logs/test-promoted-repo-slice06-c26.log`, 14 tests, 269 assertions, zero failures or errors |
| Independent score | `harness/v2/cycles/06/26/`, 62/62; all 61 shared cycle-25 items remain passing; new 06-074 passed |
| Grading profile | Filed `clean`, 62 cases, no problems and live JVM diagnostic; `profile-retry-audit.md` preserves initial captures and fresh 06-061/06-070 retries |
| Allure results | 62 verified tagged result files, one per checklist item, with profile attachments; official Allure CLI rendered 62 passed |
| Legacy instrument shadow | `harness/v2/shadow/06/26/comparison.md`, same clean tree, installed app, gate and ordered 11 verdicts as legacy on one HTTP fixture |
| Score commit | `aabf2da824b306d113d881d6b4d864151350f288` |

The builder's own named tests carried 61/62 items; item 06-062 required a standalone copy
outside the builder's permitted directory and passed the independent black-box grader.
The original 06-070 profile watcher exceeded its 30-second bound and failed to observe
QEMU. The fresh retry used a bounded watcher and observed it; both raw event sets remain
filed. This acceptance uses the clean final profile, not cycle 25's exception.

Product grader calibration and full workflow sealing remain open under ADR 0003. The old
`harness/loyly/` public launcher still needs migration. The accepted build is the running
build; future source changes require a new regeneration or approved repair cycle.
