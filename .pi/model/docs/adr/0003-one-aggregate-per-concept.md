---
status: accepted
---

# One aggregate per concept, a wired cycle, and no operator-issued facts

## Context

An audit of the model found three kinds of defect.

- **A broken cycle.** `StartCycle` emitted `FenceRequested`, which nothing consumed;
  the second cycle entity (formerly `PipelineCycle`) was never created, and its start rule
  (formerly `StartPipeline`) listened for a different fact; no rule made a cycle terminal or set its candidate digest. `FileScore` emitted a fact whose
  shape `RecordScore` could not accept, a refused score stranded the cycle in `score`, and
  `BindSuccessfulBaselineTrace` listened for a name its surface did not provide.
- **One concept, several models.** The cycle was `RegenerationCycle` and a second entity
  (formerly `PipelineCycle`); the measurement had four models, filed by two rules
  (formerly `PipelineMeasurement`, `PipelineObservation`, `ScoreVerdict`,
  `DurableMeasurementReceipt`); the builder session had three models
  (formerly `PipelineBuilderSession`, `DurableBuilderLaunch`, `BuilderSessionReference`). Verdict,
  authority, and purpose enums, an invariant, and an event were declared twice. One trigger
  name carried two signatures, and submission idempotence was checked in two rules.
- **Authority leaks.** The Grain screen could issue task receipts, observations, and
  measurements; the execution surface had no actor, so anyone could report a gate or a score;
  the measurement commands took opaque maps and read nothing they guard.

## Decision

1. One cycle aggregate, `RegenerationCycle`, owned by `regeneration-cycle.allium`, with a
   status (submitted, running, passed, failed) and a stage (fence, regenerate, gate, score,
   scoreboard, result). `SubmitCycle` creates it at the fence; ORC starts it (`StartCycle`);
   `execution-pipeline.allium` moves it through its stages by state triggers, and the result
   rules make it terminal with its candidate digest. A refused score fails the score stage.
2. A workflow task (formerly `HarnessTask`, now `WorkflowTask`, ADR 0005) is not a cycle: a task may prepare a cut. It stays its own
   aggregate; the control plane opens a cycle's task from the created cycle (`OpenCycleTask`).
3. One measurement, `CycleMeasurement`, owned by `instrument.allium`, holding a pass or fail
   verdict per checklist item (unreached is fail). Only `FileScore` files it; unknown items,
   non pass/fail verdicts, and empty returns are refused. Durable measurement receipts are a
   projection (the Grain read model `cycle-measurements`).
4. One builder session aggregate, `BuilderSession`, owned by `execution-pipeline.allium`, keyed
   by its ORC tick and bound to its cycle and attempt. The operator's view of it is a
   projection (the Grain read model `builder-sessions`).
5. `runtime-evidence.allium` alone owns `EvidenceVerdict` (an invariant evaluation can be
   inconclusive), `ProducerAuthority`, `EvidencePurpose`, `EvidenceProjectionCreated`, and
   `EvidenceProjectionIsNotAuthority`. A baseline grade has one name, `BaselineGradeCompleted`.
6. One trigger per name: the domain act (formerly `PrepareRegenerationCut`) lives in
   `regeneration-cycle.allium`; the control plane's cut task is `SubmitCutPreparationTask`.
   Submission idempotence lives only in `SubmitCycle` and `ApproveWholePlanBySubmission`.
7. `actor OrcRuntime` is declared once, in `regeneration-cycle.allium`. Every fact ORC or the
   provided builder host reports reaches a surface facing it; every surface faces an actor.
   Rules chain by state triggers or by events other rules emit, never through an actor-less
   surface.
8. The Grain screen issues operator intents only. Task receipts, observations, the filed score,
   and workspace generation bindings are issued by todo processors reacting to ORC's facts or to the
   builder host's committed tool result, over legal read model → query → processor paths. The
   score command, `file-score`, is typed per item and reads the selection, preparation, and
   approved graders it guards. Every `tick-id` is a `:uuid`, as in ORC.

## Consequences

- "Each concept is declared once" holds, and `COVERAGE.md` lists the owner of each.
- A cycle can be followed from submission to a terminal status in the specs alone, and no
  refusal leaves it in a non-terminal stage.
- Only an approved sealed grader's output, reported by ORC, can become a verdict; neither the
  operator nor a builder can inject a gate result or a score through any surface or screen.
- The seam (`affordances.yaml`) is unchanged except for the rule names its references resolve
  to, so the sealed graders stay valid.
- Names still to be aligned with the glossary (formerly `HarnessTask`, for example) are left for a later
  round.
