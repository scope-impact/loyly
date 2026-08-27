# Slot model

## The bookable resource is not "the sauna"

The naive model, one sauna per building with uniform opening days, is falsified by the
first real configuration. At Soukanvuori one stairwell sauna (sauna F) contains **two
dressing rooms**, pukuhuone 8 and pukuhuone 9, which are **independently bookable
resources with different weekly sauna days**: ph 9 has no Tuesday at all (only Thu, Fri,
Sat), while ph 8 does.[^ph] The public corpus varies the same dimension the other way:
Säästökenno has **seven saunas** in one company,[^seitseman] and the Swedish elicitation
adds many saunas across stairwells (uppgångar) with **cross-stairwell booking**
permitted.[^kors]

- **U** The bookable unit is a **resource** with its own availability calendar. A building
  has one or more saunas; a sauna has one or more bookable resources.
- **U** Availability is **per resource, per weekday**.[^ph]
- **S** How many resources, and which days each opens, is configuration.

The Swedish capture leaves the resource's **scope** open: is a booking the hot room only,
or the whole suite (bastu, changing space, gear)? The changing space is "maybe separate or
connected", so the bookable unit's boundary is itself a domain variable, marked OPEN in
the capture.[^suite]

## The slot

- **S** Slot length is policy: **90 minutes** at Soukanvuori (17.00 to 18.30),[^slot]
  **1.5 hour passes** at Masthugget.[^masthugget-pass]
- **U** A slot is a time window on a bookable resource: requested, negotiated against
  availability, then confirmed.[^ph]

## Two slot concepts, kept apart

A **standing weekly slot** is a recurring right that persists across weeks; a **single
occurrence** is one week's instance of it, which can be released without touching the
holding. The distinction and both lifecycles live in
[slot-lifecycle-and-billing](slot-lifecycle-and-billing.md); acquisition and holding in
[standing-slots-lifecycle](standing-slots-lifecycle.md).

## Time classes

A resource's calendar can carry **classes of time** with their own rules: Masthugget runs
gender-separated weekday hours and bookable family times at weekends, with a cap scoped to
the family-time class alone.[^masthugget-classes] See
[exceptional-bookings](exceptional-bookings.md).

- **U** A rule can attach to a time class, not only to a resource or a booking.
- **S** Which classes exist, and their rules, is configuration.

[^ph]: `raw/domain/soukanvuori/2022-05-booking-negotiation.md`, § 2 and "Extracted domain rules".
[^seitseman]: `raw/domain/public/asoy-saastokenno.md`, key claims.
[^kors]: `raw/domain/elicitation-2026-08-26.md`, "The resource". Oral source.
[^suite]: `raw/domain/elicitation-2026-08-26.md`, "The resource" and "The turn", marked OPEN there.
[^slot]: `raw/domain/soukanvuori/2022-05-booking-negotiation.md`, § 4.
[^masthugget-pass]: `raw/domain/public/brf-masthugget.md`, key claims.
[^masthugget-classes]: `raw/domain/public/brf-masthugget.md`, key claims.
