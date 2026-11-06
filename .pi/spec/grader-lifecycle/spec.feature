Feature: Grader lifecycle
  The product owner's accepted scenarios of this context. Each is stamped with an @item id
  that is never renumbered or reused; the checklist is one item per stamped scenario. Drafts
  not yet accepted stay in drafts.feature.

  Rule: A golden moves only with one recorded reason, and the scenario it was written from

    # instrument-golden.allium, rule MoveInstrumentGolden
    # Legacy: README "The goldens, and the three reasons one can change"
    @minimum @allium-rule-success.MoveInstrumentGolden
    @item-03-001
    Scenario: The instrument maintainer moves a golden for a recorded reason
      Given instrument golden G-score-unknown was written with the digest "g-40" from a scenario with the scenario digest "s-12"
      When the instrument maintainer moves G-score-unknown to the digest "g-41" for "instrument behavior changed" with the note "the refusal now names the grader digest" and the scenario digest "s-12"
      Then G-score-unknown has the digest "g-41"
      And its move from "g-40" to "g-41" is recorded with that reason, that note and the scenario digest "s-12"

    # instrument-golden.allium, rule MoveInstrumentGolden (requires 3); invariant SpecGrewIsAdditionsOnly
    # Legacy: README "The goldens, and the three reasons one can change" (spec grew is additions only)
    @minimum @allium-rule-failure.MoveInstrumentGolden.3 @allium-invariant.SpecGrewIsAdditionsOnly
    @item-03-002
    Scenario: "Spec grew" over a diff that is not additions only is refused
      Given instrument golden G-checklist has the digest "g-50"
      And its new content changes the title of an existing stamped item
      When the instrument maintainer moves G-checklist to the digest "g-51" for "spec grew", with a diff that is not additions only
      Then the move is refused
      And G-checklist still has the digest "g-50"

    # instrument-golden.allium, rule MoveInstrumentGolden (requires 4)
    # Legacy: README "The goldens, and the three reasons one can change"
    @minimum @allium-rule-failure.MoveInstrumentGolden.4
    @item-03-003
    Scenario: A move without a note is refused
      Given instrument golden G-score-unknown has the digest "g-40"
      When the instrument maintainer moves G-score-unknown to the digest "g-41" for "instrument behavior changed" with an empty note
      Then the move is refused
      And G-score-unknown still has the digest "g-40"

    # instrument-golden.allium, surface InstrumentGoldenDesk, guarantee NeverMovedToTurnItGreen
    # Legacy: README "What a red oracle costs"
    @minimum @allium-guarantee.NeverMovedToTurnItGreen
    @item-03-004
    Scenario: A golden is never moved for a reason outside the three
      Given instrument golden G-score-unknown has the digest "g-40"
      When the instrument maintainer moves G-score-unknown to the digest "g-41" for "make the self-check green" with the note "it was red"
      Then the move is refused, as the reason is none of "instrument behavior changed", "wording replaced" or "spec grew"
      And G-score-unknown still has the digest "g-40"
