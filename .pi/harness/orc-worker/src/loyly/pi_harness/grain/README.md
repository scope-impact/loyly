# Harness-owned Grain model

This package implements `model/pi-harness/regeneration_event_model.clj` and
nothing from ORC's `:sheet/*` stream or the generated product's event stream.

| Model capability | Public operation | Commands | Events / projection |
| --- | --- | --- | --- |
| Selection approval | `record-selection!`, `approve-selection!`, `find-selection` | `:loyly.pi-harness/record-selection`, `:loyly.pi-harness/approve-selection` | selection facts → `:loyly.pi-harness/selections` |
| Cycle preparation | `record-cycle-preparation!`, `approve-cycle-preparation!`, `query-cycle-preparation` | preparation record/approval commands | preparation facts → `:loyly.pi-harness/cycle-preparations` |
| Approved dispatch | automatic todo processor | `:loyly.pi-harness/start-approved-cycle` → `:sheet/tick-tree` | checkpointed after the ORC dispatch boundary |
| Cycle progress | `find-cycle-progress` | none | authoritative `:sheet/*` execution facts → `:loyly.pi-harness/cycle-progress` |
| Cycle observations | `record-observation!`, `find-cycle` | `:loyly.pi-harness/record-observation` | observation facts → `:loyly.pi-harness/cycle-observations` |
| Cycle measurement | `record-measurement!`, `find-measurement` | `:loyly.pi-harness/record-cycle-measurement` | measurement facts → `:loyly.pi-harness/cycle-measurements` |
| Candidate routing | candidate/route operations on `interface.clj` | generated-candidate and approved-route commands | durable component lineage and request routing projections |

The package mirrors an ORC Polylith component:

- `interface.clj` is the only production dependency boundary.
- `interface/schemas.clj` owns command and event schemas.
- `core/commands.clj` validates intent and emits events.
- `core/read_models.clj` registers pure `defreadmodel` projections.
- `core/queries.clj` registers every public read through `defquery`.
- `core/processors.clj` owns the checkpointed approved-preparation reaction.
- `runtime_event_model.clj` strictly reconciles the committed model at boot.

The three former capability namespaces are compatibility façades only. Direct
event appends and dependencies on `grain.core.*` are not part of the public API.
Generated candidates remain language-independent; Grain is the Pi-harness
control and evidence layer only.
