# Cycle 19 exploratory diagnosis

The graded candidate passed the independent gate and scored 59/61. The two new
failures are reproducible from the grader's saved JSON-RPC outputs, with no
candidate modification:

| Item | Expected | Observed |
|---|---|---|
| `06-010` | Failed cycle at the fence with detail `golden checklist differs`; `self-check` is the last effect result. | Detail is `golden checklist differs\n`; `fail-cycle` is effect result 5 after `self-check` at position 4. |
| `06-060` | A gate answering on poll 5 of 20 exposes `boot-margin-polls: 15` in attempt 1. | The passed gate event has `boot-polls: 20` and `answered-on-poll: 5`, but the attempt in `cycle-progress` has no `boot-margin-polls`. |

## Causes

For `06-010`, `self-check-leaf` in the generated candidate's `src/loyly/runner.clj`
stores the shell's raw stdout as both the self-check detail and failure detail.
It parses the last nonblank line for `failing-check` but does not use that value
for the cycle failure. The fallback tree always calls `fail-leaf`, which records
`fail-cycle` as a runner effect. The previous cycle's candidate used the parsed
last line as the failure detail and let the context policy issue the cycle-failed
command without recording a second runner effect for this reason. The current
candidate's independently registered context policy callbacks are skeletal,
as its `LIMITATIONS.md` already reports.

For `06-060`, the gate records the two inputs to the calculation correctly.
The generated `src/loyly/domain.clj` installs a generic reducer that stores
each event by event ID. Its `cycle-progress` query merges those raw event
payloads per attempt, without applying the gate-attempt read model's
transition. The previous cycle's reducer derived `boot-margin-polls` as
`boot-polls - answered-on-poll` on `gate-attempt-passed`. The current query also
bypasses the declared read-model shape for other attempt fields, so the repair
should implement that read model's full transitions, not add one field to a
response as a special case.

## Profile check

The filed profile is clean: 61 cases, 31 with a live QEMU observation, 193 model
requests, 87,326 Grain events, and 193 ORC traces. Its JVM and JFR diagnostic
arrays are empty; `surprises.json` notes the missed JVM capture for all 61
cases. A separate scratch startup of the same candidate kept a live Java PID
long enough for `profile.py jvm` to capture `gc.txt`, `heap.txt`, `threads.txt`
and `version.txt` successfully. This is a grading procedure gap, not a Java
tool or candidate startup failure. The scratch probe did not alter the filed
score or profile.

Both implementation fixes belong in the next fenced regeneration cycle. The
cycle 19 generated candidate remains unchanged and unaccepted.
