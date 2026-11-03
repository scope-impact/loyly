Feature: Initial generation from an implementation-free source cut
  Initial generation is a distinct ORC workflow: it starts from a fenced source cut,
  has no parent candidate, and produces an ungraded candidate without invoking graders.

  Rule: Initial generation does not require oracles

    @item-01-043 @skip
    Scenario: ORC routes a first approved slice to initial generation before oracles exist
      Given a prepared source cut with the implementation and prior measurements fenced out
      And an approved non-empty selection
      And no prior candidate lineage exists for its project component
      And no product oracle or instrument oracle is pinned
      When the operator submits the approved slice once
      Then ORC persists a generation route for that request
      And ORC starts the pi-harness-initial-generation workflow
      And its ordered stages are cut, builder, and record-candidate
      And the builder receives no parent candidate, grader, or host credentials
      And writes remain confined to the approved workspace scope

  Rule: Initial generation records only an explicitly ungraded candidate

    @item-01-044 @skip
    Scenario: A successful initial generation records candidate provenance without a verdict
      Given initial generation is running for an approved selection
      When the builder produces workspace output
      Then the candidate is bound to the source cut, selection, builder session, and subject digest
      And its operation is generation
      And its disposition is ungraded
      And no verifier, oracle, grader, or instrument-conformance stage runs

    @item-01-045 @skip
    Scenario: Initial generation fails when the builder produces no candidate
      Given initial generation is running for an approved selection
      When the builder produces no workspace output
      Then generation fails with the builder failure recorded
      And no candidate or passing measurement is recorded

  Rule: Initial generation cannot be mistaken for measured regeneration

    @item-01-046 @skip
    Scenario: An ungraded generation result cannot pass the regeneration grade gate
      Given an ungraded candidate was produced by initial generation
      When a caller requests a measured result without running the regeneration workflow
      Then the request is refused until ORC runs an oracle-backed regeneration cycle
      And that regeneration has its own operation identity and measurement evidence

  Rule: ORC selects measured regeneration when candidate lineage exists

    @item-01-047 @skip
    Scenario: An approved slice is routed from the latest persisted component candidate
      Given an approved selection for a project component
      And a latest candidate with a persisted output root and subject digest exists
      And the accepted product oracle and instrument oracle are pinned
      When the operator submits the approved slice once
      Then ORC persists a regeneration route with the latest candidate as parent
      And the builder receives that candidate root and verifies its digest before use
      And independent grading and instrument conformance run in the regeneration workflow
