# Specification: the public seam

How a gate, a grader and an operator drive this context's candidate. Derived from
`event-model/*.edn`; where this note and the event model disagree, the event model wins and
this note is wrong. Decided 2026-11-06 (`../decisions.md`, "First light").

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
that are instants travel as ISO-8601 UTC text.

| Method | Block | Params | Result |
|---|---|---|---|
| `specification/stamp-slice` | command `:specification/stamp-slice` | `slice`, `source`, `unstamped` (`[{line, title}]`), `empty-title-lines` (`[line]`) | `{"events": [...]}`: the events the command produced, in order |
| `specification/stamped-items` | query `:specification/stamped-items` | `slice` (optional) | `{"slices": [...]}`: one entry per slice, shaped as the read model `:specification/stamped-items`; only the named slice when `slice` is given, and none for a slice never stamped |

Each produced event is `{"type": "specification/scenario-stamped", ...}` or
`{"type": "specification/empty-title-headings-reported", ...}`, with exactly the fields of
that event's `:schema`. A stamping with nothing to stamp succeeds with no
`specification/scenario-stamped` event: the slice is fully stamped.

- `source` is the path of the slice's spec file, absolute or relative to the candidate's
  working directory. Its slice is the name of the folder holding it, up to the first `-`
  (`plan/03-gate/spec.feature` is slice `03`).
- `unstamped` and `empty-title-lines` are what the stamper read in that file: every
  unstamped scenario's heading line (counted from 1, in the file as it was read) and title,
  in file order, and every empty-title heading's line. The candidate reads the file itself
  with the one reading of Gherkin (`allium/context.allium`, contract `ScenarioReading`) and
  refuses an offer that differs from it.
- A stamped scenario carries its identity in the spec file: the candidate writes the tag
  `@item-<slice>-<number>` (the number zero padded to three digits) on a line of its own
  directly above the scenario's heading, with the heading's indentation, and keeps every
  other line and tag. The event's `item-id` is `<slice>-<number>`, without `@item-`.

## Refusals

A refusal records nothing and is the error `-32000` with `data.refusal`, one of:

| `data.refusal` | Allium (`allium/slice.allium`) | The message |
|---|---|---|
| `no-spec-found` | `StampFirstScenarios` and `StampSlice`, requires 1 | names the `source` it looked for; never says "fully stamped" |
| `not-this-slice` | requires 2 | names the source's slice and the slice asked for |
| `unstamped-mismatch` | requires 4 | the scenarios offered are not exactly the file's unstamped ones, in file order |
| `empty-title-mismatch` | requires 5 | the empty-title headings offered are not exactly the file's |

Requires 3 of each rule only chooses between the two rules (a ledger exists or not); no
caller hears it.

## The event store

The candidate records its facts in Grain's event store, the SQLite backend of Grain's
`event-store-v3` (connection type `:sqlite`, given the file's path).

- `LOYLY_GRAIN_STORE`: the path of the SQLite file. When it is set, the candidate
  uses that file, creating it if absent, and no other store. When it is unset, the candidate
  may use an in-memory store (its own tests, for example).
- The gate hands the candidate a fresh file through `LOYLY_GRAIN_STORE`, and after the
  candidate has run, reads that file itself, not through this seam: it must hold at least
  one event, every event's type must be an event of `event-model/`, and every event's
  fields, other than Grain's own `:event/*` keys, must be exactly that event's `:schema`.
- The candidate registers this context's event model, `event-model/*.edn` merged, as given
  (Grain's `register-event-model!`), and refuses to start when Grain's boot guard
  (`verify-or-throw!`) finds the running blocks and the model apart.
