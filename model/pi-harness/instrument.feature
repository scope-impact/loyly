Feature: The instrument: checklist, sealed grader, score, and scoreboard
  The instrument measures a cycle and reports to a person. The checklist is derived from
  the spec, never written. The grader is written once from the spec, calibrated against
  carried and uncarried behavior, sealed by digest, and reused until the scenarios it
  grades change. The score files one verdict per checklist item with an honest
  denominator, and the scoreboard compares cycles on their shared items, both directions,
  never as a net.

  Rule: The checklist is derived from the spec and its denominator never drifts

    @item-01-071 @skip
    Scenario: The checklist is one item per stamped scenario of the approved selection
      Given an approved selection of stamped Gherkin scenarios
      When the checklist is derived
      Then it holds exactly one item per selected scenario, keyed by its stable item identity and scenario digest
      And a scenario without a stamped identity stops the cycle instead of shrinking the checklist
      And an empty selection stops the cycle instead of producing a checklist nothing could fail

  Rule: The grader is a sealed, calibrated instrument, not a per-cycle guess

    @item-01-072 @skip
    Scenario: A grader is written once from the spec and calibrated before it is sealed
      Given no sealed grader covers a checklist item's scenario digest
      When an independent grader author derives a grader from only the selected Gherkin and the public calibration protocol
      Then the author cannot read any candidate, builder or gate transcript, prior verdict, or host credential
      And ORC calibrates the grader so carried behavior passes and uncarried behavior fails for every item
      And an uncalibrated grader is discarded and re-authored from the failing calibration cases, at most three attempts
      And only a calibrated grader is sealed under its scenario digests and offered for approval

    @item-01-073 @skip
    Scenario: An approved grader is reused until its scenarios change
      Given an approved sealed grader covers every checklist item's scenario digest
      When a later cycle reaches the score stage
      Then it reuses that grader without authoring a new one
      And it re-calibrates the grader against carried and uncarried behavior before scoring
      And a changed scenario digest requires a new grader for that item only

    @item-01-074 @skip
    Scenario: A sealed grader changes only for a recorded reason
      Given an approved sealed grader would be replaced
      When the operator reviews the replacement
      Then the reason is recorded as instrument behavior changed, wording replaced, or spec grew
      And a spec-grew replacement may only add items, never move an existing item
      And a grader is never replaced merely to turn a failing score green
      And approval binds the exact digest of the replacement grader

  Rule: The score files honest verdicts

    @item-01-075 @skip
    Scenario: Zero verdicts mean the score did not happen
      Given a candidate passed the gate
      When the sealed grader returns no readable verdict for any item
      Then the score stage fails
      And no measurement is recorded

    @item-01-076 @skip
    Scenario: An unreached item is recorded as failed and the denominator holds
      Given a checklist of three items
      And the grader reaches only one item
      When the score is filed
      Then the measurement total remains three
      And each unreached item is recorded as failed and not graded
      And the measurement is marked incomplete

    @item-01-077 @skip
    Scenario: Behavior, affordance, and runtime evidence are scored by the same sealed grader
      Given the sealed grader carries behavior, applicable affordance, and applicable runtime-evidence evaluators
      When the score stage runs against the candidate's public seam
      Then every evaluator reports per checklist item into one measurement
      And candidate telemetry can never author a verdict

  Rule: The scoreboard compares cycles honestly

    @item-01-078 @skip
    Scenario: The scoreboard compares shared items in both directions and never nets them
      Given a recorded measurement and the measurement of the previous cycle for the same selection
      When the scoreboard is reported
      Then it reports the pass rate of each cycle with its own denominator
      And it restates the comparison on the items both cycles share
      And it reports items that newly pass and items that newly fail separately, never as a net
      And items new to the checklist are counted separately as the untuned holdout

    @item-01-079 @skip
    Scenario: The first cycle of a selection has no comparison
      Given no earlier measurement exists for the selection
      When the scoreboard is reported
      Then it reports the pass rate and denominator only
      And it records that no comparison was possible
