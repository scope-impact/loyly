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

Two words above are kept apart on purpose. A harness feeds what it learns back into the
generation loop, so the agent can iterate against it, which is what the gate and the fence
do. An instrument reports to a person, once a cycle, and never into the loop. The most
valuable measurement here is therefore one the agent never sees, and calling the whole
thing a harness would hide that.

## The pieces, in pipeline order

| file | role | the rule it embodies |
|---|---|---|
| `stamp_item_ids.ts` | stamps `@item-NN-SSS` on every spec scenario | ids are handed out once and are never computed from wording, so rewording a rule cannot break the cycle-to-cycle comparison |
| `gen_checklist.ts` | derives the scoring checklist from the spec | the checklist is derived, never written; a hand-kept list goes stale silently |
| `fence.sh` | builds the clean-room clone and PROVES it | the fence is physical; a generation that can read last week's answer is not regenerating anything |
| `verify.sh` | the ratifying gate | a "done" claim is ratified by a script the claimant does not control, inside a boot budget that is named and whose margin is reported |
| `score.ts` | files verdicts against the checklist | zero verdicts means the grading did not happen, not that everything failed; an unreached item is recorded as `fail`, so a gap can never quietly shrink the denominator |
| `scoreboard.ts` | the pass rate, cycle over cycle | both directions on the shared items, never the net; a moved denominator is restated out loud, and new items are counted separately because they are the one holdout nobody has tuned |
| `hjson.ts` | the JSON dialect and the plumbing the four tools share | one copy of the byte contract (one-space indent, key order preserved), because four copies drift into four instruments; it holds no scoring rule, which is why the fence leaves it in the room |
| `config.sh` | everything this repository knows about itself | a difference that lives in the code instead of in the configuration is a fork nobody declared |
| `probe.sh` | proves the thing answering is THIS application | a name is not evidence; only domain behaviour is, and domain behaviour is code rather than a value |
| `modules.sh` | proves the shared files are one text and the configuration is complete | three hand-kept copies drift silently, and a drifted instrument reports a difference that was never in the thing measured |
| `check.sh` | runs the oracle and the adoption check, and refuses when either is red | a check nobody runs measures nothing, and its silence is indistinguishable from a pass |

No row of that table covers everything, and the gaps matter as much as the mechanisms.
The spec cannot say whether the thing answering is the application it names, which is
why `probe.sh` exists. The gate proves the suite green and says nothing about whether
the suite exercises the product. The checklist covers what a scenario states and cannot
cover what nobody wrote down. The scoreboard counts verdicts and cannot tell a wrong
verdict from a careful one. What is left over is found by a person driving the real
interface, which is why the failure modes at the bottom of this file read as they do.

## The three modules, and the check that they have not forked

This harness exists in three repositories: löyly, saunavuoro and kalibra. They are not
three harnesses. They are one, carried by hand, and every file below is byte identical in
all three.

| module | what it owns | files |
|---|---|---|
| 1, measure | stamp, derive, score, report, and the spec and oracle that judge them | `stamp_item_ids.ts`, `gen_checklist.ts`, `score.ts`, `scoreboard.ts`, `hjson.ts`, `spec.feature`, `oracle.sh` |
| 2, cleanroom | the fence: what is removed, how, and the probe that proves it | `fence.sh` |
| 3, gate | ratifying "done" through the two-entry-point contract | `verify.sh` |
| the check itself | that the three above have not forked | `modules.sh` |
| the occasion | that all of the above actually run | `check.sh`, plus the pre-commit hook it installs |

Two files in `harness/` are each repository's own, and both are declared rather than
assumed:

- **`config.sh`** holds the differences, one `KEY=value` line each, every variable
  documented in place with what reads it and what a wrong value does. If a repository
  needs a value the others do not have, it goes here in all three on the same day.
- **`probe.sh`** is the identity probe, and it is the one file whose content is never
  compared. That exemption is a design decision, not laziness. The probe decides whether
  the regenerated application is the right application, so it belongs to the measuring
  apparatus and not to the product, which is why it sits in `harness/` and survives the
  fence: inside `APP_DIR` it would sit where the agent regenerating the application could
  write the very thing that judges it. Its content differs because what only the real
  product can produce is domain behaviour, and that is code, not a configuration value.
  The check still fails if the file is missing anywhere, or if `APP_PROBE` is unset.

