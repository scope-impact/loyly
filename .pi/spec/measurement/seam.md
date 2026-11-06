# Measurement: the public seam

How a gate, a grader and an operator drive this context's candidate (slice 02). Derived from
`event-model/*.edn` and the handoff contracts it consumes; where this note and the event model
disagree, the event model wins and this note is wrong. Decided 2026-11-07 (`../decisions.md`,
"Slice 02"), in the shape of `../specification/seam.md` ("First light").

## Transport

- `start.sh` starts the candidate reading JSON-RPC 2.0 requests on stdin and writing
  responses on stdout: one JSON object per line, one response per request, carrying the
  request's `id`. Nothing else goes to stdout; diagnostics go to stderr.
- A request is `{"jsonrpc": "2.0", "id": <id>, "method": <method>, "params": {...}}`.
- A success is `{"jsonrpc": "2.0", "id": <id>, "result": {...}}`.
- A failure is `{"jsonrpc": "2.0", "id": <id>, "error": {"code": <int>, "message": <text>, "data": {...}}}`:
  `-32700` a line that is not JSON, `-32601` an unknown method, `-32602` params that do not
  match the schema, `-32000` a refusal (below).

## Methods

One method per Grain command and query of `event-model/`, named exactly as the block, without
the leading colon. Params are the block's `:schema`, field names verbatim (kebab-case); an
optional field may be left out, and a `[:maybe ...]` field may be `null`. Values that are
instants travel as ISO-8601 UTC text. A command's result is `{"events": [...]}`: the events
that command produced, in the order produced, each `{"type": "<area>/<event>", ...}` with
exactly the fields of that event's `:schema`.

| Method | Block | Result |
|---|---|---|
| `measurement/derive-checklist` | command `:measurement/derive-checklist` | `measurement/checklist-derived` |
| `measurement/file-item-verdicts` | command `:measurement/file-item-verdicts` | `measurement/item-verdicts-filed` |
| `measurement/file-score` | command `:measurement/file-score` | `measurement/measurement-filed` |
| `measurement/fail-score-stage` | command `:measurement/fail-score-stage` | `measurement/score-stage-failed` |
| `measurement/report-scoreboard` | command `:measurement/report-scoreboard` | `measurement/scoreboard-reported`, then `measurement/moved-item-set-restated` when the item set moved |
| `measurement/checklist` | query `:measurement/checklist` (`cut-id`) | `{"checklist": ...}`: the read model `:measurement/checklists` of that cut, or `null` when none was derived |
| `measurement/cycle-measurement` | query `:measurement/cycle-measurement` (`cycle-id`) | `{"measurement": ...}`: the read model `:measurement/cycle-measurements` of that cycle (`status` `filed` or `score-stage-failed`), or `null` while its score is awaited or when the cycle was never gated |
| `measurement/scoreboard` | query `:measurement/scoreboard` (`cycle-id`) | `{"scoreboard": ...}`: the fields of the cycle's `measurement/scoreboard-reported` event, plus `restatement`, the fields of its `measurement/moved-item-set-restated` event or `null`; `null` when none was reported |
| `measurement/measurements-to-report` | query `:measurement/measurements-to-report` | `{"cycle-ids": [...]}`: the filed measurements with no scoreboard yet, in filing order |

- **The reading of the spec is the issuer's.** `measurement/derive-checklist` takes the
  reading of the cut's spec files as its params, as the event model's command does: `items` (one
  per stamped scenario of every slice not planned or backlog, in file order, then position in
  the file), `unstamped` and `skipped-slices`. The issuer reads with Specification's one reading
  of Gherkin (guarantee `OneReadingOfTheSpec` of the surface `InstrumentDesk`); a grader reads
  its fixture spec files that way. The candidate records what it is offered: it keeps the
  offered order, never sorts by item identity, and changes no field. An item's `source` is the
  spec file and the line of its `Scenario` line, `<path>:<line>`, and its `rule` is `null`
  when the scenario sits under no Rule. The `checklist-digest` is the candidate's: the same
  items in the same order always give the same digest, and different items a different one.
- **The policy runs first.** When `measurement/file-score` files a measurement,
  the policy `report-scoreboard-once` issues `measurement/report-scoreboard` for it, and that
  scoreboard is reported before `measurement/file-score` responds; its events are not in
  `file-score`'s result, and `measurement/scoreboard` reads them. Calling
  `measurement/report-scoreboard` for that cycle again is refused (already reported).
- **The previous cycle** of a scoreboard is the last cycle whose measurement was filed before
  this one's, with the same `selection-id`; a cycle with no filed measurement is never one.

## The consumed handoffs, handed in

Measurement consumes three events other contexts produce. In a cycle of this context alone
those contexts are stubbed from their contracts, the producer's `event-model/handoffs.edn` (the
only file of each the room holds), and their facts reach the candidate through this seam: one
method per consumed event, named exactly as the event, its params exactly the event's contract
`:schema`.

