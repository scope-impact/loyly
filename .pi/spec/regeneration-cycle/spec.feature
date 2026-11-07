Feature: Regeneration cycle
  The product owner's accepted scenarios of this context. Each is stamped with an @item id
  that is never renumbered or reused; the checklist is one item per stamped scenario. Drafts
  not yet accepted stay in drafts.feature.

  Rule: No clean room is built over an unchecked instrument: the harness self-check opens the cycle before the fence

    # harness-self-check.allium, rules RecordHarnessSelfCheck, NotePinnedCut and NoteGoldenWritten; regeneration-cycle.allium, rule OpenCycle
    # Legacy: @harness-065
    @minimum @allium-rule-success.RecordHarnessSelfCheck @allium-rule-success.NotePinnedCut @allium-rule-success.NoteGoldenWritten @allium-rule-success.OpenCycle
    @item-04-001
    Scenario: A passing harness self-check before the fence opens the cycle on a pinned source cut
      Given the operator pinned source cut K-3
      And the instrument goldens "checklist" and "scoreboard-01" were written, goldens digest G-1
      When ORC runtime records the harness self-check of cycle C-7 before the fence, attempt 0, on source cut K-3 at goldens digest G-1 and approved grader digests D-1, outcome passed, with a budget of 3 gate attempts
      Then harness self-check passed is recorded for cycle C-7 before the fence
      And it names harness digest, goldens digest G-1, approved grader digests D-1, its identity probe and the budget
      And the cycle progress view shows cycle C-7 running at the fence stage for source cut K-3, with the budget of 3 gate attempts, 0 attempts and no candidate

    # harness-self-check.allium, rule RecordHarnessSelfCheck (requires 2); contract HarnessSelfCheckRun, invariant NeverReusedAcrossAChange; rule NoteGoldenMoved
    # Legacy: @harness-065
    @minimum @allium-rule-failure.RecordHarnessSelfCheck.2 @allium-boundary.NeverReusedAcrossAChange @allium-rule-success.NoteGoldenMoved
    @item-04-002
    Scenario: A harness self-check at goldens that are no longer current is refused
      Given source cut K-3 was pinned
      And the instrument golden "checklist" was written, goldens digest G-1
      And the instrument golden "checklist" was moved with reason spec grew, so the current goldens digest is G-2
      When ORC runtime records the harness self-check of cycle C-7 before the fence at goldens digest G-1, outcome passed
      Then the harness self-check is refused
      And no regeneration cycle C-7 exists

    # contract HarnessSelfCheckRun, invariant ReproducesEveryGolden
    # Legacy: oracle.sh (legacy-trace 60)
    @minimum @allium-boundary.ReproducesEveryGolden
    @item-04-003
    Scenario: A golden the harness self-check does not reproduce exactly fails it, by name
      Given source cut K-3 was pinned
      And the instrument golden "score-refusal" expects the refusal reason "unknown item"
      And the tools at harness digest H-1 give the refusal reason "unknown id"
      When ORC runtime runs the harness self-check of cycle C-7 before the fence
      Then harness self-check failed is recorded for cycle C-7
      And its failing check names the instrument golden "score-refusal"

    # harness-self-check.allium, rule RecordHarnessSelfCheck (requires 6)
    # Legacy: @harness-065
    @minimum @allium-rule-failure.RecordHarnessSelfCheck.6
    @item-04-004
    Scenario: A harness self-check before a gate attempt with no candidate for that attempt is refused
      Given harness self-check passed was recorded for cycle C-7 before the fence on source cut K-3
      And the clean room of cycle C-7 was proved
      And candidate regenerated for cycle C-7, attempt 1
      When ORC runtime records the harness self-check of cycle C-7 before the gate, attempt 2
      Then the harness self-check is refused
      And cycle C-7's harness self-checks are unchanged

    # harness-self-check.allium, rule RecordHarnessSelfCheck (requires 8); contract HarnessSelfCheckRun, invariant GoldenRequiredWhereItsCycleExists
    # Legacy: @harness-060, @harness-061
    @minimum @allium-rule-failure.RecordHarnessSelfCheck.8 @allium-boundary.GoldenRequiredWhereItsCycleExists
    @item-04-005
    Scenario: A cycle with scores and no scoreboard golden cannot pass the harness self-check
      Given source cut K-3 was pinned
      And source cut K-3 holds the scores of cycle 01 and no instrument golden for its scoreboard
      When ORC runtime records the harness self-check of cycle C-7 before the fence, outcome passed, with missing golden "scoreboard-01"
      Then the harness self-check is refused
      And no regeneration cycle C-7 exists

    # contract HarnessSelfCheckRun, invariant GoldenRequiredWhereItsCycleExists
    # Legacy: @harness-061
    @minimum @allium-boundary.GoldenRequiredWhereItsCycleExists
    @item-04-006
    Scenario: An instrument golden with no cycle beside it does not fail the harness self-check
      Given source cut K-3 was pinned
      And the instrument golden "scoreboard-01" was written
      And the clean room holds no cycle 01, because the fence removed it
      When ORC runtime runs the harness self-check of cycle C-7 before the fence
      Then harness self-check passed is recorded for cycle C-7
      And it lists no missing golden

    # contract HarnessSelfCheckRun, invariant MissingToolIsALoudSkip
    # Legacy: @harness-066
    @minimum @allium-boundary.MissingToolIsALoudSkip
    @item-04-007
    Scenario: A scoring tool fenced out of the clean room is a listed skip, not a failure
      Given source cut K-3 was pinned
      And the tree the harness self-check runs in has no scoring tool "score", because the fence removed it
      When ORC runtime runs the harness self-check of cycle C-7 before the gate, attempt 1
      Then harness self-check passed is recorded for cycle C-7 before the gate, attempt 1
      And its skipped checks name "score"

  Rule: The fence proves the clean room; it never asserts it

    # clean-room.allium, rule ProveCleanRoom; surface Fence, guarantee ProvedNotAsserted; regeneration-cycle.allium, rule NoteCleanRoomProved
    # Legacy: fence.sh (legacy-trace 63 to 65), @harness-065
    @minimum @allium-rule-success.ProveCleanRoom @allium-guarantee.ProvedNotAsserted @allium-rule-success.NoteCleanRoomProved
    @item-04-008
    Scenario: A clean room is proved when every check of the fence's probe holds, and the cycle moves to regenerate
      Given harness self-check passed was recorded for cycle C-7 before the fence on source cut K-3
      When ORC runtime records the clean room of cycle C-7 from source cut K-3 with one root commit, one commit, fenced paths "harness/score.clj" and "harness/cycles", every fenced path matched, none left in the tree and none reachable in the object graph
      Then clean room proved is recorded for cycle C-7, naming its commit and its fenced paths
      And the cycle progress view shows cycle C-7 at the regenerate stage with its clean room proved

    # clean-room.allium, rule ProveCleanRoom (requires 1); surface HarnessSelfCheckRecording, guarantee NoRoomOverAnUncheckedInstrument
    # Legacy: @harness-065
    @minimum @allium-rule-failure.ProveCleanRoom.1 @allium-guarantee.NoRoomOverAnUncheckedInstrument
    @item-04-009
    Scenario: No clean room is proved after a failed harness self-check
      Given harness self-check failed was recorded for cycle C-7 before the fence, failing check "golden checklist differs"
      When ORC runtime records the clean room of cycle C-7 from source cut K-3
      Then the clean room is refused
      And cycle C-7 has no clean room

    # clean-room.allium, rule ProveCleanRoom (requires 2); contract FenceProbe, invariant AFencedPathThatMatchesNothingIsAFault
    # Legacy: fence.sh (legacy-trace 63)
    @minimum @allium-rule-failure.ProveCleanRoom.2 @allium-boundary.AFencedPathThatMatchesNothingIsAFault
    @item-04-010
    Scenario: A fenced path that matches nothing stops the fence
      Given harness self-check passed was recorded for cycle C-7 before the fence on source cut K-3
      And source cut K-3 has no "harness/scroe.clj"
      When ORC runtime records the clean room of cycle C-7 with fenced path "harness/scroe.clj" unmatched
      Then the clean room is refused, naming "harness/scroe.clj" as a fenced path that matched nothing
      And cycle C-7 has no clean room

    # clean-room.allium, rule ProveCleanRoom (requires 3)
    # Legacy: fence.sh (legacy-trace 64)
    @minimum @allium-rule-failure.ProveCleanRoom.3
    @item-04-011
    Scenario: A clean room with two root commits is refused
      Given harness self-check passed was recorded for cycle C-7 before the fence on source cut K-3
      When ORC runtime records the clean room of cycle C-7 with two root commits
      Then the clean room is refused
      And cycle C-7 has no clean room

    # clean-room.allium, rule ProveCleanRoom (requires 4)
    # Legacy: fence.sh (legacy-trace 64)
    @minimum @allium-rule-failure.ProveCleanRoom.4
    @item-04-012
    Scenario: A clean room whose history is more than one commit is refused
      Given harness self-check passed was recorded for cycle C-7 before the fence on source cut K-3
      When ORC runtime records the clean room of cycle C-7 with one root commit and three commits
      Then the clean room is refused
      And cycle C-7 has no clean room

    # clean-room.allium, rule ProveCleanRoom (requires 5)
    # Legacy: fence.sh (legacy-trace 65)
    @minimum @allium-rule-failure.ProveCleanRoom.5
    @item-04-013
    Scenario: A fenced path left in the working tree is refused
      Given harness self-check passed was recorded for cycle C-7 before the fence on source cut K-3
      When ORC runtime records the clean room of cycle C-7 with fenced path "harness/cycles" still in the working tree
      Then the clean room is refused, naming "harness/cycles"
      And cycle C-7 has no clean room

    # clean-room.allium, rule ProveCleanRoom (requires 6); contract FenceProbe, invariant ObjectGraphNotWorkingTree
    # Legacy: fence.sh (legacy-trace 65)
    @minimum @allium-rule-failure.ProveCleanRoom.6 @allium-boundary.ObjectGraphNotWorkingTree
    @item-04-014
    Scenario: A prior candidate reachable in the object graph is refused even when the working tree is clean
      Given harness self-check passed was recorded for cycle C-7 before the fence on source cut K-3
      And the prior candidate under "harness-impl" is absent from the clean room's working tree
      And it is still reachable from an object of the clean room's history
      When ORC runtime records the clean room of cycle C-7 with "harness-impl" reachable in the object graph
      Then the clean room is refused, naming "harness-impl"
      And cycle C-7 has no clean room

    # clean-room.allium, rule ProveCleanRoom (requires 9)
    # Legacy: @harness-068
    @minimum @allium-rule-failure.ProveCleanRoom.9
    @item-04-015
    Scenario: A clean room that keeps a scoring tool is refused
      Given the scoring tools are "harness/score.clj" and "harness/scoreboard.clj"
      And harness self-check passed was recorded for cycle C-7 before the fence on source cut K-3
      When ORC runtime records the clean room of cycle C-7 with fenced path "harness/score.clj" only
      Then the clean room is refused, naming "harness/scoreboard.clj" as a scoring tool not fenced
      And cycle C-7 has no clean room

    # clean-room.allium, invariant AFenceHidesSomething
    # Legacy: fence.sh (legacy-trace 62)
    @minimum @allium-invariant.AFenceHidesSomething
    @item-04-016
    Scenario: A fence with no fenced paths proves no clean room
      Given harness self-check passed was recorded for cycle C-7 before the fence on source cut K-3
      When ORC runtime records the clean room of cycle C-7 with no fenced paths
      Then the clean room is refused
      And cycle C-7 has no clean room

  Rule: The gate records one verdict per attempt, with the boot budget and its margin

    # gate-attempt.allium, rule RecordGateAttempt; invariant AnsweredInsideTheBudget; surface Gate, guarantee TheMarginIsSaidOutLoud;
    # regeneration-cycle.allium, rule NoteGateAttemptPassed
    # Legacy: @harness-056
    @minimum @allium-rule-success.RecordGateAttempt @allium-invariant.AnsweredInsideTheBudget @allium-guarantee.TheMarginIsSaidOutLoud @allium-rule-success.NoteGateAttemptPassed
    @item-04-017
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
    @minimum @allium-rule-success.RecordGateAttempt @allium-guarantee.TheMarginIsSaidOutLoud @allium-rule-success.NoteGateAttemptFailed @allium-invariant.FailedAttemptsNeverExceedAttempts
    @item-04-018
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
    @minimum @allium-boundary.BootClockStartsAtLaunch
    @item-04-019
    Scenario: The boot clock covers everything the launch entrypoint does, a build included
      Given candidate D-1 was installed in gate room G-1 for gate attempt 1 of regeneration cycle C-7
      And the launch entrypoint of D-1 builds for 30 seconds before it serves its first page
      And its suite would be green
      When the gate boots D-1 with a boot budget of 10 seconds as 20 polls
      Then gate attempt 1 fails with reason boot-budget-exhausted
      And a build inside the suite would not have counted against the boot budget

    # gate-attempt.allium, rule RecordGateAttempt (requires 7); harness-self-check.allium, surface HarnessSelfCheckRecording, guarantee NoRoomOverAnUncheckedInstrument (the gate half)
    # Legacy: @harness-065
    @minimum @allium-rule-failure.RecordGateAttempt.7 @allium-guarantee.NoRoomOverAnUncheckedInstrument
    @item-04-020
    Scenario: No candidate is graded over a harness the self-check did not check
      Given candidate D-1 was installed in gate room G-1 for gate attempt 1 of regeneration cycle C-7
      And the harness self-check before gate attempt 1 passed at harness digest H-1
      When ORC runtime records gate attempt 1 passed at harness digest H-2
      Then the verdict is refused
      And gate attempt 1 still awaits its verdict and is not spent

    # gate-attempt.allium, rule RecordGateAttempt (requires 8)
    # Legacy: @harness-053
    @minimum @allium-rule-failure.RecordGateAttempt.8
    @item-04-021
    Scenario: The gate refuses to grade without the identity probe
      Given candidate D-1 was installed in gate room G-1 for gate attempt 1 of regeneration cycle C-7
      And the harness self-check before gate attempt 1 passed
      When ORC runtime records gate attempt 1 passed while gate room G-1 has no identity probe
      Then the verdict is refused
      And gate attempt 1 still awaits its verdict and is not spent

    # gate-attempt.allium, rule RecordGateAttempt (requires 9); contract GateRoom, invariant IdentityProbeIsTheInstruments
    # Legacy: @harness-053
    @minimum @allium-rule-failure.RecordGateAttempt.9 @allium-boundary.IdentityProbeIsTheInstruments
    @item-04-022
    Scenario: An identity probe that is not the one the self-check checked does not judge the candidate
      Given candidate D-1 was installed in gate room G-1 for gate attempt 1 of regeneration cycle C-7
      And the harness self-check before gate attempt 1 passed with identity probe digest P-1
      And candidate D-1 brings an identity probe of its own, digest P-9
      When ORC runtime records gate attempt 1 passed with identity probe digest P-9
      Then the verdict is refused
      And gate attempt 1 still awaits its verdict and is not spent

  Rule: A failed harness self-check fails the cycle, and a cycle failed before the score has no score

    # regeneration-cycle.allium, rule NoteFailedSelfCheck; invariant FailedBeforeScoreHasNoMeasurement;
    # surface CycleProgress, guarantee NoScoreIsNotAllFailed
    # Legacy: @harness-065
    @minimum @allium-rule-success.NoteFailedSelfCheck @allium-invariant.FailedBeforeScoreHasNoMeasurement @allium-guarantee.NoScoreIsNotAllFailed
    @item-04-023
    Scenario: A failed harness self-check before the fence fails the cycle at the fence with no score
      Given cut K-3 was pinned
      When ORC runtime records a failed harness self-check before the fence for cycle C-7, naming the check "instrument golden scoreboard-arithmetic"
      Then cycle C-7 exists and fails at the fence stage with reason harness self-check failed, naming that check
      And no clean room is proved for cycle C-7
      And the cycle progress view shows cycle C-7 as "no score", never as every item failed

    # regeneration-cycle.allium, rule ConcludeOnFailedSelfCheck
    # Legacy: @harness-065
    @minimum @allium-rule-success.ConcludeOnFailedSelfCheck
    @item-04-024
    Scenario: A failed harness self-check before a gate attempt fails the cycle at the gate and spends no attempt
      Given cycle C-7 is running at the gate stage with 1 failed gate attempt and its candidate for attempt 2 regenerated
      When ORC runtime records a failed harness self-check before the gate for cycle C-7, attempt 2
      Then the conclude-cycle policy fails cycle C-7 at the gate stage with reason harness self-check failed
      And no candidate is installed in a gate room for attempt 2
      And the cycle progress view still shows 1 failed gate attempt

  Rule: A candidate is regenerated for the next gate attempt, within the budget

    # regeneration-cycle.allium, rule RecordRegeneratedCandidate (requires 2)
    # Legacy: @harness-065
    @minimum @allium-rule-failure.RecordRegeneratedCandidate.2
    @item-04-025
    Scenario: No candidate is regenerated before the clean room is proved
      Given a passing harness self-check before the fence opened cycle C-7
      And no clean room is proved for cycle C-7
      When ORC runtime records a candidate regenerated for cycle C-7, attempt 1, by fresh generation
      Then the regenerated candidate is refused
      And the cycle progress view shows cycle C-7 at the fence stage with 0 attempts

  Rule: A cycle concludes once, passed after its scoreboard or failed at a named stage for a named reason

    # regeneration-cycle.allium, rule ConcludeOnExhaustedGateAttempts
    # Legacy: verify.sh (a red gate ends the build; now 3 gate attempts and a repair builder)
    @minimum @allium-rule-success.ConcludeOnExhaustedGateAttempts
    @item-04-026
    Scenario: The last gate attempt the budget allows fails, and the cycle fails without a score
      Given cycle C-7 has a budget of 3 gate attempts
      And gate attempts 1 and 2 of cycle C-7 failed
      And candidate D-3 was regenerated by verification repair and installed in a gate room for attempt 3
      When ORC runtime records gate attempt 3 of cycle C-7 failed with reason boot budget exhausted
      Then the conclude-cycle policy fails cycle C-7 at the gate stage with reason gate attempts exhausted
      And the cycle progress view shows cycle C-7 as "no score"

    # regeneration-cycle.allium, rule FailCycle; surface CycleStages, guarantee OneConclusion
    # Legacy: fence.sh (a fence failure exits 1; now it fails the cycle)
    @minimum @allium-rule-success.FailCycle
    @item-04-027
    Scenario: A refused fence fails the cycle at the fence
      Given a passing harness self-check before the fence opened cycle C-7 at cut K-3
      And the clean room of cycle C-7 was refused because the fenced path "harness-impl/" matched nothing
      When ORC runtime fails cycle C-7 at the fence stage with reason fence refused, naming that refusal
      Then cycle C-7 is recorded failed at the fence stage with reason fence refused and that detail
      And the cycle progress view shows cycle C-7 as "no score"

  Rule: No clean room is built over an unchecked instrument: the harness self-check opens the cycle before the fence

    # harness-self-check.allium, rule RecordHarnessSelfCheck (requires 9); contract HarnessSelfCheckRun, invariant RewordedScenarioFailsItsGolden
    @minimum @allium-rule-failure.RecordHarnessSelfCheck.9 @allium-boundary.RewordedScenarioFailsItsGolden
    @item-04-028
    Scenario: A golden whose scenario was reworded since it was written fails the harness self-check
      Given source cut K-3 was pinned
      And the instrument golden "checklist" was written from its scenario at scenario digest S-1
      And that scenario was reworded, so its scenario digest is now S-2
      When ORC runtime records the harness self-check of cycle C-7 before the fence, outcome passed, with reworded golden "checklist"
      Then the harness self-check is refused
      And no regeneration cycle C-7 exists

  Rule: The gate installs the regenerated candidate in a fresh gate room ORC chose

    # gate-attempt.allium, rule InstallCandidateInGateRoom
    @minimum @allium-rule-success.InstallCandidateInGateRoom
    @item-04-029
    Scenario: The candidate of an attempt is installed in a fresh gate room
      Given the clean room of regeneration cycle C-7 was proved
      And candidate D-1 was regenerated for gate attempt 1 by fresh generation
      When ORC runtime records candidate D-1 installed in gate room G-1 for gate attempt 1
      Then candidate D-1 is installed, read-only, in gate room G-1 for gate attempt 1
      And gate attempt 1 of C-7 awaits its verdict

    # gate-attempt.allium, invariant AtMostThreeAttempts
    @minimum @allium-invariant.AtMostThreeAttempts
    @item-04-030
    Scenario: A cycle never has a fourth gate attempt
      Given gate attempts 1, 2 and 3 of regeneration cycle C-7 failed with reason suite-red
      When ORC runtime records candidate D-4 installed in gate room G-4 for gate attempt 4
      Then the installation is refused
      And C-7 has three gate attempts

  Rule: The gate records one verdict per attempt, with the boot budget and its margin

    # gate-attempt.allium, rule RecordGateAttempt (requires 2); invariant OneVerdictPerAttempt
    @minimum @allium-rule-failure.RecordGateAttempt.2 @allium-invariant.OneVerdictPerAttempt
    @item-04-031
    Scenario: A replayed verdict records nothing and spends no attempt
      Given gate attempt 1 of regeneration cycle C-7 failed with reason suite-red
      When ORC runtime records gate attempt 1 failed with reason suite-red again
      Then the verdict is refused
      And gate attempt 1 has one verdict, failed
      And C-7 has spent one gate attempt

    # gate-attempt.allium, rule RecordGateAttempt (requires 10); contract GateRoom, invariant SeamCheckedBeforeBoot;
    # surface Gate, guarantee ARefusalSpendsNoAttempt
    @minimum @allium-rule-failure.RecordGateAttempt.10 @allium-boundary.SeamCheckedBeforeBoot @allium-guarantee.ARefusalSpendsNoAttempt
    @item-04-032
    Scenario: A seam that already responded before boot is not graded and spends no attempt
      Given candidate D-1 was installed in gate room G-1 for gate attempt 1 of regeneration cycle C-7
      And the harness self-check before gate attempt 1 passed
      And an orphan of an earlier run already responds on the public seam of gate room G-1
      When ORC runtime records gate attempt 1 passed with the seam responding before boot
      Then the verdict is refused
      And gate attempt 1 still awaits its verdict
      And C-7 has spent no gate attempt

  Rule: A candidate is regenerated for the next gate attempt, within the budget

    # regeneration-cycle.allium, rule RecordRegeneratedCandidate; surface BuilderRoomBoundary, guarantee DoneIsTheGatesVerdict
    @minimum @allium-rule-success.RecordRegeneratedCandidate @allium-guarantee.DoneIsTheGatesVerdict
    @item-04-033
    Scenario: The first candidate is a fresh generation for gate attempt 1, and a claim, not done
      Given the clean room of cycle C-7 was proved
      When ORC runtime records the candidate D-1 regenerated for cycle C-7, attempt 1, by fresh generation, mounting no evidence, its builder session reporting the slice done
      Then a candidate regenerated for attempt 1 is recorded with digest D-1
      And the cycle progress view shows cycle C-7 running at the gate stage with 1 attempt and candidate D-1, not passed
      And cycle C-7 concludes only after a gate attempt is recorded in a gate room the builder does not control

    # regeneration-cycle.allium, rule RecordRegeneratedCandidate (requires 4); invariant AttemptsWithinBudget
    @minimum @allium-rule-failure.RecordRegeneratedCandidate.4 @allium-invariant.AttemptsWithinBudget
    @item-04-034
    Scenario: No candidate is regenerated beyond the budget's gate-attempt limit
      Given cycle C-7 has a budget of 2 gate attempts
      And gate attempts 1 and 2 of cycle C-7 failed
      When ORC runtime records a candidate regenerated for cycle C-7, attempt 3, by verification repair
      Then the regenerated candidate is refused
      And the cycle progress view shows cycle C-7 with 2 attempts, never more than its budget allows

    # regeneration-cycle.allium, rule RecordRegeneratedCandidate (requires 9);
    # surface BuilderRoomBoundary, guarantee NoScoreEvidenceInABuilderRoom
    @minimum @allium-rule-failure.RecordRegeneratedCandidate.9 @allium-guarantee.NoScoreEvidenceInABuilderRoom
    @item-04-035
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

  Rule: A cycle concludes once, passed after its scoreboard or failed at a named stage for a named reason

    # regeneration-cycle.allium, rules NoteScoreStageFailed and ConcludeOnFailedScoreStage
    @minimum @allium-rule-success.NoteScoreStageFailed @allium-rule-success.ConcludeOnFailedScoreStage
    @item-04-036
    Scenario: A failed score stage fails the cycle at the score
      Given gate attempt 1 of cycle C-7 passed
      When the measurement records the score stage of cycle C-7 failed, its score refused for zero verdicts
      Then the conclude-cycle policy fails cycle C-7 at the score stage with reason score stage failed
      And no score is retaken for cycle C-7

    # regeneration-cycle.allium, rules NoteScoreboardReported, ConcludeOnReportedScoreboard and PassCycle;
    # invariant PassedOnlyMeasuredAndReported
    @minimum @allium-rule-success.NoteScoreboardReported @allium-rule-success.ConcludeOnReportedScoreboard @allium-rule-success.PassCycle @allium-invariant.PassedOnlyMeasuredAndReported
    @item-04-037
    Scenario: A cycle passes once its scoreboard is reported
      Given candidate D-1 of cycle C-7 passed gate attempt 1
      And the measurement of cycle C-7 was filed
      When the measurement reports the scoreboard of cycle C-7
      Then the conclude-cycle policy passes cycle C-7 with candidate D-1
      And the cycle progress view shows cycle C-7 passed at the result stage

  Rule: The instrument's facts only conclude the cycle, and never feed the build loop

    # regeneration-cycle.allium, rule NoteMeasurementFiled; surface MeasurementHandoffs, guarantee ConcludeOnly
    @minimum @allium-rule-success.NoteMeasurementFiled @allium-guarantee.ConcludeOnly
    @item-04-038
    Scenario: A filed measurement moves the cycle to the scoreboard and nothing else
      Given candidate D-1 of cycle C-7 passed gate attempt 1
      When the measurement of cycle C-7 is filed
      Then the cycle progress view shows cycle C-7 running at the scoreboard stage with 1 attempt
      And no candidate is regenerated and no gate attempt is recorded for cycle C-7

  Rule: A cycle concludes once, passed after its scoreboard or failed at a named stage for a named reason

    # regeneration-cycle.allium, rule NoteScoreboardReported (requires 2); invariant PassedOnlyMeasuredAndReported
    @minimum @allium-rule-failure.NoteScoreboardReported.2 @allium-invariant.PassedOnlyMeasuredAndReported
    @item-04-039
    Scenario: A scoreboard reported with no measurement filed does not pass the cycle
      Given candidate D-1 of cycle C-7 passed gate attempt 1
      When the measurement reports the scoreboard of cycle C-7, with no measurement of cycle C-7 filed
      Then the cycle progress view shows cycle C-7 running at the score stage
      And cycles-to-conclude does not list cycle C-7

    # regeneration-cycle.allium, rule NoteMeasurementFiled (requires 2)
    @minimum @allium-rule-failure.NoteMeasurementFiled.2
    @item-04-040
    Scenario: A measurement filed after the cycle failed leaves it where it failed
      Given candidate D-1 of cycle C-7 passed gate attempt 1
      And ORC runtime failed cycle C-7 at the score stage with reason budget
      When the measurement of cycle C-7 is filed
      Then the cycle progress view shows cycle C-7 failed at the score stage with reason budget, its stage still score
