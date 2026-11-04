# Ticket 01: Keep booking projections consistent

Slice: `02-projection-integrity` (`plan.md`)
Status: APPROVED

## Goal

Expose the posted list, heater schedule, and billing signals through the public API
and derive all three from the same booking lifecycle. Confirmation and release must
never leave the projections disagreeing.

## Acceptance criteria

From `spec.feature`:

- `@item-01-007`: confirming a booking updates all three derived views together.
- `@item-01-008`: giving up a slot leaves no derived view still claiming it.

The ORC run must pass builder tests, Compose verification, independent black-box
grading, and the runtime-evidence contract for both selected items.
