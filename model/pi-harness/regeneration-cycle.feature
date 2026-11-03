Feature: Specification-led regeneration through an operator session and ORC
  An operator session prepares a fenced specification cut, reviews and approves a
  deterministic cycle preparation, and submits an idempotent ORC regeneration
  cycle. A fresh builder produces a candidate in a confined workspace. Independent
  verification and grading determine the result, and the approved denominator can
  never shrink because an item was missed.

  Rule: A regeneration cut is pinned and physically fenced

    @item-01-001 @e2e
    Scenario: Preparing a cut returns immutable commit and tree identities
      Given the operator names a full source commit and an execution backend
      When the Pi harness prepares the regeneration cut
      Then the prepared cut is rooted at exactly that source commit
      And its fenced tree identity is recorded
      And prior implementation and measurement paths are absent from the reachable cut history

    @item-01-002 @skip
    Scenario: The builder cannot modify or escape the cut
      Given a prepared cut and a declared candidate scope
      When a builder session is opened in Gondolin
      Then the cut is mounted read-only
      And writes outside the declared candidate scope are denied
      And network access, judge material, host credentials, and host session resources are unavailable

  Rule: Selection is deterministic and approval binds the reviewed identity

    @item-01-003 @e2e
    Scenario: Previewing the same slice produces the same selection identity
      Given the same pinned cut, project, slice, and requested item subset
      And the selection has not already been approved
      When the selection is previewed more than once
      Then each preview contains the same ordered item denominator
      And each preview has the same selection identity and digest
      And its status is awaiting approval

    @item-01-004 @e2e
    Scenario: Approval refuses a digest other than the reviewed digest
      Given a selection contract is awaiting approval
      When approval names its selection identity with a different digest
      Then approval is refused
      And the selection remains awaiting approval

  Rule: Cycle submission is approved, scoped, and idempotent

    @item-01-005 @e2e
    Scenario: An unapproved selection cannot start a regeneration cycle
      Given a recorded selection has not been approved
      When the operator submits a regeneration cycle for that selection
      Then submission is refused before a builder starts

    @item-01-006 @skip
    Scenario: Repeating the same request identity returns the original receipt
      Given a regeneration request has already been submitted
      When the identical request is submitted with the same request identity
      Then the original cycle and task receipt is returned
      But reusing that request identity with a different payload is refused

  Rule: The builder produces a new candidate from the pinned contract

    @item-01-007 @skip
    Scenario: The builder starts fresh and must produce workspace output
      Given an approved selection and a prepared cut
      When the builder stage runs
      Then it uses a fresh separately identified builder session without the operator transcript
      And it receives the ORC-selected instruction and approved item identities
      And it executes at least one confined workspace tool
      And a cycle with no generated workspace output fails

    @item-01-007 @e2e
    Scenario: ORC exposes builder session identity in its executable contract
      Given the Pi harness ORC worker is running
      When Pi requests the executable regeneration workflow contract
      Then the builder result includes the field "builderSessionId"

  Rule: Independent checks determine whether the cycle passes

    @item-01-008 @skip
    Scenario: Verification rejects an incomplete runnable application contract
      Given the builder has produced a candidate
      When the independent verifier inspects and runs it
      Then the candidate must define app and test services
      And the app must define a healthcheck without publishing host ports
      And every required compose operation and candidate test command must succeed

    @item-01-009 @skip
    Scenario: Independent grading fails closed when no readable verdict exists
      Given an approved selection with at least one item
      When the independent grader returns no readable observation
      Then the grading stage fails
      And no passing cycle measurement is recorded

    @item-01-010 @skip
    Scenario: An unreached approved item remains a failed denominator item
      Given the approved selection contains three items
      And the grader returns evidence for only one item
      When the cycle measurement is reduced
      Then the measurement total remains three
      And each unreached item is recorded as failed and not graded
      And the measurement is marked incomplete

    @item-01-011 @e2e @playback
    Scenario: A final result requires every independent gate to pass
      Given the oracle, verifier, grader, and measurement have completed
      When the final result is derived
      Then it does not pass when any independent gate failed
      And every approved item was graded
      And failed approved items remain in the denominator

  Rule: Evidence and measurement survive workflow process restarts

    @item-01-012 @skip
    Scenario: Recording the same evidence twice is idempotent
      Given an observation or cycle measurement has already been recorded
      When the exact same identity and content are recorded again
      Then the existing durable record is returned
      But the same identity with different evidence is refused

    @item-01-013 @skip
    Scenario: A completed measurement is readable after the harness restarts
      Given a cycle measurement was recorded through its validated command
      When the harness restarts against the same event store
      Then the complete measurement is reconstructed with the same denominator and verdicts

  Rule: Backend limitations remain explicit

    @item-01-014 @skip
    Scenario: Host execution cannot be reported as independently graded
      Given a regeneration cycle uses the host backend
      When it reaches independent grading
      Then the grader reports that independent grading requires Gondolin
      And the cycle cannot pass as a complete independently graded measurement
