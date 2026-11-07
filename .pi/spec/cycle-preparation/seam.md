# Cycle preparation: the public seam

How a gate, a grader and an operator drive this context's candidate (slice 05). Derived from
`event-model/*.edn`; where this note and the event model disagree, the event model wins and
this note is wrong. Decided 2026-11-07 (`../decisions.md`, "Slice 05"), in the shape of
`../grader-lifecycle/seam.md` ("Slice 03").

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
| `cycle-preparation/pin-source-cut` | command `:cycle-preparation/pin-source-cut` | `cycle-preparation/source-cut-pinned` |
| `cycle-preparation/source-cuts` | query `:cycle-preparation/source-cuts` (no params) | `{"cuts": [...]}`: every pinned source cut, newest first, shaped as the read model `:cycle-preparation/source-cuts`; `[]` when none was pinned |

- **A commit is full hexadecimal.** `commit` is `[:re "^[0-9a-f]{40,64}$"]`: a short or
  upper-case commit does not match the schema, `-32602`, and nothing is recorded. The
  scenarios quote full 40-hex commits for that reason.
- **A tree digest is opaque.** `tree-digest` is compared as text with the repository's tree
  digest of the commit (below), never computed.

## The project repository, handed in

`PinSourceCut` asks the contract `ProjectRepository`, which the surface `Preparation` fulfils,
whether `commit` is a commit (`is_commit`) and what its tree is (`tree_of`). The event model
gives the candidate no place to read a repository from, so the repository's facts are handed
in, like its store (`../decisions.md`, "Slice 05"):

- `LOYLY_PROJECT_COMMITS`: the path of a JSON file, one object whose keys are commits (full
  hexadecimal) and whose values are their tree digests:
  `{"4f2a9c1e0b7d3a5f9c2e8b1d4a6f0c3e7b9d2a51": "t-81"}`.
- The candidate reads the file each time `pin-source-cut` runs, never only at boot.
  `is_commit(c)` is the file naming the key `c`, exactly; `tree_of(c)` is its value. A commit
  the file does not name, an absent file and an unset variable are no commit. The candidate
  runs no `git` and computes no digest.

## Refusals

A refusal records nothing and is the error `-32000` with `data.refusal`, one of the codes
below. The numbers are the `requires` of the rule in `allium/source-cut.allium`; they are
checked in that order and the first that fails is the refusal.

| Method | Allium rule | `data.refusal` by requires |
|---|---|---|
| `cycle-preparation/pin-source-cut` | `PinSourceCut` | 1 `cut-already-pinned`, 2 `not-a-commit`, 3 `tree-digest-not-the-commits` |

`cut-already-pinned` refuses a second pin of a cut identity with the same content as well as
with another: a cut is immutable, and pinning it again records nothing.

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
- ORC runs on this candidate's store: ORC shares this candidate's file and tenant, not a
  store or tenant of its own, and no other context shares them: each context keeps its own
  store (`../decisions.md`, "Spec round 5" 1). Each command method runs as the ORC
  workflow named after the flow its command belongs to in `event-model/area.edn`; a query
  method runs none.

  | Command | ORC workflow |
  |---|---|
  | `cycle-preparation/pin-source-cut` | `cycle-preparation/cut-to-fence` |

  The gate requires every event a command produced to be written inside a run of that
  workflow which completed with success (between its `:sheet/tree-tick-started` and its
  `:sheet/tree-tick-completed`, `:root-status :success`).
- The candidate registers this context's event model, `event-model/*.edn` merged, as given
  (Grain's `register-event-model!`), and refuses to start when Grain's boot guard
  (`verify-or-throw!`) finds the running blocks and the model apart.
