# Ticket 01: The weekly schedule

Slice: `01-booking-core` (`plan.md`)
Status: OPEN

## Goal

One view showing the weekly schedule for a sauna whose dressing rooms each have their
own sauna days. Rows are the configured 90 minute slots, columns the weekdays, one
column set per dressing room. A day a dressing room does not open shows no slots for
it at all, rather than slots that always refuse. Each open slot shows exactly one of:
**free**, or **held** (with the holding household).

This is the read side only: the schedule renders whatever the booking record holds.
It performs no booking.

## Acceptance criteria

From `spec.feature`:

- "The weekly schedule shows each dressing room's own sauna days": Tuesday shows
  bookable slots for pukuhuone 8 and none for pukuhuone 9.
- The schedule states asserted inside the booking scenarios ("holds it for every
  coming Tuesday", "still holds the slot") render correctly; ticket 02 produces
  those states, this ticket displays them.

Constraints from `docs/wow/invariants.md`:

- INV-1: the schedule shows only configured days and slots; it invents no rules.
- INV-4: slots are named calendar values (weekday, start, length), never floats or
  raw timestamps.
- INV-5: the view's tests derive from the spec scenarios, not from the code.
