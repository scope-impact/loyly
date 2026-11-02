# Grain fit for Löyly

**Status:** research
**Date:** 2026-10-31
**Question:** How should Grain fit Löyly's domain model, regeneration workflow, and ORC integration?

## Executive conclusion

Grain is a strong fit for Löyly's **domain model and generated application architecture**. It gives
Löyly a constrained CQRS/event-sourcing vocabulary, a declared Event Model, schema-validated
commands/events, projections, queries, and runtime conformance checks.

Grain should not replace ORC. The boundaries should be:

```text
Löyly Grain Event Model + Allium
  → generated/implemented application topology
  → Grain domain events and read models

ORC
  → cut, builder, instrument, grader, retries, checkpoints, regeneration results

Gondolin/Pi adapters
  → physical VM and Codex capabilities only
```

Grain's Event Model is a **CQRS topology**, not a generic container/component/item hierarchy.
Löyly's regeneration slice structure already exists in `docs/plan/**` and its stable `@item-*`
scenario IDs. We should not introduce a second hierarchy merely to make ORC scopes look like
containers and components.

## Sources inspected

Primary sources:

- `grain/README.md` — Grain's purpose, CQRS/event-sourcing paradigm, macro vocabulary, and
  relationship between Allium and Event Models.
- `grain/docs/event-model.md` — Event Model shape, legal topology, schemas, flow edges, Allium
  trace links, and runtime validation.
- `grain/docs/core-concepts.md` — commands, events, read models, queries, processors, event
  construction, tenancy, and projection semantics.
- `grain/components/example-service/src/ai/obney/grain/example_service/interface/event_model.clj`
  — complete service-area Event Model example.
- `grain/components/event-model-validator/src/ai/obney/grain/event_model_validator/core.clj` —
  live-registry reconciliation and structural validation rules.
- `grain/components/example-service/example-service.allium` — behavioral domain specification
  example.
- `orc/docs/ORC-PRINCIPLES.md` — ORC's event-first discipline, deterministic spine, durable
  workflow composition, and domain-agnostic reusable workflows.
- `orc/docs/ARCHITECTURE.md` and `orc/docs/DSL-REFERENCE.md` — ORC workflow build, execution,
  blackboard, event persistence, code nodes, delegation, and recovery.
- Löyly `README.md` — documentation as source of truth, generated `impl/`, and regeneration loop.
- Löyly `docs/plan/01-booking-core/plan.md` and `spec.feature` — current product slice, rules,
  stable item IDs, and acceptance contract.
- Löyly `harness/README.md`, `harness/spec.feature`, and `harness/config.sh` — measurement,
  identity, and regeneration contracts.

## What Grain provides

### 1. A constrained application paradigm

Grain deliberately standardizes information-system construction around CQRS and event sourcing.
The core public vocabulary is:

- `defcommand` — validates a request, applies decisions/invariants, and produces events;
- `defevent` — registers event schema, documentation, and history policy;
- `->event` — constructs an event instance with Grain's event identity/timestamp behavior;
- `defreadmodel` — defines a pure `(state, event) -> state` projection;
- `defquery` — reads projections without causing state changes;
- `defprocessor` — reacts to events and optionally emits commands/events;
- `defperiodic` — emits scheduled trigger events;
- `defschemas` — declares reusable data shapes.

**Source:** `grain/README.md`, `grain/docs/core-concepts.md`.

This is valuable for Löyly because the central domain failure is duplicated mutable
representations of one booking. A booking event stream plus derived schedule, heater, and
billing projections directly expresses the desired invariant: one record, many derived views.

### 2. A declared Event Model

The Event Model is an EDN/Clojure value registered with `defeventmodel` and keyed by service area.
It declares:

- commands and their schemas, reads, and produced events;
- events and their schemas;
- read models and consumed events;
- queries and their read models;
- todo processors, periodic tasks, and screens;
- flows connecting the blocks.

The example model in
`grain/components/example-service/src/ai/obney/grain/example_service/interface/event_model.clj`
is a useful template for Löyly.

