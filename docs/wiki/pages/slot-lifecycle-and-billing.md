# Slot lifecycle and billing: standing-slot forfeit and single-slot release

Product-owner decisions by Sami, 2026-08-26. These are real product rules for the **generic
product**, not scaffolding. They describe a richer, self-service operating model than
Soukanvuori currently runs, and one section below is dedicated to exactly that gap.

Source of the rules: Sami's 2026-08-26 product-owner brief, five rules on standing slots,
single-slot release, and an invoicing signal. Where a rule touches ground already documented
in the real corpus, the corpus file is cited. Where the rule assumes a capability the corpus
shows Soukanvuori does not have, that is called out, not hidden.

Tagging follows the corpus convention (`raw/domain/soukanvuori/`): **U** = candidate for the
universal core model, **S** = per-cooperative policy or a configuration value the model must
be able to express. Read the tags as claims to be falsified by the next cooperative, per the
generic-vs-first-configuration rule (one product, many houses; see the README).

## Two slot concepts, kept apart

The rules act on two different things with different lifecycles. Conflating them is the first
modelling error to avoid.

- **Standing weekly slot** (the holding). A recurring weekly reservation of a bookable
  resource by a resident, repeating indefinitely. It is a right that persists across weeks.
  It is the thing the **forfeit rule** acts on. A resident may hold **more than one**. This is
  grounded: at Soukanvuori a household asked for and received a second standing slot "koska
  tilaa on" (because there is room).[^second-slot]

