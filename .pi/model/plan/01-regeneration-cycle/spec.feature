Feature: Preparing, approving, and running a regeneration cycle through an operator session
  An operator session prepares a fenced specification cut, reviews and approves a
  deterministic selection, and submits an idempotent ORC regeneration cycle. A fresh
  builder rebuilds the selection inside a physical clean room, the gate ratifies the
  candidate, and the instrument scores it. The approved denominator can never shrink
  because an item was missed.

  Rule: The neutral source cut is pinned and physically fenced

    @e2e
    @item-01-001
    Scenario: Preparing a cut returns immutable commit and tree identities
      Given the operator names a full source commit and an execution backend
      When the Pi harness prepares the source cut
      Then the prepared cut is rooted at exactly that source commit
      And its fenced tree identity is recorded
      And prior implementation and measurement paths are absent from the reachable cut history

    @skip
    @item-01-002
    Scenario: The builder cannot modify or escape the cut
      Given a prepared cut and a declared candidate scope
      When a builder session is opened in Gondolin
      Then the cut is mounted read-only
      And writes outside the declared candidate scope are denied
      And network access, judge material, host credentials, and host session resources are unavailable

    @skip
    @item-01-003
    Scenario: The clean room holds the spec, not an answer or a judge
      Given the repository holds implementations, measurements, reports, and the instrument
      When the fence builds the clean room
      Then the spec, the domain sources, and the public application contract remain readable
      And prior implementations, recorded measurements, and reports are absent from the room and its history
      And the instrument, its calibration fixtures, and the harness that grades are absent from the room
      And a fenced path that matches nothing stops the fence instead of passing silently

    @skip
    @item-01-004
    Scenario: Slice mappings reconstruct the complete cut-bound system view
      Given a prepared cut contains Gherkin behavior items grouped into slices, components, and containers
      When the harness maps each item and reduces those mappings into a system view
      Then every behavior item appears exactly once in the reconstructed hierarchy
      And decomposing that hierarchy reproduces the same slice mappings
      And any missing, duplicate, or conflicting mapping makes the composition invalid

  Rule: Selection is deterministic and approval binds the reviewed identity

    @e2e
    @item-01-005
    Scenario: Previewing the same slice produces the same selection identity
      Given the same pinned cut, project, slice, and requested item subset
      And the selection has not already been approved
      When the selection is previewed more than once
      Then each preview contains the same ordered item denominator
      And each preview has the same selection identity and digest
      And its status is awaiting approval

    @e2e
    @item-01-006
    Scenario: Approval refuses a digest other than the reviewed digest
      Given a selection contract is awaiting approval
      When approval names its selection identity with a different digest
      Then approval is refused
      And the selection remains awaiting approval

  Rule: Cycle submission is approved, scoped, and idempotent

    @e2e
    @item-01-007
    Scenario: An unapproved selection cannot start a regeneration cycle
      Given a recorded selection has not been approved
      When the operator submits a regeneration cycle for that selection
      Then submission is refused before a builder starts

    @skip
    @item-01-008
    Scenario: Repeating the same request identity returns the original receipt
      Given a regeneration request has already been submitted
      When the identical request is submitted with the same request identity
      Then the original cycle and task receipt is returned
      But reusing that request identity with a different payload is refused

  Rule: The builder produces a new candidate from the pinned contract

    @skip
    @item-01-009
    Scenario: The builder starts fresh and must produce workspace output
      Given an approved selection and a prepared cut
      When the builder stage runs
      Then it uses a fresh separately identified builder session without the operator transcript
      And it receives the ORC-selected instruction and approved item identities
      And it executes at least one confined workspace tool
      And a cycle with no generated workspace output fails

    @e2e
    @item-01-010
    Scenario: ORC exposes builder session identity in its executable contract
      Given the Pi harness ORC worker is running
      When Pi requests the executable regeneration workflow contract
      Then the builder result includes the field "builderSessionId"

  Rule: Independent checks determine whether the cycle passes

    @skip
    @item-01-011
    Scenario: Verification rejects an incomplete runnable application contract
      Given the builder has produced a candidate
      When the gate inspects and runs it
      Then the candidate must define app and test services
      And the app must define a healthcheck without publishing host ports
      And every required compose operation and candidate test command must succeed

    @skip @playback
    @item-01-012
    Scenario: A final result requires every independent gate to pass
      Given the gate, the score, and the measurement have completed
      When the final result is derived
      Then it does not pass when the gate or any checklist item failed
      And every approved item was graded
      And failed approved items remain in the denominator

  Rule: Evidence and measurement survive workflow process restarts

    @skip
    @item-01-013
    Scenario: Recording the same evidence twice is idempotent
      Given an observation or cycle measurement has already been recorded
      When the exact same identity and content are recorded again
      Then the existing durable record is returned
      But the same identity with different evidence is refused

    @skip
    @item-01-014
    Scenario: A completed measurement is readable after the harness restarts
      Given a cycle measurement was recorded through its validated command
      When the harness restarts against the same event store
      Then the complete measurement is reconstructed with the same denominator and verdicts

  Rule: Backend limitations remain explicit

    @skip
    @item-01-015
    Scenario: Host execution cannot be reported as independently graded
      Given a regeneration cycle uses the host backend
      When it reaches the independent grade stage
      Then the collector reports that independent comparison requires Gondolin
      And the cycle cannot pass as a complete independently graded measurement
