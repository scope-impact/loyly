Feature: Cycle runner, draft scenarios
  Drafts for the Allium obligations of this context (spec round 7a, 2026-11-07): each rule's
  success, each refusal that is behaviour, each invariant, each contract invariant and each
  guarantee of allium/ that no gate verifies. The harness does not read this file. Only the
  product owner moves a draft into spec.feature, under the Rule it names, where the stamper gives
  it an @item id and the @draft tag is dropped. Every noun is a term of .pi/model/GLOSSARY.md.
  Every draft is gradeable through the runner's seam with the effects handed in (seam.md, "How a
  grader drives it without a live pi"): a fixture project repository at commit
  "4f2a9c1e0b7d3a5f9c2e8b1d4a6f0c3e7b9d2a51" with a plan of two stamped scenarios, 01-001 and
  01-002, a passing self-check, a stub builder, the five gated candidates of slices 01 to 05, a
  budget of 3 gate attempts, and the approved grader digest "r-1", unless a draft says otherwise.

  Rule: A run starts once, one at a time, on a commit of the project repository, with its five contexts responding

    # cycle-run.allium, rule StartRun; rule RecordEffect; invariant OneResultPerPosition
    # Legacy: README "A cycle": fence.sh, a builder session, verify.sh, in that order
    # Suggested tier: minimum
    @draft @allium-rule-success.StartRun @allium-rule-success.RecordEffect @allium-invariant.OneResultPerPosition
    Scenario: The operator starts a run, and it performs the cycle's effects up to the gate's verdict
      Given the stub builder leaves a candidate whose suite is green and whose launch entrypoint serves the marker on the port
      When the operator starts run R-1 of cycle C-1 at source cut K-1, commit "4f2a9c1e0b7d3a5f9c2e8b1d4a6f0c3e7b9d2a51"
      Then run R-1 awaits its verdicts
      And its effect results, at positions 1 to 9, are stamp, pin, checklist, self-check, fence, regenerate, install, self-check and gate, each recorded
      And the gate effect's events are "regeneration-cycle/gate-attempt-passed"

    # cycle-run.allium, invariant OneRunPerIdentity (rule StartRun requires 1, unit level)
    # Suggested tier: balance
    @draft @allium-invariant.OneRunPerIdentity
    Scenario: A run identity is started once
      Given run R-1 of cycle C-1 concluded failed at the fence
      When the operator starts run R-1 of cycle C-2
      Then the start is refused
      And the run progress lists run R-1 once, for cycle C-1

    # cycle-run.allium, invariant OneRunInProgress (rule StartRun requires 2, unit level)
    # Legacy: one cycle at a time, one port, one builder (config.sh PORT)
    # Suggested tier: minimum
    @draft @allium-invariant.OneRunInProgress
    Scenario: A second run is refused while the first awaits its verdicts
      Given run R-1 of cycle C-1 awaits its verdicts
      When the operator starts run R-2 of cycle C-2
      Then the start is refused
      And no run R-2 exists, and run R-1 still awaits its verdicts

    # cycle-run.allium, invariant OneRunPerCycle (rule StartRun requires 3, unit level)
    # Suggested tier: balance
    @draft @allium-invariant.OneRunPerCycle
    Scenario: A cycle is run once
      Given run R-1 of cycle C-1 concluded passed
      When the operator starts run R-2 of cycle C-1
      Then the start is refused
      And no run R-2 exists

    # cycle-run.allium, rule StartRun (requires 4)
    # Suggested tier: minimum
    @draft @allium-rule-failure.StartRun.4
    Scenario: A run of something that is not a commit of the project repository is refused
      Given "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef" is no commit of the project repository
      When the operator starts run R-1 of cycle C-1 at source cut K-1, commit "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"
      Then the start is refused
      And no run R-1 exists, and no builder was launched

    # cycle-run.allium, rule StartRun (requires 5)
    # Suggested tier: balance
    @draft @allium-rule-failure.StartRun.5
    Scenario: A run whose context does not respond its seam is refused
      Given the launch entrypoint handed in for Measurement exits at once
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the start is refused, naming measurement
      And no run R-1 exists

  Rule: The runner stamps, pins and derives the checklist, and relays every handoff from its producer's store

    # cycle-run.allium, rule RelayHandoff; surface EffectNodes, guarantee InTheProducersOrder; invariant EachHandoffOncePerConsumer
    # Legacy: harness/v2/assemble.clj, the relay (decisions.md, "Spec round 5" 1 and 2)
    # Suggested tier: minimum
    @draft @allium-rule-success.RelayHandoff @allium-guarantee.InTheProducersOrder @allium-invariant.EachHandoffOncePerConsumer
    Scenario: Each handoff is handed in, unchanged and once, to every consumer, in its producer's order
      Given the stub builder leaves a candidate the gate passes
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the relayed handoffs of run R-1 begin with specification/scenario-stamped at positions 1 and 2 to measurement, then cycle-preparation/source-cut-pinned at position 1 to measurement and to regeneration-cycle
      And regeneration-cycle/gate-attempt-passed at position 1 is relayed to measurement once
      And every relayed handoff was accepted

    # context.allium, contract ContextSeams, invariants RefusalsAreTheContexts and OnlyThroughTheSeams; surface EffectNodes, guarantee OwnsNoContextRule
    # Legacy: AGENTS.md "an unstamped scenario or an empty selection stops the cycle"
    # Suggested tier: minimum
    @draft @allium-boundary.RefusalsAreTheContexts @allium-boundary.OnlyThroughTheSeams @allium-guarantee.OwnsNoContextRule @allium-rule-success.HaltRun
    Scenario: A checklist Measurement refuses halts the run with Measurement's refusal
      Given the plan at the commit also holds a scenario with no item identity
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the checklist effect of run R-1 was refused "unstamped-scenarios", as measurement answered
      And run R-1 is halted for a refusal before the cycle
      And it has no self-check effect and no fence effect

  Rule: No clean room is built over a failed harness self-check

    # cycle-run.allium, rules ConcludeWithTheCycle and ConcludeRunFailed
    # Legacy: fence.sh calls check.sh --local before it builds a room
    # Suggested tier: minimum
    @draft @allium-rule-success.ConcludeWithTheCycle @allium-rule-success.ConcludeRunFailed
    Scenario: A failing self-check fails the cycle at the fence, and the run with it
      Given the self-check exits non-zero and its last line is "golden checklist differs"
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then run R-1 failed, its cycle failed at the fence for "harness-self-check-failed" with detail "golden checklist differs"
      And its last effect result is the self-check, and it has no fence effect and no regenerate effect

    # cycle-run.allium, rule RecordEffect (a skipped check is part of the recorded self-check)
    # Legacy: @harness-066, a missing tool is a loud skip
    # Suggested tier: balance
    @draft @allium-rule-success.RecordEffect
    Scenario: A check the self-check skips is recorded as a listed skip
      Given the self-check exits 0 and prints "SKIP bb-tools"
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the first self-check effect of run R-1 was recorded with the event "regeneration-cycle/harness-self-check-passed"
      And its detail names the skipped check "bb-tools"

  Rule: The fence builds the clean room of the cut and proves it, as legacy fence.sh does

    # context.allium, contract ContextSeams, invariant RefusalsAreTheContexts; rule ConcludeRunFailed
    # Legacy: fence.sh, "FENCE FAILED: X matches nothing"
    # Suggested tier: minimum
    @draft @allium-boundary.RefusalsAreTheContexts @allium-rule-success.ConcludeRunFailed
    Scenario: A fenced path that matches nothing fails the cycle at the fence
      Given the configuration fences "impl harness/cycles" and the commit holds impl but no harness/cycles
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the fence effect of run R-1 was refused "fenced-path-matched-nothing", as regeneration-cycle answered
      And its next effect, fail-cycle, was recorded, and run R-1 failed with its cycle at the fence for "fence-refused"
      And no builder was launched

    # context.allium, contract BuilderLaunch, invariant TheBuilderSeesOnlyItsMounts
    # Legacy: fence.sh, a clone of one commit with the fenced paths gone from the tree and the history
    # Suggested tier: minimum
    @draft @allium-boundary.TheBuilderSeesOnlyItsMounts
    Scenario: The fresh builder sees the clean room of one commit with no fenced path
      Given the configuration fences "impl harness/cycles" and the commit holds both
      And the stub builder records the paths and the commits its builder room holds
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the stub builder saw the plan, the configuration and an empty candidate scope
      And it saw one commit, and no path under impl or harness/cycles in the tree or the history

  Rule: A regenerate launches one fresh builder and records what it left in its candidate scope

    # context.allium, contract BuilderLaunch, invariant OneFreshBuilderPerRegenerate
    # Suggested tier: balance
    @draft @allium-boundary.OneFreshBuilderPerRegenerate
    Scenario: Each regenerate launches one builder and waits for it
      Given the stub builder records each launch and the candidate it leaves fails the gate once, then passes
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the stub builder was launched twice, once for the regenerate and once for the repair
      And the second launch began after the first had ended

    # context.allium, contract BuilderLaunch, invariant TheBuilderExitIsNoVerdict
    # Legacy: AGENTS.md "Done is the gate's verdict, never the builder's claim"
    # Suggested tier: minimum
    @draft @allium-boundary.TheBuilderExitIsNoVerdict
    Scenario: A builder that exits non-zero but leaves a candidate is gated
      Given the stub builder leaves a candidate the gate passes and exits with status 1
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the regenerate effect of run R-1 was recorded with that candidate's digest
      And run R-1 awaits its verdicts

    # context.allium, contract BuilderLaunch, invariant TheBuilderExitIsNoVerdict; rule ConcludeRunFailed
    # Legacy: AGENTS.md "Done is the gate's verdict, never the builder's claim"; a regenerate that produced nothing fails the cycle
    # Suggested tier: minimum
    @draft @allium-boundary.TheBuilderExitIsNoVerdict @allium-rule-success.ConcludeRunFailed
    Scenario: A builder that leaves nothing fails the cycle at regenerate
      Given the stub builder exits 0 and leaves the candidate scope empty
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then run R-1 has no regenerate effect, and its fail-cycle effect was recorded
      And run R-1 failed with its cycle at the regenerate stage for "candidate-not-regenerated"

    # context.allium, contract BuilderLaunch, invariant OneFreshBuilderPerRegenerate (bounded by the budget)
    # Suggested tier: balance
    @draft @allium-boundary.OneFreshBuilderPerRegenerate
    Scenario: A builder that outlives the regenerate timeout is stopped and the cycle fails for its budget
      Given the budget's regenerate timeout is 2 seconds and the stub builder sleeps 30 seconds
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the stub builder was stopped
      And run R-1 failed with its cycle at the regenerate stage for "budget"

  Rule: The gate grades a copy of the candidate on the host, in legacy verify.sh's order

    # context.allium, contract GateOnTheHost, invariant TheLegacyGateOrder
    # Legacy: verify.sh [1], the port must be free; decisions.md b
    # Suggested tier: minimum
    @draft @allium-boundary.TheLegacyGateOrder
    Scenario: Something answering on the port is a gate refusal, and three in a row fail the cycle
      Given the stub builder leaves a candidate the gate would pass
      And something already responds on the configured port
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the gate effects of run R-1 for attempt 1 were refused "seam-answered-before-boot" three times, each followed by a recorded gate-refusal effect
      And run R-1 failed with its cycle at the gate for "infrastructure", and the candidate's launch entrypoint was never started

    # context.allium, contract GateOnTheHost, invariant TheLegacyGateOrder
    # Legacy: verify.sh [2], the suite before anything is booted
    # Suggested tier: minimum
    @draft @allium-boundary.TheLegacyGateOrder
    Scenario: A red suite fails the attempt before the candidate is booted
      Given the stub builder leaves a candidate whose suite exits 1 and whose launch entrypoint records that it ran
      And the budget allows 1 gate attempt
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the gate effect of run R-1 for attempt 1 was recorded with the event "regeneration-cycle/gate-attempt-failed" for "suite-red"
      And the candidate's launch entrypoint never ran, and run R-1 failed with its cycle at the gate for "gate-attempts-exhausted"

    # context.allium, contract GateOnTheHost, invariant TheLegacyGateOrder
    # Legacy: verify.sh, test.sh and start.sh are the gate's contract
    # Suggested tier: balance
    @draft @allium-boundary.TheLegacyGateOrder
    Scenario: A candidate with no launch entrypoint fails the attempt for a missing contract
      Given the stub builder leaves a candidate with a test.sh and no start.sh
      And the budget allows 1 gate attempt
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the gate effect of run R-1 for attempt 1 was recorded with the event "regeneration-cycle/gate-attempt-failed" for "contract-missing"

    # context.allium, contract GateOnTheHost, invariant TheLegacyGateOrder
    # Legacy: verify.sh [4], APP_MARKER, then the identity probe
    # Suggested tier: minimum
    @draft @allium-boundary.TheLegacyGateOrder
    Scenario: A served page without the marker fails the attempt for its identity
      Given the stub builder leaves a candidate with a green suite whose page does not carry "sauna"
      And the configuration's marker is "sauna" and the budget allows 1 gate attempt
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the gate effect of run R-1 for attempt 1 was recorded with the event "regeneration-cycle/gate-attempt-failed" for "identity-probe-failed"

    # context.allium, contract GateOnTheHost, invariant TheGateGradesACopy
    # Suggested tier: balance
    @draft @allium-boundary.TheGateGradesACopy
    Scenario: What a builder writes after it ended does not reach the gate
      Given the stub builder leaves a candidate the gate passes and, after it exits, keeps writing a file that turns its suite red
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the gate effect of run R-1 for attempt 1 was recorded with the event "regeneration-cycle/gate-attempt-passed"
      And the install effect's candidate digest is the regenerate effect's

  Rule: A failed gate attempt is repaired by a fresh builder that sees only the previous candidate and the gate's evidence

    # context.allium, contract BuilderLaunch, invariant TheBuilderSeesOnlyItsMounts
    # Legacy: AGENTS.md "only the previous candidate and the gate's evidence, at most three attempts"
    # Suggested tier: minimum
    @draft @allium-boundary.TheBuilderSeesOnlyItsMounts
    Scenario: A repair builder sees the previous candidate and the gate evidence, read-only
      Given the stub builder records the paths its builder room holds, and its first candidate has a red suite
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the repair effect of run R-1 for attempt 2 was recorded with the gate evidence of attempt 1 mounted
      And the second launch saw the clean room, the previous candidate and the gate evidence of attempt 1, and could write neither of the last two

    # cycle-run.allium, rule ConcludeRunFailed
    # Legacy: AGENTS.md, at most three attempts
    # Suggested tier: minimum
    @draft @allium-rule-success.ConcludeRunFailed
    Scenario: Three failed attempts fail the cycle, and no fourth builder is launched
      Given every candidate the stub builder leaves has a red suite
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then the stub builder was launched three times
      And run R-1 failed with its cycle at the gate for "gate-attempts-exhausted"

  Rule: The verdicts of the gated candidate are handed in, filed as given, and the run ends as its cycle ends

    # cycle-run.allium, rules HandInVerdicts, RecordEffectAfterTheVerdicts and ConcludeRunPassed; invariant PassedOnlyWithVerdicts; surface RunDesk, guarantee TheRunnerJudgesNoItem
    # Legacy: step 5, a grading subagent's verdicts file, then score.clj and scoreboard.clj
    # Suggested tier: minimum
    @draft @allium-rule-success.HandInVerdicts @allium-rule-success.RecordEffectAfterTheVerdicts @allium-rule-success.ConcludeRunPassed @allium-invariant.PassedOnlyWithVerdicts @allium-guarantee.TheRunnerJudgesNoItem
    Scenario: Verdicts handed in are filed as given, and the run passes as its cycle passes
      Given run R-1 of cycle C-1 awaits its verdicts
      And a verdicts file holds 01-001 pass with evidence "booked" and 01-002 fail with evidence "judged by the main session"
      When the operator hands in that verdicts file for run R-1 with the grader digest "r-1"
      Then the file-verdicts and file-score effects of run R-1 were recorded
      And the file-score effect's measurement/measurement-filed event shows 1 of 2 passing, 01-001 pass and 01-002 fail with those evidences
      And run R-1 passed with its cycle

    # surface RunDesk, guarantee TheRunnerJudgesNoItem
    # Legacy: score.clj, "not scored: the grader never reached this item"
    # Suggested tier: balance
    @draft @allium-guarantee.TheRunnerJudgesNoItem
    Scenario: An item the verdicts file does not name is Measurement's to record as unreached
      Given run R-1 of cycle C-1 awaits its verdicts
      And a verdicts file holds only 01-001 pass
      When the operator hands in that verdicts file for run R-1 with the grader digest "r-1"
      Then the file-score effect's measurement/measurement-filed event shows 01-002 failed with the reason "unreached"
      And run R-1 passed with its cycle, 1 of 2 passing

    # cycle-run.allium, rule HandInVerdicts (requires 1)
    # Suggested tier: balance
    @draft @allium-rule-failure.HandInVerdicts.1
    Scenario: Verdicts for a run that does not exist are refused
      Given no run R-9 exists
      When the operator hands in a verdicts file for run R-9 with the grader digest "r-1"
      Then the hand-in is refused
      And no run R-9 exists

    # cycle-run.allium, rule HandInVerdicts (requires 2)
    # Legacy: score.clj files the verdicts of a gated candidate only
    # Suggested tier: minimum
    @draft @allium-rule-failure.HandInVerdicts.2
    Scenario: Verdicts for a run whose gate never passed are refused
      Given run R-1 of cycle C-1 failed with its cycle at the gate for "gate-attempts-exhausted"
      When the operator hands in a verdicts file for run R-1 with the grader digest "r-1"
      Then the hand-in is refused
      And run R-1 has no file-verdicts effect and is still failed

    # cycle-run.allium, rule HandInVerdicts (requires 3); invariant NoBuilderAfterTheVerdicts
    # Legacy: AGENTS.md "a failed score is measured, not retried within the cycle"
    # Suggested tier: minimum
    @draft @allium-rule-failure.HandInVerdicts.3 @allium-invariant.NoBuilderAfterTheVerdicts
    Scenario: A second verdicts file is refused, and no builder follows the verdicts
      Given verdicts were handed in for run R-1 of cycle C-1, and its cycle failed at the score for "score-stage-failed"
      When the operator hands in another verdicts file for run R-1 with the grader digest "r-1"
      Then the hand-in is refused
      And run R-1 has no regenerate or repair effect after its verdicts were handed in

    # cycle-run.allium, rule HandInVerdicts (requires 5)
    # Suggested tier: balance
    @draft @allium-rule-failure.HandInVerdicts.5
    Scenario: Verdicts under a grader digest the run did not approve are refused
      Given run R-1 of cycle C-1 awaits its verdicts, its approved grader digests "r-1"
      When the operator hands in a verdicts file for run R-1 with the grader digest "r-2"
      Then the hand-in is refused
      And run R-1 still awaits its verdicts

    # cycle-run.allium, rule HandInVerdicts (requires 6)
    # Legacy: score.clj refuses a blank file and one that is not JSON (@harness-033, @harness-034)
    # Suggested tier: minimum
    @draft @allium-rule-failure.HandInVerdicts.6
    Scenario: A verdicts file that is blank is refused, and nothing is filed
      Given run R-1 of cycle C-1 awaits its verdicts
      When the operator hands in a blank verdicts file for run R-1 with the grader digest "r-1"
      Then the hand-in is refused
      And run R-1 still awaits its verdicts, with no file-verdicts effect

    # context.allium, contract ContextSeams, invariant RefusalsAreTheContexts; rule ConcludeRunPassed (requires 2), rule ConcludeRunFailed (requires 2); invariant NoBuilderAfterTheVerdicts
    # Legacy: score.clj refuses an unknown item and writes nothing
    # Suggested tier: minimum
    @draft @allium-boundary.RefusalsAreTheContexts @allium-rule-failure.ConcludeRunPassed.2 @allium-rule-failure.ConcludeRunFailed.2 @allium-invariant.NoBuilderAfterTheVerdicts
    Scenario: Verdicts Measurement refuses fail the score stage, and the run fails with its cycle
      Given run R-1 of cycle C-1 awaits its verdicts
      And a verdicts file holds 01-001 pass and 09-001 pass
      When the operator hands in that verdicts file for run R-1 with the grader digest "r-1"
      Then the file-verdicts effect of run R-1 was refused "unknown-item", as measurement answered, and its fail-score-stage effect was recorded
      And run R-1 failed with its cycle at the score for "score-stage-failed", and no builder was launched after the verdicts

  Rule: A run that cannot perform an effect halts, loudly, and a run that ended stays as it ended

    # cycle-run.allium, rule HaltRun
    # Suggested tier: balance
    @draft @allium-rule-success.HaltRun
    Scenario: A context that stops answering halts the run, and nothing is recorded for that effect
      Given the launch entrypoint handed in for Measurement exits when it is asked to derive a checklist
      When the operator starts run R-1 of cycle C-1 at source cut K-1
      Then run R-1 is halted at the checklist effect because a context did not respond
      And it has no checklist effect result

    # cycle-run.allium, rules HaltRun (requires 1), ConcludeWithTheCycle (requires 1), ConcludeRunFailed (requires 1), RelayHandoff (requires 1)
    # Suggested tier: balance
    @draft @allium-rule-failure.HaltRun.1 @allium-rule-failure.ConcludeWithTheCycle.1 @allium-rule-failure.ConcludeRunFailed.1 @allium-rule-failure.RelayHandoff.1
    Scenario: A run that ended stays as it ended
      Given run R-1 of cycle C-1 failed with its cycle at the fence, and its contexts were stopped
      And run R-2 of cycle C-2 halted at the checklist effect
      When the operator reads the run progress of runs R-1 and R-2 a minute later
      Then run R-1 is still failed and run R-2 is still halted
      And neither has an effect result or a relayed handoff after it ended

    # surface RunDesk, guarantee ObservingChangesNothing
    # Suggested tier: balance
    @draft @allium-guarantee.ObservingChangesNothing
    Scenario: Reading a run's progress changes nothing
      Given run R-1 of cycle C-1 awaits its verdicts
      When the operator reads the run progress of run R-1 twice
      Then both readings are identical
      And run R-1 still awaits its verdicts
