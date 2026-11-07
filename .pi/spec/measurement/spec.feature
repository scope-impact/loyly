Feature: Measurement
  The product owner's accepted scenarios of this context. Each is stamped with an @item id
  that is never renumbered or reused; the checklist is one item per stamped scenario. Drafts
  not yet accepted stay in drafts.feature.

  Rule: The checklist is derived from the stamped scenarios at a pinned source cut, never written

    # checklist.allium, rule DeriveChecklist; rules NoteItemIdentity and NotePinnedCut; invariant TotalCountsEveryItem; guarantee DerivedNeverWritten
    # Legacy: @harness-012
    @minimum @allium-rule-success.DeriveChecklist @allium-rule-success.NoteItemIdentity @allium-rule-success.NotePinnedCut @allium-invariant.TotalCountsEveryItem @allium-guarantee.DerivedNeverWritten
    @item-02-001
    Scenario: The checklist holds one stamped item per stamped scenario of the source cut
      Given source cut K-3 was pinned
      And the stamper handed out item identities 03-001, 03-002 and 04-001
      And slices 03 and 04 hold exactly those three stamped scenarios at K-3
      When the operator derives the checklist of K-3
      Then the checklist of K-3 is derived with the stamped items 03-001, 03-002 and 04-001
      And its total is 3
      And its checklist digest is the digest of those three stamped items

    # checklist.allium, rule DeriveChecklist; contract ScenarioReading (specification); guarantee OneReadingOfTheSpec
    # Legacy: @harness-014, @harness-015, @harness-016, @harness-017, @harness-018
    @minimum @allium-guarantee.OneReadingOfTheSpec
    @item-02-002
    Scenario: The checklist reads the spec as the stamper does: file order, Rule, source line and trimmed title
      Given source cut K-3 was pinned
      And slice 03's spec file holds, in this order, stamped scenario 03-002 under no Rule and stamped scenario 03-001 under the Rule "Stamping" on line 6 with the title "  a padded title  "
      When the operator derives the checklist of K-3
      Then the first stamped item is 03-002 with no Rule
      And the second stamped item is 03-001 with the Rule "Stamping", source line 6 and the title "a padded title"
      And the stamped items are never sorted by item identity

    # checklist.allium, rule DeriveChecklist; guarantee ShrunkDenominatorIsOnRecord
    # Legacy: @harness-027
    @minimum @allium-guarantee.ShrunkDenominatorIsOnRecord
    @item-02-003
    Scenario: A planned or backlog slice left out of the checklist is on record
      Given source cut K-3 was pinned
      And slice 03 has the status "planned", slice 04 the status "backlog" and slice 05 no status
      When the operator derives the checklist of K-3
      Then the checklist holds only the stamped items of slice 05
      And the checklist records slice 03 skipped as planned and slice 04 skipped as backlog

    # checklist.allium, rule DeriveChecklist (requires 2)
    # Legacy: @harness-023, @harness-024
    @minimum @allium-rule-failure.DeriveChecklist.2
    @item-02-004
    Scenario: Every unstamped scenario of every file refuses the checklist and is listed
      Given source cut K-3 was pinned
      And slice 01's spec file holds an unstamped scenario "unstamped one" and slice 02's spec file holds an unstamped scenario "unstamped two"
      When the operator derives the checklist of K-3
      Then deriving the checklist is refused
      And the refusal lists both unstamped scenarios with their slice spec files and names the stamper
      And no checklist exists for K-3

    # checklist.allium, rule DeriveChecklist (requires 3); invariant ChecklistNeverEmpty
    # Legacy: @harness-022, @harness-025, @harness-026
    @minimum @allium-rule-failure.DeriveChecklist.3 @allium-invariant.ChecklistNeverEmpty
    @item-02-005
    Scenario: A checklist that would hold no stamped item is refused
      Given source cut K-3 was pinned
      And every slice at K-3 has the status "planned" or "backlog"
      When the operator derives the checklist of K-3
      Then deriving the checklist is refused because it would be empty
      And no checklist exists for K-3

    # checklist.allium, rule DeriveChecklist (requires 4)
    # Legacy: @harness-012
    @minimum @allium-rule-failure.DeriveChecklist.4
    @item-02-006
    Scenario: A stamped item whose item identity the stamper never handed out is refused
      Given source cut K-3 was pinned
      And the stamper handed out item identity 03-001 only
      And slice 03's spec file at K-3 carries a hand-written tag for item identity 03-007
      When the operator derives the checklist of K-3
      Then deriving the checklist is refused naming item identity 03-007
      And no checklist exists for K-3

    # checklist.allium, rule DeriveChecklist (requires 7)
    # Legacy: @harness-021, @harness-049
    @minimum @allium-rule-failure.DeriveChecklist.7
    @item-02-007
    Scenario: A stamped item with an empty title is refused
      Given source cut K-3 was pinned
      And the stamper handed out item identity 03-001
      When the operator derives the checklist of K-3 offering stamped item 03-001 with an empty title
      Then deriving the checklist is refused
      And no checklist exists for K-3

  Rule: The approved sealed grader files the item verdicts it returned for the gated candidate only

    # cycle-measurement.allium, rule FileItemVerdicts (requires 3)
    # Legacy: @harness-040
    @minimum @allium-rule-failure.FileItemVerdicts.3
    @item-02-008
    Scenario: Item verdicts for a cycle whose source cut has no checklist are refused
      Given gate attempt 1 of cycle C-7 passed for candidate D-1 at source cut K-3
      And no checklist was derived for K-3
      When the grader with approved digest G-1 files item verdicts for candidate D-1 of C-7
      Then filing the item verdicts is refused as no checklist
      And C-7's measurement has no item verdicts filed

    # cycle-measurement.allium, rule FileItemVerdicts (requires 4); surface ScoreStage, guarantee ZeroVerdictsIsNotAllFailed
    # Legacy: @harness-032, @harness-033
    @minimum @allium-rule-failure.FileItemVerdicts.4 @allium-guarantee.ZeroVerdictsIsNotAllFailed
    @item-02-009
    Scenario: Zero item verdicts file nothing, never every item failed
      Given gate attempt 1 of cycle C-7 passed for candidate D-1 and the checklist of its source cut holds 03-001
      When the grader with approved digest G-1 files an empty list of item verdicts for D-1 of C-7
      Then filing the item verdicts is refused as zero verdicts
      And C-7's measurement has no item verdicts filed and shows no score

    # cycle-measurement.allium, rule FileItemVerdicts (requires 5)
    # Legacy: @harness-039
    @minimum @allium-rule-failure.FileItemVerdicts.5
    @item-02-010
    Scenario: A verdict that is neither pass nor fail is refused before an unknown item
      Given gate attempt 1 of cycle C-7 passed for candidate D-1 and the checklist of its source cut holds 03-001
      When the grader with approved digest G-1 files the verdict "maybe" for 03-001 and pass for the unknown item 09-001
      Then filing the item verdicts is refused as a bad verdict value
      And C-7's measurement has no item verdicts filed

    # cycle-measurement.allium, rule FileItemVerdicts (requires 6)
    # Legacy: @harness-038
    @minimum @allium-rule-failure.FileItemVerdicts.6
    @item-02-011
    Scenario: A verdict for an item not on the checklist is refused
      Given gate attempt 1 of cycle C-7 passed for candidate D-1 and the checklist of its source cut holds 03-001
      When the grader with approved digest G-1 files pass for 03-001 and pass for 09-001
      Then filing the item verdicts is refused as an unknown item, naming 09-001
      And C-7's measurement has no item verdicts filed

  Rule: The score files one measurement of every checklist item; an unreached item fails and stays counted

    # cycle-measurement.allium, rule FileScore; invariants CountedWithinTheDenominator and FiledFromAtLeastOneFiling; contract Scoring, invariant EveryChecklistItemInChecklistOrder
    # Legacy: @harness-029
    @minimum @allium-rule-success.FileScore @allium-invariant.CountedWithinTheDenominator @allium-invariant.FiledFromAtLeastOneFiling @allium-boundary.EveryChecklistItemInChecklistOrder
    @item-02-012
    Scenario: The measurement holds every checklist item once, in checklist order
      Given the checklist of source cut K-3 holds 03-001, 03-002 and 04-001, in that order
      And gate attempt 1 of cycle C-7 at K-3 passed for candidate D-1
      And the grader with approved digest G-1 filed fail for 04-001, pass for 03-001 and pass for 03-002 for D-1
      When ORC runtime files the score of C-7 with the approved grader digests G-1
      Then the measurement of C-7 is filed with 03-001, 03-002 and 04-001 in that order
      And its total is 3, its passes 2 and its unreached 0
      And it carries the checklist digest of K-3 and the grader digest G-1

    # cycle-measurement.allium, contract Scoring, invariant UnreachedIsAFailAndStaysCounted
    # Legacy: @harness-036, @harness-037
    @minimum @allium-boundary.UnreachedIsAFailAndStaysCounted
    @item-02-013
    Scenario: An item with no verdict is filed as fail with the reason unreached and counted
      Given the checklist of source cut K-3 holds 03-001, 03-002 and 03-003
      And gate attempt 1 of cycle C-7 at K-3 passed for candidate D-1
      And the grader with approved digest G-1 filed pass for 03-001 only
      When ORC runtime files the score of C-7
      Then the measurement of C-7 files 03-002 and 03-003 as fail with the reason unreached
      And its total is 3 and its unreached is 2

    # cycle-measurement.allium, contract Scoring, invariant ReasonBesideTheVerdict
    # Legacy: @harness-036
    @minimum @allium-boundary.ReasonBesideTheVerdict
    @item-02-014
    Scenario: An outage reads differently from a regression, and the verdict stays pass or fail
      Given the checklist of source cut K-3 holds 03-001 and 03-002
      And gate attempt 1 of cycle C-7 at K-3 passed for candidate D-1
      And the grader with approved digest G-1 filed fail with the reason "outage" for 03-001 and fail with no reason for 03-002
      When ORC runtime files the score of C-7
      Then the measurement of C-7 files 03-001 as fail with the reason outage
      And files 03-002 as fail with no reason

    # cycle-measurement.allium, contract Scoring, invariant LastVerdictWinsAndTheRestAreListed
    # Legacy: @harness-031
    @minimum @allium-boundary.LastVerdictWinsAndTheRestAreListed
    @item-02-015
    Scenario: Duplicate verdicts: the last filed stands and the earlier ones are listed as superseded
      Given the checklist of source cut K-3 holds 03-001
      And gate attempt 1 of cycle C-7 at K-3 passed for candidate D-1
      And the grader with approved digest G-1 filed fail for 03-001 and then pass for 03-001
      When ORC runtime files the score of C-7
      Then the measurement of C-7 files 03-001 as pass
      And lists the fail for 03-001 as superseded, with the grader digest G-1

    # cycle-measurement.allium, contract Scoring, invariant EvidenceDefaultsEmpty
    # Legacy: @harness-030
    @minimum @allium-boundary.EvidenceDefaultsEmpty
    @item-02-016
    Scenario: A verdict filed without evidence carries empty evidence
      Given the checklist of source cut K-3 holds 03-001
      And gate attempt 1 of cycle C-7 at K-3 passed for candidate D-1
      And the grader with approved digest G-1 filed pass for 03-001 with no evidence
      When ORC runtime files the score of C-7
      Then the measurement of C-7 files 03-001 as pass with empty evidence

    # cycle-measurement.allium, rule FileScore (requires 2)
    # Legacy: @harness-032
    @minimum @allium-rule-failure.FileScore.2
    @item-02-017
    Scenario: A score with zero item verdicts filed is refused
      Given gate attempt 1 of cycle C-7 passed for candidate D-1
      And no grader filed item verdicts for C-7
      When ORC runtime files the score of C-7
      Then filing the score is refused as zero verdicts
      And C-7's measurement is still awaiting its score

  Rule: The scoreboard is reported once a cycle, to a person, never into the loop

    # scoreboard.allium, rules ReportScoreboardOnce and ReportScoreboard; invariant PassesWithinTotal; guarantee ToAPersonNeverIntoTheLoop
    # Legacy: @harness-041
    @minimum @allium-rule-success.ReportScoreboardOnce @allium-rule-success.ReportScoreboard @allium-invariant.PassesWithinTotal @allium-guarantee.ToAPersonNeverIntoTheLoop
    @item-02-018
    Scenario: A baseline cycle's scoreboard rates every item and has no previous cycle
      Given no cycle of the whole-plan selection was measured before cycle C-7
      When the measurement of C-7 is filed with 2 passes of 3
      Then the report-scoreboard-once policy reports the scoreboard of C-7 to the operator
      And the scoreboard shows 2 passes of 3 and the rate "66.7%"
      And it has no previous cycle

    # scoreboard.allium, rule ReportScoreboard (requires 1)
    # Legacy: @harness-043
    @minimum @allium-rule-failure.ReportScoreboard.1
    @item-02-019
    Scenario: A scoreboard for a cycle with no measurement filed is refused
      Given the score stage of cycle C-8 failed
      When the report-scoreboard-once policy reports the scoreboard of C-8
      Then reporting the scoreboard is refused because no measurement is filed
      And C-8 has no scoreboard

  Rule: A cycle is compared with the last measured cycle of the same selection, both directions apart, never a net

    # scoreboard.allium, guarantee ComparedWithTheLastMeasuredCycle; contract Comparison, invariant LastMeasuredCycleOfTheSameSelection; invariant NeverComparedWithItself
    # Legacy: @harness-042
    @minimum @allium-guarantee.ComparedWithTheLastMeasuredCycle @allium-boundary.LastMeasuredCycleOfTheSameSelection @allium-invariant.NeverComparedWithItself
    @item-02-020
    Scenario: A cycle after an unscored one is compared with the last measured cycle, not the one before it
      Given cycle C-5 of the whole-plan selection was measured
      And cycle C-6 of the whole-plan selection failed at the gate and has no measurement
      When the measurement of cycle C-7 of the whole-plan selection is filed
      Then the scoreboard of C-7 is compared with C-5
      And never with C-6 or with C-7 itself

    # scoreboard.allium, guarantee BothDirectionsNeverANet; contract Comparison, invariant BothDirectionsOnTheSharedItemsNeverANet
    # Legacy: @harness-045
    @minimum @allium-guarantee.BothDirectionsNeverANet @allium-boundary.BothDirectionsOnTheSharedItemsNeverANet
    @item-02-021
    Scenario: Newly passing and newly failing shared items are counted apart, with no net
      Given cycle C-6 of the whole-plan selection was measured with 03-001 pass and 03-002 fail
      When the measurement of cycle C-7 of the whole-plan selection is filed with 03-001 fail and 03-002 pass
      Then the scoreboard of C-7 lists 03-002 as newly passing and 03-001 as newly failing
      And it shows the shared items' rates for C-6 and C-7
      And it computes no net

    # scoreboard.allium, rule ReportScoreboard (ensures MovedItemSetRestated); guarantee AMovedItemSetIsRestatedOutLoud
    # Legacy: @harness-047
    @minimum @allium-guarantee.AMovedItemSetIsRestatedOutLoud
    @item-02-022
    Scenario: The comparison is restated on the shared items only when the item set moved
      Given cycle C-6 of the whole-plan selection was measured with a total of 3
      When the measurement of cycle C-7 of the whole-plan selection is filed with a total of 4, one item added
      Then the scoreboard of C-7 restates the comparison on the shared items, with previous total 3, total 4, 1 added and 0 dropped
      But a cycle whose item set did not move is reported with no restatement

  Rule: A rate has one decimal, rounded half to even, and "n/a" over zero

    # context.allium, contract RateFormat, invariant OneDecimalHalfToEven
    # Legacy: @harness-046
    @minimum @allium-boundary.OneDecimalHalfToEven
    @item-02-023
    Scenario: A rate exactly on a half rounds to the even digit
      Given no cycle of the whole-plan selection was measured before cycle C-7
      When the measurement of C-7 is filed with 1 pass of 16
      Then the scoreboard of C-7 shows the rate "6.2%"
      And a measurement with 5 passes of 16 shows the rate "31.2%"

    # context.allium, contract RateFormat, invariant ZeroDenominatorIsNotApplicable
    # Legacy: @harness-048
    @minimum @allium-boundary.ZeroDenominatorIsNotApplicable
    @item-02-024
    Scenario: A previous cycle with no shared item rates the shared items "n/a"
      Given cycle C-6 of the whole-plan selection was measured with only 03-001
      When the measurement of cycle C-7 of the whole-plan selection is filed with only 04-001
      Then the scoreboard of C-7 shows the shared items' rates as "n/a"
      And computes no percentage over zero shared items
