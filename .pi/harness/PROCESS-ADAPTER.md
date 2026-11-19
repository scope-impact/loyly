# Agent process entrypoints

The provided entrypoints `bin/builder-process.sh` and `bin/grader-process.sh` select an
agent host. ORC owns the cut, rooms, attempt budget, gate, checklist, deadline,
score, and Grain events. The host owns one agent session. Pi in Gondolin remains
the default for both roles.

| Role | Command | Default host | External host setting |
| --- | --- | --- | --- |
| Builder | `builder-process.sh ROOM INSTRUCTION [IMAGE]` | `bin/builder.sh` | `LOYLY_BUILDER_ADAPTER=process` and `LOYLY_BUILDER_PROCESS_HOST=/absolute/executable` |
| Grader | `grader-process.sh ROOM INSTRUCTION IMAGE` | `bin/grader.sh` | `LOYLY_GRADER_ADAPTER=process` and `LOYLY_GRADER_PROCESS_HOST=/absolute/executable` |

The entrypoint passes arguments separately and uses `exec`, preserving the selected
host's exit status and termination path. It never evaluates a command string. A
Pi builder session named by ORC as `run/tick/attempt` becomes a stable
`orc-<sha256>` Pi session key; the original ID is recorded beside the session
store as `orc-session-id`. This keeps the existing Pi host's safe-key check intact
and makes a resumed ORC attempt reopen the same Pi session. A process host can
run Codex, Claude, or another agent, but must implement the role's
room, evidence, session, and isolation contract. Pointing the setting straight at
an agent CLI is insufficient: its prompt or read-tool deny list does not confine
its shell to the room.

The accepted ORC runner can use the builder entrypoint by setting
`LOYLY_BUILDER_HOST` to the absolute path of `bin/builder-process.sh`. Its grader
is still `grade.v1` inside ORC. `grader-process.sh` currently serves external
bootstrap grading. ADR 0008 proposes a versioned ORC grader workflow that invokes
the entrypoint per item, validates its structured result, and records the judge
score. That switch requires a spec round, a regenerated slice-06 build, a
same-candidate instrument shadow, and acceptance. Exit code or final prose is
never a verdict.

The process host must record its selected model, instruction digest, session
identity, start and stop, and trace. The runner checks builder and grader models
differ. Room and VM policy must deny unrelated source, prior verdicts, and host
credentials. Each new provider host needs an isolation and cancellation probe
before it is used for a scored cycle.

## Isolated rehearsal, 2026-11-18

The overlay ran the default Pi hosts against one selected product item,
`@item-01-001`, with its original booking-core feature preamble. The builder room
held that item and no prior `impl/`; the grader got a separate copy of the gated
candidate and the selected spec. Both hosts used the `builder-clojure` Gondolin
image. The builder used `openai-codex/gpt-6.1-sol`, the grader
`openai-codex/gpt-6.1-terra`. A builder-only correction added the preamble's
90-minute duration before the final gate and grading room were made.

The fresh gate copy passed 4 tests with 81 assertions. Its HTTP response showed
Tuesday slots for dressing room 8, none for room 9, Thursday through Saturday
slots for room 9, and 90-minute spans. The independent grader filed one pass
with those HTTP observations. Its recorded tool calls read the selected spec,
started the app, used curl, wrote the verdict, and stopped the app; none read
candidate source or tests. This is an exploratory one-item rehearsal, not a
filed score or an accepted `grade.v2` cycle.

A second focused probe used the accepted ORC `cycle.v1` runner. ORC fenced a
fixture repository, launched the Pi builder through `builder-process.sh`, then
gated, graded, and scored its one item. The fixture supplied a stub gate and
stub model, as the runner's existing identity probe does. The overlay mapped
ORC's `run/tick/attempt` ID to a stable Pi key and recorded the original.
This proves the ORC-to-Pi process path; the separate live one-item rehearsal
above proves a Pi grader can file HTTP evidence. The accepted ORC grader still
runs `grade.v1` and has not been replaced by a process host.
