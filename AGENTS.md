# Löyly Agent Instructions

The maintained assets are the behavioral specifications under `model/`; generated
implementation is a reproducible build product. Keep specification planning and
implementation in separate rounds. Never repair a specification from inside a fenced
builder run.

## Pi and ORC ownership

- Pi, Codex, or another operator session explores intent, composes the system view,
  proposes a slice, and presents the exact deterministic selection for human approval.
- ORC executes only an immutable approved contract. It owns bounded prompts, constrained
  tools, retries, termination, verification/repair, grading, and durable evidence.
- Exploratory transcripts, skills, branches, prior candidates, and grading evidence do not
  cross into a fresh builder unless an explicit workflow contract permits that input.

## Workflow names

Use these names consistently in code, specifications, evidence, and operator messages:

- **Fresh Slice Generation Cycle**: starts without a parent candidate or accepted product
  oracle. It prepares a fresh source cut and a sealed harness snapshot, builds a fenced
  candidate, verifies it, and enters Grade. Grade builds a fresh candidate-blind cycle
  oracle from the approved cut, calibrates and seals it, black-box grades the complete
  denominator, and produces an oracle proposal.
- **Measured Slice Regeneration Cycle**: starts from a named measured parent candidate and
  an accepted product oracle, then collects fresh evidence for comparison.

Do not call fresh generation `initial generation`, `baseline generation`, or
`regeneration`. In code and artifact identities prefer `fresh-generation` and
`measured-regeneration`.

## Fresh artifacts and fences

- The **source cut** is immutable product/specification input visible to the builder.
- The **harness snapshot** is built fresh from one committed harness revision, calibrated,
  digest-sealed, and hidden from the builder except for the public application contract.
- The **cycle oracle** is built from the approved cut and public calibration protocol only.
  Its author cannot read the candidate, builder/verifier transcripts, previous verdicts,
  or host credentials. Applicable behavioral, affordance, and runtime-conformance checks
  are calibrated and sealed as one bundle before it grades the candidate.
- A cycle oracle is provisional. Only a complete passing grade followed by manual approval
  of the exact proposal digest can publish an accepted product oracle and promote the
  candidate to measured lineage.

A fresh cycle must not require a pre-existing product oracle, a published instrument
oracle, or a manually configured validator directory. Remote artifact publication may cache
or distribute sealed artifacts, but it is never a prerequisite for fresh generation.

## Ratification

The builder cannot declare completion. Verification and grading must drive the real public
seam from fresh state, preserve the approved denominator, and report failed, missing,
unreached, or inconclusive items explicitly. A failed independent evaluation may start only
a new clean sample from the approved cut; evaluation evidence never becomes builder input.
