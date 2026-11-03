Feature: Session-independent generation and regeneration control
  Public operations validate identities, expose durable task outcomes, bind reviewed
  selections, and allow authorized operator sessions to observe builder work.

  Rule: Every public operation refuses malformed or conflicting identities

    @item-01-015 @skip
    Scenario: Cut preparation refuses an invalid or colliding identity
      Given a cut request does not name a full source commit or supported backend
      When the operator prepares the source cut
      Then the request is refused before a cut task starts
      And an existing cut identity is never replaced by different content

    @item-01-016 @e2e
    Scenario: Selection preview refuses an empty or ambiguous denominator
      Given the requested slice has missing, duplicate, unstamped, or unknown items
      When the operator previews the selection
      Then no selection contract is recorded
      And the refusal identifies the unresolved denominator

    @item-01-017 @skip
    Scenario: Submission refuses an unsafe candidate scope
      Given an approved selection
      When submission names an empty, absolute, escaping, or overlapping candidate scope
      Then submission is refused before a builder room opens

  Rule: Workflow tasks have observable terminal outcomes

    @item-01-018 @e2e
    Scenario: Waiting for a known task returns its one terminal outcome
      Given a source-cut, generation, or regeneration task receipt was returned
      When the operator waits for its task identity
      Then the result is completed or failed with the ORC trace summary
      And waiting again returns the same terminal outcome

    @item-01-019 @e2e
    Scenario: Waiting refuses an unknown task identity
      Given no task receipt has the supplied identity
      When the operator waits for that identity
      Then the request is refused without starting new work

    @item-01-020 @skip
    Scenario: A durable task remains observable after worker restart
      Given an ORC task receipt was returned before the worker stopped
      When a replacement worker waits for the task identity
      Then it reconstructs the task outcome from durable ORC state
      And it does not rerun the task to manufacture an answer

  Rule: The reviewed preparation binds every cycle-defining choice

    @item-01-035 @skip
    Scenario: Approval binds the complete cycle preparation and diagram
      Given an approved slice selection
      When an operator session prepares a cycle with scope, target stack, execution profile, pinned inputs, and outer-cycle budget
      Then the harness returns a deterministic preparation diagram and digest
      And approval accepts only that exact preparation digest
      And changing any bound field requires a new preparation

  Rule: Any operator session can drive the outer lifecycle

    @item-01-036 @skip
    Scenario: The control plane does not depend on one interactive session implementation
      Given a Codex, Pi, or other authorized operator session
      When it supplies an approved preparation through the public control plane
      Then ORC starts the same regeneration cycle contract
      And disconnecting that session does not stop the internal ORC cycle

    @item-01-037 @skip
    Scenario: Live progress reconciles with durable ORC execution state
      Given a cycle is running and ORC emits stage notifications
      When the observing extension connects or reconnects
      Then it displays the current stage and associated builder-session reference
      And it queries ORC execution state to recover from missing notifications
      And the progress view never becomes workflow authority

  Rule: Builder sessions are observable without weakening independence

    @item-01-038 @e2e
    Scenario: An operator observes a builder session read-only
      Given ORC opened a separately identified builder session for one build attempt
      When an operator session opens that builder-session reference
      Then it can inspect committed model turns, tool calls, workspace generation, and progress
      But observation cannot change the builder transcript or candidate
      And the builder cannot read the operator transcript or independent grader evidence

    @item-01-039 @skip
    Scenario: Intervention is explicit and provenance-recorded
      Given a builder session is active or completed
      When an operator pauses, steers, or forks its work
      Then the action occurs only at a safe boundary or in a distinct fork
      And the resulting candidate is marked as assisted
      And a completed builder session is never silently mutated

    @item-01-040 @skip
    Scenario: A builder transcript is restored with its matching workspace generation
      Given a builder session committed model turns and VM tool results
      When its host or execution room restarts
      Then the session resumes from the workspace generation bound to those committed results
      And an uncommitted VM mutation is discarded or replayed from the previous generation
      And transcript state is never restored against a mismatched workspace
