# Complete workflow coverage

The Pi harness model is split by ownership while sharing stable selection, task,
cycle, subject, and measurement identities.

The feature and Allium files below define target behavior. They are not an as-built
claim. Current conformance is recorded item-by-item in `.pi/architecture.md` Appendix A.

| Responsibility | Observable model | Acceptance contract |
|---|---|---|
| Pi operator and task control | `operator-control-plane.allium` | items 01-015 through 01-020 |
| ORC/GEPA builder-instruction optimization | `prompt-optimization.allium` | items 01-021 through 01-023 |
| Ordered execution and evidence | `execution-pipeline.allium` | items 01-024 through 01-030 |
| Runtime signals and independent invariants | `runtime-evidence.allium` | items 01-031 through 01-034 |
| Core selection and measurement invariants | `regeneration-cycle.allium` | items 01-001 through 01-014 |

`.pi/affordances.schema.json` defines the six public Pi tool inputs. The Allium models and
Gherkin acceptance contracts define outcomes, refusals, the internal durable measurement
query, ORC stages, task guarantees, execution rooms, and evidence provenance. There is no
parallel YAML behavior model.

ORC's `:sheet/*` stream remains authoritative for task and stage execution. Grain
stores the harness-owned selection approval, observations, and final measurement.
Allure and other reports are projections and do not determine verdicts.

## Reconciliation obligations

The complete model intentionally exposes behavior not yet satisfied by the current
implementation:

1. Selection preview must load the complete `model/<project>` contract; it currently reads
   one legacy plan feature.
2. Workflow tasks must remain observable after worker restart; current receipts and futures
   live only in worker memory.
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

## Current conformance summary

| Status | Items |
|---|---|
| Implemented | 01-001–005, 01-007–013, 01-018–019, 01-021–022, 01-024–026, 01-028–029, 01-033 |
| Partial | 01-006, 01-014–017, 01-023, 01-027, 01-031 |
| Not implemented | 01-020, 01-030, 01-032, 01-034 |

“Implemented” means the current code satisfies the stated item within its explicit
backend and lifecycle preconditions. The grading pipeline is generic; product-specific
behavior graders are immutable payloads of the accepted product OCI oracle. Item 01-033 uses a generalized JSONL contract
evaluator, while live Löyly runtime evidence covers `@item-01-007` and `@item-01-008`.
