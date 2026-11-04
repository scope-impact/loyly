# ORC Harness Boundary

## Purpose

This document records which parts of the regeneration harness belong to ORC and which remain
external adapters. It prevents the workflow engine from being confused with the physical
execution or evaluation boundaries.

## Decision

Use ORC as the primary workflow engine. Keep Gondolin as the physical isolation boundary,
Pi/Codex as the builder intelligence, and the independent black-box grader as the behavioral
boundary. The legacy shell harness is no longer a runtime dependency and is preserved at
`archive/legacy-harness/` only for migration evidence and behavioral-parity comparison.

```text
ORC workflow
  cut → harness-calibration → builder → instrument → oracle → verify → graders
      → instrument-conformance → record-cycle → result

cut      → Gondolin cut adapter
builder  → fresh Pi/Codex session with Gondolin-routed tools
harness-calibration → pinned OCI instrument oracle resolved with ORAS
instrument/oracle/verify/graders → independent adapters or ORC-native nodes
```

## ORC responsibilities

ORC and Grain own:

- ordered workflow stages;
- task identity and idempotent submission;
- checkpoints, retries, and stage replay policy;
- execution traces and correlation IDs;
- prompt candidates and GEPA optimization;
- structured stage results and evidence aggregation;
- cycle provenance and durable history.

## Adapter responsibilities

ORC does not provide operating-system or VM capabilities. Adapters remain responsible for:

| Capability | Boundary |
|---|---|
| Physical cut isolation | Gondolin VM |
| Read-only cut and scoped writes | Gondolin VFS and `ShadowProvider` |
| Network denial | Gondolin network policy |
| Fresh builder intelligence | Pi SDK with OpenAI Codex |
| File and Git operations | Git/Gondolin adapter |
| HTTP boot and process probing | verifier adapter |
| Harness calibration | Native Clojure evaluator plus ORAS-resolved OCI instrument oracle |
| Product oracle and verifier behavior | Native Clojure model resolver plus Compose and independent grader adapters |

TypeScript may implement these adapters, but TypeScript must not own workflow state or
orchestrate stages directly. Any mutation must be initiated by an ORC workflow node.

## Current migration state

The shell runtime has been retired. ORC now owns stage ordering, Clojure owns the oracle and measurement, and narrow Node adapters own physical execution. Remaining parity gaps—generalized independent grading, durable task recovery, and explicit skip/timeout outcomes—stay visible in `.pi/architecture.md` Appendix A rather than being hidden behind a legacy adapter.

The fence is treated specially: its policy may move into ORC, but its physical execution
continues to use Gondolin.

## Capability comparison

| Plan capability | ORC | Additional boundary |
|---|---|---|
| Ordered stages | Native behavior-tree workflow | None |
| Durable execution | Grain event history and ORC traces | Persistent event-store configuration |
| Recovery | Workflow execution/replay primitives | Explicit safe/unsafe stage policy |
| Prompt optimization | GEPA integration | Real independent evaluator |
| Builder orchestration | `code`/LLM nodes | Pi/Codex and Gondolin adapter |
| Cut orchestration | Workflow node | Gondolin and Git implementation |
| Scoped writes | Not native | Gondolin VFS |
| Network isolation | Not native | Gondolin policy |
| Oracle/verify orchestration | Code nodes | Ported logic or subprocess adapter |
| Independent grading | Separate nodes/workflows | Separate process or VM and read-only subject |
| Boot budget | Result/timeout recording | Process and HTTP adapter |
| Git object-graph proof | Evidence persistence | Git/Gondolin implementation |

## Non-negotiable invariants

- The builder never receives the unfenced source repository.
- `/cut` is read-only.
- Builder writes are limited to the declared scope.
- Builder credentials and host environment are not mounted into Gondolin.
- The grader is independent of the builder and sees a read-only candidate.
- Candidate construction cannot begin until the pinned OCI instrument oracle resolves, its
  complete harness-source digest matches, and its carried and uncarried goldens pass.
- A failed or skipped oracle is not reported as a pass.
- Prompt optimization uses independent regeneration outcomes, not a circular model score.
- A completed ORC task is distinct from a passing binary grade, and remaining parity gaps stay explicit.
