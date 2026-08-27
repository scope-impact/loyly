# The Agent Harness

How an AI agent executes this method without drifting. Two ideas carry it:
**plan and implement are separate invocations**, and **"done" is ratified,
never self-asserted**. Cites: `docs/wow/way-of-working.md` (the gate),
`docs/wow/invariants.md` (INV-5).

## 1. Two-phase prompting: plan, then implement

Never one prompt that does both. Phase one is a planning invocation: the
agent studies the docs (read order below), compares the plan against the
spec and any existing code, and updates the slice plan and tickets. It is
told explicitly: **plan only, do not implement anything in this phase.**
Phase two is a separate implementation invocation that receives the plan as
input and works one ticket at a time. Splitting the phases keeps the plan
from being retro-fitted to whatever the agent happened to build.

## 2. RED/GREEN discipline

The implementation loop runs under an externally tracked TDD state, and the
state instruction overrides everything else about what to do next:

- State **GREEN**: write the next failing test from `spec.feature`. Do not
  implement features. Do not look ahead.
- State **RED**: write the minimum implementation that passes. Do not write
  new tests.
- Refactor only when green, and only then commit.
- **Retroactive tests are forbidden.** Implementation before test is a
  failed round, not a shortcut (this is what enforces INV-5).

## 3. Guardrails

Each guardrail exists because agents drift in a specific, observed way.

| Guardrail | What it does |
|---|---|
| Spec-lock | edits the agent makes to spec/plan files during a build round are reverted; specs change in planning rounds, on purpose, not mid-build |
| Orient step | every round starts by re-reading the sources of truth, not by resuming from memory |
| Ratified DONE | a DONE claim counts only if an independent verify command passes; "done" is a claim, not a state |
| Scope fence | edits outside the slice's allowed paths are reverted; a constraint on what you edit is not a constraint on your side effects |
| Adversarial review | every Nth round reviews instead of builds, hunting for invented rules and spec drift |
| Human-realistic checks | verification drives the real interface the way a resident would; an unreachable path is a finding, not something to force |

## 4. Pointing your own agent at this repo

Give your agent this read order, in this order, before any task:

1. `README.md` (what this repo is, where things live)
2. `docs/wow/` (this method: way-of-working, invariants, harness)
3. `docs/wiki/` (the domain, starting from its index)
4. `docs/plan/` (the slices; the current one is what you work on)

Then state the task and the phase (plan or implement). If the agent
proposes code and no slice covers it, the correct move is a planning phase
that writes the slice, never "just this once". `raw/` is background: the
agent may read it to check a trace, and must never edit it.
