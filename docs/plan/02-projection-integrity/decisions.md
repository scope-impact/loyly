# Decisions: slice 02-projection-integrity

## D1: Preserve stable item identities across the new cut

The scenarios already existed as `@item-01-007` and `@item-01-008`. A slice is a
reviewable grouping, not the owner of an item's identity, so the IDs are not renamed.
The approved selection digest binds the current slice, scenario bodies, and those
stable identities.

## D2: No affordance is required for this slice

The required behavior is observable through the public HTTP interface and runtime
evidence. The project affordance contract applies only to `01-booking-core`; ORC must
record slice 02 as explicitly non-applicable rather than inventing a UI requirement.

## D3: Independent-evaluation failure requests a new clean sample

Black-box grader and instrument-conformance evidence never enters a builder prompt.
If independent evaluation fails, ORC may continue its loop only by starting another
fresh builder session from this approved cut. Candidate-local verification failures
may still use the candidate and verifier evidence for repair.
