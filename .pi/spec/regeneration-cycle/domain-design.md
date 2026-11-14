# Regeneration cycle (harness: fence and gate): domain design

status: accepted as slice 04 (R4.4, 2026-11-07); proposed 2026-11-06 (spec v2, R1 event model;
R2 Allium and the product owner's decisions, `../decisions.md`). From the storm
(`../event-storm.md`: Fence, Regenerate, Gate, Conclude) and the legacy `fence.sh`,
`verify.sh`, `probe.sh` and `check.sh`, in the glossary's words.

## Files

| Module | Event Model | Allium | Aggregate | Lives |
|---|---|---|---|---|
| Context | `event-model/area.edn` (screen `cycle-progress`; six flows) | `allium/context.allium` (Budget, enums, config, contracts `HarnessSelfCheckRun`, `FenceProbe`, `GateRoom`, open questions) | none | |
| Harness self-check | `event-model/harness-self-check.edn` | `allium/harness-self-check.allium` (rule `RecordHarnessSelfCheck`; the cut and golden events; surface `HarnessSelfCheckRecording`) | **HarnessSelfCheck** | event store; one per cycle, occasion and attempt |
| Clean room | `event-model/clean-room.edn` | `allium/clean-room.allium` (rule `ProveCleanRoom`; surface `Fence`) | **CleanRoom** | event store; one per cycle |
| Regeneration cycle | `event-model/regeneration-cycle.edn` | `allium/regeneration-cycle.allium` (rules `RecordRegeneratedCandidate`, `PassCycle`, `FailCycle`; the conclude-cycle policy; surfaces `CycleStages`, `CycleProgress`, `BuilderRoomBoundary`, `MeasurementEvents`) | **RegenerationCycle** (stage, budget, attempts, the one conclusion) | event store; one per cycle identity |
| Gate attempt | `event-model/gate-attempt.edn` | `allium/gate-attempt.allium` (rules `InstallCandidateInGateRoom`, `RecordGateAttempt`; surface `Gate`) | **GateAttempt** | event store; one per cycle and attempt |
| Events other contexts fold | `event-model/gate-attempt.edn` (`gate-attempt-passed`) | emitted by `RecordGateAttempt` | | folded by Measurement, from the one store (spec round 18) |

Actors: **ORC runtime** issues every command here except the two conclusions; the
**conclude-cycle** policy issues `pass-cycle` and `fail-cycle` (ORC also issues `fail-cycle`
for a refused fence or a regenerate step that produced nothing). The **operator** only
observes (`CycleProgress`). The **builder host** issues nothing; `BuilderRoomBoundary`
states what it can rely on and never reach.

## Design decisions

1. **ORC runs, the harness records.** ORC owns the execution order; each command records a
   stage fact from what a node did in a room, and the harness guards it with `requires`.
   No harness policy starts a stage: the only policy here concludes the cycle.
2. **The cycle identity is given, not minted, in the core** (H8). The self-check before
   the fence is the first fact naming a cycle, its cut and its budget; a cycle exists from
   that self-check, passed or failed, so a cycle failed at the fence exists and has no score.
3. **Four aggregates**, one Allium file each. The aggregates emit their own events; the
   cycle file hears them by qualified name (`sc/HarnessSelfCheckPassed`) and folds them
   into the cycle, as the `regeneration-cycles` read model does. The conclude-cycle policy
   is four rules on the cycle's derived to-do conditions (`awaits_failing_self_check`,
   `awaits_failing_gate`, `awaits_failing_score`, `awaits_passing`).
4. **Self-check failed is a fact, a fence refusal is not.** A fence probe that does not
   hold is a refused `ProveCleanRoom`; ORC's fence node then fails the cycle (`FailCycle`,
   stage fence, reason `fence_refused`).
5. **Two kinds of "no" at the gate** (changed by decision b). The guards the candidate can
   fix are reasons on `gate-attempt-failed` and spend the attempt: `contract-missing`,
   `suite-discovery-empty`, `suite-red`, `boot-budget-exhausted`, `identity-probe-failed`.
   The infrastructure guards are refusals of `record-gate-attempt`, record nothing and spend
   no attempt: no passing self-check for the attempt, the identity probe missing, the probe
   not the instrument's, the seam answered before boot. ORC then records the refusal as the
   event `gate-attempt-refused` (`RecordGateRefusal`), a fact about the room that lets three
   in a row be counted: the one exception to "refusals are `requires`, not events"
   (`../decisions.md`, "Calls after R3", 1).
