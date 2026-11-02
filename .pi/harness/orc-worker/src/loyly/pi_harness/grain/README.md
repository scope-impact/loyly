# Harness-owned Grain model

This package implements `model/pi-harness/regeneration_event_model.clj` and
nothing from ORC's `:sheet/*` stream or the generated product's event stream.

| Model capability | Public operation | Commands | Events / projection |
| --- | --- | --- | --- |
| Selection approval | `record-selection!`, `approve-selection!`, `find-selection` | `:loyly/record-selection`, `:loyly/approve-selection` | `:loyly/selection-recorded`, `:loyly/selection-approved` → `:loyly/selection` |
| Cycle observations | `record-observation!`, `find-cycle` | `:loyly/record-observation` | `:loyly/observation-recorded` → `:loyly/observations` |
| Cycle measurement | `record-measurement!`, `find-measurement` | `:loyly/record-cycle-measurement` | `:loyly/cycle-measurement-recorded` → `:loyly/cycle-measurement` |

The package mirrors an ORC Polylith component:

- `interface.clj` is the only production dependency boundary.
- `interface/schemas.clj` owns command and event schemas.
- `core/commands.clj` validates intent and emits events.
- `core/read_models.clj` registers `defreadmodel` projections and owns queries.

The three former capability namespaces are compatibility façades only. Direct
event appends and dependencies on `grain.core.*` are not part of the public API.
