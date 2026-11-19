# harness-impl: the promoted build of spec v2

The accepted builds of the six spec-v2 contexts (`../.pi/spec/`) are recorded here. Contexts
01–05 were promoted in the first switch; context 06 was freshly regenerated and promoted
from cycle 26 on 2026-11-18. Each passed its independent gate and was scored.

| Folder | Context | Cycle | Score |
|---|---|---|---|
| `01-specification/` | specification | 02 | 15/15 |
| `02-measurement/` | measurement | 05 | 43/43 |
| `03-grader-lifecycle/` | grader-lifecycle | 05 | 11/11 |
| `04-regeneration-cycle/` | regeneration-cycle | 05 | 76/76 |
| `05-cycle-preparation/` | cycle-preparation | selfhost-01 (runner c05), the first self-hosted cycle | 4/4 |
| `06-cycle-runner/` | cycle-runner | 26, fresh regeneration | 62/62 |

Each folder's `PROVENANCE.md` names the room it came from, its gate log and verdict, its score
and cycle commit, the spec commit it was built from, and the digest of its source, which equals
the digest computed in the room.

The cycle-26 runner carries all 61 items shared with cycle 25, plus new Allure item 06-074.
Its filed profile is clean after two preserved fresh retries; see its provenance. The five
other accepted contexts were retained as built.

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
`LOYLY_SCORING_TOOL_PATHS`); their seams say which. The cycle-26 runner carries the six
contexts' event-model fragments in its build. Build outputs and caches
(`.cpcache/`, `target/`, `classes/`) are made on first start and ignored (`.gitignore`).

## Driving the cycle runner

`06-cycle-runner/` performs the whole cycle as one ORC tree on one Grain store. It includes the
event models of contexts 01–05 and receives the store, runs directory, project repository,
harness configuration, self-check, builder command and instruction, and grader image as its
seam specifies (`../.pi/spec/cycle-runner/seam.md`, "Handed in"). Its acceptance evidence and
known failures are in `06-cycle-runner/PROVENANCE.md`.

## A build product

Nothing here is written by hand. A change goes through the spec (`../.pi/spec/`) and a new
cycle of that context, whose gated, scored candidate replaces the folder with a new
`PROVENANCE.md`. `harness/config.sh` fences `harness-impl` from every spec-v2 clean room, so no
builder reads these candidates.
