# Measurement (instrument: score and scoreboard): domain design

status: proposed, 2026-11-06 (spec v2, R1 event model; R2 Allium and the product owner's
decisions, `../decisions.md`). From the storm (`../event-storm.md`: Prepare (checklist),
Score, Scoreboard) and the legacy `gen_checklist.clj`, `score.clj` and `scoreboard.clj`, in
the glossary's words.

## Files

| Module | Event Model | Allium | Aggregate | Lives |
|---|---|---|---|---|
| Context | `event-model/area.edn` (screen `instrument-desk`, flow `scoreboard-restates-moved-item-set`) | `allium/context.allium` (values, enums, contracts `Scoring`, `RateFormat`, `Comparison`, open questions) | none | |
| Checklist | `event-model/checklist.edn` | `allium/checklist.allium` (rule `DeriveChecklist`; the stamp and cut handoffs; surface `InstrumentDesk`) | **Checklist** | event store; one per pinned cut (later: per approved selection) |
| Cycle measurement | `event-model/cycle-measurement.edn` | `allium/cycle-measurement.allium` (rules `FileItemVerdicts`, `FileScore`, `FailScoreStage`; the gate handoff; surfaces `VerdictFilingSeam`, `ScoreStage`, `MeasurementReceipt`) | **CycleMeasurement** (filings, the one measurement, or the failed stage) | event store; one per gated cycle |
| Scoreboard | `event-model/scoreboard.edn` | `allium/scoreboard.allium` (rule `ReportScoreboard`; policy `ReportScoreboardOnce`; surface `ScoreboardReport`) | **Scoreboard** | event store; one per measured cycle |
| Handoffs produced | `event-model/handoffs.edn` (`measurement-filed`, `score-stage-failed`, `scoreboard-reported`) | emitted by `FileScore`, `FailScoreStage`, `ReportScoreboard` | | consumed by the Regeneration cycle, only to conclude |

Actors: the **operator** derives a checklist and reads the receipt and the scoreboard; the
**grader** (its run reported by ORC runtime) files item verdicts; **ORC runtime** (the
score node) files the score or records the failed stage; the **report-scoreboard-once**
policy reports. Nothing here issues a command of the build loop.

## Design decisions

1. **The operator derives the checklist in the core** (legacy: a person runs
   `gen_checklist`). The policy "whenever a selection is approved, derive its checklist"
   arrives with the selection and issues the same command.
2. **The checklist is keyed by cut**, and carries its **selection** (the whole plan in the
   core, H10), which the scoreboard compares by. It records the slices skipped by status
   (H23).
