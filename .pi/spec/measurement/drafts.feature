Feature: Measurement, draft scenarios
  Drafts for the Allium obligations of this context (2026-11-06): each rule's success, each
  refusal that is behaviour, each invariant, each contract invariant and each guarantee of
  allium/ that no gate verifies. The harness does not read this file. Only the product owner moves a draft into
  spec.feature, under the Rule it names, where the stamper gives it an @item id and the @draft
  tag is dropped. Every noun is a term of .pi/model/GLOSSARY.md.

  Rule: The checklist is derived from the stamped scenarios at a pinned source cut, never written

    # checklist.allium, rule DeriveChecklist (requires 1)
    # Suggested tier: balance
    @draft @allium-rule-failure.DeriveChecklist.1
    Scenario: A checklist of a source cut that was never pinned is refused
      Given no source cut K-9 was pinned
      When the operator derives the checklist of K-9
      Then deriving the checklist is refused because the source cut is not pinned
      And no checklist exists for K-9

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

    # cycle-measurement.allium, rule FileScore (requires 1)
    # Suggested tier: balance
    @draft @allium-rule-failure.FileScore.1
    Scenario: A score for a cycle that was never gated is refused
      Given cycle C-8 has no gate attempt that passed
      When ORC runtime files the score of C-8
      Then filing the score is refused because C-8 is not gated
      And C-8 has no measurement

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

    # scoreboard.allium, invariant OneScoreboardPerCycle (rule ReportScoreboardOnce, requires 1, is guarded by FileScore 3)
    # Suggested tier: balance
    @draft @allium-invariant.OneScoreboardPerCycle
    Scenario: A cycle's scoreboard is reported once
      Given the measurement of cycle C-7 was filed
      And the scoreboard of C-7 was reported
      When the measurement-filed fact of C-7 is delivered again
      Then the report-scoreboard-once policy reports nothing
      And C-7 has one scoreboard

  Rule: A cycle is compared with the last measured cycle of the same selection, both directions apart, never a net

    # scoreboard.allium, invariant ComparedWithinTheSelection; contract Comparison, invariant LastMeasuredCycleOfTheSameSelection (R2 question 7)
    # Suggested tier: balance
    @draft @allium-invariant.ComparedWithinTheSelection
    Scenario: Whole-plan cycles compare across source cuts, never with another selection
      Given cycle C-4 of the whole-plan selection at source cut K-2 was measured
      And cycle C-5 of the selection of slices 03 and 04 at source cut K-3 was measured
      When the measurement of cycle C-6 of the whole-plan selection at source cut K-3 is filed
      Then the scoreboard of C-6 is compared with C-4
      And its previous selection is the whole-plan selection

    # scoreboard.allium, guarantee NewItemsAreTheHoldout; contract Comparison, invariant NewItemsAreTheUntunedHoldout
    # Suggested tier: balance
    @draft @allium-guarantee.NewItemsAreTheHoldout @allium-boundary.NewItemsAreTheUntunedHoldout
    Scenario: Items only this cycle carries are the untuned holdout, and dropped items are counted
      Given cycle C-6 of the whole-plan selection was measured with 03-001 and 03-002
      When the measurement of cycle C-7 of the whole-plan selection is filed with 03-001 and 04-001
      Then the scoreboard of C-7 counts 04-001 as a new item, the untuned holdout
      And counts 03-002 as a dropped item
      And compares only 03-001 as shared
