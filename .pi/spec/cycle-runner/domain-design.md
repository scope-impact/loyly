# Cycle runner: domain design

status: accepted, 2026-11-07 (spec v2; drafted in spec round 7a, `../decisions.md`, "Slice 06
(draft)"; accepted as slice 06 in spec round 7b, "Spec round 7b"; rewritten as the one tree
`cycle.v1` in spec round 18, "Spec round 18"). From the stage-1 triage (effects E1 to
E8), the legacy harness's operator steps (`archive/legacy-harness/README.md`, "A cycle") and the
prototype `proto/orc-harness`, in the glossary's words.

## Purpose

The five contexts record a cycle's facts and guard them; none of them acts. Every command of the
Regeneration cycle is "issued by ORC runtime from what one of its nodes did in a room"
(`../decisions.md`, "Slice 04" 4). The cycle runner is that issuer: since spec round 18 one ORC
tree, `cycle-runner/cycle.v1`, on one Grain store, whose leaves perform the effects (read the
repository at the cut, build and probe the clean room, run a builder session and wait for it,
install the candidate in a gate room and run the gate, grade each item with the judge, file the
score) and record each result into the context that owns it, by that context's command on the
one store. The six contexts are one candidate: there is no relay, hand-in method or
handoff contract, and each event is appended once, by its producer, and folded from the store by
every context that reads it.

