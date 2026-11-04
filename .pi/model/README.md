# Session-hosted regeneration harness contract

This directory is the canonical **target contract** for the regeneration cycle. Its Gherkin
spec uses the legacy layout, one slice per `plan/NN-slug/spec.feature` stamped with
`@item-NN-SSS` by `harness/stamp_item_ids.clj`; the Allium files sit beside it, exposed through `.pi/harness/extension.ts` and executed by ORC under
`.pi/harness/orc-worker/`. It is separate from `model/loyly`, which describes the
product being regenerated.

ORC is itself Grain-native. The Pi harness is therefore the `:loyly.pi-harness` Grain service
area inside ORC's existing tenant-scoped runtime, not a second event system or an adapter around
ORC. Every harness state change passes through a Grain command and registered event; every public
read passes through a Grain query over a registered read model. Grain processors react to facts
when asynchronous coordination is required. ORC `:sheet/*` events remain execution authority and
are consumed directly by harness projections rather than copied into harness progress events.

The C4 architecture and supporting runtime views derived from this contract are maintained in
[`.pi/architecture.md`](../../.pi/architecture.md). That document identifies target versus
current behavior explicitly; this model remains the observable source of truth.

The target contract must not be read as a claim that every behavior is already
implemented. Every scenario is classified as either `@e2e` or `@skip`. Scenarios tagged
`@e2e` are executable conformance checks; scenarios tagged `@skip` are registered with
Vitest's native `test.skip` and do not claim executable conformance. A tag gate rejects
unclassified or doubly classified scenarios.

- `plan/07-harness-bootstrap/spec.feature` and `harness-bootstrap.allium` specify the local
  `harness/Makefile` bootstrap; it builds and verifies harness and supported-stack runtime assets without
  requiring a grader and does not regenerate the product.
- `plan/08-project-vm-config/spec.feature` and `project-vm-config.allium` require project-specific VM
  config and supported-stack Dockerfiles under root `harness/<project-id>/`, resolved by
  convention without a per-project profile JSON; shared harness config remains under
  `.pi/harness/config/`.
- `plan/02-execution-pipeline/spec.feature` and `execution-pipeline.allium` define the one regeneration
  cycle in the legacy shape: fence, regenerate, gate, score, scoreboard. The fence and the gate
  are the harness and feed the build loop; the score and the scoreboard are the instrument and
  never do.
- `plan/03-instrument/spec.feature` and `instrument.allium` define the instrument: a checklist derived from
  the spec, a grader written once, calibrated carried/uncarried, sealed, and approved by exact
  digest, an honest score, and a scoreboard that compares shared items in both directions.
- `plan/01-regeneration-cycle/spec.feature` and `regeneration-cycle.allium` define cut preparation,
  composition, selection, preparation approval, and idempotent submission.
- `plan/04-operator-control-plane/spec.feature`, `plan/06-prompt-optimization/spec.feature`,
  `plan/02-execution-pipeline/spec.feature`, and `plan/05-runtime-evidence/spec.feature` own acceptance behavior for
  their corresponding model components.
- `prompt-optimization.allium` treats the complete model-directed strategy as the GEPA mutation
  unit. Regeneration and gate repair have separate active instructions and separate
  measurements. ORC renders deterministic runtime facts beside the selected strategy through one
  prompt seam; ORC also owns the system and task instructions used by the grader author. Adapters transport exact rendered digests without adding instructions. Execution rooms
  and verifiers enforce capability and candidate contracts independently of model compliance.
  GEPA reflection is bound to an explicit Bedrock provider and model, while Pi/Codex builder
  execution and independent grading remain separate roles.
- Tagged acceptance scenarios retain stable `@item-*` identities so Allure results can be
  reconciled with the model without a parallel coverage catalogue. Two instrument-conformance
  scenarios still lack identities and remain an explicit traceability gap.
- Cut composition maps every stable Gherkin behavior item to a slice, component, and container;
  reducing those mappings must reconstruct the complete system view, and decomposition must
  recover the same mappings without omissions or duplicates.
