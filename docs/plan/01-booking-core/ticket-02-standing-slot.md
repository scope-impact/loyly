# Ticket 02: Standing slot lifecycle and the derived views

Slice: `01-booking-core` (`plan.md`)
Status: OPEN

## Goal

A household can request a standing weekly slot; the request is confirmed against the
dressing room's own sauna days and the slot's held state, and refused with a reason
otherwise (a closed day names the days that do open; a held slot names nothing about
the holder beyond that it is held). A household holding one slot can gain a second
while free slots remain, and is refused when none do. A household can give up a slot
it holds.

The whole vertical for these behaviours, including the three derived views: the
posted slot list, the heater schedule and the invoicing signal are read models over
the booking record. Confirming or giving up a slot changes the record once, and all
three views follow, because none of them stores its own copy (decision D5).

## Acceptance criteria

From `spec.feature`:

- "A standing slot cannot be requested on a closed day"
- "A requested slot on an open day is confirmed"
- "A held slot cannot be confirmed for another household"
- "A second standing slot is granted while there is room"
- "A second standing slot is refused when the schedule is full"
- "Confirming a booking updates all three derived views together"
- "Giving up a slot leaves no derived view still claiming it"

Constraints from `docs/wow/invariants.md`:

- INV-1: refusals carry the configured rule they enforce, never an invented one.
- INV-3: the standing-slot cap (second-when-room) is enforced in exactly one
  place; the condition lives in the single decision point, nowhere else.
- INV-4: slots are named calendar values.
- INV-5: tests derive from `spec.feature`, red before green.
