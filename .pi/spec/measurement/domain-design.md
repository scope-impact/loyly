# Measurement (instrument: score and scoreboard): domain design

status: proposed, 2026-11-06 (spec v2, R1). From the storm (`../event-storm.md`: Prepare
(checklist), Score, Scoreboard) and the legacy `gen_checklist.clj`, `score.clj` and
`scoreboard.clj`, in the glossary's words.

## Files

| Module | Event Model | Aggregate | Lives |
|---|---|---|---|
| Context | `event-model/area.edn` (screen `instrument-desk`, flow `scoreboard-restates-moved-item-set`) | none | |
| Checklist | `event-model/checklist.edn` | **Checklist** | event store; one per pinned cut (later: per approved selection) |
| Cycle measurement | `event-model/cycle-measurement.edn` | **CycleMeasurement** (item verdicts, the one filing, or the failed stage) | event store; one per cycle |
| Scoreboard | `event-model/scoreboard.edn` | **Scoreboard** | event store; one per measured cycle |
| Handoffs produced | `event-model/handoffs.edn` (`measurement-filed`, `score-stage-failed`, `scoreboard-reported`) | | consumed by the Regeneration cycle, only to conclude |

Actors: the **operator** derives a checklist; the **grader** (its run reported by ORC
runtime) files item verdicts; **ORC runtime** (the score node) files the score or records the
failed score stage; the **report-scoreboard-once** policy reports. Nothing here issues a
command of the build loop.

## Design decisions

1. **The operator derives the checklist in R1** (legacy: `gen_checklist` is run by a
   person). The storm's policy "whenever a selection is approved, derive its checklist"
   arrives with the selection and issues the same command. Its refusals then answer the
   person who derives, which a policy-issued command could not do.
2. **The checklist is keyed by cut** in R1 (the whole plan, the legacy shape and H10's
   proposed answer); the approved selection adds a key later. It records the slices
   skipped by `planned`/`backlog` status, so a shrunk denominator is in the record, not
   only on stderr (H23).
3. **The checklist counts only identities the stamper handed out** (`item-identities`,
   from Specification's handoff): the spec is the only author of the item list.
4. **Two steps to a measurement.** The grader files the verdicts it returned
   (`item-verdicts-filed`, as returned, duplicates included); the instrument files the one
   measurement (`file-score`, the glossary's FileScore). Several graders may file for one
   cycle (H21: graders cover part of the items).
5. **Unreached and superseded are fields of the filed measurement**, not events. An item
   with no verdict is `fail` with `reason: unreached` and stays counted (H7's proposed
   answer, the reason beside a pass-or-fail verdict). The last verdict for an item stands,
   as in legacy, but every superseded one is listed in `superseded`, so last-wins is no
   longer silent (H26 stays open).
6. **A refused score files nothing; the failed stage is a fact.** The score refusals are
   R2 `requires`. ORC's score node then records `score-stage-failed`, which is a handoff:
   the conclude policy fails the cycle, and `file-score` requires the stage not failed, so
   it is never retried within the cycle.
7. **The score binds the gated candidate**: `gated-cycles` holds the digest from
   `gate-attempt-passed`, and `file-item-verdicts` names the candidate it graded (H13).
8. **The scoreboard compares with the previous measured cycle.** `scoreboard-reported`
   carries newly passing and newly failing apart, new items (the untuned holdout) and
   dropped items, and has no net field. When the item set moved, the same command also
   records `moved-item-set-restated`.

## Storm events here

| Storm event | Became |
|---|---|
| Checklist derived | event `measurement/checklist-derived` |
| ✗ Empty selection refused | refusal (R2) of `derive-checklist`: the checklist must not be empty |
| (Unstamped scenario found, Specification's) | refusal (R2) of `derive-checklist`: every scenario stamped |
| Item verdicts filed | event `measurement/item-verdicts-filed` |
| ✗ Unreached item recorded as fail | field of `measurement-filed`: `verdict fail`, `reason unreached`, counted in `unreached` |
| ✗ Duplicate verdict superseded | field of `measurement-filed`: `superseded`; the last verdict is the read-model fact of `item-verdicts` |
| ✗ Score refused | refusals (R2) of `file-item-verdicts` and `file-score` |
| ✗ Score stage failed, not retried | event `measurement/score-stage-failed` (handoff) |
| ◆ Measurement filed | event `measurement/measurement-filed` (handoff) |
| Moved item set restated | event `measurement/moved-item-set-restated` |
| ◆ Scoreboard reported | event `measurement/scoreboard-reported` (handoff) |

## Refusals for R2

- `derive-checklist`: cut not pinned; a scenario unstamped (lists them; legacy refusal
  names the stamper); checklist empty; an item identity never stamped; an item identity
  twice; a checklist already derived for the cut with another digest.
- `file-item-verdicts`: cycle not gated, or another candidate digest; no checklist for the
  cycle's cut; zero verdicts; an item not on the checklist (checked before the verdict
  values, legacy `@harness-039`); measurement already filed; score stage failed. A verdict
  other than pass or fail fails the schema. (Later: the grader digest not approved.)
- `file-score`: zero verdicts filed; already filed; score stage failed; not gated.
- `fail-score-stage`: measurement filed; already failed.
- `report-scoreboard`: no measurement filed; already reported.

## Open questions

- **H7** (blocking, proposed answer taken for `unreached` only): `inconclusive` and
  `outage` join the reason enum with runtime evidence.
- **H13** (blocking): is the grader digest re-checked at score time? R1 binds the
  candidate; the grader digest check arrives with grader approval.
- **H16** (can wait): can an outage leave a false "scored" record? R1 refuses zero verdicts,
  but a partial outage still files, with the unreached items failed.
- **H21** (can wait): should the scoreboard also report graded coverage?
- **H26** (can wait, disagreement): supersede (legacy, now listed) or refuse duplicates?
- **New**: the "previous cycle" is the previous measured cycle. Of the same selection, the
  same cut series, or any? Legacy is the cycle number minus one.
