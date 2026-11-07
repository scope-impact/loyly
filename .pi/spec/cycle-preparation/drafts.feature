Feature: Cycle preparation, draft scenarios
  Drafts for the Allium obligations of this context (2026-11-06): each rule's success, each
  refusal that is behaviour, each invariant, each contract invariant and each guarantee of
  allium/ that no gate verifies. The harness does not read this file. Only the product owner moves a draft into
  spec.feature, under the Rule it names, where the stamper gives it an @item id and the @draft
  tag is dropped. Every noun is a term of .pi/model/GLOSSARY.md.

  Rule: A source cut is one commit and its tree, pinned once and never changed

    # cycle-preparation/allium/context.allium, contract ProjectRepository, invariant TreeContentIsTheCommits
    # Suggested tier: balance
    @draft @allium-boundary.TreeContentIsTheCommits
    Scenario: Two source cuts of one commit carry the same tree digest
      Given source cut K-3 was pinned at commit "4f2a9c1e0b7d3a5f9c2e8b1d4a6f0c3e7b9d2a51" with the tree digest "t-81"
      When the operator pins source cut K-5 at commit "4f2a9c1e0b7d3a5f9c2e8b1d4a6f0c3e7b9d2a51" with the tree digest "t-81"
      Then source cut K-5 is pinned with the tree digest "t-81"
      And the pinned source cuts show source cuts K-3 and K-5 at that commit, with the same tree digest
