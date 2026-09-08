Feature: Booking core
  The example cooperative is configured from the real corpus: one stairwell
  sauna (sauna F) containing two independently bookable dressing rooms,
  pukuhuone 8 and pukuhuone 9, each with its own weekly sauna days. Slots
  are 90 minutes. A standing slot repeats weekly for the same household
  until it is given up. The posted slot list, the heater schedule and the
  billing signal are all derived from the booking record: they can never
  disagree with it, because they are readings of it, not copies.

  Rule: Availability is per dressing room, per weekday

    @item-01-001
    Scenario: The weekly schedule shows each dressing room's own sauna days
      Given pukuhuone 9's sauna days are Thursday, Friday and Saturday
      And pukuhuone 8's sauna days include Tuesday
      When a resident views the weekly schedule for sauna F
      Then Tuesday shows bookable slots for pukuhuone 8
      And Tuesday shows no slots for pukuhuone 9

    @item-01-002
    Scenario: A standing slot cannot be requested on a closed day
      Given pukuhuone 9's sauna days are Thursday, Friday and Saturday
      When the household in apartment 12 requests a standing slot on Tuesday at 17:00 in pukuhuone 9
      Then the request is refused because pukuhuone 9 has no sauna day on Tuesday
      And the refusal names the days pukuhuone 9 does open

  Rule: A slot is confirmed against availability and held by one household

    @item-01-003
    Scenario: A requested slot on an open day is confirmed
      Given Tuesday at 17:00 in pukuhuone 8 is free
      When the household in apartment 12 requests a standing slot on Tuesday at 17:00 in pukuhuone 8
      Then the standing slot is confirmed for 17:00 to 18:30
      And the household in apartment 12 holds it for every coming Tuesday

    @item-01-004
    Scenario: A held slot cannot be confirmed for another household
      Given the household in apartment 12 holds the Tuesday 17:00 standing slot in pukuhuone 8
      When the household in apartment 3 requests a standing slot on Tuesday at 17:00 in pukuhuone 8
      Then the request is refused because the slot is held
      And the household in apartment 12 still holds the slot

  Rule: A household may hold a second standing slot when there is room

    @item-01-005
    Scenario: A second standing slot is granted while there is room
      Given the household in apartment 12 holds one standing slot
      And free standing slots remain in the weekly schedule
      When the household in apartment 12 requests a second standing slot on a free open slot
      Then the second standing slot is confirmed
      And the household in apartment 12 holds two standing slots

    @item-01-006
    Scenario: A second standing slot is refused when the schedule is full
      Given the household in apartment 12 holds one standing slot
      And no free standing slots remain in the weekly schedule
      When the household in apartment 12 requests a second standing slot
      Then the request is refused because there is no room
      And the household in apartment 12 still holds exactly one standing slot

  Rule: One booking is the single source of truth for the list, the timer and the billing

    @item-01-007
    Scenario: Confirming a booking updates all three derived views together
      Given Tuesday at 17:00 in pukuhuone 8 is free
      When the household in apartment 12's standing slot request for Tuesday at 17:00 in pukuhuone 8 is confirmed
      Then the posted slot list shows the slot for apartment 12
      And the heater schedule heats pukuhuone 8's sauna for Tuesday 17:00 to 18:30
      And an invoicing signal is raised naming apartment 12 as payer for the standing slot

    @item-01-008
    Scenario: Giving up a slot leaves no derived view still claiming it
      Given the household in apartment 12 holds the Tuesday 17:00 standing slot in pukuhuone 8
      When the household in apartment 12 gives up the standing slot
      Then the posted slot list no longer shows the slot
      And the heater schedule no longer heats for Tuesday 17:00
      And no further standing-slot charge is raised for apartment 12
      And no derived view disagrees with any other about the slot

  # -------------------------------------------------------------------------------
  # Two households, one slot. Added 2026-09-08.
  # -------------------------------------------------------------------------------
  # Every scenario above this line is sequential: each puts the world into a state and
  # then makes one request against it. @item-01-004 already says a held slot cannot be
  # confirmed for a second household, and it says it about a slot that was already held
  # when the second request arrived. Nothing here had ever asked what happens when both
  # requests are in flight, which is the first quality question anybody asks of a booking
  # system and the one this spec had never asked.
  #
  # NO DURATION IS STATED, deliberately. loyly carries no implementation at all, and
  # saunavuoro's coldrun/grounded is a workshop build rather than a build of this slice,
  # so there is nothing here whose latency would mean anything yet. A latency invented at
  # a desk is worse than none, because the first red run gets explained away and the whole
  # idea of a budget goes with it. The two numbers below are the ones that can be derived
  # without an implementation to run, and each is stated so that the scenario can go red.
  #
  # THE RACE WAS RUN BEFORE IT WAS WRITTEN, against saunavuoro's coldrun/grounded, which
  # keeps its bookings in a plain list behind a ThreadingHTTPServer with no lock anywhere.
  # Two threads released from one barrier, both asking for the open 18:30 occurrence in
  # pukuhuone 8, on a fresh date each run. Two hundred runs on 2026-09-08, in two sessions
  # of a hundred: exactly one confirmation every time and never two. So the budget is
  # green where it can be run today, which is what a budget should be on the day it is
  # written. Green is not proof that there is no race, it is the bound a hundred runs
  # buys, and the note under the second scenario says how large that bound is.
  Rule: Two households requesting one free slot leave exactly one holder

    @item-01-009
    Scenario: Two households request the same free slot with neither request answered
      Given Tuesday at 17:00 in pukuhuone 8 is free
      And neither the household in apartment 12 nor the household in apartment 3 holds it
      When both households request a standing slot on Tuesday at 17:00 in pukuhuone 8
      And the second request is made before the first has been answered
      Then exactly one of the two requests is confirmed
      And the other is refused because the slot is held
      And exactly one household holds the Tuesday 17:00 standing slot in pukuhuone 8
      And no derived view shows two holders for it
      # "Before the first has been answered" is what makes this a different scenario from
      # @item-01-004 rather than a reworded copy of it. A test that sends one request,
      # waits for its answer and only then sends the other cannot fail here whatever the
      # implementation does, because it has serialised the very thing it was meant to
      # test. The condition is written as an ordering rather than as a number of
      # milliseconds so that it stays true on a machine faster than any this will run on.

    @item-01-010
    Scenario: The race is run one hundred times and never confirms twice
      Given the slot is free again at the start of every run
      When the two household race above is run 100 times
      Then all 100 runs confirm exactly one household
      And no run confirms both
      And no run refuses both
      # ONE HUNDRED, derived and not chosen. A race is not caught by running it once: a
      # single run catches only a defect that fires almost every time, and the races that
      # reach production are the ones that fire occasionally. To catch a defect that fires
      # on three attempts in a hundred, with ninety-five percent confidence, takes
      # ln(0.05) / ln(0.97) = 98.4 runs, so a hundred. The number does the other job too:
      # at a hundred runs, one failure is not noise to be re-run, because a system with no
      # race produces zero, not a small number.

  # -------------------------------------------------------------------------------
  # A budget that is judged rather than measured. Added 2026-09-08.
  # -------------------------------------------------------------------------------
  # Not every quality wants a number, and forcing one onto this quality would produce a
  # number nobody believes. What a judged budget still needs is a named judge and a stated
  # failure, or it has gone back to being an intention.
  #
  # WHAT IT COSTS WHEN IT FAILS, taken from the corpus rather than estimated.
  # raw/domain/soukanvuori/2022-05-booking-negotiation.md is four messages over seven
  # days, 2022-04-27 to 2022-05-04, to book one slot. The manager's reply is the reason it
  # was seven days and not fourteen: it refused Tuesday in pukuhuone 9, said which days
  # that dressing room does open, and offered pukuhuone 8 at 17.00, so the resident's next
  # message could be an acceptance instead of a second question. A refusal that only says
  # no costs another round trip, and a round trip in this domain is measured in days.
  Rule: A refusal is judged by whether a resident can act on it without asking again

    @item-01-011
    Scenario: The refusal for a closed day is judged usable
      Given the household in apartment 12 has been refused a standing slot on Tuesday at 17:00 in pukuhuone 9
      When one person who has not read the sauna day configuration reads the refusal
      Then they can name a request that would be accepted, using nothing but the refusal
      And a refusal they cannot act on fails this budget, and its wording is changed before the slice is called done
      # WHO JUDGES: one person, at the slice review, who has not read the configuration.
      # That last condition is the whole check. Anybody who already knows the schedule can
      # act on a bare "no", so a judge who knows the answer cannot fail this scenario, and
      # a scenario that cannot fail is the defect this file is being repaired for.
