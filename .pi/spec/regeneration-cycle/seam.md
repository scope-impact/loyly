# Regeneration cycle: the public seam

How a gate, a grader and an operator drive this context's candidate (slice 04). Derived from
`event-model/*.edn` and the handoff contracts it consumes; where this note and the event model
disagree, the event model wins and this note is wrong. Decided 2026-11-07 (`../decisions.md`,
"Slice 04"), in the shape of `../specification/seam.md` ("First light"),
`../measurement/seam.md` ("Slice 02") and `../grader-lifecycle/seam.md` ("Slice 03").

## What the candidate does, and what it is told

The candidate records the stage facts of a cycle and guards them: it refuses what the Allium
`requires` refuse, folds the facts into its read models, runs the conclude-cycle policy and the
stage clock, and serves the operator's view. It acts on nothing outside its store: it builds no
clean room, runs no `git`, runs no harness self-check, boots no candidate and starts no builder.
Every command is issued by ORC runtime from what one of its nodes did in a room (the event
model's "Issued by"), and its params carry that node's result: the self-check run's outcome, its
failing check, skipped checks, missing and reworded goldens (contract `HarnessSelfCheckRun`); the
fence probe's commit counts and the paths it found (contract `FenceProbe`); the gate room's
result, its boot budget and the poll that answered, whether the seam answered before boot and
the identity probe that was there (contract `GateRoom`). A grader plays ORC runtime: it offers
those results as params, and the candidate records what it is offered, never re-deriving it
(`../decisions.md`, "Slice 04" 4).

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
optional field may be left out. Values that are instants travel as ISO-8601 UTC text. A
command's result is `{"events": [...]}`: the events that command produced, in the order
produced, each `{"type": "<area>/<event>", ...}` with exactly the fields of that event's
`:schema`.

| Method | Block | Result |
|---|---|---|
| `regeneration-cycle/record-harness-self-check` | command `:regeneration-cycle/record-harness-self-check` | `regeneration-cycle/harness-self-check-passed` or `regeneration-cycle/harness-self-check-failed` |
| `regeneration-cycle/prove-clean-room` | command `:regeneration-cycle/prove-clean-room` | `regeneration-cycle/clean-room-proved` |
| `regeneration-cycle/record-regenerated-candidate` | command `:regeneration-cycle/record-regenerated-candidate` | `regeneration-cycle/candidate-regenerated` |
| `regeneration-cycle/install-candidate-in-gate-room` | command `:regeneration-cycle/install-candidate-in-gate-room` | `regeneration-cycle/candidate-installed-in-gate-room` |
| `regeneration-cycle/record-gate-attempt` | command `:regeneration-cycle/record-gate-attempt` | `regeneration-cycle/gate-attempt-passed` or `regeneration-cycle/gate-attempt-failed` |
| `regeneration-cycle/record-gate-refusal` | command `:regeneration-cycle/record-gate-refusal` | `regeneration-cycle/gate-attempt-refused` |
| `regeneration-cycle/pass-cycle` | command `:regeneration-cycle/pass-cycle` | `regeneration-cycle/cycle-passed` |
| `regeneration-cycle/fail-cycle` | command `:regeneration-cycle/fail-cycle` | `regeneration-cycle/cycle-failed` |
| `regeneration-cycle/cycle-progress` | query `:regeneration-cycle/cycle-progress` (`cycle-id`, optional) | with `cycle-id`: `{"cycle": ...}`, that cycle's progress, or `null` when no such cycle exists; without: `{"cycles": [...]}`, every cycle's, in the order the cycles opened |
| `regeneration-cycle/cycles-to-conclude` | query `:regeneration-cycle/cycles-to-conclude` (no params) | `{"cycle-ids": [...]}`: the running cycles the conclude-cycle policy has yet to conclude, in the order they became due; `[]` when none |

- **A cycle's progress** is the read model `:regeneration-cycle/regeneration-cycles` of that
  cycle, its fields verbatim (`cycle-id`, `cut-id`, `status`, `stage`, `stage-entered-at`,
  `consecutive-gate-refusals`, `budget`, `harness-digest`, `clean-room-commit`,
  `candidate-digest`, `gate-attempts`, `failure`), plus `attempts`: the read model
  `:regeneration-cycle/gate-attempts` of that cycle, one row per attempt regenerated, in
  attempt order, its fields verbatim (`cycle-id`, `attempt`, `candidate-digest`, `installed?`,
  `verdict`, `reason`, `boot-margin-polls`, `evidence-root`, `last-refusal`). An optional field
  with no value is left out. `status` is `running`, `passed` or `failed`; `stage` is the stage
  the cycle reached (`result` once passed); `gate-attempts` is the number of candidates
  regenerated (the Allium's `attempts`); `budget` is the budget its first self-check carried,
  as offered; `failure` is `{"stage", "reason", "detail"}` of its `cycle-failed`;
  `boot-margin-polls` is `boot-polls` less `answered-on-poll` of a passed attempt.
- **No score is not all failed.** This context holds no item verdict, and the view shows none.
  A failed cycle shows its failure (stage, reason, detail) and nothing else of a score: it never
  had a measurement (invariant `FailedBeforeScoreHasNoMeasurement`; `FailCycle` never fails a
  cycle at the scoreboard). A grader reads "no score" as `status` `failed`.
- **Reading changes nothing.** A query records no event and runs no ORC workflow.
- **The harness self-checks have no query**: a self-check is read in its command's result, and
  a refused one records nothing (below).

## The goldens digest

`RecordHarnessSelfCheck` (requires 2) and `RecordGateAttempt` (requires 7) compare a goldens
digest with `goldens_digest_of(CurrentGoldens)`, the function of the contract
`HarnessSelfCheckRun` the surface `HarnessSelfCheckRecording` fulfils. The candidate computes
it from the current instrument goldens its read model `:regeneration-cycle/instrument-goldens`
folds from the handed-in golden events (below):

- `goldens_digest_of` is `"sha256:"` followed by the lowercase hexadecimal SHA-256 of the UTF-8
  text made of one line per current golden, `<golden-id>` TAB `<digest>` LF, the goldens ordered
  by `golden-id` (by code point). A golden's digest is the `digest` it was written with, or the
  `to-digest` of its last move. With no golden the text is empty, and the digest is
  `sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.
- Example: goldens `checklist` with digest `g-1` and `scoreboard-01` with digest `g-2` give the
  SHA-256 of `"checklist\tg-1\nscoreboard-01\tg-2\n"`.
- A golden moved after a self-check makes that self-check's goldens no longer current: a gate
  attempt over it is refused (requires 7), and a new self-check needs the new digest.

## The scoring tools, handed in

`ProveCleanRoom` (requires 9) requires every path of `config.scoring_tool_paths` to be fenced;
"each repository declares its own paths". The event model has no place for it, so it is handed
in, like the store:

- `LOYLY_SCORING_TOOL_PATHS`: a JSON array of the scoring tools' paths, the text of the
  variable itself, read when the candidate starts: `["harness/score.clj", "harness/scoreboard.clj"]`.
  Unset or empty, there is no scoring tool, and requires 9 always holds.

## The consumed handoffs, handed in

The Regeneration cycle consumes six events other contexts produce. In a cycle of this context
alone those contexts are stubbed from their contracts, each producer's
`event-model/handoffs.edn` (the only file of each the room holds), and their facts reach the
candidate through this seam: one method per consumed event, named exactly as the event, its
params exactly the event's contract `:schema`.

| Method | Contract | The candidate's read models that fold it |
|---|---|---|
| `cycle-preparation/source-cut-pinned` | `../cycle-preparation/event-model/handoffs.edn` | `:regeneration-cycle/pinned-cuts` |
| `grader-lifecycle/instrument-golden-written` | `../grader-lifecycle/event-model/handoffs.edn` | `:regeneration-cycle/instrument-goldens` |
| `grader-lifecycle/instrument-golden-moved` | `../grader-lifecycle/event-model/handoffs.edn` | `:regeneration-cycle/instrument-goldens` |
| `measurement/measurement-filed` | `../measurement/event-model/handoffs.edn` | `:regeneration-cycle/regeneration-cycles`, `:regeneration-cycle/cycles-to-conclude` |
| `measurement/score-stage-failed` | `../measurement/event-model/handoffs.edn` | `:regeneration-cycle/regeneration-cycles`, `:regeneration-cycle/cycles-to-conclude` |
| `measurement/scoreboard-reported` | `../measurement/event-model/handoffs.edn` | `:regeneration-cycle/regeneration-cycles`, `:regeneration-cycle/cycles-to-conclude` |

The candidate appends the event, exactly as given (its type and its fields, nothing added),
to its Grain store as the producer would, and responds `{"events": [<that event>]}`. It runs no
ORC workflow for it: the event is the producer's fact, not a command of this context. Params
that do not match the contract are `-32602` and append nothing; nothing else refuses a
handed-in event. A fold ignores an event its rule does not take (`NotePinnedCut` and
`NoteGoldenWritten`, requires 1: one already held; `NoteGoldenMoved`, requires 1: a golden never
written; `NoteMeasurementFiled`, `NoteScoreStageFailed`, `NoteScoreboardReported`, requires 1: a
cycle that does not exist; `NoteMeasurementFiled` and `NoteScoreStageFailed`, requires 2: a
cycle not running at the score stage, so a measurement filed after the cycle failed leaves it
where it failed; `NoteScoreboardReported`, requires 2: a cycle not running or with no
measurement filed, so a scoreboard with no measurement filed passes nothing). A cycle is due
to pass only with its measurement filed and its scoreboard reported (`awaits_passing`). A
handed-in `measurement/score-stage-failed` or
`measurement/scoreboard-reported` may make a cycle due to conclude: the policy then runs before
the method responds (below). These methods stand in for the producers until a gate assembles
the contexts (`../decisions.md`, "First light" 5); they are the only way another context's event
enters the store.

## The conclude-cycle policy and the stage clock

- **The policy runs first.** When an event the policy `conclude-cycle` subscribes to is recorded
  (`harness-self-check-failed`, `gate-attempt-failed`, `gate-attempt-refused`, or a handed-in
  `measurement/score-stage-failed` or `measurement/scoreboard-reported`) and it makes a running
  cycle due (`ConcludeOnFailedSelfCheck`, `ConcludeOnExhaustedGateAttempts`,
  `ConcludeOnRepeatedGateRefusals`, `ConcludeOnFailedScoreStage`,
  `ConcludeOnReportedScoreboard`), the policy issues `fail-cycle` or `pass-cycle` for it, and that
  conclusion is recorded before the method that recorded the event responds. Its events are not
  in that method's result; `regeneration-cycle/cycle-progress` reads them. The policy's command
  is held to the same `requires` as the method of the same name: refused, it records nothing,
  and the method that triggered it still responds with its own events.
- **The stage clock runs on the wall clock**, at the event model's schedule (the periodic task
  `stage-clock`, every 30 seconds). A running cycle's stage started when the candidate recorded
  the fact that moved it there (`stage-entered-at`); when the stage is `fence`, `regenerate`,
  `gate` or `score` and its timeout in the budget (`stage-timeouts-seconds`) has run out, the
  clock fails the cycle at that stage with reason `budget` (`ConcludeOnStageTimeout`). A grader
  observes it through `regeneration-cycle/cycle-progress` once the timeout and one period have
  passed; no accepted scenario of this slice waits for it. The identity probe offers budgets
  whose timeouts are far longer than its run.

## Refusals

A refusal records nothing and is the error `-32000` with `data.refusal`, one of the codes
below; where it names paths, `data.paths` lists them, and where it names goldens,
`data.goldens`; its message names them too. The numbers are the `requires` of the rule, in its
Allium file.

| Method | Allium rule | `data.refusal` by requires |
|---|---|---|
| `regeneration-cycle/record-harness-self-check` | `RecordHarnessSelfCheck` (`allium/harness-self-check.allium`) | 1 `cut-not-pinned`, 2 `goldens-not-current`, 3 `self-check-already-recorded`, 4 `no-failing-check`, 5 `not-the-cycles-first-fact`, 6 `no-candidate-for-attempt`, 7 `budget-out-of-bounds`, 8 `missing-golden` (`data.goldens`), 9 `reworded-golden` (`data.goldens`), 10 `reworded-golden-not-current` (`data.goldens`) |
| `regeneration-cycle/prove-clean-room` | `ProveCleanRoom` (`allium/clean-room.allium`) | 1 `self-check-not-passed`, 2 `fenced-path-matched-nothing` (`data.paths`), 3 `root-commits-not-one`, 4 `commits-not-one`, 5 `fenced-path-left-in-tree` (`data.paths`), 6 `fenced-path-reachable-in-object-graph` (`data.paths`), 7 `clean-room-already-proved`, 8 `not-the-self-checked-cut`, 9 `scoring-tool-not-fenced` (`data.paths`), 10 `cycle-concluded` |
| `regeneration-cycle/record-regenerated-candidate` | `RecordRegeneratedCandidate` (`allium/regeneration-cycle.allium`) | 1 `no-cycle`, 2 `no-clean-room`, 3 `not-the-next-attempt`, 4 `over-gate-attempt-limit`, 5 `wrong-purpose`, 6 `cycle-concluded`, 7 `not-after-a-failed-attempt`, 8 `not-the-previous-candidate`, 9 `evidence-not-mountable` |
| `regeneration-cycle/install-candidate-in-gate-room` | `InstallCandidateInGateRoom` (`allium/gate-attempt.allium`) | 1 `no-candidate-for-attempt`, 2 `not-the-regenerated-candidate`, 3 `already-installed`, 4 `cycle-concluded` |
| `regeneration-cycle/record-gate-attempt` | `RecordGateAttempt` | 1 `not-installed`, 2 `verdict-already-recorded`, 3 `reason-does-not-fit-outcome`, 4 `pass-without-poll-or-probe-result`, 5 `answered-after-last-poll`, 6 `not-the-installed-candidate`, 7 `no-passing-self-check`, 8 `identity-probe-missing`, 9 `identity-probe-not-the-instruments`, 10 `seam-answered-before-boot`, 11 `cycle-concluded`, 12 `other-grader-digests` |
| `regeneration-cycle/record-gate-refusal` | `RecordGateRefusal` | 1 `not-installed`, 2 `verdict-already-recorded`, 3 `cycle-concluded` |
| `regeneration-cycle/pass-cycle` | `PassCycle` (`allium/regeneration-cycle.allium`) | 1 `no-cycle`, 2 `scoreboard-not-reported`, 3 `cycle-concluded` |
| `regeneration-cycle/fail-cycle` | `FailCycle` | 1 `no-cycle`, 2 `cycle-concluded`, 3 `not-the-stage-reached`, 4 `reason-not-of-the-stage`, 5 `infrastructure-not-at-the-gate` |

The requires are checked in their Allium order and the first that fails is the refusal: a
clean room with an unmatched fenced path and two root commits is refused
`fenced-path-matched-nothing`. The codes 8, 9 and 10 of `record-gate-attempt` are the same text
as the values of `record-gate-refusal`'s `refusal`, so ORC runtime can record a refused verdict
as it heard it.

- **A fence with no fenced paths is a schema mismatch.** `fenced-paths` is `[:vector {:min 1}
  :string]` (invariant `AFenceHidesSomething`, which has no `requires`): an empty list is
  `-32602`, and nothing is recorded. Every other param that does not match its schema, a reason
  or a stage outside its enumeration among them, is `-32602` too.
- **"The stage the cycle reached"** (`FailCycle`, requires 3) is the `stage` of the cycle's
  progress.

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
  one event of `event-model/`; every event's type must be an event of `event-model/`, a
  consumed handoff above, or ORC's or Grain's own; and every such event's fields, other than
  Grain's own `:event/*` keys, must be exactly its `:schema` (a handoff's, its contract's).
- ORC runs on the same store: the same file and tenant, not a store of its own. Each
  command runs as the ORC workflow named after a flow of `event-model/area.edn` that holds its
  command, whoever issues it; a query method and a handed-in handoff run none.

  | Command, by its issuer | ORC workflow |
  |---|---|
  | `record-harness-self-check`, `prove-clean-room`, `record-regenerated-candidate`, `install-candidate-in-gate-room`, `record-gate-attempt` (the methods) | `regeneration-cycle/fence-to-scoreboard` |
  | `pass-cycle` (the method, and the policy on a reported scoreboard) | `regeneration-cycle/fence-to-scoreboard` |
  | `record-gate-refusal` (the method) | `regeneration-cycle/gate-refusals-fail-cycle` |
  | `fail-cycle` (the method: ORC runtime's fence or regenerate node) | `regeneration-cycle/fence-refused-fails-cycle` |
  | `fail-cycle` by the policy, on a failed self-check | `regeneration-cycle/self-check-failed-fails-cycle` |
  | `fail-cycle` by the policy, on exhausted gate attempts | `regeneration-cycle/gate-attempts-exhausted` |
  | `fail-cycle` by the policy, on three gate refusals in a row | `regeneration-cycle/gate-refusals-fail-cycle` |
  | `fail-cycle` by the policy, on a failed score stage | `regeneration-cycle/refused-score-fails-cycle` |
  | `fail-cycle` by the stage clock | `regeneration-cycle/stage-timeout-fails-cycle` |

  The gate requires every event a command produced to be written inside a run of a workflow
  named after a flow holding that command, which completed with success (between its
  `:sheet/tree-tick-started` and its `:sheet/tree-tick-completed`, `:root-status :success`).
- The candidate registers this context's event model, `event-model/*.edn` merged with the
  six consumed handoff events from their contracts, as given (Grain's `register-event-model!`),
  and refuses to start when Grain's boot guard (`verify-or-throw!`) finds the running blocks
  and the model apart.

## Not in this slice

The guarantees `TheClaimantNeverControlsTheGate` and `ProbeOutOfReach`, and Measurement's
`PublicSeamOnly`, are verified by the planned gate `room-policy` (`domain-design.md`, "Checks"),
which needs ORC's room policy: they are not the candidate's to build, and no accepted scenario
names them. The ORC nodes that act in rooms (the fence that builds and probes the clean room,
the self-check run, the gate that boots the candidate, the builder session) are not built here
either: they are the issuers of this context's commands (`../decisions.md`, "Slice 04" 4).