The legal flow grammar is deliberately constrained:

```text
command        → event
 event         → read-model
 read-model    → command | query
 query         → screen | todo-processor
 screen        → command
todo-processor → command
periodic-task  → command | event
```

**Source:** `grain/docs/event-model.md` and
`grain/components/event-model-validator/src/ai/obney/grain/event_model_validator/core.clj`.

This prevents architecture drift, but it means the Event Model should describe Löyly's actual
booking topology—not be repurposed as an arbitrary regeneration scope tree.

### 3. Behavioral traceability through Allium

Grain deliberately separates two specifications:

- Allium describes observable behavior, rules, invariants, entities, and surfaces.
- Grain Event Model describes CQRS service topology.

Commands should link to Allium rules, and screens should link to Allium surfaces with
`:grain/allium` references. The composition gate resolves these references and fails on missing
or wrong-kind links.

**Source:** `grain/docs/event-model.md`, `grain/README.md`, and
`grain/components/example-service/src/ai/obney/grain/example_service/interface/event_model.clj`.

For Löyly, this is a good fit for linking implementation topology back to the existing domain
corpus and plan scenarios. The Gherkin harness remains a measurement instrument; it should not
be silently replaced by an Event Model.

### 4. Runtime conformance

The Event Model validator compares the declared model with live Grain registries. It can detect:

- missing or extra blocks;
- misnamespaced blocks;
- dangling or wrong-kind references;
- schema mismatches;
- missing production/read edges;
- missing authorization declarations;
- missing Allium composition links when the composition gate is used.

Strict validation is intended to run before production startup through
`event-model-validator/verify-or-throw!`.

**Source:** `grain/docs/event-model.md` and
`grain/components/event-model-validator/src/ai/obney/grain/event_model_validator/core.clj`.

For regeneration, this gives us a deterministic structural gate in addition to Löyly's
behavioral harness gate.

### 5. Durable projections

Grain read models are pure reducers over events. The read-model processor provides incremental
projection, caching, watermarks, and optional durable L2 storage. Queries read projections rather
than mutating state or reading raw events directly.

**Source:** `grain/docs/core-concepts.md`.

This is the right mechanism for any future Löyly read model such as:

- current standing bookings;
- weekly availability;
- heater schedule;
- billing signals;
- regeneration history or scope recommendations.

## What Grain does not provide for this purpose

Grain does not provide:

- Gondolin VM isolation;
- denied-egress enforcement;
- Pi/Codex sessions;
- clean Git cuts;
- builder workspace scoping;
- ORC workflow scheduling, retries, or blackboard execution;
- the Löyly harness's exact checklist/oracle semantics;
- automatic inference of a product's domain hierarchy from arbitrary documents.

Those remain ORC, Gondolin, Pi, or Löyly-harness responsibilities.

## Löyly's current domain source

Löyly already has a domain-to-measurement chain:

```text
raw/
  → docs/wiki/
  → docs/plan/01-booking-core/
  → harness/spec.feature
  → harness checklist and verification
  → generated impl/
```

The current slice is explicitly structured as:

- slice: `01-booking-core`;
- rules: availability, confirmation/holding, second-slot policy, derived views, concurrency,
  actionable refusals;
- stable scenario IDs: `@item-01-001` through the current slice's items;
- acceptance: every scenario must pass against the real interface.

**Sources:** Löyly `README.md`, `docs/plan/01-booking-core/plan.md`, and
`docs/plan/01-booking-core/spec.feature`.

This is already the correct measurement hierarchy. We should not invent a parallel
`container/component/item` taxonomy unless those concepts are genuinely added to the domain
model. A Grain Event Model should describe the booking service's commands/events/projections,
while existing slice/rule/item IDs remain the harness join keys.

## Proposed Löyly Grain Event Model

The first model should cover the `booking-core` slice, for example:

