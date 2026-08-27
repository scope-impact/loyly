# Exceptional bookings: time classes, transfers, and the money tension

## Special time classes

Masthugget shows that a sauna's week is not a uniform grid: weekday hours are **gender
separated**, weekends carry **bookable family times**, and the booking cap ("Varje
lägenhet kan endast ha en aktiv/kommande bokning på familjetiderna i bastun") is scoped to
the family-time class alone.[^masthugget] A booking system that models only "slots on a
resource" cannot express a rule that binds a class of times across the week.

- **U** Time classes exist and rules can scope to them (see
  [slot-model](slot-model.md)).
- **S** Which classes a house runs is configuration.

## Handing a turn to someone else

The live elicitation gives transfers three distinct flavours, all real and mutually
tense:[^transfer]

- **Transfer (överlåta)**: you hand your time to a named someone; it is then **paid**, and
  an **additional fee** applies.
- **Penalty (straffavgift)**: not using a booked time costs extra even though you did not
  come, framed by the room as an energy-waste charge (heating an empty sauna).
- **Cancel to save heat**: the **first** slot in a sequence can be cancelled so the sauna
  is heated less; and if someone takes over a cancelled time, the taker gets it **a bit
  cheaper**.

The generic product's version of the same territory is the **single-occurrence release**
(free or sale) in [slot-lifecycle-and-billing](slot-lifecycle-and-billing.md), which the
holder uses precisely to avoid the empty-but-paid outcome.

## The money-model tension (OPEN)

The three flavours pull three ways at once: utilisation (pack it tight), ecology (do not
heat the empty room), and fairness (do not punish the person who freed the slot). A base
fee, a transfer surcharge, a no-show penalty, and a cheaper-for-the-taker discount encode
all three goals with no stated priority. The elicitation marks this OPEN, and the wiki
keeps it open: it is a product decision still owed, not a rule to infer.[^tension]

## Exceptional access

Masthugget also documents the enforcement edge of exceptional access: a **1,200 kr
emergency access fee**.[^masthugget] Breaking the access rules is priced, not just
frowned upon; see [authority-and-disputes](authority-and-disputes.md) for enforcement by
automation.

[^masthugget]: `raw/domain/public/brf-masthugget.md`, key claims.
[^transfer]: `raw/domain/elicitation-2026-08-26.md`, "Money" and "Failure modes". Oral source.
[^tension]: `raw/domain/elicitation-2026-08-26.md`, "The tensions to resolve next", tension 1.
