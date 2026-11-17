# Cycle 17 instrument shadow, 2026-11-15

The cycle 17 candidate (`.pi/harness/.state/rooms/06-c17/harness-impl/06-cycle-runner/`)
was compared with the archived legacy instrument on the same HTTP candidate files.
The scratch project was cut from `6d42c8e`, with `harness/` replaced by
`archive/legacy-harness/`. The historical scratch commit `249a90b` cited in the
decisions record is not present in this clone, so this is a new, narrower gate
comparison rather than a replay of the five switch shadow runs.

The scratch harness oracle passed `check.sh --local`. Its `verify.sh` SHA-256 was
`ee1e72df898e22286f49cc319c7a96aa49cacba2ec2059cf801f9a3e7d7ce795`.
The app was a static HTTP fixture with a passing `test.sh`, `start.sh PORT` using
Python's HTTP server, and the legacy sauna marker probe. The runner's builder
adapter copied the same `impl/` files into its fenced room; byte comparison of
all four generated files succeeded for the good version, and a directory
comparison succeeded for the bad version.
The three executable/served fixture files and the one-line bad variant are kept
under `fixture/`; the fourth file was the cut's unchanged `impl/README.md`.

| Scratch cut | Candidate | Legacy `verify.sh` | Cycle 17 gate |
|---|---|---|---|
| `45ee37f`, `impl` tree `357bb59f7716fb5d407bf9bcc86bc8b46319ebf7` | sauna marker | pass, answered on poll 2 of 20; 18 polls unused | pass, `BOOT 20 10`, `ANSWERED 2`, same probe text; candidate digest `sha256:25863b60f5b3b3d206acdc21612a6aebc475071c6397696585ff7527588d9b95` |
| `ff9e83f`, `impl` tree `8b9afa26a59899c07b003239a4d93cdb994a1f59` | wrong marker | fail after boot: page does not serve the app | fail after boot: `identity-probe-failed The served page did not carry the application marker`; candidate digest `sha256:fa880a40c983a50163259c24f0c1ec13d1dc84f445c1647e671099e755a7a012` |

These first two runner runs were given no `LOYLY_GRADER_IMAGE`, so they stopped
at grading after the gate result. That isolates the positive and negative gate
comparisons.

## Full shadow on the good candidate

The good cut `45ee37f` was then run through the whole cycle with a stub model and
the same `impl/` bytes. The researcher returned `sauna` for each item; the judge
returned a reason and pass level. The new runner used one Gondolin VM per item,
finished all 11 effects, filed one score, and ended `passed`.

| Stage | Comparison |
|---|---|
| Fence | Legacy `fence.sh` and the runner produced the same tree `e70996d111fd66da3b0b54c111a6785c60c62955`; both rooms have one root commit and no `impl` or `harness/cycles` reachable. |
| Checklist | Both derived `01-001` through `01-011`, in the same order. |
| Gate | The unchanged candidate answered on poll 2 of 20; both gates passed with the same probe text. Generated and installed `impl/` directories compared byte-for-byte. |
| Verdicts | The runner's 11 item verdicts were all pass with evidence `sauna`. The exact same verdict file was handed to legacy `score.clj`; its filed per-item `{id, verdict, evidence}` rows were identical. |
| Scoreboard | Both reported 11/11 with no previous cycle, a baseline. The runner ended `passed`. |

This is one full instrument comparison on a passing candidate, plus a negative
gate comparison on a wrong-marker candidate. It satisfies the side-by-side
instrument check for the observed stages and inputs. It does not establish
general equivalence for all candidates. Cycle 17's failed profile still blocks
acceptance. Raw scratch logs and stores remain under ignored
`.pi/harness/.state/shadow/` in this checkout.
