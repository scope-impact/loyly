Feature: Authoritative and complete runtime evidence
  Runtime signals preserve producer authority, collection completeness, candidate
  correlation, and projection provenance without allowing reports to author verdicts.

  Rule: Runtime evidence preserves authority and collection quality

    @skip @gondolin
    @item-05-001
    Scenario: A score preserves its Playwright trace
      Given the sealed grader drives the candidate only through its public seam
      When the score stage completes
      Then the cycle evidence contains a non-empty Playwright trace archive
      And the measurement binds the trace archive digest
      And a missing Playwright trace makes the score incomplete

    @skip
    @item-05-002
    Scenario: Candidate telemetry cannot author an independent verdict
      Given the candidate emits a runtime signal claiming an invariant passed
      But no independent validator evaluated the invariant
      When the cycle measurement is reduced
      Then the candidate signal is retained as evidence
      But it cannot produce a passing item observation

    @skip
    @item-05-003
    Scenario: Missing or dropped required signals are inconclusive
      Given the sealed grader carries the generic evaluator for the runtime signals declared by the approved project model
      And one required signal is missing, incomplete, sampled, or reported as dropped
      When the score stage evaluates the captured evidence for an item
      Then it persists an inconclusive invariant evaluation
      And the approved item cannot pass the score
      And collection status identifies the affected signal kind and reason
      And diagnostic profiles remain available but cannot author a verdict

    @skip
    @item-05-004
    Scenario: A passing invariant uses one correlated candidate subject
      Given an accepted domain event and every required projection snapshot
      And every signal names the same cycle, operation, event, aggregate, and subject digest
      When the independent validator evaluates the named product invariant
      Then it may record an evidence-backed passing observation
      And the observation identifies every input artifact digest

    @e2e
    @item-05-005
    Scenario: ORC exposes artifact provenance in its executable contract
      Given the Pi harness ORC worker is running
      When Pi requests the executable regeneration workflow contract
      Then the observation includes the field "artifacts"

    @skip
    @item-05-006
    Scenario: Runtime evidence is scored independently of behavior
      Given the independent execution emitted runtime evidence
      And the behavior evaluator returned a failure
      When the runtime-evidence evaluator scores the same items
      Then it records its own pass, fail, or inconclusive observation
      And the behavioral failure cannot skip or author the runtime-evidence verdict
      And an item passes only when both evaluators pass

    @skip
    @item-05-007
    Scenario: Runtime evidence is selected entirely from the pinned project model
      Given each selected item has a runtime evidence contract in the pinned project model
      When the runtime-evidence evaluator scores the selected items
      Then one generic evaluator reads their declared paths, record kinds, cardinalities, correlations, and invariants
      And adding a project or item does not require evaluator or ORC source changes
      And a selected item with no contract receives a failing observation

    @e2e
    @item-05-008
    Scenario: OTel and Allure project the persisted invariant evaluation
      Given an independent invariant evaluation and its evidence digest were persisted
      When an OTel result span and Allure report are emitted
      Then both identify the persisted evaluation and evidence digest
      And both name the same candidate subject and grader digests
      And neither projection can change the recorded verdict

    @skip
    @item-05-009
    Scenario: An operator Pi session profiles a Gondolin VM directly
      Given an isolated Pi observer session has YOLO tool access inside Gondolin
      When the observer profiles the VM while exercising the selected scenario
      Then its profile artifacts are recorded as operator-invoked evidence
      And the profile cannot become an authoritative grade without deterministic validation

    @skip @gondolin
    @item-05-010
    Scenario: Each comparison cycle isolates evidence from its pinned OTel capture policy
      Given the sealed harness snapshot supplies a declarative OTel capture policy
      When an independent Gondolin validator room opens for one regeneration cycle
      Then the candidate and OTel capture policy are mounted read-only
      And the candidate subject digest remains unchanged after observation
      And a fresh cycle evidence volume is the only writable grading mount
      And the validator image supplies OBI and the OTel eBPF profiler with tracefs and BPF FS mounted
      And native OTLP traces, metrics, and profiles are cycle-scoped diagnostic evidence
