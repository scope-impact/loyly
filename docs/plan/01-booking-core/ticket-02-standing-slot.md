# Ticket 02: Standing slot lifecycle

Slice: `01-booking-core` (`plan.md`)
Status: OPEN

## Goal

A household can request a standing weekly slot; the request is confirmed against the
dressing room's own sauna days and the slot's held state, and refused with a reason
otherwise (a closed day names the days that do open; a held slot names nothing about
the holder beyond that it is held). A household holding one slot can gain a second
while free slots remain, and is refused when none do. A household can give up a slot
it holds.

This ticket owns the booking lifecycle. Cross-projection consistency for the posted
list, heater schedule, and invoicing signal is accepted separately by slice
`02-projection-integrity`.

## Acceptance criteria

From `spec.feature`:

- "A standing slot cannot be requested on a closed day"
- "A requested slot on an open day is confirmed"
- "A held slot cannot be confirmed for another household"
- "A second standing slot is granted while there is room"
- "A second standing slot is refused when the schedule is full"

Constraints from `docs/wow/invariants.md`:

- INV-1: refusals carry the configured rule they enforce, never an invented one.
- INV-3: the standing-slot cap (second-when-room) is enforced in exactly one
  place; the condition lives in the single decision point, nowhere else.
- INV-4: slots are named calendar values.
- INV-5: tests derive from `spec.feature`, red before green.
