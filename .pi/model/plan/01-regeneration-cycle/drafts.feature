Feature: Preparing, approving, and running a regeneration cycle through an operator session, draft scenarios
  Drafts for the Allium obligations no scenario in spec.feature covers (2026-11-06).
  The harness does not read this file. Only the product owner moves a draft into
  spec.feature, under the Rule it belongs to; the stamper then gives it its @item id and
  the @draft tag is dropped. Every noun is a term of GLOSSARY.md.

  Most drafts are refusals the accepted scenarios do not reach: a cut the harness did not
  fence, a system view that does not reduce or decompose, a slice the cut does not compose,
  an empty resolution, a zero outer-cycle budget, and the three submission refusals. The
  rest are the approval of a selection, the whole-plan submission, the start of a cycle by
  ORC, and the shape of a selected stamped item.

  # regeneration-cycle.allium, rule ComposeCutSystemView (requires 1)
  # Goes under: Rule: The neutral source cut is pinned and physically fenced
  # Suggested tier: deferred

    @draft @allium-rule-failure.ComposeCutSystemView.1
    Scenario: A system composition is refused for a source cut that was never fenced
      Given a source cut the Pi harness did not prepare and fence
      When the operator asks for the system composition of that cut
      Then composing the system view is refused
      And no system composition is recorded for the cut

  # regeneration-cycle.allium, rule ComposeCutSystemView (requires 3)
  # Goes under: Rule: The neutral source cut is pinned and physically fenced
  # Suggested tier: deferred

    @draft @allium-rule-failure.ComposeCutSystemView.3
    Scenario: Slice mappings that reduce to a different system view are refused
      Given a prepared cut whose stamped items each map to exactly one slice
      But reducing those slice mappings gives a hierarchy of containers, components, and slices other than the cut's own
      When the operator asks for the system composition of that cut
      Then composing the system view is refused
      And no system composition is recorded for the cut

  # regeneration-cycle.allium, rule ComposeCutSystemView (requires 4)
  # Goes under: Rule: The neutral source cut is pinned and physically fenced
  # Suggested tier: deferred

    @draft @allium-rule-failure.ComposeCutSystemView.4
    Scenario: A system view that does not decompose into the same slice mappings is refused
      Given a prepared cut whose slice mappings reduce to its system view
      But decomposing that system view gives slice mappings other than the ones it was reduced from
      When the operator asks for the system composition of that cut
      Then composing the system view is refused
      And no system composition is recorded for the cut

  # regeneration-cycle.allium, rule PreviewSelection (requires 1)
  # Goes under: Rule: Selection is deterministic and approval binds the reviewed identity
  # Suggested tier: balance

    @draft @allium-rule-failure.PreviewSelection.1
    Scenario: Previewing a selection from a source cut that was never fenced is refused
      Given a source cut the Pi harness did not prepare and fence
      When the operator previews a selection of one slice of that cut
      Then the preview is refused
      And no selection contract is recorded

  # regeneration-cycle.allium, rule PreviewSelection (requires 2)
  # Goes under: Rule: Selection is deterministic and approval binds the reviewed identity
  # Suggested tier: balance

    @draft @allium-rule-failure.PreviewSelection.2
    Scenario: Previewing a slice the cut does not compose is refused
      Given a prepared cut and its system composition
      When the operator previews a selection of a slice that is not in that system composition
      Then the preview is refused
      And no selection contract is recorded

  # regeneration-cycle.allium, rule ProposeSelectionFromIntent (requires 1)
  # Goes under: Rule: Every public operation refuses malformed or conflicting identities (04-operator-control-plane, beside 04-004)
  # Suggested tier: balance

    @draft @allium-rule-failure.ProposeSelectionFromIntent.1
    Scenario: A slice proposal against a source cut that was never fenced is refused
      Given a source cut the Pi harness did not prepare and fence
      When the operator asks for a slice proposal from the operator's intent against that cut
      Then the slice proposal is refused
      And no selection contract is recorded

  # regeneration-cycle.allium, rule ProposeSelectionFromIntent (requires 3)
  # Goes under: Rule: Every public operation refuses malformed or conflicting identities (04-operator-control-plane, beside 04-004)
  # Suggested tier: balance

    @draft @allium-rule-failure.ProposeSelectionFromIntent.3
    Scenario: A slice proposal whose item identities resolve to nothing is refused
      Given a prepared cut and its system composition
      And a slice proposal names a composed slice but only item identities the cut does not stamp
      When the operator asks for that slice proposal
      Then the slice proposal is refused
      And no selection contract is recorded

  # regeneration-cycle.allium, rule ApproveSelection and invariant ApprovedSelectionDigestIsStable
  # Goes under: Rule: Selection is deterministic and approval binds the reviewed identity
  # Suggested tier: balance

    @draft @allium-rule-success.ApproveSelection @allium-invariant.ApprovedSelectionDigestIsStable
    Scenario: Approving the reviewed digest approves the selection contract
      Given a selection contract was previewed and is awaiting approval
      When the operator approves it naming its selection identity and its exact digest
      Then the selection contract is an approved selection
      And previewing the same cut, project, slice, and items again gives that same digest

  # regeneration-cycle.allium, rule PrepareCycle (requires 3)
  # Goes under: Rule: The reviewed preparation binds every cycle-defining choice (04-operator-control-plane, beside 04-011)
  # Suggested tier: deferred

    @draft @allium-rule-failure.PrepareCycle.3
    Scenario: A cycle preparation with no outer-cycle budget is refused
      Given an approved selection
      When the operator prepares a cycle for it with an outer-cycle budget of zero
      Then the cycle preparation is refused
      And no cycle preparation is recorded

  # regeneration-cycle.allium, rule ApproveWholePlanBySubmission
  # Goes under: Rule: Cycle submission is approved, scoped, and idempotent
  # Suggested tier: minimum

    @draft @allium-rule-success.ApproveWholePlanBySubmission
    Scenario: Submitting the whole plan approves its fixed preparation and submits one cycle
      Given the Pi harness was started on a pinned cut whose plan has stamped items
      And no regeneration cycle has the request identity
      When the operator submits a whole-plan regeneration with that request identity
      Then an approved cycle preparation of the whole plan is recorded with one outer cycle
      And a regeneration cycle with that request identity is submitted at the fence stage
      And it is not yet running

  # regeneration-cycle.allium, rule ApproveWholePlanBySubmission (requires 1)
  # Goes under: Rule: Cycle submission is approved, scoped, and idempotent
  # Suggested tier: minimum

    @draft @allium-rule-failure.ApproveWholePlanBySubmission.1
    Scenario: A whole-plan submission of a pinned cut with no stamped item is refused
      Given the Pi harness was started on a pinned cut whose plan has no stamped item
      When the operator submits a whole-plan regeneration
      Then the submission is refused
      And no cycle preparation or regeneration cycle is recorded

  # regeneration-cycle.allium, rule SubmitCycle (requires 2)
  # Goes under: Rule: Cycle submission is approved, scoped, and idempotent
  # Suggested tier: balance

    @draft @allium-rule-failure.SubmitCycle.2
    Scenario: Submitting a preparation that does not fix its execution contract is refused
      Given an approved cycle preparation that leaves its production prompt version, tool surface, retries, or termination open
      When the operator submits a regeneration cycle for that preparation
      Then the submission is refused
      And no regeneration cycle is recorded

  # regeneration-cycle.allium, rule SubmitCycle (requires 3)
  # Goes under: Rule: Cycle submission is approved, scoped, and idempotent
  # Suggested tier: balance

    @draft @allium-rule-failure.SubmitCycle.3
    Scenario: Submitting a preparation whose selection carries no stamped item is refused
      Given an approved cycle preparation whose selection contract carries no stamped item
      When the operator submits a regeneration cycle for that preparation
      Then the submission is refused
      And no regeneration cycle is recorded

  # regeneration-cycle.allium, rule SubmitCycle (requires 5)
  # Goes under: Rule: Backend limitations remain explicit
  # Suggested tier: balance

    @draft @allium-rule-failure.SubmitCycle.5
    Scenario: A submission on an execution backend the execution profile does not admit is refused
      Given an approved cycle preparation with the gondolin-isolated execution profile
      When the operator submits a regeneration cycle for it on the host execution backend
      Then the submission is refused
      And no regeneration cycle is recorded
      But the same submission on the gondolin execution backend is accepted

  # regeneration-cycle.allium, rule StartCycle
  # Goes under: Rule: Cycle submission is approved, scoped, and idempotent
  # Suggested tier: balance

    @draft @allium-rule-success.StartCycle
    Scenario: ORC starts a submitted cycle when it starts the tick dispatched for it
      Given a regeneration cycle was submitted and waits at the fence stage
      When ORC starts the tick dispatched for that cycle's workflow task
      Then the regeneration cycle is running
      And it runs from the fence stage

  # regeneration-cycle.allium, surface OrcCycleStart, guarantee OrcStartsOnlyDispatchedCycles
  # Goes under: Rule: Cycle submission is approved, scoped, and idempotent
  # Suggested tier: balance

    @draft @allium-guarantee.OrcStartsOnlyDispatchedCycles
    Scenario: Approval and submission alone start no cycle
      Given an approved cycle preparation
      And the operator submitted a regeneration cycle for it
      When time passes and ORC has started no tick dispatched for that cycle's workflow task
      Then the regeneration cycle stays submitted at the fence stage
      And its cycle progress view reports not-started

  # regeneration-cycle.allium, invariant SelectedItemsAreTitledStampedScenarios
  # Goes under: Rule: Selection is deterministic and approval binds the reviewed identity
  # Suggested tier: balance

    @draft @allium-invariant.SelectedItemsAreTitledStampedScenarios
    Scenario: Every stamped item of a selection contract is a titled, stamped scenario
      Given a prepared cut whose slice has one scenario under a Rule and one outside any Rule
      When the operator previews a selection of that slice
      Then every stamped item carries its item identity, scenario digest, source, and a non-empty title
      And the stamped item under a Rule names that Rule
      And the stamped item outside any Rule names no Rule rather than an empty one