6. **The boot budget and its margin are recorded** on both verdicts; the margin is the poll
   that answered (`GateAttempt.boot_margin_polls`).
7. **Gate attempts exhausted is a read-model fact**: `failed_attempts >= budget.gate_attempt_limit`.
8. **One conclusion per cycle**, `cycle-passed` after the scoreboard, `cycle-failed` at the
   stage the cycle reached (`FailCycle` refuses another stage, or a reason that does not
   belong to it).

## Decisions applied in R2 (`../decisions.md`)

| Decision | Applied as |
|---|---|
| H1 a recorded fact is the truth | `RecordGateAttempt` refusal 2: an attempt with a verdict records nothing; a replay spends no attempt |
| H5 budget | `Budget` value (gate-attempt limit 1..3, outer cycles, a timeout per stage, continuations 0..3) on the self-check before the fence and on the cycle; `RecordHarnessSelfCheck` refusal 7, `RecordRegeneratedCandidate` refusal 4, invariant `AttemptsWithinBudget` |
| H6 gate vs score evidence | `RecordRegeneratedCandidate` refusal 9: only a repair room mounts evidence, only this cycle's gate evidence roots; guarantees `NoScoreEvidenceInABuilderRoom`, `GateEvidenceOnly` |
| H8 cycle numbered at submission | a cycle exists from its first self-check, passed or failed; invariant `FailedBeforeScoreHasNoMeasurement`; guarantee `NoScoreIsNotAllFailed` |
| H24 self-check before the gate too | `occasion` before-fence / before-gate on the self-check; `RecordHarnessSelfCheck` refusals 5-6; `RecordGateAttempt` refusal 7 (a passing self-check for this attempt, this harness digest, the current goldens: never reused across a change) |
| H25 scoring tools fenced | `ProveCleanRoom` refusal 9 (config `scoring_tool_paths` all fenced); the self-check in the room lists them as loud skips |
| b seam / probe are infrastructure | `RecordGateAttempt` refusals 8 and 10; `seam-answered-before-boot` left the reason enum |
| Repair builder (accepted change) | `RecordRegeneratedCandidate` refusals 5, 7, 8: repair only after a failed attempt, continuing the previous candidate; flow `gate-attempt-repaired` |
| Fence failure fails the cycle | `FailCycle` with reason `fence_refused` at stage fence |

Event-model changes for these: `record-harness-self-check` and both self-check events gain
`occasion`, `attempt`, `identity-probe-digest`, `skipped-checks`, `missing-goldens`
(command), `budget`; new read model `harness-self-checks`; `record-gate-attempt` gains
`harness-digest`, `identity-probe-present`, `identity-probe-digest`,
`seam-answered-before-boot` and reads the self-checks; `seam-answered-before-boot` left both
reason enums; `install-candidate-in-gate-room` and its event carry `purpose`;
`record-regenerated-candidate` gains `mounted-evidence`; the cycle read model carries the
budget; new flow `gate-attempt-repaired`.

## Decisions applied in R2.1 (`../decisions.md`, "Questions raised by R2")

