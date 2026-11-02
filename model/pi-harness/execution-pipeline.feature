Feature: Ordered regeneration execution and evidence
  ORC owns the complete execution pipeline while independent rooms and durable
  evidence preserve candidate identity, provenance, and explicit non-success outcomes.

  Rule: ORC owns the complete ordered execution pipeline

    @item-01-024 @skip
    Scenario: A regeneration cycle follows the declared stage order
      Given an approved cycle starts
      When ORC advances the cycle
      Then its stages are cut, harness calibration, builder, instrument, oracle, verify, repair verification, graders, instrument conformance, record cycle, and grade gate in that order
      And adapter completion cannot reorder or skip an ORC stage
      And before the builder starts ORAS resolves the pinned instrument oracle
      And every verdict-affecting harness component and the complete harness source digest match
      And normalized carried and uncarried Allure goldens pass
      And any calibration mismatch prevents the builder stage

    @item-01-024 @e2e
    Scenario: ORC exposes the declared regeneration stage order
      Given the Pi harness ORC worker is running
      When Pi requests the executable regeneration workflow contract
      Then ORC exposes these stages in order:
        | cut                    |
        | harness-calibration    |
        | builder                |
        | instrument             |
        | oracle                 |
        | verify                 |
        | repair-verification    |
        | graders                |
        | instrument-conformance |
        | record-cycle           |
        | grade-gate             |

    @item-01-025 @skip
    Scenario: Instrumentation records the subject without changing it
      Given the builder produced a candidate with a subject digest
      When the instrument stage runs
      Then it records the subject digest and evidence locations
      And the candidate content digest remains unchanged

    @item-01-026 @skip
    Scenario: The oracle binds the calibrated instrument and complete pinned model contract
      Given a selection identifies a project and approved items in a pinned cut
      When the oracle stage runs
      Then it resolves every model artifact and artifact digest for that project
      And every selected item resolves exactly once
      And missing, duplicate, or unstamped scenarios fail the oracle
      And its digest combines the instrument-oracle digest with the resolved product-contract digest

  Rule: Independent rooms and evidence preserve provenance

    @item-01-027 @skip
    Scenario: Independent observers cannot modify the candidate
      Given instrumentation recorded the candidate subject digest
      When the verifier and graders inspect the candidate in their execution rooms
      Then the candidate is mounted read-only
      And judge material and host credentials are unavailable to the builder
      And every resulting observation names the same subject and oracle digests

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
        | oracle                 |
        | verify                 |
        | graders                |
        | instrument conformance |
