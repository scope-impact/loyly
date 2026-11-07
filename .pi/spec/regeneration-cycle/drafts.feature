Feature: Regeneration cycle, draft scenarios
  Drafts for the Allium obligations of this context (2026-11-06): each rule's success, each
  refusal that is behaviour, each invariant, each contract invariant and each guarantee of
  allium/ that no gate verifies. The harness does not read this file. Only the product owner moves a draft into
  spec.feature, under the Rule it names, where the stamper gives it an @item id and the @draft
  tag is dropped. Every noun is a term of .pi/model/GLOSSARY.md.

  Rule: The fence proves the clean room; it never asserts it

    # clean-room.allium, surface Fence, guarantee RebuildNotCopy
    # Suggested tier: balance
    @draft @allium-guarantee.RebuildNotCopy
    Scenario: The clean room keeps the spec and the application contract and loses every prior candidate and grader
      Given source cut K-3 holds the spec, the domain sources, the public application contract, a prior candidate, recorded measurements, a grader and its calibration fixtures
      And harness self-check passed was recorded for cycle C-7 before the fence on source cut K-3
      When ORC runtime's fence builds the clean room of cycle C-7 and clean room proved is recorded
      Then the clean room holds the spec, the domain sources and the public application contract
      And it holds no prior candidate, recorded measurement, grader, calibration fixture or grading harness

  Rule: A regeneration cycle opens at its first harness self-check, before the fence

    # regeneration-cycle.allium, rule OpenCycle (requires 1)
    # Suggested tier: deferred
    @draft @allium-rule-failure.OpenCycle.1
    Scenario: A passing harness self-check before a gate attempt opens no cycle
      Given cycle C-7 is running at the gate stage with its candidate for attempt 1 regenerated
      When ORC runtime records a passing harness self-check before the gate for cycle C-7, attempt 1
      Then the cycle progress view still lists cycle C-7 once, at the gate stage
      And its attempts, budget and harness digest are unchanged

  Rule: A stage that outlives its timeout fails the cycle for its budget

    # regeneration-cycle.allium, rule ConcludeOnStageTimeout
    # Suggested tier: balance
    @draft @allium-rule-success.ConcludeOnStageTimeout
    Scenario: A regenerate stage that outlives its timeout fails the cycle with reason budget
      Given cycle C-7 has a budget with a regenerate timeout of 30 minutes
      And the clean room of cycle C-7 was proved at 10:00
      When time passes to 10:31 with no candidate regenerated
      Then the stage clock fails cycle C-7 at the regenerate stage with reason budget and detail "stage timeout"

    # regeneration-cycle.allium, rule ConcludeOnStageTimeout (the gate stage's clock bounds gate refusals); context.allium, contract GateRoom, invariant RefusalsAreBoundedAndCounted (bounded)
    # Suggested tier: balance
    @draft @allium-rule-success.ConcludeOnStageTimeout @allium-boundary.RefusalsAreBoundedAndCounted
    Scenario: Gate refusals that spend no attempt are still bounded by the gate stage's timeout
      Given cycle C-7 has a budget with a gate timeout of 10 minutes
      And candidate D-1 entered the gate stage for attempt 1 at 10:00
      And ORC runtime recorded two gate refusals of attempt 1 by 10:09
      When time passes to 10:11 with no gate attempt recorded
      Then the stage clock fails cycle C-7 at the gate stage with reason budget
      And the cycle progress view shows 1 attempt and 0 failed gate attempts

    # regeneration-cycle.allium, rule ConcludeOnStageTimeout (requires 2)
    # Suggested tier: deferred
    @draft @allium-rule-failure.ConcludeOnStageTimeout.2
    Scenario: The stage clock does not fail a cycle waiting at the scoreboard
      Given the measurement of cycle C-7 was filed at 10:00, so cycle C-7 waits at the scoreboard stage
      And the budget's score timeout of cycle C-7 is 10 minutes
      When time passes to 10:30 with no scoreboard reported
      Then the stage clock does not fail cycle C-7
      And the cycle progress view shows cycle C-7 running at the scoreboard stage

  Rule: A cycle concludes once, passed after its scoreboard or failed at a named stage for a named reason

    # regeneration-cycle.allium, rule PassCycle (requires 1)
    # Suggested tier: deferred
    @draft @allium-rule-failure.PassCycle.1
    Scenario: Passing a cycle that never opened is refused
      Given no harness self-check was ever recorded for cycle C-9
      When the conclude-cycle policy passes cycle C-9
      Then passing the cycle is refused
      And the cycle progress view shows no cycle C-9

    # regeneration-cycle.allium, rule FailCycle (requires 1)
    # Suggested tier: deferred
    @draft @allium-rule-failure.FailCycle.1
    Scenario: Failing a cycle that never opened is refused
      Given no harness self-check was ever recorded for cycle C-9
      When ORC runtime fails cycle C-9 at the fence stage with reason fence refused
      Then failing the cycle is refused
      And the cycle progress view shows no cycle C-9

    # regeneration-cycle.allium, surface CycleStages, guarantee OrcRunsTheHarnessRecords
    # Suggested tier: deferred
    @draft @allium-guarantee.OrcRunsTheHarnessRecords
    Scenario: No harness policy starts a stage
      Given cycle C-7 has a budget with a regenerate timeout of 30 minutes
      And the clean room of cycle C-7 was proved at 10:00
      When time passes to 10:20 with no candidate recorded by ORC runtime
      Then the cycle progress view shows cycle C-7 running at the regenerate stage with 0 attempts and no candidate

    # regeneration-cycle.allium, rule NoteScoreStageFailed (requires 2)
    # Suggested tier: balance
    # Reachable only through the stub: Measurement fails no score stage of a cycle it never
    # saw gated (measurement FailScoreStage requires 1); decisions.md, "Spec round 4".
    @draft @allium-rule-failure.NoteScoreStageFailed.2
    Scenario: A score stage failed before the gate passed is not the cycle's
      Given candidate D-1 was installed in gate room G-1 for gate attempt 1 of regeneration cycle C-7
      And the measurement recorded the score stage of cycle C-7 failed, its score refused for zero verdicts
      When ORC runtime records gate attempt 1 passed
      Then the cycle progress view shows cycle C-7 running at the score stage
      And cycles-to-conclude does not list cycle C-7
