# Cycle runner: domain design

status: DRAFT, 2026-11-07 (spec v2, spec round 7a; `../decisions.md`, "Slice 06 (draft)"). For
the product owner's review before any scenario is accepted. From the stage-1 triage (effects E1 to
E8), the legacy harness's operator steps (`archive/legacy-harness/README.md`, "A cycle") and the
relay of the first assembly gate (`harness/v2/assemble.clj`), in the glossary's words.

## Purpose

The five contexts record a cycle's facts and guard them; none of them acts. Every command of the
Regeneration cycle is "issued by ORC runtime from what one of its nodes did in a room"
(`../decisions.md`, "Slice 04" 4), and until now a grader or a person played that issuer. The cycle
runner is that issuer: the ORC workflow whose nodes perform the effects (read the repository at the
cut, build and probe the clean room, launch a builder and wait for it, install the candidate in a
gate room and run the gate on the host, file the verdicts it is handed) and record each result into
the context that owns it, through that context's seam. It is also the relay: no store is shared
(A1 deferred, Spec round 5), so it takes each producer's handoffs from the producer's store and
hands them in to the consumers, as `harness/v2/assemble.clj` does.

The benchmark it runs is the full Löyly app as planned (the main working copy's `docs/plan`, today
01-booking-core, 11 items), with the legacy settings of `archive/legacy-harness/config.sh`: the
HTTP seam on port 8901, the probe kept as-is (a name check, for parity), the gate on the host.

## Boundaries

- **It performs effects and records; it owns no domain rule the five contexts own.** Whether a cut
  may be pinned, a clean room is proved, an attempt is spent, a cycle is exhausted, a verdict is
  filed or a cycle passes is each context's `requires` and policy. The runner records what it did
  and shows what the context replied (`RefusalsAreTheContexts`, `OwnsNoContextRule`); its run ends
  as its cycle ends (`ConcludeRunPassed`, `ConcludeRunFailed`).
- **Its own rules guard only its own facts**: one run at a time, a cycle run once, the verdicts
  taken once and only for a gated candidate at an approved grader digest, an effect result recorded
  once at its position, a handoff relayed once per consumer, no builder after the verdicts.
- **It never grades.** The verdicts file is handed in: one legacy-step-5 grading subagent per
  candidate writes it, and the main session hands in, with the others, the verdict of an item it
  judges itself (`@item-01-011` of the Löyly app). The same file goes to legacy `score.clj` in a
  shadow run. The sealed Löyly grader comes after the switch (OG1 = no).
- **One builder per run at a time**: pi with its Gondolin backend, launched by the runner, which
  waits for it. The builder's exit is not evidence; the gate is.
- **The gate runs on the host in stage 1**, in legacy `verify.sh`'s order, on a copy of the
  candidate taken when its builder ended.

## Files

| Module | Event Model | Allium | Aggregate | Lives |
|---|---|---|---|---|
| Context | `event-model/area.edn` (screen `run-desk`; four flows) | `allium/context.allium` (RunBudget, contracts `ContextSeams`, `ProjectRepository`, `BuilderLaunch`, `GateOnTheHost`, enums, config, open questions) | none | |
| Cycle run | `event-model/cycle-run.edn` | `allium/cycle-run.allium` (rules `StartRun`, `RecordEffect`, `RecordEffectAfterTheVerdicts`, `RelayHandoff`, `HandInVerdicts`, `ConcludeWithTheCycle`, `ConcludeRunPassed`, `ConcludeRunFailed`, `HaltRun`; surfaces `RunDesk`, `EffectNodes`) | **CycleRun** (with EffectResult, RelayedHandoff) | the runner's own event store; one per run |
| Handoffs | none | | | the five contexts' handoffs are relayed, never held |

Actors: the **operator** starts a run and hands in its verdicts (`RunDesk`); the runner's
**effect nodes** (ORC's worker) issue the rest (`EffectNodes`).

## Design decisions (draft)

1. **One aggregate, one generic effect fact.** `effect-recorded` names the effect, the attempt,
   the context, the method and the context's reply; the order of the effects is the legacy cycle's
   and lives in the processor `advance-run`, not in a `requires` (an internal command's refusal is
   unreachable from the seam). The effect results' positions make "recorded once" checkable.
2. **Only the operator's commands are methods.** The processor's commands are the runner's own;
   a grader observes what it did through `run-progress` (`seam.md`). This differs from the five
   contexts, whose grader plays the issuer.
3. **Synchronous methods.** `start-run` responds when the run awaits its verdicts, concluded or
   halted; `hand-in-verdicts` when it concluded or halted (open question Q4).
4. **The conclusion is read, not decided.** After every recording and relay the runner reads the
   Regeneration cycle's `cycle-progress`; a concluded cycle concludes the run with that progress.
5. **A halt is the runner's own fact**: an effect it could not perform (a context that did not
   respond, a handed-in command that could not start), or a recording refused before the cycle
   opened (stamp, pin, checklist), where no cycle exists to fail.
