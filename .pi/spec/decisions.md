# Decisions

Decided by the product owner on 2026-11-06, after R1 was checked against the legacy harness
(`archive/legacy-harness/`). Each line names the hot spot or question it settles.

## Hot spots

- **H1** A recorded fact is the truth. A gate attempt counts once it is recorded, so a replay spends none.
- **H5** The gate-attempt limit (3) is part of the preparation's approved budget, with the maximum outer cycles, a timeout per stage and at most 3 continuations.
- **H6** Gate evidence and score evidence live in separate stores; only a repair room mounts gate evidence; score evidence never reaches a builder room. ORC's room policy enforces it; a fence-like check proves it.
- **H7** Each item verdict carries a `reason` beside it (unreached, inconclusive, outage); the verdict stays pass or fail. Legacy put the reason in the evidence text.
- **H8** A cycle is numbered at submission. A cycle that fails the gate exists and has no score.
- **H10** Whole-plan submission is the selection's approval (the legacy shape); it never skips the grader-digest review.
- **H13** The score binds the gated candidate's digest and re-checks the grader digest when it is filed.
- **H24** A self-check runs before the fence and again before the gate, as the legacy harness does; a passing one is never reused across a change of harness or golden digests.
- **H25** The scoring tools are fenced from the clean room (AGENTS.md: no grading harness in the room; saunavuoro's choice). The legacy left this open.
- **H26** Duplicate verdicts: the last wins, and the superseded ones are listed.

## Questions raised by R1

- **b** An identity probe or seam that answers before boot is an infrastructure fault: the gate refuses without grading and spends no attempt (legacy `verify.sh`).
- **g** The scoreboard compares with the last measured cycle of the same selection (legacy: cycle n−1, which made a cycle after an unscored one look like a baseline).
- **h** A golden carries the digest of the scenario it was written from.

## Legacy behaviours: changes accepted

Kept as changed: ids numbered per slice from a ledger that never goes down; a bad verdict value
stops the score before an unknown item does; superseded duplicates listed; a refused score is
never retaken within the cycle; the filed record joins the checklist by its digest; goldens are
written from the scenario; every unstamped file is listed; skipped slices are stored; 3 gate
attempts plus a repair builder; a fence failure fails the cycle.

Reverted to the legacy rule: a seam already answering is not a graded attempt (b); the
self-check runs again before the gate (H24).

## Legacy behaviours: to add in R2

1. Which Gherkin lines are scenarios and items (Outline yes, Template no, tag runs, empty vs whitespace title).
2. The stamper's "no spec found", distinct from "fully stamped".
3. A gate refusal for a missing identity probe.
4. The identity probe is the instrument's, out of the builder's reach.
5. The boot clock starts at launch, so building inside `start.sh` counts against the budget.
6. Scoreboard rates: one decimal, half to even; a zero denominator reports "n/a".
7. The self-check reports a missing tool as a loud skip, not a failure; a golden is required where its cycle exists.
8. Config completeness and the sibling-module check: carried or dropped with a recorded reason.

## Questions raised by R2 (decided 2026-11-06)

1. **Budget exhausted** (outer cycles, a stage timeout, continuations): the cycle fails with reason `budget`, the same shape as the gate-attempt limit.
2. **Gate refusals** spend no attempt; they are bounded by the gate stage's timeout, and three consecutive infrastructure refusals fail the cycle with reason `infrastructure`.
3. **A gate that passed without exercising the product** is the score's to catch, not the gate's (legacy README: the gate proves the suite green and says nothing about whether the suite exercises the product).
4. **The self-check key** covers the approved grader digests; the capture policy waits for runtime evidence.
5. **A golden whose scenario was reworded** fails the self-check, as legacy's oracle fails on any difference; the fix is to re-record it with a logged reason.
6. **An empty-title heading** is not an item (legacy @harness-021); the stamper reports it loudly.
7. **The same selection** means the same selected slices, by name, across cuts; whole-plan cycles compare with the last measured whole-plan cycle.
