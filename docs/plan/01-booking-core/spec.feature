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
