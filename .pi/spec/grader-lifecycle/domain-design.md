# Grader lifecycle: domain design

status: proposed, 2026-11-06 (spec v2, R1). R1 holds only the legacy part of this context:
the instrument goldens. From the storm (`../event-storm.md`, Grader preparation) and the
legacy README ("The goldens, and the three reasons one can change").

## Files

| Module | Event Model | Aggregate | Lives |
|---|---|---|---|
| Context | `event-model/area.edn` (screen `instrument-goldens`, flow `golden-written-then-moved`) | none | |
| Instrument golden | `event-model/instrument-golden.edn` | **InstrumentGolden** (its digest and move history) | the harness repository; one per instrument scenario |
| Handoffs produced | `event-model/handoffs.edn` (`instrument-golden-written`, `instrument-golden-moved`) | | consumed by the Regeneration cycle (the self-check) |

## Design decisions

1. **The goldens stay in the storm's context**, Grader lifecycle, even though the graders
   themselves are a later round: they are instrument material on the instrument's own
   clock, outside every cycle. The graders are added here without renaming.
2. **A move carries exactly one reason**, one of the three the legacy README names
   (`instrument-behavior-changed`, `wording-replaced`, `spec-grew`), and a note. The reason
   is part of the event, so "moved" and "reason recorded" are one fact.
3. **`spec-grew` carries its test**: `additions-only`, the legacy diff test. R2 refuses
   `spec-grew` without it.
4. **Both golden events are handoffs** to the Regeneration cycle, whose harness self-check
   must reproduce the current goldens (glossary, Harness self-check). The storm listed only
   the grader digest as this context's handoff; this one is new and is the reason
   `record-harness-self-check` reads `regeneration-cycle/instrument-goldens`.

## Storm events here

| Storm event | Became |
|---|---|
| Instrument golden written | event `grader-lifecycle/instrument-golden-written` (handoff) |
| Instrument golden moved, reason recorded | event `grader-lifecycle/instrument-golden-moved` (handoff), reason in the event |

## Refusals for R2

`write-instrument-golden`: the golden already exists. `move-instrument-golden`: no such
golden; same digest; `spec-grew` without an additions-only diff; an empty note.

## Open questions

- **H19** (can wait): who checks a recorded reason is true? Only `spec-grew` has an
  enforceable test (additions only); the other two are a person's word.
- "Written from the scenario, never recorded from a run" cannot be checked by the event
  model. Is it a review duty, or does the golden carry the scenario digest it was written
  from so a reworded scenario flags it?
- **H27** (can wait): the legacy files still say "oracle" for three things.
