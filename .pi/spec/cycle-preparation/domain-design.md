# Cycle preparation: domain design

status: proposed, 2026-11-06 (spec v2, R1). R1 holds only the legacy part of this context:
the source cut. From the storm (`../event-storm.md`, Prepare and approve).

## Files

| Module | Event Model | Aggregate | Lives |
|---|---|---|---|
| Context | `event-model/area.edn` (screen `preparation`, flow `cut-to-fence`) | none | |
| Source cut | `event-model/source-cut.edn` | **SourceCut** | the harness's event store; one per cut |
| Handoffs produced | `event-model/handoffs.edn` (`source-cut-pinned`) | | consumed by Measurement and the Regeneration cycle |

## Design decisions

1. **The cut is its own aggregate, immutable once pinned.** Everything later binds to its
   commit and tree digest: the checklist (by `cut-id`) and the cycle (its self-check names
   the cut).
2. **Room left for the later round.** Slice proposal, selection contract, selection and
   preparation approval, submission and receipt are added here as new blocks; the
   `cycle-submitted` handoff will mint the cycle identity (H8, proposed answer) and be
   added to `regeneration-cycle/regeneration-cycles`' `:consumes`. Nothing in R1 is renamed
   by that.

## Storm events here

| Storm event | Became |
|---|---|
| ◆ Source cut pinned | event `cycle-preparation/source-cut-pinned` (handoff) |

## Refusals for R2

`pin-source-cut`: a cut identity pinned twice with different content; (later) a tree that is
not the commit's tree.

## Open questions

- **H35** (can wait): does an unapproved preparation, or an unused cut, expire?
