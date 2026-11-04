---
status: accepted
---

# Promote manually approved first-grade evidence as the product oracle

Initial generation has no accepted product oracle from which to derive a comparison verdict.
Instead of fabricating a grader before any product has been observed, ORC independently verifies
the first candidate, drives every approved scenario through the public seam, and records one
complete baseline grade. Only a complete passing denominator with passing instrument conformance
may become an oracle proposal.

The proposal contains normalized expected evidence rather than grader code or raw candidate
transcripts. It is bound to the approved selection, full scenario digests, candidate subject,
judge identity, denominator, and a stable proposal digest. ORC pauses after recording it. An
operator must approve that exact digest before an independent publisher creates the immutable
product-oracle OCI artifact. Approval of stale or changed evidence is refused.

Later regeneration collects fresh evidence through the same public seam and compares it with the
promoted oracle. Re-recording is never an automatic response to a red comparison; changed expected
behavior requires a new approved specification and a separately reviewed oracle promotion.

## Consequences

- Initial generation is independently measured rather than recorded as ungraded lineage.
- No product oracle is required before the first candidate is built; the calibrated instrument
  oracle remains required before builder execution.
- Baseline grade or oracle evidence never enters the builder or verification-repair loop.
- A failed baseline grade or instrument-conformance check may trigger another bounded attempt,
  but that attempt is a fresh generation from the approved cut with no candidate or evidence input.
  Each fresh sample receives the same bounded verification-repair loop before evaluation.
- Failed, missing, inconclusive, or non-conformant evidence cannot be proposed or promoted.
- The generated candidate becomes routable lineage only after exact manual approval and immutable
  oracle publication.
- The product oracle is expected evidence. Probes collect actual evidence and generic validators
  compare it; neither is itself the oracle.