| Method | Contract | The candidate's read model that folds it |
|---|---|---|
| `specification/scenario-stamped` | `../specification/event-model/handoffs.edn` | `:measurement/item-identities` |
| `cycle-preparation/source-cut-pinned` | `../cycle-preparation/event-model/handoffs.edn` | `:measurement/pinned-cuts` |
| `regeneration-cycle/gate-attempt-passed` | `../regeneration-cycle/event-model/handoffs.edn` | `:measurement/gated-cycles` |

The candidate appends the event, exactly as given (its type and its fields, nothing added),
to its Grain store as the producer would, and responds `{"events": [<that event>]}`. It runs no
ORC workflow: the event is the producer's fact, not a command of this context. Params that do
not match the contract are `-32602` and append nothing; nothing else refuses a handed-in event
(the folds ignore one they already hold: `NoteItemIdentity`, `NotePinnedCut` and
`OpenMeasurement`, requires 1). These methods stand in for the producers until a gate
assembles the contexts (`../decisions.md`, "First light" 5); they are the only way another
context's event enters the store.

## Refusals

A refusal records nothing and is the error `-32000` with `data.refusal`, one of the codes
below; where it names items, `data.item-ids` lists them, and refusal `unstamped-scenarios`
carries `data.unstamped`, every unstamped scenario offered (`source`, `title`), its message
naming the stamper (`specification/stamp-slice`). The numbers are the `requires` of the rule,
in its Allium file.

| Method | Allium rule | `data.refusal` by requires |
|---|---|---|
| `measurement/derive-checklist` | `DeriveChecklist` (`allium/checklist.allium`) | 1 `cut-not-pinned`, 2 `unstamped-scenarios`, 3 `empty-checklist`, 4 `item-never-stamped`, 5 `item-twice`, 6 `checklist-already-derived`, 7 `empty-title` |
| `measurement/file-item-verdicts` | `FileItemVerdicts` (`allium/cycle-measurement.allium`) | 1 and 2 `not-the-gated-candidate`, 3 `no-checklist`, 4 `zero-verdicts`, 5 `bad-verdict-value`, 6 `unknown-item`, 7 `measurement-already-filed`, 8 `score-stage-failed`, 9 `grader-not-approved`, 10 `bad-verdict-reason` |
| `measurement/file-score` | `FileScore` | 1 `not-the-gated-candidate`, 2 `zero-verdicts`, 3 `measurement-already-filed`, 4 `score-stage-failed`, 5 `grader-not-approved`, 6 `not-the-gated-candidate` |
| `measurement/fail-score-stage` | `FailScoreStage` | 1 `not-the-gated-candidate`, 2 `measurement-already-filed`, 3 `score-stage-failed` |
| `measurement/report-scoreboard` | `ReportScoreboard` (`allium/scoreboard.allium`) | 1 `no-measurement-filed`, 2 `scoreboard-already-reported` |

The requires are checked in their Allium order and the first that fails is the refusal: a
verdict neither pass nor fail is refused before an unknown item. The refusal codes of
`file-item-verdicts` and `file-score` that `measurement/score-stage-failed` enumerates are the
same text, so ORC runtime can record a refused score's code as it heard it.

## The event store

The candidate records its facts in Grain's event store, the SQLite backend of Grain's
`event-store-v3` (connection type `:sqlite`, given the file's path).

- `LOYLY_GRAIN_STORE`: the path of the SQLite file. When it is set, the candidate
  uses that file, creating it if absent, and no other store. When it is unset, the candidate
  may use an in-memory store (its own tests, for example).
- The gate hands the candidate a fresh file through `LOYLY_GRAIN_STORE`, and after the
  candidate has run, reads that file itself, not through this seam: it must hold at least
  one event of `event-model/`; every event's type must be an event of `event-model/`, a
  consumed handoff above, or ORC's or Grain's own; and every such event's fields, other than
  Grain's own `:event/*` keys, must be exactly its `:schema` (a handoff's, its contract's).
- ORC runs on the same store: the same file and tenant, not a store of its own. Each
  command method runs as the ORC workflow named after the flow its command belongs to in
  `event-model/area.edn`; a query method and a handed-in handoff run none.

  | Command | ORC workflow |
  |---|---|
  | `measurement/derive-checklist` | `measurement/stamping-to-checklist` |
  | `measurement/file-item-verdicts`, `measurement/file-score`, `measurement/fail-score-stage` | `measurement/scoring` |
  | `measurement/report-scoreboard`, also when the policy issues it | `measurement/scoreboard-restates-moved-item-set` |

  The gate requires every event a command produced to be written inside a run of that
  workflow which completed with success (between its `:sheet/tree-tick-started` and its
  `:sheet/tree-tick-completed`, `:root-status :success`).
- The candidate registers this context's event model, `event-model/*.edn` merged with the
  three consumed handoff events from their contracts, as given (Grain's
  `register-event-model!`), and refuses to start when Grain's boot guard (`verify-or-throw!`)
  finds the running blocks and the model apart.
