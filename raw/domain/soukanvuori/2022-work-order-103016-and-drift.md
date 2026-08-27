# Work order 103016 and the drift incident (2022-05-09 to 2022-10-04)

Source: Gmail thread "Ilmoituksenne 103016 on otettu tyon alle" / "on kasitelty" / "Re: ...
on kasitelty", the manager's work-order report and the resident's later complaint on the
same thread, 2022-05-09 to 2022-10-04. Redacted per `README.md`. Verbatim Finnish kept.

This is the single most important document in the repo. It is the real-world evidence that
one booking living in three representations will drift apart, which is the strongest
argument the project has for a single source of truth.

## 1. The booking becomes a work order (2022-05-09, "otettu tyon alle")

The confirmed slot from the booking negotiation did not just go onto a list. It was opened
as a maintenance **work order (ilmoitus) 103016**, titled **"Saunavuorolista + ajastus"**
(sauna slot list + timer). The booking is now a job for the maintenance company.

## 2. The work order report (2022-05-10, "on kasitelty")

The processed report, verbatim in its rule-bearing parts:

```
Kohde
  Kiinteisto        [redacted id] Asunto-osakeyhtio Soukanvuori
Ilmoittaja
  Nimi              [resident]
Tyotehtava
  Otsikko           Saunavuorolista + ajastus
  Tyotehtavan kuvaus  Paivitetty saunavuorolista + ajastus. Uusi ajastus:
                      Sauna F, pukuhuone 8. Tiistaisin klo 17-18.30 [resident]
  Sisaanpaasy       Ei asunnossa kayntia
  Kotielaimet       Ei kotielaimia
```

The description names **the three representations explicitly**: the **saunavuorolista** (the
paper or posted slot list), the **ajastus** (the stove timer that heats the sauna for the
slot), and, from the booking confirmation, the **lasku** (the vastike invoice). Confirming
one booking means a human updates all three by hand. "Uusi ajastus: Sauna F, pukuhuone 8.
Tiistaisin klo 17-18.30" is the timer being programmed to match the list.

## 3. The drift (2022-10-04)

Five months later, on the same thread, the resident wrote, verbatim:

> hei. miksikohan tama nyt kadonnut seka vuorolistoilta etta ajastusta
> ilmeisesit muutettu kun asken ei ollut sauna lammin?

Translated: why has this now vanished from the slot lists, and the timer apparently been
changed, since the sauna was not warm just now? Two of the three representations moved and
the third did not. The slot left the **vuorolista**, the **ajastus** was changed, and the
result was a **cold sauna** at a time the resident still believed was theirs. Nobody
malfunctioned; the representations were never one thing, so they drifted.

## Extracted domain rules

- **U** One booking exists in **multiple representations** (the posted slot list, the
  heater timer, the invoice). Nothing keeps them consistent except human effort, so they
  drift. This is the case for a single source of truth from which the list, the timer
  schedule and the billing are all derived.
- **U** The **heater timer is coupled to the booking**: a slot is not merely a reservation,
  it is also an instruction to make the room usable (heat it) for that window. A booking
  system that does not drive the heater leaves a gap a human fills by hand and forgets.
- **S** At Soukanvuori the coupling is manual and mediated by the maintenance company: a
  booking becomes a work order that a person actions. The automation side of the product is exactly this coupling made direct,
  and it is regulated once the heater is commanded over a network.
- **U** Failure is silent. The drift is discovered by a resident standing in a cold sauna,
  not by any system noticing the three copies disagree. Detecting divergence is a product
  feature, not an edge case.
