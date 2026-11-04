---
status: accepted
---

# Promote manually approved first-grade evidence as the product oracle

Fresh Slice Generation has no accepted product oracle from which to derive a comparison verdict.
The cycle first builds and calibrates a fresh sealed harness snapshot from the committed harness
revision. After independent verification, Grade asks a fresh external oracle-author agent to
derive a cycle oracle from only the approved cut and public calibration protocol. The author cannot read the candidate,
builder transcript, verifier evidence, prior verdicts, or host credentials. ORC calibrates the
result against carried and uncarried behavior, seals its behavioral grader and applicable
affordance and runtime-conformance evaluators to the cut, selection, harness snapshot, and
oracle-author model, and then drives every approved scenario through the candidate's public seam.
Only a complete passing denominator with passing instrument conformance may become an oracle
proposal.

The proposal contains normalized expected evidence rather than grader code or raw candidate
transcripts. It is bound to the approved selection, full scenario digests, candidate subject,
judge identity, denominator, and a stable proposal digest. ORC pauses after recording it. An
operator must approve that exact digest before an independent publisher creates the immutable
product-oracle OCI artifact. Approval of stale or changed evidence is refused.

Later regeneration collects fresh evidence through the same public seam and compares it with the
promoted oracle. Re-recording is never an automatic response to a red comparison; changed expected
behavior requires a new approved specification and a separately reviewed oracle promotion.

## Consequences

- Fresh Slice Generation is independently measured rather than recorded as ungraded lineage.
- No product or published instrument oracle is required before the first candidate is built;
  a fresh sealed harness snapshot is calibrated before builder execution.
- Grade or oracle evidence never enters the builder or verification-repair loop.
- Cycle-oracle authoring, calibration, sealing, and execution are internal responsibilities of
  Grade; they do not add workflow stages.
- Fresh candidate samples in one cycle reuse the same sealed cycle oracle. A changed binding
  input requires a newly authored and calibrated cycle oracle.
- A failed grade or instrument-conformance check may trigger another bounded attempt,
  but that attempt is a fresh generation from the approved cut with no candidate or evidence input.
  Each fresh sample receives the same bounded verification-repair loop before evaluation.
- Failed, missing, inconclusive, or non-conformant evidence cannot be proposed or promoted.
- The generated candidate becomes routable lineage only after exact manual approval and immutable
  oracle publication.
- The cycle oracle is a provisional, candidate-blind judging bundle. The product oracle is
  manually approved expected evidence produced from a complete passing grade; neither the fresh
  harness snapshot nor raw candidate evidence is itself the accepted product oracle.
