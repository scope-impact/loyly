# Booking negotiation: a second standing slot (2022-04-27 to 2022-05-04)

Source: Gmail thread "New submission from Autopaikat ja saunavuorot" / "VS: New submission
from Autopaikat ja saunavuorot", four messages between the resident and the manager
(Braleva Kiinteistopalvelut), 2022-04-27 to 2022-05-04. Redacted per `README.md`. The
rule-bearing Finnish is verbatim.

## 1. The request (web form, 2022-04-27)

The resident books through the manager's web form titled **"Autopaikat ja saunavuorot"**
(parking spaces and sauna slots, one form for both). The submission that reached the
manager carried these fields:

```
Taloyhtio:      Soukanvuori
Osoite:         [redacted street]
Nimi:           [resident]
Sahkoposti:     [redacted email]
Puhelinnumero:  [redacted phone]
Olen:           Osakas
Varaukset:      Haluan varata saunavuoron
Lisatietoja:    Huom meilla on jo vuoro mutta koska tilaa on toivoisin yhden lisaa.
SAUNAVUORON PERUUTUS
  Viikonpaiva:  Tiistai
  Klo:          17:45
```

Two domain facts are already visible. The requester is an **osakas** (shareholder, not a
mere tenant). And the free-text line is the request for a **second** standing slot,
verbatim: *"Huom meilla on jo vuoro mutta koska tilaa on toivoisin yhden lisaa."* (We
already have a slot, but since there is room I would like one more.)

## 2. The manager's answer (2022-04-29)

The manager ([asiakasneuvoja], Braleva Kiinteistopalvelut) replied, verbatim:

> Valitettavasti saunan F ph 9 ei ole saunapaivaa lainkaan tiistaisin
> on vain TO, PE ja LA
> mutta
> Ph 8 puolelta klo 17.00 onnistuu kyllae.
>
> Ilmoitattehan meille jos taemae kaey.

The non-obvious rules, all in three lines: one stairwell sauna (**sauna F**) has **two
dressing rooms**, ph 8 and ph 9 (pukuhuone 8 and 9); the two sides have **different sauna
days**; ph 9 has no Tuesday at all (only Thu, Fri, Sat); ph 8 does. The manager offers ph 8
at 17.00 and asks the resident to confirm.

## 3. The resident accepts (2022-05-02)

> ok kaey klo 17 ph 8 kaey myoes

## 4. The confirmation (2022-05-04)

The manager ([asiakasneuvoja], Braleva Kiinteistopalvelut) confirmed, verbatim:

> Varataan teille pyynnostanne saunavuoro tiistaille pukuhuone 8 klo 17.00-18-30
> Vastikekirjanpitaja lahettaa teille laskun.

The slot is **90 minutes** (17.00 to 18.30). Billing is not part of the booking channel: a
separate role, the **vastikekirjanpitaja** (the maintenance-charge bookkeeper), sends the
invoice, so the charge arrives through the vastike machinery, not from the manager who took
the booking.

## Extracted domain rules

Marked U (likely universal, a candidate for the core model) or S (Soukanvuori policy, a
configuration value the model must be able to express). This split is the point of the
project (see the repo README).

- **S** Booking is mediated by the manager, via a web form, not resident self-service.
- **S** The requester's standing is recorded (osakas). Some rights may depend on it.
- **S** A household may hold a **second** standing slot **when there is room** ("koska tilaa
  on"). The cap is not one-per-apartment here; it is one-per-apartment-plus-more-if-space.
- **S** A physical sauna (sauna F) contains **two independently bookable resources** (ph 8,
  ph 9), each with its **own weekly sauna days**. A model that assumes uniform sauna days
  per building cannot express this.
- **S** Slot length is 90 minutes here (17.00 to 18.30).
- **S** Billing is via the vastike, issued by a distinct bookkeeper role, decoupled from the
  booking act.
- **U** A slot is a time window on a bookable resource, requested, negotiated against
  availability, then confirmed. Availability is per-resource, per-weekday.
