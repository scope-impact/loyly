# Slice 01: Booking core

status: ready-for-development

## Goal

A household can request a standing weekly slot on a bookable dressing room, have it
confirmed against that room's own sauna days, hold a second one while there is room, and
give one up. This slice establishes the booking lifecycle consumed by the projection
integrity slice; slice 02 proves that the posted list, heater schedule, and invoicing
signal cannot drift from that lifecycle.

## References

Per the minimum reference rule (`docs/wow/way-of-working.md` §3), this slice rests on:

- `docs/wiki/index.md`, and from it [slot-model](../../wiki/pages/slot-model.md)
  (per-resource sauna days, the 90 minute slot, standing slot versus occurrence),
  [actors-and-fairness](../../wiki/pages/actors-and-fairness.md) (the household as
  holder, the second-slot-when-room cap) and
  [slot-lifecycle-and-billing](../../wiki/pages/slot-lifecycle-and-billing.md)
  (the invoicing signal as a domain event)
- `raw/domain/soukanvuori/2022-05-booking-negotiation.md` (per-dressing-room days,
  the 90 minute slot, the second slot "koska tilaa on", billing via the
  vastikekirjanpitaja)
- `raw/domain/soukanvuori/2022-work-order-103016-and-drift.md` (the three
  representations and the drift incident this slice exists to prevent)

## Scope

**In:**

- The weekly schedule for one sauna with per-dressing-room sauna days
- Requesting a standing slot; confirmation against availability; refusal with a
  reason on a closed day or a held slot
- The second-standing-slot-when-there-is-room policy, both directions
- Giving up a standing slot

**Out:**

- Releasing a single week's occurrence (free or sale) and the scarcity forfeit
  clocks: see `decisions.md` D1
- The manager-mediated channel (web form, work orders): see `decisions.md` D2
- Time classes (family times, gender-separated hours) and transfers: later slices,
  grounded in `raw/domain/public/brf-masthugget.md` and the elicitation source
- Fee amounts and invoice delivery: the signal names payer and charge basis; pricing
  stays on the billing side (see the wiki's slot-lifecycle-and-billing page)
- Cross-projection consistency for confirmation and release: slice
  `02-projection-integrity`

## Acceptance

`spec.feature` in this directory. Every scenario there must pass against the real
interface before this slice is done; the verification trail goes in `decisions.md`.

## Tickets

- `ticket-01-weekly-schedule.md`: the per-resource schedule read model and view
- `ticket-02-standing-slot.md`: request, confirmation, refusals, and give-up
