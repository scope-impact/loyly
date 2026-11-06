Feature: Specification, draft scenarios
  Drafts for the Allium obligations of this context (2026-11-06): each rule's success, each
  refusal that is behaviour, each invariant, each contract invariant and each guarantee of
  allium/. The harness does not read this file. Only the product owner moves a draft into
  spec.feature, under the Rule it names, where the stamper gives it an @item id and the @draft
  tag is dropped. Every noun is a term of .pi/model/GLOSSARY.md (the stamper and the spec
  author are the event model's words for the operator stamping a slice).

  Rule: A slice's first stamping hands out its item identities from 1, in file order

    # slice.allium, rule StampFirstScenarios
    # Legacy: @harness-001
    # Suggested tier: minimum
    @draft @allium-rule-success.StampFirstScenarios
    Scenario: The first stamping of a slice hands out its identities in file order
      Given no scenario of slice 03 has ever been stamped
      And "plan/03-gate/spec.feature" holds the unstamped scenarios "boots fresh" on line 6 and "refuses a busy seam" on line 12
      When the operator stamps slice 03
      Then "boots fresh" is stamped "@item-03-001" and "refuses a busy seam" is stamped "@item-03-002"
      And the next item identity slice 03 hands out is "@item-03-003"

    # specification/allium/context.allium, contract ScenarioReading, invariant SliceFromTheDirectory
    # Legacy: @harness-001, @harness-014
    # Suggested tier: minimum
    @draft @allium-boundary.SliceFromTheDirectory
    Scenario: The slice is the directory name up to its first dash, verbatim
      Given "plan/03-gate/spec.feature" and "plan/notes/spec.feature" each hold one unstamped scenario
      When the operator stamps every slice
      Then the scenario in "plan/03-gate" is stamped "@item-03-001"
      And "plan/notes" is no slice, and its scenario is not stamped

  Rule: Stamping again continues the slice's numbering and never reuses a number

    # slice.allium, rule StampSlice; surface SpecAuthoring, guarantee NeverRenumberedNeverReused; invariant NumbersBelowTheLedger
    # Legacy: @harness-010 (changed: the next number never goes down)
    # Suggested tier: minimum
    @draft @allium-rule-success.StampSlice @allium-guarantee.NeverRenumberedNeverReused @allium-invariant.NumbersBelowTheLedger
    Scenario: A deleted scenario's number is never handed out again
      Given slice 03 has stamped "@item-03-001" to "@item-03-004"
      And the scenario stamped "@item-03-004" was deleted from "plan/03-gate/spec.feature"
      And the file now holds one unstamped scenario, "refuses a second launch"
      When the operator stamps slice 03
      Then "refuses a second launch" is stamped "@item-03-005"
      And no stamped item of slice 03 is renumbered
      And the next item identity slice 03 hands out is "@item-03-006"

    # slice.allium, invariant OneLedgerPerSlice
    # Suggested tier: balance
    @draft @allium-invariant.OneLedgerPerSlice
    Scenario: A slice that was stamped before keeps one numbering
      Given slice 03 has stamped "@item-03-001" and "@item-03-002"
      And a new unstamped scenario "keeps one numbering" was added to "plan/03-gate/spec.feature"
      When the operator stamps slice 03
      Then "keeps one numbering" is stamped "@item-03-003", not "@item-03-001"

    # slice.allium, invariant IdentityHandedOutOnce
    # Legacy: @harness-008
    # Suggested tier: minimum
    @draft @allium-invariant.IdentityHandedOutOnce
    Scenario: Stamping twice changes nothing
      Given slice 03 has stamped "@item-03-001" and "@item-03-002" and nothing in it is unstamped
      When the operator stamps slice 03 again
      Then no scenario is stamped
      And the stamper reports slice 03 fully stamped
      And each item identity of slice 03 still names exactly one stamped item

    # slice.allium, surface SpecAuthoring, guarantee NeverDerivedFromWording
    # Legacy: @harness-009
    # Suggested tier: minimum
    @draft @allium-guarantee.NeverDerivedFromWording
    Scenario: Rewording a scenario keeps its item identity
      Given "boots fresh" was stamped "@item-03-001"
      And its title was reworded to "boots fresh from its launch entrypoint"
      When the operator stamps slice 03
      Then no scenario is stamped
      And "boots fresh from its launch entrypoint" is still "@item-03-001"

  Rule: Stamping refuses a spec it cannot read as the slice's own

    # slice.allium, rule StampFirstScenarios (requires 1)
    # Legacy: @harness-011
    # Suggested tier: minimum
    @draft @allium-rule-failure.StampFirstScenarios.1
    Scenario: No spec found for a slice never stamped
      Given no scenario of slice 07 has ever been stamped
      And there is no "plan/07-score/spec.feature"
      When the operator stamps slice 07
      Then the stamping is refused as "no spec found", naming "plan/07-score/spec.feature"
      And slice 07 has no stamped item

    # slice.allium, rule StampSlice (requires 1); surface SpecAuthoring, guarantee NoSpecIsNotFullyStamped
    # Legacy: @harness-011
    # Suggested tier: minimum
    @draft @allium-rule-failure.StampSlice.1 @allium-guarantee.NoSpecIsNotFullyStamped
    Scenario: A spec that went missing is "no spec found", never "fully stamped"
      Given slice 03 has stamped "@item-03-001" and "@item-03-002"
      And "plan/03-gate/spec.feature" was removed
      When the operator stamps slice 03
      Then the stamping is refused as "no spec found", naming "plan/03-gate/spec.feature"
      And the stamper does not report slice 03 fully stamped
      And the next item identity slice 03 hands out is still "@item-03-003"

    # slice.allium, rules StampFirstScenarios (requires 2) and StampSlice (requires 2)
    # Suggested tier: balance
    @draft @allium-rule-failure.StampFirstScenarios.2 @allium-rule-failure.StampSlice.2
    Scenario Outline: Another slice's spec file is refused
      Given slice 03 <history>
      And "plan/04-score/spec.feature" holds one unstamped scenario
      When the operator stamps slice 03 from "plan/04-score/spec.feature"
      Then the stamping is refused
      And slice 03 <after>

      Examples:
        | history                                       | after                               |
        | has never been stamped                        | still has no stamped item           |
        | has stamped "@item-03-001" and "@item-03-002" | still hands out "@item-03-003" next |

    # slice.allium, rules StampFirstScenarios (requires 4) and StampSlice (requires 4)
    # Legacy: @harness-007, @harness-008
    # Suggested tier: minimum
    @draft @allium-rule-failure.StampFirstScenarios.4 @allium-rule-failure.StampSlice.4
    Scenario Outline: Only the file's unstamped scenarios, in file order, are stamped
      Given slice 03 <history>
      And "plan/03-gate/spec.feature" holds the unstamped scenarios "a" on line 6 and "b" on line 12
      When the operator stamps slice 03 offering <offered>
      Then the stamping is refused
      And no scenario of slice 03 is stamped by it

      Examples:
        | history                                     | offered                    |
        | has never been stamped                      | "b" before "a"             |
        | has stamped "@item-03-001" as "boots fresh" | "boots fresh", "a" and "b" |

  Rule: An empty-title heading is not an item, and the stamper says so out loud

    # specification/allium/context.allium, contract ScenarioReading, invariant EmptyTitleIsNoScenario; slice.allium, surface SpecAuthoring, guarantee EmptyTitleReportedLoudly
    # Legacy: @harness-021 (changed: neither stamped nor counted, and reported)
    # Suggested tier: minimum
    @draft @allium-boundary.EmptyTitleIsNoScenario @allium-guarantee.EmptyTitleReportedLoudly
    Scenario: A Scenario heading with nothing after the colon is reported, not stamped
      Given no scenario of slice 03 has ever been stamped
      And "plan/03-gate/spec.feature" holds "Scenario: boots fresh" on line 6 and "Scenario:" with nothing after the colon on line 9
      When the operator stamps slice 03
      Then "boots fresh" is stamped "@item-03-001"
      And line 9 is not stamped
      And the stamper reports an empty-title heading in "plan/03-gate/spec.feature" on line 9

    # slice.allium, rules StampFirstScenarios (requires 5) and StampSlice (requires 5)
    # Legacy: @harness-021
    # Suggested tier: minimum
    @draft @allium-rule-failure.StampFirstScenarios.5 @allium-rule-failure.StampSlice.5
    Scenario Outline: A stamping that hides an empty-title heading is refused
      Given slice 03 <history>
      And "plan/03-gate/spec.feature" holds an empty-title heading on line 9 and one unstamped scenario on line 6
      When the operator stamps slice 03 reporting no empty-title heading
      Then the stamping is refused
      And no scenario of slice 03 is stamped by it

      Examples:
        | history                                       |
        | has never been stamped                        |
        | has stamped "@item-03-001" and "@item-03-002" |

  Rule: One reading of Gherkin decides what is a scenario and what stamps it

    # specification/allium/context.allium, contract ScenarioReading, invariant OutlineYesTemplateNo
    # Legacy: @harness-003
    # Suggested tier: minimum
    @draft @allium-boundary.OutlineYesTemplateNo
    Scenario: A Scenario Outline is stamped and a Scenario Template is not
      Given no scenario of slice 03 has ever been stamped
      And "plan/03-gate/spec.feature" holds "Scenario Outline: an outline" on line 6 and "Scenario Template: a template" on line 14
      When the operator stamps slice 03
      Then "an outline" is stamped "@item-03-001"
      And line 14 is not stamped

    # specification/allium/context.allium, contract ScenarioReading, invariant WhitespaceTitleIsAScenario
    # Legacy: @harness-049
    # Suggested tier: minimum
    @draft @allium-boundary.WhitespaceTitleIsAScenario
    Scenario: A heading whose title is only whitespace is a scenario
      Given no scenario of slice 03 has ever been stamped
      And "plan/03-gate/spec.feature" holds "Scenario:" followed by three spaces on line 6
      When the operator stamps slice 03
      Then line 6 is stamped "@item-03-001" with the title " "

    # specification/allium/context.allium, contract ScenarioReading, invariant TitleIsTrimmed
    # Legacy: @harness-018
    # Suggested tier: minimum
    @draft @allium-boundary.TitleIsTrimmed
    Scenario: A stamped item's title is the heading's title with surrounding whitespace stripped
      Given no scenario of slice 03 has ever been stamped
      And "plan/03-gate/spec.feature" holds "    Scenario:   a padded title   " on line 6
      When the operator stamps slice 03
      Then "@item-03-001" is stamped with the title "a padded title"

    # specification/allium/context.allium, contract ScenarioReading, invariant ItemTagFromTheContiguousTagRun
    # Legacy: @harness-005, @harness-019 (@harness-020 dropped)
    # Suggested tier: minimum
    @draft @allium-boundary.ItemTagFromTheContiguousTagRun
    Scenario: An item tag separated from its heading by a blank line does not stamp it
      Given slice 03 has stamped "@item-03-001"
      And in "plan/03-gate/spec.feature" the line "@item-03-001" is followed by a blank line and then "Scenario: drifted away"
      When the operator stamps slice 03
      Then "drifted away" is stamped "@item-03-002"

    # specification/allium/context.allium, contract ScenarioReading, invariant OtherTagsDoNotStamp
    # Legacy: @harness-005
    # Suggested tier: minimum
    @draft @allium-boundary.OtherTagsDoNotStamp
    Scenario: Tags that are not item tags do not make a scenario stamped
      Given no scenario of slice 03 has ever been stamped
      And "plan/03-gate/spec.feature" holds the tag line "@wip @slow" directly above "Scenario: has tags but no item tag"
      When the operator stamps slice 03
      Then "has tags but no item tag" is stamped "@item-03-001"
      And its tags "@wip" and "@slow" are kept
