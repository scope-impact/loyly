# The harness: regenerate, ratify, measure

A minimal, working demonstration of the machinery this method runs on, applied to the
sauna example. Distilled 2026-08-27 from a production pipeline that destroys and rebuilds
a full application from its specification every week, unsupervised, and scores the result
against a checklist derived from that same specification. Names, stacks, and deployment
plumbing from the original are deliberately absent; every mechanism that matters is here.

## The idea in five lines

1. A human sharpens the **spec**; at a fixed time it is **cut** (tagged, frozen).
2. An agent **regenerates the entire application** from the cut, inside a **fence**: a
   clean room where every prior implementation and every measurement is physically
   deleted from git history, so nothing can be copied, only rebuilt.
3. A **gate** ratifies the result: "done" is not the builder's claim, it is a script the
   builder does not control booting the app and proving the suite green.
4. An **instrument** scores the result, one item per spec scenario, and files the
   measurement with honest denominators.
5. **Anything not written into the spec dies with the implementation.** That destructive
   cut is the entire mechanism: it forces learning into the spec, because the code cannot
   carry it. The product is the spec and the instrument; the app is the weekly experiment
   that tests them.

## The pieces, in pipeline order

| file | role | the rule it embodies |
|---|---|---|
| `stamp_item_ids.py` | stamps `@item-NN-SSS` on every spec scenario | ids are assigned once and never derived from wording, so rewording a rule cannot break the week-over-week comparison |
| `gen_checklist.py` | derives the scoring checklist from the spec | the checklist is derived, never written; a hand-kept list goes stale silently |
| `fence.sh` | builds the clean-room clone and PROVES it | the fence is physical; a generation that can read last week's answer is not regenerating anything |
| `verify.sh` | the ratifying gate | a "done" claim is ratified by a script the claimant does not control |
| `score.py` | files verdicts against the checklist | zero verdicts is a grading run that did not happen, not a score of zero; an unreached item is recorded as `fail`, so a gap can never quietly shrink the denominator |
| `scoreboard.py` | the pass rate, cycle over cycle | both directions on the shared items, never the net; a moved denominator is restated out loud, and new items are counted separately because they are the one holdout nobody has tuned |

## Walkthrough

The spec is `docs/plan/*/spec.feature`; the app under test is whatever gets built in
`impl/`. The gate's contract with an implementation is two executables, so the docs never
have to name a stack: `impl/test.sh` (runs the whole suite, exits non-zero on any
failure) and `impl/start.sh PORT` (serves the app over HTTP on PORT).

```bash
# 1. stamp the spec (idempotent; only untagged scenarios change)
python3 harness/stamp_item_ids.py

# 2. derive the checklist for cycle 01
python3 harness/gen_checklist.py --out harness/cycles/01/checklist.json

# 3. build a fenced clean room (what a regeneration agent would be pointed at)
./harness/fence.sh /tmp/clean-room

# 4. ratify the current implementation
./harness/verify.sh

# 5. grade each checklist item against the running app, however you grade
#    (an agent driving the app, or a person with the spec in one hand), then file:
python3 harness/score.py 01 --verdicts my-verdicts.json

# 6. read the measurement
python3 harness/scoreboard.py 01
```

No cycle has been scored yet: producing the first `my-verdicts.json` honestly, by
actually driving the app item by item, is the exercise. The instrument refuses to record
a measurement it did not make, and so should you.

## What the full pipeline has that this demo elides

For a reader who wants the whole picture: the production version adds an unattended
multi-round generation loop with a deadline, per-round gate runs, a golden-fixture oracle
(one checklist item per ruled cell of a hand-worked calculation), per-item exclusions and
judged-mode overrides, automated deployment of every cycle to a public URL, a cycle
record that files what was decided, and the same spec built in three different stacks
with the instrument held still, so the stack is the only variable. Every one of those is
scaffolding around the five mechanisms above, which are the transferable part.

## The failure modes this shape was learned from

Each rule above exists because its absence produced a specific, silent lie:

- A gate once printed "verify OK" having never opened a browser, because a missing
  config made the test command a no-op that exits 0. Gates now assert their tools exist
  before trusting them.
- A test runner named its suites twice, once in imports and once in a run list, so a
  forgotten suite was silently green. Runners now discover suites from the directory and
  refuse an empty discovery (`verify.sh` does exactly this).
- An orphaned server from a dead run answered the health probe long afterwards, so a
  scoring run graded someone else's process. The gate now refuses to start when the port
  is already held (`verify.sh` [0]).
- A scoring run whose every batch failed on a network outage once committed "scored" over
  an empty record. The instrument now dies loudly instead: an unrecorded measurement is
  recoverable, a false record is not (`score.py`).
- A weaker fence probe once passed while the reference implementation was still readable
  one commit back. The probe now checks the object graph, not the working tree
  (`fence.sh`).
- A seeded holdback, a quarter of the items scored and never listed, with the gap between
  the visible and the hidden pass rates as the alarm, was built here and retired on
  2026-09-02. That gap measures the operator's repairs against the method alone, which
  means something only when a repair loop runs blind to the hidden items, and none ever ran
  here; the seed was committed too, so the set was unprinted rather than hidden. New
  scenarios are the holdout that needs no seed, and the scoreboard already counts them.
