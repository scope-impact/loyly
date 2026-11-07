# Grader lifecycle: the public seam

How a gate, a grader and an operator drive this context's candidate (slice 03). Derived from
`event-model/*.edn`; where this note and the event model disagree, the event model wins and
this note is wrong. Decided 2026-11-07 (`../decisions.md`, "Slice 03"), in the shape of
`../specification/seam.md` ("First light") and `../measurement/seam.md` ("Slice 02").

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
the leading colon. Params are the block's `:schema`, field names verbatim (kebab-case). Values
that are instants travel as ISO-8601 UTC text. A command's result is `{"events": [...]}`: the
events that command produced, in the order produced, each `{"type": "<area>/<event>", ...}`
with exactly the fields of that event's `:schema` (`event-model/handoffs.edn`).

| Method | Block | Result |
|---|---|---|
| `grader-lifecycle/write-instrument-golden` | command `:grader-lifecycle/write-instrument-golden` | `grader-lifecycle/instrument-golden-written` |
| `grader-lifecycle/move-instrument-golden` | command `:grader-lifecycle/move-instrument-golden` | `grader-lifecycle/instrument-golden-moved` |
| `grader-lifecycle/instrument-goldens` | query `:grader-lifecycle/instrument-goldens` (no params) | `{"goldens": [...]}`: every instrument golden, in the order written, shaped as the read model `:grader-lifecycle/instrument-goldens` (its `moves` in the order moved); `[]` when none was written |

- **A reason is the enumeration's value.** `reason` is one of `instrument-behavior-changed`,
  `wording-replaced` and `spec-grew` (the schema's `:enum`, the Allium's `MoveReason`); a
  scenario's quoted reason ("instrument behavior changed") is that value with hyphens. Any
  other reason ("make the self-check green") does not match the schema: `-32602`, and nothing
  is recorded. The enumeration is the refusal; the Allium has no `requires` for it.
- **The diff is the issuer's.** `additions-only` is the instrument maintainer's reading of the
  golden's diff (no existing item's id, name, rule or source moved; only the total line
  removed); the candidate records it as offered and refuses `spec-grew` without it.
- **A move's scenario digest** is in its event (`scenario-digest`), and is the golden's
  `scenario-digest` in the read model after it.

## The scenario digests, handed in

`WriteInstrumentGolden` and `MoveInstrumentGolden` compare the offered `scenario-digest` with
`digest_of_scenario(scenario)`, the contract `ScenarioDigests` the surface
`InstrumentGoldenDesk` fulfils. The event model gives the candidate no place to read a
scenario from, so its current scenario digests are handed in, like its store:

- `LOYLY_SCENARIO_DIGESTS`: the path of a JSON file, one object whose keys are scenarios, as
  the issuer names them in `scenario` (an item identity such as `@item-03-004`, or a title),
  and whose values are their current scenario digests:
  `{"a refused score names the unknown item": "s-12"}`. It is the reading of the cut by
  Specification's one reading of Gherkin, the issuer's, as the checklist's reading is in
  Measurement (`../decisions.md`, "Slice 02" 5); the candidate never computes a digest.
- The candidate reads the file each time a command checks a scenario digest, never only at
  boot: a reworded scenario shows as soon as the file says so. `digest_of_scenario(s)` is the
  file's value for the key `s`, exactly; a scenario the file does not name, an absent file and
  an unset variable have no digest, so every offered digest differs from it.
- `scenario` itself is recorded as offered (the golden's `scenario`), and
  `MoveInstrumentGolden` looks up the golden's recorded `scenario`, not a new one.

## Refusals

A refusal records nothing and is the error `-32000` with `data.refusal`, one of the codes
below. The numbers are the `requires` of the rule in `allium/instrument-golden.allium`; they
are checked in that order and the first that fails is the refusal.

| Method | Allium rule | `data.refusal` by requires |
|---|---|---|
| `grader-lifecycle/write-instrument-golden` | `WriteInstrumentGolden` | 1 `golden-exists`, 2 `scenario-digest-mismatch` |
| `grader-lifecycle/move-instrument-golden` | `MoveInstrumentGolden` | 1 `no-such-golden`, 2 `same-digest`, 3 `spec-grew-not-additions-only`, 4 `empty-note`, 5 `stale-scenario-digest` |

An empty `note` is the refusal `empty-note` (requires 4), not `-32602`, although the schema's
`[:string {:min 1}]` says the same: the Allium names it a refusal, and its place in the order
is the Allium's. Every other param that does not match the schema is `-32602`.

"Records nothing" means no event of `event-model/` is appended; ORC's and Grain's own run
events (a workflow run and its steps) may still be written. A `null` for a field that is
not `[:maybe ...]` is `-32602`, as any other param that does not match its schema
(`../decisions.md`, "Spec round 3").

## The event store

The candidate records its facts in Grain's event store, the SQLite backend of Grain's
`event-store-v3` (connection type `:sqlite`, given the file's path).

- `LOYLY_GRAIN_STORE`: the path of the SQLite file. When it is set, the candidate
  uses that file, creating it if absent, and no other store. When it is unset, the candidate
  may use an in-memory store (its own tests, for example).
- The gate hands the candidate a fresh file through `LOYLY_GRAIN_STORE`, and after the
  candidate has run, reads that file itself, not through this seam: it must hold at least
  one event of `event-model/`; every event's type must be an event of `event-model/` or ORC's
  or Grain's own (this context consumes no handoff); and every such event's fields, other
  than Grain's own `:event/*` keys, must be exactly its `:schema`.
- ORC runs on the same store: the same file and tenant, not a store of its own. Each
  command method runs as the ORC workflow named after the flow its command belongs to in
  `event-model/area.edn`; a query method runs none.

  | Command | ORC workflow |
  |---|---|
  | `grader-lifecycle/write-instrument-golden`, `grader-lifecycle/move-instrument-golden` | `grader-lifecycle/golden-written-then-moved` |

  The gate requires every event a command produced to be written inside a run of that
  workflow which completed with success (between its `:sheet/tree-tick-started` and its
  `:sheet/tree-tick-completed`, `:root-status :success`).
- The candidate registers this context's event model, `event-model/*.edn` merged, as given
  (Grain's `register-event-model!`), and refuses to start when Grain's boot guard
  (`verify-or-throw!`) finds the running blocks and the model apart.
