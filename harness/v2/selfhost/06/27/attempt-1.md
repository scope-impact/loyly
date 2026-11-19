# Cycle 27 self-host attempt 1: stopped at stamp

Controller: accepted cycle-26 `harness-impl/06-cycle-runner` on commit `9bf12eda3f9c48b7c19336e6d5fd4f99b64411a4`.
Run `selfhost-27`, cycle `C-selfhost-27`, cut `K-selfhost-27`. The operator approved the
64-item selection and grader digests in `harness/v2/preflight/06/27.md`. The accepted ORC
runner was launched through `harness/loyly-run` with Pi builder host
`.pi/harness/bin/selfhost-builder.sh` and a Pi model bridge for the accepted `grade.v1`.
The bridge was smoke-tested with a live Terra request and stopped after this attempt.

Result: the runner created one ORC tick, issued `specification/stamp-slice`, recorded 64
`specification/scenario-stamped` events and one `cycle-runner/effect-recorded`, then
`cycle-runner/run-halted` at `stamp` with `refused-before-the-cycle` and detail
`Stamped identities differ from the cut`. It launched no Pi builder, built no clean room,
ran no gate and filed no score. The accepted implementation was not modified.

The accepted runner's `loyly.domain/stamp` assigns identities by ordinal position in a fresh
ledger: `06-001` through `06-064`. Its `loyly.flow/stamp` compares that vector to the
approved spec's fixed tags. Slice 06 has 64 active items with retired ID gaps and ends at
`06-076`; the vectors differ. Legacy `harness/stamp_item_ids.clj` preserves stamped IDs and
`harness/gen_checklist.clj` derives the checklist from those tags. The cycle-26 same-cut
shadow tested a contiguous one-item fixture, so it did not expose this gap. This is a
controller defect at a behavior the existing spec requires, not a Pi host or model failure.

Preserved local evidence (gitignored):

- `.pi/harness/.state/loyly/selfhost-27/driver/final-progress.json`, SHA-256 `24a647d8ee20f054cc06f61765e352d40bfe058c282728130621f36bf7345b0a`
- `.pi/harness/.state/loyly/selfhost-27/runner-store.sqlite`, SHA-256 `218dc4bf7c6b643953c9f83e5b61663a50542250fd99f6de4cba786ad85db09a`

Do not promote or score this attempt. A successor controller must accept approved stamped
IDs with retired gaps while keeping Measurement's known-item protection and the public
Specification contract. Prove that case with a fresh store and with a prior ledger, then
run the same approved selection through the gate, independent Pi grader and legacy shadow.
