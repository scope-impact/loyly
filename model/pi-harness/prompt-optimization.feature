Feature: Independently measured builder-instruction optimization
  ORC activates builder instructions only after independent regeneration measurements
  compare instruction candidates over valid training and validation examples.

  Rule: Prompt optimization is independently measured

    @item-01-021 @e2e
    Scenario: Prompt optimization requires training and validation examples
      Given either example set is missing or not a collection
      When the operator requests builder-prompt optimization
      Then optimization is refused before an instruction candidate is evaluated

    @item-01-022 @skip
    Scenario: Every instruction candidate is scored by independent regeneration gates
      Given valid training and validation examples
      When ORC evaluates an instruction candidate
      Then a fresh builder produces a candidate application
      And the oracle, verifier, and independent grader produce its metric
      And the builder cannot supply or alter that metric

    @item-01-023 @skip
    Scenario: Only the winning instruction becomes active
      Given prompt optimization evaluated one or more instruction candidates
      When optimization completes
      Then the result identifies the winning instruction and its independent score
      And subsequent cycles receive that instruction through ORC
      And adapter code does not construct or select the production instruction