Run it from any of the three, naming the others:

```bash
./harness/modules.sh --siblings ../saunavuoro ../kalibra
```

It fails on a forked or missing shared file, on a per-repository file that is absent, on a
recorded golden that is missing where the oracle needs it, on a configuration variable one
repository has and another does not, on an empty value in anything but `APP_MARKER`, and on
`PLAN_DIR` disagreeing with the path the tools resolve for themselves. It only
reports the configuration values that differ, because that is what configuration is for.

Two things changed on 2026-09-08, both because a green was cheaper than it looked. The
recorded goldens in `harness/oracle/` were in no list, so a repository could lose the one
file without which the oracle cannot judge anything and the adoption check would still say
"no fork"; they are declared now, with `scoreboard-01.golden` required exactly where the
cycle it records exists. And run with no sibling named, the check used to print "identical
in 1 repository" for every shared file, which is a pass earned by comparing a file with
itself; those lines are `SKIP` now and the closing line no longer claims anything about a
fork. `harness/check.sh` reads the sibling paths from `SIBLING_REPOS` in `config.sh`, so
the comparison happens without anybody typing three paths, and a sibling that is not on
this machine is a loud skip rather than a quiet pass.

A third list was added on 2026-09-08 and it is the one that catches the next file
nobody thought about: every file directly in `harness/` must be in one of `SHARED`,
`PER_REPO` or `LOCAL`, or the check refuses. An undeclared file is one the check has no
opinion about, so a copy of it could drift for a year and nothing would say so.
`harness/oracle.py` sat in kalibra exactly like that. It is declared `LOCAL` now, meaning
it belongs to one repository alone and the others are not expected to carry it. **The
declaration is not the real fix.** That file is kalibra's domain oracle, a third
implementation of one hand-worked calculation, and it judges kalibra's arithmetic rather
than this instrument. Sitting in `harness/` under that name it is the third different
thing called "oracle" in one directory, next to `oracle.sh`, which judges the tools, and
`oracle/`, which holds their goldens. A rename, or a move out of `harness/` entirely, is
the better answer, and it was left alone because renaming it touches kalibra's own
documentation and is somebody's decision rather than a tidy-up.

One difference it reports today is open rather than settled: saunavuoro fences its scoring
tools (`score.ts`, `scoreboard.ts`) and löyly and kalibra do not. The siblings do not
agree, the surface can express either, and nobody has decided which is right.

## What makes the checks run

Until 2026-09-08 nothing did. `oracle.sh` made its assertions and `spec.feature` described
its scenarios, and the only way either executed was a person typing the command. There was
no CI in any of the three repositories, no Makefile, and the gate and the fence named the
adoption check in a comment and called neither it nor the oracle. A judge with no occasion
is not weaker than no judge, it is worse: it reads as coverage, and its silence looks
exactly like a pass.

`harness/check.sh` is the one entry point, and three things call it.

```bash
./harness/check.sh --install-hook   # once per clone
./harness/check.sh                  # the instrument, then the adoption report
./harness/check.sh --local          # the instrument alone, reading no other repository
./harness/check.sh --strict         # a fork across the siblings fails too
```

- **Every commit**, through the `pre-commit` hook that `--install-hook` writes. The
  commit is the moment every change in these repositories passes through: they have no
  remote that runs anything, so a CI workflow would be a file nothing executes.
- **`verify.sh`, before it ratifies anything.** A gate is a claim about a measurement, and
  it may not be made on an instrument nobody checked.
- **`fence.sh`, before it builds a clean room.** The room is the input to a regeneration
  these tools will grade, so a room built from a checkout whose tools have moved hands the
  next cycle a judge nobody checked.

