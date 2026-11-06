Feature: Cycle preparation, draft scenarios
  Drafts for the Allium obligations of this context (2026-11-06): each rule's success, each
  refusal that is behaviour, each invariant, each contract invariant and each guarantee of
  allium/. The harness does not read this file. Only the product owner moves a draft into
  spec.feature, under the Rule it names, where the stamper gives it an @item id and the @draft
  tag is dropped. Every noun is a term of .pi/model/GLOSSARY.md.

  Rule: A source cut is one commit and its tree, pinned once and never changed

    # source-cut.allium, rule PinSourceCut
    # Suggested tier: minimum
    @draft @allium-rule-success.PinSourceCut
    Scenario: The operator pins a source cut
      Given commit "4f2a9c1" of the project repository has the tree digest "t-81"
      When the operator pins source cut K-3 at commit "4f2a9c1" with the tree digest "t-81"
      Then source cut K-3 is pinned at commit "4f2a9c1" with the tree digest "t-81"
      And the checklist derivation and the regeneration cycle can each bind to source cut K-3

    # source-cut.allium, surface Preparation, guarantee ImmutableOncePinned; invariant OneCutPerIdentity
    # Suggested tier: minimum
    @draft @allium-guarantee.ImmutableOncePinned @allium-invariant.OneCutPerIdentity
    Scenario: A pinned source cut is never pinned again
      Given source cut K-3 was pinned at commit "4f2a9c1" with the tree digest "t-81"
      And commit "9b07e2d" of the project repository has the tree digest "t-82"
      When the operator pins source cut K-3 at commit "9b07e2d" with the tree digest "t-82"
      Then the pinning is refused
      And source cut K-3 is still the one source cut K-3, at commit "4f2a9c1" with the tree digest "t-81"

    # source-cut.allium, rule PinSourceCut (requires 2)
    # Suggested tier: minimum
    @draft @allium-rule-failure.PinSourceCut.2
    Scenario: A source cut of something that is not a commit is refused
      Given "not-a-commit" is no commit of the project repository
      When the operator pins source cut K-4 at "not-a-commit" with the tree digest "t-81"
      Then the pinning is refused
      And no source cut K-4 is pinned

    # source-cut.allium, rule PinSourceCut (requires 3)
    # Suggested tier: minimum
    @draft @allium-rule-failure.PinSourceCut.3
    Scenario: A source cut whose tree digest is not its commit's tree is refused
      Given commit "4f2a9c1" of the project repository has the tree digest "t-81"
      When the operator pins source cut K-4 at commit "4f2a9c1" with the tree digest "t-99"
      Then the pinning is refused
      And no source cut K-4 is pinned

    # cycle-preparation/allium/context.allium, contract ProjectRepository, invariant TreeContentIsTheCommits
    # Suggested tier: balance
    @draft @allium-boundary.TreeContentIsTheCommits
    Scenario: Two source cuts of one commit carry the same tree digest
      Given source cut K-3 was pinned at commit "4f2a9c1" with the tree digest "t-81"
      When the operator pins source cut K-5 at commit "4f2a9c1"
      Then source cut K-5 is pinned with the tree digest "t-81"
