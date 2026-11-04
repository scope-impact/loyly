Feature: The regeneration cycle
  ORC runs one cycle in the legacy shape: fence, regenerate, gate, score, scoreboard.
  The fence and the gate are the harness: they feed the build loop. The score and the
  scoreboard are the instrument: they report to a person, once a cycle, and never into
  the loop. Anything not written into the spec dies with the implementation.

  Rule: ORC owns the complete ordered cycle

    @item-01-024 @skip
    Scenario: A regeneration cycle follows the declared stage order
      Given an approved selection starts a cycle
      When ORC advances the cycle
      Then its stages are fence, regenerate, gate, score, and scoreboard in that order
      And adapter completion cannot reorder or skip an ORC stage
      And missing, skipped, timed-out, or inconclusive evidence remains its own outcome

    @item-01-024 @skip
    Scenario: ORC exposes the declared stage order
      Given the Pi harness ORC worker is running
      When Pi requests the executable cycle workflow contract
      Then ORC exposes these stages in order:
        | fence      |
        | regenerate |
        | gate       |
        | score      |
        | scoreboard |

  Rule: The fence and the gate feed the build loop; the score never does

    @item-01-068 @skip
    Scenario: The fence refuses to build a room for an instrument nobody checked
      Given the harness and the sealed instrument for the approved selection
      When the fence stage starts
      Then the harness self-check must pass before any clean room is built
      And a passing self-check is reused only for an identical harness digest
      And a failing self-check stops the cycle before regeneration with the failing check named

    @item-01-069 @skip
    Scenario: The gate ratifies done; the builder cannot
      Given the builder reports that the candidate is complete
      When the gate stage runs in an independent room
      Then the candidate is done only if the gate boots it fresh and proves its suite green
      And the gate records the subject digest without changing the candidate
      And a failed gate starts a fresh builder session with only the preceding candidate and the gate's evidence
      And the cycle fails at the gate after three regenerate attempts

    @item-01-070 @skip
    Scenario: The score reports to a person and never into the loop
      Given a candidate passed the gate
      When the score stage runs the sealed instrument against the checklist
      Then every checklist item receives a verdict
      And no score verdict, evidence, or failure reaches any builder session
      And a failed score never starts another regeneration within the cycle

    @item-01-024 @skip
    Scenario: A slice without a human affordance contract does not invent a UI
      Given the approved slice is not selected by the project's affordance contract
      When the score stage runs the affordance evaluator
      Then it records a passed non-applicable result with zero checks
      And the score continues without requiring screens or forms

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
        | stage      |
        | fence      |
        | regenerate |
        | gate       |
        | score      |