```clojure
(defeventmodel :loyly/booking
  {:description "Standing sauna-slot booking for one cooperative."

   :commands
   {:loyly/request-standing-slot
    {:schema [:map
              [:household-id :uuid]
              [:resource-id :keyword]
              [:weekday :keyword]
              [:start-time :string]]
     :reads #{:loyly/booking-state}
     :produces #{:loyly/standing-slot-confirmed
                 :loyly/standing-slot-refused}}

    :loyly/release-standing-slot
    {:schema [:map [:booking-id :uuid]]
     :reads #{:loyly/booking-state}
     :produces #{:loyly/standing-slot-released}}}

   :events
   {:loyly/standing-slot-confirmed {:schema ...}
    :loyly/standing-slot-refused    {:schema ...}
    :loyly/standing-slot-released   {:schema ...}
    :loyly/invoicing-signal-raised {:schema ...}}

   :read-models
   {:loyly/booking-state
    {:consumes #{:loyly/standing-slot-confirmed
                 :loyly/standing-slot-released}}
    :loyly/weekly-schedule
    {:consumes #{:loyly/standing-slot-confirmed
                 :loyly/standing-slot-released}}
    :loyly/heater-schedule
    {:consumes #{:loyly/standing-slot-confirmed
                 :loyly/standing-slot-released}}}

   :queries
   {:loyly/weekly-schedule {:reads #{:loyly/weekly-schedule}}
    :loyly/heater-schedule {:reads #{:loyly/heater-schedule}}}

   :flows
   {:loyly/booking-lifecycle {:steps [...]}}})
```

This is illustrative, not a finalized schema. The final command/event names and payloads must be
worked out from the existing wiki and raw sources before code generation.

The Event Model should link each command to the relevant Allium rule and each screen to the
relevant Allium surface. It should not link directly to generated implementation files.

## How Grain and ORC should fit together

### Separate authorities

```text
Grain event store
  = Löyly domain truth and application projections

ORC event/workflow store
  = regeneration execution truth
```

Do not duplicate ORC execution state as Löyly domain events. Do not put generated application
booking events into ORC's workflow blackboard. Keep the two event vocabularies namespaced and
semantically separate.

### ORC deterministic spine

ORC's workflow model is a strong fit for the regeneration loop because it provides typed
blackboard keys, code nodes, sequences, delegation, event-sourced execution, content-hashed
workflow definitions, traces, and restart recovery.

**Sources:** `orc/docs/ARCHITECTURE.md`, `orc/docs/DSL-REFERENCE.md`, and
`orc/docs/ORC-PRINCIPLES.md`.

The proposed workflow remains:

```text
cut
→ builder
→ instrument
→ oracle
→ verify
→ graders
→ result
```

Grain is not another ORC stage in that spine. Grain's role is to define/validate the application
that the builder is regenerating, and eventually to provide application projections used by
application-level tests.

### Scope selection without a second hierarchy

For now, ORC should accept the existing slice/item identity explicitly:

```clojure
{:slice-id "01-booking-core"
 :item-ids ["item-01-001" "item-01-002"]}
```

A future planner can query a Grain projection or the plan/spec index, but it should return these
existing IDs rather than inventing a new component tree.

A useful later projection would join:

```text
Allium rule/surface ID
+ plan slice ID
+ @item ID
+ Grain command/event/read-model IDs
+ verification contract
```

That projection is a cross-reference/read model, not a replacement for either source model.

### Builder boundary

The builder may read the pinned Grain Event Model and Allium specifications inside `/cut`. It
must not read previous implementation output, prior cycle measurements, or posterior recommendations.
ORC passes the selected slice/item IDs as explicit workflow input.

### Verification boundary

Verification has two layers:

1. Grain structural conformance: Event Model versus live Grain registries and schemas.
2. Löyly behavioral conformance: the existing harness scenarios, oracle, probe, and gate.

A generated implementation must satisfy both when it claims to be a Grain-backed Löyly build.
The existing shell harness remains the parity/reference instrument during migration.

## Events and projections for regeneration

If we later persist regeneration lifecycle facts through Grain, use commands and projections—not
bare event-store appends. The event-first rule is explicit in both Grain and ORC documentation.

Possible separate regeneration service area:

