# Löyly ORC/Pi harness

This is the active regeneration harness. Pi provides the operator and builder sessions,
ORC owns workflow state and stage ordering, Gondolin supplies isolated execution rooms,
and a pinned OCI instrument oracle supplies independent calibration and grading assets.

The target contract lives in [`../../model/pi-harness/`](../../model/pi-harness/). The
architecture and current gaps live in [`../architecture.md`](../architecture.md). The
retired shell implementation is preserved in
[`../../archive/legacy-harness/`](../../archive/legacy-harness/) as a parity reference,
not as a runtime dependency.

## Validate a checkout

Requirements are Node.js/npm and the Clojure CLI. Real isolated or OCI-backed scenarios
also require the platform supported by Gondolin and `oras` on `PATH`.

```bash
cd .pi/harness
npm ci
npm test
./orc-worker/check.sh
npm run test:e2e
```

`npm test` covers the TypeScript adapters and Pi tool boundary. `orc-worker/check.sh`
checks and tests the Clojure workflow. `npm run test:e2e` validates Gherkin coverage and
then runs the end-to-end suite with Allure result generation.

## Run a regeneration from Pi

Open Pi at the repository root. The checked-in [`.pi/settings.json`](../settings.json)
loads `harness/extension.ts`, which exposes this control-plane sequence:

1. Call `prepare_regeneration_cut` with an immutable source commit and an execution
   backend. Keep the returned cut receipt.
2. Call `preview_slice_selection` for the desired model/spec slice.
3. Review the derived scope and call `approve_slice_selection` with the preview's
   `selectionId` and digest.
4. Call `submit_regeneration_cycle` with the request ID, approved selection, scope,
   target stack, and backend.
5. Call `wait_regeneration_cycle` for the terminal result. Use
   `get_regeneration_cycle_status` for polling and `inspect_builder_session` when the
   builder transcript itself needs diagnosis.

Use `host` for adapter development. Use `gondolin` for a measured regeneration: it mounts
the cut read-only, confines writes to the declared workspace, denies ambient host access,
and runs verification and grading independently of the builder.

ORC executes this graph:

```text
cut → harness-calibration → builder → instrument → oracle → verify
    → repair-verification → graders → instrument-conformance → record-cycle → result
```

The pinned instrument oracle must resolve and pass carried and uncarried calibration
before the builder starts. Verification may request up to three ORC-owned attempts, each
with a fresh Pi/Codex session. Independent grading begins only after verification passes.

## Evidence and generated state

Generated state belongs under `.pi/harness/.state/` and must not be committed. A completed
cycle correlates stage traces, binary verdicts, and Allure-compatible runtime evidence,
including browser exchanges, trace spans, domain events, projection snapshots, invariant
results, and runtime diagnostics. The application model remains language- and
framework-agnostic; stack-specific probes are adapters at the instrumentation boundary.

Treat missing, skipped, timed-out, or uncalibrated evidence as its own outcome. Never
collapse it into a pass or silently reduce the grading denominator.
