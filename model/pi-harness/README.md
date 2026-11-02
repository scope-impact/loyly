# Session-hosted regeneration harness contract

This directory is the canonical **target contract** for the regeneration workflow,
currently exposed through `.pi/harness/extension.ts` and executed by ORC under
`.pi/harness/orc-worker/`. It is separate from `model/loyly`, which describes the
product being regenerated.

The target contract must not be read as a claim that every behavior is already
implemented. Every scenario is classified as either `@e2e` or `@skip`. Scenarios tagged
`@e2e` are executable conformance checks; scenarios tagged `@skip` are registered with
Vitest's native `test.skip` and do not claim executable conformance. A tag gate rejects
unclassified or doubly classified scenarios.

- `regeneration-cycle.feature` defines the core regeneration-cycle acceptance behavior.
- `operator-control-plane.feature`, `prompt-optimization.feature`,
  `execution-pipeline.feature`, and `runtime-evidence.feature` own acceptance behavior for
  their corresponding model components.
- Every acceptance scenario retains a stable `@item-*` identity so Allure results can be
  reconciled with the model without a parallel coverage catalogue.
- `regeneration-cycle.allium` defines the core invariants.
- `operator-control-plane.allium` defines session-agnostic preparation approval, durable ORC
  observation, builder-session inspection, and bounded outer-cycle continuation.
- `runtime-evidence.allium` separates candidate telemetry, harness observations, independent invariant verdicts, and report projections.
- `execution-pipeline.allium` gates candidate construction on a pinned OCI full-harness
  calibration, composes that instrument digest with the product-contract digest, and defines
  the bounded verification-repair boundary before independent grading.
- `regeneration_event_model.clj` describes only the harness-owned Grain event topology.
- `.pi/affordances.schema.json` defines the public Pi tool inputs; observable outcomes and
  refusals remain in the Allium and Gherkin contracts rather than a parallel YAML model.
- `GLOSSARY.md` fixes the regeneration vocabulary.
- `docs/adr/0001-session-and-cycle-ownership.md` records why ORC owns the inner cycle while
  operator sessions own approval and outer continuation.

ORC's own `:sheet/*` execution events remain governed by ORC. Löyly product events
remain governed by `model/loyly`. This model does not duplicate either stream.

## Implementation namespace map

The model remains the behavioral source of truth; the following map is only a
maintainer index into the current Clojure implementation.

| Contract responsibility | Implementation boundary |
|---|---|
| Public cycle control and status | [`loyly.pi-harness.interface`](../../.pi/harness/orc-worker/src/loyly/pi_harness/interface.clj) |
| ORC executor coordination | [`loyly.pi-harness.core.worker`](../../.pi/harness/orc-worker/src/loyly/pi_harness/core/worker.clj) |
| Cut and regeneration behavior trees | [`loyly.pi-harness.workflows`](../../.pi/harness/orc-worker/src/loyly/pi_harness/workflows/regeneration.clj) |
| Builder instruction, application contract, stack checks, and subject sealing | [`loyly.pi-harness.core.build`](../../.pi/harness/orc-worker/src/loyly/pi_harness/core/build/application_contract.clj) |
| Compose verification and independent grading | [`loyly.pi-harness.core.grading`](../../.pi/harness/orc-worker/src/loyly/pi_harness/core/grading/compose.clj) |
| Evidence manifests and Allure projections | [`loyly.pi-harness.core.evidence`](../../.pi/harness/orc-worker/src/loyly/pi_harness/core/evidence/evidence_manifest.clj) |
| Product and instrument oracle resolution and calibration | [`loyly.pi-harness.core.oracle`](../../.pi/harness/orc-worker/src/loyly/pi_harness/core/oracle/oracle.clj) |
| Process adapters and durable runtime composition | [`loyly.pi-harness.core.runtime`](../../.pi/harness/orc-worker/src/loyly/pi_harness/core/runtime/adapters.clj) |
| Deterministic slice selection | [`loyly.pi-harness.core.selection`](../../.pi/harness/orc-worker/src/loyly/pi_harness/core/selection/slice_selection.clj) |
| Harness-owned Grain facts | [`loyly.pi-harness.grain.interface`](../../.pi/harness/orc-worker/src/loyly/pi_harness/grain/interface.clj) |

