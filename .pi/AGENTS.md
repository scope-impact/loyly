# Pi Harness Engineering Rules

## Architecture

ORC/Clojure is the workflow engine and owns all regeneration behavior. It runs one cycle in
the legacy shape (see the root `AGENTS.md`):

```text
fence → regenerate → gate → score → scoreboard
```

The fence and the gate are the harness and feed the build loop; the score and the scoreboard
are the instrument and never do.

TypeScript is adapter code only. It may provide:

- Pi/Codex sessions;
- Gondolin VM, VFS, and network capabilities;
- narrow process/IPC bridges required to expose those capabilities to ORC.

TypeScript must not own workflow state, stage ordering, retries, scoring, or prompt
construction.

## Project configuration boundary

Project-specific execution and build configuration lives at the repository root under
`harness/<project-id>/` (for example, `harness/loyly/`). This is the canonical location for
each project's VM configuration and supported-stack Dockerfiles; derive conventional paths
from the project ID and stack instead of adding a per-project `harness.json`. Product truth
and behavioral contracts stay under `model/<project-id>/`, and project-agnostic harness
configuration stays under `.pi/harness/config/`. Do not create a second project-config tree under `.pi/harness/config/projects/`
or `projects/<project-id>/`.

## Domain model boundary

Grain is the source of truth for Löyly's event-first application model; Allium is the source
of truth for observable behavior. Grain's Event Model describes CQRS topology, not a generic
regeneration hierarchy:

```text
Allium behavior + Grain Event Model
  → generated/implemented application
  → Grain domain events and projections
```

Do not create a parallel `scope.edn`, component tree, or item registry. Löyly's existing
`docs/plan/**` slices, `Rule:` groups, and stable `@item-*` IDs remain the regeneration and
measurement keys. Cross-references may be derived, but must not become a second source of truth.

Keep event streams distinct:

- Löyly domain events describe the product.
- ORC events describe regeneration execution.

Use Grain's command → validated event → projection path; never bare event-store appends.
Use strict Grain Event Model validation to reconcile declared topology with live registries.

## Regeneration boundary

- The cut is pinned before a cycle starts.
- The harness self-check must pass before any clean room is built; a passing self-check is
  reused only for an identical harness digest.
- The fence is physical and proved: prior implementations, measurements, reports, graders,
  calibration fixtures, and the grading harness are absent from the clean room and its history.
- `/cut` is read-only.
- Builder writes are limited to the declared `/workspace` scope.
- Builder sessions use Codex through Pi and never inherit the root Pi transcript.
- Host credentials and host environment are not mounted into Gondolin.
- The gate and the score are independent of the builder.
- Gate repair is ORC-owned, limited to three regenerate attempts, and uses a fresh Pi/Codex
  builder session for each attempt.
- A repair builder may receive the preceding candidate plus that attempt's gate evidence
  root read-only. It must never receive score evidence.
- The score starts only after the candidate passes the gate.
- Supported target stacks select language-specific offline Gondolin builder rooms and are
  independently checked by the gate; unknown or mismatched stacks fail closed.
- Generated output is persisted only from an ORC stage.

## Instrument lifecycle

- The grader is authored by an independent author from the selected Gherkin and the public
  calibration protocol only. It drives only the candidate's public seam and must not read
  candidate source, builder transcripts, prior measurements, or gate implementation.
- A grader is calibrated against carried and uncarried behavior and sealed by digest. An
  uncalibrated grader is discarded and re-authored from the failing calibration cases, at
  most three attempts.
- Only an operator approves a sealed grader, and only its exact digest. A cycle refuses to
  score with a grader nobody approved.
- Reuse one approved grader across cycles until its scenarios change. A failing score never
  replaces a grader; a replacement records its reason (instrument behavior changed, wording
  replaced, or spec grew with additions only).
- The scoreboard compares cycles on shared items in both directions and never computes a net.

## Migration rule

The original Löyly harness is archived at `archive/legacy-harness/` and remains the
behavioral parity reference while its behavior moves into ORC. Do not rewrite it as if it
were active or silently weaken the reference until ORC output matches it, including
refusal, skip, timeout, and evidence behavior.

## Prompt rule

Builder instructions are ORC/GEPA data. Do not construct production prompts in TypeScript.
Codex receives the prompt selected by the ORC workflow.

## Failure classification and GEPA eligibility

Classify independent evidence before changing the model, grader, prompt, or generated
candidate. These layers have different responsibilities:

```text
Allium/Gherkin       observable product behavior
Grain Event Model    command → event → projection topology and invariants
runtime evidence     proof that the declared runtime path occurred
builder prompt       instructions for interpreting and implementing the contracts
generated candidate one implementation produced from those inputs
```

Use the following evidence rules:

- If the specification, model, and grader disagree about an observable result, treat it as
  a specification/model or grader defect. Resolve the disagreement and
  recalibrate before running GEPA.
- If a declared command, event, aggregate, projection, or invariant is absent or
  contradictory, correct the Grain Event Model. Do not compensate in the builder prompt.
- If a clear, calibrated contract is missed by one generated implementation, record a
  candidate defect. One sample is not enough to conclude that the prompt is defective.
- If multiple independent candidates repeatedly miss the same clear contract, or a prompt
  revision improves the measured result without changing the model or grader, classify it
  as a builder-prompt defect and make it eligible for GEPA optimization.
- If carried or uncarried calibration fails, stop before the builder. Repair the instrument
  or grader rather than optimizing against it.

GEPA optimizes implementation reliability for a coherent, calibrated contract. It must not
be used to resolve ambiguity between Allium/Gherkin, Grain, runtime-evidence contracts, and
black-box graders. Feeding ambiguous grader feedback to GEPA teaches the builder to satisfy
an accidental measurement rather than the accepted behavior.

For example, a released slot represented as `available: true` while a grader recognizes only
`status: free` is not prompt feedback until the accepted wire contract establishes which
representation is required. By contrast, choosing a healthcheck executable that is absent
from the declared offline image is initially a candidate/build defect; it becomes prompt
feedback only when it recurs across candidates.

## Cleanup rule

Keep generated state under `.pi/harness/.state/`; it is ignored and disposable. Do not add
model credentials, transcripts, VM outputs, or generated applications to Git.

Run focused checks from `.pi/harness` with `npm test`, and the legacy self-check with
`harness/check.sh --local` from the repository root.
