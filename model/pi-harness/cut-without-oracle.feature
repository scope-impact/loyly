Feature: Regenerate from a cut without an oracle
  A first regeneration can start from an approved, fenced specification cut before a
  product oracle or instrument oracle exists. It may produce a candidate, but cannot
  claim independent measurement or pass the measured-cycle grade gate.

  Rule: A cut without an oracle can start a first regeneration

    @item-01-043 @skip
    Scenario: A first regeneration starts without product or instrument oracles
      Given a prepared fenced cut and an approved non-empty selection
      And the cut has no product oracle or instrument oracle
      When the operator starts a first regeneration
      Then a fresh isolated builder session receives the pinned specification and approved items
      And the builder receives no grader, prior candidate, or host credentials
      And writes remain confined to the approved workspace scope

  Rule: A first regeneration from an oracle-free cut produces an ungraded candidate

    @item-01-044 @skip
    Scenario: The first candidate is recorded without an independent verdict
      Given a first regeneration started from a cut without an oracle
      When the builder produces workspace output
      Then the candidate is bound to the cut, selection, builder session, and subject digest
      And the result is classified as an ungraded candidate
      And no grader or instrument-conformance stage runs
      And the regeneration is not reported as a passing measured cycle

    @item-01-045 @skip
    Scenario: A builder that produces no candidate fails the first regeneration
      Given a first regeneration started from a cut without an oracle
      When the builder produces no workspace output
      Then the first regeneration fails with the builder failure recorded
      And no candidate or passing measurement is recorded

  Rule: An oracle-free first regeneration cannot substitute for measured regeneration

    @item-01-046 @skip
    Scenario: An ungraded candidate cannot pass the independent grade gate
      Given a candidate was produced from a cut without an oracle
      When a caller requests a passing regeneration result
      Then the request is refused until accepted oracles are pinned
      And a later measured cycle records independent verification and grading separately
