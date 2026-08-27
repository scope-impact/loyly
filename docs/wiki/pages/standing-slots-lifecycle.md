# Standing slots lifecycle

## Acquisition, as actually documented

At Soukanvuori a standing slot is acquired by **request to the manager** (a web form),
followed by **negotiation against availability** (the requested Tuesday did not exist on
ph 9, so the manager offered ph 8), an explicit **resident confirmation**, and a manager
confirmation that also sets billing in motion.[^neg] The confirmed slot then becomes a
**work order** to update the posted list and the stove timer.[^wo]

Säästökenno documents the same shape at scale: **fixed weekly and bi-weekly standing
slots**, booked via the maintenance company.[^saasto]

- **U** Acquisition is request, availability negotiation, confirmation. Even a
  self-service product implements the same three steps; the manager is just one possible
  counterparty.
- **S** Weekly versus bi-weekly recurrence is configuration (Säästökenno has both).

## Holding

A standing slot persists across weeks. The holder can release a single week's occurrence
without touching the holding; the holding is only lost through the scarcity forfeit or by
giving it up. Both lifecycles, with state diagrams, are in
[slot-lifecycle-and-billing](slot-lifecycle-and-billing.md).

A second slot can be held **when there is room**,[^second] which means a holding acquired
under spare capacity can later become the target of the scarcity forfeit when the house
fills. Acquisition conditions and reclaim rules are two ends of one policy.

## Lapse for non-use: a gap, with a warning attached

Nothing in the real corpus documents automatic lapse of an unused standing slot. What the
corpus documents instead is the failure mode of the opposite policy: Säästökenno **tried
flexible slots and discontinued the experiment in 2023 for low usage and energy
cost**.[^saasto] Heating a sauna for slots nobody uses is money, and the live elicitation
lands on the same point from the landlord's side: heating and labour are the owner's
economics, so utilisation rules and energy rules pull together.[^landlord]

- **U** Whether unused holdings decay is policy, but the **evidence for the decision**
  (attendance versus booking) is a universal modelling concern: a system that records
  actual use makes any lapse rule enforceable on real data.
- **S** Lapse triggers, grace periods, and pause mechanisms are configuration; the corpus
  currently documents none, so any default must be argued from the Säästökenno lesson,
  not assumed.

[^neg]: `raw/domain/soukanvuori/2022-05-booking-negotiation.md`, § 1 to § 4.
[^wo]: `raw/domain/soukanvuori/2022-work-order-103016-and-drift.md`, § 1 and § 2.
[^saasto]: `raw/domain/public/asoy-saastokenno.md`, key claims.
[^second]: `raw/domain/soukanvuori/2022-05-booking-negotiation.md`, § 1 and "Extracted domain rules".
[^landlord]: `raw/domain/elicitation-2026-08-26.md`, "What owning a sauna means for the landlord". Oral source.