- `operator-control-plane.allium` defines session-agnostic preparation approval, durable ORC
  observation, builder-session inspection, and bounded outer-cycle continuation. An LLM may
  propose an existing slice and item subset from operator intent, but deterministic resolution
  against the cut and explicit operator approval remain authoritative. Exploratory skills,
  dynamic tools, branching, and steering stay in the operator session; only an immutable
  approved fixed execution contract crosses into ORC.
- `runtime-evidence.allium` separates candidate telemetry, harness observations, independent invariant verdicts, and report projections.
- `regeneration_event_model.clj` mandates the harness-owned Grain service area. Its block names
  use the `:loyly.pi-harness/*` namespace, its public commands, events, read models, queries, and
  processors must reconcile with live registries, and every event has an explicit `defevent`
  history contract. Startup and CI fail strict validation on missing, extra, or mismatched blocks.
- `model/<project>/affordances.yaml` defines the minimum actor-facing interaction surface that
  exposes Allium/Gherkin behavior to people and tools for the slices selected by its `slice` or
  `slices` field. A non-selected slice records affordance conformance as non-applicable and does
  not invent a UI. `.pi/affordances.schema.json` is an
  extensible vocabulary, not a closed shape: independent conformance ignores unknown fields and
  validates reachable screens, operable accessible actions, declared calls, and visible feedback.
- `GLOSSARY.md` fixes the regeneration-cycle vocabulary.
- `docs/adr/0001-session-and-cycle-ownership.md` records why ORC owns the inner cycle while
  operator sessions own approval and outer continuation.
- `docs/adr/0002-pi-harness-is-an-orc-grain-service-area.md` records why the harness shares
  ORC's Grain runtime and projects ORC execution facts instead of duplicating them.

ORC's own `:sheet/*` execution events remain governed by ORC but may feed Pi-harness read models
and processors. Löyly product events remain governed by `model/loyly`. The three service areas
share Grain infrastructure without duplicating one another's facts.

## Implementation namespace map

The model remains the behavioral source of truth; the following map is only a
maintainer index into the current Clojure implementation.

| Contract responsibility | Implementation boundary |
|---|---|
| Public regeneration control and status | [`loyly.pi-harness.interface`](../../.pi/harness/orc-worker/src/loyly/pi_harness/interface.clj) |
| ORC executor coordination | [`loyly.pi-harness.core.worker`](../../.pi/harness/orc-worker/src/loyly/pi_harness/core/worker.clj) |
| Source-cut and regeneration-cycle behavior trees | [`loyly.pi-harness.workflows`](../../.pi/harness/orc-worker/src/loyly/pi_harness/workflows/slice_cycle.clj) |
| Builder strategy and rendering, stack checks, and subject sealing | [`loyly.pi-harness.core.build`](../../.pi/harness/orc-worker/src/loyly/pi_harness/core/build/prompt.clj) |
| Compose verification and independent grading | [`loyly.pi-harness.core.grading`](../../.pi/harness/orc-worker/src/loyly/pi_harness/core/grading/compose.clj) |
| Evidence manifests and Allure projections | [`loyly.pi-harness.core.evidence`](../../.pi/harness/orc-worker/src/loyly/pi_harness/core/evidence/evidence_manifest.clj) |
| Harness self-check and grader calibration | [`loyly.pi-harness.core.oracle`](../../.pi/harness/orc-worker/src/loyly/pi_harness/core/oracle/oracle.clj) |
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

ORC tick state remains workflow authority. The Pi-harness Grain service area projects durable
stage/attempt views directly from ORC events for reconnecting clients and owns preparation
approval, observations, and measurements. The projection cannot advance ORC and emits no mirror
progress event.

## Known implementation limits and gaps

The contract exposes gaps found while deriving it from the current implementation. The current
runtime still runs the twelve-stage slice cycle with routes, a per-cycle grader author, and
proposal-based promotion; the contract now requires the five-stage legacy cycle with an
approved sealed grader and a scoreboard.

