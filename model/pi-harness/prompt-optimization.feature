Feature: Independently measured builder-instruction optimization
  ORC activates complete purpose-specific builder strategies only after independent
  measurements compare instruction candidates over valid training and validation examples.

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
      And independent verification and a complete candidate-blind black-box grade produce its metric
      And the metric does not require an approved sealed grader
      And the builder cannot supply or alter that metric

    @item-01-023 @skip
    Scenario: Only the winning instruction for each purpose becomes durably active
      Given prompt optimization evaluated one or more instruction candidates for one purpose
      When optimization completes
      Then the result identifies the winning instruction and its independent score
      And ORC durably deactivates the prior instruction for that purpose
      And subsequent cycles recover the selected instruction after process restart

    @item-01-056 @skip
    Scenario: GEPA optimizes the complete strategy sent through one prompt seam
      Given ORC owns a complete model-directed strategy and deterministic runtime task context
      When an instruction candidate is evaluated
      Then ORC renders the exact candidate strategy beside the runtime task context
      And the evaluated prompt digest equals the prompt digest received by the builder
      And adapter code neither appends instructions nor constructs production prompt text

    @item-01-057 @skip
    Scenario: Generation and verification repair are independent optimization targets
      Given regeneration and gate repair use different builder purposes
      When ORC measures instruction candidates for each purpose
      Then the generation candidate is measured from a fresh approved cut
      And the repair candidate is measured from fixed failed-gate inputs
      And behavior supplied by one purpose cannot improve the other purpose's score

    @item-01-058 @skip
    Scenario: Bedrock reflection is pinned and provenance recorded
      Given the operator selects an enabled Bedrock reflection model
      When GEPA proposes an instruction candidate
      Then the optimization binds the Bedrock provider and exact model identity
      And the proposal records sanitized model-call and usage evidence
      And AWS credentials do not enter prompts, events, transcripts, or repository state

    @item-01-059 @skip
    Scenario: ORC owns every builder and grader-author production prompt
      Given a regeneration builder, gate-repair builder, or grader author starts
      When its model session is created
      Then ORC supplies both the system instruction and task instruction
      And ORC binds their digests to the deterministic runtime context and rendered prompt
      And TypeScript transports the prompt without constructing or appending production text

    @item-01-060 @skip
    Scenario: Harness enforcement does not depend on model compliance
      Given a production prompt describes the candidate and execution boundaries
      When the model ignores or contradicts one of those boundaries
      Then execution rooms still enforce filesystem, network, and credential isolation
      And verification still enforces stack, Compose, health, tests, affordances, and evidence
      And prompt text cannot weaken an independent gate

    @item-01-061 @skip
    Scenario: Bedrock reflection and candidate execution remain separate roles
      Given GEPA uses the pinned Bedrock reflection model
      When Bedrock proposes a builder-instruction candidate
      Then a fresh fenced Pi/Codex builder executes that instruction
      And Bedrock receives no candidate workspace or builder credentials
      And the independent grader remains outside both model roles
