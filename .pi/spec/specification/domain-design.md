# Specification: domain design

status: proposed, 2026-11-06 (spec v2, R1 event model; R2 Allium and the product owner's
decisions, `../decisions.md`). Derived from the storm (`../event-storm.md`, Specify) and
the legacy stamper (`archive/legacy-harness/stamp_item_ids.clj`), in the glossary's words
(`.pi/model/GLOSSARY.md`).

The open host: every other context conforms to its published language, the **item
identity**, the **stamped scenario** and, since R2, the **one reading of Gherkin**
(contract `ScenarioReading`).

## Files

| Module | Event Model | Allium | Aggregate | Lives |
|---|---|---|---|---|
| Context | `event-model/area.edn` (screen `spec-authoring`, flow `stamping`, which stays inside the context) | `allium/context.allium` (value `UnstampedScenario`, contract `ScenarioReading`, open question) | none | |
| Slice | `event-model/slice.edn` | `allium/slice.allium` (rules `StampFirstScenarios`, `StampSlice`; surface `SpecAuthoring`) | **Slice** (its stamped items, the ledger's next number) | the project repository; one per slice's `spec.feature` |
| Handoffs produced | `event-model/handoffs.edn` (`scenario-stamped`) | emitted by both stamping rules | | consumed by Measurement |

## Design decisions

1. **Slice is the aggregate**, because "an identity is handed out once, never renumbered or
   reused" is decided per slice. The next number comes from a **ledger that never goes
   down** (accepted change): one past the highest number the slice ever handed out, so a
   deleted scenario's number is never reused (legacy `@harness-010` lowered it).
2. **One event per newly stamped scenario.** Stamping is idempotent: the scenarios offered
   must be exactly the file's unstamped ones (refusal 4), so a rerun records nothing.
3. **The stamp carries no scenario digest.** The identity survives rewording; the digest is
   taken at the cut, by the checklist.
4. **Two rules for one command**: `StampFirstScenarios` opens the ledger at 1,
   `StampSlice` continues it. A spec file with no scenario opens it with nothing stamped.
5. **One reading of Gherkin** for the stamper and the checklist (contract
   `ScenarioReading`), so the two tools cannot disagree about what is an item.

## Legacy behaviours added in R2 (decisions.md "to add")

- **1, which lines are scenarios and items**: contract `ScenarioReading` invariants
  `OutlineYesTemplateNo` (`@harness-003`), `EmptyTitleIsNoScenario` (`@harness-021`),
  `WhitespaceTitleIsAScenario` (`@harness-049`), `TitleIsTrimmed` (`@harness-018`),
  `ItemTagFromTheContiguousTagRun` and `OtherTagsDoNotStamp` (`@harness-005`, `019`),
  `SliceFromTheDirectory` (`@harness-001`, `014`). Dropped with reason: the checklist's
  "pending tag" wart (`@harness-020`), a second reading of the same file.
- **2, "no spec found" distinct from "fully stamped"**: refusal 1 of both rules (the refusal
  names where it looked), against success with nothing stamped; guarantee
  `NoSpecIsNotFullyStamped` (`@harness-011`).

## Storm events here

| Storm event | Became |
|---|---|
| Scenario stamped | event `specification/scenario-stamped` (handoff) |
| ✗ Unstamped scenario found | `measurement` `DeriveChecklist` refusal 2 |

## Refusals (the `requires`, numbered as in the Allium)

- `StampFirstScenarios`: 1 no spec found; 2 the source is not this slice's spec file; 3 the
  slice already has a ledger; 4 the scenarios offered are not exactly the file's unstamped
  ones, in file order; 5 (R2.1) the empty-title headings reported are not exactly the
  file's.
- `StampSlice`: 1 no spec found; 2 not this slice's file; 3 no ledger yet; 4 and 5 as above.

## Drafts (R3, 2026-11-06)

`drafts.feature`: 17 draft scenarios for the 24 must-cover obligations of this context
(`tools/spec/v2/coverage.py`: each rule's success, each `requires` that is behaviour, each
invariant, each contract invariant, each guarantee), every one tagged; 0 uncovered. Each
draft names its rule, file and the Gherkin `Rule:` it goes under, cites the legacy
`@harness-NNN` it preserves, and suggests a tier. Unit-level, not drafted (2): `StampFirstScenarios.3` and `StampSlice.3` (one half each of the ledger / no-ledger pair). Refusals 2, 4 and 5 of the two stamping rules are drafted as Scenario Outlines with one example per rule (no ledger, a ledger).
The product owner accepts a draft by moving it into `spec.feature` (R4).

## Open questions

None. Settled in R2.1 (`../decisions.md`, "Questions raised by R2", 6): **an empty-title
heading is not an item, and the stamper reports it loudly** (legacy `@harness-021`).
Applied as: `stamp-slice` carries `empty-title-lines`; refusal 5 of `StampFirstScenarios`
and `StampSlice` (the lines reported are exactly the file's, none hidden); event
`empty-title-headings-reported` (emitted whenever the file has one, even with nothing to
stamp); contract function `empty_title_lines_of`; guarantee `EmptyTitleReportedLoudly`.
