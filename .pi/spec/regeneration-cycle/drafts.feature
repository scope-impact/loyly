Feature: Regeneration cycle, draft scenarios
  Drafts for the Allium obligations of this context (2026-11-06): each rule's success, each
  refusal that is behaviour, each invariant, each contract invariant and each guarantee of
  allium/ that no gate verifies. The harness does not read this file. Only the product owner moves a draft into
  spec.feature, under the Rule it names, where the stamper gives it an @item id and the @draft
  tag is dropped. Every noun is a term of .pi/model/GLOSSARY.md.

  Rule: No clean room is built over an unchecked instrument: the harness self-check opens the cycle before the fence

    # harness-self-check.allium, rules RecordHarnessSelfCheck, NotePinnedCut and NoteGoldenWritten; regeneration-cycle.allium, rule OpenCycle
    # Legacy: @harness-065
    # Suggested tier: minimum
    @draft @allium-rule-success.RecordHarnessSelfCheck @allium-rule-success.NotePinnedCut @allium-rule-success.NoteGoldenWritten @allium-rule-success.OpenCycle
    Scenario: A passing harness self-check before the fence opens the cycle on a pinned source cut
      Given the operator pinned source cut K-3
      And the instrument goldens "checklist" and "scoreboard-01" were written, goldens digest G-1
      When ORC runtime records the harness self-check of cycle C-7 before the fence, attempt 0, on source cut K-3 at goldens digest G-1 and approved grader digests D-1, outcome passed, with a budget of 3 gate attempts
      Then harness self-check passed is recorded for cycle C-7 before the fence
      And it names harness digest, goldens digest G-1, approved grader digests D-1, its identity probe and the budget
      And the cycle progress view shows cycle C-7 running at the fence stage for source cut K-3, with the budget of 3 gate attempts, 0 attempts and no candidate

    # harness-self-check.allium, rule RecordHarnessSelfCheck (requires 1)
    # Suggested tier: balance
    @draft @allium-rule-failure.RecordHarnessSelfCheck.1
    Scenario: A harness self-check on a source cut nobody pinned is refused
      Given no source cut K-9 was pinned
      When ORC runtime records the harness self-check of cycle C-7 before the fence on source cut K-9, outcome passed
      Then the harness self-check is refused
      And no regeneration cycle C-7 exists

    # harness-self-check.allium, rule RecordHarnessSelfCheck (requires 2); contract HarnessSelfCheckRun, invariant NeverReusedAcrossAChange; rule NoteGoldenMoved
    # Legacy: @harness-065
    # Suggested tier: minimum
    @draft @allium-rule-failure.RecordHarnessSelfCheck.2 @allium-boundary.NeverReusedAcrossAChange @allium-rule-success.NoteGoldenMoved
    Scenario: A harness self-check at goldens that are no longer current is refused
      Given source cut K-3 was pinned
      And the instrument golden "checklist" was written, goldens digest G-1
      And the instrument golden "checklist" was moved with reason spec grew, so the current goldens digest is G-2
      When ORC runtime records the harness self-check of cycle C-7 before the fence at goldens digest G-1, outcome passed
      Then the harness self-check is refused
      And no regeneration cycle C-7 exists

    # contract HarnessSelfCheckRun, invariant ReproducesEveryGolden
    # Legacy: oracle.sh (legacy-trace 60)
    # Suggested tier: minimum
    @draft @allium-boundary.ReproducesEveryGolden
    Scenario: A golden the harness self-check does not reproduce exactly fails it, by name
      Given source cut K-3 was pinned
      And the instrument golden "score-refusal" expects the refusal reason "unknown item"
      And the tools at harness digest H-1 give the refusal reason "unknown id"
      When ORC runtime runs the harness self-check of cycle C-7 before the fence
      Then harness self-check failed is recorded for cycle C-7
      And its failing check names the instrument golden "score-refusal"

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

    # harness-self-check.allium, rule RecordHarnessSelfCheck (requires 6)
    # Legacy: @harness-065
    # Suggested tier: minimum
    @draft @allium-rule-failure.RecordHarnessSelfCheck.6
    Scenario: A harness self-check before a gate attempt with no candidate for that attempt is refused
      Given harness self-check passed was recorded for cycle C-7 before the fence on source cut K-3
      And the clean room of cycle C-7 was proved
      And candidate regenerated for cycle C-7, attempt 1
      When ORC runtime records the harness self-check of cycle C-7 before the gate, attempt 2
      Then the harness self-check is refused
      And cycle C-7's harness self-checks are unchanged

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

    # harness-self-check.allium, rule RecordHarnessSelfCheck (requires 8); contract HarnessSelfCheckRun, invariant GoldenRequiredWhereItsCycleExists
    # Legacy: @harness-060, @harness-061
    # Suggested tier: minimum
    @draft @allium-rule-failure.RecordHarnessSelfCheck.8 @allium-boundary.GoldenRequiredWhereItsCycleExists
    Scenario: A cycle with scores and no scoreboard golden cannot pass the harness self-check
      Given source cut K-3 was pinned
      And source cut K-3 holds the scores of cycle 01 and no instrument golden for its scoreboard
      When ORC runtime records the harness self-check of cycle C-7 before the fence, outcome passed, with missing golden "scoreboard-01"
      Then the harness self-check is refused
      And no regeneration cycle C-7 exists

    # contract HarnessSelfCheckRun, invariant GoldenRequiredWhereItsCycleExists
    # Legacy: @harness-061
    # Suggested tier: minimum
    @draft @allium-boundary.GoldenRequiredWhereItsCycleExists
    Scenario: An instrument golden with no cycle beside it does not fail the harness self-check
      Given source cut K-3 was pinned
      And the instrument golden "scoreboard-01" was written
      And the clean room holds no cycle 01, because the fence removed it
      When ORC runtime runs the harness self-check of cycle C-7 before the fence
      Then harness self-check passed is recorded for cycle C-7
      And it lists no missing golden

    # harness-self-check.allium, rule RecordHarnessSelfCheck (requires 9); contract HarnessSelfCheckRun, invariant RewordedScenarioFailsItsGolden
    # Suggested tier: minimum
    @draft @allium-rule-failure.RecordHarnessSelfCheck.9 @allium-boundary.RewordedScenarioFailsItsGolden
    Scenario: A golden whose scenario was reworded since it was written fails the harness self-check
      Given source cut K-3 was pinned
      And the instrument golden "checklist" was written from its scenario at scenario digest S-1
      And that scenario was reworded, so its scenario digest is now S-2
      When ORC runtime records the harness self-check of cycle C-7 before the fence, outcome passed, with reworded golden "checklist"
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

    # contract HarnessSelfCheckRun, invariant MissingToolIsALoudSkip
    # Legacy: @harness-066
    # Suggested tier: minimum
    @draft @allium-boundary.MissingToolIsALoudSkip
    Scenario: A scoring tool fenced out of the clean room is a listed skip, not a failure
      Given source cut K-3 was pinned
      And the tree the harness self-check runs in has no scoring tool "score", because the fence removed it
      When ORC runtime runs the harness self-check of cycle C-7 before the gate, attempt 1
      Then harness self-check passed is recorded for cycle C-7 before the gate, attempt 1
      And its skipped checks name "score"

  Rule: The fence proves the clean room; it never asserts it

    # clean-room.allium, rule ProveCleanRoom; surface Fence, guarantee ProvedNotAsserted; regeneration-cycle.allium, rule NoteCleanRoomProved
    # Legacy: fence.sh (legacy-trace 63 to 65), @harness-065
    # Suggested tier: minimum
    @draft @allium-rule-success.ProveCleanRoom @allium-guarantee.ProvedNotAsserted @allium-rule-success.NoteCleanRoomProved
    Scenario: A clean room is proved when every check of the fence's probe holds, and the cycle moves to regenerate
      Given harness self-check passed was recorded for cycle C-7 before the fence on source cut K-3
      When ORC runtime records the clean room of cycle C-7 from source cut K-3 with one root commit, one commit, fenced paths "harness/score.clj" and "harness/cycles", every fenced path matched, none left in the tree and none reachable in the object graph
      Then clean room proved is recorded for cycle C-7, naming its commit and its fenced paths
      And the cycle progress view shows cycle C-7 at the regenerate stage with its clean room proved

    # clean-room.allium, rule ProveCleanRoom (requires 1); surface HarnessSelfCheckRecording, guarantee NoRoomOverAnUncheckedInstrument
    # Legacy: @harness-065
    # Suggested tier: minimum
    @draft @allium-rule-failure.ProveCleanRoom.1 @allium-guarantee.NoRoomOverAnUncheckedInstrument
    Scenario: No clean room is proved after a failed harness self-check
      Given harness self-check failed was recorded for cycle C-7 before the fence, failing check "golden checklist differs"
      When ORC runtime records the clean room of cycle C-7 from source cut K-3
      Then the clean room is refused
      And cycle C-7 has no clean room

    # clean-room.allium, rule ProveCleanRoom (requires 2); contract FenceProbe, invariant AFencedPathThatMatchesNothingIsAFault
    # Legacy: fence.sh (legacy-trace 63)
    # Suggested tier: minimum
    @draft @allium-rule-failure.ProveCleanRoom.2 @allium-boundary.AFencedPathThatMatchesNothingIsAFault
    Scenario: A fenced path that matches nothing stops the fence
      Given harness self-check passed was recorded for cycle C-7 before the fence on source cut K-3
      And source cut K-3 has no "harness/scroe.clj"
      When ORC runtime records the clean room of cycle C-7 with fenced path "harness/scroe.clj" unmatched
      Then the clean room is refused, naming "harness/scroe.clj" as a fenced path that matched nothing
      And cycle C-7 has no clean room

    # clean-room.allium, rule ProveCleanRoom (requires 3)
    # Legacy: fence.sh (legacy-trace 64)
    # Suggested tier: minimum
    @draft @allium-rule-failure.ProveCleanRoom.3
    Scenario: A clean room with two root commits is refused
      Given harness self-check passed was recorded for cycle C-7 before the fence on source cut K-3
      When ORC runtime records the clean room of cycle C-7 with two root commits
      Then the clean room is refused
      And cycle C-7 has no clean room

    # clean-room.allium, rule ProveCleanRoom (requires 4)
    # Legacy: fence.sh (legacy-trace 64)
    # Suggested tier: minimum
    @draft @allium-rule-failure.ProveCleanRoom.4
    Scenario: A clean room whose history is more than one commit is refused
      Given harness self-check passed was recorded for cycle C-7 before the fence on source cut K-3
      When ORC runtime records the clean room of cycle C-7 with one root commit and three commits
      Then the clean room is refused
      And cycle C-7 has no clean room

    # clean-room.allium, rule ProveCleanRoom (requires 5)
    # Legacy: fence.sh (legacy-trace 65)
    # Suggested tier: minimum
    @draft @allium-rule-failure.ProveCleanRoom.5
    Scenario: A fenced path left in the working tree is refused
      Given harness self-check passed was recorded for cycle C-7 before the fence on source cut K-3
      When ORC runtime records the clean room of cycle C-7 with fenced path "harness/cycles" still in the working tree
      Then the clean room is refused, naming "harness/cycles"
      And cycle C-7 has no clean room

    # clean-room.allium, rule ProveCleanRoom (requires 6); contract FenceProbe, invariant ObjectGraphNotWorkingTree
    # Legacy: fence.sh (legacy-trace 65)
    # Suggested tier: minimum
    @draft @allium-rule-failure.ProveCleanRoom.6 @allium-boundary.ObjectGraphNotWorkingTree
    Scenario: A prior candidate reachable in the object graph is refused even when the working tree is clean
      Given harness self-check passed was recorded for cycle C-7 before the fence on source cut K-3
      And the prior candidate under "harness-impl" is absent from the clean room's working tree
      And it is still reachable from an object of the clean room's history
      When ORC runtime records the clean room of cycle C-7 with "harness-impl" reachable in the object graph
      Then the clean room is refused, naming "harness-impl"
      And cycle C-7 has no clean room

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

    # clean-room.allium, rule ProveCleanRoom (requires 9)
    # Legacy: @harness-068
    # Suggested tier: minimum
    @draft @allium-rule-failure.ProveCleanRoom.9
    Scenario: A clean room that keeps a scoring tool is refused
      Given the scoring tools are "harness/score.clj" and "harness/scoreboard.clj"
      And harness self-check passed was recorded for cycle C-7 before the fence on source cut K-3
      When ORC runtime records the clean room of cycle C-7 with fenced path "harness/score.clj" only
      Then the clean room is refused, naming "harness/scoreboard.clj" as a scoring tool not fenced
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

    # clean-room.allium, invariant AFenceHidesSomething
    # Legacy: fence.sh (legacy-trace 62)
    # Suggested tier: minimum
    @draft @allium-invariant.AFenceHidesSomething
    Scenario: A fence with no fenced paths proves no clean room
      Given harness self-check passed was recorded for cycle C-7 before the fence on source cut K-3
      When ORC runtime records the clean room of cycle C-7 with no fenced paths
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

    # gate-attempt.allium, rule InstallCandidateInGateRoom
    # Suggested tier: minimum
    @draft @allium-rule-success.InstallCandidateInGateRoom
    Scenario: The candidate of an attempt is installed in a fresh gate room
      Given the clean room of regeneration cycle C-7 was proved
      And candidate D-1 was regenerated for gate attempt 1 by fresh generation
      When ORC runtime records candidate D-1 installed in gate room G-1 for gate attempt 1
      Then candidate D-1 is installed, read-only, in gate room G-1 for gate attempt 1
      And gate attempt 1 of C-7 awaits its verdict

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

    # gate-attempt.allium, invariant AtMostThreeAttempts
    # Suggested tier: minimum
    @draft @allium-invariant.AtMostThreeAttempts
    Scenario: A cycle never has a fourth gate attempt
      Given gate attempts 1, 2 and 3 of regeneration cycle C-7 failed with reason suite-red
      When ORC runtime records candidate D-4 installed in gate room G-4 for gate attempt 4
      Then the installation is refused
      And C-7 has three gate attempts

  Rule: The gate records one verdict per attempt, with the boot budget and its margin

    # gate-attempt.allium, rule RecordGateAttempt; invariant AnsweredInsideTheBudget; surface Gate, guarantee TheMarginIsSaidOutLoud;
    # regeneration-cycle.allium, rule NoteGateAttemptPassed
    # Legacy: @harness-056
    # Suggested tier: minimum
    @draft @allium-rule-success.RecordGateAttempt @allium-invariant.AnsweredInsideTheBudget @allium-guarantee.TheMarginIsSaidOutLoud @allium-rule-success.NoteGateAttemptPassed
    Scenario: A candidate that responded inside the boot budget passes, the margin is said out loud, and the cycle moves to the score
      Given candidate D-1 was installed in gate room G-1 for gate attempt 1 of regeneration cycle C-7
      And the harness self-check before gate attempt 1 passed at harness digest H-1, the current goldens, grader digests R-1 and identity probe digest P-1
      When ORC runtime records gate attempt 1 passed: boot budget 10 seconds as 20 polls, responded on poll 2, identity probe result "kalibra", suite green
      Then gate attempt 1 passed for candidate D-1
      And the verdict carries the boot budget of 10 seconds and 20 polls and the poll that responded, 2
      And the cycle progress view shows cycle C-7 at the score stage with candidate D-1 and a boot margin of 18 polls

    # gate-attempt.allium, rule RecordGateAttempt (the failed branch); surface Gate, guarantee TheMarginIsSaidOutLoud;
    # regeneration-cycle.allium, rule NoteGateAttemptFailed and invariant FailedAttemptsNeverExceedAttempts
    # Legacy: @harness-057
    # Suggested tier: minimum
    @draft @allium-rule-success.RecordGateAttempt @allium-guarantee.TheMarginIsSaidOutLoud @allium-rule-success.NoteGateAttemptFailed @allium-invariant.FailedAttemptsNeverExceedAttempts
    Scenario: A candidate that has not responded when the boot budget runs out fails the attempt, and the cycle goes back to regenerate
      Given regeneration cycle C-7 has a budget of 3 gate attempts
      And candidate D-1 was installed in gate room G-1 for gate attempt 1 of C-7
      And the harness self-check before gate attempt 1 passed
      When ORC runtime records gate attempt 1 failed with reason boot-budget-exhausted, boot budget 10 seconds as 20 polls, gate evidence root E-1
      Then gate attempt 1 failed with reason boot-budget-exhausted
      And the verdict names the boot budget of 10 seconds and 20 polls
      And the cycle progress view shows cycle C-7 at the regenerate stage with 1 attempt and 1 failed gate attempt
      And gate evidence root E-1 is the gate evidence a repair room for attempt 2 may mount

    # context.allium, contract GateRoom, invariant BootClockStartsAtLaunch
    # Legacy: @harness-058
    # Suggested tier: minimum
    @draft @allium-boundary.BootClockStartsAtLaunch
    Scenario: The boot clock covers everything the launch entrypoint does, a build included
      Given candidate D-1 was installed in gate room G-1 for gate attempt 1 of regeneration cycle C-7
      And the launch entrypoint of D-1 builds for 30 seconds before it serves its first page
      And its suite would be green
      When the gate boots D-1 with a boot budget of 10 seconds as 20 polls
      Then gate attempt 1 fails with reason boot-budget-exhausted
      And a build inside the suite would not have counted against the boot budget

    # gate-attempt.allium, surface Gate, guarantee GateEvidenceOnly
    # Suggested tier: minimum
    @draft @allium-guarantee.GateEvidenceOnly
    Scenario: A failed attempt leaves gate evidence a repair builder may read, and never score evidence
      Given gate attempt 1 of regeneration cycle C-7 failed with reason suite-red and evidence root E-1
      When the repair builder's room for gate attempt 2 mounts its evidence
      Then it mounts gate evidence E-1, the suite's output of attempt 1
      And E-1 holds no item verdict and no score evidence

    # gate-attempt.allium, rule RecordGateAttempt (requires 1)
    # Suggested tier: balance
    @draft @allium-rule-failure.RecordGateAttempt.1
    Scenario: No verdict is recorded for an attempt whose candidate was never installed
      Given candidate D-1 was regenerated for gate attempt 1 of regeneration cycle C-7
      And it was not installed in a gate room
      When ORC runtime records gate attempt 1 passed
      Then the verdict is refused
      And C-7 has no gate attempt verdict

    # gate-attempt.allium, rule RecordGateAttempt (requires 2); invariant OneVerdictPerAttempt
    # Suggested tier: minimum
    @draft @allium-rule-failure.RecordGateAttempt.2 @allium-invariant.OneVerdictPerAttempt
    Scenario: A replayed verdict records nothing and spends no attempt
      Given gate attempt 1 of regeneration cycle C-7 failed with reason suite-red
      When ORC runtime records gate attempt 1 failed with reason suite-red again
      Then the verdict is refused
      And gate attempt 1 has one verdict, failed
      And C-7 has spent one gate attempt

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

    # gate-attempt.allium, rule RecordGateAttempt (requires 7); harness-self-check.allium, surface HarnessSelfCheckRecording, guarantee NoRoomOverAnUncheckedInstrument (the gate half)
    # Legacy: @harness-065
    # Suggested tier: minimum
    @draft @allium-rule-failure.RecordGateAttempt.7 @allium-guarantee.NoRoomOverAnUncheckedInstrument
    Scenario: No candidate is graded over a harness the self-check did not check
      Given candidate D-1 was installed in gate room G-1 for gate attempt 1 of regeneration cycle C-7
      And the harness self-check before gate attempt 1 passed at harness digest H-1
      When ORC runtime records gate attempt 1 passed at harness digest H-2
      Then the verdict is refused
      And gate attempt 1 still awaits its verdict and is not spent

    # gate-attempt.allium, rule RecordGateAttempt (requires 8)
    # Legacy: @harness-053
    # Suggested tier: minimum
    @draft @allium-rule-failure.RecordGateAttempt.8
    Scenario: The gate refuses to grade without the identity probe
      Given candidate D-1 was installed in gate room G-1 for gate attempt 1 of regeneration cycle C-7
      And the harness self-check before gate attempt 1 passed
      When ORC runtime records gate attempt 1 passed while gate room G-1 has no identity probe
      Then the verdict is refused
      And gate attempt 1 still awaits its verdict and is not spent

    # gate-attempt.allium, rule RecordGateAttempt (requires 9); contract GateRoom, invariant IdentityProbeIsTheInstruments
    # Legacy: @harness-053
    # Suggested tier: minimum
    @draft @allium-rule-failure.RecordGateAttempt.9 @allium-boundary.IdentityProbeIsTheInstruments
    Scenario: An identity probe that is not the one the self-check checked does not judge the candidate
      Given candidate D-1 was installed in gate room G-1 for gate attempt 1 of regeneration cycle C-7
      And the harness self-check before gate attempt 1 passed with identity probe digest P-1
      And candidate D-1 brings an identity probe of its own, digest P-9
      When ORC runtime records gate attempt 1 passed with identity probe digest P-9
      Then the verdict is refused
      And gate attempt 1 still awaits its verdict and is not spent

    # gate-attempt.allium, rule RecordGateAttempt (requires 10); contract GateRoom, invariant SeamCheckedBeforeBoot;
    # surface Gate, guarantee ARefusalSpendsNoAttempt
    # Suggested tier: minimum
    @draft @allium-rule-failure.RecordGateAttempt.10 @allium-boundary.SeamCheckedBeforeBoot @allium-guarantee.ARefusalSpendsNoAttempt
    Scenario: A seam that already responded before boot is not graded and spends no attempt
      Given candidate D-1 was installed in gate room G-1 for gate attempt 1 of regeneration cycle C-7
      And the harness self-check before gate attempt 1 passed
      And an orphan of an earlier run already responds on the public seam of gate room G-1
      When ORC runtime records gate attempt 1 passed with the seam responding before boot
      Then the verdict is refused
      And gate attempt 1 still awaits its verdict
      And C-7 has spent no gate attempt

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

  Rule: A failed harness self-check fails the cycle, and a cycle failed before the score has no score

    # regeneration-cycle.allium, rule NoteFailedSelfCheck; invariant FailedBeforeScoreHasNoMeasurement;
    # surface CycleProgress, guarantee NoScoreIsNotAllFailed
    # Legacy: @harness-065
    # Suggested tier: minimum
    @draft @allium-rule-success.NoteFailedSelfCheck @allium-invariant.FailedBeforeScoreHasNoMeasurement @allium-guarantee.NoScoreIsNotAllFailed
    Scenario: A failed harness self-check before the fence fails the cycle at the fence with no score
      Given cut K-3 was pinned
      When ORC runtime records a failed harness self-check before the fence for cycle C-7, naming the check "instrument golden scoreboard-arithmetic"
      Then cycle C-7 exists and fails at the fence stage with reason harness self-check failed, naming that check
      And no clean room is proved for cycle C-7
      And the cycle progress view shows cycle C-7 as "no score", never as every item failed

    # regeneration-cycle.allium, rule ConcludeOnFailedSelfCheck
    # Legacy: @harness-065
    # Suggested tier: minimum
    @draft @allium-rule-success.ConcludeOnFailedSelfCheck
    Scenario: A failed harness self-check before a gate attempt fails the cycle at the gate and spends no attempt
      Given cycle C-7 is running at the gate stage with 1 failed gate attempt and its candidate for attempt 2 regenerated
      When ORC runtime records a failed harness self-check before the gate for cycle C-7, attempt 2
      Then the conclude-cycle policy fails cycle C-7 at the gate stage with reason harness self-check failed
      And no candidate is installed in a gate room for attempt 2
      And the cycle progress view still shows 1 failed gate attempt

  Rule: A candidate is regenerated for the next gate attempt, within the budget

    # regeneration-cycle.allium, rule RecordRegeneratedCandidate; surface BuilderRoomBoundary, guarantee DoneIsTheGatesVerdict
    # Suggested tier: minimum
    @draft @allium-rule-success.RecordRegeneratedCandidate @allium-guarantee.DoneIsTheGatesVerdict
    Scenario: The first candidate is a fresh generation for gate attempt 1, and a claim, not done
      Given the clean room of cycle C-7 was proved
      When ORC runtime records the candidate D-1 regenerated for cycle C-7, attempt 1, by fresh generation, mounting no evidence, its builder session reporting the slice done
      Then a candidate regenerated for attempt 1 is recorded with digest D-1
      And the cycle progress view shows cycle C-7 running at the gate stage with 1 attempt and candidate D-1, not passed
      And cycle C-7 concludes only after a gate attempt is recorded in a gate room the builder does not control

    # regeneration-cycle.allium, rule RecordRegeneratedCandidate (requires 1)
    # Suggested tier: balance
    @draft @allium-rule-failure.RecordRegeneratedCandidate.1
    Scenario: A candidate for a cycle that never opened is refused
      Given no harness self-check was ever recorded for cycle C-9
      When ORC runtime records a candidate regenerated for cycle C-9, attempt 1, by fresh generation
      Then the regenerated candidate is refused
      And the cycle progress view shows no cycle C-9

    # regeneration-cycle.allium, rule RecordRegeneratedCandidate (requires 2)
    # Legacy: @harness-065
    # Suggested tier: minimum
    @draft @allium-rule-failure.RecordRegeneratedCandidate.2
    Scenario: No candidate is regenerated before the clean room is proved
      Given a passing harness self-check before the fence opened cycle C-7
      And no clean room is proved for cycle C-7
      When ORC runtime records a candidate regenerated for cycle C-7, attempt 1, by fresh generation
      Then the regenerated candidate is refused
      And the cycle progress view shows cycle C-7 at the fence stage with 0 attempts

    # regeneration-cycle.allium, rule RecordRegeneratedCandidate (requires 3)
    # Suggested tier: balance
    @draft @allium-rule-failure.RecordRegeneratedCandidate.3
    Scenario: A candidate for an attempt that is not the next one is refused
      Given the clean room of cycle C-7 was proved
      When ORC runtime records a candidate regenerated for cycle C-7, attempt 2, by verification repair
      Then the regenerated candidate is refused
      And the cycle progress view shows cycle C-7 at the regenerate stage with 0 attempts

    # regeneration-cycle.allium, rule RecordRegeneratedCandidate (requires 4); invariant AttemptsWithinBudget
    # Suggested tier: minimum
    @draft @allium-rule-failure.RecordRegeneratedCandidate.4 @allium-invariant.AttemptsWithinBudget
    Scenario: No candidate is regenerated beyond the budget's gate-attempt limit
      Given cycle C-7 has a budget of 2 gate attempts
      And gate attempts 1 and 2 of cycle C-7 failed
      When ORC runtime records a candidate regenerated for cycle C-7, attempt 3, by verification repair
      Then the regenerated candidate is refused
      And the cycle progress view shows cycle C-7 with 2 attempts, never more than its budget allows

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

    # regeneration-cycle.allium, rule RecordRegeneratedCandidate (requires 9);
    # surface BuilderRoomBoundary, guarantee NoScoreEvidenceInABuilderRoom
    # Suggested tier: minimum
    @draft @allium-rule-failure.RecordRegeneratedCandidate.9 @allium-guarantee.NoScoreEvidenceInABuilderRoom
    Scenario Outline: Only a repair room mounts evidence, and only this cycle's gate evidence
      Given <given>
      When ORC runtime records a candidate regenerated for cycle C-7, attempt <attempt>, by <purpose>, its room mounting <mounted>
      Then the regenerated candidate is refused
      And the cycle progress view shows cycle C-7 with <attempts> attempts, unchanged

      Examples:
        | given                                                          | attempt | purpose             | mounted                             | attempts |
        | the clean room of cycle C-7 was proved                         | 1       | fresh generation    | gate evidence root E-0              | 0        |
        | gate attempt 1 of cycle C-7 failed with gate evidence root E-1 | 2       | verification repair | the score evidence of cycle C-6     | 1        |
        | gate attempt 1 of cycle C-7 failed with gate evidence root E-1 | 2       | verification repair | gate evidence root E-5 of cycle C-6 | 1        |

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

    # regeneration-cycle.allium, rule ConcludeOnExhaustedGateAttempts
    # Legacy: verify.sh (a red gate ends the build; now 3 gate attempts and a repair builder)
    # Suggested tier: minimum
    @draft @allium-rule-success.ConcludeOnExhaustedGateAttempts
    Scenario: The last gate attempt the budget allows fails, and the cycle fails without a score
      Given cycle C-7 has a budget of 3 gate attempts
      And gate attempts 1 and 2 of cycle C-7 failed
      And candidate D-3 was regenerated by verification repair and installed in a gate room for attempt 3
      When ORC runtime records gate attempt 3 of cycle C-7 failed with reason boot budget exhausted
      Then the conclude-cycle policy fails cycle C-7 at the gate stage with reason gate attempts exhausted
      And the cycle progress view shows cycle C-7 as "no score"

    # regeneration-cycle.allium, rules NoteScoreStageFailed and ConcludeOnFailedScoreStage
    # Suggested tier: minimum
    @draft @allium-rule-success.NoteScoreStageFailed @allium-rule-success.ConcludeOnFailedScoreStage
    Scenario: A failed score stage fails the cycle at the score
      Given gate attempt 1 of cycle C-7 passed
      When the measurement records the score stage of cycle C-7 failed, its score refused for zero verdicts
      Then the conclude-cycle policy fails cycle C-7 at the score stage with reason score stage failed
      And no score is retaken for cycle C-7

    # regeneration-cycle.allium, rules NoteScoreboardReported, ConcludeOnReportedScoreboard and PassCycle;
    # invariant PassedOnlyMeasuredAndReported
    # Suggested tier: minimum
    @draft @allium-rule-success.NoteScoreboardReported @allium-rule-success.ConcludeOnReportedScoreboard @allium-rule-success.PassCycle @allium-invariant.PassedOnlyMeasuredAndReported
    Scenario: A cycle passes once its scoreboard is reported
      Given candidate D-1 of cycle C-7 passed gate attempt 1
      And the measurement of cycle C-7 was filed
      When the measurement reports the scoreboard of cycle C-7
      Then the conclude-cycle policy passes cycle C-7 with candidate D-1
      And the cycle progress view shows cycle C-7 passed at the result stage

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

    # regeneration-cycle.allium, rule FailCycle; surface CycleStages, guarantee OneConclusion
    # Legacy: fence.sh (a fence failure exits 1; now it fails the cycle)
    # Suggested tier: minimum
    @draft @allium-rule-success.FailCycle
    Scenario: A refused fence fails the cycle at the fence
      Given a passing harness self-check before the fence opened cycle C-7 at cut K-3
      And the clean room of cycle C-7 was refused because the fenced path "harness-impl/" matched nothing
      When ORC runtime fails cycle C-7 at the fence stage with reason fence refused, naming that refusal
      Then cycle C-7 is recorded failed at the fence stage with reason fence refused and that detail
      And the cycle progress view shows cycle C-7 as "no score"

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

  Rule: The instrument's facts only conclude the cycle, and never feed the build loop

    # regeneration-cycle.allium, rule NoteMeasurementFiled; surface MeasurementHandoffs, guarantee ConcludeOnly
    # Suggested tier: minimum
    @draft @allium-rule-success.NoteMeasurementFiled @allium-guarantee.ConcludeOnly
    Scenario: A filed measurement moves the cycle to the scoreboard and nothing else
      Given candidate D-1 of cycle C-7 passed gate attempt 1
      When the measurement of cycle C-7 is filed
      Then the cycle progress view shows cycle C-7 running at the scoreboard stage with 1 attempt
      And no candidate is regenerated and no gate attempt is recorded for cycle C-7

  Rule: The operator observes a cycle, and observing changes nothing

    # regeneration-cycle.allium, surface CycleProgress, guarantee ObservingChangesNothing
    # Suggested tier: balance
    @draft @allium-guarantee.ObservingChangesNothing
    Scenario: Reading the cycle progress view records nothing
      Given cycle C-7 is running at the gate stage with 1 attempt
      When the operator reads the cycle progress view of cycle C-7 twice
      Then both readings show cycle C-7 at the gate stage with 1 attempt
      And no stage fact is recorded for cycle C-7