```clojure
(defeventmodel :loyly/regeneration
  {:commands
   {:loyly/request-regeneration {...}}
   :events
   {:loyly/regeneration-requested {...}
    :loyly/cut-prepared {...}
    :loyly/build-completed {...}
    :loyly/measurement-recorded {...}
    :loyly/regeneration-completed {...}}
   :read-models
   {:loyly/regeneration-cycles {...}}})
```

However, this should not be introduced before the actual regeneration loop is reliable. ORC is
already the current owner of regeneration execution and its durable stage results. Grain can
become the long-lived domain/provenance projection once its storage and tenancy decisions are
real rather than speculative.

## Risks and non-fit areas

### 1. Grain is not a free-form hierarchy engine

The Event Model's legal topology is intentionally constrained. Do not use it to encode arbitrary
workflow or plan nesting. Use Allium for behavior and the existing plan/spec IDs for regeneration
measurements.

### 2. Duplicate sources of truth

Adding a new `scope.edn`, a second item registry, or a manually maintained component tree would
contradict Löyly's documentation-as-source-of-truth rule. Any cross-reference should be derived
from existing plan, Allium, and Event Model sources.

### 3. Two event stores / two meanings of event

Grain and ORC both use event-sourcing concepts. Mixing their event streams would make provenance
ambiguous. Use explicit namespaces and adapters; never treat an ORC stage completion as a Löyly
business event.

### 4. Generated code is not the model

Grain's runtime validator reconciles a model with live registries. That is valuable only if the
model is authored before implementation and included in the cut. The builder must not be allowed
to redefine the model merely to make generated code validate.

### 5. Tenant identity

Grain requires tenant-scoped event-store operations. Löyly's actual housing-cooperative/house
boundary must be decided from the domain sources before enabling a production Grain store. This
is separate from the regeneration request ID and ORC task identity.

### 6. Allium/Grain tooling availability

Composition validation uses repository-relative Allium paths and may require the Allium CLI in
development/CI. Production boot validation does not require Allium source files or CLI, according
to Grain's Event Model documentation. The regeneration cut must nevertheless include the model and
behavioral specs needed by the builder.

## Recommended implementation order

### Phase 1 — model before code

1. Add Löyly's first Allium specification for booking-core, grounded in the existing wiki/raw
   sources and linked to `docs/plan/01-booking-core/spec.feature` items.
2. Add a `defeventmodel :loyly/booking` topology with commands, events, read models, queries,
   screens, and flows.
3. Register schemas and links; run Grain's strict Event Model validation.
4. Do not generate application code yet.

### Phase 2 — regeneration integration

1. Include Grain model files in the pinned cut.
2. Have the ORC builder prompt identify the Event Model and Allium files as authoritative.
3. Keep the builder output limited to the declared implementation scope.
4. Run Grain structural validation as an instrument stage.
5. Run the existing Löyly behavioral verifier independently.
6. Compare Allure evidence across both gates.

### Phase 3 — projections and actual app

1. Implement Grain commands/events/read models for booking-core.
2. Add contract tests from Allium and the existing Gherkin scenarios.
3. Verify that schedule, heater, and billing views are projections of booking facts.
4. Only after this works, consider a Grain-backed regeneration-history projection.

### Phase 4 — adaptive slicing

1. Keep stable `@item-*` IDs as measurement keys.
2. Build a derived cross-reference projection joining plan items, Allium rules, and Grain blocks.
3. Let ORC select existing slice/item IDs based on explicit policy.
4. Add posterior recommendations later, without exposing posterior internals to the builder.

## Bottom line

Grain should become Löyly's **event-first domain model and generated application contract**.
ORC should remain Löyly's **durable regeneration engine**. Allium and the existing plan/spec remain
behavioral sources and measurement identifiers. Gondolin and Pi remain physical/model execution
adapters.

The first real integration is not posterior planning. It is authoring and validating the
`booking-core` Grain Event Model and Allium links, then making the ORC builder and independent
Löyly harness treat those files as part of the pinned regeneration source.
