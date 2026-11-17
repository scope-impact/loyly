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

Two questions sort them. Is the guardrail input to a round, or is it pointed at
what the round produced? Does it tell the agent correct or incorrect, or does it
hand back material and leave the verdict to the agent? Spec-lock and the scope
fence are prohibitions issued before a round starts, the orient step hands back
material with no verdict attached, ratified DONE and the adversarial review judge
what a round produced, and the human-realistic check is the only row where anyone
looks at the running product. Read that way the table is **normative-heavy and
perception-light**: strong at telling an agent whether it is right, weak wherever
an agent is given something to look at and has to supply its own judgment.

| Guardrail | What it does |
|---|---|
| Spec-lock | edits the agent makes to spec/plan files during a build round are reverted; specs change in planning rounds, on purpose, not mid-build |
| Orient step | every round starts by re-reading the sources of truth, not by resuming from memory |
| Ratified DONE | a DONE claim counts only if an independent verify command passes; "done" is a claim, not a state |
| Scope fence | edits outside the slice's allowed paths are reverted; a constraint on what you edit is not a constraint on your side effects |
| Adversarial review | every Nth round reviews instead of builds, hunting for invented rules and spec drift |
| Human-realistic checks | verification drives the real interface the way a resident would; an unreachable path is a finding, not something to force |

The weak side is a step nobody has built here: nothing in the build loop hands the
agent an unjudged look at the running product. What would fill it is a perception
step, screenshots of the real surface at two widths handed back with no verdict
attached and a question rather than an assertion, so the agent supplies the
judgment. Two limits belong in the proposal rather than in the eventual
post-mortem. A screenshot harness has no software keyboard, so a control that
hides under one is still found only by a person holding a phone. And the fuller
pipeline this method was distilled from ran such a step, judged by a separate pass,
and retired it because judging cost several times what generating cost against a
capacity limit that could not be bought through. So the step has to be rebuilt
inside a budget agreed in advance: what share of a cycle perception may take, and
what gets judged by machine, by a person, or not at all.

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

## 5. Durability: a round outlives the process running it

Every rule above is a rule about rounds, and a loop that holds its position only in
memory loses that position when it dies. Restarted by hand, it can run the interrupted
round a second time on top of the first attempt's half-finished edits, skip the round
without the history ever saying so, or carry on without resetting the context, and then
the orient step has quietly stopped happening. None of these failures makes a sound. A
build loop that runs unattended is going to die mid-round sooner or later, so its
position is a record, written down at every stage of a round before acting on that stage:

- **Where the cycle is**: the phase, the round, the TDD state, and the stage the
  round in flight had reached (opened, context reset, input submitted). A restart
  continues from that stage. It never repeats an earlier stage, and never repeats the
  context reset in particular, because a second reset would throw away the work the
  crash interrupted.
- **What the agent did**: every model turn and every tool call, recorded before
  anything is shown or acted on. An action that was cut off is reported back to the
  agent as interrupted, and is never silently run again. The exception is an action
  that is safe to repeat, such as running the gate.
- **A ratified DONE**: written down by whatever ran the gate, as soon as the gate
  passes, so that a crash between the gate passing and the loop noticing it loses nothing.

The record belongs to one cycle and must not cross the fence. A regeneration that can
read the previous cycle's transcripts can copy the previous cycle's answer. This repository
keeps that record where the fence's clone never reaches it. The loop that keeps it lives
in `agent/`, and its README names the stack it is built on, so that this document does not.