The benchmark it runs is the full Löyly app as planned (the main working copy's `docs/plan`, today
01-booking-core, 11 items), with its own configuration, handed in: the archived Löyly settings of
`archive/legacy-harness/config.sh` (the HTTP seam on port 8901, the probe kept as-is, a name check,
for parity) and the fence `impl .pi archive/legacy-harness/cycles` (`../decisions.md`, "The switch
to the new harness" 2); the gate on the host. Since spec round 8 it also gates JSON-RPC services
over stdio, as `harness/v2/gate.sh` does (`APP_SEAM=stdio`).

## Boundaries

- **It performs effects and records; it owns no domain rule the five contexts own.** Whether a cut
  may be pinned, a clean room is proved, an attempt is spent, a cycle is exhausted, a verdict is
  filed or a cycle passes is each context's `requires` and policy. The runner records what it did
  and shows what the context replied (`RefusalsAreTheContexts`, `OwnsNoContextRule`); its run ends
  as its cycle ends (`ConcludeRunPassed`, `ConcludeRunFailed`).
- **Its own rules guard only its own facts**: one run at a time, a cycle run once, an effect
  result recorded once at its position, no builder once the score stage began, a run passed only
  with its score filed.
- **The runner itself judges no item.** For each item the sealed grader, the versioned judge
  workflow `cycle-runner/grade.v1`, drives the gated candidate over its seam with a researcher and
  scores it with the judge, the reason before the score. The score leaf files the
  judge's scores as scored; an item with no score is Measurement's to file as an unreached fail.
  The grader's digest must be one the run approved, or the run halts before the cycle.
- **One builder per run at a time**: a pi-durable session in a Gondolin VM, run by a code leaf
  through the provided builder host, its identity derived from the run's tick, so a resumed run
  reopens it. The builder's exit is not evidence; the gate is.
- **The gate runs on the host in stage 1**, on a copy of the candidate taken when its builder
  ended: for the `http` seam in legacy `verify.sh`'s order; for the `stdio` seam in
  `harness/v2/gate.sh`'s order, in a copy of the clean room, the event-store check handed in
  (`LOYLY_STORE_CHECK_CMD`) and never restated here; or by a gate command handed in.

## Files

| Module | Event Model | Allium | Aggregate | Lives |
|---|---|---|---|---|
| Context | `event-model/area.edn` (screen `run-desk`; the flow `cycle.v1`) | `allium/context.allium` (RunBudget, contracts `OneStore`, `ProjectRepository`, `BuilderSession`, `GateOnTheHost`, `TheJudge`, enums, config, the decided questions) | none | |
| Cycle run | `event-model/cycle-run.edn` | `allium/cycle-run.allium` (rules `StartRun`, `RecordEffect`, `ConcludeWithTheCycle`, `ConcludeRunPassed`, `ConcludeRunFailed`, `HaltRun`; surfaces `RunDesk`, `EffectNodes`) | **CycleRun** (with EffectResult) | the one store; one per run |

Actors: the **operator** starts a run (`RunDesk`); the leaves of `cycle.v1` and of the workflows it
delegates to (ORC's worker) issue the rest (`EffectNodes`).

## Design decisions

1. **One aggregate, one generic effect fact.** `effect-recorded` names the effect, the attempt,
   the context, the command and the context's reply; the order of the effects is the legacy
   cycle's and lives in the tree (`seam.md`, "The tree"), not in a `requires`. The effect
   results' positions make "recorded once" checkable.
2. **Only the operator's command is a method.** The leaves' commands are the runner's own; a
   grader observes what it did through `run-progress` (`seam.md`).
3. **A synchronous start.** `start-run` responds when the run concluded or halted, its grading
   and score included (Q4). The seam replies to other requests meanwhile, so a run in progress is
   read through `run-progress`, its tick among it.
4. **The conclusion is read, not decided.** A leaf reads the Regeneration cycle's
   `cycle-progress` after each recording that may conclude it; a concluded cycle concludes the
   run with that progress.
5. **A halt is the runner's own fact**: an effect it could not perform (a handed-in command that
   could not start, a model node that failed), or a refusal before the cycle opened (stamp, pin,
   checklist, a sealed grader not approved), where no cycle exists to fail.
6. **Everything the runner acts on is handed in** (`seam.md`, "Handed in"), so a grader drives
   it black-box with a fixture repository, a stub self-check, a stub builder host and a stub
   model.
7. **Resume, not restart.** A stopped run resumes where it stopped when the runner starts again
   on its store (ORC's `resume-in-progress!`); a run is never started again to recover it.

## The effects, by triage

| Triage | Effects | Recorded through |
|---|---|---|
| E1 stamp, pin | `stamp`, `pin`, `checklist` | `specification/stamp-slice`, `cycle-preparation/pin-source-cut`, `measurement/derive-checklist` |
| (03) grader | none: the sealed grader's digest checked against the run's approved digests | |
| E2 self-check | `self-check` (before the fence) | `regeneration-cycle/record-harness-self-check` |
| E3 fence | `fence`, `fail-cycle` | `regeneration-cycle/prove-clean-room`, `fail-cycle` |
| render | none: the `llm` node writes the builder instruction | |
| E4 regenerate | `regenerate`, `fail-cycle` | `regeneration-cycle/record-regenerated-candidate`, `fail-cycle` |
| E5 install, gate | `install`, `self-check` (before the gate), `gate`, `gate-refusal` | `install-candidate-in-gate-room`, `record-harness-self-check`, `record-gate-attempt`, `record-gate-refusal` |
| E6 repair (at most 3 attempts) | `repair` | `record-regenerated-candidate` (`verification-repair`) |
| grade | none: the judge's scores are ORC's events | |
| E7 file | `file-verdicts`, `file-score`, `fail-score-stage` | `measurement/file-item-verdicts`, `file-score`, `fail-score-stage` |
| conclude, halt | `conclude-run`, `halt-run` (the runner's own) | |

## Refusals (the `requires`, numbered as in the Allium)

- `StartRun`: 1 run identity taken, 2 another run in progress, 3 the cycle was run before (these
  three unit level: guards of a creation), 4 not a commit of the project repository, 5 the
  configured builder image not in the builder images directory.
- `RecordEffect`, `ConcludeRunPassed` 1 (unit level: own status); `ConcludeWithTheCycle`,
  `ConcludeRunFailed`, `HaltRun` 1: a run that ended stays as it ended; `ConcludeRunPassed` 2 and
  `ConcludeRunFailed` 2: the run ends as the cycle did.

## Accepted (spec rounds 7b to 7d)

`spec.feature`: all 34 drafts of round 7a, and one added for Q1, stamped `@item-06-001` to
`@item-06-035` (`.pi/plan/06-cycle-runner`): 21 minimum, 14 balance. Three were reworded so each
step is observable through the seam or carries a decision (`../decisions.md`, "Spec round 7b" 1).
Spec round 7c appended `@item-06-036`, spec round 7d `@item-06-037` and `@item-06-038`, and spec
round 8 `@item-06-039` to `@item-06-048` (the stdio gate and the plan's slices), and spec round 9
`@item-06-049` to `@item-06-051` (the builder image, its refusal, Measurement's history), spec
round 11 `@item-06-052` (the http probe in the gated tree), and spec round 12 `@item-06-053` to
`@item-06-057` (the gate command), spec round 13 `@item-06-058` (the context start deadline), and spec
round 14 `@item-06-059` (each effect its own leaf, reworded in spec round 16 for the delegated attempt), spec round 15 `@item-06-060` (the boot polls start at once; reworded in spec round 16b to count polls, not seconds), and spec round 16 `@item-06-061` to `@item-06-063` (the delegated gate attempt, the build's own event model, a context's reply bound), all minimum: 63 items. `drafts.feature` holds none. Of the 56 must-cover obligations
(`tools/spec/v2/coverage.py`), 7 are unit level and 4 gate-verified (`TheGateRoomIsNotTheBuilders`, planned `room-policy`; `TheBlackboardIsTheRunsState` and `EveryWaitHasABound`, planned `leaf-contract`; `TheCandidateHoldsOnlyItsBuild`, planned `candidate-contents`).

## Decided in spec round 18

`../decisions.md`, "Spec round 18": the cycle runner is one tree,
`cycle-runner/cycle.v1`, on one store, its leaves the commands of 01 to 05; the relay
(`RelayHandoff`, `EachHandoffOncePerConsumer`, `InTheProducersOrder`), the verdicts hand-in
(`HandInVerdicts`, `RecordEffectAfterTheVerdicts`) and the five contexts as programs
(`ContextSeams`'s `contexts_respond`, `AReplyWithinItsBound`, `context_start_seconds`,
`context_reply_seconds`) are gone; the builder is a pi-durable session whose identity comes from
the tick (`BuilderSession`); the builder instruction is rendered by an `llm` node; each item is
graded by the sealed grader `cycle-runner/grade.v1` (`TheJudge`); a stopped run resumes
(`AStoppedRunResumes`). Removed with their stamps: `@item-06-006`, `007`, `025`, `027` to `033`,
`058`, `063`. Added: `@item-06-064` to `@item-06-073`. Reworded, keeping their stamps: the rest
whose steps named the relay, the hand-in or a run awaiting its verdicts. 61 items; of the 48
must-cover obligations, 8 are unit level and 5 gate-verified (`AModelOtherThanTheBuildersOwn`,
planned `model-independence`, added).
Then ("Spec round 18" 12) `@item-06-067` also covers a judge reply with a level and no reasoning:
it scores nothing. Still 61 items.

## Decided questions (spec round 7b)

- **Q1, the stamped plan against a fresh ledger: restamp a stripped copy.** The runner stamps a
  copy of each plan spec file with its item tags removed; the identities the stamp hands out
  must equal the file's own tags, in file order, else the run halts (`refused-before-the-cycle`).
  Specification is unchanged. A plan whose numbering has a gap halts too; the Löyly plan's
  `@item-01-001` to `011` has none.
- **Q2 and Q5, the benchmark's fence and clean room: its own configuration.** The archived Löyly
  configuration with `FENCED_PATHS="impl .pi archive/legacy-harness/cycles"`: the paths that
  hold a prior candidate or evidence; `docs/`, `raw/` and the harness tools stay in the room.
  The archived value, `impl harness/cycles`, stops at the fence today
  (`fenced-path-matched-nothing`), because the main working copy has no `harness/cycles`. The
  configuration is handed in (`LOYLY_HARNESS_CONFIG`); the spec holds only its reading. Round 7b
  also listed `harness-impl`, left open because no branch tracks it; the switch dropped it
  (`../decisions.md`, "Spec round 7b" 3 and "The switch to the new harness" 2).
- **Q3, the harness digest**: the SHA-256 of the configuration file, the self-check command and
  the identity probe, in that order, as drafted; for the `stdio` seam the store-check command
  follows (spec round 8).

## Decided in spec round 16

D1 to D9 of `../decisions.md`, "Spec round 16": the trees follow ORC's principles. The blackboard
is the run's state, with its keys and each leaf's `:reads` and `:writes` (`seam.md`, "The
trees"); each leaf is a function of its own; a gate attempt is the subtree
`cycle-runner/gate-attempt.v1`, delegated once per attempt (`EachGateAttemptOneDelegatedTick`,
`@item-06-061`); the stamps are a `map-each` over one primitive leaf, relayed once after;
every wait has a bound (`AReplyWithinItsBound`, `@item-06-063`; `EveryWaitHasABound`); the
build carries its own event models and only its build (`seam.md`, "The build";
`CarriesItsOwnEventModel`, `@item-06-062`; `TheCandidateHoldsOnlyItsBuild`); the store check
follows a delegated child tick.

## Decided in spec round 14

D1 to D3 of `../decisions.md`, "Spec round 14": each flow is an ORC tree whose leaves are its
effects and commands, each leaf completing with its outcome on the blackboard and the tree
branching on it (`seam.md`, "The trees"); the processor `advance-run`, its to-do list
`runs-to-advance` and that query are gone; the gate's store check holds each event to its
leaf (`EachEffectInItsOwnLeaf`, `@item-06-059`).

## Decided in spec round 12

D1 to D5 of `../decisions.md`, "Spec round 12": a fence plan is configuration (`FENCED_PATHS`,
`APP_DIR`, its digest a line of the configuration), no runner change; the gate handed to a
provided command, `LOYLY_GATE_CMD <gate-room> <candidate> <evidence-dir>`, when one is handed in,
its result read from its exit status and its last line; a gate stopped by a refusal guard
recorded as a failed outcome so the context refuses it with the guard's code, and no fourth try
(round 10's draft D2); the run concluded with its cycle before a method is checked (round 10's
draft D5); the harness digest covers the gate command.

## Decided in spec round 8

D1 to D8 of `../decisions.md`, "Spec round 8": the gate's contract chosen by `APP_SEAM` (absent,
`http`); the store check handed in; a failed store check recorded as `identity-probe-failed`;
the stdio budget 30 seconds, one poll; a plan slice that is a symbolic link read at its target
and `PLAN_SLICES` selecting slices; the stdio gate room a copy of the clean room; the builder
instruction handed in; slice 05 the first self-hosted context.
- **Q4, synchronous methods**, as drafted; the judge watches a long run through the Grain store
  and the pi session directly.
- **Q6, the scenario digest**: the runner's reading in `seam.md`, as drafted; Specification names
  none.
