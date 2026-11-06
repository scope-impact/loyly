# Specification: domain design

status: proposed, 2026-11-06 (spec v2, round R1: the event model of the legacy core; the
Allium arrives in R2). Derived from the storm (`../event-storm.md`, Specify) and the legacy
stamper (`archive/legacy-harness/stamp_item_ids.clj`), in the glossary's words
(`.pi/model/GLOSSARY.md`).

The open host: every other context conforms to its published language, the **item
identity** and the **stamped scenario**.

## Files

| Module | Event Model | Aggregate | Lives |
|---|---|---|---|
| Context | `event-model/area.edn` (screen `spec-authoring`, flow `stamping-to-checklist`) | none | |
| Slice | `event-model/slice.edn` | **Slice** (its stamped items, the next free number) | the project repository; one per slice's `spec.feature` |
| Handoffs produced | `event-model/handoffs.edn` (`scenario-stamped`) | | consumed by Measurement |

## Design decisions

1. **Slice is the aggregate**, because "an identity is handed out once, never renumbered or
   reused" is decided per slice: the next number is one past the highest the slice ever
   carried, even from a stray tag (legacy `@harness-004`).
2. **One event per newly stamped scenario.** Stamping is idempotent: a scenario already
   stamped produces nothing, so a rerun records nothing.
3. **The stamp carries no scenario digest.** The identity survives rewording; the digest
   changes with the behavior, so it is taken at the cut, by the checklist.

## Storm events here

| Storm event | Became |
|---|---|
| Scenario stamped | event `specification/scenario-stamped` (handoff) |
| ✗ Unstamped scenario found | refusal (R2) of `measurement/derive-checklist`: every scenario of the checklist must be stamped. It answers the operator who derives; nothing is recorded (see `../measurement/domain-design.md`) |

## Refusals for R2

`stamp-slice`: none beyond its schema (a slice is a number; a scenario already stamped is
skipped, not refused).

## Open questions

- None of its own. The stamping→checklist flow ends in Measurement.
