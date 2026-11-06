# Session-hosted regeneration harness contract

This directory is the canonical **target contract** for the regeneration cycle. Its Gherkin
spec uses the legacy layout, one slice per `plan/NN-slug/spec.feature` stamped with
`@item-NN-SSS` by `harness/stamp_item_ids.clj`; the Allium files sit beside it. The legacy
harness in `harness/` generates this harness cleanly from this contract, built on ORC and
Grain. It is separate from the Löyly product model, which describes a product the harness
regenerates.

ORC is itself Grain-native. The Pi harness is therefore the `:loyly.pi-harness` Grain service
area inside ORC's existing tenant-scoped runtime, not a second event system or an adapter around
ORC. Every harness state change passes through a Grain command and registered event; every public
read passes through a Grain query over a registered read model. Grain processors react to facts
when asynchronous coordination is required. ORC `:sheet/*` events remain execution authority and
are consumed directly by harness projections rather than copied into harness progress events.

The architecture this contract specifies is summarized in
[`.pi/architecture.md`](../../.pi/architecture.md); this model remains the source of truth.

The target contract must not be read as a claim that every behavior is already
implemented. A regenerated harness is measured by the sealed graders an operator approved
(`harness/graders.approved.json`); the `@e2e` and `@skip` tags left on scenarios by an earlier
executable suite carry no verdict.

Each item also carries one build tier, on the same line as its `@item` tag. A tier carries no
verdict and never changes the checklist or its denominator:

| Tag | Items | Meaning |
|---|---|---|
| `@minimum` | 29 | Parity with the legacy harness: the cut, fence, gate, probe, stamping, checklist, score, scoreboard, and goldens. Without these there is no cycle. |
| `@balance` | 36 | New since the legacy harness, cheap on ORC, or closing a known legacy gap: the sealed grader, selection and approval, per-slice and durable builders, the RPC seam, and idempotency. |
| `@deferred` | 29 | Not needed until a baseline scoreboard exists: runtime evidence (05), prompt optimization (06), and the operator cockpit and Grain surface in 04. |

- Every builder (slice, repair, grader author) is a durable Pi session that ORC launches,
  keyed by its tick, in a Gondolin room ORC chooses (`02-018`, `02-019`,
  `execution-pipeline.allium`). The durable Pi host and the Gondolin images are provided, like
  ORC and Grain; [`.pi/architecture.md`](../../.pi/architecture.md) states the split.
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
- `event-model/loyly/pi_harness/event_model.clj` (`loyly.pi-harness.event-model`) mandates the
  harness-owned Grain service area. Its block names use the `:loyly.pi-harness/*` namespace, its
  public commands, events, read models, queries, and processors must reconcile with live
  registries, and every event has an explicit `defevent` history contract. Startup and CI fail
  strict validation on missing, extra, or mismatched blocks.
  `event-model/orc/sheet/event_model.clj` (`orc.sheet.event-model`) declares only the ORC
  `:sheet` command and events the harness dispatches or projects, copied from ORC's pinned
  `orc-service`, so every edge resolves. One area per file; each namespace matches its path.
- `COVERAGE.md` maps each slice to its spec, each command to its Allium rule, and records the
  verification commands and the accepted Allium diagnostic baseline.
- `affordances.yaml` is this contract's public seam, in the affordance format: the harness has no
  UI, so it declares a `seam` (JSON-RPC over stdio) instead of screens, and each action's call
  names an `rpc` method. Every outcome and rejection names the Allium rule it realises. A
  sealed grader drives a candidate only through it.
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
and processors. Löyly product events remain governed by the Löyly product model. The three service areas
share Grain infrastructure without duplicating one another's facts.
