# agent/: the build loop, durable

`docs/wow/harness.md` describes how an agent builds this repository without drifting:
plan and implement run as separate invocations, an external TDD state decides what comes
next, edits outside the slice get reverted, "done" counts only once the gate ratifies it,
and every Nth round reviews instead of building. Until now those rules existed only as
prose, followed by whoever was driving. This directory turns them into a loop, and makes
the loop **durable**: it records its position in a store, so a process that dies in the
middle of a round is picked up in that same round by the next process. The round is not
run twice and not skipped.

It is built on [`@earendil-works/pi-durable`](https://www.npmjs.com/package/@earendil-works/pi-durable)
(Pi's durable agent harness, experimental, pinned at 1.5.2), with
[`@earendil-works/pi-ai`](https://www.npmjs.com/package/@earendil-works/pi-ai) for model access.
pi-durable commits every model turn, tool call and document change to SQLite before
anything acts on it. The loop keeps its own position in one more document of the same
store.

## Why durability is a guardrail and not a convenience

A loop that keeps its rules in memory loses them when it dies, and it loses them silently.
Restart it by hand and one of three things goes wrong:

- the round that was cut off is run again, so a second agent invocation works over half of
  the first one's edits under a TDD state that no longer describes the tree;
- the round is skipped, and the history no longer records that it happened;
- the context is not reset, so the orient step ("re-read the sources of truth, not resume
  from memory") quietly never runs.

All three lie without a sound. That is the cold sauna again: the record and the world
disagree, and nothing notices.

## Running it

```bash
cd agent && npm ci && cd ..
export ANTHROPIC_API_KEY=...                         # or the key of the provider you name
node agent/run.mjs --model anthropic/claude-opus-5-5 # plan, then rounds, until ratified DONE
node agent/run.mjs status                            # where the cycle is, round by round
node agent/run.mjs reset                             # forget the cycle; the next run starts anew
```

Options: `--rounds N` (implementation rounds before the cycle is declared `exhausted`,
default 20), `--review-every N` (default 4; 0 turns review rounds off), `--max-turns N`
(tool rounds one round may take, default 40), `--store FILE` (default
`.loyly-agent/cycle.sqlite`). `--model` takes any `provider/modelId` that pi-ai knows; the
`LOYLY_AGENT_MODEL` environment variable sets the default.

Kill it however you like (Ctrl-C, `kill -9`, a lost machine) and run the same command again.

In a clean room, run it against the fenced clone: `./harness/fence.sh /tmp/clean-room`, then
`cd /tmp/clean-room/agent && npm ci && node run.mjs`. The fence clones the repository, and
the store is git-ignored, so one cycle's transcripts never reach the next cycle's room. A
regeneration that can read last cycle's conversation is copying, the same failure
`FENCED_PATHS` exists to prevent.

## The guardrails, as code

| guardrail (`docs/wow/harness.md`) | where | how |
|---|---|---|
| two-phase prompting | `loop.mjs`, `drive` | a plan conversation (it may edit only `docs/plan/`) runs once, then a separate build conversation runs. The plan's answer goes to every build round as input |
| RED/GREEN discipline | `measureTdd`, section `loyly-tdd` | the state is the verdict of `impl/test.sh`, measured by the loop after every round and never reported by the agent. The state's instruction goes into the system prompt from the stored document |
| orient step | `playRound` | each round starts by resetting the conversation's context, then tells the agent to read in the README's order |
| spec-lock and scope fence | `guardExtension`, `revertOutside` | `write` and `edit` outside the round's scope are blocked before they run. Whatever `bash` changed outside it is put back to `HEAD` after the round and listed in the history as `reverted` |
| ratified DONE | `claim_done` tool | runs `harness/verify.sh`. Only exit 0 counts, and that verdict is committed to the store by the tool itself |
| adversarial review | `kindOf`, `promptFor` | every Nth round gets only `read` and `bash` and has no scope, so any edit is reverted. Its findings go to the build rounds that follow |

The history records what the loop observed and does not judge it: a GREEN round that ended
GREEN without ratifying anything (no failing test was written), and a RED round that ended
RED. "Retroactive tests are forbidden" depends on the stack, which the loop cannot know, so
it is left to the review rounds and to the instrument.

## What survives a crash, and how

The round goes through stages, and the store records each one as it is reached:
`opened` → `reset` → `submitted` → closed (pushed to `history`, `pending` cleared, the
round counter advanced, all in one commit). On restart:

- a round still at `opened` gets its context reset. A round already at `reset` does not,
  because a second reset would discard the run the crash interrupted;
- the submission is sent again with the same `requestId`, and pi-durable returns the
  existing one rather than admitting a duplicate;
- inside the run, pi-durable continues from its last commit. A model request cut off
  partway counts as a failed attempt and is retried. A tool cut off partway returns an
  `interrupted` result to the model unless it is declared replay-safe (`claim_done` is:
  running the gate again is the correct recovery);
- the post-round work (revert, measure, record) is idempotent, so a crash during it
  repeats it without harm.

## Tests

```bash
cd agent && npm test
```

The tests run the loop against a miniature repository with pi-ai's faux provider, so they
need no API key. The crash test kills a real driver process with `kill -9` partway through
a round and starts a second process on the same store. That second process is scripted
with exactly one model answer, so if it repeated any earlier round it would run out of
answers and fail.

## Open, and stated rather than hidden

- **Not part of the harness ring.** `harness/` is one text across four repositories,
  checked byte for byte by `harness/modules.sh`. This loop is löyly's own and lives
  outside `harness/`, so the ring's check has no opinion on it. Whether it joins the ring
  is somebody's decision, not a tidy-up.
- **No commits.** "Refactor only when green, and only then commit" stays a human step.
  An automatic commit would run the pre-commit hook (`harness/check.sh`) inside every
  round, which is a choice about the hook's budget that has not been made.
- **No perception step.** The gap that `docs/wow/harness.md` names stays open: nothing
  here gives the agent an unjudged look at the running product.
- **One writer per store.** pi-durable has no cross-process lock. Two drivers on one
  store at the same time is undefined behaviour, so don't do it.
- **The driver refuses to start a new cycle** if the working tree has uncommitted changes
  outside `impl/` and `docs/plan/`, because the scope fence would revert them.
