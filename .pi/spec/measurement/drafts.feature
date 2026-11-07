Feature: Measurement, draft scenarios
  Drafts for the Allium obligations of this context (2026-11-06): each rule's success, each
  refusal that is behaviour, each invariant, each contract invariant and each guarantee of
  allium/ that no gate verifies. The harness does not read this file. Only the product owner moves a draft into
  spec.feature, under the Rule it names, where the stamper gives it an @item id and the @draft
  tag is dropped. Every noun is a term of .pi/model/GLOSSARY.md.

  Rule: The checklist is derived from the stamped scenarios at a pinned source cut, never written

    # checklist.allium, rule NoteItemIdentity; invariant OneItemPerIdentity
    # Suggested tier: balance
    @draft @allium-invariant.OneItemPerIdentity
    Scenario: An item identity handed over twice is known once
      Given the stamper handed out item identity 03-001 for slice 03
      When Specification hands over the stamping of 03-001 again
      Then the checklist counts item identity 03-001 once

  Rule: The operator reads a cycle's measurement receipt

    # checklist.allium, surface InstrumentDesk, guarantee MeasurementReceiptOnRequest
    # Legacy: @harness-037
    # Suggested tier: balance
    @draft @allium-guarantee.MeasurementReceiptOnRequest
    Scenario: The measurement receipt shows every verdict and reason, the denominator, the unreached count and the superseded verdicts
      Given the measurement of cycle C-7 was filed with 3 items, 1 pass, 1 unreached and 1 superseded verdict
      When the operator reads the measurement receipt of C-7
      Then the receipt shows each item's verdict and reason
      And shows the total 3, the unreached count 1 and the superseded verdict

  # ---- The open grader (proposed 2026-11-07, for the next spec round)
  # Drafts with no Allium yet: the score stage with the open grader, from the open grader tried
  # and reverted in the spec-v2 harness (6708388, b6fcf72). They carry no @allium tag until the
  # spec round writes the rules. How the open grader grades is Grader lifecycle's drafts. They
  # conflict with the accepted Rule "The approved sealed grader files the item verdicts it
  # returned for the gated candidate only" and with AGENTS.md "The instrument" (sealed,
  # calibrated, operator-approved): domain-design.md, "Open questions", OG1 to OG3.

  Rule: The score stage delegates grading to a child grading workflow over the gated candidate

    # New behaviour, no Allium yet (open grader); legacy README step 5: "grade each checklist item against the running app"
    # Suggested tier: minimum
    @draft
    Scenario: The score stage hands the checklist and the gated candidate to a child grading workflow
      Given the checklist of source cut K-3 holds 03-001, 03-002 and 03-003
      And gate attempt 1 of cycle C-7 at K-3 passed for candidate D-1
      When ORC runtime runs the score stage of C-7
      Then a child grading workflow grades the checklist of K-3, each item with its scenario, against D-1 only
      And the item verdicts it returns are filed for D-1 of C-7, with the grading run they came from

  Rule: The open grader returns one verdict per item with evidence; an item it leaves out is unreached

    # New behaviour, no Allium yet (open grader); contract Scoring, invariant UnreachedIsAFailAndStaysCounted
    # Suggested tier: minimum
    @draft
    Scenario: An item the open grader leaves out is filed as fail with the reason unreached
      Given the checklist of source cut K-3 holds 03-001, 03-002 and 03-003
      And gate attempt 1 of cycle C-7 at K-3 passed for candidate D-1
      When the open grader returns pass for 03-001 and fail for 03-002, each with its evidence, and nothing for 03-003
      Then the measurement of C-7 files 03-001 as pass and 03-002 as fail, each with its evidence
      And files 03-003 as fail with the reason unreached, and its total is 3

    # New behaviour, no Allium yet (open grader); FileItemVerdicts (requires 4), zero verdicts
    # Suggested tier: minimum
    @draft
    Scenario: An open grader that returns no verdicts files no score
      Given gate attempt 1 of cycle C-7 passed for candidate D-1
      When the child grading workflow of C-7 ends with no item verdicts, finished or not
      Then no score of C-7 is filed
      And the score stage of C-7 fails as zero verdicts

  Rule: A surprise is recorded beside the measurement and never changes a verdict

    # New behaviour, no Allium yet (open grader); surprises
    # Suggested tier: balance
    @draft
    Scenario: The open grader's surprise is on the measurement receipt and the verdict stands
      Given gate attempt 1 of cycle C-7 passed for candidate D-1
      When the open grader returns pass for 03-001 with the surprise "the scenario and seam.md disagree on the refusal's wording"
      Then the measurement of C-7 files 03-001 as pass
      And the measurement receipt of C-7 shows the surprise beside 03-001, apart from the item verdicts
