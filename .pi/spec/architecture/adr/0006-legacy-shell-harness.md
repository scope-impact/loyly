# 0006 The legacy shell harness drives cycles and builds the new harness

## Status

Accepted (2026-11-13). Replaced by `cycle.v1` (ADR 0001) once its shadow comparison passes.
Lifts "Self-hosting stopped" (`../../decisions.md`) for this one regeneration.

## Context

The promoted cycle runner (slice 06, cycle 08) ran Löyly's run-03 (gate passed, 11/11). Its
rebuilds since have not reached promotion: cycle 09 not promoted, cycle 10 red on the gate in
three attempts, cycle 11 stopped after 4h17m never green (`harness/v2/cycles/06/11/STOPPED.md`).
Meanwhile the legacy shell harness (`harness/` and the shell runbook that follows its steps)
drove the batch of contexts 01 to 05 unattended. A harness that builds its own replacement would
put the old instrument in charge of judging the new one.

## Decision

The legacy shell harness, not ORC, drives every cycle until `cycle.v1` runs, and it builds the new
harness: one ORC tree from all six contexts' specs (ADR 0001), in one cycle.

```text
fence.sh → builder.sh (pi in Gondolin) → gate.sh (≤3, repair) → black-box grading → score.sh
```

The cycle runner (`harness-impl/06-cycle-runner/`) drives nothing and is not rebuilt. The
prototype (`proto/orc-harness`) is evidence for the new harness's spec, not a driver. The legacy
cycle builds the harness; the harness builds the products.

Order:

1. Slice 06's scenarios and seam are rewritten as `cycle.v1`, and the relay, hand-in methods and
   handoff contracts of 01 to 05 are removed (ADR 0001), approved in a spec round.
2. The one tree, through the legacy shell harness.
3. The new instrument is compared once with the previous one (the promoted build or the legacy
   harness) on the same candidates before it is used (AGENTS.md, "The instrument judging itself").

## Consequences

- Nothing is regenerated before step 1: building against today's slice 06 rebuilds the old runner.
- The shell harness is provided, edited directly; its fixes do not wait for a spec round.
- The accepted build is written into `harness-impl/` with its `PROVENANCE.md` naming the legacy
  cycle that produced it.

## Evidence

- Batch at `ecac349`, commit `3448def`: 01 15/15, 02 40/43, 03 11/11 (gate attempt 2), 04 76/76,
  05 4/4; built three at a time at 12G and 4 CPUs.
- Cycle 11: 183 tool calls, 30.3M tokens, stopped at 15:16:58Z.
