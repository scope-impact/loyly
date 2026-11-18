# factory/: cycles on demand, from a Slack channel

The method's weekly loop (cut the spec, regenerate in a fence, ratify, measure, fold
back), run by a machine instead of by hand. Someone with the right to do so writes
`run cycle` in a Slack channel, and in that message's thread they get:
- the plan,
- every round of the build,
- the review findings,
- the gate's ratified DONE, waiting for their `approve`,
- and the score.

It has three parts, each runnable and testable on its own:

| file | what it does |
|---|---|
| `run-cycle.mjs` | one whole cycle, resumable: fence a room, install, build with the durable loop (`agent/`), grade, score, keep the records |
| `grade.mjs` | the measurement the builder never sees: the checklist from the cut, and a separate headless Claude that drives the running app over HTTP and may not read the build |
| `dispatcher.mjs` | reads one channel, starts and supervises one cycle at a time, and restarts a runner that died |

```bash
node factory/run-cycle.mjs --cycle 03                 # by hand, no Slack needed
node factory/dispatcher.mjs                            # the front door (env below)
cd factory && npm test                                 # 5 tests, a whole cycle among them
```

## What a cycle is, stage by stage

`run-cycle.mjs` records each stage in `<workdir>/cycle-NN/state.json` before it starts the
next one. Run the same command again and it continues from where it stopped.

1. **room**: `harness/fence.sh` builds a clean room from this checkout's HEAD, the
   *cut*. The room has no `impl/` and no `harness/cycles/`, and the fence proves both are
   absent from its history.
2. **installed**: `npm ci` in the room's `harness/` and `agent/`.
3. **built**: the durable loop builds the slice with the Claude Code worker until
   `harness/verify.sh` ratifies a DONE, or the rounds run out. If the loop is killed, the
   rerun resumes the round it was in.
4. **graded**: the checklist is derived with this checkout's tools, never the room's
   copies. A separate Claude run grades every item against fresh instances of the app.
   A build that doesn't boot is graded all fail, without spending a grader.
5. **scored**: `harness/score.ts` and `harness/scoreboard.ts`, in this checkout.
6. **recorded**: everything the cycle produced is collected into
   `<workdir>/cycle-NN/records/`:
   - the loop's store and log,
   - the builder's and the grader's transcripts,
   - the verdicts and the scoreboard,
   - `state.json`.

## Isolation: the fence on the machine

The room has neither the previous answer nor this cycle's checklist, but the machine may
still have both: this checkout, other cycles' rooms, and earlier transcripts. So the
builder runs fenced off from them. `--isolation`, or `LOYLY_ISOLATION`, picks how:

| mode | how | where it fits |
|---|---|---|
| `user` | the builder runs as its own Unix user (`--builder-user`), who owns its room and can read nothing else | a server, which is what this README deploys. Needs the runner to run as root, and no special kernel privileges |
| `namespace` | the builder runs in a mount namespace where everything else is covered by empty filesystems | root with `CAP_SYS_ADMIN`, as in a Claude Code cloud sandbox. Default Docker containers do not have it |
| `none` | no isolation | tests only. The runner says so loudly and records it in `state.json`, because a cycle built this way may have read its own checklist |

## The thread is the interface

The dispatcher starts the runner with `SLACK_THREAD_TS` set to the message that asked.
`agent/slack.mjs` then reports into that thread and reads that thread. In it, people on
the allowlist can:
- **steer** the builder while a round runs;
- **leave a note** for the next round;
- reply **`approve`** or **`reject: <what is wrong>`** to a DONE the gate has ratified.

The dispatcher itself answers:

| message, at the top level of the channel | answer, in its thread |
|---|---|
| `run cycle` / `run cycle 03` | starts it; or says which cycle is already running; or that cycle 03 is already scored |
| `status` | the running cycle and its stage |

Messages from people not on `SLACK_ALLOWED_USERS`, and from bots, are ignored. There is no
default: a message starts an agent that runs shell commands.

The dispatcher keeps its own state in `<workdir>/dispatcher.json`, written before
anything it guards happens:
- **the channel cursor**, so a message is acted on once, across restarts;
- **the running cycle.**

On every tick it checks the running cycle. If the runner has died before the last stage,
the dispatcher starts it again and it resumes. After three restarts in a row, it gives up
and says so in the thread.

## Deploying

What it needs: a Linux VM that stays up, with Node 22, git, `runuser` and Claude Code.

1. **The Slack app.**
   - At api.slack.com/apps, choose *Create New App* → *From a manifest*, and paste
     `deploy/slack-app-manifest.yaml`.
   - Install it to the workspace and copy the bot token (`xoxb-…`).
   - Invite the bot to the channel: `/invite @loyly-factory`.
   - Note the channel ID, and the member IDs of the people allowed to run cycles.
2. **The builder's user**, who owns nothing else:
   `useradd --create-home --shell /bin/bash loyly-builder`
3. **Claude Code**, logged in twice:
   - as root, for the grader;
   - as `loyly-builder`, for the builder: `runuser -u loyly-builder -- claude setup-token`,
     or `claude auth login`.

   Each user's transcripts stay in its own home. The runner copies the builder's into the
   cycle's records.
4. **The checkout**, out of the builder's reach:
   ```bash
   git clone <this repository> /srv/loyly && chmod 700 /srv/loyly
   cd /srv/loyly && npm ci --prefix harness && (cd agent && npm ci)
   mkdir -p /srv/loyly-cycles && chmod 711 /srv/loyly-cycles
   ```
   The cycles directory is `711`. The builder can pass through it to reach its own room,
   but cannot list the others.
5. **The configuration**: copy `deploy/factory.env.example` to `/etc/loyly/factory.env`
   (mode `0600`) and fill it in.
6. **The service**:
   ```bash
   cp deploy/loyly-factory.service /etc/systemd/system/
   systemctl daemon-reload && systemctl enable --now loyly-factory
   journalctl -u loyly-factory -f
   ```
7. **The first cycle**: write `run cycle` in the channel.

## Not done yet, and said so

- **Pushing scores and records.**
  - A cycle's score is filed in `/srv/loyly/harness/cycles/NN/`, and its records under
    `/srv/loyly-cycles/`. Nothing commits or pushes them yet.
  - On a server that is durable on disk. It is not shared until somebody pushes.
  - Which branch the records belong on is the owner's decision.
- **Folding back.** A cycle's plan round rewrites the plan and the tickets inside the room.
  Those changes stay in the room's records until a person decides what to keep.
- **The one human-judged item.** `@item-01-011` is meant for a person who has not read the
  configuration. The grader approximates that person, and its evidence says so.
- **Not yet run on a real server, or against a real Slack workspace.**
  - Tested: the code paths, with a fake Slack server and a scripted Claude. One real
    cycle was run by hand in a cloud session (cycle 02, scored 11 of 11).
  - The first cycle on the server is the end-to-end test.
