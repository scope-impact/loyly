Feature: Session-independent generation and regeneration control, draft scenarios
  Drafts for the Allium obligations no scenario in spec.feature covers (2026-11-06).
  The harness does not read this file. Only the product owner moves a draft into
  spec.feature, under the Rule it belongs to; the stamper then gives it its @item id and
  the @draft tag is dropped. Every noun is a term of GLOSSARY.md.

  # operator-control-plane.allium, rule ValidatePiHarnessServiceArea
  # Goes under: Rule: The Pi harness is a strictly validated Grain service area inside ORC
  # Suggested tier: deferred

    @draft @allium-rule-success.ValidatePiHarnessServiceArea
    Scenario: A Pi harness service area that matches its Grain Event Model is validated at startup
      Given the Pi harness service area is registered in ORC's Grain runtime
      And its commands, events, read models, queries, and processors match its Grain Event Model
      And every required Grain-to-Allium trace link resolves
      When ORC reports that it loaded the Pi harness service area
      Then the Pi harness service area is recorded as validated for ORC's Grain runtime

  # operator-control-plane.allium, rule ValidatePiHarnessServiceArea (requires 1)
  # Goes under: Rule: The Pi harness is a strictly validated Grain service area inside ORC
  # Suggested tier: deferred

    @draft @allium-rule-failure.ValidatePiHarnessServiceArea.1
    Scenario: A Pi harness service area loaded outside ORC's Grain runtime is refused
      Given the Pi harness service area is loaded in a Grain runtime other than ORC's
      When ORC reports that it loaded the Pi harness service area
      Then validation is refused and the harness process does not start
      And no Pi harness service area is recorded as validated

  # operator-control-plane.allium, rule ValidatePiHarnessServiceArea (requires 2)
  # Goes under: Rule: The Pi harness is a strictly validated Grain service area inside ORC
  # Suggested tier: deferred

    @draft @allium-rule-failure.ValidatePiHarnessServiceArea.2
    Scenario: An unregistered Pi harness service area is refused
      Given ORC's Grain runtime has not registered the Pi harness service area
      When ORC reports that it loaded the Pi harness service area
      Then validation is refused and the harness process does not start
      And no Pi harness service area is recorded as validated

  # operator-control-plane.allium, rule ValidatePiHarnessServiceArea (requires 4)
  # Goes under: Rule: The Pi harness is a strictly validated Grain service area inside ORC
  # Suggested tier: deferred

    @draft @allium-rule-failure.ValidatePiHarnessServiceArea.4
    Scenario: An unresolved Grain-to-Allium trace link is refused at startup
      Given the Pi harness service area matches its Grain Event Model
      But one command links an Allium rule that does not exist
      When ORC reports that it loaded the Pi harness service area
      Then validation is refused naming the unresolved trace link
      And no Pi harness service area is recorded as validated

  # operator-control-plane.allium, rule DispatchSubmittedTaskToOrc
  # Goes under: Rule: Workflow tasks have observable terminal outcomes
  # Suggested tier: balance

    @draft @allium-rule-success.DispatchSubmittedTaskToOrc
    Scenario: A submitted workflow task is dispatched to ORC once
      Given an operator submitted a regeneration cycle from an approved cycle preparation
      And its workflow task was opened
      When the harness dispatches the workflow task to ORC
      Then ORC receives one command whose identity is derived from the workflow task
      And the command names the workflow task and its cycle preparation

  # operator-control-plane.allium, rule DispatchSubmittedTaskToOrc (requires 1)
  # Goes under: Rule: Workflow tasks have observable terminal outcomes
  # Suggested tier: balance

    @draft @allium-rule-failure.DispatchSubmittedTaskToOrc.1
    Scenario: A redelivered workflow task starts no second ORC tick
      Given a workflow task was opened and ORC was dispatched a tick for it
      When the workflow task is delivered for dispatch again
      Then no second command is dispatched to ORC
      And the workflow task still has exactly one ORC tick

  # operator-control-plane.allium, rule PrepareCutTask
  # Goes under: Rule: Every public operation refuses malformed or conflicting identities
  # Suggested tier: balance

    @draft @allium-rule-success.PrepareCutTask
    Scenario: Submitting a cut preparation opens a workflow task
      Given no workflow task exists for the request identity
      When the operator submits a source-cut preparation naming a full source commit and the gondolin execution backend
      Then a task receipt is returned for a new cut-preparation workflow task
      And the workflow task is submitted with no trace and no outcome yet

  # operator-control-plane.allium, rule OpenCycleTask
  # Goes under: Rule: Workflow tasks have observable terminal outcomes
  # Suggested tier: balance

    @draft @allium-rule-success.OpenCycleTask
    Scenario: A submitted regeneration cycle opens exactly one workflow task
      Given an approved cycle preparation
      When the operator submits a regeneration cycle from it with a request identity
      Then a regeneration-cycle workflow task is opened, named by that request identity
      And the workflow task carries the request digest, the cycle preparation, and the parent cycle, if any
      And its status is submitted

  # operator-control-plane.allium, rule RepeatIdenticalSubmission (requires 1)
  # Goes under: Rule: Every public operation refuses malformed or conflicting identities
  # Suggested tier: balance

    @draft @allium-rule-failure.RepeatIdenticalSubmission.1
    Scenario: A repeated cycle submission cannot return a cut-preparation receipt
      Given a cut-preparation workflow task was opened for a request identity
      When the operator repeats a regeneration cycle submission naming that workflow task
      Then the repeat is refused and no task receipt is returned
      And the cut-preparation workflow task is unchanged

  # operator-control-plane.allium, rules StartWorkflowTask and CompleteWorkflowTask
  # Goes under: Rule: Workflow tasks have observable terminal outcomes
  # Suggested tier: balance

    @draft @allium-rule-success.StartWorkflowTask @allium-rule-success.CompleteWorkflowTask
    Scenario: ORC starts and completes a workflow task
      Given a regeneration cycle was submitted and its workflow task is submitted
      When ORC reports that the workflow task started under a trace
      And ORC reports that the workflow task completed with its outcome
      Then the cycle progress view reports running and then completed
      And waiting for the workflow task returns completed with that trace and outcome

  # operator-control-plane.allium, rule WaitForWorkflowTask (requires 1)
  # Goes under: Rule: Workflow tasks have observable terminal outcomes
  # Suggested tier: balance

    @draft @allium-rule-failure.WaitForWorkflowTask.1
    Scenario: Waiting returns no terminal outcome for a running workflow task
      Given ORC started a workflow task and has not completed or failed it
      When the operator waits for the workflow task
      Then no terminal outcome is returned for it
      And the workflow task is unchanged and still running

  # operator-control-plane.allium, rule ReadDurableMeasurement (requires 1)
  # Goes under: Rule: Workflow tasks have observable terminal outcomes
  # Suggested tier: balance

    @draft @allium-rule-failure.ReadDurableMeasurement.1
    Scenario: Reading a measurement no score filed is refused
      Given a regeneration cycle failed at the gate, so no score was filed for it
      When the operator reads the cycle's measurement
      Then the read is refused naming the cycle
      And no measurement is recorded for the cycle

  # operator-control-plane.allium, rule ProjectCycleProgress (requires 1)
  # Goes under: Rule: Any operator session can drive the outer lifecycle
  # Suggested tier: deferred

    @draft @allium-rule-failure.ProjectCycleProgress.1
    Scenario: A cut-preparation workflow task projects no cycle progress
      Given ORC started a cut-preparation workflow task under a trace
      When ORC reports an execution fact for that workflow task
      Then no cycle progress view is projected for it
      And querying cycle progress for its request identity is refused

  # operator-control-plane.allium, rule ProjectCycleProgress (requires 2)
  # Goes under: Rule: Any operator session can drive the outer lifecycle
  # Suggested tier: deferred

    @draft @allium-rule-failure.ProjectCycleProgress.2
    Scenario: A regeneration cycle ORC has not started projects no progress
      Given a regeneration cycle was submitted and ORC has not started its workflow task
      When an execution fact for that workflow task arrives before ORC reports its trace
      Then no cycle progress view is projected for it
      And the cycle progress view reports not-started

  # operator-control-plane.allium, rule ReadCycleProgress (requires 1)
  # Goes under: Rule: Any operator session can drive the outer lifecycle
  # Suggested tier: deferred

    @draft @allium-rule-failure.ReadCycleProgress.1
    Scenario: Querying the progress of an unknown cycle is refused
      Given no cycle progress view exists for a cycle identity
      When the operator queries cycle progress for that identity
      Then the query is refused naming the cycle identity
      And no cycle progress view is created

  # operator-control-plane.allium, rule ObserveBuilderSession and invariant
  # BuilderObservationDoesNotAssist
  # Goes under: Rule: Builder sessions are observable without weakening independence
  # Suggested tier: deferred

    @draft @allium-rule-success.ObserveBuilderSession @allium-invariant.BuilderObservationDoesNotAssist
    Scenario: Observing a builder session returns its view and never marks the candidate assisted
      Given ORC launched a builder session for a tick and the builder host committed tool results
      And no operator has intervened in it
      When the operator opens the builder session
      Then the read-only view names the builder session, its tick, and its latest workspace generation
      And the builder session is not marked assisted

  # operator-control-plane.allium, rule ContinueWithChildCycle
  # Goes under: Rule: Any operator session can drive the outer lifecycle
  # Suggested tier: deferred

    @draft @allium-rule-success.ContinueWithChildCycle
    Scenario: A child regeneration cycle records its lineage after a terminal measurement
      Given a regeneration cycle's workflow task failed
      And the operator approved a distinct child cycle preparation within the outer-cycle budget
      When the operator prepares the child cycle with a reason
      Then the child cycle's lineage names the parent cycle, the child cycle preparation, and the reason

  # operator-control-plane.allium, rule ContinueWithChildCycle (requires 1)
  # Goes under: Rule: Any operator session can drive the outer lifecycle
  # Suggested tier: deferred

    @draft @allium-rule-failure.ContinueWithChildCycle.1
    Scenario: A child cycle cannot continue a running parent cycle
      Given a regeneration cycle's workflow task is still running
      And the operator approved a child cycle preparation
      When the operator prepares the child cycle
      Then the cycle continuation decision is refused
      And no lineage is recorded and the parent workflow task is unchanged

  # operator-control-plane.allium, rule ContinueWithChildCycle (requires 3)
  # Goes under: Rule: Any operator session can drive the outer lifecycle
  # Suggested tier: deferred

    @draft @allium-rule-failure.ContinueWithChildCycle.3
    Scenario: A child cycle beyond the outer-cycle budget is refused
      Given a regeneration cycle prepared with an outer-cycle budget of one has completed
      And the operator approved a child cycle preparation
      When the operator prepares the child cycle
      Then the cycle continuation decision is refused as over the outer-cycle budget
      And no lineage is recorded and the parent workflow task is unchanged

  # operator-control-plane.allium, guarantee TaskFactsAreOrcs
  # Goes under: Rule: Workflow tasks have observable terminal outcomes
  # Suggested tier: balance

    @draft @allium-guarantee.TaskFactsAreOrcs
    Scenario: Only ORC reports that a workflow task started, completed, or failed
      Given a regeneration cycle was submitted and its workflow task is submitted
      When an operator reports that the workflow task started, completed, or failed
      Then the report is refused
      And the workflow task is unchanged until ORC reports its tick facts
