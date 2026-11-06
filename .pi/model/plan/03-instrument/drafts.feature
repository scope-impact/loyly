Feature: The instrument: checklist, sealed grader, score, and scoreboard, draft scenarios
  Drafts for the Allium obligations no scenario in spec.feature covers (2026-11-06).
  The harness does not read this file. Only the product owner moves a draft into
  spec.feature, under the Rule it belongs to, where the stamper gives it its @item id
  and the @draft tag is dropped. Every noun is a term of .pi/model/GLOSSARY.md.

  Sixteen drafts: the four refusals of an instrument golden, the bounds on a grader
  author's attempts, the seal that requires a candidate-blind grader author, the third
  uncalibrated grader, the retirement of a replaced grader, a score filed by a grader
  other than the one the cycle preparation bound, and a zero-verdict return that names
  no item.

  # instrument.allium, rule WriteInstrumentGolden (requires 1)
  # Goes under: Rule: The instrument's exact behaviour is held by goldens written from the spec
  # Suggested tier: minimum

    @draft @allium-rule-failure.WriteInstrumentGolden.1
    Scenario: An instrument golden needs a scenario that fixes the exact output
      Given a scenario that states no exact inputs or exact expected output for the instrument
      When the operator records an instrument golden for that scenario
      Then the instrument golden is refused
      And the recorded instrument goldens are unchanged

  # instrument.allium, rule WriteInstrumentGolden (requires 2)
  # Goes under: Rule: The instrument's exact behaviour is held by goldens written from the spec
  # Suggested tier: minimum

    @draft @allium-rule-failure.WriteInstrumentGolden.2
    Scenario: An instrument golden captured from a run is refused
      Given a scenario that fixes the scoreboard's arithmetic
      And an expected output captured from a run of the instrument rather than written from that scenario
      When the operator records it as the scenario's instrument golden
      Then the instrument golden is refused
      And the recorded instrument goldens are unchanged

  # instrument.allium, rule WriteInstrumentGolden (requires 3)
  # Goes under: Rule: The instrument's exact behaviour is held by goldens written from the spec
  # Suggested tier: minimum

    @draft @allium-rule-failure.WriteInstrumentGolden.3
    Scenario: A replacement instrument golden must name its recorded reason
      Given an instrument golden was recorded for the stamping scenario
      When the operator records a replacement instrument golden for it without a reason
      Then the replacement is refused
      And the recorded instrument golden is unchanged

  # instrument.allium, rule WriteInstrumentGolden (requires 4)
  # Goes under: Rule: The instrument's exact behaviour is held by goldens written from the spec
  # Suggested tier: minimum

    @draft @allium-rule-failure.WriteInstrumentGolden.4
    Scenario: A spec-grew instrument golden may only add cases
      Given an instrument golden was recorded for the checklist derivation scenario
      When the operator records a replacement for the reason spec grew that changes one of its existing cases
      Then the replacement is refused
      And the recorded instrument golden is unchanged

  # instrument.allium, rule AuthorGrader (requires 2)
  # Goes under: Rule: The grader is a sealed, calibrated instrument, not a per-cycle guess
  # Suggested tier: balance

    @draft @allium-rule-failure.AuthorGrader.2
    Scenario: A grader reported with no attempt number is refused
      Given a grader author's builder session completed for a checklist's scenario digests
      When ORC reports the grader it wrote with attempt number 0
      Then the grader is refused
      And no grader is calibrating for those scenario digests

  # instrument.allium, rule AuthorGrader (requires 3)
  # Goes under: Rule: The grader is a sealed, calibrated instrument, not a per-cycle guess
  # Suggested tier: balance

    @draft @allium-rule-failure.AuthorGrader.3
    Scenario: A fourth grader attempt is refused
      Given three graders written for the same scenario digests were each discarded as uncalibrated
      When ORC reports a grader for those scenario digests with attempt number 4
      Then the grader is refused
      And no grader is calibrating for those scenario digests

  # instrument.allium, rule AuthorGrader (requires 4)
  # Goes under: Rule: The grader is a sealed, calibrated instrument, not a per-cycle guess
  # Suggested tier: balance

    @draft @allium-rule-failure.AuthorGrader.4
    Scenario: A replacement grader written without a recorded reason is refused
      Given an approved sealed grader covers a checklist's scenario digests
      When ORC reports a grader that replaces it but names no reason
      Then the grader is refused
      And the approved sealed grader is unchanged and no successor is calibrating

  # instrument.allium, rule SealCalibratedGrader (requires 2)
  # Goes under: Rule: The grader is a sealed, calibrated instrument, not a per-cycle guess
  # Suggested tier: balance

    @draft @allium-rule-failure.SealCalibratedGrader.2
    Scenario: A grader whose author read more than the spec and the protocol is never sealed
      Given a grader is calibrating
      And its grader author's builder session read a candidate besides the selected Gherkin and the public calibration protocol
      When ORC reports that carried behavior passed and uncarried behavior failed
      Then sealing the grader is refused
      And the grader is still calibrating and offered to no operator for approval

  # instrument.allium, rule ReauthorUncalibratedGrader (requires 3)
  # Goes under: Rule: The grader is a sealed, calibrated instrument, not a per-cycle guess
  # Suggested tier: balance

    @draft @allium-rule-failure.ReauthorUncalibratedGrader.3
    Scenario: A grader author is re-run only from public calibration cases
      Given the first grader for a checklist is calibrating
      When ORC reports its calibration failed, citing a case drawn from a candidate rather than the public calibration protocol
      Then re-authoring the grader is refused
      And the grader is still calibrating and no second grader author is started

  # instrument.allium, rule StopAfterThreeUncalibratedGraders
  # Goes under: Rule: The grader is a sealed, calibrated instrument, not a per-cycle guess
  # Suggested tier: balance

    @draft @allium-rule-success.StopAfterThreeUncalibratedGraders
    Scenario: A third uncalibrated grader leaves the instrument unavailable
      Given two graders for a checklist's scenario digests were discarded as uncalibrated
      And the third grader for them is calibrating
      When ORC reports that its calibration failed
      Then the third grader is discarded
      And the instrument is declared unavailable for those scenario digests
      And no fourth grader author is started

  # instrument.allium, rule StopAfterThreeUncalibratedGraders (requires 2)
  # Goes under: Rule: The grader is a sealed, calibrated instrument, not a per-cycle guess
  # Suggested tier: balance

    @draft @allium-rule-failure.StopAfterThreeUncalibratedGraders.2
    Scenario: A first uncalibrated grader does not make the instrument unavailable
      Given the first grader for a checklist's scenario digests is calibrating
      When ORC reports that its calibration failed
      Then the instrument is not declared unavailable for those scenario digests
      And a second grader author is requested from the failing calibration cases

  # instrument.allium, rule RetireReplacedGrader
  # Goes under: Rule: The grader is a sealed, calibrated instrument, not a per-cycle guess
  # Suggested tier: balance

    @draft @allium-rule-success.RetireReplacedGrader
    Scenario: Approving a successor retires the grader it replaces
      Given an approved sealed grader covers a checklist's scenario digests
      And a successor grader that replaces it because the spec grew was calibrated and sealed
      When the operator approves the successor's exact digest
      Then the successor is approved
      And the grader it replaces is retired and scores no later cycle

  # instrument.allium, rule RetireReplacedGrader (requires 1)
  # Goes under: Rule: The grader is a sealed, calibrated instrument, not a per-cycle guess
  # Suggested tier: balance

    @draft @allium-rule-failure.RetireReplacedGrader.1
    Scenario: Approving a grader that replaces nothing retires no grader
      Given an approved sealed grader covers one selection's scenario digests
      And a sealed grader for another selection replaces no grader
      When the operator approves the second grader's exact digest
      Then no grader is retired
      And the first grader is still approved

  # instrument.allium, rule RetireReplacedGrader (requires 2)
  # Goes under: Rule: The grader is a sealed, calibrated instrument, not a per-cycle guess
  # Suggested tier: balance

    @draft @allium-rule-failure.RetireReplacedGrader.2
    Scenario: Only an approved grader is retired by its successor
      Given a sealed grader nobody approved
      And a sealed successor that replaces it because the wording was replaced
      When the operator approves the successor's exact digest
      Then the replaced grader is not retired
      And it is still sealed

  # instrument.allium, rule FileScore (requires 4)
  # Goes under: Rule: The score files honest verdicts
  # Suggested tier: balance

    @draft @allium-rule-failure.FileScore.4
    Scenario: A score from a grader the cycle preparation did not bind is refused
      Given a cycle's approved cycle preparation binds one approved sealed grader digest
      And the cycle passed the gate and reached the score stage
      When ORC returns verdicts from a different approved grader
      Then filing the score is refused
      And no measurement is recorded for the cycle

  # instrument.allium, rules RefuseUnknownVerdicts and RefuseInvalidVerdicts (requires 1 of each)
  # Goes under: Rule: The score refuses verdicts the checklist cannot account for
  # Suggested tier: minimum

    @draft @allium-rule-failure.RefuseUnknownVerdicts.1 @allium-rule-failure.RefuseInvalidVerdicts.1
    Scenario: A score with zero verdicts names no item
      Given a candidate passed the gate and the cycle reached the score stage
      When the sealed grader returns no verdict at all
      Then the score stage fails as a score that did not happen
      And the refusal names no unknown item and no invalid verdict
      And no measurement is recorded
