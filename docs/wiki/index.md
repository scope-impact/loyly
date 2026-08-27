# Löyly domain wiki

This wiki is the curated domain knowledge for the sauna booking system: a generic Nordic
product whose first real configuration is As Oy Soukanvuori (Espoo).

## The two layers (simplified source-docs convention)

**Sources are authoritative; this wiki is derived.** The wiki is one representation of the
sources: it can be rebuilt from them at any time, and where a wiki page and a source ever
disagree, the source wins and the page is the bug.

The sources are:

- `raw/domain/soukanvuori/`: real, redacted 2022 documents from Soukanvuori. Each file
  carries its own provenance header (which Gmail thread, which message).
- `raw/domain/public/`: provenance records for the verified public corpus (Finnish,
  Swedish, Norwegian). Cite-only, raw artifacts not captured; see its `README.md`,
  including the list of known sources whose URLs still need recovering.
- `raw/domain/elicitation-2026-08-26.md`: a live elicitation with the product owner and
  a collaborator, a Swedish-rental world. Oral source: real, but weaker evidence than a
  document, and its open tensions are marked OPEN there.
- Product-owner decisions of 2026-08-26, recorded in
  [slot-lifecycle-and-billing](pages/slot-lifecycle-and-billing.md). The underlying brief
  is not captured as a file; that page is its record.

Every factual claim in a wiki page carries a footnote naming the specific source and the
section it comes from, for example:

> The slot is 90 minutes.[^ex]

[^ex]: `raw/domain/soukanvuori/2022-05-booking-negotiation.md`, § 4.

When sources conflict, the page says so and records which one the system follows and why.
Do not silently pick a winner.

## The U/S tagging convention

Following the corpus files: **U** marks a candidate rule for the universal core model,
**S** marks per-cooperative policy, a configuration value the model must be able to
express. The tags are claims to be falsified: the first documented cooperative whose rules
cannot be expressed as configuration is a design bug, not an exception (see the README's
"one product, many houses" rule).

## Pages

- [Slot model](pages/slot-model.md): what is bookable (it is not "the sauna"), the slot,
  and availability per resource per weekday.
- [Actors and fairness](pages/actors-and-fairness.md): who books, who pays, who operates,
  and the fairness pressure behind caps.
- [Standing slots lifecycle](pages/standing-slots-lifecycle.md): how a standing slot is
  acquired and held, and the Säästökenno lesson on loosening them.
- [Slot lifecycle and billing](pages/slot-lifecycle-and-billing.md): the 2026-08-26
  product-owner rules. Scarcity forfeit (two clocks), single-occurrence release (free or
  sale), and the invoicing signal as a domain event.
- [Exceptional bookings](pages/exceptional-bookings.md): special time classes, caps,
  transfers, and the money-model tension.
- [Authority and disputes](pages/authority-and-disputes.md): the board owns the rules,
  fees are governance, enforcement can be automated.

## The one lesson above all

One booking lives in several representations (the posted list, the heater timer, the
invoice), and at Soukanvuori they drifted until a resident stood in a cold sauna at a time
they still believed was theirs.[^drift] Everything in this wiki exists so that the system
derives every representation from a single source of truth. This wiki follows the same
rule about its own sources.

[^drift]: `raw/domain/soukanvuori/2022-work-order-103016-and-drift.md`, § 3.
