# 01-regeneration-cycle

Carried through JSON-RPC subprocess tests: @item-01-001, @item-01-005,
@item-01-006, @item-01-007, @item-01-008, @item-01-010.

Not carried (the specification's `@skip` stamps are not treated as waivers):

- @item-01-002: No Gondolin capability/mount/network isolation implementation. Host sessions are explicitly developmental, not isolated.
- @item-01-003: The legacy fence removes configured paths and proves history, but its retained harness assets do not meet the stricter judge-free clean-room requirement. Builder views additionally omit harness/archive; this is not a proven isolated cut.
- @item-01-004: Item uniqueness is checked; component/container mappings and reversible hierarchy composition are not implemented.
- @item-01-009: A fresh Pi process and transcript are implemented, with tool/output checks, but confinement and approved per-item instruction binding lack a complete public-seam test.
- @item-01-011: The legacy executable gate runs the suite and timed identity probe; the compose-service contract is not implemented.
- @item-01-012: No complete approved-grader scoring and strict measurement reduction. Scoring fails explicitly rather than returning invented verdicts.
- @item-01-013: Validated observation and measurement commands with identity/content collision checks are not implemented.
- @item-01-014: Grain SQLite persists selections, approvals, cuts, receipts, and ORC execution. Complete measurement recording/reconstruction is not implemented.
- @item-01-015: Host score execution explicitly refuses independent comparison, but no end-to-end test has reached it through a successful real builder and gate.

Cycle execution uses actual ORC code nodes. Preparation executes the pinned source's
legacy fence (including its self-check). Regeneration invokes a fresh real Pi session;
the independent gate restores its tools from pinned source. Scoring and scoreboard
currently refuse without an approved instrument/measurement; they do not claim completion.

# 02-execution-pipeline

Carried through JSON-RPC subprocess tests: @item-02-002, @item-02-003,
@item-02-010. The self-check now runs before allocating a clean-room directory;
its successful harness-content digest is recorded in Grain. The test establishes a
passing cut, reuses it, changes harness content, and proves the named failing check
prevents creation of another room. The legacy fence also retains its own check.

Not carried (`@skip` is not a waiver):

- @item-02-001: The executable ORC sequence declares the correct order, but there is no successful full-cycle public-seam test or complete explicit non-success reduction.
- @item-02-004: Independent legacy gate execution exists, but digest-bound gate records and ORC-owned bounded regeneration/repair routing are not implemented.
- @item-02-005: No sealed approved instrument execution or complete per-item verdict filing; score refuses instead of manufacturing a measurement.
- @item-02-006: No affordance evaluator/non-applicable result implementation.
- @item-02-007: No observation identity/content collision command implementation.
- @item-02-008: No durable observation report projection implementation.
- @item-02-009: ORC exposes execution failures, but not all four stage failure modes preserve an explicitly filed approved denominator and reason.
- @item-02-011: The legacy gate uses the thirty-second probe, but unused-budget margin is not a structured digest-bound gate result, and no passing end-to-end gate test covers it.
- @item-02-012: Regenerate still starts one actual Pi process for the selection, not one fresh session per selected slice in plan order.
- @item-02-013: The host builder gets a copied clean-room view with sensitive directories removed, but there is no per-slice handoff or proven read isolation; the requirement is not claimed.

# 03-instrument

Carried through JSON-RPC subprocess tests: @item-03-001. Checklist derivation now
parses scenarios rather than counting tag occurrences. Preview returns a derived
`checklist` alongside the existing string `items` field, with stable item IDs and
per-scenario digests including Rule, Background, steps, arguments, and Examples.
Missing/ambiguous stamps, duplicate IDs, and empty scenario sets refuse selection.
The full-plan regenerate node derives and records its selection before invoking Pi.
Tests first failed against the preceding implementation and now pass; additional
public-seam tests cover changed Examples/Rule contracts and unknown-grader refusal.

Not carried (`@skip` is not a waiver):

- @item-03-002: No candidate-blind independent author room, public calibration executor,
  or ORC-owned three-attempt calibration/re-authoring workflow. No grader is sealed.
- @item-03-003: Without sealed graders, digest-scoped reuse, recalibration, and
  item-local replacement cannot be exercised through the public seam.
- @item-03-004: Unknown digest approval is explicitly refused, but replacement
  review/reasons, add-only validation, and exact replacement approval are absent.
- @item-03-005: Scoring still fails closed before running a grader. There is no
  successful gate-to-grader test proving zero returned verdicts fail without filing.
- @item-03-006: No approved-grader execution or strict full-denominator measurement
  reducer; the three-item/unreached case cannot yet be driven through the seam.
- @item-03-007: No sealed behavior/affordance/runtime-evidence evaluators. Candidate
  telemetry is not consumed as verdicts, but evaluator execution is not implemented.
- @item-03-008: No filed cycle measurements or shared-item scoreboard projection;
  comparison and holdout reporting are not implemented.
- @item-03-009: No first-cycle scoreboard execution. Returning an invented report
  without a filed measurement would conceal the missing instrument.

No tests for earlier slices were removed or weakened. The product does not claim
that unavailable instrument stages ran successfully.

# 04-operator-control-plane

Carried through JSON-RPC subprocess tests: @item-04-006, @item-04-007,
@item-04-008, @item-04-009. New tests first failed for duplicate selection
requests, overlapping scopes, and absent ORC trace summaries; those defects are
fixed. Repeated waits cover actual completed cut preparation and actual failed
whole-plan and approved-slice ORC executions, not manufactured task statuses.
The Gondolin execution fails explicitly in the regenerate node because that
backend is unavailable. No test claims it built a candidate.

Not carried (`@skip` is not a waiver):

- @item-04-001: The existing service does not register/reconcile the complete
  authoritative Grain Event Model, or resolve its Allium trace links at startup.
- @item-04-002: Earlier slices use registered event schemas but bare Grain store
  appends and reads, not the required complete command/query/read-model topology.
  This slice does not claim that path complies with the model.
- @item-04-003: No harness progress projection from authoritative ORC events.
- @item-04-004: Preview and exact-digest approval exist, but no real exploratory
  LLM slice-proposal session and cut-bound proposal resolver is exposed.
- @item-04-005: Invalid commits/backends are refused synchronously, but filesystem
  cut collisions are detected inside the fence task, not before task issuance.
  No complete collision-preflight public-seam test was carried.
- @item-04-010: Wait still uses the current worker's task futures. Grain durably
  stores receipts and ORC execution facts, but a replacement worker does not yet
  reconstruct the terminal outcome from them.
- @item-04-011: No complete cycle-preparation/diagram exact-digest approval seam.
- @item-04-012: Unknown grader digests fail closed, but no calibrated sealed
  grader exists to approve or use for a successful scored cycle.
- @item-04-013: ORC executes actual cycle nodes independently of the operator
  transcript, but immutable complete preparation and bounded retry contracts
  are not implemented or exercised by an authorized-session test.
- @item-04-014: No reconnectable execution-status/progress projection or extension.
- @item-04-015: The regenerate node runs a separately identified real Pi process
  and saves its transcript, but builder-session observation is not implemented.
  Host execution does not prove the required operator/grader read isolation.
- @item-04-016: No safe-boundary intervention/fork or assisted-candidate provenance.
- @item-04-017: No atomic transcript/workspace generation restore protocol.

No earlier tests were weakened or deleted. Existing stage/instrument limitations
remain explicit; trace summaries identify real ORC executions and do not assert
successful stages that never ran.

# 05-runtime-evidence

Carried: @item-05-005. The JSON-RPC workflow-contract exposes `artifacts`
from the observation schema in the executable ORC score output, not a canned
field list. The schema preserves artifact digest, media type, and location and
matches the observation shape in the supplied Grain Event Model. A regression
test naming the item failed on stale contract metadata before the change and
passes now; it checks both the subprocess seam and public request handler with
an altered executable schema. This schema does not claim scoring has run.

Not carried (`@skip` and `@gondolin` are not waivers):

- @item-05-001: No sealed grader/browser execution or genuine Playwright trace
  collection; no measurement can bind such an archive yet.
- @item-05-002: No runtime-signal ingestion and authoritative measurement reducer.
  The existing score refusal does not establish retention of candidate signals.
- @item-05-003: No model-driven signal collector or persisted independent
  invariant evaluation, including incomplete/sampled/dropped collection status.
- @item-05-004: No independent invariant executor validating operation, event,
  aggregate, and subject correlations across accepted events and projections.
- @item-05-006: No sealed independently executed behavior/runtime evaluators or
  conjunctive per-item measurement reduction. A schema is not their execution.
- @item-05-007: No pinned-project-model generic invariant interpreter; the
  existing workflow fails closed rather than inventing observations.
- @item-05-008: No persisted invariant evaluations or OTel/Allure report emitters.
  Cannot test durable-before-projection provenance without those producers.
- @item-05-009: No isolated Gondolin observer session or operator profile capture
  and deterministic validation path.
- @item-05-010: No Gondolin validator image, read-only policy/candidate mounts,
  isolated writable evidence volume, OBI/eBPF setup, or native OTLP collection.

Earlier slice limitations remain unchanged. No tests were deleted or weakened.

# 06-prompt-optimization

Carried: @item-06-001. A JSON-RPC subprocess test covers missing, null,
empty, and non-list training and validation sets for both purposes. It first
failed against unknown-method responses; the handler now refuses with the
invalid field named before initializing runtime state or evaluating anything.

Not carried (`@skip` is not a waiver):

- @item-06-002: No independent optimization evaluator, complete candidate-blind
  grade, or fresh isolated optimization-builder execution. Valid requests fail
  explicitly; no score is fabricated.
- @item-06-003: Without measured candidates, there is no winner-selection and
  Grain-backed purpose-scoped activation/recovery implementation.
- @item-06-004: No GEPA strategy candidate/rendered-prompt digest pipeline.
- @item-06-005: No independent fresh-cut and fixed-failed-gate evaluation datasets
  or purpose-specific metric execution.
- @item-06-006: No enabled Bedrock reflection provider/model registry or real
  sanitized model-call/usage provenance. Input validation is not reflection.
- @item-06-007: The inherited builder prompt is not a complete ORC-owned
  system/task prompt contract; grader-author and repair prompt seams are absent.
- @item-06-008: Host execution does not enforce filesystem/network/credential
  isolation, and independent gate enforcement remains incomplete as recorded
  by earlier slices. Prompt validation cannot supply those capabilities.
- @item-06-009: No Bedrock proposal session and fenced optimization execution
  workflow with independent grading and enforced role separation.

Earlier tests and limitations are preserved. The optimization endpoint refuses
valid requests as unavailable rather than returning a fictional winning strategy.

# 07-harness-bootstrap

Not carried (`@skip` is not a waiver):

- @item-07-001: No complete runtime bootstrap or snapshot sealing implementation.
  The clean room supplies the legacy `harness/Makefile`, but not its
  `.pi/harness` npm package, Gondolin configurations, build contexts, platform
  verification scripts, or carried/uncarried snapshot calibration machinery.
  The candidate does not implement replacements for those assets. Docker command
  invocation alone would not prove built and verified runtimes or a calibrated,
  revision-bound sealed manifest.
- @item-07-002: No successful bootstrap execution exists against which to prove
  the required harness-only effects. A refused or missing bootstrap is not a
  successful bootstrap and is not claimed as a passing negative-effects test.
- @item-07-003: No candidate bootstrap Makefile target/runtime build executor
  exists to exercise a genuine configured Docker/Gondolin build failure. A
  missing-target error would not establish the scenario's failure propagation.

The declared JSON-RPC seam has no bootstrap method; these scenarios explicitly
name `make -C harness bootstrap`. No additional RPC method, canned manifest,
pretend runtime asset, or mocked build-success test was added. No passing test
is claimed for these three items. Implementing and testing the actual bootstrap
entry point, its runtime assets, calibration, and sealing remains necessary.
Earlier slice tests and implementation are unchanged.

# 08-project-vm-config

Carried through JSON-RPC subprocess tests: @item-08-001, @item-08-002.
Project identity is selected by `PROJECT_ID` in the pinned cut's shared
`harness/config.sh`; submission resolves `harness/<project-id>/gondolin.json`
and exactly `Dockerfile.<targetStack>` for typescript, rust, java, or rails.
No per-project profile is read. Resolution validates actual regular files and
canonical containment before issuing a cycle task. The chosen paths travel in
the ORC request and the Grain-backed, idempotent receipt as `runtimeFiles`.
Unconfigured older host-development projects retain their existing behavior.

Both tests failed against the preceding implementation (22 assertions) and
pass after adding resolution. The missing Rust test supplies another project's
Rust Dockerfile and proves no cycle task is issued; unsupported and traversal
stacks also refuse. Positive tests cover all four stacks in a prepared cut.
They await actual failed ORC execution: this slice does not claim Gondolin was
started or that an image was built. Those earlier backend limitations remain.
No earlier test was weakened or deleted.

# 09-rpc-seam

Carried through JSON-RPC subprocess tests: @item-09-001, @item-09-002,
@item-09-003, @item-09-004. Pipelined numeric/string IDs and a following
sentinel request check one-line response framing; unknown-method refusal keeps
the process alive. Repeated cycle submission returns its original receipt,
issues no extra task, and retains the same terminal outcome. A real ORC cycle
runs the fixture's fence and fails in regenerate on the unavailable Gondolin
backend; wait now explicitly names that failed stage alongside ORC's reason.
The stage assertion failed before the change. Framing, method refusal, and
receipt idempotency already passed without production changes.

No scenario of this slice is left uncarried. This does not claim a successful
regeneration or fix earlier slices' unavailable backends, scoring, or worker
restart recovery. No earlier test was weakened or removed.
