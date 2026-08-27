# Authority and disputes

## The board owns the rules

In a Finnish housing company, the rules for common facilities are **agreed in the board**
(hallitus): the board sets them and the board revises them.[^board] The Norwegian corpus
shows the same power exercised concretely: at Romolslia, sauna use is **by contract with a
fee set by the unit board**.[^romolslia]

- **U** The booking system is downstream of governance. Every rule it enforces is a board
  decision, and the board can change it; rules therefore need effective dates and a
  record of where they came from.
- **S** Fee amounts and contract terms are per-house policy. Fee levels are genuinely
  contested in the real world (reimbursement caps versus rising house fees),[^yle] so a
  fee change is an event the system should record, not silently overwrite.

## Who arbitrates day to day

The real corpus documents dispute routing only for the manager-mediated world: at
Soukanvuori, the complaint about the vanished slot went **on the same work-order thread
to the manager**, five months after the booking; there was no other channel.[^drift] The
elicitation names the **vaktmästare** as the operations human who fixes and
resets.[^vakt]

- **S** Who is the first stop for a dispute (manager, caretaker, board) is configuration.
- **U** A dispute references a specific booking and its history. The Soukanvuori
  complaint could only say "my slot vanished" because the thread held the original
  confirmation; the system should make that evidence first-class rather than accidental.

## Enforcement can be automated

Two corpus documents show rule enforcement moving from social pressure into the
infrastructure itself:

- Romolslia's shared laundry runs on **tokens with hours enforced by automatic power
  cutoff**.[^romolslia]
- Masthugget prices rule-breaking directly: a **1,200 kr emergency access fee**.[^access]

That is the same coupling the Soukanvuori work order shows by hand (a booking updates the
stove timer),[^wo] and it is where the product's automation scope begins. The regulatory
boundary is sharp and already researched: commanding a heater over a network is regulated
under SFS-EN 60335-2-53, and the exemption for weekly clocks fell in 2023. That
constraint bounds the automation side of the product; it is researched, not designed, here.

- **U** An enforcement action (cutoff, fee, access denial) is a domain event tied to a
  rule and a booking, so it can be explained and appealed.
- **S** Which enforcement is automated, and what it costs, is configuration; whether it
  is even lawful can depend on jurisdiction and standard, so configuration needs a
  compliance dimension, not just a preferences one.

[^board]: `raw/domain/public/kiinteistolehti-board-authority.md`, key claims.
[^romolslia]: `raw/domain/public/romolslia-borettslag.md`, key claims (sections 5.2 and 5.5).
[^yle]: `raw/domain/public/yle-sauna-fees-2022.md`, key claims.
[^drift]: `raw/domain/soukanvuori/2022-work-order-103016-and-drift.md`, § 3.
[^vakt]: `raw/domain/elicitation-2026-08-26.md`, "Actors". Oral source.
[^access]: `raw/domain/public/brf-masthugget.md`, key claims.
[^wo]: `raw/domain/soukanvuori/2022-work-order-103016-and-drift.md`, § 1 and § 2.
