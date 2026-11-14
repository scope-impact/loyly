# 0003 The judge scores checklist items

## Status

Accepted for direction (2026-11-13). The product owner: the expectation is to use ORC's judge
"exactly what we are doing". Its build waits on ADR 0001.

## Context

The instrument's rule was graders in a functional language other than the product's, sharing no
code or runtime with what they measure. The Elixir graders were retired (`cb8f5c7`); since then a
black-box agent drives the candidate's seam and its verdicts file is handed in
(`cycle-runner/hand-in-verdicts`, legacy step 5). ORC has a judge (`criteria × stance × scale`,
discrete bands, reason before score) whose scores are events (`:judge/score-emitted`).

## Decision

Grading is ORC's: for each checklist item, a `repl-researcher` drives the candidate over its
public seam only (ADR 0002) and records what it set up, sent and observed; the judge scores the
item `pass` or `fail` on that evidence, the reason before the score. The researcher reads the
seam and the event model (the seam names no parameters), and each item starts with its evidence
cleared. Every checklist item is filed; one with no score files as an unreached fail. The checklist item is the
criterion. An unreached item fails and stays in the denominator, as before.

Independence comes from black-box access (the seam only, never the candidate's sources) and a
model other than the builder's. This supersedes the rule that graders use another functional
language.

A sealed grader is a published judge workflow version (`grade.v1`) with its rubric's digest:
Grader lifecycle's goldens and their moves (three recorded reasons) apply to it unchanged.

## Consequences

- `cycle-runner/hand-in-verdicts` goes when slice 06 is rewritten; Measurement's
  `file-item-verdicts` takes the judge's scores.
- The judge shares ORC with the ORC contexts it scores; a defect in ORC could hide in both. The
  seam-only access and a different model are the guard.
- Calibration (carried passes, uncarried fails) is still owed before a judge version is relied on.

## Evidence

- The batch's host graders (Claude, black-box) scored 01 to 05 at `ecac349`: 15/15, 40/43,
  11/11, 76/76, 4/4.
- ORC's `docs/JUDGE-ARCHITECTURE.md` and `docs/EVALUATION-COMPONENT.md` at `af086675`; native
  judge rubrics added upstream on 2026-11-10 (`e7d8fb57`).
- Prototype `proto/orc-harness` (FINDINGS.md):
  - Slice 05 graded 4/4 in 239 s, matching the known result. Earlier tries scored 2/4, 2/4, 2/4
    and 3/4 until two fixes: evidence leaked between `map-each` items (a clear-evidence leaf per
    item), and the researcher guessed `cut` for `cut-id` (the event model added to its input).
    The judge failed every bad run and passed none.
  - The judge is a `code` leaf built from ORC's tier-1 pieces (a binary `discrete-scale`,
    `build-tier1-instruction`, `gate-banded-output`), recording `:judge/score-emitted`. ORC's
    built-in judges have sealed 1–5 scales and attach asynchronously, so they do not gate a cycle.
  - The item id must be in the judge's name: scores are keyed by sheet, node, tick and name, so a
    second `map-each` item's score is otherwise dropped.
  - `:level` must be specced `:int`; a constrained spec fails marker parsing through the shim.
    Built-in judges work through the shim only when it is registered as `:openai`.