| Decision | Applied as |
|---|---|
| 1 budget exhausted fails the cycle with reason `budget` | `FailureReason` `budget`; `RegenerationCycle.stage_entered_at` (set wherever the stage changes) and derived `stage_deadline` (`timeout_of(budget, stage)`); temporal rule `ConcludeOnStageTimeout` (periodic task `stage-clock`, flow `stage-timeout-fails-cycle`) fails the cycle at a stage that outlived its timeout. The outer cycles (counted per approved preparation) and the continuations (per builder session) arrive later and use the same reason: a hook (`Budget` comment), not a rule |
| 2 gate refusals bounded; three in a row fail with `infrastructure` | `FailureReason` `infrastructure`; enum `GateRefusal`; rule `RecordGateRefusal` (ORC records a refusal after a refused `RecordGateAttempt`: event `gate-attempt-refused`, no attempt spent); `RegenerationCycle.consecutive_gate_refusals` (reset by a verdict, rule `NoteGateAttemptRefused`); policy rule `ConcludeOnRepeatedGateRefusals` (config `max_consecutive_gate_refusals` 3); `FailCycle` refusal 5 (infrastructure only at the gate); the gate stage's clock bounds refusals; contract `GateRoom` `RefusalsAreBoundedAndCounted`; flow `gate-refusals-fail-cycle` |
| 3 a gate that passed without exercising the product is the score's | contract `GateRoom` `RefusalsAreBoundedAndCounted` (prose); open question removed |
| 4 the self-check key covers the approved grader digests | `grader_digests` on `RecordHarnessSelfCheck`, `HarnessSelfCheck` and `harness-self-check-passed`; `RecordGateAttempt` gains `grader_digests`, refusal 12 (the attempt's self-check ran at these approved grader digests); contract `HarnessSelfCheckRun` `NeverReusedAcrossAChange`. The capture policy waits for runtime evidence (open question) |
| 5 a reworded golden fails the self-check | `reworded_goldens` on `RecordHarnessSelfCheck` and `harness-self-check-failed`; refusals 9 (no pass with a reworded golden) and 10 (a reworded golden is a current golden); contract `HarnessSelfCheckRun` `RewordedScenarioFailsItsGolden` |

## Legacy behaviours added in R2 (decisions.md "to add")

- **3, a missing identity probe**: `RecordGateAttempt` refusal 8 (legacy `verify.sh`, which
  refuses before it boots).
- **4, the probe is the instrument's**: refusal 9, the probe digest is the one the self-check
  checked; contract `GateRoom` `IdentityProbeIsTheInstruments`; guarantee `ProbeOutOfReach`
  (legacy `probe.sh`, README "The three modules", `@harness-053`).
- **5, the boot clock starts at launch**: contract `GateRoom` `BootClockStartsAtLaunch`
  (`@harness-058`); the budget's seconds and polls declared together (config, `@harness-056`).
- **7, a missing tool is a loud skip; a golden is required where its cycle exists**:
  `skipped-checks` recorded on the self-check; `RecordHarnessSelfCheck` refusal 8 (no pass
  with a missing golden); contract `HarnessSelfCheckRun` (`@harness-060`, `061`, `066`).
- **8, config completeness and the sibling-module check: dropped**, with this reason. The
  legacy `modules.sh` existed because one harness was copied by hand into three
  repositories, with each repository's differences in a hand-kept `config.sh`
  (`@harness-050` to `055`, `059`, `062`, `064`, `068` to `070`). In v2 there is one
  harness, generated from this spec and run by ORC; the values a repository declared
  (`APP_DIR`, `PORT`, `FENCED_PATHS`, `APP_PROBE`, `SIBLING_REPOS`) are the approved
  preparation's and the room policy's, typed in their commands. What the check protected
  is carried elsewhere: the harness digest names the exact harness a self-check ran (a
  forked copy is a different digest), the self-check reproduces the goldens, the identity
  probe is required (refusals 8-9), fenced paths that match nothing refuse the fence, and
  the goldens' presence is part of the self-check (refusal 8). `PLAN_DIR` agreement and the
  `SHARED`/`PER_REPO`/`LOCAL` lists have no subject once there is one copy.

## Storm events here

| Storm event | Became |
|---|---|
| ◆ Harness self-check passed | event `harness-self-check-passed` (before the fence and before each gate attempt) |
| ✗ Harness self-check failed | event `harness-self-check-failed`; the conclude policy records `cycle-failed` |
| ◆ Clean room proved | event `clean-room-proved` |
| ✗ Fence refused: a fenced path matched nothing | `ProveCleanRoom` refusal 2; ORC records `cycle-failed` (stage fence) |
| ✗ Fence refused: prior candidate reachable in the object graph | `ProveCleanRoom` refusal 6; ORC records `cycle-failed` (stage fence) |
| ◆ Candidate regenerated | event `candidate-regenerated` |
| Candidate installed in the gate room | event `candidate-installed-in-gate-room` |
| ◆ Gate attempt passed | event `gate-attempt-passed` (folded by another context) |
| ✗ Gate attempt failed | event `gate-attempt-failed` |
| ✗ Gate refused: tool missing or no-op suite | `gate-attempt-failed`, reason `contract-missing` |
| ✗ Gate refused: suite discovery empty | `gate-attempt-failed`, reason `suite-discovery-empty` |
| ✗ Gate refused: seam answered before boot | `RecordGateAttempt` refusal 10 (was a reason in R1; decision b) |
| ✗ Boot budget exhausted | `gate-attempt-failed`, reason `boot-budget-exhausted` |
| ✗ Identity probe failed | `gate-attempt-failed`, reason `identity-probe-failed`; a missing probe is refusal 8 |
| ◆ Gate attempts exhausted | read-model fact; policy rule `ConcludeOnExhaustedGateAttempts` |
| ◆ Cycle passed | event `cycle-passed` |
| ✗ Cycle failed | event `cycle-failed` |

Not modeled: builder room and session, slice built and continued, repair builder launched
(the repair is the `verification-repair` candidate), cycle started by ORC, timed out,
worker replaced, model drift.

## Refusals (the `requires`, numbered as in the Allium)

- `RecordHarnessSelfCheck`: 1 cut not pinned; 2 goldens digest not the current goldens;
  3 already recorded for the cycle, occasion and attempt; 4 failed without a failing check;
  5 before the fence but the cycle exists or the attempt is not 0; 6 before a gate attempt
  but no running cycle of this cut with that attempt's candidate; 7 budget outside 1..3
  gate attempts or over 3 continuations; 8 passed with a missing golden; 9 (R2.1) passed with a reworded golden; 10
  (R2.1) a reworded golden that is not a current golden.
- `ProveCleanRoom`: 1 self-check before the fence not passed; 2 a fenced path matched
  nothing; 3 root commits not 1; 4 commits not 1; 5 a fenced path left in the tree; 6 a
  fenced path reachable in the object graph; 7 already proved; 8 not the self-checked cut;
  9 a scoring tool not fenced; 10 cycle concluded.
- `RecordRegeneratedCandidate`: 1 no cycle; 2 no clean room; 3 not the next attempt; 4 over
  the budget's limit; 5 wrong purpose for the attempt; 6 concluded; 7 not after a failed
  attempt; 8 a repair not continuing the previous candidate; 9 evidence mounted that is not
  this cycle's gate evidence, or any by a fresh generation.
- `InstallCandidateInGateRoom`: 1 no candidate for the attempt; 2 another digest; 3 already
  installed; 4 concluded.
- `RecordGateAttempt`: 1 not installed; 2 already has a verdict; 3 failed without a reason or
  passed with one; 4 passed without the answering poll and identity probe result; 5 answered after
  the last poll; 6 another candidate; 7 no passing self-check for the attempt at this harness
  and the current goldens; 8 identity probe missing; 9 probe not the instrument's; 10 seam
  answered before boot; 11 concluded; 12 (R2.1) the self-check ran at other approved grader
  digests.
- `RecordGateRefusal` (R2.1): 1 not installed; 2 already has a verdict; 3 concluded.
- `PassCycle`: 1 no cycle; 2 scoreboard not reported; 3 concluded. `FailCycle`: 1 no cycle;
  2 concluded; 3 not the stage the cycle reached; 4 a reason that does not belong to the stage;
  5 (R2.1) infrastructure at a stage other than the gate.
- `ConcludeOnStageTimeout` (R2.1, the stage clock): 1 concluded; 2 a stage without a
  timeout (scoreboard, result).

## Drafts (R3, 2026-11-06; trimmed in R3.1)

`drafts.feature`: 83 draft scenarios (95 in R3) for the 114 must-cover obligations of this
context (126 in R3; `tools/spec/v2/coverage.py`: each rule's success, each `requires` that
is behaviour, each invariant, each contract invariant, each guarantee not verified by a
gate), every one tagged; 0 uncovered. Each draft names its rule, file and the Gherkin
`Rule:` it goes under, cites the legacy `@harness-NNN` it preserves, and suggests a tier
(39 minimum, 39 balance, 5 deferred).

Set aside, not drafted (24 unit level, 3 gate-verified):

- **Idempotent creations** (6): `ProveCleanRoom.7`, `InstallCandidateInGateRoom.3`,
  `RecordHarnessSelfCheck.3`, `NotePinnedCut.1`, `NoteGoldenWritten.1`, `OpenCycle.2`.
- **The cycle's own status** (9): `RecordRegeneratedCandidate.6`, the six conclude-policy
  guards, `PassCycle.3`, `FailCycle.2`. A test of another aggregate's status (a concluded
  cycle refusing a gate verdict) is behaviour and is drafted (`../decisions.md`, "Calls
  after R3", 2).
- **Guarded upstream** (9), each by a `guarded by:` comment the coverage gate checks: the
  seven folds' `exists cycle` (`NoteCleanRoomProved.1` behind `ProveCleanRoom.1`;
  `NoteGateAttemptPassed.1`, `NoteGateAttemptFailed.1` behind `RecordGateAttempt.11`;
  `NoteGateAttemptRefused.1` behind `RecordGateRefusal.3`; `NoteMeasurementFiled.1`,
  `NoteScoreStageFailed.1`, `NoteScoreboardReported.1` behind measurement's `FileScore.1`,
  `FailScoreStage.1`, `ReportScoreboard.1` and `RecordGateAttempt.11`),
  `NoteFailedSelfCheck.1` behind `RecordHarnessSelfCheck.5` and `.6`, and
  `NoteGoldenMoved.1` behind grader lifecycle's `MoveInstrumentGolden.1`.
