# Domain elicitation, 2026-08-26 (oral source)

First live elicitation. The question posed: "what domain knowledge do we need to capture
before implementing?" The room is the product owner and a collaborator. Captured in their terms, lightly sorted,
not yet resolved. Open tensions are marked OPEN.

## Headline: this is a SECOND configuration, and it is not Finnish

Everything below describes a **Swedish rental** world (hyresrätt), where the right to book
derives from a **tenancy** and the cost rides on the **monthly rent**. That is a different
tenure model from Soukanvuori (Finland), where the booker is an **osakas** (shareholder) and
billing is a **vastike** invoice from a bookkeeper. So "who may book" and "how it is billed"
are per-configuration, not universal. This is exactly the falsifiability the project wants:
if both the Finnish co-op and the Swedish rental case fit as configuration, the model holds.

## Actors

- **vaktmastare** (caretaker): operations, maintenance, the human who fixes and resets.
- **hyresgast** (tenant): the booker and payer. Booking rights come from the tenancy.
- **besokare** (visitor / guest): uses the sauna but does not book or pay.
- **tredjehands hyresgast** (third-hand / sub-sublet tenant): OPEN, potentially a booker, an
  edge of "who counts as a tenant".

## The resource

- A **bastuutrymme** (sauna suite) is more than the hot room: the **bastu** itself, plus
  **ombyte** (changing facilities), plus gear (**prylar**): a water bucket, a fridge for
  beer, sanitary supplies.
- A **husbolag** (housing company) can have **many saunas**, in different **uppgangar**
  (stairwells). **You can book across stairwells** (boka kors). One physical sauna can hold
  more than one bookable side, echoing Soukanvuori's ph 8 / ph 9.
- OPEN: is a booking the hot room only, or the whole suite? The changing space "kanske skilt
  eller ansluten" (maybe separate or connected) to the sauna, so the bookable unit is not
  fixed.

## The turn

- A turn is **the time you sit in the sauna**, which may be **separate from or connected to**
  the surrounding spaces. So the turn's scope (room only vs suite) is itself a domain
  variable, tied to the resource question above.

## House rules (the things regulars just know)

- No aroma oils in the sauna (inga aromoljor).
- Do not wet the sauna walls or the benches (fa inte vata bastuvaggar och lav).
- The spaces must be **empty before the next person arrives** (tomma innan nasta kommer).
- You **avoid meeting the next or previous** user (man undviker att traffa nasta / forra).
  This is a turnover buffer and a privacy rule, and it shapes how tightly turns can be packed.

## Money

- **Only the tenant may book, and the tenant pays.** The **tenancy** is what authorises the
  booking. The rental contract prices the **use** of the sauna, and the cost is **added to
  the rent each month** (Sweden).
- Access is by **lasbricka** (key fob) to reach common areas. The room wants a **physical
  bokningstavla** (booking board) where you **blippa** (tap) your fob. So the deliverable is
  two things: **a booking app AND an information/access system**, not just the app.
- **Transfer**: you can hand your time to someone else, and then it is **paid** (overlata,
  da betalas det). You pay for the time you booked; an **additional fee** applies.
- **Penalty**: there is a **straffavgift** (penalty fee) for **not using** a booked time,
  charged even though you did not use it. The room flags this as **oekologiskt**: heating an
  unused sauna wastes energy, so the penalty is really an energy-waste charge.
- **Cancel to save heat**: you can **cancel** your time **if you are the first** in the
  sequence, so the sauna needs to be heated less. Cancellation has an energy purpose, not
  just an availability one.
- **Resale is cheaper for the taker**: if you cancel and someone takes over, you get it **a
  bit cheaper** (lite billigare). OPEN: this seems to conflict with the "additional fee" and
  the penalty. See the tension below.

## Failure modes (why today's system fails a real person)

- The **previous guest propped the sauna door open** (stallt upp bastudorren), so it does not
  stay hot. (Note: this is exactly why a door
  switch is unsuitable as a remote-heating safety detector for a shared sauna: the armed
  state dies on every door opening.)
- The **sauna is not warm**: a technical fault, or an information fault (the timer and the
  booking disagreed, the Soukanvuori drift again).
- The **sauna stands empty**: people do not use their times. The room wants to **pack
  utilisation as tight as possible** (packa in sa mycket som mojligt).
- **Ad hoc booking is hard or impossible** today.
- **Cancel-and-hope**: you can cancel and hope someone takes over for a lower price, but then
  the failure is the **system not reaching the tenants** in time, and a person **loses
  money**. Reaching people (notification) is load-bearing, not a nicety.

## What owning a sauna means for the landlord (fastighetsbolag)

- **Heating costs** (varmekostnader), **maintenance**, and **caretaker operations**. The
  landlord's economics are energy and labour, which is why waste (empty-but-heated) and the
  penalty and the cancel-to-save-heat rule all point the same way: utilisation is money.

## The tensions to resolve next (OPEN)

1. **The money model pulls three ways at once.** There is a base fee (on the rent), an extra
   fee on transfer, a penalty for waste, and yet a taker pays *less*. Utilisation (pack it
   tight), ecology (do not heat the empty), and fairness (do not punish the person who freed
   the slot) are not the same goal, and the fee rules currently encode all three without a
   priority. This needs untangling.
2. **The bookable unit is undefined**: hot room only, or the whole suite, and how that
   interacts with cross-stairwell booking.
3. **Two products, one boundary**: the booking app and the physical information/access board
   (fob tap) are both in scope. Where does one end and the other begin?
