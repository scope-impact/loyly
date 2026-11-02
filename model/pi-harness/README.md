# Pi harness regeneration contract

This directory is the canonical **target contract** for the regeneration workflow
exposed through `.pi/harness/extension.ts` and executed by ORC under
`.pi/harness/orc-worker/`. It is separate from `model/loyly`, which describes the
product being regenerated.

The target contract must not be read as a claim that every behavior is already
implemented. [`.pi/architecture.md` Appendix A](../../.pi/architecture.md#appendix-a-implementation-conformance)
is the implementation-status record, and `COVERAGE.md` summarizes the gaps.

- `spec.feature` gives harness behavior stable acceptance-item identities.
- `regeneration-cycle.allium` defines the core invariants; the connected control, prompt, execution, and runtime-evidence models are summarized in `COVERAGE.md`.
- `runtime-evidence.allium` separates candidate telemetry, harness observations, independent invariant verdicts, and report projections.
- `execution-pipeline.allium` gates candidate construction on a pinned OCI full-harness
  calibration, composes that instrument digest with the product-contract digest, and defines
  the bounded verification-repair boundary before independent grading.
- `regeneration_event_model.clj` describes only the harness-owned Grain event topology.
- `.pi/affordances.schema.json` defines the public Pi tool inputs; observable outcomes and
  refusals remain in the Allium and Gherkin contracts rather than a parallel YAML model.
- [`.pi/architecture.md` Appendix A](../../.pi/architecture.md#appendix-a-implementation-conformance) records implementation conformance.
- `GLOSSARY.md` fixes the regeneration vocabulary.

ORC's own `:sheet/*` execution events remain governed by ORC. Löyly product events
remain governed by `model/loyly`. This model does not duplicate either stream.

## Behavioral coverage

`COVERAGE.md` maps all 34 acceptance items across the operator control plane, prompt optimization, and the complete execution and evidence pipeline. ORC task and prompt progress remain ORC-owned; Grain stores only durable harness approval and measurement facts.

## Known implementation limits and gaps

The contract exposes gaps found while deriving it from the current implementation:

1. A host-backed cycle can be submitted, but independent grading currently requires Gondolin,
   so only Gondolin can produce a passing complete measurement.
2. Selection preview currently reads `docs/plan/<slice>/spec.feature` rather than the complete
   project model contract used by the oracle.
3. Workflow task receipts are held in worker memory and cannot be resumed after restart.
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

These are reconciliation obligations, not alternative sources of truth.
