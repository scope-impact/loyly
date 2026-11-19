Feature: Cycle runner, draft scenarios
  The harness does not read this file. A draft becomes an item only after the product owner
  moves it into spec.feature with a stamped identity. The Pi grader host contract is now in
  spec.feature; this remaining draft concerns promotion policy outside the candidate.

  Rule: A successor cannot accept its own score

    @draft
    Scenario: The accepted runner builds its successor without letting the candidate judge itself
      Given the accepted cycle runner starts a fenced regeneration of slice 06
      And the candidate contains a changed grader workflow
      When the accepted runner gates and scores that candidate
      Then the accepted runner's gate and score remain the only ones used in that run
      And the candidate is not promoted until an independent Pi grader and a same-candidate instrument shadow agree with the filed evidence
