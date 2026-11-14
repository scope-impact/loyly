# Runbook: a cycle of slice 06 (the new harness), built and graded by Claude Code

How cycles 13 onward of slice 06 (`cycle-runner`, the one ORC tree `cycle.v1`) are run
(`.pi/spec/decisions.md`, "Spec round 19" 4). The builder is a fresh Claude Code subagent (Opus)
on the host and the grader a fresh Claude Code subagent (Sonnet) on the host: no pi in the
legacy loop. Cycle 13 is the worked example: gate passed on attempt 1, 60/61
(`harness/v2/cycles/06/13/`, commit `1d5020f`).

Run every command from the repository root. `STATE=.pi/harness/.state`. `NN=06`. `MM` is the
cycle number (next: `14`).

## 0. Once per machine

- The Gondolin CLI on PATH (installed 2026-11-14 for @item-06-066):
  ```bash
  (cd .pi/harness && npm ci --no-audit --no-fund)
  ln -sf "$PWD/.pi/harness/node_modules/.bin/gondolin" ~/.local/bin/gondolin
  ```
  Smoke test (about 2 s; prints `Linux aarch64`; `/Users` absent in the VM):
  ```bash
  IMG=$PWD/.pi/harness/.state/assets/builder-clojure
  gondolin exec --image $IMG --allow-host egress-blocked.invalid -- uname -sm
  ```
- Java, the Clojure CLI, `bb`, `jq`, `git` on the host.

## 1. Finish the spec round, commit, push

The fence clones HEAD, so the spec the builder reads must be committed.

1. `git status` / `git diff`: review the round. Round 20 (committed `69ea324`, 2026-11-14; the next
   cycle, 14, builds on it) covers: the researcher's shell in a Gondolin VM the runner starts
   (`LOYLY_GRADER_IMAGE`, @item-06-066 wording replaced), the harness digest's self-check
   command read as handed in, readings for 013 and 071, the builder's open choices, and the
   harness edits (`prompt.clj` host wording, `grader-instruction-06.md` host machine section and
   a temp folder for 062).
2. Check the item count is unchanged (210; slice 06 at 61):
   `grep -c '@item-06-' .pi/spec/cycle-runner/spec.feature`
3. Commit and push:
   `git add -A .pi/spec harness .pi/harness/grader-instruction-06.md tools/spec/views/dist`
   `git commit -m "Spec round 20: ..."` then `git push scope-impact spec-v2`.

## 2. Fence and render

```bash
STATE=.pi/harness/.state; MM=14
ROOM=$STATE/rooms/06-c$MM; OUT=$STATE/prompts/06-c$MM
SLICE=06 harness/fence.sh $ROOM > $ROOM.fence.log 2>&1; echo "fence exit $?"; tail -3 $ROOM.fence.log
bb harness/v2/prompt.clj 06 $OUT       # OUT/system.md and OUT/prompt.md
```

The fence must end `fence OK: ... is a single-commit clean room with no ...`. The room must hold
no `harness-impl/`.

## 3. Build: a fresh Claude Code subagent

