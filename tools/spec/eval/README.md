`loyly.html` is the evaluation report of the spec-v2 harness regenerating the full Löyly app in five shadow runs beside the legacy harness; `tools/spec/publish.sh` publishes it at `eval/loyly.html` on gh-pages.
Its sources are `.pi/harness/.state/shadow/run-0{1..5}/` (git-ignored run evidence), `.pi/spec/decisions.md` (rounds 7c, 7d and "The switch to the new harness"), `harness-impl/*/PROVENANCE.md`, and `docs/plan/01-booking-core/spec.feature` at `6d42c8e`.
It is a hand-written snapshot dated 2026-11-09, not regenerated; later runs do not update it.

`cycle-runner.html` is the later cycle-25 acceptance snapshot for slice 06. It reports the
61/61 behavior score, the same-cut legacy shadow and the explicit audited exception for the
filed failed profile. It is published at `eval/cycle-runner.html` alongside the historical
Löyly evaluation.
