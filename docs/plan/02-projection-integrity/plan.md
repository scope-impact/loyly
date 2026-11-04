# Slice 02: Projection integrity

status: approved

## Goal

Prove through the public interface that confirming or releasing one standing booking
changes every dependent view together: the posted slot list, heater schedule, and
billing signal. This isolates the corpus's central drift incident as a small vertical
slice over the booking lifecycle established by slice 01.

## References

- `docs/wiki/pages/slot-lifecycle-and-billing.md`: confirmation raises the billing
  signal and release ends future standing-slot charges.
- `raw/domain/soukanvuori/2022-work-order-103016-and-drift.md`: the posted list,
  timer, and billing representation drifted and caused the cold-sauna incident.
- `docs/plan/01-booking-core/decisions.md` D5: the views are projections of one
  booking record, not independently writable copies.

## Scope

**In:**

- Confirmation appears in the posted list, heater schedule, and billing signal.
- Release disappears from the posted list and heater schedule and prevents further
  standing-slot charges.
- Runtime evidence for both stable behavior identities.

**Out:**

- A new booking lifecycle, new stable behavior IDs, or separate synchronization.
- A resident UX affordance. This slice is API-observable and therefore has no
  affordance contract of its own.
- Fee calculation, invoice delivery, occurrence release, and scarcity rules.

## Acceptance

`spec.feature` is the executable acceptance projection. Its IDs remain
`@item-01-007` and `@item-01-008` because item identity is stable across cuts.

## Tickets

- `ticket-01-projection-integrity.md`: expose and verify the three consistent views.
