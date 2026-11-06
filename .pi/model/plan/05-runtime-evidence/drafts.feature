Feature: Authoritative and complete runtime evidence, draft scenarios
  Drafts for the Allium obligations no scenario in spec.feature covers (2026-11-06).
  The harness does not read this file. Only the product owner moves a draft into
  spec.feature, under the Rule it belongs to, where the stamper gives it its @item id and
  the @draft tag is dropped. Every noun is a term of GLOSSARY.md.

  Twenty-five drafts: the refusals of recording a runtime signal, of an independent
  invariant evaluation with a pass or fail verdict, of an inconclusive evaluation, of
  opening a validation room and of binding a baseline trace artifact; and the two
  invariants no accepted scenario checks, that independent rooms keep the gated subject and
  that a bound trace artifact is complete and independent.

  # runtime-evidence.allium, rule RecordRuntimeSignal (requires 1)
  # Goes under: Rule: Runtime evidence preserves authority and collection quality
  # Suggested tier: deferred

    @draft @allium-rule-failure.RecordRuntimeSignal.1
    Scenario: A runtime signal without an identity is refused
      Given a regeneration cycle is running
      When ORC reports a captured runtime signal for that cycle with an empty signal identity
      Then recording the runtime signal is refused
      And the cycle's recorded runtime signals are unchanged

  # runtime-evidence.allium, rule RecordRuntimeSignal (requires 2)
  # Goes under: Rule: Runtime evidence preserves authority and collection quality
  # Suggested tier: deferred

    @draft @allium-rule-failure.RecordRuntimeSignal.2
    Scenario: A runtime signal that names no regeneration cycle is refused
      Given a regeneration cycle is running
      When ORC reports a captured runtime signal with an empty cycle identity
      Then recording the runtime signal is refused
      And no runtime signal is recorded under that signal identity

  # runtime-evidence.allium, rule RecordRuntimeSignal (requires 3)
  # Goes under: Rule: Runtime evidence preserves authority and collection quality
  # Suggested tier: deferred

    @draft @allium-rule-failure.RecordRuntimeSignal.3
    Scenario: A runtime signal that names no producer is refused
      Given a regeneration cycle is running
      When ORC reports a captured runtime signal for that cycle with an empty producer
      Then recording the runtime signal is refused
      And the cycle's recorded runtime signals are unchanged

  # runtime-evidence.allium, rule RecordRuntimeSignal (requires 4)
  # Goes under: Rule: Runtime evidence preserves authority and collection quality
  # Suggested tier: deferred

    @draft @allium-rule-failure.RecordRuntimeSignal.4
    Scenario: A runtime signal with a negative dropped count is refused
      Given a regeneration cycle is running
      When ORC reports a captured runtime signal for that cycle whose dropped count is -1
      Then recording the runtime signal is refused
      And the cycle's recorded runtime signals are unchanged

  # runtime-evidence.allium, rule EvaluateCompleteInvariant (requires 1)
  # Goes under: Rule: Runtime evidence preserves authority and collection quality
  # Suggested tier: deferred

    @draft @allium-rule-failure.EvaluateCompleteInvariant.1
    Scenario: A pass or fail evaluation over no runtime signals is refused
      Given a regeneration cycle whose gate passed a candidate with subject digest "S-1"
      And ORC captured no runtime signal for an approved item of that cycle
      When ORC reports an independent invariant check of that item with the verdict pass and no runtime signals
      Then the independent invariant evaluation is refused
      And no evidence verdict is recorded for that item and invariant

  # runtime-evidence.allium, rule EvaluateCompleteInvariant (requires 2)
  # Goes under: Rule: Runtime evidence preserves authority and collection quality
  # Suggested tier: deferred

    @draft @allium-rule-failure.EvaluateCompleteInvariant.2
    Scenario: A pass or fail evaluation over another cycle's runtime signal is refused
      Given ORC captured complete runtime signals for regeneration cycle "C-1"
      And ORC captured a complete runtime signal for regeneration cycle "C-2"
      When ORC reports an independent invariant check for cycle "C-1" with the verdict pass over signals of both cycles
      Then the independent invariant evaluation is refused
      And no evidence verdict is recorded for cycle "C-1" for that item and invariant

  # runtime-evidence.allium, rule EvaluateCompleteInvariant (requires 3)
  # Goes under: Rule: Runtime evidence preserves authority and collection quality
  # Suggested tier: deferred

    @draft @allium-rule-failure.EvaluateCompleteInvariant.3
    Scenario: A pass or fail evaluation over runtime signals of another subject digest is refused
      Given ORC captured a complete runtime signal naming subject digest "S-1"
      And ORC captured a complete runtime signal of the same cycle naming subject digest "S-2"
      When ORC reports an independent invariant check for subject digest "S-1" with the verdict pass over both signals
      Then the independent invariant evaluation is refused
      And no evidence verdict is recorded for that item and invariant

  # runtime-evidence.allium, rule EvaluateCompleteInvariant (requires 4)
  # Goes under: Rule: Runtime evidence preserves authority and collection quality
  # Suggested tier: deferred

    @draft @allium-rule-failure.EvaluateCompleteInvariant.4
    Scenario: A pass or fail evaluation over an incomplete runtime signal is refused
      Given ORC captured two runtime signals of one cycle and subject digest, one of them incomplete
      When ORC reports an independent invariant check with the verdict pass over both signals
      Then the independent invariant evaluation is refused
      And no evidence verdict is recorded for that item and invariant

  # runtime-evidence.allium, rule EvaluateCompleteInvariant (requires 5)
  # Goes under: Rule: Runtime evidence preserves authority and collection quality
  # Suggested tier: deferred

    @draft @allium-rule-failure.EvaluateCompleteInvariant.5
    Scenario: A pass or fail evaluation over a sampled runtime signal is refused
      Given ORC captured two complete runtime signals of one cycle and subject digest, one of them sampled
      When ORC reports an independent invariant check with the verdict fail over both signals
      Then the independent invariant evaluation is refused
      And no evidence verdict is recorded for that item and invariant

  # runtime-evidence.allium, rule EvaluateCompleteInvariant (requires 6)
  # Goes under: Rule: Runtime evidence preserves authority and collection quality
  # Suggested tier: deferred

    @draft @allium-rule-failure.EvaluateCompleteInvariant.6
    Scenario: A pass or fail evaluation over a runtime signal that dropped records is refused
      Given ORC captured two complete runtime signals of one cycle and subject digest, one of them with a dropped count of 3
      When ORC reports an independent invariant check with the verdict pass over both signals
      Then the independent invariant evaluation is refused
      And no evidence verdict is recorded for that item and invariant

  # runtime-evidence.allium, rule EvaluateCompleteInvariant (requires 7)
  # Goes under: Rule: Runtime evidence preserves authority and collection quality
  # Suggested tier: deferred

    @draft @allium-rule-failure.EvaluateCompleteInvariant.7
    Scenario: A complete collection cannot be evaluated as inconclusive
      Given ORC captured complete, unsampled runtime signals with nothing dropped for one cycle and subject digest
      When ORC reports an independent invariant check over them with the verdict inconclusive
      Then the independent invariant evaluation is refused
      And no evidence verdict is recorded for that item and invariant

  # runtime-evidence.allium, rule MarkIncompleteInvariantInconclusive (requires 1)
  # Goes under: Rule: Runtime evidence preserves authority and collection quality
  # Suggested tier: deferred

    @draft @allium-rule-failure.MarkIncompleteInvariantInconclusive.1
    Scenario: A complete collection cannot be marked inconclusive
      Given ORC captured complete, unsampled runtime signals with nothing dropped for one cycle and subject digest
      When ORC reports the inputs of an independent invariant check over them as incomplete
      Then marking the independent invariant evaluation inconclusive is refused
      And no evidence verdict is recorded for that item and invariant

  # runtime-evidence.allium, rule MarkIncompleteInvariantInconclusive (requires 2)
  # Goes under: Rule: Runtime evidence preserves authority and collection quality
  # Suggested tier: deferred

    @draft @allium-rule-failure.MarkIncompleteInvariantInconclusive.2
    Scenario: An inconclusive evaluation must name the affected signal kind and reason
      Given ORC captured a sampled runtime signal whose collection completeness records no reason
      When ORC reports the inputs of an independent invariant check over it as incomplete
      Then marking the independent invariant evaluation inconclusive is refused
      And no evidence verdict is recorded for that item and invariant

  # runtime-evidence.allium, rule OpenValidationRoom (requires 1)
  # Goes under: Rule: Runtime evidence preserves authority and collection quality
  # Suggested tier: deferred

    @draft @allium-rule-failure.OpenValidationRoom.1
    Scenario: A validation room with a writable capture policy is refused
      Given a regeneration cycle whose gate passed its candidate
      When ORC reports a validation room started for that cycle with its capture policy mounted writable
      Then opening the validation room is refused
      And the cycle has no validation room

  # runtime-evidence.allium, rule OpenValidationRoom (requires 2)
  # Goes under: Rule: Runtime evidence preserves authority and collection quality
  # Suggested tier: deferred

    @draft @allium-rule-failure.OpenValidationRoom.2
    Scenario: A validation room with the capture policy at another mount is refused
      Given a regeneration cycle whose gate passed its candidate
      When ORC reports a validation room started for that cycle with its read-only capture policy mounted outside "/observability"
      Then opening the validation room is refused
      And the cycle has no validation room

  # runtime-evidence.allium, rule OpenValidationRoom (requires 3)
  # Goes under: Rule: Runtime evidence preserves authority and collection quality
  # Suggested tier: deferred

    @draft @allium-rule-failure.OpenValidationRoom.3
    Scenario: A validation room with another cycle's evidence volume is refused
      Given regeneration cycles "C-1" and "C-2" each have their own cycle evidence volume
      When ORC reports a validation room started for cycle "C-1" with the cycle evidence volume of cycle "C-2"
      Then opening the validation room is refused
      And cycle "C-1" has no validation room

  # runtime-evidence.allium, rule OpenValidationRoom (requires 4)
  # Goes under: Rule: Runtime evidence preserves authority and collection quality
  # Suggested tier: deferred

    @draft @allium-rule-failure.OpenValidationRoom.4
    Scenario: A validation room with the cycle evidence volume at another mount is refused
      Given a regeneration cycle whose gate passed its candidate
      When ORC reports a validation room started for that cycle with its cycle evidence volume mounted outside "/evidence"
      Then opening the validation room is refused
      And the cycle has no validation room

  # runtime-evidence.allium, rule OpenValidationRoom (requires 5)
  # Goes under: Rule: Runtime evidence preserves authority and collection quality
  # Suggested tier: deferred

    @draft @allium-rule-failure.OpenValidationRoom.5
    Scenario: A validation room with a read-only cycle evidence volume is refused
      Given a regeneration cycle whose gate passed its candidate
      When ORC reports a validation room started for that cycle with its cycle evidence volume mounted read-only
      Then opening the validation room is refused
      And the cycle has no validation room

  # runtime-evidence.allium, rule BindSuccessfulBaselineTrace (requires 1)
  # Goes under: Rule: Runtime evidence preserves authority and collection quality
  # Suggested tier: deferred

    @draft @allium-rule-failure.BindSuccessfulBaselineTrace.1
    Scenario: A trace artifact of a failing baseline grade is not bound
      Given an independent invariant evaluation of a regeneration cycle recorded the evidence verdict fail
      And the independent validator recorded a complete trace artifact of that grade
      When ORC reports the baseline grade completed with that evaluation and trace artifact
      Then binding the trace artifact is refused
      And the cycle has no bound trace artifact

  # runtime-evidence.allium, rule BindSuccessfulBaselineTrace (requires 2)
  # Goes under: Rule: Runtime evidence preserves authority and collection quality
  # Suggested tier: deferred

    @draft @allium-rule-failure.BindSuccessfulBaselineTrace.2
    Scenario: Another cycle's trace artifact is not bound
      Given an independent invariant evaluation of regeneration cycle "C-1" recorded the evidence verdict pass
      And the independent validator recorded a complete trace artifact for regeneration cycle "C-2"
      When ORC reports the baseline grade of cycle "C-1" completed with that evaluation and the trace artifact of cycle "C-2"
      Then binding the trace artifact is refused
      And cycle "C-1" has no bound trace artifact

  # runtime-evidence.allium, rule BindSuccessfulBaselineTrace (requires 3)
  # Goes under: Rule: Runtime evidence preserves authority and collection quality
  # Suggested tier: deferred

    @draft @allium-rule-failure.BindSuccessfulBaselineTrace.3
    Scenario: A trace artifact outside the baseline trace path is not bound
      Given an independent invariant evaluation of a regeneration cycle recorded the evidence verdict pass
      And the independent validator recorded a complete trace artifact at a path other than "playwright/baseline/trace.zip" in the cycle evidence volume
      When ORC reports the baseline grade completed with that evaluation and trace artifact
      Then binding the trace artifact is refused
      And the cycle has no bound trace artifact

  # runtime-evidence.allium, rule BindSuccessfulBaselineTrace (requires 4)
  # Goes under: Rule: Runtime evidence preserves authority and collection quality
  # Suggested tier: deferred

    @draft @allium-rule-failure.BindSuccessfulBaselineTrace.4
    Scenario: An incomplete trace artifact is not bound
      Given an independent invariant evaluation of a regeneration cycle recorded the evidence verdict pass
      And the independent validator recorded an incomplete trace artifact of that grade
      When ORC reports the baseline grade completed with that evaluation and trace artifact
      Then binding the trace artifact is refused
      And the cycle has no bound trace artifact

  # runtime-evidence.allium, rule BindSuccessfulBaselineTrace (requires 5)
  # Goes under: Rule: Runtime evidence preserves authority and collection quality
  # Suggested tier: deferred

    @draft @allium-rule-failure.BindSuccessfulBaselineTrace.5
    Scenario: A trace artifact the candidate recorded is not bound
      Given an independent invariant evaluation of a regeneration cycle recorded the evidence verdict pass
      And the candidate, not the independent validator, recorded a complete trace artifact of that grade
      When ORC reports the baseline grade completed with that evaluation and trace artifact
      Then binding the trace artifact is refused
      And the cycle has no bound trace artifact

  # runtime-evidence.allium, rule BindSuccessfulBaselineTrace and invariant
  # BoundTraceIsCompleteAndIndependent
  # Goes under: Rule: Runtime evidence preserves authority and collection quality
  # Suggested tier: deferred

    @draft @allium-invariant.BoundTraceIsCompleteAndIndependent
    Scenario: A bound trace artifact is complete, independent, of its own cycle, and digest-bound
      Given an independent invariant evaluation of regeneration cycle "C-1" recorded the evidence verdict pass
      And the independent validator recorded a complete trace artifact of that grade for cycle "C-1"
      When ORC reports the baseline grade of cycle "C-1" completed with that evaluation and trace artifact
      Then the bound trace artifact of cycle "C-1" is complete and recorded by the independent validator
      And it names cycle "C-1" and a non-empty content digest
      And it is bound to the passing evaluation's evidence digest

  # runtime-evidence.allium, invariant IndependentRoomsPreserveSubject
  # Goes under: Rule: Runtime evidence preserves authority and collection quality
  # Suggested tier: deferred

    @draft @allium-invariant.IndependentRoomsPreserveSubject
    Scenario: An independent invariant evaluation names the subject digest the gate passed
      Given the gate of a regeneration cycle passed its candidate with subject digest "S-1"
      And ORC captured complete runtime signals of that cycle naming subject digest "S-1"
      When ORC reports an independent invariant check of that cycle over those signals
      Then the recorded independent invariant evaluation names subject digest "S-1"
      And no evaluation of that cycle names an earlier or later candidate's subject digest
