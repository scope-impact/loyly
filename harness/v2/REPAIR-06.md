# Cycle-25 repair slice for the accepted cycle-23 runner

This is the approved workflow contract under ADR 0007. The operator said "Do it" after the
exact repair inputs were presented in this file. The same 61-item selection and sealed grader
digest were approved for cycle 24; neither changed. The legacy comparison and room-boundary
proof are recorded in `preflight/06/25.md`.

| Input | Pinned value |
| --- | --- |
| Accepted base | `harness-impl/06-cycle-runner/`, cycle 23 attempt 2 |
| Base source digest | `947d49609d24e2325fcb027a695b03c3b68c2b9cd9337a3401d39cf58df5231a` over 39 files, excluding caches and provenance |
| Repair selection | `06-047`, `06-066` |
| Full protected selection | 61 item IDs; ordered list SHA-256 `19b3d887e008a60ac9270c9a5845c2e5ecf0b9f0174439b7d8d7a91072a35c42` |
| Current spec.feature | SHA-256 `e3facb847152b9c183e8613fc5d0a394e84c3ee32f45f5582f0cc23c46e0774c` |
| Current sealed grader instruction | SHA-256 `677b1d9a7c8ac3c56602528a17abdd7450c21158bf90e5a48c156ab32553297c` |

The builder's room is the fenced current cut plus one instrument-supplied,
`repair-input/accepted-base/` copy of the pinned source. The builder may read that copy and
write only the new `harness-impl/06-cycle-runner/` candidate. The instrument verifies the
base digest before and after the build, records every candidate-source difference against
the base, and refuses any undeclared input or output. The builder receives the two selected
item IDs and their current spec, without prior verdicts or grader transcripts.

The independent gate runs the complete suite, fresh identity probe, and Grain store check.
The independent grader scores all 61 current items. Accept only if 047 and 066 pass, none of
the other 59 newly fail, the profile is clean, and a same-cut instrument shadow matches
legacy on the shared fixture. Promotion replaces the complete context and records both the
base digest and repair diff in provenance.

The accepted cycle-23 runner stays in service until a replacement meets all checks. Run the
cycle from the committed cut in this order:

1. Run `SLICE=06 harness/fence.sh .pi/harness/.state/rooms/06-c25`, then
   `python3 harness/v2/repair-base-06.py prepare .pi/harness/.state/rooms/06-c25` from this
   checkout. The first command must prove a single-commit clean room; the second must report
   the pinned 39-file digest. The room's `repair-input/` holds only `accepted-base/`.
2. Launch one fresh Astra builder in the background. Its only readable inputs are the room,
   its declared dependency caches, the two pinned git dependencies, Gondolin and its image.
   Tell it to copy `repair-input/accepted-base/` into `harness-impl/06-cycle-runner/`, read the
   current spec, and carry 06-047 and 06-066 with public-seam tests. The full regeneration
   prompt from `prompt.clj` is not used: it says there is no previous implementation and
   would contradict this approved repair contract. The builder writes only the candidate,
   runs its complete suite and fresh stdio identity, and reports remaining selected items.
   Continue that same session on its remaining selected IDs up to three times, stopping on
   no change, as the legacy `carry-slice!` rule does. It sees no previous verdicts or scores.
3. Run `python3 harness/v2/repair-base-06.py check ROOM` after each builder turn and keep its
   JSON source diff. Run the independent `harness/v2/gate.sh 06 ROOM`; a gate failure gets at
   most two fresh repair attempts under the current gate-repair contract.
4. Make a fresh grading room and use a different-model Terra grader under
   `grader-instruction-06.md`, exactly as `RUNBOOK-06.md` steps 5–6 require. Score every item
   once and retain the profile and surprises. The score is compared with cycle 23 on shared
   items: report newly passing and newly failing separately.
5. Before accepting the complete candidate, run the same-cut instrument shadow against
   legacy. Require 047 and 066 to pass, no newly failing protected item, a clean profile,
   and a matching shadow. The operator accepts the replacement with provenance naming the
   base, source diff, gate, score and shadow.
