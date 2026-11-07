Feature: Grader lifecycle, draft scenarios
  Drafts for the Allium obligations of this context (2026-11-06): each rule's success, each
  refusal that is behaviour, each invariant, each contract invariant and each guarantee of
  allium/ that no gate verifies. The harness does not read this file. Only the product owner moves a draft into
  spec.feature, under the Rule it names, where the stamper gives it an @item id and the @draft
  tag is dropped. Every noun is a term of .pi/model/GLOSSARY.md (the instrument maintainer is
  the event model's word for the person who keeps the instrument goldens).

  Rule: An instrument golden is written from its scenario and carries that scenario's digest

    # instrument-golden.allium, rule WriteInstrumentGolden; surface InstrumentGoldenDesk, guarantee WrittenFromTheScenarioNeverFromARun
    # Suggested tier: minimum
    @draft @allium-rule-success.WriteInstrumentGolden @allium-guarantee.WrittenFromTheScenarioNeverFromARun
    Scenario: The instrument maintainer writes a golden from its scenario
      Given the instrument scenario "a refused score names the unknown item" has the scenario digest "s-12"
      When the instrument maintainer writes instrument golden G-score-unknown from that scenario, with the scenario digest "s-12" and the digest "g-40"
      Then instrument golden G-score-unknown is written with the digest "g-40" and the scenario digest "s-12"
      And the harness self-check of the next cycle must reproduce it

    # instrument-golden.allium, invariant OneGoldenPerIdentity
    # Suggested tier: balance
    @draft @allium-invariant.OneGoldenPerIdentity
    Scenario: A golden is written once
      Given instrument golden G-score-unknown was written with the digest "g-40"
      When the instrument maintainer writes instrument golden G-score-unknown again with the digest "g-41"
      Then the writing is refused
      And there is one instrument golden G-score-unknown, with the digest "g-40"

  Rule: A golden moves only with one recorded reason, and the scenario it was written from

    # instrument-golden.allium, rule MoveInstrumentGolden (requires 1)
    # Suggested tier: balance
    @draft @allium-rule-failure.MoveInstrumentGolden.1
    Scenario: Moving a golden that was never written is refused
      Given no instrument golden G-unknown was ever written
      When the instrument maintainer moves G-unknown to the digest "g-41" for "spec grew"
      Then the move is refused
      And there is no instrument golden G-unknown

    # grader-lifecycle/allium/context.allium, contract ScenarioDigests, invariant DigestChangesWithTheBehaviour
    # Suggested tier: balance
    @draft @allium-boundary.DigestChangesWithTheBehaviour
    Scenario: Rewording a scenario changes its scenario digest and keeps its item identity
      Given the instrument scenario "@item-03-004" has the scenario digest "s-12"
      And instrument golden G-score-unknown was written from it with the scenario digest "s-12"
      When the scenario "@item-03-004" is reworded
      And the instrument maintainer moves G-score-unknown to the digest "g-41" for "wording replaced" with the scenario's new scenario digest
      Then G-score-unknown carries a scenario digest other than "s-12"
      And the scenario it was written from is still "@item-03-004"

  # ---- The open grader (proposed 2026-11-07, for the next spec round)
  # Drafts with no Allium yet: behaviour first, from the open grader tried and reverted in the
  # spec-v2 harness (6708388, b6fcf72 and its uncommitted follow-up). They name no obligation and
  # carry no @allium tag until the spec round writes the rules. They conflict with AGENTS.md "The
  # instrument" and the glossary's Grader (sealed, calibrated, operator-approved): domain-design.md,
  # "Open questions", OG1 to OG3. Its filing, its unreached items and its surprises are
  # Measurement's drafts.

  Rule: The open grader grades however it judges, through the candidate's public seam only

    # New behaviour, no Allium yet (open grader); score stage, legacy README step 5: "however you grade"
    # Suggested tier: minimum
    @draft
    Scenario: The open grader decides for each step whether it needs a model
      Given the checklist of cycle C-7 holds 03-001 and 03-002
      And gate attempt 1 of C-7 passed for candidate D-1
      When the open grader grades 03-001 by sending one request and comparing the response with the scenario's Then
      And grades 03-002 by asking a model to read a response against the scenario
      Then the grading run of C-7 records a model call for 03-002 and none for 03-001
      And both item verdicts are the open grader's, each with its evidence

    # New behaviour, no Allium yet (open grader); the public seam: seam.md of the graded context
    # Suggested tier: minimum
    @draft
    Scenario: The open grader reaches the candidate only through the seam tools
      Given gate attempt 1 of cycle C-7 passed for candidate D-1
      When the open grader grades the checklist of C-7
      Then every step it takes is one of the seam tools: start the candidate with a fresh event store, send a request, hand in a file, stop the candidate, read the spec
      And each start of D-1 gets an event store no earlier start used
      And no seam tool reads the candidate's source

    # New behaviour, no Allium yet (open grader); the black-box rule
    # Suggested tier: minimum
    @draft
    Scenario: A step that names anything but a seam tool or the grader's own contained code is refused and recorded
      Given gate attempt 1 of cycle C-7 passed for candidate D-1
      And the open grader is grading the checklist of C-7
      When the open grader takes a step that runs a host function that is not a seam tool
      Then the step is refused, and nothing outside the grading run is touched
      And the grading run of C-7 records the refused step and what it named

    # New behaviour, no Allium yet (open grader); explore session
    # Suggested tier: balance
    @draft
    Scenario: The open grader hands an item to an explore session and still files the verdict itself
      Given gate attempt 1 of cycle C-7 passed for candidate D-1
      And the open grader is grading 03-004 of the checklist of C-7
      When the open grader hands 03-004 to an explore session, with all its tools, a copy of the spec and a launch entrypoint for a fresh D-1
      Then the explore session's every tool call is recorded in the grading run of C-7
      And the explore session returns its findings, never an item verdict
      And the item verdict of 03-004 is the open grader's

  Rule: Every grading run is kept, findable by slice, cycle and candidate

    # New behaviour, no Allium yet (open grader); the grading history
    # Suggested tier: balance
    @draft
    Scenario: The operator finds a grading run by slice, cycle and candidate
      Given the open grader graded cycle C-6 of slice 03 for candidate D-0
      And the open grader graded cycle C-7 of slice 03 for candidate D-1
      When the operator reads the grading history of slice 03
      Then it lists both grading runs, each with its slice, its cycle, its candidate, its start and its verdict counts
      And the grading run of C-7 shows its steps, its model calls, its refused steps and its explore sessions

    # New behaviour, no Allium yet (open grader); a grading run that files nothing
    # Suggested tier: balance
    @draft
    Scenario: A trial grading run is kept in no history and files nothing
      Given gate attempt 1 of cycle C-7 passed for candidate D-1
      When the operator runs a trial grading of the checklist of C-7
      Then the trial's grading run and item verdicts are shown to the operator
      And the grading history and the measurement of C-7 are unchanged

  Rule: A black-box audit of a grading run reports it clean or names each breach

    # New behaviour, no Allium yet (open grader); black-box audit
    # Suggested tier: minimum
    @draft
    Scenario: A grading run that stayed on the seam is audited clean
      Given the grading run of cycle C-7 used only seam tools, its own contained code and one explore session that read only its spec copy and its launch entrypoint
      When the operator audits the grading run of C-7
      Then the audit reports it clean
      And lists every seam tool call and every explore-session tool call it checked

    # New behaviour, no Allium yet (open grader); black-box audit
    # Suggested tier: minimum
    @draft
    Scenario: An explore session that reached past the seam is named as a breach
      Given an explore session of the grading run of cycle C-7 read a file of the gate room of C-7, the candidate's source of D-1, the measurement of cycle C-6 and a host credential
      When the operator audits the grading run of C-7
      Then the audit names four breaches, each with its tool call: the room, the candidate's source, a prior cycle and a credential
      And the item verdicts of C-7 are unchanged by the audit
