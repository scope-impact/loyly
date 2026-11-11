# harness-impl: the promoted build of spec v2

The new harness, as built: the six contexts of spec v2 (`../.pi/spec/`), each the candidate a
regeneration cycle built from a fenced clean room, that passed its gate and was scored. Promoted
here on 2026-11-08, after the switch (`../.pi/spec/decisions.md`, "The switch to the new
harness"): this is the build that runs the regeneration cycles since then.

| Folder | Context | Cycle | Score |
|---|---|---|---|
| `01-specification/` | specification | 02 | 15/15 |
| `02-measurement/` | measurement | 05 | 43/43 |
| `03-grader-lifecycle/` | grader-lifecycle | 05 | 11/11 |
| `04-regeneration-cycle/` | regeneration-cycle | 05 | 76/76 |
| `05-cycle-preparation/` | cycle-preparation | selfhost-01 (runner c05), the first self-hosted cycle | 4/4 |
| `06-cycle-runner/` | cycle-runner | 07 (http gate runs the probe in the gated tree, spec round 11) | 52/52 |

Each folder's `PROVENANCE.md` names the room it came from, its gate log and verdict, its score
and cycle commit, the spec commit it was built from, and the digest of its source, which equals
the digest computed in the room.

## Running a context

Each context is Clojure on ORC with Grain, and speaks JSON-RPC 2.0 on stdio through its own
`start.sh`; `test.sh` runs its whole suite. What it reads from the environment is in its seam,
`../.pi/spec/<context>/seam.md`. Every context keeps its events in the SQLite Grain store
`LOYLY_GRAIN_STORE` names, for example:

```sh
LOYLY_GRAIN_STORE=/tmp/prep/events.sqlite LOYLY_PROJECT_COMMITS=/tmp/prep/commits.json \
  harness-impl/05-cycle-preparation/start.sh
```

Some contexts read more (`LOYLY_PROJECT_COMMITS`, `LOYLY_SCENARIO_DIGESTS`,
`LOYLY_SCORING_TOOL_PATHS`); their seams say which. A candidate reads its event model from
`../.pi/spec/<context>/event-model/`, so run it from this checkout. Build outputs and caches
(`.cpcache/`, `target/`, `classes/`) are made on first start and ignored (`.gitignore`).

## Driving the cycle runner

`06-cycle-runner/` performs a whole cycle, driving the five contexts of slices 01-05 through
their seams. It is handed its store, a runs directory, the project repository, the project's
harness configuration, the five contexts (`LOYLY_CONTEXTS`, a JSON file naming each context's
`start.sh` here), the self-check, the builder command and the builder instruction:
`../.pi/spec/cycle-runner/seam.md`, "Handed in". How it was run against the Löyly benchmark, and
what stays open (a run cannot yet resume after a restart, so the runner is kept alive from start
to the filed score), is in `../.pi/spec/decisions.md`, "The switch to the new harness", and the
builder command in `../.pi/harness/README.md`, "The builder command".
The whole loop over Löyly, step by step: `../README.md`, "The tutorial: one full loop".

## A build product

Nothing here is written by hand. A change goes through the spec (`../.pi/spec/`) and a new
cycle of that context, whose gated, scored candidate replaces the folder with a new
`PROVENANCE.md`. `harness/config.sh` fences `harness-impl` from every spec-v2 clean room, so no
builder reads these candidates.
