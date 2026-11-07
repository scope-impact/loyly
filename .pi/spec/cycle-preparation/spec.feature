Feature: Cycle preparation
  The product owner's accepted scenarios of this context. Each is stamped with an @item id
  that is never renumbered or reused; the checklist is one item per stamped scenario. Drafts
  not yet accepted stay in drafts.feature.

  Rule: A source cut is one commit and its tree, pinned once and never changed

    # source-cut.allium, rule PinSourceCut
    @minimum @allium-rule-success.PinSourceCut
    @item-05-001
    Scenario: The operator pins a source cut
      Given commit "4f2a9c1e0b7d3a5f9c2e8b1d4a6f0c3e7b9d2a51" of the project repository has the tree digest "t-81"
      When the operator pins source cut K-3 at commit "4f2a9c1e0b7d3a5f9c2e8b1d4a6f0c3e7b9d2a51" with the tree digest "t-81"
      Then source cut K-3 is pinned at commit "4f2a9c1e0b7d3a5f9c2e8b1d4a6f0c3e7b9d2a51" with the tree digest "t-81"
      And the pinned source cuts show source cut K-3 with that commit and tree digest

    # source-cut.allium, surface Preparation, guarantee ImmutableOncePinned; invariant OneCutPerIdentity
    @minimum @allium-guarantee.ImmutableOncePinned @allium-invariant.OneCutPerIdentity
    @item-05-002
    Scenario: A pinned source cut is never pinned again
      Given source cut K-3 was pinned at commit "4f2a9c1e0b7d3a5f9c2e8b1d4a6f0c3e7b9d2a51" with the tree digest "t-81"
      And commit "9b07e2d4c8a1f6035e9d2b7c4a0f8e1d6b3c5a92" of the project repository has the tree digest "t-82"
      When the operator pins source cut K-3 at commit "9b07e2d4c8a1f6035e9d2b7c4a0f8e1d6b3c5a92" with the tree digest "t-82"
      Then the pinning is refused
      And the pinned source cuts hold one source cut K-3, at commit "4f2a9c1e0b7d3a5f9c2e8b1d4a6f0c3e7b9d2a51" with the tree digest "t-81"

    # source-cut.allium, rule PinSourceCut (requires 2)
    @minimum @allium-rule-failure.PinSourceCut.2
    @item-05-003
    Scenario: A source cut of something that is not a commit is refused
      Given "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef" is no commit of the project repository
      When the operator pins source cut K-4 at "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef" with the tree digest "t-81"
      Then the pinning is refused
      And no source cut K-4 is pinned

    # source-cut.allium, rule PinSourceCut (requires 3)
    @minimum @allium-rule-failure.PinSourceCut.3
    @item-05-004
    Scenario: A source cut whose tree digest is not its commit's tree is refused
      Given commit "4f2a9c1e0b7d3a5f9c2e8b1d4a6f0c3e7b9d2a51" of the project repository has the tree digest "t-81"
      When the operator pins source cut K-4 at commit "4f2a9c1e0b7d3a5f9c2e8b1d4a6f0c3e7b9d2a51" with the tree digest "t-99"
      Then the pinning is refused
      And no source cut K-4 is pinned
