Feature: Independently measured builder-instruction optimization, draft scenarios
  Drafts for the Allium obligations no scenario in spec.feature covers (2026-11-06).
  The harness does not read this file. Only the product owner moves a draft into
  spec.feature, under the Rule it belongs to, where the stamper gives it its @item id
  and the @draft tag is dropped. Every noun is a term of GLOSSARY.md.

  Twenty-five drafts: the three refusals of rendering a production prompt, starting a
  prompt optimization and its two binding refusals, proposing an instruction candidate
  and its four refusals, measuring an instruction candidate and its five refusals,
  rejecting an instruction candidate and its two refusals, the three refusals of
  selecting a winner, an optimization that ends without a winner, and ORC alone
  reporting optimization facts.

  # prompt-optimization.allium, rule RenderProductionPrompt (requires 1)
  # Goes under: Rule: Prompt optimization is independently measured
  # Suggested tier: balance

    @draft @allium-rule-failure.RenderProductionPrompt.1
    Scenario: A production prompt without a system instruction digest is refused
      Given ORC starts a tick for a slice builder
      When ORC reports a rendered production prompt whose system instruction digest is empty
      Then the production prompt is refused
      And no production prompt is recorded for that builder session role

  # prompt-optimization.allium, rule RenderProductionPrompt (requires 2)
  # Goes under: Rule: Prompt optimization is independently measured
  # Suggested tier: balance

    @draft @allium-rule-failure.RenderProductionPrompt.2
    Scenario: A production prompt without a task instruction digest is refused
      Given ORC starts a tick for a repair builder
      When ORC reports a rendered production prompt whose task instruction digest is empty
      Then the production prompt is refused
      And no production prompt is recorded for that builder session role

  # prompt-optimization.allium, rule RenderProductionPrompt (requires 3)
  # Goes under: Rule: Prompt optimization is independently measured
  # Suggested tier: balance

    @draft @allium-rule-failure.RenderProductionPrompt.3
    Scenario: A production prompt without its runtime task context is refused
      Given ORC starts a tick for a grader author
      When ORC reports a rendered production prompt whose runtime task context digest is empty
      Then the production prompt is refused
      And no production prompt is recorded for that builder session role

  # prompt-optimization.allium, rule StartPromptOptimization
  # Goes under: Rule: Prompt optimization is independently measured
  # Suggested tier: deferred

    @draft @allium-rule-success.StartPromptOptimization
    Scenario: An operator starts a prompt optimization for one purpose
      Given training and validation examples for the fresh-generation purpose
      When the operator requests prompt optimization with the pinned reflection provider and a named reflection model
      Then a prompt optimization for fresh generation is evaluating
      And its reflection model binding names that provider and model
      And it has no winning instruction yet

  # prompt-optimization.allium, rule StartPromptOptimization (requires 1)
  # Goes under: Rule: Prompt optimization is independently measured
  # Suggested tier: deferred

    @draft @allium-rule-failure.StartPromptOptimization.1
    Scenario: A prompt optimization with an unpinned reflection provider is refused
      Given training and validation examples for the verification-repair purpose
      When the operator requests prompt optimization naming a reflection provider other than the pinned one
      Then the prompt optimization is refused
      And no prompt optimization is evaluating and no instruction candidate is proposed

  # prompt-optimization.allium, rule StartPromptOptimization (requires 2)
  # Goes under: Rule: Prompt optimization is independently measured
  # Suggested tier: deferred

    @draft @allium-rule-failure.StartPromptOptimization.2
    Scenario: A prompt optimization without a reflection model is refused
      Given training and validation examples for the fresh-generation purpose
      When the operator requests prompt optimization with the pinned reflection provider and no reflection model
      Then the prompt optimization is refused
      And no prompt optimization is evaluating and no instruction candidate is proposed

  # prompt-optimization.allium, rule ProposeInstructionCandidate
  # Goes under: Rule: Prompt optimization is independently measured
  # Suggested tier: deferred

    @draft @allium-rule-success.ProposeInstructionCandidate
    Scenario: GEPA proposes an instruction candidate under the pinned reflection model binding
      Given an operator started a prompt optimization for fresh generation that is evaluating
      When ORC reports an instruction candidate for fresh generation, proposed by the bound reflection provider and model, with its reflection call digest
      Then the instruction candidate is recorded as proposed with that reflection call digest
      And it has no production prompt digest, independent score, or metric evidence yet

  # prompt-optimization.allium, rule ProposeInstructionCandidate (requires 2)
  # Goes under: Rule: Generation and verification repair are independent optimization targets
  # Suggested tier: deferred

    @draft @allium-rule-failure.ProposeInstructionCandidate.2
    Scenario: An instruction candidate for the other purpose is refused
      Given a prompt optimization for fresh generation is evaluating
      When ORC reports an instruction candidate whose builder instruction is for verification repair
      Then the instruction candidate is refused
      And the prompt optimization's instruction candidates are unchanged

  # prompt-optimization.allium, rule ProposeInstructionCandidate (requires 3)
  # Goes under: Rule: Prompt optimization is independently measured
  # Suggested tier: deferred

    @draft @allium-rule-failure.ProposeInstructionCandidate.3
    Scenario: An instruction candidate from another reflection provider is refused
      Given a prompt optimization is evaluating under the pinned reflection provider
      When ORC reports an instruction candidate proposed by a different reflection provider
      Then the instruction candidate is refused
      And the prompt optimization's instruction candidates are unchanged

  # prompt-optimization.allium, rule ProposeInstructionCandidate (requires 4)
  # Goes under: Rule: Prompt optimization is independently measured
  # Suggested tier: deferred

    @draft @allium-rule-failure.ProposeInstructionCandidate.4
    Scenario: An instruction candidate from another reflection model is refused
      Given a prompt optimization is evaluating with its reflection model binding
      When ORC reports an instruction candidate proposed by the pinned reflection provider with a different reflection model
      Then the instruction candidate is refused
      And the prompt optimization's instruction candidates are unchanged

  # prompt-optimization.allium, rule ProposeInstructionCandidate (requires 5)
  # Goes under: Rule: Prompt optimization is independently measured
  # Suggested tier: deferred

    @draft @allium-rule-failure.ProposeInstructionCandidate.5
    Scenario: An instruction candidate without its reflection call evidence is refused
      Given a prompt optimization is evaluating with its reflection model binding
      When ORC reports an instruction candidate with an empty reflection call digest
      Then the instruction candidate is refused
      And the prompt optimization's instruction candidates are unchanged

  # prompt-optimization.allium, rule MeasureInstructionCandidate
  # Goes under: Rule: Prompt optimization is independently measured
  # Suggested tier: deferred

    @draft @allium-rule-success.MeasureInstructionCandidate
    Scenario: An instruction candidate is measured under the exact production prompt it ran with
      Given ORC proposed an instruction candidate for fresh generation
      And ORC rendered a slice builder's production prompt whose task instruction is that candidate's builder instruction
      And the builder host transported that production prompt unchanged
      When ORC returns an independent metric from a fresh approved cut, with the fresh builder's gate result and a complete black-box grade
      Then the instruction candidate is measured
      And it records that production prompt's digest, the independent score, and the metric evidence

  # prompt-optimization.allium, rule MeasureInstructionCandidate (requires 2)
  # Goes under: Rule: Generation and verification repair are independent optimization targets
  # Suggested tier: deferred

    @draft @allium-rule-failure.MeasureInstructionCandidate.2
    Scenario: A metric from a production prompt of the wrong builder session role is refused
      Given ORC proposed an instruction candidate for verification repair
      And ORC rendered a slice builder's production prompt
      When ORC returns an independent metric for the instruction candidate under that slice builder's production prompt
      Then the metric is refused
      And the instruction candidate stays proposed with no independent score

  # prompt-optimization.allium, rule MeasureInstructionCandidate (requires 3)
  # Goes under: Rule: Prompt optimization is independently measured
  # Suggested tier: deferred

    @draft @allium-rule-failure.MeasureInstructionCandidate.3
    Scenario: A metric from a production prompt carrying a different builder instruction is refused
      Given ORC proposed an instruction candidate for fresh generation
      And ORC rendered a slice builder's production prompt whose task instruction digest differs from the candidate's builder instruction
      When ORC returns an independent metric for the instruction candidate under that production prompt
      Then the metric is refused
      And the instruction candidate stays proposed with no independent score

  # prompt-optimization.allium, rule MeasureInstructionCandidate (requires 4)
  # Goes under: Rule: Prompt optimization is independently measured
  # Suggested tier: deferred

    @draft @allium-rule-failure.MeasureInstructionCandidate.4
    Scenario: A metric from a production prompt the builder host altered is refused
      Given ORC proposed an instruction candidate for fresh generation
      And ORC rendered the slice builder's production prompt carrying its builder instruction
      But the builder host did not transport that production prompt byte for byte
      When ORC returns an independent metric for the instruction candidate
      Then the metric is refused
      And the instruction candidate stays proposed with no independent score

  # prompt-optimization.allium, rule MeasureInstructionCandidate (requires 5)
  # Goes under: Rule: Generation and verification repair are independent optimization targets
  # Suggested tier: deferred

    @draft @allium-rule-failure.MeasureInstructionCandidate.5
    Scenario: A repair metric not drawn from fixed failed-gate inputs is refused
      Given ORC proposed an instruction candidate for verification repair
      And ORC rendered the repair builder's production prompt carrying its builder instruction
      When ORC returns an independent metric whose evidence comes from a fresh approved cut instead of fixed failed-gate inputs
      Then the metric is refused
      And the instruction candidate stays proposed with no independent score

  # prompt-optimization.allium, rule MeasureInstructionCandidate (requires 6)
  # Goes under: Rule: Prompt optimization is independently measured
  # Suggested tier: deferred

    @draft @allium-rule-failure.MeasureInstructionCandidate.6
    Scenario: A metric without a gate result and a complete black-box grade is refused
      Given ORC proposed an instruction candidate for fresh generation
      And ORC rendered the slice builder's production prompt carrying its builder instruction
      When ORC returns an independent metric whose evidence lacks the fresh builder's gate result or a complete black-box grade
      Then the metric is refused
      And the instruction candidate stays proposed with no independent score

  # prompt-optimization.allium, rule RejectInstructionCandidate
  # Goes under: Rule: Prompt optimization is independently measured
  # Suggested tier: deferred

    @draft @allium-rule-success.RejectInstructionCandidate
    Scenario: GEPA rejects a measured instruction candidate it will not carry forward
      Given a prompt optimization is evaluating
      And one of its instruction candidates was measured
      When ORC reports that instruction candidate rejected
      Then the instruction candidate is rejected
      And it can never become its purpose's active instruction

  # prompt-optimization.allium, rule RejectInstructionCandidate (requires 1)
  # Goes under: Rule: Only the winning instruction for each purpose becomes durably active
  # Suggested tier: deferred

    @draft @allium-rule-failure.RejectInstructionCandidate.1
    Scenario: Rejecting an instruction candidate that was already selected is refused
      Given an instruction candidate was selected as its purpose's active instruction
      When ORC reports that instruction candidate rejected
      Then the rejection is refused
      And the instruction candidate is still the active instruction for its purpose

  # prompt-optimization.allium, rule RejectInstructionCandidate (requires 2)
  # Goes under: Rule: Prompt optimization is independently measured
  # Suggested tier: deferred

    @draft @allium-rule-failure.RejectInstructionCandidate.2
    Scenario: Rejecting an instruction candidate after its optimization ended is refused
      Given a prompt optimization failed without a winner
      And one of its instruction candidates was measured
      When ORC reports that instruction candidate rejected
      Then the rejection is refused
      And the instruction candidate stays measured

  # prompt-optimization.allium, rule SelectWinningInstruction (requires 2)
  # Goes under: Rule: Only the winning instruction for each purpose becomes durably active
  # Suggested tier: deferred

    @draft @allium-rule-failure.SelectWinningInstruction.2
    Scenario: A winner from another prompt optimization is refused
      Given two prompt optimizations are evaluating
      And an instruction candidate of the second was measured
      When ORC reports that instruction candidate as the first optimization's winner
      Then the winner is refused
      And both prompt optimizations are still evaluating and the active instructions are unchanged

  # prompt-optimization.allium, rule SelectWinningInstruction (requires 4)
  # Goes under: Rule: Generation and verification repair are independent optimization targets
  # Suggested tier: deferred

    @draft @allium-rule-failure.SelectWinningInstruction.4
    Scenario: A winner for the other purpose is refused
      Given a prompt optimization for fresh generation is evaluating
      And one of its instruction candidates was measured but carries a builder instruction for verification repair
      When ORC reports that instruction candidate as the winner
      Then the winner is refused
      And the prompt optimization is still evaluating and the active instructions are unchanged

  # prompt-optimization.allium, rule SelectWinningInstruction (requires 5) and invariant
  # ActiveInstructionWasIndependentlyMeasured
  # Goes under: Rule: Only the winning instruction for each purpose becomes durably active
  # Suggested tier: deferred

    @draft @allium-rule-failure.SelectWinningInstruction.5 @allium-invariant.ActiveInstructionWasIndependentlyMeasured
    Scenario: Only the highest independently measured instruction candidate can win
      Given a prompt optimization is evaluating
      And ORC measured two of its instruction candidates with independent scores 0.6 and 0.8
      And a third instruction candidate was proposed but never measured
      When ORC reports the instruction candidate scored 0.6 as the winner
      Then the winner is refused
      And the prompt optimization is still evaluating and the active instructions are unchanged
      And every active instruction names its independent score, production prompt digest, and metric evidence

  # prompt-optimization.allium, rule FailPromptOptimization
  # Goes under: Rule: Prompt optimization is independently measured
  # Suggested tier: deferred

    @draft @allium-rule-success.FailPromptOptimization
    Scenario: A prompt optimization that ends without a winner fails with its reason
      Given a fresh-generation instruction is active
      And a prompt optimization for fresh generation is evaluating
      When ORC reports that the prompt optimization ended without a winner
      Then the prompt optimization fails with that reason recorded
      And the active fresh-generation instruction is unchanged

  # prompt-optimization.allium, guarantee OptimizationFactsAreOrcs
  # Goes under: Rule: Prompt optimization is independently measured
  # Suggested tier: deferred

    @draft @allium-guarantee.OptimizationFactsAreOrcs
    Scenario: An operator cannot report optimization facts on ORC's behalf
      Given an operator started a prompt optimization that is evaluating
      When the operator tries to propose, measure, reject, or select an instruction candidate
      Then each attempt is refused
      And only ORC's reports change the prompt optimization or its instruction candidates