- **Gate-verified** (3, "Calls after R3", 3): `BudgetInSecondsAndPolls` (gate
  `budget-in-seconds-and-polls`), `TheClaimantNeverControlsTheGate` and `ProbeOutOfReach`
  (gate `room-policy`, planned; see Checks).

R3.1 merged six pairs whose Given and When were the same: the self-check before the fence
with the cycle it opens; the second self-check before the fence with `OneCyclePerIdentity`;
the proved clean room with the stage it moves to; each gate verdict with its fold into the
cycle; the first regenerated candidate with `DoneIsTheGatesVerdict`.
The product owner accepts a draft by moving it into `spec.feature` (R4). R4.4 (2026-11-07):
the 27 that cite legacy behaviour and are tier minimum are accepted, `@item-04-001` to
`@item-04-027` (slice 04, `.pi/plan/04-regeneration-cycle`); 56 stay drafts, 12 of them new
minimum. Its seam is `seam.md` (`../decisions.md`, "Slice 04"). Since then `@item-04-028` to
`@item-04-038` (spec round 2), `@item-04-039` and `@item-04-040` (spec round 3) and `@item-04-041`
to `@item-04-076` (spec round 6) are accepted; the 9 drafts left stay drafts for the reasons in
`../decisions.md`, "Spec round 6" 4.

