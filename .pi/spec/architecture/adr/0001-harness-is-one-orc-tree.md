# 0001 The harness is one ORC tree on one store

## Status

Proposed (2026-11-13). Prototype rounds 1 and 2 on `proto/orc-harness` support it; accepted with
slice 06's rewrite.

## Context

ORC is used today only to run behaviour trees over an event store. Its model nodes
(`llm`, `repl-researcher`), judge, self-improving loop, resume and telemetry exporter are unused:
the builder and grader are pi or Claude sessions launched as outside programs, verdicts are
handed in, and a run cannot resume. Slice 06, the cycle runner that drives the cycle, has the
largest seam and the hardest builds: cycle 09 was not promoted, cycle 10 failed the gate three
times, cycle 11 ran 4h17m (183 tool calls, 30.3M tokens) and never went green. Rounds 14, 16 and
16b went mostly into making builds follow ORC's rules rather than into using ORC's outputs.

The product owner's direction (2026-11-13): the whole cycle is one simple ORC tree, using ORC's
features thoroughly, with pi as the model.

## Decision

The harness is one workflow, `cycle.v1` (`../README.md`), on one Grain store. The contexts 01 to
05 stay the domain (their scenarios, Allium rules and event models); their commands are leaves of
the tree, and the one event model is the union of theirs. There is no relay, hand-in method or
handoff contract between contexts.

- **The build and the repair are pi-durable `code` leaves** in Gondolin, their session id
  derived from the tick, so a stopped run resumes the same pi session. The builder instruction is
  rendered by an `llm` node before them, which is what GEPA tunes (ADR 0004).
- **Grading's driver is a `repl-researcher` and the judge a code leaf** (ADR 0003). Model calls go
  to pi through a small OpenAI-compatible shim over pi-ai on the Codex login, registered as
  `(llm/register-provider! :pi-codex {:provider :openai :config {:api-base … :timeout 600000}})`.
- **Tools in the VM.** The nodes' `:mcp-tools` (a shell and files) run in Gondolin through the
  `:tool-caller-fn` gate (ADR 0002).
- **Code leaves** for the commands of 01 to 05 on the one store: pin, self-check,
  fence, stamps, checklist, gate steps and score.
- **`delegate` and versioned workflows** for a gate attempt (`gate-attempt.v1`) and the judge
  (`grade.v1`, ADR 0003).
- **`resume-in-progress!`**: a run that stopped resumes where it was.
- **The telemetry exporter** to OpenTelemetry (ADR 0005).

## Consequences

- Slice 06's spec is rewritten as this tree. Its effects E4 (regenerate), E6 (repair), the
  verdicts hand-in and the relay change. Its stamps, delegated gate attempts, wait bounds and a
  leaf per effect carry over.
- The contexts 01 to 05 keep their domain; their own trees, stores, relay, hand-in methods and
  handoff contracts go. This supersedes "Spec round 5"'s relay and round 17's tree per context.
- A context cannot be rebuilt on its own: a change anywhere regenerates the tree, and one build
  carries all six specs. Event names must not collide in the one event model.
- `resume-in-progress!` resumes `code` leaves only: a run stopped while a researcher is in flight
  stays stuck, a known gap until ORC resumes researcher nodes.
- One new provided part, the shim. pi's packages stay provided and are never regenerated.
- ORC's pin may move forward: later ORC adds model fallback on `llm` and researcher nodes and
  native judge rubrics (ObneyAI/orc, 2026-11-05 to 2026-11-10; the pin is `af086675`).

## Evidence

- No public example of ORC driving pi (web and ObneyAI's repositories, 2026-11-13). The one
  local example is `loyly-orc-clean-run/.pi/harness/orc-worker` (2026-11-03): pi as an outside
  program, `resume-in-progress!`, OTel evidence; no model nodes, no judge.
- litellm-clj's `openai_compatible` provider takes `:api-base` and passes tools
  (`ObneyAI/litellm-clj`, `src/litellm/providers/openai_compatible.clj`).
- ORC's load cost at the pin: requiring `orc-service.interface` takes 7.8 s on the host (Clojure
  alone 0.5 s, with Grain 3.5 s); it loads `sci` and `litellm` (about 280 namespaces).
- Prototype `proto/orc-harness` (a8c12b0 to 5b3389e, FINDINGS.md):
  - pi/Codex as ORC's model through the shim; `:mcp-tools` and `:tool-caller-fn` reach Gondolin.
  - Toy build: ORC researcher 2 model calls, 13.4k input tokens uncached; pi-durable leaf 4 model
    calls, 3.4k input plus 2.6k cache-read, 17.6 s with pi's VM boot. Same gate pass, 2/2.
  - Resume: killed 5 s into the pi session, `resume-in-progress!` re-ran the leaf in the same tick
    and pi resumed its session (1 turn before, 5 after); the cycle completed 2/2. A run killed
    during grading stayed stuck: `runtime.clj` resumes `:leaf` nodes only.
  - pi-durable allows one writer per store: a crashed run's host must be dead before resuming.
  - One tree on one SQLite ORC store, one trace of 19 spans; slice 05 graded 4/4.
- No single build of all five contexts has been tried; separately they reached 01 15/15,
  02 40/43, 03 11/11, 04 76/76, 05 4/4 (ADR 0006).