- **Released occurrence** (one week's use). A single week's instance of a standing slot that
  the holder does not want, offered to others while the holding itself stays intact. It is the
  thing the **release rule** acts on. Releasing one occurrence changes nothing about the
  standing slot: next week is still the resident's.

Rule of thumb: the forfeit rule can take away the **holding**; a release only ever gives away
**one occurrence**. A resident who releases every occurrence for a month still holds the slot.

- **U** A standing weekly slot and a single released occurrence are distinct domain objects
  with distinct lifecycles. One is a recurring right; the other is a one-week transfer of use.

---

## Part A: the scarcity forfeit rule (standing slot)

### The trigger (rule 3)

- **U** When the house is **full** (all slots taken) **and** at least one resident is **waiting
  in a queue**, and a resident **holds more than one** standing slot, one of that resident's
  slots is put **under notice**. Scarcity is what activates the rule: with a free slot or an
  empty queue, nothing fires.
- **S** The notice period length ("say 1 month") is a configuration value, stated as one month
  but explicitly approximate in the brief.
- **S** The choose-deadline length (two weeks) is a configuration value.

### Two clocks

Both start when the slot goes under notice.

- **Choose clock, 2 weeks.** The window in which the *resident* may choose which of their slots
  to give up. If it elapses without a choice, the right to choose passes to the queue-head.
- **Notice clock, 1 month.** The window after which the resident actually loses the slot. The
  holding survives until this elapses, regardless of who chose.

The choose clock is nested inside the notice clock: the choice of *which* slot happens first
(or defaults to the queue-head), and the *loss* happens later when the notice clock runs out.

### The state model

```
                          scarcity trigger fires
                          (house full + queue non-empty
                           + resident holds > 1 slot)
                                     |
                                     v
   HELD  --------------------->  UNDER_NOTICE
   (resident holds              (one slot must go; WHICH is not
    >= 2 standing slots)         yet fixed; choose clock + notice
     ^                           clock both running)
     |                                |
     | non-chosen slots stay          |
     | HELD (only one is lost)        |
     |                    +-----------+------------------------+
     |                    |                                    |
     |          resident chooses within            2 weeks elapse, no choice
     |          2 weeks                             -> right to choose passes
     |                    |                            to queue-head
     |                    v                                    |
     |               NOTICE_SLOT fixed  <----------------------+
     |               (the specific slot to be lost is
     |                now identified; notice clock still
     |                running)
     |                    |
     |          queue-head acts on the identified slot
     |            +-------+--------+
     |            |                |
     |        CONFIRM           CANCEL
     |        (take it)         (decline)
     |            |                |
     |            v                v
     |     (queue-head is    (queue-head declines; disposition
     |      designated new    of the slot is an OPEN QUESTION:
     |      holder, pending    return to pool / offer to next
     |      lapse)             in queue / resident keeps it)
     |            |                |
     |            +-------+--------+
     |                    |
     |            notice clock (1 month) elapses
     |                    |
     |                    v
     +----------(?)---  LAPSED
       reclaim?         resident loses the slot:
       see open Q       - if CONFIRMED  -> REASSIGNED to queue-head
                        - if CANCELLED  -> returns to POOL (open Q)
```

### Who acts at each step

- **System**: detects the scarcity condition, puts a slot under notice, starts both clocks,
  transfers the choose-right to the queue-head at the 2-week mark, and lapses the slot at the
  1-month mark.
- **Resident**: chooses which of their slots to give up (within 2 weeks). The slots not chosen
  return to plain HELD; the resident keeps them.
- **Queue-head** (person next in the queue): once the slot is under notice, may **confirm**
  (take it) or **cancel** (decline). If the resident missed the 2-week choose window, the
  queue-head also **chooses which** of the resident's slots to take.

### Branch outcomes

- **Resident chooses in time -> queue-head confirms**: at lapse, the chosen slot is reassigned
  to the queue-head. Resident keeps their other slot(s).
- **Resident misses 2 weeks -> queue-head chooses and confirms**: the queue-head both picks the
  slot and takes it; at lapse it is reassigned to them.
- **Queue-head cancels (declines)**: the resident still loses the slot at lapse (the brief says
  the loss happens one month after notice), but who then gets it is undetermined (see open
  questions). Whether a decline instead halts the forfeit and lets the resident keep the slot
  is not stated.

- **U** A standing slot moves HELD -> UNDER_NOTICE -> LAPSED under scarcity, with a resident
  choose-right that defaults to the queue-head after a deadline, and a separate notice clock
  that governs when the holding is actually lost.

### What Soukanvuori does NOT have (reconciliation)

This whole rule assumes capabilities the real corpus shows Soukanvuori lacks:

- **No queue, no auto-forfeit.** Soukanvuori booking is **mediated by the manager** through a
  web form ("Autopaikat ja saunavuorot"), not resident self-service.[^manager] A second slot is
  granted by the manager "when there is room",[^second-slot] not reclaimed by a queue-driven
  timer. There is no documented queue and no documented notice mechanism.
- **The forfeit rule is a different operating model**, not a configuration of Soukanvuori's. It
  is the generic product's self-service model. Whether Soukanvuori would ever adopt it is a
  policy question for that cooperative, not an abstraction the corpus already supports.

---

## Part B: single-slot release (occurrence, rule 4)

A resident who cannot use one week (for example while travelling) can **release that one
occurrence** and offer it to others, without touching the standing slot.

- **U** A released occurrence has two modes:
  - **FREE**: the resident **still pays their normal fee** (they simply do not want the slot
    wasted empty), and the **taker uses it at no charge**.
  - **SALE**: the occurrence is offered for others to **buy**.

### Occurrence state model

```
   SCHEDULED  ---- holder releases ---->  RELEASED (mode = FREE | SALE)
   (this week's                                |
    instance of the                            |
    standing slot)               +-------------+-------------+
                                 |                           |
                             taker/buyer takes         nobody takes
                                 |                           |
                                 v                           v
                              TAKEN                       UNCLAIMED
                    (FREE: taker uses free;      (slot goes unused; resident
                     SALE: buyer has bought)      still pays normal fee)
```

- The standing slot itself never leaves HELD during a release. Only the one occurrence moves.
- **UNCLAIMED** is the do-nothing outcome the resident is trying to avoid: an empty, still-paid
  slot. The rules do not say a release can be withdrawn once offered; that is left open.

### What Soukanvuori does NOT have (reconciliation)

- **S** Soukanvuori has no documented self-service resale of a single week. Its billing runs
  through the vastike, issued by the **vastikekirjanpitaja**, decoupled from the booking
  act.[^billing] A per-occurrence resale that charges a buyer is again the generic model, not a
  Soukanvuori capability the corpus shows.

---

## Part C: the invoicing signal (rule 5)

The system must **produce a signal for invoicing** so the billing side charges correctly. At
Soukanvuori the billing side is the **vastikekirjanpitaja** and the charge arrives on the
**vastike invoice**, separate from whoever took the booking.[^billing]

Model it as a **domain event**, not a schema. The rules imply the event exists and roughly
what it must carry; they do not fix its fields, so the fields below are marked as implied or
undetermined.

### Triggers (what raises the event)

- **U** A standing slot held over a billing period -> the **normal standing-slot fee** is due
  from its holder.
- **U** A **resale** of a released occurrence (mode SALE, outcome TAKEN) -> a charge is due for
  that resale.
- A **free** release (mode FREE) still leaves the **normal fee** due from the original holder,
  and the taker owes nothing. Whether this needs its own event or is simply the absence of a
  resale charge is undetermined.

### What the event must carry (implied by the rules)

- **which resident** is being charged (the payer),
- **which slot or occurrence** the charge is for (standing holding vs a specific week),
- **free vs paid**, so the billing side can tell a normal fee from a resale,
- **an amount, or a reference the billing side prices from**. The brief says the signal lets
  billing "charge correctly"; it does not say the system sets the price. Consistent with
  Soukanvuori, where pricing lives on the vastike side, the safe reading is that the event may
  carry a **reference** and let billing price it, but this is undetermined (see open questions).

- **U** Billing is a downstream consumer fed by a domain event, decoupled from booking. This
  matches the corpus: the booking channel and the invoice are already separate roles at
  Soukanvuori.[^billing]

### Connection to the drift invariant

The invoicing signal is one more **representation** of a booking, alongside the slot list and
the heater timer. The corpus's strongest lesson is that one booking living in three
representations **drifts** when nothing keeps them in sync: at Soukanvuori a slot vanished from
the vuorolista while the ajastus was changed, leaving a cold sauna.[^drift] An invoicing signal
derived from the same single source of truth as the list and the timer is the point: the
billing must not become a fourth copy that drifts.

---

## Open questions (raised, not answered)

These are genuine gaps in the 2026-08-26 rules. They are product decisions still owed, not
things to settle by assumption.

1. **Queue ordering and priority.** How the queue is ordered (seniority of request, arrival
   time, need) and who is the "next in queue" is unspecified.
2. **Forfeit trigger scope: per-resource or house-wide.** Does "everything is full" mean the
   specific resource a queued resident wants, or the whole house? A per-dressing-room world
   like Soukanvuori's (ph 8 and ph 9 have different sauna days)[^perroom] makes this sharp.
3. **Forfeit threshold: exactly two, or any count above one.** Rule 3 says "holds two weekly
   slots". Whether the rule fires only at exactly two, or at any holding above one (three, four
   ...), and how many slots are put under notice at once, is unstated.
