Feature: Ordered slice-cycle execution and evidence
  ORC owns one slice-cycle workflow shared by Fresh Slice Generation and Measured Slice
  Regeneration. The route ORC persisted for the approved slice selects the oracle, grade,
  repair-evaluation, and conclude behavior, while independent rooms and durable evidence
  preserve candidate identity, provenance, and explicit non-success outcomes.

  Rule: ORC owns the complete ordered execution pipeline

    @item-01-024 @skip
    Scenario: A slice cycle follows the declared stage order
      Given an approved cycle starts with its persisted fresh-generation or measured-regeneration route
      When ORC advances the cycle
      Then its stages are cut, harness snapshot, oracle, builder, instrument, verify, repair verification, affordance conformance, grade, instrument conformance, repair evaluation, and conclude in that order
      And repair evaluation runs only on the fresh-generation route
      And adapter completion cannot reorder or skip an ORC stage
      And before the oracle and builder stages ORC builds, calibrates, and seals a fresh harness snapshot from the committed harness revision
      And every verdict-affecting harness component, runtime asset, and the complete harness revision match its manifest
      And normalized carried and uncarried Allure goldens pass
      And a passing calibration is reused only for an identical harness snapshot digest
      And any calibration mismatch stops the cycle before the builder stage
      And missing, skipped, timed-out, or inconclusive evidence remains its own outcome

    @item-01-024 @skip
    Scenario: ORC exposes the declared slice-cycle stage order
      Given the Pi harness ORC worker is running
      When Pi requests the executable slice-cycle workflow contract
      Then ORC exposes these stages in order:
        | cut                    |
        | harness-snapshot       |
        | oracle                 |
        | builder                |
        | instrument             |
        | verify                 |
        | repair-verification    |
        | affordance-conformance |
        | grade                  |
        | instrument-conformance |
        | repair-evaluation      |
        | conclude               |

    @item-01-024 @skip
    Scenario: A slice without a human affordance contract does not invent a UI
      Given the approved slice is not selected by the project's affordance contract
      When independent affordance conformance runs
      Then it records a passed non-applicable result with zero checks
      And the cycle continues to grade without requiring screens or forms

    @item-01-025 @skip
    Scenario: Instrumentation records the subject without changing it
      Given the builder produced a candidate with a subject digest
      When the instrument stage runs
      Then it records the subject digest and evidence locations
      And the candidate content digest remains unchanged

    @item-01-026 @skip
    Scenario: The whole-system product oracle supplies approved expected evidence
      Given an approved selection names stable item identities and full Gherkin scenario digests
      When the measured-regeneration oracle stage resolves its product-oracle OCI before the builder starts
      Then the immutable descriptor contains every selected item exactly once
      And every selected entry matches the full scenario digest and contains manually approved normalized expected evidence
      And unselected catalogue entries do not enter the approved denominator
      And the product oracle need not match the source tree or name the harness snapshot
      And cycle evidence records the independently resolved product descriptor and sealed harness snapshot digest
      And any missing, duplicate, or scenario-digest-mismatched selected item prevents the builder from starting

  Rule: The persisted route selects the oracle, grade, repair-evaluation, and conclude steps

    @item-01-063 @skip
    Scenario: The fresh-generation oracle stage authors and seals a cycle oracle before the builder
      Given a fresh-generation cycle has sealed its harness snapshot
      And no pre-existing product oracle or published instrument oracle is available
      When the oracle stage runs
      Then a fresh oracle-author agent derives the cycle oracle from only the selected Gherkin and public calibration protocol
      And ORC calibrates it against carried and uncarried behavior and seals its binding digest
      And an uncalibrated cycle oracle is discarded and re-authored for at most three attempts
      And the builder starts only after the cycle oracle is sealed
      And three uncalibrated authoring attempts fail the cycle before any builder starts

    @item-01-064 @skip
    Scenario: The measured-regeneration oracle stage resolves the pinned accepted oracle
      Given a measured-regeneration cycle has sealed its harness snapshot
      And its route pins an accepted product oracle by immutable digest
      When the oracle stage runs
      Then ORC resolves that accepted product oracle and its calibration by digest before the builder starts
      And no oracle-author agent runs and no new oracle is authored
      And compared measured-regeneration cycles reuse the same accepted oracle digest
      And an unresolvable or digest-mismatched oracle stops the cycle before the builder stage

    @item-01-065 @skip
    Scenario: Only fresh generation may resample after a failed evaluation
      Given a verified candidate failed grade or instrument conformance
      When the cycle leaves the instrument-conformance stage
      Then a fresh-generation cycle enters repair evaluation and may start a clean sample from the approved cut
      And a fresh-generation cycle runs at most three evaluation rounds in total
      And a measured-regeneration cycle skips repair evaluation and reports its single measurement
      And no evaluation evidence enters any builder or verification-repair prompt

    @item-01-066 @skip
    Scenario: A fresh-generation cycle concludes by proposing its oracle
      Given a fresh-generation cycle graded the complete approved denominator with its sealed cycle oracle
      When the conclude stage runs
      Then ORC normalizes the grade evidence as an oracle proposal basis
      And ORC materializes the Allure evidence and records the oracle proposal
      And the proposal is published and the candidate promoted only after manual approval of the exact proposal digest

    @item-01-067 @skip
    Scenario: A measured-regeneration cycle concludes by recording its gated measurement
      Given a measured-regeneration cycle graded the complete approved denominator against the accepted oracle's expected evidence
      When the conclude stage runs
      Then ORC records the measured cycle with every approved item in the denominator
      And the grade gate passes only when every independent gate passes
      And no oracle proposal is recorded

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
      Given the <stage> stage is refused, skipped, times out, or returns inconclusive evidence
      When the cycle reaches a terminal outcome
      Then the cycle fails with the stage and reason recorded
      And every approved item remains in the measurement denominator

      Examples:
        | stage                  |
        | harness snapshot       |
        | oracle                 |
        | builder                |
        | instrument             |
        | verify                 |
        | affordance conformance |
        | grade                  |
        | instrument conformance |
