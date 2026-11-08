# Pi harness: provided builder runtime

Nothing here is generated. This folder holds what the spec treats as provided, like ORC and
Grain, and stays in the clean room so a generated harness can use it.

Since the switch (`../spec/decisions.md`, "The switch to the new harness"), the new harness runs
the regeneration cycles: the slice-06 cycle runner drives the five contexts of slices 01-05
(spec v2, [`../spec/`](../spec/README.md)). The builder is `pi` with its Gondolin backend on the
existing Codex login; the cycle runner launches it as `LOYLY_BUILDER_CMD <builder-room>
<instruction-file>` and waits for it, one builder per run at a time
([`../spec/cycle-runner/seam.md`](../spec/cycle-runner/seam.md)); the exact `pi` command the
shadow runs used is below, "The builder command". The
legacy harness in [`../../harness/`](../../harness/README.md) stays as the reference.

| Path | What it is |
|---|---|
| `config/`, `fixtures/platforms/`, `scripts/verify-platforms.mjs` | The Gondolin builder and grader VM configuration that `harness/Makefile` builds (into `.state/assets/`) and verifies, and the platform probes it runs in the grader VM. |
| `builder-host/` | **Not built.** The durable Pi builder host (`@earendil-works/pi-durable`) that ORC would launch once per builder tick, forwarding tool calls into the Gondolin room ORC chose (spec v1, `.pi/model` 02-018, 02-019). The cycle runner does not need it: it launches `pi` directly. It remains an option for a run that resumes after a restart, which the switch leaves open. `harness/regen.clj`, the spec-v1 driver that generated from [`../model/`](../model/README.md), still refuses to regenerate without it. |
| `.state/` | Run state: clean rooms, builder sessions, candidates, VM assets, sealed graders. Ignored by git. |

The sealed graders under `.state/graders/sealed/`, approved by digest in
[`../../harness/graders.approved.json`](../../harness/graders.approved.json), were written for
spec v1. Spec-v2 cycles are scored the legacy way, from a grading session's verdicts file
(`../spec/decisions.md`, "How spec-v2 cycles are scored").

```bash
npm ci
npm test
make -C ../../harness bootstrap   # Gondolin builder and grader VM images
```

## The builder command

Recorded 2026-11-08 from the shadow runs' evidence (`run-01` to `run-05`, `evidence/inputs.txt`
and `bin/builder.sh`; `../spec/decisions.md`, "The builder command the shadow runs used"); the
same in all five runs. `LOYLY_BUILDER_CMD <builder-room> <instruction-file>` ran, with the
builder room as its working directory and
`GONDOLIN_GUEST_DIR=<main repository>/.pi/harness/.state/assets/builder` (the TypeScript builder
VM image that `harness/Makefile` builds):

```bash
pi -e <gondolin extension> --model openai-codex/gpt-6.1-sol --thinking high --no-skills --no-prompt-templates --session-dir <run's builder session dir> -p @<instruction-file> "Build it now, as the attached instruction says."
```

- `pi` 1.4.0, on the existing Codex (`openai-codex`) login: `pi auth check --model
  openai-codex/gpt-6.1-sol` reported ready, and no new login was made.
- `<gondolin extension>` is pi's own `examples/extensions/gondolin`, copied and run unmodified;
  its `node_modules` resolve `@earendil-works/gondolin` 0.14.0 from `.pi/harness/node_modules`.
  It routes pi's built-in tools into the Gondolin VM.
- The wrapper only recorded the launch (the instruction's digest, start, end, exit code) and
  pi's output beside the run's evidence; it changed nothing pi saw.