3. **The checklist counts only identities the stamper handed out** (`ItemIdentity`, from
   Specification's handoff), read with Specification's one reading of Gherkin.
4. **Two steps to a measurement**: the grader files the verdicts it returned
   (`FileItemVerdicts`, duplicates included); the instrument files the one measurement
   (`FileScore`). Several graders may file for one cycle (H21).
5. **Unreached and superseded are fields of the filed measurement**, not events (contract
   `Scoring`).
6. **A refused score files nothing; the failed stage is a fact** (`FailScoreStage`), a
   handoff that fails the cycle; the score is never retaken within the cycle.
7. **The score binds the gated candidate and the approved grader digests** (H13).
8. **The scoreboard compares with the last measured cycle of the same selection** (g).

## Decisions applied in R2 (`../decisions.md`)

| Decision | Applied as |
|---|---|
| H7 reason beside the verdict | `reason` (unreached, inconclusive, outage) beside a pass-or-fail verdict on the filed items and on `item-verdicts-filed` (inconclusive, outage); `FileItemVerdicts` refusal 10; contract `Scoring` `ReasonBesideTheVerdict` |
| H13 score binds candidate and grader digest | `FileItemVerdicts` refusals 2 and 9; `FileScore` refusals 5 (grader digest re-checked when filed) and 6; the approved grader digests are an input fact (`approved-grader-digests`) until the grader lifecycle's approval arrives; invariant `FilingsBindTheGatedCandidate` |
| H26 last wins, superseded listed | contract `Scoring` `LastVerdictWinsAndTheRestAreListed`; `superseded` on `measurement-filed` |
| g last measured cycle of the same selection | `selection-id` on the checklist, the measurement and the scoreboard; contract `Comparison` `LastMeasuredCycleOfTheSameSelection`; guarantee `ComparedWithTheLastMeasuredCycle` |
| Bad verdict value before unknown item (accepted change) | `FileItemVerdicts` refusal 5 before 6; the command schema no longer types the verdict, so the refusal is the instrument's |
| Every unstamped file listed (accepted change) | `DeriveChecklist` refusal 2 lists every unstamped scenario of every file |
| Skipped slices stored (accepted change) | `skipped-slices` on `checklist-derived`; guarantee `ShrunkDenominatorIsOnRecord` |
| A refused score never retaken (accepted change) | `FileItemVerdicts` refusals 7-8, `FileScore` refusals 3-4 |
| Filed record joins the checklist by digest (accepted change) | `checklist-digest` on `measurement-filed` |

Event-model changes for these: `derive-checklist`, `checklist-derived` and `checklists` gain
`selection-id`; `file-item-verdicts` gains `approved-grader-digests`, an untyped `verdict`
and an optional `reason`; `file-score` gains `approved-grader-digests`;
`item-verdicts-filed` gains `reason`; `measurement-filed` gains `selection-id` and the
reasons inconclusive and outage; both refusal enums gain `grader-not-approved` and
`bad-verdict-reason`; `scoreboard-reported` gains `selection-id`, `rate` and the shared
rates.

## Legacy behaviours added in R2 (decisions.md "to add")

- **1, which lines are scenarios and items**: the checklist uses Specification's contract
  `ScenarioReading`; `DeriveChecklist` refusal 7 (an empty title is no item); guarantee
  `OneReadingOfTheSpec`.
- **6, scoreboard rates**: contract `RateFormat` (one decimal, half to even; "n/a" over
  zero; `@harness-045`, `046`, `048`); `rate` on `scoreboard-reported`.

## Storm events here

| Storm event | Became |
|---|---|
| Checklist derived | event `measurement/checklist-derived` |
| ✗ Empty selection refused | `DeriveChecklist` refusal 3 |
| (Unstamped scenario found, Specification's) | `DeriveChecklist` refusal 2 |
| Item verdicts filed | event `measurement/item-verdicts-filed` |
| ✗ Unreached item recorded as fail | field of `measurement-filed`: `verdict fail`, `reason unreached`, counted in `unreached` |
| ✗ Duplicate verdict superseded | field of `measurement-filed`: `superseded` |
| ✗ Score refused | refusals of `FileItemVerdicts` and `FileScore` |
| ✗ Score stage failed, not retried | event `measurement/score-stage-failed` (handoff) |
| ◆ Measurement filed | event `measurement/measurement-filed` (handoff) |
| Moved item set restated | event `measurement/moved-item-set-restated` |
| ◆ Scoreboard reported | event `measurement/scoreboard-reported` (handoff) |

## Refusals (the `requires`, numbered as in the Allium)

- `DeriveChecklist`: 1 cut not pinned; 2 a scenario unstamped (every one listed; the refusal
  names the stamper); 3 checklist empty; 4 an identity never stamped; 5 an identity twice;
  6 already derived for the cut; 7 an item with an empty title.
- `FileItemVerdicts` (refusal code for `fail-score-stage` in brackets): 1 cycle not gated
  [not-the-gated-candidate]; 2 another candidate [not-the-gated-candidate]; 3 no checklist
  [no-checklist]; 4 zero verdicts [zero-verdicts]; 5 a verdict neither pass nor fail
  [bad-verdict-value]; 6 an item not on the checklist [unknown-item]; 7 already filed; 8
  score stage failed; 9 grader digest not approved [grader-not-approved]; 10 a reason not
  inconclusive or outage, or beside a pass [bad-verdict-reason].
- `FileScore`: 1 not gated; 2 zero verdicts filed; 3 already filed; 4 stage failed; 5 a grader
  digest no longer approved; 6 a filing for another candidate.
- `FailScoreStage`: 1 not gated; 2 measurement filed; 3 already failed.
- `ReportScoreboard`: 1 no measurement filed; 2 already reported. Invariant (R2.1)
  `ComparedWithinTheSelection`.

## Drafts (R3, 2026-11-06; trimmed in R3.1)

`drafts.feature`: 45 draft scenarios (47 in R3) for the 66 must-cover obligations of this
context (69 in R3; `tools/spec/v2/coverage.py`: each rule's success, each `requires` that is
behaviour, each invariant, each contract invariant, each guarantee not verified by a gate),
every one tagged; 0 uncovered. Each draft names its rule, file and the Gherkin `Rule:` it
goes under, cites the legacy `@harness-NNN` it preserves, and suggests a tier (32 minimum,
13 balance). Set aside, not drafted (10 unit level, 1 gate-verified):
`DeriveChecklist.6`, `NoteItemIdentity.1`, `NotePinnedCut.1`, `OpenMeasurement.1`,
`ReportScoreboard.2` (idempotent creations); `FileItemVerdicts.8`, `FileScore.4`,
`FailScoreStage.3` (the measurement's own status); `FileScore.6` (guarded by
`FileItemVerdicts.2`: a filing for another candidate is refused when it is filed) and
`ReportScoreboardOnce.1` (guarded by `FileScore.3`: a measurement is filed, so handed over,
once), each by a `guarded by:` comment the coverage gate checks; guarantee `PublicSeamOnly`
(gate `room-policy`, planned: `../regeneration-cycle/domain-design.md`, "Checks"; decisions.md,
"Calls after R3", 3).
The product owner accepts a draft by moving it into `spec.feature` (R4).

## Open questions

Settled by `../decisions.md`: H7 (the field), H13, H26, g. Still open (also as
`open question` in `allium/context.allium`):

- **H7** (part): which runtime evidence makes a verdict inconclusive or an outage.
- **H16** (can wait): a partial outage still files, with the unreached items failed; an
  `outage` reason is recordable but nothing requires it.
- **H21** (can wait): should the scoreboard also report graded coverage?
- **R3.1**: `PublicSeamOnly` waits for the planned `room-policy` check.

Settled in R2.1 (`../decisions.md`, "Questions raised by R2", 7): **the same selection** is
the same selected slices, by name, across cuts; whole-plan cycles compare with the last
measured whole-plan cycle. Applied as: the checklist's `selection_id` is "whole-plan" or the
selected slices' names, never the cut; `Scoreboard.previous_selection_id` and
`previous-selection-id` on `scoreboard-reported`; invariant `ComparedWithinTheSelection`;
contract `Comparison` and guarantee `ComparedWithTheLastMeasuredCycle` reworded.