`core.worker` is intentionally the only implementation coordinator. Its executor
Vars and the root prompt compatibility Var retain the fully qualified names stored
inside serialized ORC workflow nodes. Supporting components must not depend back
on `core.worker` or the public interface.

## Executable conformance

The Vitest adapter declares one readable TypeScript test per component-owned scenario. `@e2e`
scenarios have concrete execution and emit each modeled Given/When/Then step through
`allure.step`; `@skip` scenarios use Vitest's native `test.skip` until their public-seam
translation is implemented. A static Gherkin-to-TypeScript lint checks scenario counts, native
skip classification, component labels, and literal modeled steps before Vitest starts. Missing
translations and failed `@e2e` scenarios prevent a successful conformance run. Here `@skip`
means “not yet translated into E2E,” not necessarily “underlying implementation absent.”
Executable-test evidence under `.pi/harness/allure-results/e2e/` is generated and not checked in;
the complete skipped inventory remains visible in Vitest's run summary.

ORC stage progress remains ORC-owned; Grain stores durable preparation approval, observations,
and measurements without duplicating ORC execution events.

## Known implementation limits and gaps

The contract exposes gaps found while deriving it from the current implementation:

1. A host-backed cycle can be submitted, but independent grading currently requires Gondolin,
   so only Gondolin can produce a passing complete measurement.
2. Selection preview currently reads `docs/plan/<slice>/spec.feature` rather than the complete
   project model contract used by the oracle.
3. Legacy `submit`/`wait` task receipts remain in worker memory. The preferred
   `start-cycle` path uses caller-supplied ORC tick and correlation identities, reads status
   from ORC's durable projection, and asks ORC to resume abandoned leaf frontiers on startup.
4. The activated optimized instruction is held in worker memory rather than durable state.
5. Refused, skipped, timed-out, and errored stages currently collapse into failure.
6. Independent black-box behavior remains project-specific. Löyly has calibrated programs
   for all eleven booking-core items inside its accepted product OCI oracle; the project
   tree no longer supplies runtime grader files. Fresh-session grader authoring is not yet
   an ORC workflow stage.
7. GEPA candidate scoring is gated by full harness calibration and uses the product oracle,
   Compose verifier, and independent per-item black-box measurement. The selected instruction
   and its metric evidence are not yet durable harness records.
8. Full source-commit and candidate-scope validation occurs inside asynchronous cut
   or builder execution, after a task receipt has been created, rather than at submission.
9. Gondolin provides the declared builder isolation. The host backend executes through
   a normal host shell with the host environment and is not capability-equivalent.
10. Re-previewing an already approved deterministic selection returns its current
    `approved` status; it does not regress to `awaiting-approval`.
11. Standalone runtime conformance is selected from each pinned project's JSONL contract;
    the evaluator contains no project, item, event, projection, or field identities. Live
    Löyly evidence covers `@item-01-007` and `@item-01-008`. General producer authority, collection
    completeness, OBI capture, semantic OTLP conventions, `inconclusive` outcomes, and
    invariant-result projection remain unimplemented.
12. Supported target stacks are now independently bound to language-specific Gondolin builder
    rooms and checked against the candidate. Rust, Java, Ruby on Rails, and the Node family fail
    closed on a stack mismatch; this is a finite supported-stack registry, not universal
    language independence. The host backend remains non-ratifying and is not isolation-equivalent.
13. Verification repair is bounded to three attempts. Each repair uses a fresh builder seeded
    with the previous candidate and a read-only verification evidence root. Runtime instrument
    conformance runs after behavioral grading and does not yet participate in this repair loop.
14. Approval currently binds only the slice selection. It does not yet bind a complete cycle
    preparation containing scope, target stack, execution profile, pinned inputs, diagram, and
    outer-cycle budget.
15. The control RPC exposes reconnect-safe overall execution status from ORC's durable tick
    projection, but not yet the current node/stage or builder-session reference.
16. Builder sessions are fresh and isolated but currently in memory, disposed after the stage,
    and unavailable for durable inspection, attachment, or forking.
17. Builder transcripts are not yet bound to durable workspace generations. Restoring an agent
    transcript without the VM-visible filesystem it changed would be invalid.
18. Parent-cycle lineage and bounded continuation heuristics are not yet recorded when an
    operator session decides to begin another complete cycle.

These are reconciliation obligations, not alternative sources of truth.
