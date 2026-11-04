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

From the repository root, `make -C harness bootstrap PROJECT=<project-id>` installs the
harness dependencies, builds the cut, instrument, grader, and TypeScript/Rust/Java/Rails OCI
images, converts them to Gondolin assets, and verifies every asset under
`.pi/harness/.state/assets/`. It requires the conventional runtime inputs under
`harness/<project-id>/`; `PROJECT` defaults to `loyly`. It also requires Docker, Node.js/npm,
and the platform supported by Gondolin. It does not build the selected product implementation,
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

## Runtime archives and target platforms

Grading rooms deny egress, so they never pull images. The command
`make -C harness runtime-images PROJECT=<project-id>` (part of bootstrap) builds or pulls every
image a grading room starts and saves one archive per target cell under `.state/runtime-images/`;
the room mounts it read-only and runs `docker load`. One archive may carry several images
(base, sidecars, gateway).

A target is **language × platform**. Besides the bare language stacks, two platforms are
available for TypeScript so far:

| Platform | Public seam (`app:8080`) | Sidecars | Runtime base |
|---|---|---|---|
| `aws-serverless` | SAM gateway (`config/build/sam-gateway/`) → Lambda Runtime Interface Emulator | `dynamodb-local` | `public.ecr.aws/lambda/nodejs:22` (bundles AWS SDK v3) |
| `container-postgres` | the candidate's own HTTP server | `postgres:16-alpine` | `harness/<project-id>/Dockerfile.typescript-postgres` (`pg` under `/node_modules`) |

The SAM gateway replaces `sam local start-api`, which needs a Docker socket and registry
pulls. It reads the candidate's `template.yaml`, routes `HttpApi` (payload 2.0) and `Api`
(1.0) events to the function container, and creates every `AWS::DynamoDB::Table` and
`AWS::Serverless::SimpleTable` on `dynamodb-local`, so a broken template fails startup. Local
table names are the logical ID unless a literal `TableName` is declared. It currently supports
exactly one function and serializes invocations, because the emulator serves one at a time.
Its unit tests run with `cd config/build/sam-gateway && npm install && npm test`.

**The VM side is proven before any cycle starts.** Bootstrap ends with
`make -C harness verify-platforms`, which runs `scripts/verify-platforms.mjs --all` in a
deny-egress Gondolin grader VM: every archive must load and every image it carries must start
with networking disabled, and every platform probe under `fixtures/platforms/` must pass end to
end through its public seam. The script refuses to run if the VM can reach a registry; Gondolin
answers blocked requests with `403 blocked by request policy`, so curl's exit status alone does
not prove denial. Use `node scripts/verify-platforms.mjs <probe> host` to debug a probe on host
Docker. Overrides: `PI_HARNESS_PROBE_MEMORY` (`4G`), `PI_HARNESS_PROBE_ROOTFS_SIZE` (`12G`),
`PI_HARNESS_PROBE_CPUS` (`4`).

Rust and Java carry a fixed offline library set (`harness/<project-id>/platform/`):
`pi-harness/rust:1.91-web` vendors axum, tokio, serde, serde_json, and reqwest (tests) from a
pinned `Cargo.lock`, and `pi-harness/java:21-web` runs Maven 3.9.11 offline against a
repository seeded from a template pom (Jackson, JUnit 5, and pinned plugins). The builder room
and the grading archive use the same image, and the manifest sits at `/opt/platform/` in both.

Known gaps, not yet wired into ORC (the production prompt and stack registry still know only
bare language stacks):

- The gateway does not yet enforce the template `Timeout` (Lambda default 3 s), still accepts a
  trailing slash on literal routes, and collapses duplicate query parameters instead of joining
  them with commas (2.0) or adding `multiValue*` fields (1.0).
- A candidate's own `impl/**/node_modules` would shadow the platform package set; conformance
  must reject it.
- The Rails offline image ships no app server (no puma or webrick).
- `config/build/sam-gateway/` is grading machinery but is not yet an instrument-oracle input.
- Archives are not byte-reproducible across rebuilds; pin images by digest, not archive checksum.

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
2. Call `inspect_slice_composition` so the active Pi session can explore the cut's existing
   slices and stable Gherkin items using whatever skills and tools the operator allows.
3. Call `preview_slice_selection` with the session's proposed slice and optional item subset.
4. Review the deterministic result and call `approve_slice_selection` with the preview's
   `selectionId` and digest.
5. Call `submit_approved_slice` once with a request ID, approved selection, scope, target
   stack, and backend. The fixed execution contract goes directly to ORC, which selects measured
   initial generation or regeneration from persisted candidate lineage.
6. Query `get_harness_operation_status` with the returned `tickId`; use
   `inspect_builder_session` for the bounded builder transcript and `wait_harness_operation`
   only while the originating worker remains connected.

The operator session may itself be durable when exploratory work must survive interruption.
Its transcript, skills, branches, and dynamic tool choices do not cross into the ORC builder.

Use `host` only for adapter development. Use `gondolin` for generation and measured
regeneration: it mounts
the cut read-only, confines writes to the declared workspace, denies ambient host access,
and runs verification and grading independently of the builder.

ORC keeps the workflows separate. Initial generation is independently verified and baseline
graded, then yields a non-authoritative oracle proposal for manual approval:

```text
cut → calibration → builder → instrument → affordance → verify → repair-verification
    → baseline-grade [author → calibrate → seal/reuse → public-seam grade]
    → instrument-conformance → fresh-sample loop
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
