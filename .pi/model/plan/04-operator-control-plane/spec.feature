Feature: Session-independent generation and regeneration control
  Public operations validate identities, expose durable task outcomes, bind reviewed
  selections, and allow authorized operator sessions to observe builder work.

  Rule: The Pi harness is a strictly validated Grain service area inside ORC

    @skip
    @item-04-001
    Scenario: Startup refuses a harness topology that differs from its Grain Event Model
      Given ORC and the Pi harness have loaded their registered Grain service areas
      When the shared Grain runtime starts the Pi harness
      Then strict validation reconciles every harness command, event, read model, query, processor, schema, and edge
      And missing, extra, or mismatched runtime blocks prevent startup
      And CI also resolves every required Grain-to-Allium trace link

    @skip
    @item-04-002
    Scenario: Public harness operations use Grain commands and queries
      Given the Pi harness is running inside ORC's tenant-scoped Grain runtime
      When an operator changes or reads harness state
      Then every state change passes through a registered Grain command and event
      And every public read passes through a registered Grain query over a read model
      And no public operation appends a bare event or reads the event store directly

    @skip
    @item-04-003
    Scenario: Harness progress is projected from authoritative ORC events
      Given an approved cycle is executing through ORC
      When ORC records tree and node execution events
      Then the harness progress read model projects the current stage and builder-session reference
      And reconnecting clients read that projection through a Grain query
      And the harness emits no duplicate progress event

  Rule: Every public operation refuses malformed or conflicting identities

    @skip
    @item-04-004
    Scenario: An LLM proposes a reviewable slice but cannot approve it
      Given an operator describes the behavior to regenerate against a prepared cut
      When an exploratory Pi, Codex, or other operator session inspects the cut composition and asks the LLM to propose a slice
      Then every proposed slice and item identity is resolved from that cut
      And invented, missing, duplicate, or ambiguous identities are refused
      And the resolved selection is recorded as awaiting approval
      And a durable session may retain its transcript, skills, tool calls, and branches
      But that transcript is not part of the approved execution contract
      And no generation or regeneration starts until the operator approves its exact digest

    @skip
    @item-04-005
    Scenario: Cut preparation refuses an invalid or colliding identity
      Given a cut request does not name a full source commit or supported backend
      When the operator prepares the source cut
      Then the request is refused before a cut task starts
      And an existing cut identity is never replaced by different content

    @e2e
    @item-04-006
    Scenario: Selection preview refuses an empty or ambiguous denominator
      Given the requested slice has missing, duplicate, unstamped, or unknown items
      When the operator previews the selection
      Then no selection contract is recorded
      And the refusal identifies the unresolved denominator

    @skip
    @item-04-007
    Scenario: Submission refuses an unsafe candidate scope
      Given an approved selection
      When submission names an empty, absolute, escaping, or overlapping candidate scope
      Then submission is refused before a builder room opens

  Rule: Workflow tasks have observable terminal outcomes

    @e2e
    @item-04-008
    Scenario: Waiting for a known task returns its one terminal outcome
      Given a source-cut, generation, or regeneration task receipt was returned
      When the operator waits for its task identity
      Then the result is completed or failed with the ORC trace summary
      And waiting again returns the same terminal outcome

    @e2e
    @item-04-009
    Scenario: Waiting refuses an unknown task identity
      Given no task receipt has the supplied identity
      When the operator waits for that identity
      Then the request is refused without starting new work

    @skip
    @item-04-010
    Scenario: A durable task remains observable after worker restart
      Given an ORC task receipt was returned before the worker stopped
      When a replacement worker waits for the task identity
      Then it reconstructs the task outcome from durable ORC state
      And it does not rerun the task to manufacture an answer

  Rule: The reviewed preparation binds every cycle-defining choice

    @skip
    @item-04-011
    Scenario: Approval binds the complete cycle preparation and diagram
      Given an approved slice selection
      When an operator session prepares a cycle with scope, target stack, execution profile, pinned inputs, and outer-cycle budget
      Then the harness returns a deterministic preparation diagram and digest
      And approval accepts only that exact preparation digest
      And changing any bound field requires a new preparation

    @skip
    @item-04-012
    Scenario: A sealed grader is used only after manual approval of its exact digest
      Given a calibrated grader was sealed for a selection's scenario digests
      When an operator manually approves its exact grader digest
      Then later cycles score with that grader
      And a stale or different digest is refused
      And a cycle refuses to score with a sealed grader nobody approved

  Rule: Any operator session can drive the outer lifecycle

    @skip
    @item-04-013
    Scenario: The control plane does not depend on one interactive session implementation
      Given a Codex, Pi, or other authorized operator session
      When it supplies an immutable approved fixed execution contract through the public control plane
      Then ORC starts the same regeneration cycle contract
      And ORC owns its bounded prompt, tool surface, retries, termination, and result
      And disconnecting that session does not stop the internal ORC cycle

    @skip
    @item-04-014
    Scenario: Live progress reconciles with durable ORC execution state
      Given a cycle is running and ORC emits stage notifications
      When the observing extension connects or reconnects
      Then it displays the current stage and associated builder-session reference
      And it queries ORC execution state to recover from missing notifications
      And the progress view never becomes workflow authority

  Rule: Builder sessions are observable without weakening independence

    @e2e
    @item-04-015
    Scenario: An operator observes a builder session read-only
      Given ORC opened a separately identified builder session for one build attempt
      When an operator session opens that builder-session reference
      Then it can inspect committed model turns, tool calls, workspace generation, and progress
      But observation cannot change the builder transcript or candidate
      And the builder cannot read the operator transcript or independent grader evidence

    @skip
    @item-04-016
    Scenario: Intervention is explicit and provenance-recorded
      Given a builder session is active or completed
      When an operator pauses, steers, or forks its work
      Then the action occurs only at a safe boundary or in a distinct fork
      And the resulting candidate is marked as assisted
      And a completed builder session is never silently mutated

    @skip
    @item-04-017
    Scenario: A builder transcript is restored with its matching workspace generation
      Given a builder session committed model turns and VM tool results
      When its host or execution room restarts
      Then the session resumes from the workspace generation bound to those committed results
      And an uncommitted VM mutation is discarded or replayed from the previous generation
      And transcript state is never restored against a mismatched workspace
