# 0007 Repair a bounded selection on an accepted build

## Status

Accepted for the cycle-25 repair (2026-11-17). The operator's "Do it" approved the bounded
repair described in `harness/v2/REPAIR-06.md` after the accepted cycle-23 runner and its two
open items were identified. The repair's spec, selection, base and sealed grader digests are
pinned there. No repair cycle had started at acceptance.

## Context

Cycle 23's complete runner passed its gate, scored 59/61 with a clean profile, and matched the
legacy harness on one same-cut instrument shadow. Cycle 24 rebuilt all of slice 06 from an
empty room and regressed to 31/61. The operator wants a slice that fixes only the broken
behavior while preserving the accepted rest. The current fence excludes prior candidates,
and the 61 stamped items all belong to one cycle-runner context. A new bounded context for
two unrelated failures would add a seam and assembly work without isolating shared code.

## Options considered

| Option | Benefit | Cost or risk |
| --- | --- | --- |
| Full fresh regeneration of 06 | Existing clean-room contract; simple provenance | Repeats all 61 behaviors and has regressed working code |
| Extract new contexts around the failures | Independent later builds if genuine domain boundaries emerge | 047 and 066 have different responsibilities; the one-tree runner still needs assembly changes |
| Repair selection from an accepted base | Builder effort targets two behaviors; accepted code is pinned and auditable | Prior source deliberately crosses the fence, so the exception and full regression evidence must be explicit |

## Decision

Define a **repair slice** as a deterministic selection of existing stamped items within one
accepted context. It creates a new candidate version of that *whole context*; it is not a new
context or a partial promotion. For the first repair slice, select `06-047` and `06-066` on
the current spec, with the complete accepted cycle-23 artifact as a read-only base pinned by
its 39-file source digest `947d49609d24e2325fcb027a695b03c3b68c2b9cd9337a3401d39cf58df5231a`.

The instrument fences the current spec cut as usual, then places the pinned base in a
separate read-only `repair-input/accepted-base/` mount. This is the sole prior source
the builder may read. The builder copies it into a new candidate and changes code only to
carry the selected items. It receives the current spec and the two selected IDs, but no
prior grader transcript, verdict file, or score details. It records a file-level diff and
reason for every source change. The accepted base is never edited in place.

The gate runs the candidate's complete suite and independent public-seam probe. A separate
grader scores **all 61 items**, using the approved sealed grader; the protected 59 items must
have no newly failing verdict. The profile must be clean. Because 06 is the instrument, run
the candidate against legacy on the same cut and app before acceptance. Only then may the
new complete artifact replace `harness-impl/06-cycle-runner/` with provenance naming the
base digest, repair selection, source diff, gate, full score, and shadow.

The exact repair selection, spec cut, base digest, and grader digest require operator approval
before the repair room is created. Round 26's first-request clarification is part of the
selected 066 repair. A changed event-model or seam contract outside the selected behavior
invalidates this path and requires a fresh full-context regeneration or a separately approved
migration.

## Consequences and evidence

- The builder's task is bounded to 047 and 066; the acceptance denominator remains 61.
- This is an explicit exception to `AGENTS.md`'s fresh-builder exclusion of prior candidates.
  The exception applies only to the pinned accepted base and the approved repair selection.
- Before first use, prove that the repair room contains exactly the current spec and pinned
  base, that the base remains unchanged, and that an unrelated file edit is visible in the
  source diff. The instrument preflight must compare the new contract with legacy's repair
  and acceptance behavior.
- A repair slice cannot be promoted alone. The result is one gated, fully graded runner.

## Rationale

The accepted build provides a stable starting point and makes regressions against 59 known
passing items observable. The extra dependency on accepted source is intentional and
auditable. Full grading and same-cut shadow protect against a narrow repair damaging the
instrument. If those checks show coupling that prevents a bounded repair, return to a full
regeneration or design a real context boundary; do not hide that coupling by moving item IDs.