1. A host-backed cycle can be submitted, but independent grading currently requires Gondolin,
   so only Gondolin can produce a passing complete measurement.
2. Selection preview currently reads `docs/plan/<slice>/spec.feature`, accepts an explicitly
   named slice, and hashes the project model inventory. It does not yet propose a slice from
   operator intent or resolve stable item identities and full scenario digests directly from the
   canonical product Gherkin contract.
3. The activated optimized instruction is held in worker memory rather than durable state. The
   target contract requires one recoverable active instruction per purpose: regeneration and
   gate repair.
4. Refused, skipped, timed-out, and errored stages currently collapse into failure.
5. The production prompt renderer now measures the complete purpose-specific strategy and binds
   deterministic runtime context, including the selected stack and base image, to the exact prompt
   digest. Candidate scoring remains responsible for the gate and a complete score by an approved
   sealed grader. The selected
   instruction and its metric evidence are not yet durable harness records.
6. Full source-commit and candidate-scope validation occurs inside asynchronous cut
   or builder execution, after a task receipt has been created, rather than at submission.
7. Gondolin provides the declared builder isolation. The host backend executes through
   a normal host shell with the host environment and is not capability-equivalent.
8. Re-previewing an already approved deterministic selection returns its current
    `approved` status; it does not regress to `awaiting-approval`.
9. Standalone runtime conformance is selected from each pinned project's JSONL contract;
    the evaluator contains no project, item, event, projection, or field identities. Live
    Löyly evidence covers `@item-01-007` and `@item-01-008`. General producer authority, collection
    completeness, OBI capture, semantic runtime-signal OTLP conventions, and `inconclusive`
    outcomes remain unimplemented. Persisted invariant results are projected as OTLP JSON
    spans alongside Allure with the same evaluation identity and evidence-manifest digest.
10. Supported target stacks are now independently bound to language-specific Gondolin builder
    rooms and checked against the candidate. Rust, Java, Ruby on Rails, and the Node family fail
    closed on a stack mismatch; this is a finite supported-stack registry, not universal
    language independence. The host backend remains non-ratifying and is not isolation-equivalent.
11. Gate repair is bounded to three attempts. Each repair uses a fresh builder seeded with the
    previous candidate and a read-only gate evidence root; score evidence never enters it.
12. Approval currently binds only the slice selection. It does not yet bind a complete cycle
    preparation containing scope, target stack, execution profile, pinned inputs, diagram, and
    outer-cycle budget.
13. The control RPC reconciles ORC's authoritative durable tick status with a harness-owned
    durable stage/attempt projection and opaque builder-session reference. The Pi extension can
    query this state after reconnect; push notification subscription and an automatic live UI
    remain unimplemented.
14. Builder sessions are fresh, isolated, and persisted as append-only Pi transcripts. The
    read-only RPC exposes committed entries, tool-call count, generated top-level workspace
    entries, status, and attempt progress. Steering, attachment, and forking remain unimplemented.
15. Builder transcripts are not yet bound to durable workspace generations. Restoring an agent
    transcript without the VM-visible filesystem it changed would be invalid.
16. Parent-cycle lineage and bounded continuation heuristics are not yet recorded when an
    operator session decides to begin another complete cycle.
17. The current harness implementation uses `:loyly/*` runtime blocks, direct read helpers, and a
    duplicate harness progress event. It lacks the declared cycle-preparation blocks, Grain
    `defquery` handlers, registered `defevent` history contracts, the Grain Event Model runtime
    package, and strict startup/model-conformance validation. ORC also does not yet register the
    `:sheet` Event Model required to resolve the harness's cross-area command and event edges.
    These dependencies must converge with the target `:loyly.pi-harness/*` service area before
    the harness is Grain-conformant.
18. GEPA reflection currently uses the runtime's hardcoded OpenRouter provider. The target contract
    requires explicit Bedrock provider registration, a pinned reflection model per optimization,
    and durable sanitized provider/model/call provenance. AWS credentials must remain outside
    prompts, events, transcripts, and repository state.

These are reconciliation obligations, not alternative sources of truth.
