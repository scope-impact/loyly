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
- `verify.sh` and `probe.sh` drive the harness's real seam, JSON-RPC over stdio
  (`.pi/model/rpc.yaml`), instead of HTTP: the harness is not a web product, and a page served
  only for the gate is exactly what a builder can fake (harness-regen-3 served a static page).
  The legacy rules stay: the instrument is checked first, the suite is trusted by exit code
  only, the boot is fresh and timed with its margin reported, and identity is proved by domain
  behavior (the cycle contract's name and stage order). The boot budget is thirty seconds, not
  ten (`@item-02-011`): the harness starts a JVM and ORC's durable system, 11.0s cold.
- `config.sh` declares no `PORT`; the harness has none.
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

**2026-11-05: an item's requirement changed, and the spec grew.** `@item-02-010` now proves
identity over the stdio seam instead of an HTTP page, so its name changed; that is a recorded
change to what the spec requires, not an instrument change. Five items were added
(`@item-02-011`, `@item-09-001` to `@item-09-004`). The golden's diff removes only that one
name line and the `total` line (75 → 80).

**2026-11-05: the first scored cycle.** Cycle 01 (`harness-regen-5`) was scored by the approved
grader `sha256:b9db122a…` (7 items, calibrated carried/uncarried): 6/80 pass, 73 unreached and
recorded as fail. `harness/oracle.sh record` recorded `oracle/scoreboard-01.golden` for it; the
checklist golden did not move.

**2026-11-05: the spec grew.** The first regenerate attempt now runs one fresh builder per
selected slice: `@item-02-012` and `@item-02-013` were appended to the end of slice 02. The
golden's diff is additions plus the one `total` line (80 → 82). The approved grader's scenarios
did not change, so it stays approved.

**2026-11-05: an item was reworded.** `@item-01-007` read as if `submit-cycle` took a
selection, which contradicted `rpc.yaml`; cycle 05's builder followed it and refused every bare
submit. It now names the slice path ("An unapproved partial selection cannot start a slice
cycle"), and `rpc.yaml` states that `submit-cycle` regenerates the whole plan as its approved
selection. That is wording replaced, a recorded reason; the golden's diff is that one name line.
The approved grader does not cover `01-007`, so it stays approved.

**2026-11-05: the spec grew.** Cycle 08's nine slice builders stopped after 6 to 26 tool calls
each, leaving most of their slices listed in `UNCARRIED.md`. `@item-02-014` was appended to the
end of slice 02: ORC continues a slice builder's session, at most three times, while it lists
its own slice's items uncarried. The golden's diff is additions plus the one `total` line
(82 → 83). No approved grader's scenarios changed, so all three stay approved.
