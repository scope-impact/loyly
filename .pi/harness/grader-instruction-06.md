# Grade the cycle-runner candidate (slice 06), black-box

You are the grader of one regeneration cycle: you file one verdict per checklist item of slice 06,
the `cycle-runner` context. You did not build the candidate and you must not repair it. Your
verdicts are the instrument: they report to a person and never go back to the builder.

## The grading room (your working directory)

| Path | What it is |
|---|---|
| `.pi/spec/cycle-runner/` (also `.pi/plan/06-cycle-runner/`) | The spec of the context under grade: `spec.feature` (the stamped scenarios), `seam.md` (how a grader drives it), `event-model/`, `allium/`, `domain-design.md` |
| `.pi/spec/<context>/seam.md`, `event-model/` | The public seam and event model of the five contexts the runner drives (specification, measurement, grader-lifecycle, regeneration-cycle, cycle-preparation) |
| `.pi/spec/decisions.md` | The decisions record: readings of the spec |
| `checklist.txt` | The item ids, one per line, in file order: your denominator |
| `harness-impl/06-cycle-runner/` | The gated candidate (a copy, at the repository's layout: it reads `../../.pi/spec/` from there): `start.sh` boots it on its JSON-RPC stdio seam; `test.sh` runs its own suite |
| `harness-impl/0N-<context>/` (01 to 05) | The promoted builds of the five contexts, for the runner's `LOYLY_CONTEXTS` launch entrypoints (`harness-impl/0N-<context>/start.sh`) |
| `harness/v2/event_store_check.clj` (+ `event-store-check.sh`, `deps.edn`) | Reference only: how the gate reads a Grain SQLite store directly with Grain's own reader. Reuse its reading code to inspect a store |
| `work/` | Yours, read-write: fixtures, stub scripts, stores, logs, and your output |

Do not read or look for anything outside this room. In particular, never read (they are not here,
and you must not try to reconstruct them): builder sessions or transcripts, prompts or
instructions handed to a builder, prior verdicts or scores of any cycle (`harness/v2/cycles`,
`grading/`), other candidates, or the candidate's own tests as a source of truth. The candidate's `README.md`, `VALIDATION.md`, test names and `test/` are its
builder's claims: you may run `harness-impl/06-cycle-runner/test.sh`, but a green suite is never a verdict.

## How to grade

1. Read `.pi/spec/cycle-runner/seam.md` and `spec.feature` first, then the parts of the other
   seams and `decisions.md` that a scenario needs. The spec is authoritative; where `seam.md` and
   `event-model/` disagree, the event model wins.
2. Drive the candidate **only through its seam**: start `harness-impl/06-cycle-runner/start.sh` with the `LOYLY_*`
   environment seam.md names ("Handed in"), send JSON-RPC requests on its stdin, read responses on
   its stdout. Hand in effects as seam.md describes ("How a grader drives it without a live pi"):
   stub builders, fixture project repositories (git), probes, a gate command, launch entrypoints
   for contexts. Build every fixture under `work/`, one directory per scenario or group, from fresh
   state each time. Where a scenario needs a real context, use `harness-impl/0N-<context>/start.sh` as
   its launch entrypoint; where it needs a misbehaving one (slow, silent, exiting), write a small
   stub script.
3. Each scenario's Given/When/Then is what you set up, what you do, and what you check. A Then
   passes only when you observed it: a response, a refusal code, a recorded outcome, a file, a
   store event, a measured time.
4. `@item-06-059`: read the **runner's own Grain store** (the SQLite file you handed it in
   `LOYLY_GRAIN_STORE`, or as seam.md says) directly, after the run, with Grain's reader
   (`harness/v2/event_store_check.clj` shows how): find the ORC workflow `cycle-runner/run-to-gate`,
   its leaves' start and completion, and check where each effect result and each relayed handoff
   lies. The seam alone cannot answer it.
5. `@item-06-058`: use **real timings**: a launch entrypoint that first responds 30 seconds after
   its launch, one that never responds and does not exit, and measure the refusal's wall-clock time
   from the start request (it must come between 60 and 90 seconds). Do not shorten the waits.
6. Take the time grading needs: hours, not minutes. Work through every item; do not stop early.
   Answer only when every item has a verdict you reached by trying it; if the candidate cannot
   start at all after the setup above, say exactly why in each item's evidence and in
   `work/surprises.json`.
7. Unreached is fail. If you cannot set a scenario up, it fails with the reason as its evidence.
   Never pass an item because it "probably" holds.

## The machine

You run as root in a Gondolin VM (see the environment section): bash, git, curl, Java 21, the
Clojure CLI and babashka (`bb`); no Python (if you truly need it, `apk add python3` and append the
command to `/workspace/.builder/image-additions.txt`). Network reaches only the package registries
and GitHub. Your home directory is empty at boot, so the first start of each Clojure program
fetches its dependencies: start each `harness-impl/*/start.sh` once with stdin closed
(`</dev/null`) before you grade, one at a time, so later starts are quick. The room's files belong
to another user than root, so git refuses them until you run
`git config --global safe.directory '*'` (once per session). If a program fails at startup, read
its error: it is most likely your setup (an environment variable, a path, a missing fixture), not
the candidate; fix the setup and retry before you fail an item for it. Every
context is a JVM: run at most two scenarios in parallel, and kill every process a scenario started
before the next. Write stubs as shell or `bb` scripts.

## Output

Write `work/verdicts.json`: a JSON array with exactly one object per line of `checklist.txt`, in
that order, and nothing else:

```json
[{"id": "06-001", "verdict": "pass", "evidence": "what you set up, what you sent, what you observed"}]
```

`id` is the item id without `@item-` (`06-001`); `verdict` is `pass` or `fail`; `evidence` is one
or two sentences of what you observed (the requests, the codes, the times, the store events), and
for a fail, which Then did not hold. The evidence of an item you have not yet tried starts with
`not reached`. Write the file early (all `fail`, evidence `not reached`) and update it as you go,
so an interrupted grading still files every item. If you answer while items are still `not
reached`, you will be asked to continue with them.

Optionally, `work/surprises.json`: `[{"id", "note"}]`, places where the spec was ambiguous or the
candidate did something the spec does not mention. A surprise never changes a verdict.

When every item has a verdict, answer with one line: the number of items, passes and fails.
