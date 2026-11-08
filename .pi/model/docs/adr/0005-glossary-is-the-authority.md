---
status: accepted
---

# The glossary is the authority for the domain's words

> **Spec v1, kept as history.** The maintained spec is spec v2 in `.pi/spec/` ([README](../../../spec/README.md)); the harness that runs the cycles now is in its decisions, ["The switch to the new harness"](../../../spec/decisions.md#the-switch-to-the-new-harness-decided-2026-11-08).

## Context

The model's identifiers used words the glossary's own `_Avoid_` lists rule out. Formerly:
the cycle and its stages were named for a pipeline; rooms had four names; a cut was formerly a
"regeneration cut" and a task formerly a "harness task"; the builder's purpose and role had
four vocabularies, and the execution backend and profile were formerly two modes;
technology names stood in for domain terms (the VM, the reflection provider, the trace tool,
the telemetry standard, the launch script, the uncarried-items file); and trigger names mixed
imperative, past, and present tense. About 110 helper predicates were used but
never declared, some invariants restated a `transitions` block or only prose, and the grader,
the builder session, and the instruction candidate had states nothing reached.

## Decision

1. `GLOSSARY.md` is the authority for domain terms. Allium and Grain identifiers,
   `.pi/architecture.md`, and the ADRs use its terms. An `_Avoid_` item is a synonym for that
   term; an avoided word that is itself a term is a cross-reference to it.
2. Where the seam (`affordances.yaml`) fixes a value, the seam's value is the published
   language: the model uses it verbatim and the glossary names it (`workspaceGeneration`,
   the backend values `host` and `gondolin`, the profile values, and the purpose values
   `fresh-generation` and `verification-repair`). The seam is not renamed.
3. One vocabulary per concept: the builder role is `BuilderSessionRole` (`slice_builder`,
   `repair_builder`, `grader_author`) everywhere, production prompts included; the
   instruction purpose is the seam's; the execution backend is the one execution concept, and the
   execution profile is the policy over it (which backends a preparation admits), not a
   second mode. Rooms are the builder room, the gate room, and the validation room, which ORC
   starts; the clean room is the read-only cut a room mounts.
4. Technology stays out of domain names unless the seam fixes it; `.pi/architecture.md`
   ("Technology bindings") binds each domain term to its technology.
5. Triggers: an operator or ORC command is imperative, a reported fact is past tense.
6. A helper a rule or invariant reads is declared as a typed `contract` signature with its
   meaning, and the surface that relies on it `demands` it. An invariant is a state predicate;
   one that only restated a `transitions` block or a rule's own `requires`, or only prose, is a
   `@guarantee` or a contract `@invariant` instead.
7. `tools/spec/glossary-lint.py` enforces the `_Avoid_` lists over the Allium, the harness's
   Grain Event Model, `.pi/architecture.md`, and the ADRs; `tools/spec/gates.sh` runs it. A
   line that says "formerly" or "avoid" may name an old term. The Gherkin in `plan/` is
   reported, not failed: an accepted scenario changes only with the product owner's say-so.

The main renames:

- formerly `RegenerationCut`, `PrepareRegenerationCut`: now `SourceCut`, `PrepareSourceCut`.
- formerly `SelectionItem` and the instrument's `ChecklistItem`: now one value, `StampedItem`.
- formerly `HarnessTask` and its kind, status, and rules: now `WorkflowTask`,
  `StartWorkflowTask`, `CompleteWorkflowTask`, `FailWorkflowTask`, `WaitForWorkflowTask`.
- formerly `PipelineStageDidNotSucceed`, `FailPipelineStage`: now `CycleStageDidNotSucceed`,
  `FailCycleStage`; the import alias, formerly `pipeline`, is now `execution`.
- formerly `BuilderInstructionPurpose { regeneration | gate_repair }` and
  `ProductionPromptRole`: now `{ fresh_generation | verification_repair }` and
  `BuilderSessionRole`.
- formerly `IndependentValidationRoom`, `OtelCapturePolicy`, `BrowserTraceArtifact`: now
  `ValidationRoom`, `CapturePolicy`, `TraceArtifact`.
- formerly `answered_by_launched_process`, `image`, `prompt_digest`, `runtime_context_digest`:
  now `answered_by_booted_candidate`, `target_stack`, `production_prompt_digest`,
  `runtime_task_context_digest`.
- formerly present-tense or `Orc`-prefixed facts (`OrcRendersProductionPrompt`,
  `OrcBuilderTickStarted`, `StartRegenerationCycle`): now `ProductionPromptRendered`,
  `BuilderTickStarted`, `DispatchedTickStarted`.

## Consequences

- The lifecycles close: `AuthorGrader` creates a grader from a grader author's session,
  `RetireReplacedGrader` retires an approved grader its approved successor replaces, a
  `BuilderSession` ends completed or failed (`EndBuilderSession`), and an instruction candidate
  is rejected (`RejectInstructionCandidate`) or superseded by a later winner.
- `SubmitCycle` requires that the preparation's execution profile admits the submitted
  backend, as the seam's `SubmitApprovedSlice` states.
- The seam's methods, params, values, and responses are unchanged; one `allium:` reference
  follows a rename (`WaitForWorkflowTask`).
- The Gherkin still uses some avoided words; the lint lists them for the product owner.
