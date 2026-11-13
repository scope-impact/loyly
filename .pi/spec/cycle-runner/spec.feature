Feature: Cycle runner
  The product owner's accepted scenarios of this context. Each is stamped with an @item id
  that is never renumbered or reused; the checklist is one item per stamped scenario. Drafts
  not yet accepted stay in drafts.feature.
  Every scenario is driven through the runner's seam with the effects handed in (seam.md, "How
  a grader drives it without a live pi"): a fixture project repository at commit
  "4f2a9c1e0b7d3a5f9c2e8b1d4a6f0c3e7b9d2a51" with a plan of two stamped scenarios, 01-001 and
  01-002, a passing self-check, a stub builder, the five gated candidates of slices 01 to 05, a
  budget of 3 gate attempts, and the approved grader digest "r-1", unless a scenario says
  otherwise.

  Rule: A run starts once, one at a time, on a commit of the project repository, with its five contexts responding

    # cycle-run.allium, rule StartRun; rule RecordEffect; invariant OneResultPerPosition
    # Legacy: README "A cycle": fence.sh, a builder session, verify.sh, in that order
    @minimum @allium-rule-success.StartRun @allium-rule-success.RecordEffect @allium-invariant.OneResultPerPosition
    @item-06-001
    Scenario: The operator starts a run, and it performs the cycle's effects up to the gate's verdict
      Given the stub builder leaves a candidate whose suite is green and whose launch entrypoint serves the marker on the port
      When the operator starts run R-1 of cycle C-1 at source cut K-1, commit "4f2a9c1e0b7d3a5f9c2e8b1d4a6f0c3e7b9d2a51"
      Then run R-1 awaits its verdicts
      And its effect results, at positions 1 to 9, are stamp, pin, checklist, self-check, fence, regenerate, install, self-check and gate, each recorded
      And the gate effect's events are "regeneration-cycle/gate-attempt-passed"

    # cycle-run.allium, invariant OneRunPerIdentity (rule StartRun requires 1, unit level)
    @balance @allium-invariant.OneRunPerIdentity
    @item-06-002
    Scenario: A run identity is started once
      Given run R-1 of cycle C-1 concluded failed at the fence
      When the operator starts run R-1 of cycle C-2
      Then the start is refused
      And the run progress lists run R-1 once, for cycle C-1

    # cycle-run.allium, invariant OneRunInProgress (rule StartRun requires 2, unit level)
    # Legacy: one cycle at a time, one port, one builder (config.sh PORT)
    @minimum @allium-invariant.OneRunInProgress
    @item-06-003
    Scenario: A second run is refused while the first awaits its verdicts
      Given run R-1 of cycle C-1 awaits its verdicts
      When the operator starts run R-2 of cycle C-2
      Then the start is refused
      And no run R-2 exists, and run R-1 still awaits its verdicts

    # cycle-run.allium, invariant OneRunPerCycle (rule StartRun requires 3, unit level)
    @balance @allium-invariant.OneRunPerCycle
    @item-06-004
    Scenario: A cycle is run once
      Given run R-1 of cycle C-1 concluded passed
      When the operator starts run R-2 of cycle C-1
      Then the start is refused
      And no run R-2 exists

    # cycle-run.allium, rule StartRun (requires 4)
    @minimum @allium-rule-failure.StartRun.4
    @item-06-005
    Scenario: A run of something that is not a commit of the project repository is refused
      Given "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef" is no commit of the project repository
      When the operator starts run R-1 of cycle C-1 at source cut K-1, commit "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"
      Then the start is refused
      And no run R-1 exists, and no builder was launched

    # cycle-run.allium, rule StartRun (requires 5)
    @balance @allium-rule-failure.StartRun.5
    @item-06-006
    Scenario: A run whose context does not respond on its seam is refused
      Given the launch entrypoint handed in for Measurement exits at once
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the start is refused, naming measurement
      And no run R-1 exists

  Rule: The runner stamps, pins and derives the checklist, and relays every handoff from its producer's store

    # cycle-run.allium, rule RelayHandoff; surface EffectNodes, guarantee InTheProducersOrder; invariant EachHandoffOncePerConsumer
    # Legacy: harness/v2/assemble.clj, the relay (decisions.md, "Spec round 5" 1 and 2)
    @minimum @allium-rule-success.RelayHandoff @allium-guarantee.InTheProducersOrder @allium-invariant.EachHandoffOncePerConsumer
    @item-06-007
    Scenario: Each handoff is handed in, unchanged and once, to every consumer, in its producer's order
      Given the stub builder leaves a candidate the gate passes
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the relayed handoffs of run R-1 begin with specification/scenario-stamped at positions 1 and 2 to measurement, then cycle-preparation/source-cut-pinned at position 1 to measurement and to regeneration-cycle
      And regeneration-cycle/gate-attempt-passed at position 1 is relayed to measurement once
      And every relayed handoff was accepted

    # context.allium, contract ContextSeams, invariants RefusalsAreTheContexts and OnlyThroughTheSeams; surface EffectNodes, guarantee OwnsNoContextRule
    # Legacy: AGENTS.md "an unstamped scenario or an empty selection stops the cycle"
    @minimum @allium-boundary.RefusalsAreTheContexts @allium-boundary.OnlyThroughTheSeams @allium-guarantee.OwnsNoContextRule @allium-rule-success.HaltRun
    @item-06-008
    Scenario: A checklist Measurement refuses halts the run with Measurement's refusal
      Given the plan at the commit holds only a spec file with no scenario
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the checklist effect of run R-1 was refused "empty-checklist", as measurement answered
      And run R-1 is halted at the checklist effect for a refusal before the cycle
      And it has no self-check effect and no fence effect

    # cycle-run.allium, rule HaltRun (the restamped identities are not the plan's; decisions.md, "Spec round 7b" 2)
    # Legacy: AGENTS.md "an unstamped scenario or an empty selection stops the cycle"
    @minimum @allium-rule-success.HaltRun
    @item-06-009
    Scenario: A plan whose item identities a fresh stamp does not give back halts the run before the cycle
      Given the plan at the commit also holds a scenario with no item identity
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the stamp effect of run R-1 was recorded, as specification answered
      And run R-1 is halted at the stamp effect for a refusal before the cycle
      And it has no pin effect and no checklist effect

  Rule: No clean room is built over a failed harness self-check

    # cycle-run.allium, rules ConcludeWithTheCycle and ConcludeRunFailed
    # Legacy: fence.sh calls check.sh --local before it builds a room
    @minimum @allium-rule-success.ConcludeWithTheCycle @allium-rule-success.ConcludeRunFailed
    @item-06-010
    Scenario: A failing self-check fails the cycle at the fence, and the run with it
      Given the self-check exits non-zero and its last line is "golden checklist differs"
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then run R-1 failed, its cycle failed at the fence for "harness-self-check-failed" with detail "golden checklist differs"
      And its last effect result is the self-check, and it has no fence effect and no regenerate effect

    # cycle-run.allium, rule RecordEffect (a skipped check is part of the recorded self-check)
    # Legacy: @harness-066, a missing tool is a loud skip
    @balance @allium-rule-success.RecordEffect
    @item-06-011
    Scenario: A check the self-check skips is recorded as a listed skip
      Given the self-check exits 0 and prints "SKIP bb-tools"
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the first self-check effect of run R-1 was recorded with the event "regeneration-cycle/harness-self-check-passed"
      And its detail names the skipped check "bb-tools"

  Rule: The fence builds the clean room of the cut and proves it, as legacy fence.sh does

    # context.allium, contract ContextSeams, invariant RefusalsAreTheContexts; rule ConcludeRunFailed
    # Legacy: fence.sh, "FENCE FAILED: X matches nothing"
    @minimum @allium-boundary.RefusalsAreTheContexts @allium-rule-success.ConcludeRunFailed
    @item-06-012
    Scenario: A fenced path that matches nothing fails the cycle at the fence
      Given the configuration fences "impl harness/cycles" and the commit holds impl but no harness/cycles
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the fence effect of run R-1 was refused "fenced-path-matched-nothing", as regeneration-cycle answered
      And its next effect, fail-cycle, was recorded, and run R-1 failed with its cycle at the fence for "fence-refused"
      And no builder was launched

    # context.allium, contract BuilderLaunch, invariant TheBuilderSeesOnlyItsMounts
    # Legacy: fence.sh, a clone of one commit with the fenced paths gone from the tree and the history
    @minimum @allium-boundary.TheBuilderSeesOnlyItsMounts
    @item-06-013
    Scenario: The fresh builder sees the clean room of one commit with no fenced path
      Given the configuration fences "impl harness/cycles" and the commit holds both
      And the stub builder records the paths and the commits its builder room holds
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the stub builder saw the plan, the configuration and an empty candidate scope
      And it saw one commit, and no path under impl or harness/cycles in the tree or the history

  Rule: A regenerate launches one fresh builder and records what it left in its candidate scope

    # context.allium, contract BuilderLaunch, invariant OneFreshBuilderPerRegenerate
    @balance @allium-boundary.OneFreshBuilderPerRegenerate
    @item-06-014
    Scenario: Each regenerate launches one builder and waits for it
      Given the stub builder records each launch and the candidate it leaves fails the gate once, then passes
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the stub builder was launched twice, once for the regenerate and once for the repair
      And the second launch began after the first had ended

    # context.allium, contract BuilderLaunch, invariant TheBuilderExitIsNoVerdict
    # Legacy: AGENTS.md "Done is the gate's verdict, never the builder's claim"
    @minimum @allium-boundary.TheBuilderExitIsNoVerdict
    @item-06-015
    Scenario: A builder that exits non-zero but leaves a candidate is gated
      Given the stub builder leaves a candidate the gate passes and exits with status 1
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the regenerate effect of run R-1 was recorded with that candidate's digest
      And run R-1 awaits its verdicts

    # context.allium, contract BuilderLaunch, invariant TheBuilderExitIsNoVerdict; rule ConcludeRunFailed
    # Legacy: AGENTS.md "Done is the gate's verdict, never the builder's claim"; a regenerate that produced nothing fails the cycle
    @minimum @allium-boundary.TheBuilderExitIsNoVerdict @allium-rule-success.ConcludeRunFailed
    @item-06-016
    Scenario: A builder that leaves nothing fails the cycle at regenerate
      Given the stub builder exits 0 and leaves the candidate scope empty
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then run R-1 has no regenerate effect, and its fail-cycle effect was recorded
      And run R-1 failed with its cycle at the regenerate stage for "candidate-not-regenerated"

    # context.allium, contract BuilderLaunch, invariant OneFreshBuilderPerRegenerate (bounded by the budget)
    @balance @allium-boundary.OneFreshBuilderPerRegenerate
    @item-06-017
    Scenario: A builder that outlives the regenerate timeout is stopped and the cycle fails for its budget
      Given the budget's regenerate timeout is 2 seconds and the stub builder sleeps 30 seconds
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the stub builder was stopped
      And run R-1 failed with its cycle at the regenerate stage for "budget"

  Rule: The gate grades a copy of the candidate on the host, in legacy verify.sh's order

    # context.allium, contract GateOnTheHost, invariant TheLegacyGateOrder
    # Legacy: verify.sh [1], the port must be free; decisions.md b
    @minimum @allium-boundary.TheLegacyGateOrder
    @item-06-018
    Scenario: Something answering on the port is a gate refusal, and three in a row fail the cycle
      Given the stub builder leaves a candidate the gate would pass
      And something already responds on the configured port
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the gate effects of run R-1 for attempt 1 were refused "seam-answered-before-boot" three times, each followed by a recorded gate-refusal effect
      And run R-1 failed with its cycle at the gate for "infrastructure", and the candidate's launch entrypoint was never started

    # context.allium, contract GateOnTheHost, invariant TheLegacyGateOrder
    # Legacy: verify.sh [2], the suite before anything is booted
    @minimum @allium-boundary.TheLegacyGateOrder
    @item-06-019
    Scenario: A red suite fails the attempt before the candidate is booted
      Given the stub builder leaves a candidate whose suite exits 1 and whose launch entrypoint records that it ran
      And the budget allows 1 gate attempt
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the gate effect of run R-1 for attempt 1 was recorded with the event "regeneration-cycle/gate-attempt-failed" for "suite-red"
      And the candidate's launch entrypoint never ran, and run R-1 failed with its cycle at the gate for "gate-attempts-exhausted"

    # context.allium, contract GateOnTheHost, invariant TheLegacyGateOrder
    # Legacy: verify.sh, test.sh and start.sh are the gate's contract
    @balance @allium-boundary.TheLegacyGateOrder
    @item-06-020
    Scenario: A candidate with no launch entrypoint fails the attempt for a missing contract
      Given the stub builder leaves a candidate with a test.sh and no start.sh
      And the budget allows 1 gate attempt
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the gate effect of run R-1 for attempt 1 was recorded with the event "regeneration-cycle/gate-attempt-failed" for "contract-missing"

    # context.allium, contract GateOnTheHost, invariant TheLegacyGateOrder
    # Legacy: verify.sh [4], APP_MARKER, then the identity probe
    @minimum @allium-boundary.TheLegacyGateOrder
    @item-06-021
    Scenario: A served page without the marker fails the attempt for its identity
      Given the stub builder leaves a candidate with a green suite whose page does not carry "sauna"
      And the configuration's marker is "sauna" and the budget allows 1 gate attempt
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the gate effect of run R-1 for attempt 1 was recorded with the event "regeneration-cycle/gate-attempt-failed" for "identity-probe-failed"

    # context.allium, contract GateOnTheHost, invariant TheGateGradesACopy
    @balance @allium-boundary.TheGateGradesACopy
    @item-06-022
    Scenario: What a builder writes after it ended does not reach the gate
      Given the stub builder leaves a candidate the gate passes and, after it exits, keeps writing a file that turns its suite red
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the gate effect of run R-1 for attempt 1 was recorded with the event "regeneration-cycle/gate-attempt-passed"
      And the install effect's candidate digest is the regenerate effect's

  Rule: A failed gate attempt is repaired by a fresh builder that sees only the previous candidate and the gate's evidence

    # context.allium, contract BuilderLaunch, invariant TheBuilderSeesOnlyItsMounts
    # Legacy: AGENTS.md "only the previous candidate and the gate's evidence, at most three attempts"
    # decisions.md "Spec round 16b" D2: the evidence mounted is read from the repair effect's detail, through run-progress
    @minimum @allium-boundary.TheBuilderSeesOnlyItsMounts
    @item-06-023
    Scenario: A repair builder sees the previous candidate and the gate evidence, read-only
      Given the stub builder records the paths its builder room holds, and its first candidate has a red suite
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the repair effect of run R-1 for attempt 2 was recorded with the event "regeneration-cycle/candidate-regenerated" for "verification-repair", its detail naming the evidence root that attempt 1's "regeneration-cycle/gate-attempt-failed" carries
      And the second launch saw the clean room, the previous candidate and the gate evidence of attempt 1, and could write neither of the last two

    # cycle-run.allium, rule ConcludeRunFailed
    # Legacy: AGENTS.md, at most three attempts
    @minimum @allium-rule-success.ConcludeRunFailed
    @item-06-024
    Scenario: Three failed attempts fail the cycle, and no fourth builder is launched
      Given every candidate the stub builder leaves has a red suite
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the stub builder was launched three times
      And run R-1 failed with its cycle at the gate for "gate-attempts-exhausted"

  Rule: The verdicts of the gated candidate are handed in, filed as given, and the run ends as its cycle ends

    # cycle-run.allium, rules HandInVerdicts, RecordEffectAfterTheVerdicts and ConcludeRunPassed; invariant PassedOnlyWithVerdicts; surface RunDesk, guarantee TheRunnerJudgesNoItem
    # Legacy: step 5, a grading subagent's verdicts file, then score.clj and scoreboard.clj
    @minimum @allium-rule-success.HandInVerdicts @allium-rule-success.RecordEffectAfterTheVerdicts @allium-rule-success.ConcludeRunPassed @allium-invariant.PassedOnlyWithVerdicts @allium-guarantee.TheRunnerJudgesNoItem
    @item-06-025
    Scenario: Verdicts handed in are filed as given, and the run passes as its cycle passes
      Given run R-1 of cycle C-1 awaits its verdicts
      And a verdicts file holds 01-001 pass with evidence "booked" and 01-002 fail with evidence "judged by the main session"
      When the operator hands in that verdicts file for run R-1 with the grader digest "r-1"
      Then the file-verdicts and file-score effects of run R-1 were recorded
      And the file-score effect's measurement/measurement-filed event shows 1 of 2 passing, 01-001 pass and 01-002 fail with those evidences
      And run R-1 passed with its cycle

    # surface RunDesk, guarantee TheRunnerJudgesNoItem
    # Legacy: score.clj, "not scored: the grader never reached this item"
    @balance @allium-guarantee.TheRunnerJudgesNoItem
    @item-06-026
    Scenario: An item the verdicts file does not name is Measurement's to record as unreached
      Given run R-1 of cycle C-1 awaits its verdicts
      And a verdicts file holds only 01-001 pass
      When the operator hands in that verdicts file for run R-1 with the grader digest "r-1"
      Then the file-score effect's measurement/measurement-filed event shows 01-002 failed with the reason "unreached"
      And run R-1 passed with its cycle, 1 of 2 passing

    # cycle-run.allium, rule HandInVerdicts (requires 1)
    @balance @allium-rule-failure.HandInVerdicts.1
    @item-06-027
    Scenario: Verdicts for a run that does not exist are refused
      Given no run R-9 exists
      When the operator hands in a verdicts file for run R-9 with the grader digest "r-1"
      Then the hand-in is refused
      And no run R-9 exists

    # cycle-run.allium, rule HandInVerdicts (requires 2)
    # Legacy: score.clj files the verdicts of a gated candidate only
    @minimum @allium-rule-failure.HandInVerdicts.2
    @item-06-028
    Scenario: Verdicts for a run whose gate never passed are refused
      Given run R-1 of cycle C-1 failed with its cycle at the gate for "gate-attempts-exhausted"
      When the operator hands in a verdicts file for run R-1 with the grader digest "r-1"
      Then the hand-in is refused
      And run R-1 has no file-verdicts effect and is still failed

    # cycle-run.allium, rule HandInVerdicts (requires 3); invariant NoBuilderAfterTheVerdicts
    # Legacy: AGENTS.md "a failed score is measured, not retried within the cycle"
    @minimum @allium-rule-failure.HandInVerdicts.3 @allium-invariant.NoBuilderAfterTheVerdicts
    @item-06-029
    Scenario: A second verdicts file is refused, and no builder follows the verdicts
      Given verdicts were handed in for run R-1 of cycle C-1, and its cycle failed at the score for "score-stage-failed"
      When the operator hands in another verdicts file for run R-1 with the grader digest "r-1"
      Then the hand-in is refused
      And run R-1 has no regenerate or repair effect after its verdicts were handed in

    # cycle-run.allium, rule HandInVerdicts (requires 5)
    @balance @allium-rule-failure.HandInVerdicts.5
    @item-06-030
    Scenario: Verdicts under a grader digest the run did not approve are refused
      Given run R-1 of cycle C-1 awaits its verdicts, its approved grader digests "r-1"
      When the operator hands in a verdicts file for run R-1 with the grader digest "r-2"
      Then the hand-in is refused
      And run R-1 still awaits its verdicts

    # cycle-run.allium, rule HandInVerdicts (requires 6)
    # Legacy: score.clj refuses a blank file and one that is not JSON (@harness-033, @harness-034)
    @minimum @allium-rule-failure.HandInVerdicts.6
    @item-06-031
    Scenario: A verdicts file that is blank is refused, and nothing is filed
      Given run R-1 of cycle C-1 awaits its verdicts
      When the operator hands in a blank verdicts file for run R-1 with the grader digest "r-1"
      Then the hand-in is refused
      And run R-1 still awaits its verdicts, with no file-verdicts effect

    # context.allium, contract ContextSeams, invariant RefusalsAreTheContexts; rule ConcludeRunPassed (requires 2), rule ConcludeRunFailed (requires 2); invariant NoBuilderAfterTheVerdicts
    # Legacy: score.clj refuses an unknown item and writes nothing
    @minimum @allium-boundary.RefusalsAreTheContexts @allium-rule-failure.ConcludeRunPassed.2 @allium-rule-failure.ConcludeRunFailed.2 @allium-invariant.NoBuilderAfterTheVerdicts
    @item-06-032
    Scenario: Verdicts Measurement refuses fail the score stage, and the run fails with its cycle
      Given run R-1 of cycle C-1 awaits its verdicts
      And a verdicts file holds 01-001 pass and 09-001 pass
      When the operator hands in that verdicts file for run R-1 with the grader digest "r-1"
      Then the file-verdicts effect of run R-1 was refused "unknown-item", as measurement answered, and its fail-score-stage effect was recorded
      And run R-1 failed with its cycle at the score for "score-stage-failed", and no builder was launched after the verdicts

  Rule: A run that cannot perform an effect halts, loudly, and a run that ended stays as it ended

    # cycle-run.allium, rule HaltRun
    @balance @allium-rule-success.HaltRun
    @item-06-033
    Scenario: A context that stops answering halts the run, and nothing is recorded for that effect
      Given the launch entrypoint handed in for Measurement exits when it is asked to derive a checklist
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then run R-1 is halted at the checklist effect because a context did not respond
      And it has no checklist effect result

    # cycle-run.allium, rules HaltRun (requires 1), ConcludeWithTheCycle (requires 1), ConcludeRunFailed (requires 1), RelayHandoff (requires 1)
    @balance @allium-rule-failure.HaltRun.1 @allium-rule-failure.ConcludeWithTheCycle.1 @allium-rule-failure.ConcludeRunFailed.1 @allium-rule-failure.RelayHandoff.1
    @item-06-034
    Scenario: A run that ended stays as it ended
      Given run R-1 of cycle C-1 failed with its cycle at the fence
      And run R-2 of cycle C-2 halted at the checklist effect
      When the operator reads the run progress of runs R-1 and R-2 again, 5 seconds after each start responded
      Then run R-1 is still failed and run R-2 is still halted
      And each shows the same effect results and relayed handoffs as when its start responded

    # surface RunDesk, guarantee ObservingChangesNothing
    @balance @allium-guarantee.ObservingChangesNothing
    @item-06-035
    Scenario: Reading a run's progress changes nothing
      Given run R-1 of cycle C-1 awaits its verdicts
      When the operator reads the run progress of run R-1 twice
      Then both readings are identical
      And run R-1 still awaits its verdicts

  Rule: The gate grades a copy of the candidate on the host, in legacy verify.sh's order

    # context.allium, contract GateOnTheHost, invariant TheLegacyGateOrder
    # Legacy: verify.sh [4], "$ROOT/$APP_PROBE" "$URL", with URL "http://127.0.0.1:$PORT"; shadow run 1, decisions.md "Spec round 7c"
    @minimum @allium-boundary.TheLegacyGateOrder
    @item-06-036
    Scenario: The identity probe is handed the served URL, and passes a candidate that serves the marker
      Given the stub builder leaves a candidate whose suite is green and whose launch entrypoint serves the marker on the port
      And the cut's identity probe exits 0 only when its one argument is "http://127.0.0.1:" followed by the configured port and the page there carries the marker, and exits 1 when handed the bare port
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the gate effect of run R-1 for attempt 1 was recorded with the event "regeneration-cycle/gate-attempt-passed"
      And run R-1 awaits its verdicts

    # context.allium, contract GateOnTheHost, invariant TheGateGradesACopy
    # Legacy: verify.sh runs the candidate's test.sh in a writable tree; shadow run 2, decisions.md "Spec round 7d"
    @minimum @allium-boundary.TheGateGradesACopy
    @item-06-037
    Scenario: A candidate whose suite writes inside its own directory passes the gate
      Given the stub builder leaves a candidate whose suite creates and removes a temporary directory inside its own directory, then exits 0, and whose launch entrypoint serves the marker on the port
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the gate effect of run R-1 for attempt 1 was recorded with the event "regeneration-cycle/gate-attempt-passed"
      And the install effect's candidate digest is the regenerate effect's

  Rule: No clean room is built over a failed harness self-check

    # cycle-run.allium, rule RecordEffect (a skipped check is part of the recorded self-check)
    # Legacy: check.sh prints "SKIP  the oracle cannot judge this tree: a tool it grades is absent."; shadow run 2, decisions.md "Spec round 7d"
    @minimum @allium-rule-success.RecordEffect
    @item-06-038
    Scenario: A skipped check is recorded with the whole text after SKIP
      Given the self-check exits 0 and prints legacy check.sh's line "SKIP  the oracle cannot judge this tree: a tool it grades is absent."
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the first self-check effect of run R-1 was recorded with the event "regeneration-cycle/harness-self-check-passed"
      And its detail names the skipped check "the oracle cannot judge this tree: a tool it grades is absent.", that legacy line's whole text after SKIP

  Rule: A stdio candidate is gated as harness/v2/gate.sh gates it: its suite, its identity probe, then the store the probe's candidate wrote

    # context.allium, contract GateOnTheHost, invariant TheStdioGateOrder
    # Legacy: harness/v2/gate.sh, harness/verify.sh then harness/v2/event-store-check.sh; decisions.md "Spec round 8"
    @minimum @allium-boundary.TheStdioGateOrder
    @item-06-039
    Scenario: A stdio candidate whose suite, identity probe and store check pass passes the gate
      Given the configuration's seam is "stdio" and it names no port
      And the stub builder leaves a candidate whose suite exits 0 and whose launch entrypoint exists
      And the cut's identity probe prints "answered as the context" and exits 0, and the store check exits 0
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the gate effect of run R-1 for attempt 1 was recorded with the event "regeneration-cycle/gate-attempt-passed"
      And that event carries a boot budget of 30 seconds and 1 poll, answered on poll 1, and the identity probe result "answered as the context"
      And run R-1 has no gate-refusal effect and awaits its verdicts

    # context.allium, contract GateOnTheHost, invariant TheStoreCheckReadsTheProbesStore
    # Legacy: harness/v2/gate.sh exports GATE_STORE, unsets LOYLY_GRAIN_STORE and hands the store check that store; decisions.md "Spec round 8"
    @minimum @allium-boundary.TheStoreCheckReadsTheProbesStore
    @item-06-040
    Scenario: The identity probe is handed the gate room's launch entrypoint, the boot budget and the gate's store, and the store check reads that store
      Given the configuration's seam is "stdio"
      And the stub builder leaves a candidate whose suite exits 0
      And the cut's identity probe records its arguments and its environment and exits 0, and the store check records its arguments and exits 0
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the identity probe was run with two arguments: the absolute path of the launch entrypoint in run R-1's gate room, and "30"
      And its GATE_STORE named a file under the runs directory and outside the gate room, and no variable whose name starts "LOYLY_" was in its environment
      And the store check's last argument was that same file

    # context.allium, contract GateOnTheHost, invariants TheStdioGateOrder and TheStoreCheckReadsTheProbesStore
    # Legacy: harness/verify.sh [1], the suite before anything is booted; harness/v2/gate.sh unsets LOYLY_GRAIN_STORE before the suite
    @minimum @allium-boundary.TheStdioGateOrder @allium-boundary.TheStoreCheckReadsTheProbesStore
    @item-06-041
    Scenario: A red stdio suite fails the attempt before the identity probe, and the suite is handed no store
      Given the configuration's seam is "stdio" and the budget allows 1 gate attempt
      And the stub builder leaves a candidate whose suite records its environment and exits 1
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the gate effect of run R-1 for attempt 1 was recorded with the event "regeneration-cycle/gate-attempt-failed" for "suite-red"
      And the suite's environment held neither GATE_STORE nor any variable whose name starts "LOYLY_"
      And neither the identity probe nor the store check ran

    # context.allium, contract GateOnTheHost, invariant TheStdioGateOrder
    # Legacy: harness/verify.sh [2], "the started product is not the application under test, or did not answer in time"
    @minimum @allium-boundary.TheStdioGateOrder
    @item-06-042
    Scenario: An identity probe that fails fails the attempt for its identity, and the store is not checked
      Given the configuration's seam is "stdio" and the budget allows 1 gate attempt
      And the stub builder leaves a candidate whose suite exits 0
      And the cut's identity probe prints "no response within the 30s boot budget" and exits 1
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the gate effect of run R-1 for attempt 1 was recorded with the event "regeneration-cycle/gate-attempt-failed" for "identity-probe-failed"
      And its detail carries "no response within the 30s boot budget"
      And the store check never ran

    # context.allium, contract GateOnTheHost, invariant TheStdioGateOrder (decisions.md "Spec round 8", D3: a store check that fails is the rest of the identity check)
    # Legacy: harness/v2/gate.sh, "the event-store check failed"
    @minimum @allium-boundary.TheStdioGateOrder
    @item-06-043
    Scenario: A store check that fails fails the attempt for its identity, with the store check's output as the detail
      Given the configuration's seam is "stdio" and the budget allows 1 gate attempt
      And the stub builder leaves a candidate whose suite exits 0
      And the cut's identity probe exits 0
      And the store check prints "FAIL event 7 was not written inside a run of the ORC workflow" and exits 1
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the gate effect of run R-1 for attempt 1 was recorded with the event "regeneration-cycle/gate-attempt-failed" for "identity-probe-failed"
      And its detail carries "FAIL event 7 was not written inside a run of the ORC workflow"
      And run R-1 failed with its cycle at the gate for "gate-attempts-exhausted"

    # context.allium, contract GateOnTheHost, invariant TheGateRoomHoldsTheCleanRoom
    # Legacy: harness/v2/gate.sh gates the candidate inside its room; decisions.md "Spec round 8"
    @minimum @allium-boundary.TheGateRoomHoldsTheCleanRoom
    @item-06-044
    Scenario: A stdio candidate reads the spec beside it in the gate room
      Given the configuration's seam is "stdio" and the cut's identity probe and the store check exit 0
      And the stub builder leaves a candidate whose suite exits 0 only when the plan's first spec file is at its path from the root of the room the suite runs in
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the gate effect of run R-1 for attempt 1 was recorded with the event "regeneration-cycle/gate-attempt-passed"
      And the install effect's candidate digest is the regenerate effect's

    # cycle-run.allium, rule HaltRun (a handed-in command that could not be started)
    @minimum @allium-rule-success.HaltRun
    @item-06-045
    Scenario: A stdio run whose store check cannot be started halts at the gate
      Given the configuration's seam is "stdio" and the cut's identity probe exits 0
      And the stub builder leaves a candidate whose suite exits 0
      And the store-check command handed in names no program
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then run R-1 is halted at the gate effect because a handed-in command could not run
      And it has no gate effect result

    # context.allium, contract GateOnTheHost, invariant TheHarnessDigestCoversTheStoreCheck
    @minimum @allium-boundary.TheHarnessDigestCoversTheStoreCheck
    @item-06-046
    Scenario: The harness digest of a stdio run covers the store-check command
      Given the configuration's seam is "stdio", the budget allows 1 gate attempt and the cut's identity probe exits 1
      And the operator started run R-1 of cycle C-1 at source cut K-1, handed in the store-check command "check-store.sh one", and it ended
      When the operator starts run R-2 of cycle C-2 at source cut K-2, handed in the store-check command "check-store.sh two", everything else the same
      Then the first self-check effects of runs R-1 and R-2 were both recorded with the event "regeneration-cycle/harness-self-check-passed"
      And their harness digests differ

  Rule: The runner stamps, pins and derives the checklist, and relays every handoff from its producer's store

    # context.allium, contract ProjectRepository, invariant ASliceLinkIsReadAtItsTarget
    # Legacy: gen_checklist reads PLAN_DIR on disk, where spec v2's .pi/plan/NN-<context> links resolve; decisions.md "Spec round 8"
    @minimum @allium-boundary.ASliceLinkIsReadAtItsTarget
    @item-06-047
    Scenario: A slice that is a symbolic link in the plan is read at its target
      Given the plan at the commit holds its slice "01-a" as a symbolic link to "../spec/a", whose spec file holds the two stamped scenarios
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the stamp effect of run R-1 was recorded, as specification answered, and the run did not halt
      And the checklist effect's events name the items 01-001 and 01-002, each with its source under "01-a"

    # context.allium, contract ProjectRepository, invariant ThePlanSlicesAreTheSelection
    # Legacy: harness/v2/score.sh derives a context's checklist from that slice's spec alone; decisions.md "Spec round 8"
    @minimum @allium-boundary.ThePlanSlicesAreTheSelection
    @item-06-048
    Scenario: The configuration's plan slices select what is stamped and checklisted
      Given the plan at the commit holds slice 01 with 01-001 and 01-002 and slice 02 with 02-001
      And the configuration's plan slices are "02"
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the stamp effects of run R-1 stamped slice 02 only
      And the checklist effect's events name the item 02-001 and no item of slice 01

  Rule: A regenerate launches one fresh builder and records what it left in its candidate scope

    # context.allium, contract BuilderLaunch, invariant TheBuilderRunsInTheConfiguredImage
    # decisions.md "Spec round 9" D1: selfhost-01's builder 1 ran in the TypeScript image, installed a Linux toolchain into its scope, and gate attempt 1 was suite-red on the host
    @minimum @allium-boundary.TheBuilderRunsInTheConfiguredImage
    @item-06-049
    Scenario: Each builder is handed the configured builder image, and each regenerate names the image and its digest
      Given the configuration's builder image is "builder-clojure" and the builder images directory holds a folder of that name
      And the stub builder records the arguments of each launch, and the candidate it leaves fails the gate once, then passes
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then each launch of the stub builder was handed three arguments: its builder room, its instruction file and the path of "builder-clojure" in the builder images directory
      And the regenerate and repair effects of run R-1 each name in their detail the builder image "builder-clojure" and its digest

  Rule: A run starts once, one at a time, on a commit of the project repository, with its five contexts responding

    # cycle-run.allium, rule StartRun (requires 6)
    # decisions.md "Spec round 9" D2: the runner checks only that the named builder image is there; its toolchain is the provided image build's
    @minimum @allium-rule-failure.StartRun.6
    @item-06-050
    Scenario: A start whose configured builder image is not in the builder images directory is refused
      Given the configuration's builder image is "builder-clojure" and the builder images directory holds no folder of that name
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the start is refused "builder-image-missing"
      And no run R-1 exists, and no builder was launched

  Rule: Measurement keeps its history across runs when a history store is handed in, so a scoreboard compares a cycle with the last measured cycle of its selection

    # context.allium, contract ContextSeams, invariant MeasurementKeepsItsHistory; cycle-run.allium, surface EffectNodes, guarantee InTheProducersOrder
    # Legacy: scoreboard.clj compares a cycle with the previous cycle's committed scores; decisions.md "Spec round 9" D6: selfhost-01's scoreboard had no previous cycle, legacy's compared with cycle 01
    @minimum @allium-boundary.MeasurementKeepsItsHistory
    @item-06-051
    Scenario: Two runs handed one measurement history store compare the second cycle with the first
      Given the operator hands in the measurement history store H
      And run R-1 of cycle C-1 at source cut K-1 was handed its verdicts and passed with its cycle
      When the operator starts run R-2 of cycle C-2 at source cut K-2, everything else the same, and hands in its verdicts
      Then Measurement's scoreboard of cycle C-2, read from H through Measurement's own seam after run R-2 ended, has the previous cycle C-1
      And run R-2's relayed handoffs from measurement are one measurement/measurement-filed and one measurement/scoreboard-reported, each to regeneration-cycle

  Rule: The gate grades a copy of the candidate on the host, in legacy verify.sh's order

    # context.allium, contract GateOnTheHost, invariant TheGateRoomHoldsTheCleanRoom
    # Legacy: verify.sh [4] runs "$ROOT/$APP_PROBE" "$URL" in the tree it gates (lines 23, 36, 154), and Löyly's harness/probe.sh sources "$ROOT/harness/config.sh", ROOT its own directory's parent; Löyly run-01 (runner c05) failed "identity-probe-failed" on all 3 attempts, decisions.md "Spec round 11"
    @minimum @allium-boundary.TheGateRoomHoldsTheCleanRoom
    @item-06-052
    Scenario: An identity probe that reads a file beside it in the cut passes a candidate that serves the marker
      Given the cut's identity probe is "harness/probe.sh", and it reads the marker from "harness/config.sh" by its path relative to the probe's own location, never from its working directory or its environment
      And it exits 0 only when it can read that file and the page at its one argument carries the marker read there, and exits 1 otherwise
      And the stub builder leaves a candidate whose suite is green and whose launch entrypoint serves the marker on the port
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the gate effect of run R-1 for attempt 1 was recorded with the event "regeneration-cycle/gate-attempt-passed"
      And the install effect's candidate digest is the regenerate effect's

  Rule: A gate command handed in is the gate: the runner hands it the cut's tree, the candidate's copy and an evidence directory, and records its result

    # context.allium, contract GateOnTheHost, invariants TheGateCommandJudges and TheGateCommandsResultIsRead
    # decisions.md "Spec round 12" D2: Halla's candidates boot only against a world (PostgreSQL, Medplum) that runs in a VM, so a provided gate command runs the gate there
    @minimum @allium-boundary.TheGateCommandJudges
    @item-06-053
    Scenario: A gate command that exits 0 passes the attempt with the budget, the poll and the probe line it printed
      Given the operator hands in a gate command that records its three arguments and what it finds at each
      And it prints "BOOT 20 10", then "ANSWERED 3", then last "ledger answered as itself", and exits 0
      And the stub builder leaves a candidate in its candidate scope
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the gate effect of run R-1 for attempt 1 was recorded with the event "regeneration-cycle/gate-attempt-passed"
      And that event carries a boot budget of 10 seconds and 20 polls, answered on poll 3, and the identity probe result "ledger answered as itself"
      And the gate command was launched once, in the project repository, with the cut's tree holding nothing at the candidate's scope, a copy of the candidate whose digest is the install effect's, and an empty directory that is the event's evidence root
      And the candidate's launch entrypoint was never started by the runner

    # context.allium, contract GateOnTheHost, invariant TheGateCommandsResultIsRead
    # decisions.md "Spec round 12" D2: the last line's first word is a reason of gate-attempt-failed
    @minimum @allium-boundary.TheGateCommandsResultIsRead
    @item-06-054
    Scenario: A gate command that exits 1 naming a reason fails the attempt with that reason, and the repair builder is handed its evidence
      Given the operator hands in a gate command that writes a file into its evidence directory, prints "BOOT 20 10" and last "suite-red 3 of 65 tests failed", and exits 1 on attempt 1, and passes attempt 2
      And the stub builder records the paths of each builder room
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the gate effect of run R-1 for attempt 1 was recorded with the event "regeneration-cycle/gate-attempt-failed", its reason "suite-red" and its detail "3 of 65 tests failed"
      And the repair builder's room held, read-only, the file the gate command wrote into its evidence directory for attempt 1
      And the gate effect for attempt 2 was recorded with the event "regeneration-cycle/gate-attempt-passed"

    # context.allium, contract GateOnTheHost, invariants TheGateCommandsResultIsRead and TheLegacyGateOrder
    # decisions.md "Spec round 12" D3: a refusal guard is offered as a failed outcome, and Regeneration cycle refuses it with the guard's own code
    @minimum @allium-boundary.TheGateCommandsResultIsRead
    @item-06-055
    Scenario: A gate command whose last line is a refusal to grade is a gate refusal, and three in a row fail the cycle
      Given the operator hands in a gate command that prints "BOOT 20 10" and last "seam-answered-before-boot 127.0.0.1:8920 answered before start.sh ran", and exits 1
      And the stub builder leaves a candidate in its candidate scope
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the gate effects of run R-1 for attempt 1 were refused "seam-answered-before-boot" three times, each followed by a recorded gate-refusal effect
      And run R-1 failed with its cycle at the gate for "infrastructure"

    # cycle-run.allium, rule HaltRun (a gate that could not be performed); context.allium, invariant TheGateCommandsResultIsRead
    # decisions.md "Spec round 12" D2: a world or a VM that did not come up is not the candidate's failure, and Regeneration cycle has no reason for it
    # decisions.md "Spec round 16b" D3: the cycle left running is read through run-progress, as no fail-cycle effect and no cycle
    @minimum @allium-rule-success.HaltRun
    @item-06-056
    Scenario: A gate command whose last line names no code the runner reads halts the run at the gate
      Given the operator hands in a gate command that prints "BOOT 20 10" and last "infrastructure the world did not come up", and exits 2
      And the stub builder leaves a candidate in its candidate scope
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then run R-1 is halted at the gate effect because a handed-in command could not run, its detail "infrastructure the world did not come up"
      And run R-1's progress has no gate effect result, no fail-cycle effect result and no cycle: the runner did not conclude its cycle

    # context.allium, contract GateOnTheHost, invariant TheHarnessDigestCoversTheGateCommand
    # decisions.md "Spec round 12" D5: the gate command is the judge, so it is part of the harness
    @minimum @allium-boundary.TheHarnessDigestCoversTheGateCommand
    @item-06-057
    Scenario: The harness digest covers the gate command, and is unchanged when none is handed in
      Given runs R-1, R-2 and R-3 of cycles C-1, C-2 and C-3 at source cut K-1 differ only in the gate command: none for R-1, "gate-a" for R-2 and "gate-b" for R-3, each gate command passing every attempt
      When the operator starts each of the runs R-1, R-2 and R-3 in turn
      Then the self-check effects of run R-1 carry the SHA-256 of the configuration file, the self-check command and the identity probe, in that order, as the harness digest
      And the self-check effects of runs R-1, R-2 and R-3 carry three different harness digests

  Rule: A start waits for its contexts, as long as a context takes to load

    # cycle-run.allium, rule StartRun (requires 5); context.allium, config context_start_seconds
    # decisions.md "Spec round 13" D1: cycle 08's runner gave its contexts 20 seconds, and a context takes about 20 to load, so Halla's first nine starts were refused
    @minimum @allium-rule-failure.StartRun.5
    @item-06-058
    Scenario: A context that first responds 30 seconds after its launch is waited for, and one that never responds refuses the start after 60
      Given the launch entrypoint handed in for Cycle preparation responds to its first query 30 seconds after its launch
      And the launch entrypoint handed in for Measurement never responds and does not exit
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the start is refused, naming measurement and not cycle-preparation
      And the refusal came no sooner than 60 seconds and no later than 90 seconds after the start
      And no run R-1 exists, and no builder was launched

  Rule: Each flow is an ORC tree, and each effect the runner performs is a leaf of its own

    # cycle-run.allium, surface EffectNodes, guarantee EachEffectInItsOwnLeaf; seam.md "The trees"
    # decisions.md "Spec round 14" D3: every context so far ran each flow as one ORC leaf around orchestration of its own, which the store check could not tell apart
    @minimum @allium-guarantee.EachEffectInItsOwnLeaf
    @item-06-059
    Scenario: Each effect a run records lies inside the leaf of its flow's tree named for that effect
      Given the stub builder leaves a candidate whose suite is green and whose launch entrypoint serves the marker on the port
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then run R-1 awaits its verdicts
      And in the runner's own store, each effect result of run R-1 lies between the start and the completion of a leaf, of the run's tick of the ORC workflow "cycle-runner/run-to-gate" or of a child tick that tick delegated, whose name is its effect, or its effect followed by ":" and a qualifier
      And those are nine leaves with nine different names, one for each of stamp, pin, checklist, self-check, fence, regenerate, install, self-check and gate
      And each handoff relayed for run R-1 lies inside a leaf of those ticks named "relay", or "relay" followed by ":" and a qualifier

  Rule: The gate grades a copy of the candidate on the host, in legacy verify.sh's order

    # context.allium, contract GateOnTheHost, invariant TheLegacyGateOrder; config boot_budget_seconds, boot_polls; seam.md E5 (http)
    # Legacy: verify.sh lines 117 to 121, a poll first and then the wait, so poll n is made (n - 1) half seconds after start.sh is launched
    # decisions.md "Spec round 15": runner cycle 09 waited before each poll and recorded legacy's poll 2 as poll 1, so it was not promoted
    # decisions.md "Spec round 16b" D1: counted in polls, not seconds; runner cycle 10's gate host answered the 1.25-second stand-in on poll 5
    @minimum @allium-boundary.TheLegacyGateOrder
    @item-06-060
    Scenario: A candidate that refuses its first three requests is answered on a later poll, its margin 20 less that poll
      Given the stub builder leaves a candidate whose suite is green and whose launch entrypoint replies to its first three HTTP requests with the status 503 and serves the marker from its fourth on
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the gate effect of run R-1 for attempt 1 was recorded with the event "regeneration-cycle/gate-attempt-passed"
      And that event carries a boot budget of 10 seconds and 20 polls, answered on poll 4 or later
      And run R-1's attempt 1 shows a boot margin of 20 polls less the poll it was answered on

  Rule: A gate attempt is one subtree, delegated once per attempt

    # cycle-run.allium, surface EffectNodes, guarantee EachGateAttemptOneDelegatedTick; seam.md "The trees"
    # decisions.md "Spec round 16" D3: cycles 09 and 10 wrote the three attempts out inline in run-to-gate, and read the attempt out of each leaf's name
    @minimum @allium-guarantee.EachGateAttemptOneDelegatedTick
    @item-06-061
    Scenario: Each gate attempt runs as a child tick of its own of the workflow "cycle-runner/gate-attempt.v1"
      Given the stub builder's first candidate has a red suite, and its second a green suite and a launch entrypoint that serves the marker on the port
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then run R-1 awaits its verdicts, its gate effect for attempt 2 recorded with the event "regeneration-cycle/gate-attempt-passed"
      And in the runner's own store, the install, self-check and gate effect results of attempt 1 lie inside leaves of one tick of the ORC workflow "cycle-runner/gate-attempt.v1", and those of attempt 2 inside leaves of another
      And each of those two ticks is a child tick of the run's tick of the ORC workflow "cycle-runner/run-to-gate"
      And the repair effect result of attempt 2 lies inside a leaf of the run's tick of "cycle-runner/run-to-gate" itself

  Rule: The runner carries its own event model, as its room gave it

    # cycle-run.allium, surface RunDesk, guarantee CarriesItsOwnEventModel; seam.md "The build"
    # decisions.md "Spec round 16" D6: cycles 08 to 10 read the event models from ../../.pi/spec at run time, so the promoted build could not start once the repository's spec moved
    @minimum @allium-guarantee.CarriesItsOwnEventModel
    @item-06-062
    Scenario: A copy of the candidate in a folder of its own, with no spec around it, runs a cycle
      Given a copy of the candidate in a folder outside the project repository, with no .pi folder in it or in any folder above it
      And the stub builder leaves a candidate whose suite is green and whose launch entrypoint serves the marker on the port
      When the operator starts run R-1 of cycle C-1 at source cut K-1 on that copy
      Then run R-1 awaits its verdicts, its gate effect for attempt 1 recorded with the event "regeneration-cycle/gate-attempt-passed"

  Rule: A run that cannot perform an effect halts, loudly, and a run that ended stays as it ended

    # context.allium, contract ContextSeams, invariant AReplyWithinItsBound; config context_reply_seconds; seam.md "The trees", every wait has a bound
    # decisions.md "Spec round 16" D5: ORC bounds no leaf, and cycles 09 and 10 waited up to a day for a tick, so a context that hangs without exiting would hold the run for as long
    @minimum @allium-boundary.AReplyWithinItsBound
    @item-06-063
    Scenario: A context that stops replying without exiting halts the run 60 seconds after it was asked
      Given the launch entrypoint handed in for Measurement stops replying, and does not exit, when it is asked to derive a checklist
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then run R-1 is halted at the checklist effect because a context did not respond
      And the halt came no sooner than 60 seconds and no later than 90 seconds after Measurement was asked
      And it has no checklist effect result
