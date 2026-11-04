Feature: Projection integrity
  The posted slot list, heater schedule, and billing signal are projections of the
  same booking lifecycle. They must change together when a standing slot is
  confirmed or released. These scenarios retain their original stable item IDs;
  moving them into slice 02 does not create new behavior identities.

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
