Feature: Initial generation captures and promotes its first independent grade
  Initial generation starts from a fenced source cut with no parent candidate and no
  accepted product oracle. After independent verification, it black-box grades every
  approved item through the public seam and proposes the normalized successful evidence
  as the first product oracle. Publication and candidate promotion require an operator's
  approval of that exact proposal digest.

  Rule: Initial generation does not require an existing product oracle

    @item-01-043 @skip
    Scenario: ORC runs explicitly requested generation without consulting candidate history
      Given a prepared source cut with the implementation and prior measurements fenced out
      And an approved non-empty selection
      And a prior candidate may exist for its project component
      And no product oracle is accepted for the approved scenarios
      And the pinned instrument oracle is calibrated
      When the operator explicitly submits Generate for the approved slice
      Then ORC persists a generation route for that request
      And ORC starts the pi-harness-initial-generation workflow
      And its automated stages are cut, harness calibration, builder, instrument, verify, repair verification, baseline grade, instrument conformance, and propose oracle
      And the builder receives no parent candidate, grader evidence, or host credentials
      And writes remain confined to the approved workspace scope

  Rule: Initial generation becomes measured only through approved oracle promotion

    @item-01-044 @skip
    Scenario: A successful first grade proposes evidence for oracle promotion
      Given initial generation is running for an approved selection
      When the builder produces workspace output and independent verification passes
      And an independent black-box judge grades every approved item through the public seam
      And every item passes with complete evidence
      And instrument conformance passes for the same candidate subject
      Then ORC normalizes the evidence without timestamps, random identities, or transport noise
      And ORC records a proposed oracle bound to the selection and scenario digests
      And the proposal records the candidate subject, complete denominator, evidence digest, and judge identity
      And generation waits for manual approval without publishing an oracle or recording a promotable candidate

    @item-01-052 @skip
    Scenario: Manual approval promotes exactly the reviewed oracle evidence
      Given a successful first grade produced a proposed oracle with a stable digest
      When the operator approves that exact proposal digest
      Then the independent publisher publishes the normalized evidence as an immutable product-oracle OCI artifact
      And Grain records the proposal digest, descriptor digest, selection, scenarios, candidate subject, and approving operator
      And the generated candidate is recorded with disposition baseline-measured
      And later regeneration uses that descriptor as its comparison oracle
      But approval with a stale or different digest is refused

    @item-01-053 @skip
    Scenario: Failed or incomplete first grading cannot produce an oracle
      Given initial generation has passed independent verification
      When any approved item fails, is missing, is inconclusive, or lacks evidence
      Then no oracle proposal is approvable
      And no product oracle is published
      And no baseline-measured candidate is recorded
      And the failed denominator and evidence remain visible for diagnosis

    @item-01-048 @skip
    Scenario: Initial generation keeps repairing until verification passes
      Given initial generation has produced a candidate that fails independent verification
      When verification returns its evidence to ORC
      Then ORC starts a fresh Gondolin builder session from that candidate
      And the repair builder receives only verifier evidence
      And ORC verifies the repaired candidate again
      And no baseline grader or oracle proposal runs while verification still fails

    @item-01-045 @skip
    Scenario: Initial generation fails when the builder produces no candidate
      Given initial generation is running for an approved selection
      When the builder produces no workspace output
      Then generation fails with the builder failure recorded
      And no candidate, oracle proposal, or passing measurement is recorded

  Rule: Captured evidence is not an oracle until manually approved

    @item-01-046 @skip
    Scenario: Unapproved generation evidence cannot become an oracle or measured candidate
      Given a successful first grade produced a proposed oracle
      When a caller requests its use before the operator approves the exact proposal digest
      Then the request is refused
      And the evidence remains a proposal rather than an accepted product oracle
      And the generation candidate cannot be routed as a measured parent

  Rule: Regeneration compares against the promoted oracle

    @item-01-047 @skip
    Scenario: An explicitly requested regeneration uses the named persisted component candidate
      Given an approved selection for a project component
      And a named baseline-measured candidate with a persisted output root and subject digest exists
      And the accepted promoted product oracle and instrument oracle are pinned
      When the operator explicitly submits Regenerate with that candidate as parent
      Then ORC persists a regeneration route with the named candidate as parent
      And the builder receives that candidate root and verifies its digest before use
      And independent evidence collection compares the regenerated candidate with the promoted oracle
      And instrument conformance runs in the regeneration workflow