## Checks

The checks that verify this context's structural obligations ("Verified by: gate <name>").

| Check | Verifies | State |
|---|---|---|
| `budget-in-seconds-and-polls` (`tools/spec/v2/structural.py`, in `gates.sh`) | contract `GateRoom`, `BudgetInSecondsAndPolls`: the boot budget declared once as seconds and polls (one config block), both on every gate verdict in the Allium and the event model, the margin derived from the poll that answered | real |
| `room-policy` | guarantees `TheClaimantNeverControlsTheGate`, `ProbeOutOfReach`, `GateEvidenceOnly` and measurement's `PublicSeamOnly` and `NeverIntoTheLoop` (spec round 3; the mount itself is `RecordRegeneratedCandidate` requires 9, `@item-04-035`): for each room ORC builds (builder, repair, gate, validation) it reads ORC's room policy and proves what is mounted, writable and reachable. The builder and repair rooms hold neither the identity probe nor a scoring tool; the gate room is not a builder room and no builder session has a handle on it; the validation room reaches the candidate only through its public seam. The "fence-like check" of decisions.md H6 | planned: needs ORC's room policy, which arrives with the builder host (open item) |

## Open questions

Settled by `../decisions.md`: H1, H5, H6, H8, H24, H25, b, "is `seam-answered-before-boot`
an attempt" (no), and in R2.1 the five questions R2 raised (budget, gate refusals, H15, the
self-check key, the reworded golden). Still open (the first also as `open question` in
`allium/context.allium`):

- **H24** (part): does the self-check key also cover the capture policy? It waits for
  runtime evidence.
- Is `cycle-failed`'s stage `regenerate` (`candidate-not-regenerated`) enough before builder
  sessions and continuations arrive?
- **R3.1, the planned `room-policy` check** (Checks): it cannot exist before ORC's room
  policy does; until then `TheClaimantNeverControlsTheGate`, `ProbeOutOfReach`,
  `GateEvidenceOnly` and measurement's `PublicSeamOnly` and `NeverIntoTheLoop` (the last two
  pointed here since spec round 3) are verified by nothing that runs.

## Decided questions

- **Spec round 4, an assembly-gate question: was a cycle that failed at the score stage
  measured?** A measurement filed after the cycle failed at the score stage leaves the cycle
  where it failed (`NoteMeasurementFiled` requires 2, `@item-04-040`), but stays Measurement's
  record, and can be the next cycle's baseline (`last_measured_before`). In the assembled
  system the two contexts may disagree on whether that cycle was measured. **Decided (round 5):
  leave it (option c).** The two settle different questions: the cycle's status is this
  context's, and a measurement filed after the cycle failed at the score stage stays
  Measurement's record and may be the next cycle's baseline (legacy: `score.clj` files whatever
  was scored, and the legacy harness keeps no cycle state). Deferred: a `cycle-failed` handoff
  to Measurement (`../decisions.md`, "Spec round 5" 3; `../measurement/domain-design.md`,
  "Decided questions").
