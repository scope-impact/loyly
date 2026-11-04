# Löyly ORC/Pi harness

This is the active regeneration harness. Pi provides the operator and builder sessions,
ORC owns workflow state and stage ordering, Gondolin supplies isolated execution rooms,
and a pinned OCI instrument oracle supplies independent calibration and grading assets.

The target contract lives in [`../../model/pi-harness/`](../../model/pi-harness/). The
architecture and current gaps live in [`../architecture.md`](../architecture.md). The
retired shell implementation is preserved in
[`../../archive/legacy-harness/`](../../archive/legacy-harness/) as a parity reference,
not as a runtime dependency.

## Bootstrap and validate a checkout

From the repository root, `make -C harness bootstrap` installs the harness dependencies,
builds the cut, instrument, grader, and TypeScript/Rust/Java/Rails OCI images, converts them
to Gondolin assets, and verifies every asset under `.pi/harness/.state/assets/`. It requires
Docker, Node.js/npm, and the platform supported by Gondolin. It does not build the Löyly app,
publish oracles, or submit regeneration.

For tests, the Clojure CLI is also required. Real OCI-backed scenarios require `oras` on
`PATH`.

```bash
cd .pi/harness
npm test
./orc-worker/check.sh
npm run test:e2e
```

`npm test` covers the TypeScript adapters and Pi tool boundary. `orc-worker/check.sh`
checks and tests the Clojure workflow. `npm run test:e2e` validates Gherkin coverage and
then runs the end-to-end suite with Allure result generation.

## OCI oracles

The accepted instrument and product oracles are published to GHCR and runtime configuration
pins their immutable descriptor digests. The `accepted` tags are lookup aliases only:

```text
ghcr.io/scope-impact/loyly/instrument-oracle:accepted
ghcr.io/scope-impact/loyly/product-oracle:accepted
```

Authenticate ORAS to `ghcr.io` with `read:packages` access before running OCI-backed checks.
Local OCI publishing layouts and generated Gondolin images live under `.state/`; the
`config/` tree contains tracked declarations only. Set `PI_HARNESS_INSTRUMENT_ORACLE_REF`
or `PI_HARNESS_PRODUCT_ORACLE_REF` to override a pinned registry reference for development.

The product oracle is an independently managed, pinned black-box artifact. This repository
must not build, package, or publish grader implementation or calibration. The harness
publisher builds only the instrument oracle from harness sources. Instrument calibration
goldens live under [`test/fixtures/instrument-oracle/`](test/fixtures/instrument-oracle/),
while [`config/instrument-oracle.json`](config/instrument-oracle.json) declares the harness
inputs whose aggregate digest is bound into that oracle. There are no checked-in generated
OCI source or layout trees.

The product artifact has one whole-system `catalog.json` layer
(`application/vnd.loyly.product-oracle.catalog.v1+json`), followed by its runnable fixture
server, non-authoritative observability policy, and grader files. Catalogue entries
are keyed by stable Gherkin item ID and full scenario digest. A measured cycle validates and
materializes only its approved entries; unrelated catalogue entries and unrelated model files
do not enter the denominator. Initial generation neither resolves nor fabricates this artifact.

Preview the instrument OCI layout without publishing:

```bash
npm run publish:oracles -- --no-push --output /tmp/loyly-oracles
```

Publish from a clean checkout:

```bash
npm run publish:oracles
```

Publishing tags the instrument artifact with the full Git commit and `accepted`, records the
Git revision and repository URL as OCI annotations, verifies the remote descriptor digest, and
updates only the instrument oracle reference. Product oracle publication and digest changes
are managed independently. Generated Gondolin assets under `.state/assets` are disposable
build outputs and are not instrument source files; the tracked build declarations and harness
code are the instrument inputs.

## Run generation and regeneration from Pi

Open Pi at the repository root. The checked-in [`.pi/settings.json`](../settings.json)
loads `harness/extension.ts`, which exposes this control-plane sequence:

1. Call `prepare_source_cut` with an immutable source commit and an execution
   backend. Keep the returned cut receipt.
2. Call `preview_slice_selection` for the desired model/spec slice.
3. Review the derived scope and call `approve_slice_selection` with the preview's
   `selectionId` and digest.
4. Call `submit_approved_slice` once with a request ID, approved selection, scope, target
   stack, and backend. ORC routes to measured initial generation when no persisted candidate
   exists for the project/container/component, or measured regeneration from the latest
   persisted candidate when one exists. The operator does not select the operation.
5. Call `wait_harness_operation` for the returned task. Use
   `get_harness_operation_status` for polling and `inspect_builder_session` when the builder
   transcript itself needs diagnosis.

Use `host` only for adapter development. Use `gondolin` for generation and measured
regeneration: it mounts
the cut read-only, confines writes to the declared workspace, denies ambient host access,
and runs verification and grading independently of the builder.

ORC keeps the workflows separate. Initial generation is independently verified and baseline
graded, then yields a non-authoritative oracle proposal for manual approval:

```text
cut → calibration → builder → instrument → affordance → verify → repair-verification
    → baseline-grade → instrument-conformance → fresh-sample loop
    → Allure → propose-oracle
```

Failed verification starts a fresh Gondolin builder session from the preceding candidate and
read-only verifier evidence. Failed black-box grading or instrument conformance can only start
another clean sample from the approved cut; that builder receives neither the candidate nor any
evaluation evidence. A generated candidate becomes routable lineage only after exact manual
approval and publication of the resulting oracle proposal. Every fresh evaluation sample gets
its own bounded verification-repair loop before black-box grading can run.

Measured regeneration requires pinned oracles and executes:

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
