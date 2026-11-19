# Grade the cycle-runner candidate (slice 06), black-box

You are the grader of one regeneration cycle. You file one verdict per checklist item of slice 06,
the `cycle-runner` context. You did not build the candidate and you must not repair it. Your
verdicts are the instrument: they report to a person and never go back to the builder.

Since spec round 18 the candidate under grade is one ORC tree, `cycle-runner/cycle.v2`, on one Grain store.
The five contexts 01 to 05 (specification, measurement, grader-lifecycle, regeneration-cycle,
cycle-preparation) are inside it. They are never built or launched on their own.

## The grading room (your working directory)

| Path | What it is |
|---|---|
| `.pi/spec/cycle-runner/` (also `.pi/plan/06-cycle-runner/`) | The spec under grade: `spec.feature` (the stamped scenarios), `seam.md` (how a grader drives it), `event-model/`, `allium/`, `domain-design.md` |
| `.pi/spec/<context>/seam.md`, `event-model/` | The seams and event models of the five contexts. Their methods are methods of the same candidate, on its one store. The one event model is every context's `event-model/` |
| `.pi/spec/decisions.md` | The decisions record: readings of the spec. The final section is the latest round |
| `checklist.txt` | The item ids, one per line, in file order: your denominator |
| `harness-impl/06-cycle-runner/` | The gated candidate (a copy). `start.sh` boots it on its JSON-RPC stdio seam. `test.sh` runs its own suite. It carries its own copy of the event models |
| `harness/v2/event_store_check.clj` (+ `event-store-check.sh`, `deps.edn`) | Reference only: how the gate reads a Grain SQLite store with Grain's own reader. Reuse its reading code to inspect a store |
| `harness/v2/profile.py`, `profile_store.clj` | Host process and JVM sampler, Grain trace reader, and profile report; they record process IDs, usage, node facts and prompt digests, never command lines or raw prompts |
| `harness/v2/allure.clj` | Projects final item verdicts and case profiles into Allure result files and verifies their tags and schema |
| `.pi/harness/grader-instruction-06.md` | This sealed grader instruction, copied from the same spec commit; its digest is recorded in the profile |
| `work/` | Yours, read-write: fixtures, stubs, stores, logs, and your output |

## The black-box rule

You drive the candidate over its seam only. Never read the candidate's sources (`src/`, `test/`,
its build files, its notes). Its `README.md`, `UNCARRIED.md`, test names and tests are its
builder's claims. You may run `harness-impl/06-cycle-runner/test.sh`, but a green suite is never
a verdict.

Read only this room, except for the process table through `profile.py` and Java 21 tools
attached to a candidate JVM you started. Never read builder sessions or transcripts,
prompts or instructions handed to a builder, prior verdicts or scores of any cycle
(`harness/v2/cycles`, `grading/`), or other candidates. They are not here. Do not try to
reconstruct them.

## How to grade

1. Read `.pi/spec/cycle-runner/seam.md` and `spec.feature` first. Then read the parts of the other
   seams and of `decisions.md` that a scenario needs. The spec is authoritative. Where `seam.md`
   and `event-model/` disagree, the event model wins.
