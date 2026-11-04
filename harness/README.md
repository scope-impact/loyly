# The legacy harness tools, measuring the Pi/ORC harness

These are the legacy fence, gate, and instrument tools from
[`archive/legacy-harness/`](../archive/legacy-harness/README.md), adopted here to regenerate
and measure the Pi/ORC harness itself. The product they measure is the harness; its spec is
`.pi/model/plan/NN-slug/spec.feature` (`PLAN_DIR` in `config.sh`). ORC runs the cycle and calls
these tools from its stages: fence (`fence.sh`), regenerate (a Pi builder session), gate
(`verify.sh`), score (the sealed grader and `score.clj`), and scoreboard (`scoreboard.clj`).
The archive stays untouched as the parity reference.

## Differences from the archive, each with its reason

- `hjson.clj` resolves the plan tree from the `PLAN_DIR` line of `harness/config.sh` instead of
  hard-coding `docs/plan`. This repository already uses `docs/plan` for the Löyly product, so
  the harness spec needs its own tree. Reading the file, not the environment, keeps the tools
  and `modules.sh` agreeing and stops a parent shell leaking its own value.
- `spec.feature` `@harness-054` and its oracle check follow that change: the tools resolve the
  declared tree, so the line reads ok and the exit code is 0.
- `modules.sh` declares `harness/Makefile` as a local file: it is this repository's runtime
  bootstrap, not part of the instrument.
- `config.sh` declares no `SIBLING_REPOS`: no other repository carries this harness, so
  `modules.sh` compares nothing and says so.

## The goldens, and why they moved

**2026-11-04: the instrument was adopted for a different product.** The checklist golden in
`oracle/` had been recorded for the Löyly product spec under `docs/plan/`. Here the same tools
measure the harness spec under `.pi/model/plan/`, so the derived checklist is a different list
(74 items). This is neither an instrument behavior change nor a reworded spec: the measured
spec itself changed. The golden was re-recorded with `harness/oracle.sh record` for that reason
and no other.

**2026-11-04: the spec grew.** `@item-02-010` (the running harness proves its identity by
serving its cycle contract) was added, so the derived checklist gained one item (75). The
golden's diff is additions plus the one `total` line, which is the test for this case.
