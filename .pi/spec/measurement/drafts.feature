Feature: Measurement, draft scenarios
  Drafts for the Allium obligations of this context (2026-11-06): each rule's success, each
  refusal that is behaviour, each invariant, each contract invariant and each guarantee of
  allium/ that no gate verifies. The harness does not read this file. Only the product owner moves a draft into
  spec.feature, under the Rule it names, where the stamper gives it an @item id and the @draft
  tag is dropped. Every noun is a term of .pi/model/GLOSSARY.md.

  Rule: The checklist is derived from the stamped scenarios at a pinned source cut, never written

    # checklist.allium, rule DeriveChecklist; rules NoteItemIdentity and NotePinnedCut; invariant TotalCountsEveryItem; guarantee DerivedNeverWritten
    # Legacy: @harness-012
    # Suggested tier: minimum
    @draft @allium-rule-success.DeriveChecklist @allium-rule-success.NoteItemIdentity @allium-rule-success.NotePinnedCut @allium-invariant.TotalCountsEveryItem @allium-guarantee.DerivedNeverWritten
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
    # Suggested tier: minimum
    @draft @allium-guarantee.OneReadingOfTheSpec
    Scenario: The checklist reads the spec as the stamper does: file order, Rule, source line and trimmed title
      Given source cut K-3 was pinned
      And slice 03's spec file holds, in this order, stamped scenario 03-002 under the Rule "Stamping" on line 6 with the title "  a padded title  " and stamped scenario 03-001 under no Rule
      When the operator derives the checklist of K-3
      Then the first stamped item is 03-002 with the Rule "Stamping", source line 6 and the title "a padded title"
      And the second stamped item is 03-001 with no Rule
      And the stamped items are never sorted by item identity

    # checklist.allium, rule DeriveChecklist; guarantee ShrunkDenominatorIsOnRecord
    # Legacy: @harness-027
    # Suggested tier: minimum
    @draft @allium-guarantee.ShrunkDenominatorIsOnRecord
    Scenario: A planned or backlog slice left out of the checklist is on record
      Given source cut K-3 was pinned
      And slice 03 has the status "planned", slice 04 the status "backlog" and slice 05 no status
      When the operator derives the checklist of K-3
      Then the checklist holds only the stamped items of slice 05
      And the checklist records slice 03 skipped as planned and slice 04 skipped as backlog

    # checklist.allium, rule DeriveChecklist (requires 1)
    # Suggested tier: balance
    @draft @allium-rule-failure.DeriveChecklist.1
    Scenario: A checklist of a source cut that was never pinned is refused
      Given no source cut K-9 was pinned
      When the operator derives the checklist of K-9
      Then deriving the checklist is refused because the source cut is not pinned
      And no checklist exists for K-9

    # checklist.allium, rule DeriveChecklist (requires 2)
    # Legacy: @harness-023, @harness-024
    # Suggested tier: minimum
    @draft @allium-rule-failure.DeriveChecklist.2
    Scenario: Every unstamped scenario of every file refuses the checklist and is listed
      Given source cut K-3 was pinned
      And slice 01's spec file holds an unstamped scenario "unstamped one" and slice 02's spec file holds an unstamped scenario "unstamped two"
      When the operator derives the checklist of K-3
      Then deriving the checklist is refused
      And the refusal lists both unstamped scenarios with their slice spec files and names the stamper
      And no checklist exists for K-3

    # checklist.allium, rule DeriveChecklist (requires 3); invariant ChecklistNeverEmpty
    # Legacy: @harness-022, @harness-025, @harness-026
    # Suggested tier: minimum
    @draft @allium-rule-failure.DeriveChecklist.3 @allium-invariant.ChecklistNeverEmpty
    Scenario: A checklist that would hold no stamped item is refused
      Given source cut K-3 was pinned
      And every slice at K-3 has the status "planned" or "backlog"
      When the operator derives the checklist of K-3
      Then deriving the checklist is refused because it would be empty
      And no checklist exists for K-3

    # checklist.allium, rule DeriveChecklist (requires 4)
    # Legacy: @harness-012
    # Suggested tier: minimum
    @draft @allium-rule-failure.DeriveChecklist.4
    Scenario: A stamped item whose item identity the stamper never handed out is refused
      Given source cut K-3 was pinned
      And the stamper handed out item identity 03-001 only
      And slice 03's spec file at K-3 carries a hand-written tag for item identity 03-007
      When the operator derives the checklist of K-3
      Then deriving the checklist is refused naming item identity 03-007
      And no checklist exists for K-3

    # checklist.allium, rule DeriveChecklist (requires 5)
    # Suggested tier: balance
    @draft @allium-rule-failure.DeriveChecklist.5
    Scenario: An item identity carried by two scenarios is refused
      Given source cut K-3 was pinned
      And the stamper handed out item identity 03-001
      And two scenarios of slice 03 at K-3 both carry item identity 03-001
      When the operator derives the checklist of K-3
      Then deriving the checklist is refused naming item identity 03-001
      And no checklist exists for K-3

    # checklist.allium, rule DeriveChecklist (requires 7)
    # Legacy: @harness-021, @harness-049
    # Suggested tier: minimum
    @draft @allium-rule-failure.DeriveChecklist.7
    Scenario: A stamped item with an empty title is refused
      Given source cut K-3 was pinned
      And the stamper handed out item identity 03-001
      When the operator derives the checklist of K-3 offering stamped item 03-001 with an empty title
      Then deriving the checklist is refused
      And no checklist exists for K-3

    # checklist.allium, invariant OneChecklistPerCut (rule DeriveChecklist, requires 6)
    # Suggested tier: balance
    @draft @allium-invariant.OneChecklistPerCut
    Scenario: A source cut has one checklist
      Given source cut K-3 was pinned
      And the checklist of K-3 was derived with a total of 3
      When the operator derives the checklist of K-3 again
      Then the instrument desk shows one checklist for K-3, with a total of 3

    # checklist.allium, rule NoteItemIdentity; invariant OneItemPerIdentity
    # Suggested tier: balance
    @draft @allium-invariant.OneItemPerIdentity
    Scenario: An item identity handed over twice is known once
      Given the stamper handed out item identity 03-001 for slice 03
      When Specification hands over the stamping of 03-001 again
      Then the checklist counts item identity 03-001 once

  Rule: The approved sealed grader files the item verdicts it returned for the gated candidate only

    # cycle-measurement.allium, rules OpenMeasurement and FileItemVerdicts; guarantee ApprovedDigestOnly
    # Suggested tier: minimum
    @draft @allium-rule-success.OpenMeasurement @allium-rule-success.FileItemVerdicts @allium-guarantee.ApprovedDigestOnly
    Scenario: The approved grader files its item verdicts for the candidate the gate ratified
      Given source cut K-3 was pinned and its checklist holds 03-001 and 03-002
      And gate attempt 1 of cycle C-7 passed for candidate D-1 at K-3
      When the grader with approved digest G-1 files the verdicts pass for 03-001 and fail for 03-002 for candidate D-1 of C-7
      Then the item verdicts of G-1 for D-1 are filed for C-7, as returned

    # cycle-measurement.allium, rule FileItemVerdicts (requires 1)
    # Suggested tier: balance
    @draft @allium-rule-failure.FileItemVerdicts.1
    Scenario: Item verdicts for a cycle the gate ratified no candidate in are refused
      Given cycle C-8 has no gate attempt that passed
      When the grader with approved digest G-1 files item verdicts for C-8
      Then filing the item verdicts is refused as not the gated candidate
      And C-8 has no measurement

    # cycle-measurement.allium, rule FileItemVerdicts (requires 2); invariant FilingsBindTheGatedCandidate
    # Suggested tier: minimum
    @draft @allium-rule-failure.FileItemVerdicts.2 @allium-invariant.FilingsBindTheGatedCandidate
    Scenario: Item verdicts for another candidate than the gated one are refused
      Given gate attempt 2 of cycle C-7 passed for candidate D-2
      When the grader with approved digest G-1 files item verdicts for candidate D-1 of C-7
      Then filing the item verdicts is refused as not the gated candidate
      And C-7's measurement has no item verdicts filed

    # cycle-measurement.allium, rule FileItemVerdicts (requires 3)
    # Legacy: @harness-040
    # Suggested tier: minimum
    @draft @allium-rule-failure.FileItemVerdicts.3
    Scenario: Item verdicts for a cycle whose source cut has no checklist are refused
      Given gate attempt 1 of cycle C-7 passed for candidate D-1 at source cut K-3
      And no checklist was derived for K-3
      When the grader with approved digest G-1 files item verdicts for candidate D-1 of C-7
      Then filing the item verdicts is refused as no checklist
      And C-7's measurement has no item verdicts filed

    # cycle-measurement.allium, rule FileItemVerdicts (requires 4); surface ScoreStage, guarantee ZeroVerdictsIsNotAllFailed
    # Legacy: @harness-032, @harness-033
    # Suggested tier: minimum
    @draft @allium-rule-failure.FileItemVerdicts.4 @allium-guarantee.ZeroVerdictsIsNotAllFailed
    Scenario: Zero item verdicts file nothing, never every item failed
      Given gate attempt 1 of cycle C-7 passed for candidate D-1 and the checklist of its source cut holds 03-001
      When the grader with approved digest G-1 files an empty list of item verdicts for D-1 of C-7
      Then filing the item verdicts is refused as zero verdicts
      And C-7's measurement has no item verdicts filed and shows no score

    # cycle-measurement.allium, rule FileItemVerdicts (requires 5)
    # Legacy: @harness-039
    # Suggested tier: minimum
    @draft @allium-rule-failure.FileItemVerdicts.5
    Scenario: A verdict that is neither pass nor fail is refused before an unknown item
      Given gate attempt 1 of cycle C-7 passed for candidate D-1 and the checklist of its source cut holds 03-001
      When the grader with approved digest G-1 files the verdict "maybe" for 03-001 and pass for the unknown item 09-001
      Then filing the item verdicts is refused as a bad verdict value
      And C-7's measurement has no item verdicts filed

    # cycle-measurement.allium, rule FileItemVerdicts (requires 6)
    # Legacy: @harness-038
    # Suggested tier: minimum
    @draft @allium-rule-failure.FileItemVerdicts.6
    Scenario: A verdict for an item not on the checklist is refused
      Given gate attempt 1 of cycle C-7 passed for candidate D-1 and the checklist of its source cut holds 03-001
      When the grader with approved digest G-1 files pass for 03-001 and pass for 09-001
      Then filing the item verdicts is refused as an unknown item, naming 09-001
      And C-7's measurement has no item verdicts filed

    # cycle-measurement.allium, rule FileItemVerdicts (requires 7)
    # Suggested tier: balance
    @draft @allium-rule-failure.FileItemVerdicts.7
    Scenario: Item verdicts after the measurement is filed are refused
      Given gate attempt 1 of cycle C-7 passed for candidate D-1
      And the measurement of C-7 was filed with 2 passes of 3
      When the grader with approved digest G-1 files pass for 03-003 for D-1 of C-7
      Then filing the item verdicts is refused
      And the measurement of C-7 still has 2 passes of 3

    # cycle-measurement.allium, rule FileItemVerdicts (requires 9)
    # Suggested tier: minimum
    @draft @allium-rule-failure.FileItemVerdicts.9
    Scenario: Item verdicts from a grader digest that is not approved are refused
      Given gate attempt 1 of cycle C-7 passed for candidate D-1
      And the approved grader digests are G-1
      When the grader with digest G-9 files item verdicts for D-1 of C-7
      Then filing the item verdicts is refused as grader not approved
      And C-7's measurement has no item verdicts filed

    # cycle-measurement.allium, rule FileItemVerdicts (requires 10)
    # Suggested tier: minimum
    @draft @allium-rule-failure.FileItemVerdicts.10
    Scenario: A reason beside a pass, or the reason unreached, from the grader is refused
      Given gate attempt 1 of cycle C-7 passed for candidate D-1 and the checklist of its source cut holds 03-001 and 03-002
      When the grader with approved digest G-1 files pass with the reason "outage" for 03-001 and fail with the reason "unreached" for 03-002
      Then filing the item verdicts is refused as a bad verdict reason
      And C-7's measurement has no item verdicts filed

  Rule: The score files one measurement of every checklist item; an unreached item fails and stays counted

    # cycle-measurement.allium, rule FileScore; invariants CountedWithinTheDenominator and FiledFromAtLeastOneFiling; contract Scoring, invariant EveryChecklistItemInChecklistOrder
    # Legacy: @harness-029
    # Suggested tier: minimum
    @draft @allium-rule-success.FileScore @allium-invariant.CountedWithinTheDenominator @allium-invariant.FiledFromAtLeastOneFiling @allium-boundary.EveryChecklistItemInChecklistOrder
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
    # Suggested tier: minimum
    @draft @allium-boundary.UnreachedIsAFailAndStaysCounted
    Scenario: An item with no verdict is filed as fail with the reason unreached and counted
      Given the checklist of source cut K-3 holds 03-001, 03-002 and 03-003
      And gate attempt 1 of cycle C-7 at K-3 passed for candidate D-1
      And the grader with approved digest G-1 filed pass for 03-001 only
      When ORC runtime files the score of C-7
      Then the measurement of C-7 files 03-002 and 03-003 as fail with the reason unreached
      And its total is 3 and its unreached is 2

    # cycle-measurement.allium, contract Scoring, invariant ReasonBesideTheVerdict
    # Legacy: @harness-036
    # Suggested tier: minimum
    @draft @allium-boundary.ReasonBesideTheVerdict
    Scenario: An outage reads differently from a regression, and the verdict stays pass or fail
      Given the checklist of source cut K-3 holds 03-001 and 03-002
      And gate attempt 1 of cycle C-7 at K-3 passed for candidate D-1
      And the grader with approved digest G-1 filed fail with the reason "outage" for 03-001 and fail with no reason for 03-002
      When ORC runtime files the score of C-7
      Then the measurement of C-7 files 03-001 as fail with the reason outage
      And files 03-002 as fail with no reason

    # cycle-measurement.allium, contract Scoring, invariant LastVerdictWinsAndTheRestAreListed
    # Legacy: @harness-031
    # Suggested tier: minimum
    @draft @allium-boundary.LastVerdictWinsAndTheRestAreListed
    Scenario: Duplicate verdicts: the last filed stands and the earlier ones are listed as superseded
      Given the checklist of source cut K-3 holds 03-001
      And gate attempt 1 of cycle C-7 at K-3 passed for candidate D-1
      And the grader with approved digest G-1 filed fail for 03-001 and then pass for 03-001
      When ORC runtime files the score of C-7
      Then the measurement of C-7 files 03-001 as pass
      And lists the fail for 03-001 as superseded, with the grader digest G-1

    # cycle-measurement.allium, contract Scoring, invariant EvidenceDefaultsEmpty
    # Legacy: @harness-030
    # Suggested tier: minimum
    @draft @allium-boundary.EvidenceDefaultsEmpty
    Scenario: A verdict filed without evidence carries empty evidence
      Given the checklist of source cut K-3 holds 03-001
      And gate attempt 1 of cycle C-7 at K-3 passed for candidate D-1
      And the grader with approved digest G-1 filed pass for 03-001 with no evidence
      When ORC runtime files the score of C-7
      Then the measurement of C-7 files 03-001 as pass with empty evidence

    # cycle-measurement.allium, rule FileScore (requires 1)
    # Suggested tier: balance
    @draft @allium-rule-failure.FileScore.1
    Scenario: A score for a cycle that was never gated is refused
      Given cycle C-8 has no gate attempt that passed
      When ORC runtime files the score of C-8
      Then filing the score is refused because C-8 is not gated
      And C-8 has no measurement

    # cycle-measurement.allium, rule FileScore (requires 2)
    # Legacy: @harness-032
    # Suggested tier: minimum
    @draft @allium-rule-failure.FileScore.2
    Scenario: A score with zero item verdicts filed is refused
      Given gate attempt 1 of cycle C-7 passed for candidate D-1
      And no grader filed item verdicts for C-7
      When ORC runtime files the score of C-7
      Then filing the score is refused as zero verdicts
      And C-7's measurement is still awaiting its score

    # cycle-measurement.allium, rule FileScore (requires 3); invariant OneMeasurementPerCycle; surface ScoreStage, guarantee AFalseRecordCannotBeUntaken
    # Suggested tier: minimum
    @draft @allium-rule-failure.FileScore.3 @allium-invariant.OneMeasurementPerCycle @allium-guarantee.AFalseRecordCannotBeUntaken
    Scenario: A filed measurement is never filed again
      Given gate attempt 1 of cycle C-7 passed for candidate D-1
      And the measurement of C-7 was filed with 2 passes of 3
      When ORC runtime files the score of C-7 again
      Then filing the score is refused
      And C-7 has one measurement, still with 2 passes of 3

    # cycle-measurement.allium, rule FileScore (requires 5)
    # Suggested tier: minimum
    @draft @allium-rule-failure.FileScore.5
    Scenario: A score whose grader digest is no longer approved when it is filed is refused
      Given gate attempt 1 of cycle C-7 passed for candidate D-1
      And the grader with digest G-1 filed item verdicts for D-1 while G-1 was approved
      When ORC runtime files the score of C-7 with the approved grader digests G-2
      Then filing the score is refused as grader not approved
      And C-7's measurement is still awaiting its score

  Rule: A refused score fails the score stage, once, and the score is never retaken within the cycle

    # cycle-measurement.allium, rule FailScoreStage
    # Suggested tier: minimum
    @draft @allium-rule-success.FailScoreStage
    Scenario: ORC runtime records the score stage failed after a refused score
      Given gate attempt 1 of cycle C-7 passed for candidate D-1
      And the grader's item verdicts for C-7 were refused as an unknown item
      When ORC runtime records the score stage of C-7 failed for the refusal unknown item
      Then the score stage of C-7 is failed, naming the refusal unknown item
      And the measurement receipt of C-7 shows the refusal and no score

    # cycle-measurement.allium, rule FailScoreStage (requires 1)
    # Suggested tier: balance
    @draft @allium-rule-failure.FailScoreStage.1
    Scenario: A failed score stage for a cycle that was never gated is refused
      Given cycle C-8 has no gate attempt that passed
      When ORC runtime records the score stage of C-8 failed
      Then recording the failed score stage is refused because C-8 is not gated
      And C-8 has no measurement

    # cycle-measurement.allium, rule FailScoreStage (requires 2)
    # Suggested tier: balance
    @draft @allium-rule-failure.FailScoreStage.2
    Scenario: A failed score stage after the measurement is filed is refused
      Given gate attempt 1 of cycle C-7 passed for candidate D-1
      And the measurement of C-7 was filed with 2 passes of 3
      When ORC runtime records the score stage of C-7 failed
      Then recording the failed score stage is refused
      And the measurement of C-7 is still filed with 2 passes of 3

    # cycle-measurement.allium, rule FileItemVerdicts (requires 8, unit-level) and FileScore (requires 4, unit-level); surface ScoreStage, guarantee NeverIntoTheLoop
    # Suggested tier: minimum
    @draft @allium-guarantee.NeverIntoTheLoop
    Scenario: Score evidence never reaches a builder, and a failed score stage is not retaken
      Given gate attempt 1 of cycle C-7 passed for candidate D-1
      And the score stage of C-7 failed for the refusal unknown item
      When the grader with approved digest G-1 files item verdicts for D-1 of C-7 again
      Then filing the item verdicts is refused
      And no builder room of C-7 mounts the item verdicts or the score evidence

  Rule: The operator reads a cycle's measurement receipt

    # checklist.allium, surface InstrumentDesk, guarantee MeasurementReceiptOnRequest
    # Legacy: @harness-037
    # Suggested tier: balance
    @draft @allium-guarantee.MeasurementReceiptOnRequest
    Scenario: The measurement receipt shows every verdict and reason, the denominator, the unreached count and the superseded verdicts
      Given the measurement of cycle C-7 was filed with 3 items, 1 pass, 1 unreached and 1 superseded verdict
      When the operator reads the measurement receipt of C-7
      Then the receipt shows each item's verdict and reason
      And shows the total 3, the unreached count 1 and the superseded verdict

  Rule: The scoreboard is reported once a cycle, to a person, never into the loop

    # scoreboard.allium, rules ReportScoreboardOnce and ReportScoreboard; invariant PassesWithinTotal; guarantee ToAPersonNeverIntoTheLoop
    # Legacy: @harness-041
    # Suggested tier: minimum
    @draft @allium-rule-success.ReportScoreboardOnce @allium-rule-success.ReportScoreboard @allium-invariant.PassesWithinTotal @allium-guarantee.ToAPersonNeverIntoTheLoop
    Scenario: A baseline cycle's scoreboard rates every item and has no previous cycle
      Given no cycle of the whole-plan selection was measured before cycle C-7
      When the measurement of C-7 is filed with 2 passes of 3
      Then the report-scoreboard-once policy reports the scoreboard of C-7 to the operator
      And the scoreboard shows 2 passes of 3 and the rate "66.7%"
      And it has no previous cycle
      And no command of the build loop reads it

    # scoreboard.allium, invariant OneScoreboardPerCycle (rule ReportScoreboardOnce, requires 1, is guarded by FileScore 3)
    # Suggested tier: balance
    @draft @allium-invariant.OneScoreboardPerCycle
    Scenario: A cycle's scoreboard is reported once
      Given the measurement of cycle C-7 was filed
      And the scoreboard of C-7 was reported
      When the measurement-filed fact of C-7 is delivered again
      Then the report-scoreboard-once policy reports nothing
      And C-7 has one scoreboard

    # scoreboard.allium, rule ReportScoreboard (requires 1)
    # Legacy: @harness-043
    # Suggested tier: minimum
    @draft @allium-rule-failure.ReportScoreboard.1
    Scenario: A scoreboard for a cycle with no measurement filed is refused
      Given the score stage of cycle C-8 failed
      When the report-scoreboard-once policy reports the scoreboard of C-8
      Then reporting the scoreboard is refused because no measurement is filed
      And C-8 has no scoreboard

  Rule: A cycle is compared with the last measured cycle of the same selection, both directions apart, never a net

    # scoreboard.allium, guarantee ComparedWithTheLastMeasuredCycle; contract Comparison, invariant LastMeasuredCycleOfTheSameSelection; invariant NeverComparedWithItself
    # Legacy: @harness-042
    # Suggested tier: minimum
    @draft @allium-guarantee.ComparedWithTheLastMeasuredCycle @allium-boundary.LastMeasuredCycleOfTheSameSelection @allium-invariant.NeverComparedWithItself
    Scenario: A cycle after an unscored one is compared with the last measured cycle, not the one before it
      Given cycle C-5 of the whole-plan selection was measured
      And cycle C-6 of the whole-plan selection failed at the gate and has no measurement
      When the measurement of cycle C-7 of the whole-plan selection is filed
      Then the scoreboard of C-7 is compared with C-5
      And never with C-6 or with C-7 itself

    # scoreboard.allium, invariant ComparedWithinTheSelection; contract Comparison, invariant LastMeasuredCycleOfTheSameSelection (R2 question 7)
    # Suggested tier: balance
    @draft @allium-invariant.ComparedWithinTheSelection
    Scenario: Whole-plan cycles compare across source cuts, never with another selection
      Given cycle C-4 of the whole-plan selection at source cut K-2 was measured
      And cycle C-5 of the selection of slices 03 and 04 at source cut K-3 was measured
      When the measurement of cycle C-6 of the whole-plan selection at source cut K-3 is filed
      Then the scoreboard of C-6 is compared with C-4
      And its previous selection is the whole-plan selection

    # scoreboard.allium, guarantee BothDirectionsNeverANet; contract Comparison, invariant BothDirectionsOnTheSharedItemsNeverANet
    # Legacy: @harness-045
    # Suggested tier: minimum
    @draft @allium-guarantee.BothDirectionsNeverANet @allium-boundary.BothDirectionsOnTheSharedItemsNeverANet
    Scenario: Newly passing and newly failing shared items are counted apart, with no net
      Given cycle C-6 of the whole-plan selection was measured with 03-001 pass and 03-002 fail
      When the measurement of cycle C-7 of the whole-plan selection is filed with 03-001 fail and 03-002 pass
      Then the scoreboard of C-7 lists 03-002 as newly passing and 03-001 as newly failing
      And it shows the shared items' rates for C-6 and C-7
      And it computes no net

    # scoreboard.allium, guarantee NewItemsAreTheHoldout; contract Comparison, invariant NewItemsAreTheUntunedHoldout
    # Suggested tier: balance
    @draft @allium-guarantee.NewItemsAreTheHoldout @allium-boundary.NewItemsAreTheUntunedHoldout
    Scenario: Items only this cycle carries are the untuned holdout, and dropped items are counted
      Given cycle C-6 of the whole-plan selection was measured with 03-001 and 03-002
      When the measurement of cycle C-7 of the whole-plan selection is filed with 03-001 and 04-001
      Then the scoreboard of C-7 counts 04-001 as a new item, the untuned holdout
      And counts 03-002 as a dropped item
      And compares only 03-001 as shared

    # scoreboard.allium, rule ReportScoreboard (ensures MovedItemSetRestated); guarantee AMovedItemSetIsRestatedOutLoud
    # Legacy: @harness-047
    # Suggested tier: minimum
    @draft @allium-guarantee.AMovedItemSetIsRestatedOutLoud
    Scenario: The comparison is restated on the shared items only when the item set moved
      Given cycle C-6 of the whole-plan selection was measured with a total of 3
      When the measurement of cycle C-7 of the whole-plan selection is filed with a total of 4, one item added
      Then the scoreboard of C-7 restates the comparison on the shared items, with previous total 3, total 4, 1 added and 0 dropped
      But a cycle whose item set did not move is reported with no restatement

  Rule: A rate has one decimal, rounded half to even, and "n/a" over zero

    # context.allium, contract RateFormat, invariant OneDecimalHalfToEven
    # Legacy: @harness-046
    # Suggested tier: minimum
    @draft @allium-boundary.OneDecimalHalfToEven
    Scenario: A rate exactly on a half rounds to the even digit
      Given no cycle of the whole-plan selection was measured before cycle C-7
      When the measurement of C-7 is filed with 1 pass of 16
      Then the scoreboard of C-7 shows the rate "6.2%"
      And a measurement with 5 passes of 16 shows the rate "31.2%"

    # context.allium, contract RateFormat, invariant ZeroDenominatorIsNotApplicable
    # Legacy: @harness-048
    # Suggested tier: minimum
    @draft @allium-boundary.ZeroDenominatorIsNotApplicable
    Scenario: A previous cycle with no shared item rates the shared items "n/a"
      Given cycle C-6 of the whole-plan selection was measured with only 03-001
      When the measurement of cycle C-7 of the whole-plan selection is filed with only 04-001
      Then the scoreboard of C-7 shows the shared items' rates as "n/a"
      And computes no percentage over zero shared items
