Feature: The regeneration cycle, draft scenarios
  Drafts for the Allium obligations no scenario in spec.feature covers (2026-11-06).
  The harness does not read this file. Only the product owner moves a draft into
  spec.feature, under the Rule it belongs to; the stamper then gives it its @item id and
  the @draft tag is dropped. Every noun is a term of .pi/model/GLOSSARY.md.

  # execution-pipeline.allium, rule RunHarnessSelfCheck (requires 1)
  # Goes under: Rule: The fence and the gate feed the build loop; the score never does
  # Suggested tier: minimum

    @draft @allium-rule-failure.RunHarnessSelfCheck.1
    Scenario: A harness self-check that reproduced no instrument golden is refused
      Given a running regeneration cycle waits at the fence
      When ORC reports a harness self-check that names no instrument golden
      Then the harness self-check is refused
      And no passing harness self-check is recorded for that harness digest
      And the cycle stays at the fence

  # execution-pipeline.allium, rule FenceCleanRoom (requires 4)
  # Goes under: Rule: The fence and the gate feed the build loop; the score never does
  # Suggested tier: minimum

    @draft @allium-rule-failure.FenceCleanRoom.4
    Scenario: The fence refuses a cycle whose grader nobody approved for every selected item
      Given a regeneration cycle was submitted from an approved cycle preparation and ORC started it
      And a passing harness self-check is recorded for the harness digest
      And the preparation's grader digest names no approved sealed grader covering every selected item
      When ORC reports the clean room proved for the cycle
      Then the clean room is refused
      And the cycle stays at the fence with no clean room

  # execution-pipeline.allium, rule FenceCleanRoom (requires 5)
  # Goes under: Rule: The fence and the gate feed the build loop; the score never does
  # Suggested tier: minimum

    @draft @allium-rule-failure.FenceCleanRoom.5
    Scenario: A clean room with no fenced path is refused
      Given a running regeneration cycle waits at the fence
      And a passing harness self-check and an approved sealed grader cover its selection
      When ORC reports the clean room proved with no fenced path
      Then the clean room is refused
      And the cycle stays at the fence with no clean room

  # execution-pipeline.allium, invariant CleanRoomBeforeBuilder
  # Goes under: Rule: The fence and the gate feed the build loop; the score never does
  # Suggested tier: minimum

    @draft @allium-invariant.CleanRoomBeforeBuilder
    Scenario: No candidate is recorded for a cycle whose clean room was never proved
      Given a running regeneration cycle whose fence never recorded a clean room
      When ORC reports a builder session completed for the cycle
      Then no candidate subject is recorded for the cycle
      And every candidate subject the harness holds belongs to a cycle with a clean room

  # execution-pipeline.allium, rule RegenerateCandidate
  # Goes under: Rule: The fence and the gate feed the build loop; the score never does
  # Suggested tier: minimum

    @draft @allium-rule-success.RegenerateCandidate
    Scenario: A completed builder session records the candidate and moves the cycle to the gate
      Given a running regeneration cycle whose clean room was proved
      And every selected slice was built by its slice builder on the first regenerate attempt
      When ORC reports the last builder session of attempt 1 completed with generated paths and tool calls
      Then the candidate subject of attempt 1 is recorded with its subject digest
      And the cycle moves to the gate

  # execution-pipeline.allium, rule RegenerateCandidate (requires 3)
  # Goes under: Rule: The fence and the gate feed the build loop; the score never does
  # Suggested tier: balance

    @draft @allium-rule-failure.RegenerateCandidate.3
    Scenario: A candidate for regenerate attempt 0 is refused
      Given a running regeneration cycle at the regenerate stage
      When ORC reports a builder session completed for attempt 0
      Then the candidate is refused
      And the cycle stays at the regenerate stage with no candidate subject

  # execution-pipeline.allium, rule RegenerateCandidate (requires 4)
  # Goes under: Rule: The fence and the gate feed the build loop; the score never does
  # Suggested tier: minimum

    @draft @allium-rule-failure.RegenerateCandidate.4
    Scenario: A fourth regenerate attempt is refused
      Given a running regeneration cycle at the regenerate stage
      When ORC reports a builder session completed for attempt 4
      Then the candidate is refused
      And the cycle stays at the regenerate stage with no candidate subject for attempt 4

  # execution-pipeline.allium, rule RegenerateCandidate (requires 5)
  # Goes under: Rule: The fence and the gate feed the build loop; the score never does
  # Suggested tier: balance

    @draft @allium-rule-failure.RegenerateCandidate.5
    Scenario: A candidate from another cycle's builder session is refused
      Given a running regeneration cycle at the regenerate stage
      And ORC launched a builder session for a different regeneration cycle
      When ORC reports that builder session completed for this cycle
      Then the candidate is refused
      And this cycle stays at the regenerate stage with no candidate subject

  # execution-pipeline.allium, rule RegenerateCandidate (requires 6)
  # Goes under: Rule: The fence and the gate feed the build loop; the score never does
  # Suggested tier: balance

    @draft @allium-rule-failure.RegenerateCandidate.6
    Scenario: A candidate from a builder session of another regenerate attempt is refused
      Given a running regeneration cycle at the regenerate stage for attempt 2
      And ORC launched the cycle's repair builder session for attempt 3
      When ORC reports that builder session completed for attempt 2
      Then the candidate is refused
      And the cycle stays at the regenerate stage with no candidate subject for attempt 2

  # execution-pipeline.allium, rule RegenerateCandidate (requires 7)
  # Goes under: Rule: The fence and the gate feed the build loop; the score never does
  # Suggested tier: balance

    @draft @allium-rule-failure.RegenerateCandidate.7
    Scenario: A grader author's builder session cannot produce a candidate
      Given a running regeneration cycle at the regenerate stage
      And ORC launched a grader author builder session
      When ORC reports that builder session completed for the cycle
      Then the candidate is refused
      And the cycle stays at the regenerate stage with no candidate subject

  # execution-pipeline.allium, rule RegenerateCandidate (requires 10)
  # Goes under: Rule: The fence and the gate feed the build loop; the score never does
  # Suggested tier: minimum

    @draft @allium-rule-failure.RegenerateCandidate.10
    Scenario: A repair candidate built from more than the previous candidate and its gate evidence is refused
      Given the gate attempt 1 of a running regeneration cycle failed and the cycle returned to the regenerate stage
      And the repair builder room of attempt 2 held an earlier cycle's candidate beside the gate evidence
      When ORC reports the repair builder session of attempt 2 completed
      Then the candidate is refused
      And the cycle stays at the regenerate stage with no candidate subject for attempt 2

  # execution-pipeline.allium, rule BuildSlice (requires 3)
  # Goes under: Rule: The first regenerate attempt builds one slice at a time
  # Suggested tier: balance

    @draft @allium-rule-failure.BuildSlice.3
    Scenario: A slice build from another cycle's slice builder is refused
      Given a running regeneration cycle at the regenerate stage with no slice built
      And ORC launched a slice builder session for a different regeneration cycle
      When ORC reports that slice builder completed the first selected slice of this cycle
      Then the slice build is refused
      And this cycle still has no slice built

  # execution-pipeline.allium, rule BuildSlice (requires 4)
  # Goes under: Rule: The first regenerate attempt builds one slice at a time
  # Suggested tier: balance

    @draft @allium-rule-failure.BuildSlice.4
    Scenario: A slice build from a repair builder session is refused
      Given a running regeneration cycle at the regenerate stage with no slice built
      And ORC launched a repair builder session for the cycle
      When ORC reports that builder session completed the first selected slice
      Then the slice build is refused
      And the cycle still has no slice built

  # execution-pipeline.allium, rule BuildSlice (requires 5)
  # Goes under: Rule: The first regenerate attempt builds one slice at a time
  # Suggested tier: balance

    @draft @allium-rule-failure.BuildSlice.5
    Scenario: A slice build at a position other than the slice's plan position is refused
      Given a running regeneration cycle whose approved selection holds the slices 01 and 02 in plan order
      And no slice was built yet
      When ORC reports the slice builder of slice 02 completed at position 1
      Then the slice build is refused
      And the cycle still has no slice built

  # execution-pipeline.allium, rule BuildSlice (requires 6)
  # Goes under: Rule: The first regenerate attempt builds one slice at a time
  # Suggested tier: balance

    @draft @allium-rule-failure.BuildSlice.6
    Scenario: A slice build out of plan order is refused
      Given a running regeneration cycle whose approved selection holds the slices 01, 02, and 03 in plan order
      And only slice 01 was built
      When ORC reports the slice builder of slice 03 completed at position 3
      Then the slice build is refused
      And only slice 01 is built

  # execution-pipeline.allium, rule BuildSlice (requires 8)
  # Goes under: Rule: The first regenerate attempt builds one slice at a time
  # Suggested tier: balance

    @draft @allium-rule-failure.BuildSlice.8
    Scenario: A slice builder that generated nothing does not build its slice
      Given a running regeneration cycle at the regenerate stage with no slice built
      When ORC reports the slice builder of the first selected slice completed with no generated path
      Then the slice build is refused
      And the cycle still has no slice built

  # execution-pipeline.allium, rule RecordGateAttempt
  # Goes under: Rule: The fence and the gate feed the build loop; the score never does
  # Suggested tier: minimum

    @draft @allium-rule-success.RecordGateAttempt
    Scenario: The gate's return is recorded as a gate attempt bound to the candidate
      Given a running regeneration cycle at the gate with the candidate subject of attempt 1
      When ORC reports the gate returned for attempt 1 with that subject digest, its target stack, and its gate evidence
      Then a gate attempt is recorded with its attempt number, subject digest, target stack, result, and gate evidence
      And the subject digest recomputed after the gate equals the recorded one

  # execution-pipeline.allium, rule RecordGateAttempt (requires 3)
  # Goes under: Rule: The fence and the gate feed the build loop; the score never does
  # Suggested tier: minimum

    @draft @allium-rule-failure.RecordGateAttempt.3
    Scenario: A gate attempt for a subject digest no candidate carries is refused
      Given a running regeneration cycle at the gate with the candidate subject of attempt 1
      When ORC reports the gate returned for attempt 1 with a different subject digest
      Then the gate attempt is refused
      And the cycle stays at the gate with no gate attempt

  # execution-pipeline.allium, rule RecordGateAttempt (requires 4)
  # Goes under: Rule: The fence and the gate feed the build loop; the score never does
  # Suggested tier: minimum

    @draft @allium-rule-failure.RecordGateAttempt.4
    Scenario: A gate that changed the candidate it gated is refused
      Given a running regeneration cycle at the gate with the candidate subject of attempt 1
      And the candidate's digest recomputed after the gate differs from its subject digest
      When ORC reports the gate returned for attempt 1
      Then the gate attempt is refused
      And the cycle stays at the gate with no gate attempt

  # execution-pipeline.allium, rules PassGate, RepairAfterFailedGate (requires 2) and
  # FailAfterThreeGateAttempts (requires 2)
  # Goes under: Rule: The fence and the gate feed the build loop; the score never does
  # Suggested tier: minimum

    @draft @allium-rule-success.PassGate @allium-rule-failure.PassGate.2 @allium-rule-failure.RepairAfterFailedGate.2 @allium-rule-failure.FailAfterThreeGateAttempts.2
    Scenario: A passing gate attempt moves the cycle to the score and only a passing one does
      Given a running regeneration cycle at the gate with the candidate subject of attempt 3
      When ORC reports the gate passed for attempt 3
      Then the cycle moves to the score
      And no repair builder session starts and the cycle does not fail at the gate
      But a gate that did not pass never moves a cycle to the score

  # execution-pipeline.allium, rule RepairAfterFailedGate and FailAfterThreeGateAttempts (requires 3)
  # Goes under: Rule: The fence and the gate feed the build loop; the score never does
  # Suggested tier: minimum

    @draft @allium-rule-success.RepairAfterFailedGate @allium-rule-failure.FailAfterThreeGateAttempts.3
    Scenario: A failed second gate attempt returns the cycle to the regenerate stage
      Given a running regeneration cycle at the gate with the candidate subject of attempt 2
      When ORC reports the gate failed for attempt 2 with its reason
      Then the cycle returns to the regenerate stage for a repair builder
      And the cycle does not fail at the gate

  # execution-pipeline.allium, rules FailAfterThreeGateAttempts and RepairAfterFailedGate (requires 3)
  # Goes under: Rule: The fence and the gate feed the build loop; the score never does
  # Suggested tier: minimum

    @draft @allium-rule-failure.RepairAfterFailedGate.3
    Scenario: A failed third gate attempt starts no fourth regenerate attempt
      Given a running regeneration cycle at the gate with the candidate subject of attempt 3
      When ORC reports the gate failed for attempt 3
      Then no repair builder session starts
      And the cycle fails at the gate with the reason "gate failed after three regenerate attempts"

  # execution-pipeline.allium, rules PassGate, RepairAfterFailedGate and
  # FailAfterThreeGateAttempts (requires 1 of each)
  # Goes under: Rule: The fence and the gate feed the build loop; the score never does
  # Suggested tier: balance

    @draft @allium-rule-failure.PassGate.1 @allium-rule-failure.RepairAfterFailedGate.1 @allium-rule-failure.FailAfterThreeGateAttempts.1
    Scenario: A gate attempt recorded for a cycle no longer at the gate moves nothing
      Given a regeneration cycle that has left the gate
      When a gate attempt for that cycle is recorded
      Then the cycle does not move to the score or the regenerate stage
      And the cycle's stage and status are unchanged

  # execution-pipeline.allium, rule LaunchDurableBuilderSession (requires 5)
  # Goes under: Rule: ORC launches every builder as a durable Pi session in a room ORC chooses
  # Suggested tier: balance

    @draft @allium-rule-failure.LaunchDurableBuilderSession.5
    Scenario: A slice builder room with nothing writable is refused
      Given a running regeneration cycle at the regenerate stage
      When ORC starts a slice builder tick whose builder room declares no candidate scope writable
      Then the builder session is refused
      And no builder session exists for that tick

  # execution-pipeline.allium, rule LaunchDurableBuilderSession (requires 7)
  # Goes under: Rule: ORC launches every builder as a durable Pi session in a room ORC chooses
  # Suggested tier: balance

    @draft @allium-rule-failure.LaunchDurableBuilderSession.7
    Scenario: Only a repair builder room mounts gate evidence
      Given a running regeneration cycle at the regenerate stage for attempt 1
      When ORC starts a slice builder tick whose builder room mounts gate evidence
      Then the builder session is refused
      And no builder session exists for that tick

  # execution-pipeline.allium, rule LaunchDurableBuilderSession (requires 8)
  # Goes under: Rule: ORC launches every builder as a durable Pi session in a room ORC chooses
  # Suggested tier: balance

    @draft @allium-rule-failure.LaunchDurableBuilderSession.8
    Scenario: A grader author session bound to a regeneration cycle is refused
      Given a running regeneration cycle
      When ORC starts a grader author tick that names the cycle
      Then the builder session is refused
      And no builder session exists for that tick

  # execution-pipeline.allium, rule LaunchDurableBuilderSession (requires 9)
  # Goes under: Rule: ORC launches every builder as a durable Pi session in a room ORC chooses
  # Suggested tier: balance

    @draft @allium-rule-failure.LaunchDurableBuilderSession.9
    Scenario: A slice builder or repair builder session must name its cycle and regenerate attempt
      Given no builder session exists for a tick
      When ORC starts a slice builder tick that names no regeneration cycle or no regenerate attempt
      Then the builder session is refused
      And no builder session exists for that tick

  # execution-pipeline.allium, rule ResumeDurableBuilderSession (requires 1)
  # Goes under: Rule: ORC launches every builder as a durable Pi session in a room ORC chooses
  # Suggested tier: balance

    @draft @allium-rule-failure.ResumeDurableBuilderSession.1
    Scenario: A relaunch in a different builder room is refused
      Given ORC launched a slice builder session for a tick and it is running
      When ORC relaunches that tick in a builder room other than the one it chose at launch
      Then the relaunch is refused
      And the builder session keeps its builder room and its launch count

  # execution-pipeline.allium, rule ResumeDurableBuilderSession (requires 2)
  # Goes under: Rule: ORC launches every builder as a durable Pi session in a room ORC chooses
  # Suggested tier: balance

    @draft @allium-rule-failure.ResumeDurableBuilderSession.2
    Scenario: A relaunch with a different production prompt is refused
      Given ORC launched a slice builder session for a tick and it is running
      When ORC relaunches that tick with a production prompt of another digest
      Then the relaunch is refused
      And the builder session keeps its production prompt digest and its launch count

  # execution-pipeline.allium, rule EndBuilderSession
  # Goes under: Rule: ORC launches every builder as a durable Pi session in a room ORC chooses
  # Suggested tier: balance

    @draft @allium-rule-success.EndBuilderSession
    Scenario: The builder host's report ends a builder session completed or failed
      Given ORC launched two slice builder sessions and both are running
      When the builder host reports the first session finished and the second stopped without finishing
      Then the first builder session is completed and the second is failed
      And neither outcome is a verdict on the candidate

  # execution-pipeline.allium, rule AdvanceToScoreboard
  # Goes under: Rule: The fence and the gate feed the build loop; the score never does
  # Suggested tier: minimum

    @draft @allium-rule-success.AdvanceToScoreboard
    Scenario: A filed measurement moves the cycle to the scoreboard
      Given a running regeneration cycle whose gate passed and which is at the score
      When the approved sealed grader's measurement is filed for the cycle
      Then the cycle moves to the scoreboard

  # execution-pipeline.allium, rule AdvanceToScoreboard (requires 1)
  # Goes under: Rule: Non-success outcomes remain explicit and measurable
  # Suggested tier: balance

    @draft @allium-rule-failure.AdvanceToScoreboard.1
    Scenario: A measurement filed after the cycle failed does not revive it
      Given a regeneration cycle failed at the score because the score stage timed out
      When a measurement for the cycle is filed late
      Then the cycle does not move to the scoreboard
      And the cycle stays failed at the score

  # execution-pipeline.allium, rule AdvanceToScoreboard (requires 2)
  # Goes under: Rule: The fence and the gate feed the build loop; the score never does
  # Suggested tier: balance

    @draft @allium-rule-failure.AdvanceToScoreboard.2
    Scenario: A measurement for a cycle not at the score moves nothing
      Given a running regeneration cycle that has already moved to the scoreboard
      When a second measurement for the cycle is filed
      Then the cycle's stage is unchanged

  # execution-pipeline.allium, rule RecordScoreboard
  # Goes under: Rule: The fence and the gate feed the build loop; the score never does
  # Suggested tier: minimum

    @draft @allium-rule-success.RecordScoreboard
    Scenario: A reported scoreboard moves the cycle to its result
      Given a running regeneration cycle whose measurement was filed and which is at the scoreboard
      When the scoreboard is reported for the cycle
      Then the cycle moves to its result

  # execution-pipeline.allium, rule RecordScoreboard (requires 1)
  # Goes under: Rule: Non-success outcomes remain explicit and measurable
  # Suggested tier: balance

    @draft @allium-rule-failure.RecordScoreboard.1
    Scenario: A scoreboard reported after the cycle failed does not revive it
      Given a regeneration cycle failed at the scoreboard because the scoreboard stage errored
      When a scoreboard for the cycle is reported late
      Then the cycle does not move to its result
      And the cycle stays failed at the scoreboard

  # execution-pipeline.allium, rule RecordScoreboard (requires 2)
  # Goes under: Rule: The fence and the gate feed the build loop; the score never does
  # Suggested tier: balance

    @draft @allium-rule-failure.RecordScoreboard.2
    Scenario: A scoreboard for a cycle not at the scoreboard moves nothing
      Given a running regeneration cycle at the score whose measurement is not filed
      When a scoreboard for the cycle is reported
      Then the cycle's stage is unchanged

  # execution-pipeline.allium, rule PassCycle
  # Goes under: new Rule: The result follows the measurement and names the gated candidate
  # Suggested tier: minimum

    @draft @allium-rule-success.PassCycle
    Scenario: A cycle whose complete measurement failed no item passes and names its candidate
      Given a running regeneration cycle whose gate passed attempt 2
      And its complete measurement failed no checklist item and its scoreboard was reported
      When the cycle reaches its result
      Then the cycle passed
      And it names the subject digest of the candidate gate attempt 2 passed

  # execution-pipeline.allium, invariant ScoreOnlyAfterPassingGate
  # Goes under: Rule: The fence and the gate feed the build loop; the score never does
  # Suggested tier: minimum

    @draft @allium-invariant.ScoreOnlyAfterPassingGate
    Scenario: A cycle whose gate never passed has no measurement
      Given a regeneration cycle failed at the gate after three regenerate attempts
      When the cycle's measurement is read
      Then no measurement exists for the cycle
      And every measurement the harness holds belongs to a cycle with a passing gate attempt

  # execution-pipeline.allium, invariant GraderIsAuthoredByAGraderAuthorSession
  # Goes under: Rule: ORC launches every builder as a durable Pi session in a room ORC chooses
  # Suggested tier: balance

    @draft @allium-invariant.GraderIsAuthoredByAGraderAuthorSession
    Scenario: Every grader names the grader author session that wrote it
      Given ORC launched a grader author builder session and it wrote a grader
      When the grader is sealed
      Then the grader names that builder session as its author
      And no grader names a slice builder or repair builder session as its author
