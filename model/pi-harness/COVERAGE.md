# Complete regeneration workflow coverage

The regeneration harness model is split by ownership while sharing stable selection, task,
cycle, subject, and measurement identities.

The feature and Allium files below define target behavior. They are not an as-built
claim. Current conformance is recorded item-by-item in `.pi/architecture.md` Appendix A.

| Responsibility | Observable model | Acceptance contract |
|---|---|---|
| Session-agnostic preparation, task control, progress, and builder-session access | `operator-control-plane.allium` | items 01-015 through 01-020 and 01-035 through 01-040 |
| ORC/GEPA builder-instruction optimization | `prompt-optimization.allium` | items 01-021 through 01-023 |
| Ordered execution and evidence | `execution-pipeline.allium` | items 01-024 through 01-030 |
| Runtime signals and independent invariants | `runtime-evidence.allium` | items 01-031 through 01-034 |
| Core selection and measurement invariants | `regeneration-cycle.allium` | items 01-001 through 01-014 |

`.pi/affordances.schema.json` defines the currently implemented Pi tool inputs. The Allium
models and Gherkin acceptance contracts define the target session-agnostic preparation,
outcomes, refusals, durable measurement query, ORC stages, progress views, builder-session
access, execution rooms, and evidence provenance. There is no parallel YAML behavior model.

ORC's `:sheet/*` stream remains authoritative for task and stage execution. Grain stores the
harness-owned selection and cycle-preparation approvals, observations, and final measurement.
Allure and other reports are projections and do not determine verdicts.

Implementation traceability follows the component namespace map in
[`README.md`](README.md#implementation-namespace-map). The namespace refactor does
not change acceptance coverage: Allium and Gherkin remain organized by observable
responsibility, while Clojure namespaces are organized by implementation ownership.

## Allium diagnostic baseline

As of 2026-11-02, `allium check model/pi-harness` completes with no errors or
findings. Its accepted structural baseline is 4 warnings and 30 informational
diagnostics:

- three `externalEntity.missingSourceHint` warnings identify operator entities
  governed by another module;
- one `definition.unused` warning identifies the target intervention kind that
  is not yet connected to implemented behavior;
- informational `field.unused` diagnostics retain contract data needed by
  incomplete target behavior; and
- informational `rule.unreachableTrigger` diagnostics describe cross-module or
  external ORC/runtime triggers that have no local producer.

New diagnostics are drift and must be explained or corrected. This baseline is
not a claim that the accepted diagnostics are resolved.

## Reconciliation obligations

The complete model intentionally exposes behavior not yet satisfied by the current
implementation:

1. Selection preview must load the complete `model/<project>` contract; it currently reads
   one legacy plan feature.
2. The preferred cycle path is observable after restart by caller-supplied ORC tick identity,
   durable tick projection, and ORC frontier recovery. Legacy numeric receipts and futures
   remain process-local.
3. The activated optimized instruction must be durable; current activation lives only in
   worker memory.
4. Refused, skipped, timed-out, and errored stage outcomes must remain distinct; the current
   worker collapses them into failure.
5. Host execution cannot claim complete independent grading while the independent grader
   requires Gondolin.
6. The map/reduce grading pipeline accepts an approved denominator generically. Executable
   behavior is product-specific and is resolved from the pinned product OCI oracle; Löyly's
   accepted oracle contains all eleven booking-core graders. Fresh-session authoring and
   oracle publication are not yet automated by ORC.
7. GEPA uses the independent black-box measurement, but optimized-instruction activation
   remains process-local and its metric evidence is not yet sealed into a harness evidence
   manifest.
8. Unsafe commits and scopes fail during asynchronous execution rather than being refused
   before a task receipt is created.
9. Host builder execution does not satisfy the Gondolin isolation contract.
10. Runtime conformance is evaluated from project-owned JSONL contracts without compiled-in
    project or item identities. Live Löyly evidence currently covers `@item-01-007` and
    `@item-01-008`;
    general producer authority, collection completeness, OTel invariant projection, and
    `inconclusive` verdicts remain unimplemented.
11. Stack conformance is implemented for the finite supported registry (Rust, Java, Ruby on
    Rails, and the Node family) using language-specific Gondolin builder rooms and independent
    candidate checks. Unknown stacks fail closed. This does not make the host backend
    capability-equivalent or prove arbitrary-stack portability.
12. Verification repair is implemented as a maximum-three-attempt ORC loop. A failed attempt's
    candidate seeds the next fresh builder and its evidence root is mounted read-only. Runtime
    instrument-conformance failures occur later and are not currently repair inputs.
13. Complete cycle preparation approval is not implemented; current approval binds only the
    deterministic slice selection.
14. The RPC reads reconnect-safe overall status from ORC's durable tick projection, but it does
    not yet expose the current node/stage or reconcile live events into builder-session progress.
15. Builder sessions are in memory and disposed after each stage. There is no durable
    builder-session registry, workspace-generation binding, read-only observer, or explicit
    intervention/fork path.
16. A terminal measurement can inform a human or model, but child-cycle lineage and bounded
    continuation policy are not yet recorded as control-plane facts.

## Current conformance summary

| Status | Items |
|---|---|
| Implemented | 01-001–005, 01-007–013, 01-018–019, 01-021–022, 01-024–026, 01-028–029, 01-033 |
| Partial | 01-006, 01-014–017, 01-020, 01-023, 01-027, 01-031, 01-036–037 |
| Not implemented | 01-030, 01-032, 01-034–035, 01-038–040 |

“Implemented” means the current code satisfies the stated item within its explicit
backend and lifecycle preconditions. The grading pipeline is generic; product-specific
behavior graders are immutable payloads of the accepted product OCI oracle. Item 01-033 uses a generalized JSONL contract
evaluator, while live Löyly runtime evidence covers `@item-01-007` and `@item-01-008`.
