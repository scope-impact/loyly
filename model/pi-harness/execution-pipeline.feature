Feature: Ordered regeneration execution and evidence
  ORC owns the complete execution pipeline while independent rooms and durable
  evidence preserve candidate identity, provenance, and explicit non-success outcomes.

  Rule: ORC owns the complete ordered execution pipeline

    @item-01-024 @skip
    Scenario: A regeneration cycle follows the declared stage order
      Given an approved cycle starts
      When ORC advances the cycle
      Then its stages are cut, harness calibration, builder, instrument, affordance conformance, verify, repair verification, collect evidence, compare oracle, instrument conformance, record cycle, and grade gate in that order
      And adapter completion cannot reorder or skip an ORC stage
      And before the builder starts ORAS resolves the pinned instrument oracle
      And every verdict-affecting harness component and the complete harness source digest match
      And normalized carried and uncarried Allure goldens pass
      And any calibration mismatch prevents the builder stage

    @item-01-024 @skip
    Scenario: ORC exposes the declared regeneration stage order
      Given the Pi harness ORC worker is running
      When Pi requests the executable regeneration workflow contract
      Then ORC exposes these stages in order:
        | cut                    |
        | harness-calibration    |
        | builder                |
        | instrument             |
        | affordance-conformance |
        | verify                 |
        | repair-verification    |
        | collect-evidence       |
        | compare-oracle         |
        | instrument-conformance |
        | record-cycle           |
        | grade-gate             |

    @item-01-024 @skip
    Scenario: A slice without a human affordance contract does not invent a UI
      Given the approved slice is not selected by the project's affordance contract
      When independent affordance conformance runs
      Then it records a passed non-applicable result with zero checks
      And verification continues without requiring screens or forms

    @item-01-025 @skip
    Scenario: Instrumentation records the subject without changing it
      Given the builder produced a candidate with a subject digest
      When the instrument stage runs
      Then it records the subject digest and evidence locations
      And the candidate content digest remains unchanged

    @item-01-026 @skip
    Scenario: The whole-system product oracle supplies approved expected evidence
      Given an approved selection names stable item identities and full Gherkin scenario digests
      When harness calibration resolves its product-oracle OCI before the builder starts
      Then the immutable descriptor contains every selected item exactly once
      And every selected entry matches the full scenario digest and contains manually approved normalized expected evidence
      And unselected catalogue entries do not enter the approved denominator
      And the product oracle need not match the source tree or name the instrument oracle
      And cycle evidence records the independently resolved product and instrument descriptors
      And any missing, duplicate, or scenario-digest-mismatched selected item prevents the builder from starting

  Rule: Independent rooms and evidence preserve provenance

    @item-01-028 @skip
    Scenario: Evidence identity cannot be reused for different content
      Given an observation exists for one cycle, item, kind, and producer
      When different evidence is recorded with that identity
      Then recording is refused as a conflict
      And the original observation remains authoritative

    @item-01-029 @skip
    Scenario: Evidence reports are derived projections
      Given a validated observation was recorded
      When an Allure or other report is emitted
      Then the report preserves the observation identity, verdict, reachability, and provenance
      And changing or losing the report cannot change the recorded measurement

  Rule: Non-success outcomes remain explicit and measurable

    @item-01-030 @skip
    Scenario Outline: A non-successful stage cannot disappear from the result
      Given the <stage> stage is refused, skipped, or times out
      When the cycle reaches a terminal outcome
      Then the cycle fails with the stage and reason recorded
      And every approved item remains in the measurement denominator

      Examples:
        | stage                  |
        | builder                |
        | instrument             |
        | verify                 |
        | evidence collection    |
        | oracle comparison      |
        | instrument conformance |
