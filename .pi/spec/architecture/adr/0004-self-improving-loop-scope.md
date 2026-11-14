# 0004 GEPA improves the builder and the grader, per project context

## Status

Accepted (2026-11-13). The product owner: "GEPA will improve builder and grader per project
context."

## Context

ORC's learning path starts from a judge score: score, Living Description, GEPA reflective
dataset, a new instruction candidate, Pareto selection (ORC `docs/JUDGE-ARCHITECTURE.md`). Three
rules kept it out:

- The builder instruction is frozen and generic; GEPA is not run on it (`../../decisions.md`,
  "Spec round 16b" D5).
- Score evidence never becomes builder input (`AGENTS.md`, "Ratification").
- The score and the scoreboard report to a person, never into the loop (`AGENTS.md`, "The
  regeneration cycle").

## Decision

ORC's self-improving loop, with GEPA, optimizes two instructions separately for each project
context (Löyly's contexts 01 to 06, Halla Health's `wallet-consent`, and so on):

- **The builder instruction**, rendered by the `llm` node before the build and repair leaves
  (ADR 0001). GEPA collects instructions only from `llm` and `ai` nodes, not from a `code` leaf's
  parameters.
- **The grader instruction**, the instruction of the judge and of the researcher that drives the
  seam (ADR 0003).

Each is a versioned instruction: a winner is published as a new, immutable workflow version
(`gepa/apply-winner!`), and a cycle names the version it uses.

Guards, part of the decision:

- **The builder's metric is the judge's score on items GEPA does not see**: held-out items per
  context, the untuned holdout the scoreboard already counts.
- **The grader's metric is calibration, never the score**: agreement with known verdicts on
  calibration candidates (Grader lifecycle's instrument goldens and sealed fixtures, where
  carried behavior passes and uncarried behavior fails). So a grader is never tuned to turn a
  score green.
- **A new grader version is used only after the operator approves it**, and only for one of
  Grader lifecycle's recorded reasons: instrument behavior changed, wording replaced, spec grew.
- **Between cycles, never inside one**: the judge's scores feed GEPA between cycles. A score or a
  steer never enters a running build.

## Consequences

- Supersedes round 16b D5 (the instruction frozen, GEPA not run).
- Supersedes `AGENTS.md`'s "score evidence never becomes builder input" and "the instrument
  reports to a person, never into the loop": the judge's scores now feed GEPA between cycles.
- Each project context carries its own instruction versions; a cycle's provenance names them.
- Held-out items and calibration candidates must exist per context before its loop runs.

## Evidence

- Steer triage (`.pi/harness/bin/triage-steers.sh`) after cycle 11 and the batch: recurring
  lessons were found by hand (gate in a VM, a suite budget, a command wait bound); the loop is to
  find such lessons from scores.
- Prototype `proto/orc-harness` round 2: GEPA ran its full loop through the pi shim, none of it on
  OpenRouter (evaluated runs and reflection use the context's `:llm-provider`; the judges the
  global `*judge-provider*`, set with `alter-var-root`). The seed scored 0.5; no proposal beat it
  within 6 calls. One evaluation is a whole pi build in a VM (16–48 s): 7 builds took 456 s, so a
  real context's loop is slow and costly.
