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

  Rule: An instrument golden is written from its scenario and carries that scenario's digest

    # instrument-golden.allium, rule WriteInstrumentGolden (requires 2)
    @minimum @allium-rule-failure.WriteInstrumentGolden.2
    @item-03-005
    Scenario: A golden whose scenario digest is not its scenario's is refused
      Given the instrument scenario "a refused score names the unknown item" has the scenario digest "s-12"
      When the instrument maintainer writes instrument golden G-score-unknown from that scenario with the scenario digest "s-11"
      Then the writing is refused
      And there is no instrument golden G-score-unknown

  Rule: A golden moves only with one recorded reason, and the scenario it was written from

    # instrument-golden.allium, rule MoveInstrumentGolden (requires 2); invariant AMoveMovesSomething
    @minimum @allium-rule-failure.MoveInstrumentGolden.2 @allium-invariant.AMoveMovesSomething
    @item-03-006
    Scenario: A move to the same digest is refused
      Given instrument golden G-score-unknown has the digest "g-40"
      When the instrument maintainer moves G-score-unknown to the digest "g-40" for "wording replaced" with the note "no change"
      Then the move is refused
      And G-score-unknown records no move

    # instrument-golden.allium, rule MoveInstrumentGolden (requires 5)
    @minimum @allium-rule-failure.MoveInstrumentGolden.5
    @item-03-007
    Scenario: A move that names a stale scenario digest is refused
      Given instrument golden G-score-unknown was written from a scenario whose scenario digest is now "s-13"
      When the instrument maintainer moves G-score-unknown to the digest "g-41" for "wording replaced" with the note "reworded" and the scenario digest "s-12"
      Then the move is refused
      And G-score-unknown still has the digest "g-40" and the scenario digest it was written with

  Rule: An instrument golden is written from its scenario and carries that scenario's digest

    # instrument-golden.allium, rule WriteInstrumentGolden; surface InstrumentGoldenDesk, guarantee WrittenFromTheScenarioNeverFromARun
    @minimum @allium-rule-success.WriteInstrumentGolden @allium-guarantee.WrittenFromTheScenarioNeverFromARun
    @item-03-008
    Scenario: The instrument maintainer writes a golden from its scenario
      Given the instrument scenario "a refused score names the unknown item" has the scenario digest "s-12"
      When the instrument maintainer writes instrument golden G-score-unknown from that scenario, with the scenario digest "s-12" and the digest "g-40"
      Then instrument golden G-score-unknown is written with the digest "g-40" and the scenario digest "s-12"
      And the instrument goldens show G-score-unknown with the digest "g-40" and the scenario digest "s-12"

  Rule: An instrument golden is written from its scenario and carries that scenario's digest

    # instrument-golden.allium, invariant OneGoldenPerIdentity
    @balance @allium-invariant.OneGoldenPerIdentity
    @item-03-009
    Scenario: A golden is written once
      Given instrument golden G-score-unknown was written with the digest "g-40"
      When the instrument maintainer writes instrument golden G-score-unknown again with the digest "g-41"
      Then the writing is refused
      And there is one instrument golden G-score-unknown, with the digest "g-40"

  Rule: A golden moves only with one recorded reason, and the scenario it was written from

    # instrument-golden.allium, rule MoveInstrumentGolden (requires 1)
    @balance @allium-rule-failure.MoveInstrumentGolden.1
    @item-03-010
    Scenario: Moving a golden that was never written is refused
      Given no instrument golden G-unknown was ever written
      When the instrument maintainer moves G-unknown to the digest "g-41" for "spec grew"
      Then the move is refused
      And there is no instrument golden G-unknown

    # grader-lifecycle/allium/context.allium, contract ScenarioDigests, invariant DigestChangesWithTheBehaviour
    @balance @allium-boundary.DigestChangesWithTheBehaviour
    @item-03-011
    Scenario: Rewording a scenario changes its scenario digest and keeps its item identity
      Given the instrument scenario "@item-03-004" has the scenario digest "s-12"
      And instrument golden G-score-unknown was written from it with the scenario digest "s-12"
      When the scenario "@item-03-004" is reworded, so its scenario digest is now "s-13"
      And the instrument maintainer moves G-score-unknown to the digest "g-41" for "wording replaced" with the scenario digest "s-13"
      Then the instrument goldens show G-score-unknown with the digest "g-41" and the scenario digest "s-13"
