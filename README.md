# Löyly

> **Löyly** (noun): the steam or vapour that rises when water is thrown onto the hot
> stones of a sauna stove. By extension it also refers to the heat and humidity of the
> sauna itself, which is why you can talk about good or bad löyly.

Löyly is a sauna-slot booking system for Nordic housing cooperatives. But the app is not
the point. This repo is a small, complete, working demonstration of a way of building
software in which **documentation is the source of truth and code is a build product**:
the implementation is deliberately disposable, and deleting and regenerating it is not a
catastrophe but a measurement. What this repo actually ships is a specification you can
build from and an instrument that tells you how good the build was.

Everything in it is real. The domain documents are redacted transcriptions of a real
Finnish housing cooperative's 2022 email threads plus verified public sources from
Finland, Sweden and Norway. Nothing is invented scaffolding, and that is a rule with an
enforcement point, not a preference (see `docs/wow/invariants.md`, INV-1).

## The story this repo is built around

In 2022, a resident of a Finnish housing cooperative booked a standing sauna slot,
Tuesdays at 17:00. The booking became three things maintained by hand: a line on the
posted slot list, a programme on the stove timer, and a charge on the monthly invoice.
Five months later two of the three had been changed and one had not, and the resident
stood in a cold sauna at a time they still believed was theirs.

Nobody malfunctioned. The three representations were never one thing, so they drifted.
The incident is in this repo verbatim
(`raw/domain/soukanvuori/2022-work-order-103016-and-drift.md`), and it is the whole
argument in one image: **when the same fact lives in several hand-kept copies, the copies
diverge, and the failure is silent until someone is standing in the cold.** That is as
true of documentation and code as it is of slot lists and stove timers. This method is
what taking that seriously looks like.

Two rules fall straight out of the story:

- **One record, derived views.** In the app: the booking record is the single source of
  truth, and the list, the timer schedule and the billing signal are readings of it,
  never copies. In the repo: the docs are the single source of truth, and the code is a
  reading of them.
- **One product, many houses.** Every documented cooperative runs the sauna differently
  (different slot lengths, different caps, different bookers, different billing). The
  model must express each as configuration; the first documented house whose rules
  cannot be expressed is a design bug, not an exception to hard-code.

## How to read the repo

Read in this order. Each layer is derived from the one above it, and nothing may drift
from what precedes it.

| order | path | what it is |
|---|---|---|
| 1 | `raw/` | the sources: real booking mail, work orders, public house rules, one interview. Immutable: never edited, only cited |
| 2 | `docs/wiki/` | the domain, distilled from the sources. Every claim footnotes the source it came from. Start at `index.md` |
| 3 | `docs/wow/` | the method itself: the way of working, five invariants each with a named enforcement point, and the agent harness |
| 4 | `docs/plan/01-booking-core/` | one slice of work: a plan, an executable Gherkin spec, tickets, and recorded decisions |
| 5 | `.pi/model/` | the executable contract for regeneration, grading, runtime evidence, and the Pi control plane |
| 6 | `.pi/harness/` | the active ORC/Pi harness. Start at its `README.md`; Pi loads its tools from `.pi/settings.json` |
| 7 | `impl/` | the build product. Intentionally empty: ORC regenerates it from a pinned cut |
| 8 | `archive/legacy-harness/` | the retired shell harness, preserved only as a behavioral-parity reference |

Two conventions you will notice everywhere:

- **Every document cites another document.** A wiki page cites a raw source, a plan cites
  wiki pages, a ticket cites spec scenarios, a decision cites the plan it diverged from.
  A claim that rests on nothing is not finished.
- **The docs never name a stack.** No language, framework or database appears anywhere
  under `docs/`. You could build Löyly as a web app, a mobile app, or a phone-in voice
  service without changing a line of them.

## The tutorial: one full loop

This is the flow the repo exists to demonstrate. Pi is the operator surface, ORC owns the
workflow, Gondolin provides isolated execution, and the OCI instrument oracle independently
calibrates and grades the generated application. See [the harness boundary](docs/orc-harness-boundary.md)
for the ownership rules.

### 0. Validate the harness

```bash
cd .pi/harness
npm ci
npm test
./orc-worker/check.sh
npm run test:e2e
```

The unit suites are the fast development loop. The end-to-end suite exercises the modeled
control plane, real Gondolin rooms, OCI oracle fixtures, grading, and Allure evidence. Some
environment-dependent scenarios skip when their external runtime is unavailable; a skip is
recorded and is never treated as a pass.

### 1. Build from the docs

Read the slice (`docs/plan/01-booking-core/`): eight Gherkin scenarios covering the
weekly schedule, standing-slot booking with reasoned refusals, and the
single-source-of-truth rule from the story above. In a Pi session, ask the harness to:

1. `prepare_source_cut` from an explicit Git commit and execution backend;
2. `preview_slice_selection`, then `approve_slice_selection` for the intended scope;
3. `submit_approved_slice` with the approved selection, target stack, and backend. The
   approved operation selects a Fresh Slice Generation Cycle with no parent or accepted
   product oracle, or a Measured Slice Regeneration Cycle with named measured lineage;
4. `wait_harness_operation` and inspect the operation receipt and any returned evidence.

The checked-in `.pi/settings.json` loads these tools automatically when Pi opens this
repository. `host` is useful for harness development; measured regeneration uses the
`gondolin` backend.

### 2. Ratify and measure

ORC runs one of two fixed contracts:

```text
Fresh Slice Generation:
cut → sealed harness snapshot → fenced build → verify/repair
    → Grade [build + calibrate + seal cycle oracle → black-box grade]
    → oracle proposal

Measured Slice Regeneration:
cut → sealed harness snapshot → fenced build → verify/repair
    → Grade [accepted product-oracle comparison] → measurement
```

The builder never declares itself done. Verification and grading run outside its session,
against a read-only candidate. The checklist remains derived from stable `@item-*` scenario
IDs, while runtime evidence records whether the expected application path actually occurred:
browser exchanges, trace spans, domain events, projection snapshots, invariant results, and
runtime diagnostics. Evidence and reports are written under `.pi/harness/.state/`; they are
generated artifacts and are not committed.

### 3. Regenerate

Start another cycle from the same pinned specification and oracle digest. ORC creates a
fresh cut and a fresh Pi/Codex builder session; Gondolin exposes the cut read-only and
limits writes to the declared workspace scope. The previous implementation, transcript,
grader evidence, host credentials, and host environment are not available to the builder.

### 4. Study the delta

Score the regeneration with the same checklist and compare. Every difference between the
two builds is information: a rule the docs failed to carry, a decision the spec left
open, an invention that crept in. **The delta measures the spec, not the builder.**

### 5. Fold the learning back

Whatever the delta taught you goes into `raw/` (if it is new source knowledge),
`docs/wiki/` (if it is domain), or the slice (if it is scope), as a recorded decision.
Never only into the code: the next regeneration deletes the code, and anything living
only there dies with it. That destructive step is not a cost of the method, it is the
mechanism: it forces learning into the documents, because nothing else survives.

Then cut, regenerate, and measure again. On a production project this loop runs weekly.

## What to steal

If you take nothing else from this repo, take these five. The retired implementation and
the stories behind these rules remain in `archive/legacy-harness/README.md`:

1. The checklist is **derived** from the spec, never written by hand.
2. A "done" claim is **ratified** by a script the claimant does not control.
3. The fence is **physical and proved**, not an honour system.
4. The instrument **refuses to record a measurement it did not make**, and an unreached
   item is a fail, never a smaller denominator.
5. Learning that is not written into the spec **dies with the implementation**, on
   purpose.

Good löyly.
