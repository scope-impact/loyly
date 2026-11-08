# Grader lifecycle: domain design

status: accepted as slice 03 (R4.3, 2026-11-07); proposed 2026-11-06 (spec v2, R1 event model;
R2 Allium and the product owner's decisions, `../decisions.md`). The core holds only the legacy
part of this context: the instrument goldens. From the storm (`../event-storm.md`, Grader
preparation) and the legacy README ("The goldens, and the three reasons one can change").

## Files

| Module | Event Model | Allium | Aggregate | Lives |
|---|---|---|---|---|
| Context | `event-model/area.edn` (screen `instrument-goldens`, flow `golden-written-then-moved`) | `allium/context.allium` (enum `MoveReason`, contract `ScenarioDigests`, open questions) | none | |
| Instrument golden | `event-model/instrument-golden.edn` | `allium/instrument-golden.allium` (rules `WriteInstrumentGolden`, `MoveInstrumentGolden`; surface `InstrumentGoldenDesk`) | **InstrumentGolden** (its digest, its scenario digest and its move history) | the harness repository; one per instrument scenario |
| Handoffs produced | `event-model/handoffs.edn` (`instrument-golden-written`, `instrument-golden-moved`) | emitted by both rules | | consumed by the Regeneration cycle (the self-check) |

## Design decisions

1. **The goldens stay in the storm's context**, Grader lifecycle: instrument material on the
   instrument's own clock, outside every cycle. The graders are added here later without
   renaming.
2. **A move carries exactly one reason**, one of the three the legacy README names, and a
   note; "moved" and "reason recorded" are one fact.
3. **`spec_grew` carries its test**: `additions_only`, refused without it.
4. **Both golden events are handoffs** to the Regeneration cycle, whose self-check must
   reproduce the current goldens.
5. **A golden carries the digest of the scenario it was written from** (decision h): written
   from the scenario, never recorded from a run, and a reworded scenario shows as a
   different digest.

## Decisions applied in R2

| Decision | Applied as |
|---|---|
| h golden carries its scenario digest | `scenario-digest` on both commands, both events and the read model; `WriteInstrumentGolden` refusal 2, `MoveInstrumentGolden` refusal 5 |
| Goldens written from the scenario (accepted change) | guarantee `WrittenFromTheScenarioNeverFromARun` |

## Storm events here

| Storm event | Became |
|---|---|
| Instrument golden written | event `grader-lifecycle/instrument-golden-written` (handoff) |
| Instrument golden moved, reason recorded | event `grader-lifecycle/instrument-golden-moved` (handoff), reason in the event |

## Refusals (the `requires`, numbered as in the Allium)

- `WriteInstrumentGolden`: 1 the golden already exists; 2 the scenario digest is not the
  digest of the scenario it is written from.
- `MoveInstrumentGolden`: 1 no such golden; 2 the same digest; 3 `spec_grew` without an
  additions-only diff; 4 an empty note; 5 the scenario digest is not the scenario's current
  digest.

## Drafts (R3, 2026-11-06)

`drafts.feature`: 11 draft scenarios for the 14 must-cover obligations of this context
(`tools/spec/v2/coverage.py`: each rule's success, each `requires` that is behaviour, each
invariant, each contract invariant, each guarantee), every one tagged; 0 uncovered. Each
draft names its rule, file and the Gherkin `Rule:` it goes under, cites the legacy
`@harness-NNN` it preserves, and suggests a tier. Unit-level, not drafted (1): `WriteInstrumentGolden.1` (an idempotent creation), which invariant `OneGoldenPerIdentity` checks through its draft.
The product owner accepts a draft by moving it into `spec.feature` (R4). R4.3 (2026-11-07):
the 4 that cite legacy behaviour and are tier minimum are accepted, `@item-03-001` to
`@item-03-004` (slice 03, `.pi/plan/03-grader-lifecycle`); 7 stay drafts, 4 of them new minimum.
Its seam is `seam.md` (`../decisions.md`, "Slice 03"). Since then the other 7 are accepted too:
`@item-03-005` to `@item-03-007` (spec round 2), `@item-03-008` (spec round 3, reworded) and
`@item-03-009` to `@item-03-011` (spec round 6); every R3 draft is in `spec.feature`.

2026-11-07: 8 more drafts, the open grader (`drafts.feature`, "The open grader"), with no
Allium yet and no `@allium` tag, for the next spec round: it grades however it judges, deciding
per step whether it needs a model; it reaches the candidate only through the seam tools (start
the candidate with a fresh event store, send a request, hand in a file, stop it, read the spec);
a step naming anything but a seam tool or its own contained code is refused and recorded; it may
hand an item to an explore session with all tools, recorded, and still files the verdict itself;
every grading run is kept in the grading history, findable by slice, cycle and candidate, and a
trial files nothing; a black-box audit reports a run clean or names each breach (the room, the
candidate's source, a prior cycle, a credential). Measurement holds the matching filing drafts.
Parked since spec round 3 (OG1 = no, below); they are all `drafts.feature` holds.

## Open questions

- **H19** (can wait): who checks a recorded reason is true? Only `spec_grew` has an
  enforceable test.
- **H27** (can wait): the legacy files still say "oracle" for three things.

## Decided questions

- **h**: the golden carries its scenario digest. A golden whose scenario was reworded fails the
  Regeneration cycle's self-check (R2.1, `../decisions.md`, "Questions raised by R2" 5).

The open grader drafts (2026-11-07) conflicted with accepted text; spec round 3 decided each
before any of them could be accepted:

- **OG1** (blocks the drafts): AGENTS.md "The instrument" and the glossary's **Grader** say a
  grader is written once by a grader author, calibrated so carried behaviour passes and
  uncarried behaviour fails, sealed by digest, and used only after an operator approves that
  digest, and the sealed graders are Elixir scripts. The open grader grades each cycle however
  it judges, and the legacy-style scoring decision (2026-11-07) dropped calibration and approval
  for our own cycles. Either the glossary and AGENTS.md change, or the open grader is not a
  grader. The glossary also avoids "per-cycle judge" and "LLM judge" for Grader.
  **Decided (round 3): no** — the product Grader stays sealed, calibrated and approved;
  legacy-style subagent grading is harness practice outside the spec; the 12 drafts are
  parked in `drafts.feature` (`../decisions.md`, "Spec round 3").
- **OG2**: AGENTS.md says graders share no code or runtime with what they measure, in a
  functional language other than the product's Clojure. The open grader runs as an ORC child
  workflow, on the harness's own ORC and Grain. Is that independence kept? **Moot while OG1
  is no** (`../decisions.md`, "Spec round 3").
- **OG3**: the explore session has all its tools; the black box around it is an audit after the
  run, not a room. "The fence is physical, never an instruction": does an explore session need
  its own room, or is a named breach enough? **Moot while OG1 is no** (`../decisions.md`,
  "Spec round 3").
