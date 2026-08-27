# Actors and fairness

## Tenure defines the booker

Who may book is not universal; it follows the tenure model, and the corpus documents two:

- **Finnish asunto-osakeyhtiö** (Soukanvuori): the booker's standing is recorded on the
  booking form; the documented requester is an **osakas** (shareholder).[^osakas]
- **Swedish rental** (hyresrätt, from the live elicitation): the booker is the
  **hyresgäst** (tenant), the right to book derives from the tenancy, and the cost rides
  on the rent. Guests (besökare) use the sauna but neither book nor pay. Whether a
  third-hand subtenant counts as a booker is OPEN.[^hyresgast]

- **U** A booking is held by a **household unit** (apartment or tenancy), not by a person.
- **S** Which tenure roles may book, and what standing is recorded, is configuration.

## The operating roles

At Soukanvuori booking is **mediated by the manager** (a customer-service person at the
management company answers the web form), and billing is executed by a third role, the
**vastikekirjanpitäjä**, who sends the invoice separately from the booking
channel.[^mediated] A booking then becomes a **work order** that maintenance actions by
hand (list, timer).[^workorder] Säästökenno likewise books via the maintenance
company.[^saastokenno] The Swedish capture adds the **vaktmästare** (caretaker) as the
operations human.[^vaktmastare]

- **U** Booking, operations, and billing are **distinct roles** that a configuration may
  assign to one party or several.
- **S** Manager-mediated versus resident self-service is configuration, and the generic
  product's self-service rules must not assume every house runs them (see the
  reconciliation sections of
  [slot-lifecycle-and-billing](slot-lifecycle-and-billing.md)).

## The fairness pressure

Scarcity of good evening slots is what generates the rules. The corpus shows fairness
enforced as **caps per household unit**, always scoped to something:

- Masthugget: **one active or upcoming booking per apartment**, scoped to the family-time
  class.[^cap]
- Soukanvuori: a household may hold a **second standing slot when there is room** ("koska
  tilaa on"), so the cap is conditional on spare capacity, not absolute.[^second]
- The generic product: under scarcity (house full, queue non-empty), a multi-slot holder
  is put under notice and loses one slot; see the forfeit rule in
  [slot-lifecycle-and-billing](slot-lifecycle-and-billing.md).

- **U** Fairness rules are caps and reclaim rules on the household unit.
- **S** The cap's size, scope, and conditionality are configuration.

## Fees are contested, not trivia

Real fee data: Kela reimburses at most 10 EUR per month while Lumo charges 20 and an Akaa
housing company raised its fee to 25.[^yle] The fee a house sets and the fee an
institution reimburses are different numbers set by different parties, so a fee change is
a governance event with losers, not a settings tweak. Who sets fees is on
[authority-and-disputes](authority-and-disputes.md).

[^osakas]: `raw/domain/soukanvuori/2022-05-booking-negotiation.md`, § 1.
[^hyresgast]: `raw/domain/elicitation-2026-08-26.md`, "Actors" and "Money". Oral source.
[^mediated]: `raw/domain/soukanvuori/2022-05-booking-negotiation.md`, § 1, § 4, and "Extracted domain rules".
[^workorder]: `raw/domain/soukanvuori/2022-work-order-103016-and-drift.md`, § 1 and § 2.
[^saastokenno]: `raw/domain/public/asoy-saastokenno.md`, key claims.
[^vaktmastare]: `raw/domain/elicitation-2026-08-26.md`, "Actors". Oral source.
[^cap]: `raw/domain/public/brf-masthugget.md`, key claims (verbatim cap sentence).
[^second]: `raw/domain/soukanvuori/2022-05-booking-negotiation.md`, § 1 and "Extracted domain rules".
[^yle]: `raw/domain/public/yle-sauna-fees-2022.md`, key claims.
