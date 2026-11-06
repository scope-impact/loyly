Feature: The regeneration cycle
  ORC runs one cycle in the legacy shape: fence, regenerate, gate, score, scoreboard.
  The fence and the gate are the harness: they feed the build loop. The score and the
  scoreboard are the instrument: they report to a person, once a cycle, and never into
  the loop. Anything not written into the spec dies with the implementation.

  Rule: ORC owns the complete ordered cycle

    @skip
    @item-02-001
    Scenario: A regeneration cycle follows the declared stage order
      Given an approved selection starts a cycle
      When ORC advances the cycle
      Then its stages are fence, regenerate, gate, score, and scoreboard in that order
      And adapter completion cannot reorder or skip an ORC stage
      And missing, skipped, timed-out, or inconclusive evidence remains its own outcome

    @skip
    @item-02-002
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

    @skip
    @item-02-003
    Scenario: The fence refuses to build a room for an instrument nobody checked
      Given the harness and the sealed instrument for the approved selection
      When the fence stage starts
      Then the harness self-check must pass before any clean room is built
      And a passing self-check is reused only for an identical harness digest
      And a failing self-check stops the cycle before regeneration with the failing check named

    @skip
    @item-02-004
    Scenario: The gate ratifies done; the builder cannot
      Given the builder reports that the candidate is complete
      When the gate stage runs in an independent room
      Then the candidate is done only if the gate boots it fresh and proves its suite green
      And the gate records the subject digest without changing the candidate
      And a failed gate starts a fresh builder session with only the preceding candidate and the gate's evidence
      And the cycle fails at the gate after three regenerate attempts

    @skip
    @item-02-005
    Scenario: The score reports to a person and never into the loop
      Given a candidate passed the gate
      When the score stage runs the sealed instrument against the checklist
      Then every checklist item receives a verdict
      And no score verdict, evidence, or failure reaches any builder session
      And a failed score never starts another regeneration within the cycle

    @skip
    @item-02-006
    Scenario: A slice without a human affordance contract does not invent a UI
      Given the approved slice is not selected by the project's affordance contract
      When the score stage runs the affordance evaluator
      Then it records a passed non-applicable result with zero checks
      And the score continues without requiring screens or forms

  Rule: Independent rooms and evidence preserve provenance

    @skip
    @item-02-007
    Scenario: Evidence identity cannot be reused for different content
      Given an observation exists for one cycle, item, kind, and producer
      When different evidence is recorded with that identity
      Then recording is refused as a conflict
      And the original observation remains authoritative

    @skip
    @item-02-008
    Scenario: Evidence reports are derived projections
      Given a validated observation was recorded
      When an Allure or other report is emitted
      Then the report preserves the observation identity, verdict, reachability, and provenance
      And changing or losing the report cannot change the recorded measurement

  Rule: Non-success outcomes remain explicit and measurable

    @skip
    @item-02-009
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

  Rule: The running harness proves its identity by domain behavior, not by name

    @skip
    @item-02-010
    Scenario: The running harness proves its identity by answering its cycle contract
      Given the harness is started with start.sh speaking JSON-RPC over stdio
      When a client sends the cycle-contract request
      Then the response names the harness "pi-harness"
      And it lists the cycle stages fence, regenerate, gate, score, and scoreboard in that order

    @skip
    @item-02-011
    Scenario: The gate boots the harness within a thirty-second budget and reports the margin
      Given the candidate passed its whole suite
      When the gate launches start.sh
      Then the identity probe must be answered within thirty seconds of the launch
      And the gate reports how much of the budget was unused
      And slow work that does not fit belongs in test.sh, which the gate does not time

  Rule: The first regenerate attempt builds one slice at a time

    @skip
    @item-02-012
    Scenario: The first attempt starts one fresh builder per selected slice in plan order
      Given an approved selection of several plan slices
      When the regenerate stage runs its first attempt
      Then it starts one fresh builder session for each selected slice, in plan order
      And each builder is told which slice it carries and reads the whole spec in the clean room
      And the gate runs once, on the candidate the last slice builder left

    @skip
    @item-02-013
    Scenario: A slice builder receives only the clean room and this cycle's candidate so far
      Given an earlier slice builder of the same cycle wrote the candidate
      When the next slice builder starts
      Then it reads the clean room and the candidate as the earlier builder left it
      And it receives no earlier builder's transcript, no candidate from an earlier cycle, and no gate or score evidence

    @skip
    @item-02-014
    Scenario: A slice builder continues while it lists its own slice's items uncarried
      Given a slice builder's turn ends while its UNCARRIED.md still names items of its slice
      When ORC continues that same builder session
      Then the continuation names only the slice items still listed uncarried and asks the builder to carry them
      And ORC continues at most three times, stopping early when a continuation leaves the candidate unchanged
      And the builder's listing is never a verdict: the gate and the score still decide
