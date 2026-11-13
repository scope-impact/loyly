# Supervising a builder or a grader

Provided, hand-written, never regenerated (like `../bin/builder.sh`). The supervisor is the
operator's Claude Code session, or a Claude subagent it starts: no program of its own and no
judging model of its own. It watches a running pi-durable session (a builder from
`../bin/builder.sh`, a grader from `../bin/grader.sh`) through three scripts, judges it itself
(progressing, stalled, looping, off scope, done), and has three powers: **report, stop, steer**.

| Script | What it does | Writes |
|---|---|---|
| `../bin/watch.sh <evidence-dir> [--since <cursor>] [--tools N] [--stall-minutes N] [--at <iso-time>]` | A compact digest of the session's store, read-only (`node:sqlite`, `readOnly`; SQLite's locks keep a reader safe beside the writer): state and elapsed time, entries and tool calls since the cursor, tokens, later inputs (steers, grader rounds), minutes since the last entry, a tool call still running and for how long (a long suite or batch is not a stall), test runs (a `test.sh` or `-M:test` segment of a bash call, heredoc bodies ignored; green or red by the `clojure.test` tally, else by exit code), the last green run, repeated calls, the last N tool calls with exit and first output lines, and `cursor=<id>` to pass back as `--since`. `--stall-minutes N` blocks, polling every 30 s, until no entry for N minutes or the session ended, then prints `STALLED` or `ENDED` and the digest: run it in the background to be woken. `--at` replays a recorded store as of a time. | nothing |
| `../bin/steer.sh <evidence-dir> <message-file>` | Puts the message into the running builder through pi-durable's own steer (below). Refuses a grader, an ended launch, and a launch without a steer inbox. Waits up to 60 s for the host's answer. | `<evidence-dir>/steers.jsonl`: time, launch, name, request id, cursor, the message verbatim, its sha256, outcome |
| `../bin/stop.sh <evidence-dir> <reason>` | Stops a builder or a grader by its own recorded pids: SIGINT to the wrapper (`builder.sh`/`grader.sh` forward SIGTERM to the host), then after 60 s, or at once with no wrapper, SIGTERM to the host. Checks first that the host pid's command line names `host.ts` and this launch's store. Never by name, never SIGKILL. The run settles unanswered (`aborted`); the same session id resumes it. | `<evidence-dir>/stops.jsonl` |

`<evidence-dir>` is a launch directory or a directory of `launch-N/` (the latest is taken).

## How a steer reaches a running session

pi-durable allows one writer per store ("One process writes a storage at a time; there is no
cross-process locking", its README, "Storage"), so nothing writes the SQLite store from outside.
The builder host is the writer: with `LOYLY_STEER_DIR` (which `builder.sh` sets to
`launch-N/steer/` and records as `steer_dir=`), `../builder-host/host.ts` polls that directory every
5 s while its run works and submits each new `<name>.md` to its own session with pi-durable's
documented busy-conversation input, `root.submit({ type: "input", content, requestId:
"steer-<name>", whenBusy: "steer", whenIdle: "reject" })` (README, "Busy Conversations": "Steers
are placed after the current tool round and join the running work"). The request id makes a
rerun never steer twice; `whenIdle: "reject"` means a steer never starts a run of its own. The host
answers with `<name>.steered` (the submission id) or `<name>.refused` (why). The steer lands in the
store as a `pi.user` entry with the message verbatim, and `watch.sh` counts it.

Proof, offline (no live model): `cd ../builder-host && GONDOLIN_GUEST_DIR=<image> node
steer-smoke.ts ../.state/steer-smoke`: a faux-model run sleeps in the VM, `steer.sh` steers it,
the steer is the store's `pi.user` entry verbatim, the model's next turn sees it, `steers.jsonl`
records it, and `steer.sh` refuses an ended launch and a grader.

A builder launched before this change (runner cycle 10's, `builders/06-c10/launch-1`) has no steer
inbox and no recorded pids: `steer.sh` and `stop.sh` refuse it; `watch.sh` works.

## Guardrails (AGENTS.md: the clean room, the instrument, ratification)

- **Steer the builder only.** A grader is reported and stopped, never steered: steering the
  instrument would bias the score. `steer.sh` refuses a launch whose `launch.txt` says
  `kind=grader`.
- **A steer draws only on** the builder's own session (what `watch.sh` shows), the room's spec and
  the gate's public contract. Never on grading evidence, verdicts, scores, other candidates or
  prior cycles' transcripts: score evidence never becomes builder input. A steer never states what
  a scenario's answer is; it names a process problem ("the suite has been red for 40 minutes and
  the last 6 runs time out; run one failing test alone", "you are editing outside the declared
  scope") and leaves the product to the builder.
- **The builder still cannot declare completion**: the gate and the score decide, whatever the
  supervisor saw.
- **Every steer is evidence**: `steers.jsonl` and the store's `pi.user` entry. A cycle's steers
  are, with the gate attempts' failing lines, the input to `../bin/triage-steers.sh`: candidate
  spec inbox items a person takes into a spec round. The builder instruction is frozen and
  generic; a steer never becomes an instruction paragraph.
- **Stop** is for a session that is no longer worth its cost (stalled, looping on a red suite for
  hours, off scope); the reason goes in `stops.jsonl`. A stopped builder resumes from its store
  (`LOYLY_BUILDER_SESSION_ID`), as cycle 08's did in a larger VM.

## A supervising loop, as the Claude session runs it

```bash
E=.pi/harness/.state/builders/06-c11
.pi/harness/bin/watch.sh $E                       # first look; note cursor=
.pi/harness/bin/watch.sh $E --stall-minutes 20    # in the background: wakes the session on a stall
.pi/harness/bin/watch.sh $E --since <cursor>      # every ~10 min, or when woken
.pi/harness/bin/steer.sh $E steer.md              # only for a builder, only within the guardrails
.pi/harness/bin/stop.sh  $E "red suite for 5 h, every run times out in the 1 GB VM"
```

## Replays (2026-11-13)

`watch.sh --at` over the recorded stores, as a supervisor would have seen them:

| Session | At | Digest (key lines) | Judgement |
|---|---|---|---|
| `builders/06-c8/launch-1` (1 GB VM) | 09:30Z (2.6 h) | 27 test runs, 0 green, 12 red or timed out; last entry 12.6 min ago | stalled/looping |
| same | 13:19Z (6.4 h) | 59 test runs, 0 green ("last green: never, in 383.9 min"), 31 red or timed out; 54 M tokens | looping on a red suite: stop, resume in a larger VM (what the operator did at 13:20Z) |
| `builders/06-c9/launch-1` | 21:45Z (58 min) | 7 test runs, 1 green, latest 37 tests, 1 error | progressing |
| same | end (92 min) | 10 test runs, last two GREEN (39 tests, 227 assertions, 0 failures) | done |
| `builders/06-c9/launch-2` | end (30 min) | 2 test runs, both GREEN (39 tests, 273 assertions) | done |
| `builders/06-c10/launch-1` (live) | 06:57Z (85 min) | 21 test runs, 2 green, latest timed out; edits to the integration tests | progressing (report only) |
