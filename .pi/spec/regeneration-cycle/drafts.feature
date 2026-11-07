Feature: Regeneration cycle, draft scenarios
  Drafts for the Allium obligations of this context (2026-11-06): each rule's success, each
  refusal that is behaviour, each invariant, each contract invariant and each guarantee of
  allium/ that no gate verifies. The harness does not read this file. Only the product owner moves a draft into
  spec.feature, under the Rule it names, where the stamper gives it an @item id and the @draft
  tag is dropped. Every noun is a term of .pi/model/GLOSSARY.md.

  Rule: No clean room is built over an unchecked instrument: the harness self-check opens the cycle before the fence

    # harness-self-check.allium, rule RecordHarnessSelfCheck (requires 1)
    # Suggested tier: balance
    @draft @allium-rule-failure.RecordHarnessSelfCheck.1
    Scenario: A harness self-check on a source cut nobody pinned is refused
      Given no source cut K-9 was pinned
      When ORC runtime records the harness self-check of cycle C-7 before the fence on source cut K-9, outcome passed
      Then the harness self-check is refused
      And no regeneration cycle C-7 exists

    # harness-self-check.allium, rule RecordHarnessSelfCheck (requires 4) and invariant FailedSelfCheckNamesItsCheck
    # Suggested tier: balance
    @draft @allium-rule-failure.RecordHarnessSelfCheck.4 @allium-invariant.FailedSelfCheckNamesItsCheck
    Scenario: A failed harness self-check that names no failing check is refused
      Given source cut K-3 was pinned
      When ORC runtime records the harness self-check of cycle C-7 before the fence, outcome failed, with no failing check
      Then the harness self-check is refused
      And no regeneration cycle C-7 exists

    # harness-self-check.allium, rule RecordHarnessSelfCheck (requires 5); regeneration-cycle.allium, invariant OneCyclePerIdentity
    # Suggested tier: balance
    @draft @allium-rule-failure.RecordHarnessSelfCheck.5 @allium-invariant.OneCyclePerIdentity
    Scenario: A second harness self-check before the fence of a cycle that already exists is refused
      Given harness self-check passed was recorded for cycle C-7 before the fence, attempt 0, on source cut K-3
      And the clean room of cycle C-7 was proved
      When ORC runtime records another harness self-check of cycle C-7 before the fence, attempt 1, outcome passed
      Then the harness self-check is refused
      And cycle C-7's harness self-checks are unchanged
      And the cycle progress view lists cycle C-7 once, at the regenerate stage, with its clean room

    # harness-self-check.allium, invariant OneSelfCheckPerOccasion
    # Suggested tier: balance
    @draft @allium-invariant.OneSelfCheckPerOccasion
    Scenario: A harness self-check is recorded once per cycle, occasion and attempt
      Given harness self-check passed was recorded for cycle C-7 before the fence on source cut K-3
      And candidate regenerated for cycle C-7, attempt 1
      And harness self-check passed was recorded for cycle C-7 before the gate, attempt 1
      When ORC runtime records the harness self-check of cycle C-7 before the gate, attempt 1, again
      Then nothing is recorded
      And cycle C-7 has exactly one harness self-check before the gate for attempt 1

    # harness-self-check.allium, rule RecordHarnessSelfCheck (requires 7)
    # Suggested tier: balance
    @draft @allium-rule-failure.RecordHarnessSelfCheck.7
    Scenario: A harness self-check carrying a budget beyond three gate attempts is refused
      Given source cut K-3 was pinned
      When ORC runtime records the harness self-check of cycle C-7 before the fence, outcome passed, with a budget of 4 gate attempts
      Then the harness self-check is refused
      And no regeneration cycle C-7 exists

    # harness-self-check.allium, rule RecordHarnessSelfCheck (requires 10)
    # Suggested tier: balance
    @draft @allium-rule-failure.RecordHarnessSelfCheck.10
    Scenario: A reworded golden that is not a current instrument golden is refused
      Given source cut K-3 was pinned
      And no instrument golden "scoreboard-09" was written
      When ORC runtime records the harness self-check of cycle C-7 before the fence, outcome failed, failing check "reworded golden", with reworded golden "scoreboard-09"
      Then the harness self-check is refused
      And no regeneration cycle C-7 exists

  Rule: The fence proves the clean room; it never asserts it

    # clean-room.allium, invariant OneCleanRoomPerCycle
    # Legacy: fence.sh (legacy-trace 61)
    # Suggested tier: balance
    @draft @allium-invariant.OneCleanRoomPerCycle
    Scenario: A cycle has one clean room
      Given harness self-check passed was recorded for cycle C-7 before the fence on source cut K-3
      And clean room proved was recorded for cycle C-7 with commit R-1
      When ORC runtime records the clean room of cycle C-7 again with commit R-2
      Then nothing is recorded
      And cycle C-7's clean room is still commit R-1

    # clean-room.allium, rule ProveCleanRoom (requires 8)
    # Suggested tier: balance
    @draft @allium-rule-failure.ProveCleanRoom.8
    Scenario: A clean room built from another source cut than the self-checked one is refused
      Given harness self-check passed was recorded for cycle C-7 before the fence on source cut K-3
      And the operator pinned source cut K-4
      When ORC runtime records the clean room of cycle C-7 from source cut K-4
      Then the clean room is refused
      And cycle C-7 has no clean room

    # clean-room.allium, rule ProveCleanRoom (requires 10)
    # Suggested tier: balance
    @draft @allium-rule-failure.ProveCleanRoom.10
    Scenario: No clean room is proved for a concluded cycle
      Given harness self-check passed was recorded for cycle C-7 before the fence on source cut K-3
      And cycle failed was recorded for cycle C-7 at the fence for its budget
      When ORC runtime records the clean room of cycle C-7
      Then the clean room is refused
      And cycle C-7 has no clean room

    # clean-room.allium, surface Fence, guarantee RebuildNotCopy
    # Suggested tier: balance
    @draft @allium-guarantee.RebuildNotCopy
    Scenario: The clean room keeps the spec and the application contract and loses every prior candidate and grader
      Given source cut K-3 holds the spec, the domain sources, the public application contract, a prior candidate, recorded measurements, a grader and its calibration fixtures
      And harness self-check passed was recorded for cycle C-7 before the fence on source cut K-3
      When ORC runtime's fence builds the clean room of cycle C-7 and clean room proved is recorded
      Then the clean room holds the spec, the domain sources and the public application contract
      And it holds no prior candidate, recorded measurement, grader, calibration fixture or grading harness

  Rule: The gate installs the regenerated candidate in a fresh gate room ORC chose

    # gate-attempt.allium, rule InstallCandidateInGateRoom (requires 1)
    # Suggested tier: balance
    @draft @allium-rule-failure.InstallCandidateInGateRoom.1
    Scenario: No candidate is installed for an attempt that was never regenerated
      Given the clean room of regeneration cycle C-7 was proved
      And no candidate was regenerated for gate attempt 1
      When ORC runtime records candidate D-1 installed in gate room G-1 for gate attempt 1
      Then the installation is refused
      And C-7 has no gate attempt

    # gate-attempt.allium, rule InstallCandidateInGateRoom (requires 2)
    # Suggested tier: balance
    @draft @allium-rule-failure.InstallCandidateInGateRoom.2
    Scenario: Another candidate than the one regenerated is not installed
      Given candidate D-1 was regenerated for gate attempt 1 of regeneration cycle C-7
      When ORC runtime records candidate D-9 installed in gate room G-1 for gate attempt 1
      Then the installation is refused
      And C-7 has no gate attempt

    # gate-attempt.allium, rule InstallCandidateInGateRoom (requires 4)
    # Suggested tier: balance
    @draft @allium-rule-failure.InstallCandidateInGateRoom.4
    Scenario: No candidate is installed for a concluded cycle
      Given candidate D-1 was regenerated for gate attempt 1 of regeneration cycle C-7
      And C-7 failed at the gate with reason budget
      When ORC runtime records candidate D-1 installed in gate room G-1 for gate attempt 1
      Then the installation is refused
      And C-7 has no gate attempt

  Rule: The gate records one verdict per attempt, with the boot budget and its margin

    # gate-attempt.allium, rule RecordGateAttempt (requires 1)
    # Suggested tier: balance
    @draft @allium-rule-failure.RecordGateAttempt.1
    Scenario: No verdict is recorded for an attempt whose candidate was never installed
      Given candidate D-1 was regenerated for gate attempt 1 of regeneration cycle C-7
      And it was not installed in a gate room
      When ORC runtime records gate attempt 1 passed
      Then the verdict is refused
      And C-7 has no gate attempt verdict

    # gate-attempt.allium, rule RecordGateAttempt (requires 3)
    # Suggested tier: balance
    @draft @allium-rule-failure.RecordGateAttempt.3
    Scenario: A failed verdict without a reason is refused
      Given candidate D-1 was installed in gate room G-1 for gate attempt 1 of regeneration cycle C-7
      And the harness self-check before gate attempt 1 passed
      When ORC runtime records gate attempt 1 failed with no reason
      Then the verdict is refused
      And gate attempt 1 still awaits its verdict

    # gate-attempt.allium, rule RecordGateAttempt (requires 4)
    # Suggested tier: balance
    @draft @allium-rule-failure.RecordGateAttempt.4
    Scenario: A pass without the poll that responded and the identity probe result is refused
      Given candidate D-1 was installed in gate room G-1 for gate attempt 1 of regeneration cycle C-7
      And the harness self-check before gate attempt 1 passed
      When ORC runtime records gate attempt 1 passed with no responding poll and no identity probe result
      Then the verdict is refused
      And gate attempt 1 still awaits its verdict

    # gate-attempt.allium, rule RecordGateAttempt (requires 5)
    # Suggested tier: balance
    @draft @allium-rule-failure.RecordGateAttempt.5
    Scenario: A candidate that responded after the last poll of the budget does not pass
      Given candidate D-1 was installed in gate room G-1 for gate attempt 1 of regeneration cycle C-7
      And the harness self-check before gate attempt 1 passed
      When ORC runtime records gate attempt 1 passed, responded on poll 21 of a boot budget of 20 polls
      Then the verdict is refused
      And gate attempt 1 still awaits its verdict

    # gate-attempt.allium, rule RecordGateAttempt (requires 6)
    # Suggested tier: balance
    @draft @allium-rule-failure.RecordGateAttempt.6
    Scenario: A verdict on another candidate than the one installed is refused
      Given candidate D-1 was installed in gate room G-1 for gate attempt 1 of regeneration cycle C-7
      And the harness self-check before gate attempt 1 passed
      When ORC runtime records gate attempt 1 passed for candidate D-9
      Then the verdict is refused
      And gate attempt 1 still awaits its verdict for candidate D-1

    # gate-attempt.allium, rule RecordGateAttempt (requires 11)
    # Suggested tier: balance
    @draft @allium-rule-failure.RecordGateAttempt.11
    Scenario: No verdict is recorded for a concluded cycle
      Given candidate D-1 was installed in gate room G-1 for gate attempt 1 of regeneration cycle C-7
      And the harness self-check before gate attempt 1 passed
      And C-7 failed at the gate with reason budget
      When ORC runtime records gate attempt 1 passed
      Then the verdict is refused
      And gate attempt 1 still awaits its verdict

    # gate-attempt.allium, rule RecordGateAttempt (requires 12)
    # Suggested tier: balance
    @draft @allium-rule-failure.RecordGateAttempt.12
    Scenario: A self-check at other approved grader digests does not stand for the attempt
      Given candidate D-1 was installed in gate room G-1 for gate attempt 1 of regeneration cycle C-7
      And the harness self-check before gate attempt 1 passed at approved grader digests R-1
      When ORC runtime records gate attempt 1 passed at approved grader digests R-2
      Then the verdict is refused
      And gate attempt 1 still awaits its verdict and is not spent

  Rule: Gate refusals spend no attempt, and three in a row fail the cycle for infrastructure

    # gate-attempt.allium, rule RecordGateRefusal
    # Suggested tier: balance
    @draft @allium-rule-success.RecordGateRefusal
    Scenario: A refusal to grade is recorded and spends no attempt
      Given candidate D-1 was installed in gate room G-1 for gate attempt 1 of regeneration cycle C-7
      And the gate refused to grade the attempt because the seam responded before boot
      When ORC runtime records the gate refusal seam-answered-before-boot for gate attempt 1
      Then gate attempt 1 of C-7 was refused for seam-answered-before-boot
      And gate attempt 1 still awaits its verdict
      And C-7 has spent no gate attempt

    # gate-attempt.allium, rule RecordGateRefusal (requires 1)
    # Suggested tier: balance
    @draft @allium-rule-failure.RecordGateRefusal.1
    Scenario: No refusal is recorded for an attempt whose candidate was never installed
      Given candidate D-1 was regenerated for gate attempt 1 of regeneration cycle C-7
      And it was not installed in a gate room
      When ORC runtime records the gate refusal seam-answered-before-boot for gate attempt 1
      Then the gate refusal is refused
      And C-7 has no gate refusal

    # gate-attempt.allium, rule RecordGateRefusal (requires 2)
    # Suggested tier: balance
    @draft @allium-rule-failure.RecordGateRefusal.2
    Scenario: No refusal is recorded over an attempt that has its verdict
      Given gate attempt 1 of regeneration cycle C-7 passed
      When ORC runtime records the gate refusal identity-probe-missing for gate attempt 1
      Then the gate refusal is refused
      And gate attempt 1 is still passed and C-7 has no gate refusal

    # gate-attempt.allium, rule RecordGateRefusal (requires 3)
    # Suggested tier: balance
    @draft @allium-rule-failure.RecordGateRefusal.3
    Scenario: No refusal is recorded for a concluded cycle
      Given candidate D-1 was installed in gate room G-1 for gate attempt 1 of regeneration cycle C-7
      And C-7 failed at the gate with reason budget
      When ORC runtime records the gate refusal seam-answered-before-boot for gate attempt 1
      Then the gate refusal is refused
      And C-7 has no gate refusal

    # regeneration-cycle.allium, rules NoteGateAttemptRefused and ConcludeOnRepeatedGateRefusals; context.allium, contract GateRoom, invariant RefusalsAreBoundedAndCounted (counted)
    # Suggested tier: balance
    @draft @allium-rule-success.NoteGateAttemptRefused @allium-rule-success.ConcludeOnRepeatedGateRefusals @allium-boundary.RefusalsAreBoundedAndCounted
    Scenario: Three consecutive gate refusals fail the cycle at the gate for infrastructure
      Given candidate D-1 of cycle C-7 is installed in a gate room for attempt 1
      And ORC runtime recorded two gate refusals of attempt 1, the seam answered before boot
      When ORC runtime records a third gate refusal of attempt 1, the seam answered before boot
      Then the conclude-cycle policy fails cycle C-7 at the gate stage with reason infrastructure
      And the cycle progress view shows 1 attempt and 0 failed gate attempts

    # regeneration-cycle.allium, rules NoteGateAttemptRefused and NoteGateAttemptFailed (a verdict resets the count)
    # Suggested tier: balance
    @draft @allium-rule-success.NoteGateAttemptRefused @allium-rule-success.NoteGateAttemptFailed
    Scenario: A gate verdict between refusals resets their count
      Given ORC runtime recorded two gate refusals of attempt 1 of cycle C-7
      And gate attempt 1 of cycle C-7 then failed with reason suite red
      And candidate D-2 was regenerated by verification repair and installed in a gate room for attempt 2
      When ORC runtime records a gate refusal of attempt 2, the identity probe missing
      Then cycle C-7 is still running at the gate stage
      And the cycle progress view shows 2 attempts and 1 failed gate attempt

  Rule: A regeneration cycle opens at its first harness self-check, before the fence

    # regeneration-cycle.allium, rule OpenCycle (requires 1)
    # Suggested tier: deferred
    @draft @allium-rule-failure.OpenCycle.1
    Scenario: A passing harness self-check before a gate attempt opens no cycle
      Given cycle C-7 is running at the gate stage with its candidate for attempt 1 regenerated
      When ORC runtime records a passing harness self-check before the gate for cycle C-7, attempt 1
      Then the cycle progress view still lists cycle C-7 once, at the gate stage
      And its attempts, budget and harness digest are unchanged

  Rule: A candidate is regenerated for the next gate attempt, within the budget

    # regeneration-cycle.allium, rule RecordRegeneratedCandidate (requires 1)
    # Suggested tier: balance
    @draft @allium-rule-failure.RecordRegeneratedCandidate.1
    Scenario: A candidate for a cycle that never opened is refused
      Given no harness self-check was ever recorded for cycle C-9
      When ORC runtime records a candidate regenerated for cycle C-9, attempt 1, by fresh generation
      Then the regenerated candidate is refused
      And the cycle progress view shows no cycle C-9

    # regeneration-cycle.allium, rule RecordRegeneratedCandidate (requires 3)
    # Suggested tier: balance
    @draft @allium-rule-failure.RecordRegeneratedCandidate.3
    Scenario: A candidate for an attempt that is not the next one is refused
      Given the clean room of cycle C-7 was proved
      When ORC runtime records a candidate regenerated for cycle C-7, attempt 2, by verification repair
      Then the regenerated candidate is refused
      And the cycle progress view shows cycle C-7 at the regenerate stage with 0 attempts

    # regeneration-cycle.allium, rule RecordRegeneratedCandidate (requires 5)
    # Suggested tier: balance
    @draft @allium-rule-failure.RecordRegeneratedCandidate.5
    Scenario Outline: Attempt 1 is a fresh generation and every later attempt a verification repair
      Given <given>
      When ORC runtime records a candidate regenerated for cycle C-7, attempt <attempt>, by <purpose>
      Then the regenerated candidate is refused
      And the cycle progress view shows cycle C-7 with <attempts> attempts, unchanged

      Examples:
        | given                                                    | attempt | purpose             | attempts |
        | the clean room of cycle C-7 was proved                   | 1       | verification repair | 0        |
        | gate attempt 1 of cycle C-7 failed with reason suite red | 2       | fresh generation    | 1        |

    # regeneration-cycle.allium, rule RecordRegeneratedCandidate (requires 7)
    # Suggested tier: balance
    @draft @allium-rule-failure.RecordRegeneratedCandidate.7
    Scenario: A verification repair never follows a passed gate attempt
      Given gate attempt 1 of cycle C-7 passed
      When ORC runtime records a candidate regenerated for cycle C-7, attempt 2, by verification repair
      Then the regenerated candidate is refused
      And the cycle progress view shows cycle C-7 at the score stage with 1 attempt

    # regeneration-cycle.allium, rule RecordRegeneratedCandidate (requires 8)
    # Suggested tier: balance
    @draft @allium-rule-failure.RecordRegeneratedCandidate.8
    Scenario: A verification repair's parent is the previous candidate
      Given candidate D-1 of cycle C-7 failed gate attempt 1
      When ORC runtime records candidate D-2 regenerated for attempt 2 by verification repair, with parent candidate D-0
      Then the regenerated candidate is refused
      And the cycle progress view shows cycle C-7 at the regenerate stage with candidate D-1

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

    # regeneration-cycle.allium, rule PassCycle (requires 2); invariant PassedOnlyMeasuredAndReported
    # Suggested tier: balance
    @draft @allium-rule-failure.PassCycle.2 @allium-invariant.PassedOnlyMeasuredAndReported
    Scenario: A cycle whose scoreboard is not reported cannot pass
      Given the measurement of cycle C-7 was filed
      And no scoreboard is reported for cycle C-7
      When the conclude-cycle policy passes cycle C-7
      Then passing the cycle is refused
      And the cycle progress view shows cycle C-7 running at the scoreboard stage

    # regeneration-cycle.allium, surface CycleStages, guarantee OneConclusion
    # Suggested tier: balance
    @draft @allium-guarantee.OneConclusion
    Scenario: A concluded cycle is never concluded again
      Given cycle C-7 failed at the fence stage with reason fence refused
      When ORC runtime fails cycle C-7 at the fence stage with reason budget
      Then failing the cycle is refused
      And the cycle progress view shows cycle C-7 failed at the fence stage with reason fence refused, unchanged

    # regeneration-cycle.allium, rule FailCycle (requires 1)
    # Suggested tier: deferred
    @draft @allium-rule-failure.FailCycle.1
    Scenario: Failing a cycle that never opened is refused
      Given no harness self-check was ever recorded for cycle C-9
      When ORC runtime fails cycle C-9 at the fence stage with reason fence refused
      Then failing the cycle is refused
      And the cycle progress view shows no cycle C-9

    # regeneration-cycle.allium, rule FailCycle (requires 3)
    # Suggested tier: balance
    @draft @allium-rule-failure.FailCycle.3
    Scenario: A cycle fails only at the stage it reached
      Given the clean room of cycle C-7 was proved, so cycle C-7 is at the regenerate stage
      When ORC runtime fails cycle C-7 at the fence stage with reason fence refused
      Then failing the cycle is refused
      And the cycle progress view shows cycle C-7 running at the regenerate stage

    # regeneration-cycle.allium, rule FailCycle (requires 4)
    # Suggested tier: balance
    @draft @allium-rule-failure.FailCycle.4
    Scenario: A cycle fails only for a reason that belongs to its stage
      Given candidate D-1 of cycle C-7 was regenerated, so cycle C-7 is at the gate stage
      When ORC runtime fails cycle C-7 at the gate stage with reason fence refused
      Then failing the cycle is refused
      And the cycle progress view shows cycle C-7 running at the gate stage

    # regeneration-cycle.allium, rule FailCycle (requires 5)
    # Suggested tier: balance
    @draft @allium-rule-failure.FailCycle.5
    Scenario: An infrastructure failure belongs to the gate stage only
      Given the clean room of cycle C-7 was proved, so cycle C-7 is at the regenerate stage
      When ORC runtime fails cycle C-7 at the regenerate stage with reason infrastructure
      Then failing the cycle is refused
      And the cycle progress view shows cycle C-7 running at the regenerate stage

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

  Rule: The operator observes a cycle, and observing changes nothing

    # regeneration-cycle.allium, surface CycleProgress, guarantee ObservingChangesNothing
    # Suggested tier: balance
    @draft @allium-guarantee.ObservingChangesNothing
    Scenario: Reading the cycle progress view records nothing
      Given cycle C-7 is running at the gate stage with 1 attempt
      When the operator reads the cycle progress view of cycle C-7 twice
      Then both readings show cycle C-7 at the gate stage with 1 attempt
      And no stage fact is recorded for cycle C-7
