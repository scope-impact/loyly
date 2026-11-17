# Runbook: a cycle of slice 06 (the new harness)

How cycles 13 onward of slice 06 (`cycle-runner`, the one ORC tree `cycle.v1`) are run
(`.pi/spec/decisions.md`, "Spec round 19" 4). The builder and grader are fresh subagents on
the host, on different models (ADR 0003): no pi in the legacy loop. Cycle 13 used Claude
Opus and Sonnet; cycles 14 to 22 used Codex builders, and cycles 23 and 24 used Astra.
Cycle 25 is the accepted running context, repaired from accepted cycle 23. Its score was
61/61 and its legacy shadow matched; its profile exception is recorded in
`harness-impl/06-cycle-runner/PROVENANCE.md`. Cycle 24's gate passed on attempt 1 but scored
31/61, with a failed profile and two legacy shadow regressions (`harness/v2/cycles/06/24/`,
`harness/v2/shadow/06/24/`). It is not accepted.
The next grader's profile contract and its recorded reason are in `harness/v2/PROFILE-06.md`.

Run every command from the repository root. `STATE=.pi/harness/.state`. `NN=06`. `MM` is the
next unused cycle number; set it explicitly before fencing.

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

## Before every cycle: compare with the legacy harness

Before selection approval or fencing, review every proposed cycle-runner spec, grader, gate,
and runbook change against the reference behavior in `harness/regen.clj` and
`archive/legacy-harness/{fence.sh,verify.sh,score.clj,scoreboard.clj}`. Include the adapted
`harness/` tools when the public seam differs from the archive. Read the previous cycle's
instrument shadow comparison; if it is absent, run the previous candidate and legacy harness
on the same fixture before starting another build. Record a short table at
`harness/v2/preflight/06/$MM.md`: changed behavior, legacy reference, preserved behavior or
intentional divergence, and the test or shadow observation that will prove it. Resolve any
unexplained divergence and commit this report with the approved selection before fencing.

The new candidate's own shadow comparison can run only after it exists. For slice 06, perform
that comparison before accepting or using the candidate, as `AGENTS.md` requires for the
instrument.

## 1. Finish the spec round, commit, push

The fence clones HEAD, so the spec the builder reads must be committed.