**What it costs, measured on the reference machine (Apple Silicon, native arm64,
2026-09-08):** 7.4 seconds in löyly, 7.6 in saunavuoro, 8.1 in kalibra, for a commit that
stages anything at all. Most of that is the oracle's CHECK 9, which runs `modules.sh`
about twenty times over fixture repositories; the rest is `modules.sh` twice more, once
here and once across the siblings. The number is written down because it is the number
that decides whether a hook survives: eight seconds is worth paying and thirty would not
be, and if this figure climbs, the fix is fewer fixture runs rather than a hook nobody
keeps installed. The tools' runtime is deliberately kept off the PATH for the CHECK 9 runs
that are not about `PLAN_DIR`, which under Babashka alone took the oracle from twelve
seconds back to seven. Re-measured after the move to TypeScript (2026-11-17, a Linux
container, no Babashka installed): 7.5 seconds for `check.sh --local` in löyly with
dependency-free tools, and 10 seconds once they were rewritten on Effect, whose loading
adds about 45 milliseconds to each of the hundred-odd tool runs the oracle makes. That is
inside the eight-is-worth-it, thirty-is-not range above but nearer its top than before,
and it is the first place to look if the hook starts to be skipped.

The hook is not a committed file, which is why it is not the only caller. `.git/hooks`
lives outside the tree, a fresh clone has none until `--install-hook` is run there, and
`git commit --no-verify` skips it. The two calls inside `verify.sh` and `fence.sh` travel
with the repository, so the strongest claims this harness makes, "this is ratified" and
"this room is clean", cannot be made over a red instrument anywhere.

**Three of the four verdicts stop a commit, and the fourth only reports.** The oracle is
repository local and deterministic, and so is the adoption check run over this repository
alone, so both are binding. So is a third, narrower one: every shared module file the
commit actually stages must be the same text in every sibling on this machine, checked
byte for byte. What only reports is the adoption check run across the siblings in full,
because that is a statement about somebody else's working tree. Another agent halfway
through editing `spec.feature` in saunavuoro would otherwise fail a commit in kalibra that
has nothing to do with it, and a check that blocks for somebody else's reason is a check
that gets uninstalled. A commit is answerable for what it changes, which is what the third
verdict measures, and `--strict` makes the full comparison binding when that is what you
want.

**What a red oracle costs, stated plainly.** Growing `docs/plan` changes the checklist,
which moves `harness/oracle/checklist.golden.json`, which turns the oracle red until the
golden is re-recorded. That friction is deliberate and it is the point: the golden is the
scoring denominator, and the denominator moving is exactly the event this repository
wants a person to notice and write down. Re-record with `harness/oracle.sh record` and add
the reason under "The goldens, and the three reasons one can change" below. Re-recording to
get a green destroys the only evidence that anything moved.

## The gate's boot budget

Ten seconds, twenty polls at half a second, declared in `verify.sh` as two constants so
the seconds and the polls cannot drift apart. The number is not new: the gate has polled
twenty times at half a second since it was written. What was new on 2026-09-08 is that the
budget was only ever a loop bound. Nothing named it, nothing reported how much of it was
used, and a boot creeping from one second to nine looked exactly like a boot that took
one. So a green run now prints the margin, in polls, and a refusal names the budget in
seconds and the two ways out.

The clock starts when `start.sh` is launched and stops at the first answer, which is the
contract that decides where slow work belongs: a build inside `start.sh` is inside the
budget, a build inside `test.sh` is not, because the suite step is untimed. kalibra's
`impl/start.sh` is built around exactly that.

Measured 2026-09-08, six runs each: saunavuoro's `coldrun/grounded` answered on poll 2 of
20 every time, 0.59 to 0.61 seconds of wall clock, and kalibra's warm web build answered
on poll 2 of 20 every time, 0.60 to 0.63 seconds. Eighteen of the twenty polls are margin
in both. löyly carries no implementation, so nothing was measured there. The cold path is
what the budget is for: a kalibra web build from cold takes closer to forty seconds, which
is why its suite warms the build first.

`harness/spec.feature` states the same ten seconds in `@harness-056` to `@harness-058`. A
budget changed in one of those two places is a spec that no longer describes the gate.

## Walkthrough

The spec is `docs/plan/*/spec.feature` (`PLAN_DIR`); the app under test is whatever gets
built in `impl/` (`APP_DIR`). The gate's contract with an implementation is two
executables, so the docs never have to name a stack: `impl/test.sh` (runs the whole suite,
exits non-zero on any failure) and `impl/start.sh PORT` (serves the app over HTTP on
PORT). Both names, and the port, are in `harness/config.sh`.

