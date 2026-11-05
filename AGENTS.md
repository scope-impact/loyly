# Löyly Agent Instructions

The maintained assets are the behavioral specifications under `model/`; generated
implementation is a reproducible build product. Keep specification planning and
implementation in separate rounds. Never repair a specification from inside a fenced
builder run.

## Pi and ORC ownership

- Pi, Codex, or another operator session explores intent, composes the system view,
  proposes a slice, and presents the exact deterministic selection for human approval.
- ORC executes only an immutable approved contract. It owns bounded prompts, constrained
  tools, retries, termination, verification/repair, grading, and durable evidence.
- Exploratory transcripts, skills, branches, prior candidates, and grading evidence do not
  cross into a fresh builder unless an explicit workflow contract permits that input.

## The regeneration cycle

ORC runs one cycle, in the shape of the legacy harness (`archive/legacy-harness/`):

```text
fence → regenerate → gate → score → scoreboard
```

- **Fence**: build a clean room from the approved cut and prove it. The fence is physical:
  every fenced path is absent from the working tree and the object graph, and a fenced path
  that matches nothing stops the fence. No room is built until the harness self-check passes.
- **Regenerate**: a fresh builder session rebuilds the selection from the clean room only.
- **Gate**: a room the builder does not control boots the candidate fresh and proves its
  suite green. Done is the gate's verdict, never the builder's claim. A failed gate may start
  a fresh builder with only the previous candidate and the gate's evidence, at most three
  attempts.
- **Score**: the approved sealed grader files one verdict per checklist item.
- **Scoreboard**: report the pass rate and compare with the previous cycle on shared items.

The fence and the gate are the **harness**: they feed the build loop. The score and the
scoreboard are the **instrument**: they report to a person, once a cycle, and never into the
loop. Anything not written into the spec dies with the implementation.

## The clean room

The clean room holds the spec, the domain sources, and the public application contract. It
holds no prior implementation, recorded measurement, report, grader, calibration fixture, or
grading harness. The builder reads it read-only and writes only its declared scope.

## The instrument

- The **checklist** is one item per stamped scenario of the approved selection. It is
  derived, never written; an unstamped scenario or an empty selection stops the cycle.
- The **grader** is written once from the selected Gherkin and the public calibration
  protocol. Its author cannot read any candidate, transcript, prior verdict, or host
  credential. It is calibrated so carried behavior passes and uncarried behavior fails,
  sealed by digest, and used only after an operator approves that exact digest. It is reused
  until its scenarios change and replaced only for a recorded reason: instrument behavior
  changed, wording replaced, or spec grew (additions only). Never replace a grader to turn a
  failing score green.
- The **score** is honest: zero verdicts mean no score was filed, and an unreached item
  fails and stays in the denominator.
- The **scoreboard** compares shared items, newly passing and newly failing separately,
  counts new items as the untuned holdout, and never computes a net.

## Ratification

The builder cannot declare completion. The gate and the score drive the real public seam
from fresh state, preserve the approved denominator, and report failed, missing, unreached,
or inconclusive items explicitly. Score evidence never becomes builder input; a failed score
is measured, not retried within the cycle.
