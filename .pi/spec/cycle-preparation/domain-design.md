# Cycle preparation: domain design

status: proposed, 2026-11-06 (spec v2, R1 event model; R2 Allium and the product owner's
decisions, `../decisions.md`). The core holds only the legacy part of this context: the
source cut. From the storm (`../event-storm.md`, Prepare and approve).

## Files

| Module | Event Model | Allium | Aggregate | Lives |
|---|---|---|---|---|
| Context | `event-model/area.edn` (screen `preparation`, flow `cut-to-fence`) | `allium/context.allium` (contract `ProjectRepository`, open questions) | none | |
| Source cut | `event-model/source-cut.edn` | `allium/source-cut.allium` (rule `PinSourceCut`; surface `Preparation`) | **SourceCut** | the harness's event store; one per cut |
| Handoffs produced | `event-model/handoffs.edn` (`source-cut-pinned`) | emitted by `PinSourceCut` | | consumed by Measurement and the Regeneration cycle |

## Design decisions

1. **The cut is its own aggregate, immutable once pinned.** Everything later binds to its
   commit and tree digest: the checklist (by `cut-id`) and the cycle (its self-check names
   the cut).
2. **Room left for the later round.** Slice proposal, selection contract, selection and
   preparation approval, submission and receipt are added here as new blocks; the
   `cycle-submitted` handoff mints the cycle identity (H8) and will carry the approved
   **budget** (H5) and **grader digests** (H13). Until then ORC gives the budget to the
   cycle with its first self-check and the approved grader digests to the score, as input
   facts (regeneration-cycle and measurement domain designs). Nothing here is renamed by
   that.
3. **Whole-plan submission is the selection's approval** (H10): in the core every checklist
   is the whole plan at its cut, selection `whole-plan`.

## Storm events here

| Storm event | Became |
|---|---|
| ◆ Source cut pinned | event `cycle-preparation/source-cut-pinned` (handoff) |

## Refusals (the `requires`, numbered as in the Allium)

- `PinSourceCut`: 1 the cut identity is already pinned (immutable: with the same content or
  another, a second pin records nothing); 2 not a commit of the project repository; 3 the
  tree digest is not the commit's tree.

## Drafts (R3, 2026-11-06)

`drafts.feature`: 5 draft scenarios for the 6 must-cover obligations of this context
(`tools/spec/v2/coverage.py`: each rule's success, each `requires` that is behaviour, each
invariant, each contract invariant, each guarantee), every one tagged; 0 uncovered. Each
draft names its rule, file and the Gherkin `Rule:` it goes under, cites the legacy
`@harness-NNN` it preserves, and suggests a tier. Unit-level, not drafted (1): `PinSourceCut.1` (the guard of an idempotent creation), which guarantee `ImmutableOncePinned` and invariant `OneCutPerIdentity` still check through their draft.
The product owner accepts a draft by moving it into `spec.feature` (R4).

## Open questions

- **H35** (can wait): does an unapproved preparation, or an unused cut, expire?
- **H5** (part): confirm the approved preparation carries exactly the budget fields the
  cycle uses now (gate-attempt limit, outer cycles, a timeout per stage, continuations).
