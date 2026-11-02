Feature: Complete control, optimization, execution, and evidence behavior
  The Pi-hosted ORC harness exposes an observable control plane, independently
  optimizes builder instructions, executes every declared stage in order, and
  preserves evidence and non-success outcomes without weakening the denominator.

  Rule: Every public operation refuses malformed or conflicting identities

    @item-01-015
    Scenario: Cut preparation refuses an invalid or colliding identity
      Given a cut request does not name a full source commit or supported backend
      When the operator prepares the regeneration cut
      Then the request is refused before a cut task starts
      And an existing cut identity is never replaced by different content

    @item-01-016
    Scenario: Selection preview refuses an empty or ambiguous denominator
      Given the requested slice has missing, duplicate, unstamped, or unknown items
      When the operator previews the selection
      Then no selection contract is recorded
      And the refusal identifies the unresolved denominator

    @item-01-017
    Scenario: Submission refuses an unsafe candidate scope
      Given an approved selection
      When submission names an empty, absolute, escaping, or overlapping candidate scope
      Then submission is refused before a builder room opens

  Rule: Workflow tasks have observable terminal outcomes

    @item-01-018
    Scenario: Waiting for a known task returns its one terminal outcome
      Given a cut or regeneration task receipt was returned
      When the operator waits for its task identity
      Then the result is completed or failed with the ORC trace summary
      And waiting again returns the same terminal outcome

    @item-01-019
    Scenario: Waiting refuses an unknown task identity
      Given no task receipt has the supplied identity
      When the operator waits for that identity
      Then the request is refused without starting new work

    @item-01-020
    Scenario: A durable task remains observable after worker restart
      Given an ORC task receipt was returned before the worker stopped
      When a replacement worker waits for the task identity
      Then it reconstructs the task outcome from durable ORC state
      And it does not rerun the task to manufacture an answer

  Rule: Prompt optimization is independently measured

    @item-01-021
    Scenario: Prompt optimization requires training and validation examples
      Given either example set is missing or not a collection
      When the operator requests builder-prompt optimization
      Then optimization is refused before an instruction candidate is evaluated

    @item-01-022
    Scenario: Every instruction candidate is scored by independent regeneration gates
      Given valid training and validation examples
      When ORC evaluates an instruction candidate
      Then a fresh builder produces a candidate application
      And the oracle, verifier, and independent grader produce its metric
      And the builder cannot supply or alter that metric

    @item-01-023
    Scenario: Only the winning instruction becomes active
      Given prompt optimization evaluated one or more instruction candidates
      When optimization completes
      Then the result identifies the winning instruction and its independent score
      And subsequent cycles receive that instruction through ORC
      And adapter code does not construct or select the production instruction

  Rule: ORC owns the complete ordered execution pipeline

    @item-01-024
    Scenario: A regeneration cycle follows the declared stage order
      Given an approved cycle starts
      When ORC advances the cycle
      Then its stages are cut, harness calibration, builder, instrument, oracle, verify, graders, instrument conformance, record cycle, and result in that order
      And adapter completion cannot reorder or skip an ORC stage
      And before the builder starts ORAS resolves the pinned instrument oracle
      And every verdict-affecting harness component and the complete harness source digest match
      And normalized carried and uncarried Allure goldens pass
      And any calibration mismatch prevents the builder stage

    @item-01-025
    Scenario: Instrumentation records the subject without changing it
      Given the builder produced a candidate with a subject digest
      When the instrument stage runs
      Then it records the subject digest and evidence locations
      And the candidate content digest remains unchanged

    @item-01-026
    Scenario: The oracle binds the calibrated instrument and complete pinned model contract
      Given a selection identifies a project and approved items in a pinned cut
      When the oracle stage runs
      Then it resolves every model artifact and artifact digest for that project
      And every selected item resolves exactly once
      And missing, duplicate, or unstamped scenarios fail the oracle
      And its digest combines the instrument-oracle digest with the resolved product-contract digest

  Rule: Independent rooms and evidence preserve provenance

    @item-01-027
    Scenario: Independent observers cannot modify the candidate
      Given instrumentation recorded the candidate subject digest
      When the verifier and graders inspect the candidate in their execution rooms
      Then the candidate is mounted read-only
      And judge material and host credentials are unavailable to the builder
      And every resulting observation names the same subject and oracle digests

    @item-01-028
    Scenario: Evidence identity cannot be reused for different content
      Given an observation exists for one cycle, item, kind, and producer
      When different evidence is recorded with that identity
      Then recording is refused as a conflict
      And the original observation remains authoritative

    @item-01-029
    Scenario: Evidence reports are derived projections
      Given a validated observation was recorded
      When an Allure or other report is emitted
      Then the report preserves the observation identity, verdict, reachability, and provenance
      And changing or losing the report cannot change the recorded measurement

  Rule: Non-success outcomes remain explicit and measurable

    @item-01-030
    Scenario Outline: A non-successful stage cannot disappear from the result
      Given the <stage> stage is refused, skipped, or times out
      When the cycle reaches a terminal outcome
      Then the cycle fails with the stage and reason recorded
      And every approved item remains in the measurement denominator

      Examples:
        | stage      |
        | builder    |
        | instrument |
        | oracle     |
        | verify     |
        | graders    |
        | instrument conformance |

  Rule: Runtime evidence preserves authority and collection quality

    @item-01-031
    Scenario: Candidate telemetry cannot author an independent verdict
      Given the candidate emits a runtime signal claiming an invariant passed
      But no independent grader evaluated the invariant
      When the cycle measurement is reduced
      Then the candidate signal is retained as evidence
      But it cannot produce a passing item observation

    @item-01-032
    Scenario: Missing or dropped required signals are inconclusive
      Given a product measurement binding requires an event and projection snapshots
      And one required signal is missing, sampled, or reported as dropped
      When the independent grader evaluates the invariant
      Then the invariant verdict is inconclusive
      And the approved item cannot pass

    @item-01-033
    Scenario: A passing invariant uses one correlated candidate subject
      Given an accepted domain event and every required projection snapshot
      And every signal names the same cycle, operation, event, aggregate, and subject digest
      When the independent grader evaluates the named product invariant
      Then it may record an evidence-backed passing observation
      And the observation identifies every input artifact digest

    Scenario: Instrument conformance is independent of the behavioral grade
      Given the independent execution emitted runtime evidence
      And the black-box behavioral grader returned a failure
      When the instrument conformance stage evaluates the runtime evidence
      Then it records its own pass, fail, or inconclusive observation
      And the behavioral failure cannot skip or author the instrument verdict
      And the final cycle passes only when both independent results pass

    Scenario: Instrument conformance is selected entirely from the pinned project model
      Given each selected item has a runtime evidence contract in the pinned project model
      When the instrument conformance stage evaluates the selected items
      Then one generic evaluator reads their declared paths, record kinds, cardinalities, correlations, and invariants
      And adding a project or item does not require evaluator or ORC source changes
      And a selected item with no contract receives a failing observation

    @item-01-034
    Scenario: OTel and Allure project the persisted invariant evaluation
      Given an independent invariant evaluation and its evidence digest were persisted
      When an OTel result span and Allure report are emitted
      Then both identify the persisted evaluation and evidence digest
      And neither projection can change the recorded verdict
