Feature: The harness's public seam is JSON-RPC over stdio
  The harness is driven the same way by Pi, an operator, the gate, and a grader: one JSON
  object per line on stdin, one response per line on stdout, as declared in .pi/model/affordances.yaml.

  Rule: Every request gets exactly one response carrying its id

    @item-09-001 @balance
    Scenario: A request is answered once with its own id
      Given the harness is started with start.sh
      When a client sends a request with id 7
      Then exactly one response line carries id 7
      And it is either ok with a result or not ok with an error

    @item-09-002 @balance
    Scenario: An unknown method is refused without stopping the harness
      Given the harness is started with start.sh
      When a client sends a method the seam does not declare
      Then the response is not ok and names the method
      And a following cycle-contract request is still answered

  Rule: Cycles are submitted and observed through the seam

    @item-09-003 @balance @allium-rule-success.RepeatIdenticalSubmission @allium-invariant.OneRequestNamesOneTask
    Scenario: Repeating a cycle request returns the original receipt
      Given a cycle was submitted with a request id
      When the same request id is submitted again
      Then the same receipt is returned and no second cycle starts

    @item-09-004 @balance @allium-rule-success.WaitForWorkflowTask @allium-rule-success.FailWorkflowTask
    Scenario: Waiting on a submitted cycle returns its one terminal outcome
      Given a cycle was submitted
      When a client waits on its task
      Then the response reports completed or failed exactly once
      And a failed outcome names the stage and reason