4. **Resale pricing and collection.** Who sets a resale price (the releaser or the house), and
   who **collects** the money (the releaser keeps it, or the house does), is not stated.
5. **Free-release fee treatment.** The releaser keeps paying and the taker is free (stated).
   Whether the house therefore **double-collects** (fee from the holder, nothing from the taker,
   which is fine) or is meant to credit the holder is not stated. The plain reading is no
   double-collect beyond the one normal fee, but confirm.
6. **Contents and delivery of the invoicing signal.** The exact fields, whether the event
   carries an amount or a price reference, and how it is delivered to the vastike side, are
   undetermined.
7. **Reconciliation with Soukanvuori's manual, manager-mediated reality.** The forfeit rule
   and self-service resale assume a queue and self-service that Soukanvuori does not run; how
   (or whether) the generic model maps onto a manager-mediated cooperative is open.
8. **Notice withdrawal / reclaim.** If the scarcity condition clears (the queue empties) before
   the notice clock elapses, is the notice withdrawn and the slot reclaimed as HELD? The
   "reclaimed?" branch is not addressed by the rules.
9. **Queue-head decline disposition.** If the queue-head cancels (declines), does the slot
   return to the pool, pass to the next person in the queue, or stay with the resident? Rule 3
   says the resident loses it at one month but not who gets it after a decline.
10. **Release withdrawal.** Whether a resident who released an occurrence (FREE or SALE) can
    withdraw the offer before someone takes it is not stated.

[^second-slot]: `raw/domain/soukanvuori/2022-05-booking-negotiation.md`, § 1 and "Extracted domain rules": a household may hold a second standing slot "when there is room" ("koska tilaa on").
[^manager]: `raw/domain/soukanvuori/2022-05-booking-negotiation.md`, "Extracted domain rules": booking is mediated by the manager via a web form, not resident self-service.
[^billing]: `raw/domain/soukanvuori/2022-05-booking-negotiation.md`, § 4: "Vastikekirjanpitaja lahettaa teille laskun." Billing is a distinct role, decoupled from the booking act.
[^perroom]: `raw/domain/soukanvuori/2022-05-booking-negotiation.md`, § 2: one stairwell sauna (F) has two dressing rooms (ph 8, ph 9) with different sauna days.
[^drift]: `raw/domain/soukanvuori/2022-work-order-103016-and-drift.md`, § 3: the slot vanished from the vuorolista while the ajastus was changed, leaving a cold sauna. One booking in three representations drifts.