```bash
# 0. once per clone (and once in a clean room): the tools' one dependency
npm ci --prefix harness

# 1. stamp the spec (idempotent; only untagged scenarios change)
node harness/stamp_item_ids.ts

# 2. derive the checklist for cycle 01
node harness/gen_checklist.ts --out harness/cycles/01/checklist.json

# 3. build a fenced clean room (what a regeneration agent would be pointed at)
./harness/fence.sh /tmp/clean-room

# 4. ratify the current implementation
./harness/verify.sh

# 5. grade each checklist item against the running app, however you grade
#    (an agent driving the app, or a person with the spec in one hand), then file:
node harness/score.ts 01 --verdicts my-verdicts.json

# 6. read the measurement
node harness/scoreboard.ts 01
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

- A gate once reported success without ever exercising the app, because a missing
  config turned the test command into a no-op that exits 0. Gates now assert their tools
  exist before trusting them.
- A test runner named its suites twice, once in imports and once in a run list, so a
  forgotten suite was silently green. Runners now discover suites from the directory and
  refuse an empty discovery (`verify.sh` does exactly this).
- An orphaned process from a dead run once answered a health probe long afterwards, so a
  scoring run graded the wrong process. The gate now refuses to start when the port is
  already held (`verify.sh` [0]).
- A scoring run whose every batch failed on a network outage once committed "scored" over
  an empty record. The instrument now dies loudly instead: an unrecorded measurement is
  recoverable, a false record is not (`score.ts`).
- A weaker fence probe once passed while the previous implementation was still reachable
  from an older commit. The probe now checks the object graph, not the working tree
  (`fence.sh`).
- A seeded holdback, a quarter of the items scored and never listed, with the gap between
  the visible and the hidden pass rates as the alarm, was built here and retired on
  2026-09-02. That gap measures the operator's repairs against the method alone, which
  means something only when a repair loop runs blind to the hidden items, and none ever ran
  here; the seed was committed too, so the set was unprinted rather than hidden. New
  scenarios are the holdout that needs no seed, and the scoreboard already counts them.

## The goldens, and the three reasons one can change

`harness/oracle.sh` judges an implementation of these tools against recorded goldens in
`harness/oracle/`, byte for byte, refusal messages included. A golden that moves is
normally an alarm, so it is worth writing down each time one moved and why.

Three events look alike in a diff and must never be confused:

- **The instrument's behaviour changed.** Serious. It needs a reason, and a golden may not
  be re-recorded merely to get the suite green again.
- **The wording was replaced.** Deliberate, and with no behavioural consequence.
  Re-recording is then the correct response, and an entry here is the record of it.
- **The spec grew.** `checklist.golden.json` records the checklist derived from
  `docs/plan/`, so adding scenarios there moves it without anything about the instrument
  having changed at all. Re-recording is correct, and this is the case that will recur,
  which is exactly why it needs a test somebody can apply rather than a judgement somebody
  makes. **The test is that the diff is additions only.** No existing item's `id`, `name`,
  `rule` or `source` line may move, and the only removed line may be the `total` count. A
  re-record that changes an existing item is not this case, whatever the commit message
  says, and it is the first case wearing this one's clothes:

  ```bash
  git diff -- harness/oracle/checklist.golden.json | grep '^-' | grep -v '^---'
  #   must print the old "total" line and nothing else
  ```

**2026-11-17: the tools moved from Babashka to TypeScript, in löyly first.** The five
`.clj` files were replaced by `.ts` files that node runs directly, with no build step, so
the harness needs node and nothing else (`bb` is no longer required anywhere). The same
day they were rewritten on [Effect](https://effect.website) (`effect` 4.1.0, pinned in
`harness/package.json`, installed with `npm ci --prefix harness`), in a style kept close to
the Clojure rather than translated from it: values are plain data and the functions over
them pure, what touches the world is one `Io` service, a refusal and an unreadable document
are typed failures (`Refusal`, `JsonError`), and `run` in `hjson.ts` is the one place
either becomes an exit code. `package.json` and `package-lock.json` are shared module files
now, declared in `modules.sh`. A missing `harness/node_modules` fails the oracle outright
rather than skipping it, because a tool that cannot load is not a tool that is absent. It was accepted the way the Babashka was accepted in place of the
Python: `harness/oracle.sh ts` passed with the same counts as `harness/oracle.sh bb`
(114 ok, 2 skipped, and the skips are the scored-cycle checks löyly cannot make), and
**no golden was re-recorded**: `checklist.golden.json` came back byte identical from the new
generator. Because löyly has no scored cycle, the scorer and the scoreboard were also run
side by side with the Babashka over 491 generated cases (malformed and edge-case JSON,
floats at the edges of `Double.toString`, unpaired surrogates, CRLF and Unicode
whitespace in specs, every slice status shape, and the rounding of every rate up to 400
items) and agreed on every exit code, every byte of stdout and stderr, and every file
written, both before the Effect rewrite and after it. The places where the JVM's behaviour reached the output are reproduced on
purpose and each is commented in `hjson.ts`: what `trim` and the reader count as
whitespace, Java's ASCII-only `\s`, how a double prints, `BigDecimal`'s exact
half-even rounding, and an unpaired surrogate written as `?`.

Two refusal messages name a command and so changed their words on purpose
(`@harness-023`, `@harness-040`): they now say `harness/stamp_item_ids.ts` and
`node harness/gen_checklist.ts`, and `harness/spec.feature` moved with them.

**This forks the ring until the siblings take the same change.** The shared files are
one text across löyly, saunavuoro, kalibra and chartroom, and in löyly that text is now
TypeScript. Run across the siblings, `modules.sh` will report every tool as missing or
forked until the same commit lands in each of them, on the same day as far as that is
possible. `migrations/harness-to-typescript.sh <sibling>`, run from löyly with the sibling
cloned beside it, does the mechanical part and stops short of committing: it refuses over a
shared file that has drifted from löyly's pre-move text, records the Babashka oracle's
counts, copies the shared files, renames the tools in the sibling's own files (FENCED_PATHS
included), installs the dependency, and prints the TypeScript oracle's counts next to the
baseline. Rehearsed on two simulated siblings rebuilt from löyly's history: one shaped like
saunavuoro, fencing the scoring tools and carrying a cycle scored and recorded by the
Babashka (121 ok, 0 skipped before and after, so the scorer round-trip the oracle cannot
make in löyly passed byte for byte there), and one carrying a local `harness/oracle.py`
like kalibra (114 ok, 2 skipped before and after). Across löyly and the two, `modules.sh`
reported every shared file identical; the saunavuoro-shaped clean room, missing the fenced
`score.ts` and `scoreboard.ts`, skipped loudly instead of failing, as `@harness-066` says. The oracle and the per-commit check are repository local and are green here.

**2026-09-08: the spec grew, in all three repositories.** Scenarios were added under
`docs/plan/`, so the derived checklist gained items and the golden moved with it. The
denominators went from 8 to 11 in löyly, 8 to 11 in saunavuoro and 251 to 255 in kalibra,
and the harness's own spec went from 55 scenarios to 67. Nothing about the instrument
changed: the diff in every golden is additions plus the one `total` line, which is the
test above, and no existing item moved. Recorded here in `c9cd302`.

**2026-09-02: the wording was replaced with our own.** The comments, docstrings, refusal
messages and the scoreboard's printed report were rewritten in our own plain language.
Nothing else moved: no logic, no exit code, no JSON structure or key order, and no change
to how a rate is formatted. `harness/oracle/checklist.golden.json` came back byte for byte
identical, which is the check that proves it, and both `harness/oracle.sh py` and
`harness/oracle.sh bb` pass with the same counts as before. The goldens were regenerated
with `harness/oracle.sh record` for that reason and no other.

**2026-09-03: the skip lines moved from stdout to stderr.** A behaviour change, the
serious kind, made on purpose: `gen_checklist` prints "skipping" for a planned or backlog
slice, and until now that line landed on stdout ahead of the checklist JSON, so
`gen_checklist | jq` broke on any repository with a planned slice. The oracle's expectation
moved with it (`@harness-027`, `@harness-028`); no golden changed, because the goldens are
written through `--out` and never carried the line.
