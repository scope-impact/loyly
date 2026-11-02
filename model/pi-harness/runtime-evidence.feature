Feature: Authoritative and complete runtime evidence
  Runtime signals preserve producer authority, collection completeness, candidate
  correlation, and projection provenance without allowing reports to author verdicts.

  Rule: Runtime evidence preserves authority and collection quality

    @item-01-031 @skip
    Scenario: Candidate telemetry cannot author an independent verdict
      Given the candidate emits a runtime signal claiming an invariant passed
      But no independent grader evaluated the invariant
      When the cycle measurement is reduced
      Then the candidate signal is retained as evidence
      But it cannot produce a passing item observation

    @item-01-032 @skip
    Scenario: Missing or dropped required signals are inconclusive
      Given a product measurement binding requires an event and projection snapshots
      And one required signal is missing, sampled, or reported as dropped
      When the independent grader evaluates the invariant
      Then the invariant verdict is inconclusive
      And the approved item cannot pass

    @item-01-033 @skip
    Scenario: A passing invariant uses one correlated candidate subject
      Given an accepted domain event and every required projection snapshot
      And every signal names the same cycle, operation, event, aggregate, and subject digest
      When the independent grader evaluates the named product invariant
      Then it may record an evidence-backed passing observation
      And the observation identifies every input artifact digest

    @item-01-033 @e2e
    Scenario: ORC exposes artifact provenance in its executable contract
      Given the Pi harness ORC worker is running
      When Pi requests the executable regeneration workflow contract
      Then the observation includes the field "artifactDigests"

    @skip
    Scenario: Instrument conformance is independent of the behavioral grade
      Given the independent execution emitted runtime evidence
      And the black-box behavioral grader returned a failure
      When the instrument conformance stage evaluates the runtime evidence
      Then it records its own pass, fail, or inconclusive observation
      And the behavioral failure cannot skip or author the instrument verdict
      And the final cycle passes only when both independent results pass

    @skip
    Scenario: Instrument conformance is selected entirely from the pinned project model
      Given each selected item has a runtime evidence contract in the pinned project model
      When the instrument conformance stage evaluates the selected items
      Then one generic evaluator reads their declared paths, record kinds, cardinalities, correlations, and invariants
      And adding a project or item does not require evaluator or ORC source changes
      And a selected item with no contract receives a failing observation

    @item-01-034 @skip
    Scenario: OTel and Allure project the persisted invariant evaluation
      Given an independent invariant evaluation and its evidence digest were persisted
      When an OTel result span and Allure report are emitted
      Then both identify the persisted evaluation and evidence digest
      And neither projection can change the recorded verdict
