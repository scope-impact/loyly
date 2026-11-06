---
status: accepted
---

# The cycle's stage facts are harness events

## Context

After ADR 0003 the Allium model holds the whole regeneration cycle, but the Grain Event Model
realised 27 of its 70 rules. The cycle's own facts had no Grain events: the harness self-check,
the clean room proved, a slice built, a candidate regenerated, a gate attempt, the checklist
derived, a grader sealed or discarded, the scoreboard reported, the cycle passed or failed, a
task completed or failed, and the prompt-optimization candidates. ORC runs the workflow that
produces these facts, so there were two places they could live:

- **Projections of ORC's node facts.** Harness read models would fold
  `:sheet/node-execution-completed` and read each node's `write-keys` to learn what a stage
  produced.
- **Harness events.** ORC's workflow nodes would issue harness commands carrying the typed
  fact, and the harness would record it.

## Decision

1. The cycle's stage facts are **harness events** in `:loyly.pi-harness`, recorded by
   commands that ORC's workflow nodes issue. ORC owns the workflow and decides when a stage
   runs; the harness's Grain area owns the facts and their invariants. Each command links the
   Allium rule it realises, reads the read models its rule guards, and is refused (a
   `requires`, not an event) when the guard does not hold. Examples:
   `record-harness-self-check`, `prove-clean-room`, `record-slice-build`, `record-candidate`,
   `record-gate-attempt`, `seal-grader`, `discard-grader`, `record-validation-room`,
   `bind-baseline-trace`, and the prompt-optimization facts.
2. A transition that an Allium rule makes on a state trigger is a **policy**: a todo
   processor reading a to-do query and issuing a command. `route-gate-verdict` (pass, repair,
   or fail after three), `route-filed-score` (scoreboard or a failed score stage),
   `report-cycle-scoreboard`, `close-reported-cycle`, `conclude-cycle-result` (passed or
   failed), `start-dispatched-cycle`, and `record-task-outcome`.
3. The one cycle aggregate has one read model, `regeneration-cycles`, keyed by `:cycle-id`
   (the cycle's request identity), folded only from harness events. Every ORC tick is a
   `:tick-id :uuid`.
4. Where the harness reacts to an ORC node starting or completing (launching a builder, filing
   the score, recording validation evidence, answering progress), it reads the
   **anti-corruption translation** `node-stages`: a read model over `:sheet/*` that yields the
   harness's `CycleStage` and builder role per tick and node. No processor reads a raw node id.
5. Rules that only read stay queries; rules whose effect is outside the harness's runtime
   (stamping and goldens are repository files, the boot guard is startup, a grader reuse is a
   request to ORC) are listed in `COVERAGE.md` with the reason.

## Why not projections of ORC node facts

ORC's node outputs are untyped: `write-keys` and `write-profile` are `[:map-of :keyword :any]`
in ORC's schemas. The harness's invariants need typed facts: the honest denominator
(`MeasurementDenominatorNeverShrinks`, `FailedDenominatorPreserved`), a gate that passes only on
an observed run (`GatePassRequiresObservedRun`), a clean room only after a passing self-check
for the same harness digest, and a sealed grader approved only by its exact digest. A
projection of untyped node outputs can neither refuse a fact that breaks them nor give the
fact a schema the boot guard can check. It would also make the harness's domain language
depend on how ORC names its nodes and keys.

## Relation to ADR 0002

ADR 0002 says ORC execution progress is projected from `:sheet/*`, never copied into a harness
event. That still holds: `node-stages` and the `cycle-progress` query are projections, and no
harness event mirrors a tick or node starting or completing. A stage fact is not progress; it
is a domain fact with a schema and an invariant, which ORC's node facts do not carry.

## Consequences

- 65 of the 70 Allium rules are linked from a Grain command, processor, read model, or query;
  the five that are not are listed with their reason in `COVERAGE.md`.
- The cycle can be followed in Grain from submission to a terminal status: `cycle-submitted`,
  `cycle-started`, `clean-room-proved`, `slice-built`, `candidate-regenerated`,
  `gate-attempt-recorded`, `cycle-stage-advanced`, `cycle-measurement-recorded` or
  `score-refused`, `scoreboard-reported`, and `cycle-passed` or `cycle-failed`.
- ORC's workflow must issue these commands from its nodes. That is ORC configuration of the
  harness's workflow (the sheet the harness hands ORC), not a change to ORC.
- The flows are now a few end-to-end slices (approval, cut preparation, cycle submission, the
  durable builder session, the cycle's harness half and instrument half, grader preparation,
  task outcome, runtime evidence, prompt optimization, child cycle) instead of one per block.
- The declared `:sheet` copy stays; its boot-guard risk and the upstream fix are recorded in
  ADR 0002 ("Known risk").
- Purpose names differ between Allium (`regeneration`, `gate_repair`) and the Grain and seam
  wording (`fresh-generation`, `verification-repair`); the seam is fixed, so the alignment is
  left to a later round.
