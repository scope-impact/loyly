Feature: The instrument: checklist, sealed grader, score, and scoreboard
  The instrument measures a cycle and reports to a person. The checklist is derived from
  the spec, never written. The grader is written once from the spec, calibrated against
  carried and uncarried behavior, sealed by digest, and reused until the scenarios it
  grades change. The score files one verdict per checklist item with an honest
  denominator, and the scoreboard compares cycles on their shared items, both directions,
  never as a net.

  Rule: The checklist is derived from the spec and its denominator never drifts

    @skip
    @item-03-001
    Scenario: The checklist is one item per stamped scenario of the approved selection
      Given an approved selection of stamped Gherkin scenarios
      When the checklist is derived
      Then it holds exactly one item per selected scenario, keyed by its stable item identity and scenario digest
      And a scenario without a stamped identity stops the cycle instead of shrinking the checklist
      And an empty selection stops the cycle instead of producing a checklist nothing could fail

  Rule: The grader is a sealed, calibrated instrument, not a per-cycle guess

    @skip
    @item-03-002
    Scenario: A grader is written once from the spec and calibrated before it is sealed
      Given no sealed grader covers a checklist item's scenario digest
      When an independent grader author derives a grader from only the selected Gherkin and the public calibration protocol
      Then the author cannot read any candidate, builder or gate transcript, prior verdict, or host credential
      And ORC calibrates the grader so carried behavior passes and uncarried behavior fails for every item
      And an uncalibrated grader is discarded and re-authored from the failing calibration cases, at most three attempts
      And only a calibrated grader is sealed under its scenario digests and offered for approval

    @skip
    @item-03-003
    Scenario: An approved grader is reused until its scenarios change
      Given an approved sealed grader covers every checklist item's scenario digest
      When a later cycle reaches the score stage
      Then it reuses that grader without authoring a new one
      And it re-calibrates the grader against carried and uncarried behavior before scoring
      And a changed scenario digest requires a new grader for that item only

    @skip
    @item-03-004
    Scenario: A sealed grader changes only for a recorded reason
      Given an approved sealed grader would be replaced
      When the operator reviews the replacement
      Then the reason is recorded as instrument behavior changed, wording replaced, or spec grew
      And a spec-grew replacement may only add items, never move an existing item
      And a grader is never replaced merely to turn a failing score green
      And approval binds the exact digest of the replacement grader

  Rule: The score files honest verdicts

    @skip
    @item-03-005
    Scenario: Zero verdicts mean the score did not happen
      Given a candidate passed the gate
      When the sealed grader returns no readable verdict for any item
      Then the score stage fails
      And no measurement is recorded

    @skip
    @item-03-006
    Scenario: An unreached item is recorded as failed and the denominator holds
      Given a checklist of three items
      And the grader reaches only one item
      When the score is filed
      Then the measurement total remains three
      And each unreached item is recorded as failed and not graded
      And the measurement is marked incomplete

    @skip
    @item-03-007
    Scenario: Behavior, affordance, and runtime evidence are scored by the same sealed grader
      Given the sealed grader carries behavior, applicable affordance, and applicable runtime-evidence evaluators
      When the score stage runs against the candidate's public seam
      Then every evaluator reports per checklist item into one measurement
      And candidate telemetry can never author a verdict

  Rule: The scoreboard compares cycles honestly

    @skip
    @item-03-008
    Scenario: The scoreboard compares shared items in both directions and never nets them
      Given a recorded measurement and the measurement of the previous cycle for the same selection
      When the scoreboard is reported
      Then it reports the pass rate of each cycle with its own denominator
      And it restates the comparison on the items both cycles share
      And it reports items that newly pass and items that newly fail separately, never as a net
      And items new to the checklist are counted separately as the untuned holdout

    @skip
    @item-03-009
    Scenario: The first cycle of a selection has no comparison
      Given no earlier measurement exists for the selection
      When the scoreboard is reported
      Then it reports the pass rate and denominator only
      And it records that no comparison was possible

  # Ported from the legacy instrument (archive/legacy-harness/spec.feature): the stamper.
  Rule: An item identity is stamped once and never moves

    @item-03-010
    Scenario: An unstamped scenario receives the next free identity in its slice
      Given a slice whose highest stamped sequence number is 4 and one scenario with no item tag
      When the slice is stamped
      Then that scenario receives sequence number 5 on a tag line with the scenario's own indentation
      And a Scenario Outline is stamped exactly like a Scenario
      And a scenario whose only tags are not item tags counts as unstamped
      And a slice that gains no identity is not rewritten

    @item-03-011
    Scenario: Stamping never renumbers, never re-derives, and never reuses an identity
      Given a stamped slice
      When it is stamped again, a scenario is reworded, or a scenario is deleted
      Then stamping again changes nothing
      And a reworded scenario keeps its identity
      And a deleted scenario's number is never handed out again

  # Ported from the legacy instrument: what a checklist item carries.
  Rule: A checklist item carries where it came from

    @item-03-012
    Scenario: Each item names its identity, rule, source, and title
      Given an approved selection of stamped scenarios
      When the checklist is derived
      Then each item carries its item identity, its scenario digest, the Rule it sits under or none, the file and line of its Scenario line, and its title with surrounding whitespace stripped
      And items are ordered by slice file, then by position in the file
      And a Scenario line with an empty title is not an item

  # Ported from the legacy instrument. Guard: a measurement filed wrong cannot be untaken.
  Rule: The score refuses verdicts the checklist cannot account for

    @item-03-013
    Scenario: A verdict for an item not on the checklist stops the score
      Given an item identity that is not on the checklist
      When the grader returns a verdict for that identity
      Then the score stage fails naming every unknown item, in the order the grader returned them
      And no measurement is recorded

    @item-03-014
    Scenario: A verdict that is neither pass nor fail stops the score
      Given an item on the checklist
      When the grader returns the verdict "maybe", no verdict, or null for it
      Then the score stage fails naming that item
      And no measurement is recorded
      And unknown items are reported before invalid verdicts, so a return with both faults names only the unknown items

    @item-03-015
    Scenario: A repeated verdict keeps the last, and evidence is optional
      Given the grader returns two verdicts for one item, the second without evidence
      When the score is filed
      Then the item is filed with the last verdict
      And its evidence is empty rather than missing

  # Ported from the legacy scoreboard: its arithmetic is contract.
  Rule: The scoreboard's arithmetic is fixed

    @item-03-016
    Scenario: A rate exactly on a half rounds to the even digit
      Given a comparison whose shared rate is exactly 1 of 16
      When the scoreboard is reported
      Then the percentage is 6.2, not 6.3
      And a rate with a zero denominator reports no percentage at all

    @item-03-017
    Scenario: A moved item set is stated only when it moved
      Given the previous cycle and this cycle
      When the scoreboard is reported
      Then it states the previous total, this total, and how many items were added and dropped only when any were added or dropped
      And a previous cycle that shares no item with this one is still compared, over zero shared items

  # ORC ships evaluation judges (orc/specs/evaluation.allium): LLM and custom judges that
  # score node completions from 0 to 1, aggregate them, and feed instruction optimization.
  # That is the opposite of this instrument, so they may observe but never measure.
  Rule: Only the sealed grader judges

    @item-03-018
    Scenario: ORC evaluation judge scores are diagnostics, never verdicts
      Given ORC evaluation judges scored nodes of a cycle
      When the score stage files the measurement
      Then every checklist verdict comes from the approved sealed grader
      And no judge score, composite score, or LLM opinion is a verdict or part of the measurement
      And judge scores are recorded only as diagnostic runtime evidence, which no builder session receives

  # Ported from the legacy oracle (archive/legacy-harness/oracle.sh), which held the
  # instrument's tools to recorded bytes. Here the goldens are written from the scenarios
  # above, so they state what the spec says rather than what some implementation once did.
  Rule: The instrument's exact behaviour is held by goldens written from the spec

    @item-03-019
    Scenario: An instrument golden is written from a scenario, never recorded from an implementation
      Given a scenario that fixes the instrument's exact behaviour, such as stamping, checklist derivation, a score refusal, or the scoreboard's arithmetic
      When its golden is written
      Then the golden states the scenario's inputs and its exact expected output, refusal reasons included
      And it names the scenario it was written from
      And it is never captured from any implementation's output
      And it is instrument material, fenced from every clean room like a grader

    @item-03-020
    Scenario: The harness self-check reproduces every instrument golden before any clean room
      Given the instrument goldens
      When the harness self-check runs
      Then the harness's stamping, checklist derivation, score filing, and scoreboard reproduce every golden exactly
      And a golden that is not reproduced is named and fails the self-check, so no clean room is built
      And a golden changes only for a recorded reason: instrument behaviour changed, wording replaced, or the spec grew with additions only
