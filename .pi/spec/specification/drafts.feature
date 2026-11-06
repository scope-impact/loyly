Feature: Specification, draft scenarios
  Drafts for the Allium obligations of this context (2026-11-06): each rule's success, each
  refusal that is behaviour, each invariant, each contract invariant and each guarantee of
  allium/ that no gate verifies. The harness does not read this file. Only the product owner moves a draft into
  spec.feature, under the Rule it names, where the stamper gives it an @item id and the @draft
  tag is dropped. Every noun is a term of .pi/model/GLOSSARY.md (the stamper and the spec
  author are the event model's words for the operator stamping a slice).

  Rule: Stamping again continues the slice's numbering and never reuses a number

    # slice.allium, invariant OneLedgerPerSlice
    # Suggested tier: balance
    @draft @allium-invariant.OneLedgerPerSlice
    Scenario: A slice that was stamped before keeps one numbering
      Given slice 03 has stamped "@item-03-001" and "@item-03-002"
      And a new unstamped scenario "keeps one numbering" was added to "plan/03-gate/spec.feature"
      When the operator stamps slice 03
      Then "keeps one numbering" is stamped "@item-03-003", not "@item-03-001"

  Rule: Stamping refuses a spec it cannot read as the slice's own

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