In a Claude Code session in this repository, launch an Agent: `general-purpose`, model `opus`,
in the background. Its prompt is this preamble (put in the room's absolute path), then
`---`, then the full text of `$OUT/prompt.md` word for word:

```text
You are a clean-room regeneration builder for one context of the spec, `cycle-runner` (slice
06). Your clean room is the folder

<ABSOLUTE PATH OF $ROOM>

Run every command from inside it (`cd` there first; use absolute paths). Read only inside the
room, never outside it, except the dependency caches your tools use (~/.m2, ~/.gitlibs), the
two git dependencies the instruction names, the `gondolin` CLI on PATH and the guest image the
instruction names (read-only). Write only under `harness-impl/06-cycle-runner/` in the room. Use
the shell to build and test. Do not git commit. Do not look at any other folder on this machine,
including the room's parent folders or other rooms: the room is your only input.

You are not in a VM: you run on the macOS host itself, with a BSD userland, which is where the
gate runs your scripts.

Work steadily and keep the suite bounded, as the spec asks: get the spine (one run through the
whole tree with stubs, the judge included) first. When you are done, or cannot go further,
answer with a short report: what you carried, what is in UNCARRIED.md, the suite's runtime, and
your own hand-played identity check.

The instruction follows, verbatim.
```

Cycle 13's build took 67 minutes.

When it reports, check it wrote only its scope (must print `0`):

```bash
(cd $ROOM && git status --short | grep -vc '^?? harness-impl/')
cat $ROOM/harness-impl/06-cycle-runner/UNCARRIED.md
```

## 4. Gate (at most three attempts)

```bash
harness/v2/gate.sh 06 $ROOM > $STATE/logs/gate-c$MM-slice06.log 2>&1; echo "gate exit $?"
tail -5 $STATE/logs/gate-c$MM-slice06.log     # must end "=== gate OK: ..."
```

It takes about 5 minutes (suite ~3.5 min, probe, store check). If it fails, a repair is a new
Opus subagent given the same preamble and instruction, plus the previous candidate
(`$ROOM/harness-impl/06-cycle-runner`, read-only) and the gate log as evidence, in a freshly
fenced room `06-c$MM-a2`, with a closing paragraph naming both. Gate that room. Stop after
attempt 3: the cycle failed at the gate.

## 5. Grade: a fresh Claude Code subagent on another model

```bash
.pi/harness/bin/grading-room.sh $STATE/grading/06-c$MM 06 $ROOM/harness-impl/06-cycle-runner $(git rev-parse HEAD)
```

Launch an Agent: `general-purpose`, model `sonnet` (not the builder's model, ADR 0003), in the
background. Its prompt is this preamble, then `---`, then the full text of
`.pi/harness/grader-instruction-06.md` word for word:

```text
Your grading room (working directory) is

<ABSOLUTE PATH OF $STATE/grading/06-c$MM>

`cd` into it first and use absolute paths. Read only inside it, write only under its `work/`,
except the dependency caches the Clojure CLI uses (~/.m2, ~/.gitlibs), the `gondolin` CLI and
the guest image the instruction names, and the one temp folder the instruction allows for
@item-06-062. Do not look at any other folder on this machine (not the room's parents, other
rooms or grading folders, or the repository). Do not finish while any item is still `not
reached`.
```

Cycle 13's grading took about 50 minutes. Progress while it runs:

```bash
jq -r 'group_by(.verdict)|map("\(.[0].verdict): \(length)")|.[]' $STATE/grading/06-c$MM/work/verdicts.json
```

## 6. Score, commit, push

```bash
V=$STATE/grading/06-c$MM/work/verdicts.json
jq length $V                                                     # 61
diff <(jq -r '.[].id' $V) $STATE/grading/06-c$MM/checklist.txt && echo order-ok
jq '[.[]|select(.evidence|test("not reached"))]|length' $V       # 0
harness/v2/score.sh 06 $ROOM --verdicts $V --by "claude subagent sonnet, grader-instruction-06" \
  > $STATE/logs/score-c$MM-slice06.log 2>&1; tail -12 $STATE/logs/score-c$MM-slice06.log
git add harness/v2/cycles/06/$MM && git commit -m "Harness: cycle $MM of slice 06 scored X/61 ..." && git push scope-impact spec-v2
```

The report compares with the last scored cycle on shared items: newly passing and newly
failing, never a net. Read `$STATE/grading/06-c$MM/work/surprises.json` for spec readings to
take into the next round.

## 7. After a good score (not yet done)

- **Acceptance** is the operator's call (AGENTS.md, "An accepted build is the running build"):
  copy the candidate into `harness-impl/06-cycle-runner/` with its `PROVENANCE.md` (cycle, spec
  commit, gate log, score).
- **The instrument exception**: slice 06 is the instrument, so before it is used, run it once
  side by side with the legacy harness on the same candidates and compare.
- **Proposed, not decided**: ADR 0007, rebuild only the context a round changed and keep the
  accepted rest read-only, with a full rebuild before each acceptance and every few rounds. Pros
  and cons were discussed 2026-11-14. Draft it only after a build is accepted.

## Cycle record

| Cycle | Spec | Builder | Gate | Score | Note |
|---|---|---|---|---|---|
| 12 | 50c5d20 | pi gpt-6.1-sol in Gondolin, 3h42m | passed, attempt 1 | 43/61 | halted at grading |
| 13 | 2a85cda | Claude Opus subagent, host, 67 min | passed, attempt 1 | 60/61 | 066: no Gondolin VM |
| 14 | 69ea324 | (next) | | | round 20: researcher VM, new leaf `grading-vm` |