6. **Everything the runner acts on is handed in** (`seam.md`, "Handed in"), so a grader drives
   it black-box with a fixture repository, a stub self-check, a stub builder and fixture
   candidates; only the five contexts are the real gated candidates, since the relay reads their
   Grain stores.

## The effects, by triage

| Triage | Effects | Recorded through |
|---|---|---|
| E1 stamp, pin | `stamp`, `pin`, `checklist` | `specification/stamp-slice`, `cycle-preparation/pin-source-cut`, `measurement/derive-checklist` |
| E2 self-check | `self-check` (before the fence) | `regeneration-cycle/record-harness-self-check` |
| E3 fence | `fence`, `fail-cycle` | `regeneration-cycle/prove-clean-room`, `fail-cycle` |
| E4 regenerate | `regenerate`, `fail-cycle` | `regeneration-cycle/record-regenerated-candidate`, `fail-cycle` |
| E5 install, gate | `install`, `self-check` (before the gate), `gate`, `gate-refusal` | `install-candidate-in-gate-room`, `record-harness-self-check`, `record-gate-attempt`, `record-gate-refusal` |
| E6 repair (at most 3 attempts) | `repair` | `record-regenerated-candidate` (`verification-repair`) |
| E7 grade, file | `file-verdicts`, `file-score`, `fail-score-stage` | `measurement/file-item-verdicts`, `file-score`, `fail-score-stage` |
| E8 relay, conclude | `relay-handoff`, `conclude-run` (the runner's own) | every consumer's hand-in methods |

## Refusals (the `requires`, numbered as in the Allium)

- `StartRun`: 1 run identity taken, 2 another run in progress, 3 the cycle was run before (these
  three unit level: guards of a creation), 4 not a commit of the project repository, 5 a context
  not handed in or not responding.
- `HandInVerdicts`: 1 no such run, 2 the gate has not passed, 3 verdicts already handed in, 4 the
  run no longer awaits its verdicts (unit level), 5 grader digest not approved, 6 the file absent,
  blank or not a list of verdicts.
- `RecordEffect`, `RecordEffectAfterTheVerdicts`, `ConcludeRunPassed` 1 (unit level: own status);
  `RelayHandoff`, `ConcludeWithTheCycle`, `ConcludeRunFailed`, `HaltRun` 1: a run that ended stays
  as it ended; `ConcludeRunPassed` 2 and `ConcludeRunFailed` 2: the run ends as the cycle did.

## Drafts (round 7a)

`drafts.feature`: 34 drafts for the 40 must-cover obligations (`tools/spec/v2/coverage.py`), 0
uncovered; 7 unit level, 1 gate-verified (`TheGateRoomIsNotTheBuilders`, planned `room-policy`).
Suggested tiers: 20 minimum (19 cite legacy behaviour; the not-a-commit refusal is new), 14 balance. Grouped by effect: start
(6), stamp, pin, checklist and relay (2), self-check (2), fence (2), regenerate (4), gate (5),
repair (2), verdicts and conclusion (8), halt and observation (3).

## Open questions

- **Q1 (blocks the benchmark)**: the Löyly plan is already stamped (`@item-01-001` to `011`), and
  Specification's ledger starts empty in every run (each context on a fresh store), so no
  `scenario-stamped` names those items and Measurement refuses the checklist (`unknown-item`).
  Draft reading in `seam.md`: the runner stamps a copy of each spec file with its item tags
  removed, so the ledger hands out the same identities in file order. Alternatives: a persistent
  Specification store per project, or an import command in Specification (a spec change there).
- **Q2 (blocks the benchmark)**: legacy `FENCED_PATHS="impl harness/cycles"`, but the main
  working copy has no `harness/cycles` (only `impl/README.md` is tracked), so the fence of today's cut
  refuses `fenced-path-matched-nothing` and every run fails at the fence. Keep the legacy value,
  or declare the benchmark's own configuration?
- **Q3**: the harness digest of a self-check; draft: the SHA-256 of the configuration file, the
  self-check command and the identity probe, in that order.
- **Q4**: synchronous methods mean nothing can be read through the seam during a live builder's
  hours. Keep, or respond at once and advance in the background (a grader then polls)?
- **Q5**: the clean room of the Löyly cut holds this repository's `.pi/`, `harness/` and
  `harness-impl/` as well as `docs/plan`; legacy fenced only `impl` and `harness/cycles`. Is that
  the room the product owner wants the builder to see, for parity?
- **Q6**: the scenario digest formula (`seam.md`) is the runner's reading; Specification's
  `ScenarioReading` names none. Confirm, or name it in Specification.