1. `git status` / `git diff`: review the next spec round and obtain approval of its exact
   deterministic selection and sealed grader digest before fencing (AGENTS.md, "Pi and ORC
   ownership" and "The instrument").
2. Check the item count is unchanged (210; slice 06 at 61):
   `grep -c '@item-06-' .pi/spec/cycle-runner/spec.feature`
3. Commit and push:
   `git add -A .pi/spec harness .pi/harness/grader-instruction-06.md orc tools/spec/views/dist`
   `git commit -m "Spec round NN: ..."` then push to the authorized remote.

## 2. Fence and render

```bash
STATE=.pi/harness/.state; MM=<next-unused-cycle-number>
ROOM=$STATE/rooms/06-c$MM; OUT=$STATE/prompts/06-c$MM
SLICE=06 harness/fence.sh $ROOM > $ROOM.fence.log 2>&1; echo "fence exit $?"; tail -3 $ROOM.fence.log
bb harness/v2/prompt.clj 06 $OUT       # OUT/system.md and OUT/prompt.md
```

The fence must end `fence OK: ... is a single-commit clean room with no ...`. The room must hold
no `harness-impl/`.

## 3. Build: a fresh Codex subagent

Launch a fresh Codex subagent on the approved builder model in the background. Its prompt is this preamble
(put in the room's absolute path), then
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

Before the gate, continue the **same builder subagent** while `UNCARRIED.md` lists slice-06
items, up to three continuation turns. This is the legacy `regen.clj` `carry-slice!` rule:
the builder keeps its own session and candidate, and no grading evidence enters the room.
On each turn, give it only the IDs in its current `UNCARRIED.md` and this task:

```text
Your turn ended with these items of your slice still listed in
harness-impl/06-cycle-runner/UNCARRIED.md: <CURRENT ITEM IDS>. Carry them now. For each, write a
failing test that drives it through the public seam and names its id, make it pass with the
smallest real change, keep the whole suite green, and remove its line from UNCARRIED.md. Leave a
line only for an item you truly cannot carry, with the reason. Then rerun test.sh, start
start.sh on a fresh store, and confirm the full identity check over stdin. The clean-room
boundary and write scope from the first turn still apply.
```

After each continuation, check the write scope again and compare a digest of the candidate's
relative file paths and bytes with the digest before that turn. Stop when no items remain, the
candidate did not change, or three continuations have finished. Record the remaining IDs in the
cycle handoff. Gate repair in step 4 is separate: it begins only after an independent gate
failure and uses a fresh subagent with that gate's evidence.

## 4. Gate (at most three attempts)

```bash
harness/v2/gate.sh 06 $ROOM > $STATE/logs/gate-c$MM-slice06.log 2>&1; echo "gate exit $?"
tail -5 $STATE/logs/gate-c$MM-slice06.log     # must end "=== gate OK: ..."
```

It takes about 5 minutes (suite ~3.5 min, probe, store check). If it fails, a repair is a new
Codex subagent on the same builder model, given the same preamble and instruction, plus the previous candidate
(`$ROOM/harness-impl/06-cycle-runner`, read-only) and the gate log as evidence, in a freshly
fenced room `06-c$MM-a2`, with a closing paragraph naming both. Gate that room. Stop after
attempt 3: the cycle failed at the gate.

## 5. Grade: a fresh Codex subagent on another model

```bash
.pi/harness/bin/grading-room.sh $STATE/grading/06-c$MM 06 $ROOM/harness-impl/06-cycle-runner $(git rev-parse HEAD)
```

Launch a fresh Codex subagent on `gpt-6.1-terra` (not the builder's model, ADR 0003), in the
background. Its prompt is this preamble, then `---`, then the full text of
`.pi/harness/grader-instruction-06.md` word for word:

```text
Your grading room (working directory) is

<ABSOLUTE PATH OF $STATE/grading/06-c$MM>

`cd` into it first and use absolute paths. Read only inside it, write only under its `work/`,
except the dependency caches the Clojure CLI uses (~/.m2, ~/.gitlibs), the `gondolin` CLI and
the guest image the instruction names, the host process table through `profile.py` for this
room's programs, Java 21's `jcmd`/`jstat`/`jfr` for a candidate JVM PID, and the one temp folder
the instruction allows for @item-06-062. Do not look at any other folder on this machine (not
the room's parents, other rooms or grading folders, or the repository). Do not finish while any
item is still `not reached`.
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
harness/v2/score.sh 06 $ROOM --verdicts $V --by "codex subagent gpt-6.1-terra, grader-instruction-06" \
  --profile $STATE/grading/06-c$MM/work/profile.json \
  > $STATE/logs/score-c$MM-slice06.log 2>&1; tail -12 $STATE/logs/score-c$MM-slice06.log
git add harness/v2/cycles/06/$MM && git commit -m "Harness: cycle $MM of slice 06 scored X/61 ..." && git push ragavsathish spec-v2
```

The report compares with the last scored cycle on shared items: newly passing and newly
failing, never a net. Read `$STATE/grading/06-c$MM/work/surprises.json` for spec readings to
take into the next round. For the revised grader, also read `work/profile.json`; pass it to
`score.sh` with `--profile $STATE/grading/06-c$MM/work/profile.json`, which files its digest
and copy beside the score. A failed profile
is still filed with the honest score and blocks acceptance until investigated. The revised
grader instruction digest requires operator approval under AGENTS.md before use.

## 7. After a score and instrument comparison

- **Acceptance** is the operator's call (AGENTS.md, "An accepted build is the running build"):
  copy the candidate into `harness-impl/06-cycle-runner/` with its `PROVENANCE.md` (cycle, spec
  commit, gate log, score).
- **The instrument exception**: slice 06 is the instrument, so before it is used, run it once
  side by side with the legacy harness on the same candidates and compare.
- **Cycle-25 repair exception**: ADR 0007 and `harness/v2/REPAIR-06.md` define the bounded
  repair of 06-047 and 06-066 from the pinned accepted cycle-23 artifact. It still requires
  a full gate, all-61 grading, clean profile, and same-cut legacy shadow before replacing
  the runner. Use that workflow for cycle 25; use this fresh-room runbook for later full
  regenerations.

## Cycle record

| Cycle | Spec | Builder | Gate | Score | Note |
|---|---|---|---|---|---|
| 12 | 50c5d20 | pi gpt-6.1-sol in Gondolin, 3h42m | passed, attempt 1 | 43/61 | halted at grading |
| 13 | 2a85cda | Claude Opus subagent, host, 67 min | passed, attempt 1 | 60/61 | 066: no Gondolin VM |
| 14 | 69ea324, 81d9f83 | Codex gpt-6.1-sol subagent, host | passed, attempt 1 | 59/61 | 066 passed; 026 unproved Given, 070 restart lacked model provider |
| 15 | 7bc2610 | Codex gpt-6.1-sol subagent, host | passed, attempt 1 | 57/61 | 026 passed; 010, 054, 056 newly failed; 070 still failed |
| 16 | 12f622b | Codex gpt-6.1-sol subagent, host | passed, attempt 1 | 61/61 | 010, 054, 056, 070 newly passed; QEMU children leaked after grading |
| 17 | ff6e8f5 | Codex gpt-6.1-sol subagent, host | passed, attempt 1 | 61/61 | behavior unchanged from 16; profile failed on an out-of-order 073 capture; acceptance open |
| 18 | 0e3f5e6 | Codex gpt-6.1-sol subagent, host | passed, attempt 1 | 61/61 | behavior unchanged from 17; profile failed on 54 live runner after captures; acceptance open |
| 19 | 73e70a7 | Codex gpt-6.1-sol subagent, host | passed, attempt 1 | 59/61 | 010 and 060 newly failed; profile clean, shadow matched legacy; acceptance open |
| 20 | 6f187ac | Codex gpt-6.1-sol subagent, host | passed, attempt 3 | 61/61 | 010 and 060 newly passed; profile clean; judge numeric bands failed the legacy shadow; acceptance open |
| 21 | cb19e87 | Codex gpt-6.1-sol subagent, host | passed | 23/61 | profile failed; not accepted |
| 22 | cb19e87 | Codex gpt-6.1-sol subagent, host | passed | 55/61 | profile failed; legacy shadow matched; not accepted |
| 23 | cb19e87 | Codex gpt-6-astra subagents, host | passed, attempt 2 | 59/61 | profile clean; legacy shadow matched; 047 and 066 failed; accepted with both open |
| 24 | 76ee55b | Codex gpt-6-astra subagent, host | passed, attempt 1 | 31/61 | profile failed; built-in gate missing and gitlinks dropped in legacy shadow; not accepted |
| 25 | 3d58399 | Codex gpt-6-astra repair subagent, host | passed, attempt 1 | 61/61 | 047 and 066 newly passed vs accepted 23; no newly failing; legacy shadow matched; profile failed on eight audited retry names; accepted by explicit operator exception |