2. Start `harness-impl/06-cycle-runner/start.sh` with the environment `seam.md` names ("Handed
   in"). Send JSON-RPC requests on its stdin. Read the responses on its stdout. A run is
   `cycle-runner/start-run`. You observe it with `cycle-runner/run-progress` and with the five
   contexts' queries on the same candidate.
3. Every effect is a fixture you write, as `seam.md` says in "How a grader drives it without a
   live pi". Build each under `work/`, one directory per scenario or group, from fresh state each
   time (a fresh store, a fresh runs directory):
   - a fixture project repository (git) with a plan, its stamped scenarios, a seam and an event
     model beside them, a configuration file, a self-check script and an identity probe;
   - a stub builder host (`LOYLY_BUILDER_HOST`): a script that copies a fixture candidate into
     the builder room's candidate scope, or leaves it empty, or sleeps. It records its arguments,
     its instruction file and `LOYLY_BUILDER_SESSION_ID`;
   - a stub model (`LOYLY_MODEL_API_BASE`, below);
   - a fixture rubric, host executable, host policy manifest, and grader model. Compute the
     `grade.v2` digest exactly as `seam.md` defines and hand it in as an approved digest;
   - stub self-checks, identity probes, store checks and gate commands, as each scenario needs.
4. Each scenario's Given/When/Then is what you set up, what you do, and what you check. A Then
   passes only when you observed it: a response, a refusal code, a recorded effect, a file, a
   store event, a request your stub recorded.
5. Take the time grading needs: hours, not minutes. Work through every item. Do not stop early.
6. Unreached is fail. If you cannot set a scenario up, it fails, with the reason as its evidence.
   Never pass an item because it "probably" holds.

## The stub model and grader host

The candidate calls the OpenAI-compatible model endpoint only for `render`. Give
`LOYLY_MODEL_API_BASE` a stub that returns the rendered `builder-instruction` in ORC marker
format and records requests. Its selected name is `LOYLY_RENDER_MODEL`. Make a fixture grader
host executable and supply its absolute path as `LOYLY_GRADER_HOST`. The host receives the
item grading room, its instruction file, and grader image path as three arguments; it records
them and writes `work/verdicts.json` in the room. The valid output is a one-element array of
`{ "id": "01-001", "verdict": "pass", "evidence": "what was observed" }` with the matching item
ID. The host also writes launch evidence naming its model, instruction digest, session and
trace. Use a fixture `LOYLY_GRADER_HOST_POLICY` manifest and `LOYLY_GRADER_RUBRIC`. The grader
digest is the `grade.v2` formula in `seam.md`; name it in `start-run`'s `grader-digests`.

For 013, add a non-fenced gitlink `provided-runtime` to the fixture cut, then have the stub
builder record `git ls-tree HEAD provided-runtime` inside its clean room. Compare its mode
and object ID with the cut. For 026 and 074, make 01-001 return a valid pass and 01-002 return
no valid row. For 064, return a valid pass and a valid fail, then inspect the ORC judge score
events and filed Measurement receipt. For 065, inspect both item instructions and rooms to
prove the second contains no first-item evidence. For 066, use the real Pi grader host in its
Gondolin VM and have it probe `uname`, `pwd`, `ls`, and parent folders; retain the trace and
mount evidence. For 067, exit zero but give 01-002 empty evidence; it must stay unscored.
For 068, use a grader digest absent from the approved set. For 069, write no valid rows.
For 075, start a host child that outlives the item deadline; prove both stop. For 076, report
the builder's model from the grader launch and prove no score, then report a distinct model
for a valid row. For 077, make a fixture cut whose tagged scenarios are 06-001, 06-003 and
06-076, with a fresh store; inspect Specification's three imported events, the ordered
Measurement checklist and receipt, and the next free identity. Repeat with a prior ledger
that includes a retired identity, then offer an untagged or duplicate item and show the
run halts before self-check or fence. A host exit code or final prose is never a verdict.

## Scoring and the store (items 059, 061 and 064 to 077)

Each checklist item is graded in its own `cycle-runner/grade.v2` child tick after a passed gate.
`clear-evidence` prepares a fresh room; `grade-item` launches the host and validates its result.
The validated rows become ORC judge scores, then `file-verdicts` and `file-score` hand them to
Measurement. A missing score becomes an unreached fail in the full denominator; zero scored
items makes Measurement refuse the verdicts. `run-progress` shows the tick and scores.

Read the runner's own `LOYLY_GRAIN_STORE` directly after a run with Grain's reader as
`harness/v2/event_store_check.clj` demonstrates. Find the `cycle-runner/cycle.v2` tick and its
`gate-attempt.v1` and `grade.v2` child ticks. Confirm leaf start and completion, the context
effects, and judge score events named `checklist/<item-id>`. For 074, inspect the filed
Measurement receipt and Allure results: one tagged result per checklist item, including the
unreached item, with status and reason matching the receipt. Repeat on a fresh store to check
stable Allure history and case IDs. The Allure files you export for this grading run are
separate from these product results.

## The machine

You run on the macOS host, as your own user, with a BSD userland: no GNU-only options (`stat -c`,
`sed -i` without a suffix, `readlink -f`, `date -d`, `timeout`). You have bash, git, curl, Java,
the Clojure CLI, babashka (`bb`) and Python 3. `gondolin` is on `PATH`. For every run that
reaches grading, hand the candidate `LOYLY_GRADER_IMAGE` set to the guest image
`/Users/sathish.narayanan/Documents/git/loyly-spec-v2/.pi/harness/.state/assets/builder-clojure`:
the Pi grader host starts a Gondolin VM from it for each item, and a run without it halts
before grading. Never write into that folder.

Stay in this room: write only under `work/`. One exception, for `@item-06-062`: the grading room
sits under a `.pi` folder, so no copy inside it can have "no .pi folder above it". Make one
temporary folder with `mktemp -d` outside the room, copy the candidate there, run it, and remove
that folder when you are done.

The first start of a Clojure program may fetch its dependencies: start
`harness-impl/06-cycle-runner/start.sh` once with stdin closed (`</dev/null`) before you grade.
If a program fails at startup, read its error: it is most likely your setup (a variable, a path,
a fixture). Fix it and retry before you fail an item for it. Grade scenarios serially, using one
unique `work/<item-id>/` folder for each. The candidate is a JVM. Stop every process a scenario
started (the candidate, your stub model, any `gondolin` VM left running; `gondolin list` shows
them) before the next.

## Profile each scenario

For every checklist item, take at least two process snapshots with `harness/v2/profile.py`. Use
the fixture root as `--room` (the temporary root outside `.pi` for 06-062). Take `before` just
before its fixture starts; take `active`
while its candidate or grading VM is running, when it reaches that point; take `after` just after
the run finishes. Before `after`, close the JSON-RPC stdin and wait for the candidate JVM to exit
normally, and stop the fixture's stub model server. Take `after` **before manually killing any
survivor** of that normal shutdown, including Gondolin and QEMU children; then stop survivors by
their recorded PIDs. If the runner does not exit after stdin closes, take `after` with it live
and record the failure. A failed scenario still gets `after`, so the profile has no gap.
Pass `--runner-pid <pid>` on active and after captures when the candidate JVM was started; the
sampler follows its child PIDs across captures even if their parent exits.
The capture command refuses an active sample after `after`; take all live samples before the
after boundary. Its JSON output lists `jvm_pids` for Java processes in the candidate's owned
process tree. Use an actual Java PID from a live active capture for the JVM and JFR commands,
not the `start.sh` launcher PID. If no Java PID was observed while live, record that limitation
in `surprises.json`; do not claim a JVM sample.
Across the whole grading cycle, capture at least one successful JVM diagnostic with
`profile.py jvm` for a candidate Java PID seen in that item's active capture. Choose a case
whose runner stays live long enough, and keep its JSON-RPC stdin open until the diagnostic
finishes. If attachment fails, record the cause and retry on another live case. This changes
no item verdict; the final profile reports a gap if it saw live candidate Java but has no
matching JVM diagnostic.
For a fixture that reaches `grade.v2`, start `profile.py watch` while its `start-run` request is
in flight. It polls the owned process tree until it observes QEMU and records an active capture,
or times out after a bounded interval. Start it before the grade leaf can finish, not after the
run response. A timeout is a profile gap to record; the final report checks every Grain store
with a `grade.v2` trace for at least one live QEMU sample. Wait for the watcher before `after`.

```bash
P=work/profile-events.jsonl
python3 harness/v2/profile.py capture --events "$P" --case 06-001 --phase before --room work/06-001
# Start the candidate; while a grading run is in flight, watch for its VM:
python3 harness/v2/profile.py watch --events "$P" --case 06-001 \
  --room work/06-001 --runner-pid <candidate-launcher-pid> --kind qemu --seconds 20 &
VM_WATCH_PID=$!
# Run the fixture; sample the JVM while it is live, then wait for the watcher:
python3 harness/v2/profile.py capture --events "$P" --case 06-001 --phase active --room work/06-001
wait "$VM_WATCH_PID" || echo 'VM was not observed; preserve the profile gap'
# Close stdin, wait for normal runner exit, stop the stub model, then capture survivors:
python3 harness/v2/profile.py capture --events "$P" --case 06-001 --phase after --room work/06-001
```

Use `mark` to bind the scenario's observed run ID, Grain trace ID and model request count to the
profile when those exist: `python3 harness/v2/profile.py mark --events "$P" --case 06-001
--name trace-id --value <id>`. Read ORC's node and prompt evidence from the Grain store and the
stub model's request log, not from process guesses. The prompt optimizer is a later spec round;
grading records its evidence and does not change instructions.
Record exactly one `model-requests` mark for each case, including `0` when the model was not
called. If the fixture never created a Grain store, record one `no-store` mark with the reason as
its value. Every case has either a Grain profile or a `no-store` reason.

For each scenario that opened a Grain store, attach ORC's own event and trace profile. The reader
copies the SQLite store and WAL before opening them. It reports tick lineage, node names and
outcomes, durations, token usage where present, prompt instruction digests and event counts. It
keeps raw prompts and blackboard values in the room's store for later approved GEPA work.

```bash
python3 harness/v2/profile.py store --events "$P" --case 06-001 \
  --store work/06-001/events.sqlite --output work/06-001/orc-profile.json
```

If a scenario keeps its JVM running long enough to sample, capture it with the Java 21 tools
through `profile.py jvm`. It writes a heap summary, GC counters and a thread dump under that
item's `work/` folder; the filed report holds their hashes and thread-state counts. The VM and
JVM must still be stopped at the `after` boundary. JVM measurements diagnose a failure or a slow
case; they are not exact thresholds.

```bash
python3 harness/v2/profile.py jvm --events "$P" --case 06-001 \
  --pid <candidate-jvm-pid> --output work/06-001/jvm
```

For a slow or stuck case, run a bounded Java Flight Recorder sample:

```bash
python3 harness/v2/profile.py jfr --events "$P" --case 06-001 \
  --pid <candidate-jvm-pid> --seconds 10 --output work/06-001/jfr
```

Keep the `.jfr` under `work/`; the filed report carries its digest and size. The JFR sample
diagnoses JVM CPU, allocation, lock and GC behavior. Use ORC's Grain trace for Clojure leaf and
model-call attribution.

After all verdicts are final, write the profile report. It checks that every checklist item has
one `before` and `after`, in order, with no room-owned VM process at either boundary, and that
its model and Grain evidence is accounted for. It keeps timings and peak observed RSS as
diagnostics. It also writes `work/profile.traces.otlp.json` and
`work/profile.metrics.otlp.json`: OTLP/HTTP JSON with grading and item spans, probe events,
process memory, VM count and JVM thread metrics. If it reports `failed`, preserve its findings
and still file the honest item verdicts; put the profile problem in `surprises.json`.

```bash
python3 harness/v2/profile.py report --events "$P" --checklist checklist.txt \
  --verdicts work/verdicts.json --instruction .pi/harness/grader-instruction-06.md \
  --output work/profile.json
```

## Output

Write `work/verdicts.json`: a JSON array with exactly one object per line of `checklist.txt`, in
that order, and nothing else:

```json
[{"id": "06-001", "verdict": "pass", "evidence": "what you set up, what you sent, what you observed"}]
```

`id` is the item id without `@item-` (`06-001`). `verdict` is `pass` or `fail`. `evidence` is one
or two sentences of what you observed (the requests, the codes, the store events), and for a
fail, which Then did not hold. The evidence of an item you have not tried yet starts with `not
reached`. Write the file early (all `fail`, evidence `not reached`) and update it as you go, so an
interrupted grading still files every item. If you answer while items are still `not reached`,
you will be asked to continue with them.

Optionally, write `work/surprises.json`: `[{"id", "note"}]`, places where the spec was ambiguous
or the candidate did something the spec does not mention. A surprise never changes a verdict.

After `work/profile.json` exists, export and verify Allure results:

```bash
bb harness/v2/allure.clj export --checklist checklist.txt --verdicts work/verdicts.json \
  --profile work/profile.json --out work/allure-results
bb harness/v2/allure.clj verify --checklist checklist.txt --verdicts work/verdicts.json \
  --profile work/profile.json --out work/allure-results
```

Each checklist item has one `{uuid}-result.json` in the [Allure test result format](https://allurereport.org/docs/how-it-works-test-result-file/).
Its `labels` include `{"name":"tag","value":"item-06-001"}` for `@item-06-001`;
`status` maps `pass` to `passed` and `fail` to `failed`. A JSON attachment holds the
case's profile summary. The profile's own `failed` status stays visible in that attachment
and never changes an item verdict. Allure is a view of `verdicts.json`, not a second score;
the OTLP trace and metric files remain separate.

When every item has a verdict, `work/profile.json` exists, and Allure verification passes,
answer with one line: the number of items, passes, fails and the profile status.
