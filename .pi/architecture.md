# Pi harness architecture

## The shape

The legacy harness regenerates a new harness from the Pi harness contract, the same way it
regenerated Löyly. The contract is treated as one more product spec; the output is a harness
built on ORC and Grain.

```text
  driver                      project                        product
  ───────────────────────     ─────────────────────────      ──────────────────────────
  harness/  (legacy tools)    .pi/model/                     .pi/harness/harness-impl/
    fence.sh  ─────────────►    plan/NN-slug/spec.feature  ─►  a harness on ORC + Grain
    Pi builder                  rpc.yaml (stdio seam)            ORC: the cycle, retries,
    verify.sh (gate)            *.allium, GLOSSARY.md                 resume
    sealed graders              Grain event model                Grain: facts that span runs
    score.clj, scoreboard.clj                                     (approvals, receipts,
                                                                   measurements)
```

`bb harness/regen.clj <run-id>` runs the loop end to end. ORC belongs only in the product. The driver keeps its records as committed files:
`harness/cycles/NN/` for each cycle and `harness/graders.approved.json` for the approved graders.

## The loop

```text
fence → regenerate → gate → score → scoreboard
```

| Step | Tool | Notes |
|---|---|---|
| fence | `harness/fence.sh` | A proved clean room holding only the contract; refuses unless `harness/check.sh --local` is green. `.pi/harness` and this file are fenced. |
| regenerate | `.pi/harness/src/builder-worker.ts` | One fresh Pi/Codex builder per slice, in plan order, each seeded with this cycle's candidate so far; continued in the same session, at most three times, while its own `UNCARRIED.md` lists items of its slice (`@item-02-014`). |
| gate | `harness/verify.sh` | Runs `APP_DIR/test.sh`, then proves identity over the stdio seam within 30 s. A failed gate may start a fresh repair builder with the previous candidate and the gate log, at most three attempts. |
| score | sealed graders, then `harness/score.clj` | Only graders whose digest is in `harness/graders.approved.json`. One verdict per checklist item; an unreached item fails and stays in the denominator. |
| scoreboard | `harness/scoreboard.clj` | Shared items with the previous cycle, newly passing and newly failing, never a net. |

`harness/config.sh` names the product: `APP_DIR=harness-impl`, `PLAN_DIR=.pi/model/plan`.

## The instrument

Three sealed graders cover 17 of 83 items, each calibrated so carried behavior passes and
uncarried behavior fails, and approved by the operator by exact digest:

| Digest | Entry | Items |
|---|---|---|
| `sha256:302072fd…` | `grade.py` | 02-001, 02-002, 02-010, 04-009, 04-010, 09-001, 09-002, 09-003 |
| `sha256:da4d43e5…` | `grade_seam.py` | 01-005, 01-006, 01-007, 04-008, 06-001 |
| `sha256:dac9f462…` | `grade_contract.py` | 01-010, 04-005, 04-006, 05-005 |

Sources are in `.pi/harness/instrument/`; the sealed copies are in `.pi/harness/.state/graders/sealed/`.

## Scoreboard

| Cycle | Run | Builder | Candidate | Score | vs previous |
|---|---|---|---|---:|---|
| 01 | `harness-regen-5` | gpt-5.6-luna | stub with canned stage results | 6/80 | baseline |
| 02 | `harness-regen-6` | gpt-5.6-luna | stub, 62 lines | 7/80 | +1 / −0 |
| 03 | `harness-regen-7` | gpt-6.1-sol | first real candidate | 8/80 | +1 / −0 |
| 04 | `harness-regen-8` | gpt-6.1-sol | real core on a fetchable ORC pin | 8/80 | +0 / −0 |
| 05 | `harness-regen-9` | gpt-6.1-sol | three items broken by a spec ambiguity | 5/82 | +0 / −3 |
| 06 | `harness-regen-10` | gpt-6.1-sol | one builder per slice; 17 graded items | 6/82 | +1 / −0 |
| 07 | `harness-regen-11` | gpt-6.1-sol | spec names the repository | 12/82 | +6 / −0 |
| 08 | `harness-regen-13` | gpt-6.1-sol | spec states slice-composition's shape; **promoted** | 14/82 | +3 / −1 |

Cycles 01–08 were driven by `orc-worker`, a hand-written ORC program that ran the same legacy
tools. It left the loop on 2026-11-05; git keeps it (last present at `358e4ca`).
`harness-regen-14` and `harness-regen-15` were stopped unscored.

## The promoted harness

Cycle 08's candidate (subject `sha256:f5e1611e…`) is at `.pi/harness/harness-impl/`, promoted
by the operator on 2026-11-05. Its own suite is green (25 tests). It passes 14 of 17 graded
items and lists 60 items uncarried in its `UNCARRIED.md`, among them sealed scoring, gate
repair, Gondolin and resume.

## Open

- Grader coverage: 17 of 83 items.
- Five `:grain/allium` links in the event model name Allium rules that no longer exist.
- `harness/config.sh` points at the Pi harness contract; regenerating Löyly needs its own config.
