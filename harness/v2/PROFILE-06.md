# Slice 06 grading profile

## Allure result projection

The grader writes one [Allure test result file](https://allurereport.org/docs/how-it-works-test-result-file/)
per stamped checklist item under `work/allure-results/`. The Allure `tag` label is the
stamped item ID without `@` (`item-06-001`), and the suite is `cycle-runner`.
The item verdict maps to Allure `passed` or `failed`; the evidence is its description
and, for a failed item, its status message. A case profile summary is a JSON attachment,
not an extra test. `testCaseId` and `fullName` are stable for the item across cycles.
The Allure output is verified against the authoritative checklist, verdicts and profile
before scoring. OTLP traces and metrics remain separate diagnostic artifacts. This is a
reporting projection: it does not move a golden or change an item verdict.

Cycle 16 passed its gate and all 61 checklist items, but its grader observed Gondolin QEMU
children still alive after grading. A host probe reproduced the cause: stopping the Gondolin CLI
reparented its QEMU child to PID 1; stopping the child cleared it. The candidate is not accepted.
The requirement to stop both already appears in spec round 20 and the cycle-runner seam.

The next slice-06 grader version adds a sidecar profile. For each checklist case it takes a
before snapshot, optional live samples, and an after snapshot before manual cleanup. The exact
check requires zero room-owned VM and runner processes at the boundaries. It records the model
request count, and either a Grain profile or a reason no store was created. The Grain reader
copies the SQLite store and WAL, then uses Grain's own reader to collect ORC trace lineage, node
outcomes, durations, event counts, instruction digests, and token usage when supplied. It does
not inspect candidate source.

Java 21 `jcmd`, `jstat`, and bounded JFR samples add thread, heap, GC, CPU, allocation and lock
evidence for a live JVM. Wall time, RSS and sampled JFR values are diagnostic rather than exact
grading thresholds. Raw command lines and prompts stay out of the filed profile; raw stores and
JFR files remain in ignored grading work. The report is digest-bound to the checklist, verdicts
and grader instruction. It emits OTLP/HTTP JSON trace and metric payloads following ADR 0005:
one grading trace, child spans per item, events for snapshots and Grain/JVM evidence, and metrics
for observed process memory, VM count, and thread count. The score files both payloads and their
digests. A failed profile accompanies the honest score and blocks acceptance.
This change to the grading instrument does not add a checklist item or alter cycle 16's score.

ORC's `ORC-PRINCIPLES.md` calls for a deterministic tree around model work, event-first evidence,
adversarial verification, and bounded cleanup. Its trace and GEPA guides show that node
instructions are versioned and GEPA can optimize them from train and validation examples. The
profile preserves node and instruction provenance for later dataset selection. Grading does not
run GEPA or mutate instructions. The revised grader digest and cycle 17's exact selection require
operator approval before the grader is used or the cycle is fenced.

The payload follows the [OTLP JSON encoding](https://opentelemetry.io/docs/specs/otlp/) with
`resourceSpans` and `resourceMetrics`. It uses the published
[`process.memory.usage` and `process.thread.count` conventions](https://opentelemetry.io/docs/specs/semconv/system/process-metrics/)
and keeps Löyly-specific checks under `loyly.*` attributes and metrics. No raw prompt content is
exported.

## Cycle 17 finding and next instrument revision

Cycle 17's sealed grader scored all 61 items as passing, but its profile failed: an extra active
capture for 06-073 was recorded after that case's after boundary. Its scripts also sampled the
`start.sh` launcher PID before identifying the Java child, so no JVM attachment succeeded. The
filed cycle 17 profile and score remain unchanged. This is a recorded instrument-behavior reason
to revise the grader procedure before another cycle; it is not a reason to alter any behavior
verdict.

`profile.py capture` now refuses a capture outside the before → active* → after sequence before
appending it. A live capture returns the owned Java PIDs, so the grader can attach `jcmd`, `jstat`
and JFR to the actual candidate JVM. The revised grader instruction requires that PID and records
the absence of a live Java PID honestly. This revision changes the sealed instruction digest and
requires the operator's approval before use.
An isolated live Java 21 probe found the owned JVM PID and captured its version, heap, GC and
thread dump through `jcmd`/`jstat`; the focused profiler tests also pass.

## Cycle 18 finding and next instrument revision

Cycle 18 again scored 61/61, and its full instrument shadow against legacy had already agreed,
but the filed profile failed on 54 after boundaries. The grader captured while its JSON-RPC
runner was still live, then stopped it. That does not distinguish normal server lifetime from an
orphan after shutdown. The profile also sampled no live QEMU in any of the 31 cases whose Grain
store records `grade.v1`, so its VM resource view missed the very grading interval it sought to
measure. The cycle 18 profile and score remain unchanged.

The next instruction closes stdin and waits for the normal runner exit before `after`, then
captures any remaining runner or VM process before manual cleanup. `profile.py watch` polls the
owned process tree during the blocking run and records the first live QEMU observation. The
report now fails any case with a `grade.v1` trace but no live VM sample. JVM and JFR evidence
remain diagnostic. This is a second recorded instrument-behavior reason to revise the sealed
grader; the new instruction digest needs operator approval before use.
An isolated Gondolin run verified the watcher saw QEMU while the guest slept and that the after
snapshot saw no owned process once the CLI exited. Eight focused profile tests pass.
The round 23 instruction also gives the grader an explicit gitlink fixture for the clarified
`@item-06-013`; the sealed digest therefore covers both changes.

## Cycle 19 finding and next instrument revision

Cycle 19's grader scored 59/61 and its profile passed the VM and shutdown checks, with live
QEMU observed in 31 cases. It saw a live candidate Java PID in every case but captured no JVM
diagnostic in any case. A separate scratch run of the same candidate used `profile.py jvm`
successfully to capture heap, GC, threads and version data, so the gap was in the grading
procedure. The filed cycle 19 score and profile remain unchanged.

For the next grader, `profile.py report` fails when an active capture observes a candidate
Java PID but the whole cycle has no JVM diagnostic matching a live case and PID. The grader
instruction requires at least one successful attachment while that JVM is alive and records
failed attachments for diagnosis. This is a recorded instrument-behavior change; it alters
neither the 61-item selection nor any item verdict. The revised sealed digest requires
operator approval before use.
