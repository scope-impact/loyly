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
| `.state/` | Run state, ignored by git; the root `PI_HARNESS_STATE_ROOT` overrides. Spec v1 (`harness/regen.clj`): `rooms/<run-id>`, `outputs/`, `builder-sessions/`, `graders/sealed/`, `cycles/`. Spec v2: `rooms/NN-cMM/` and `NN-cMM.fence.log` (each slice's clean room and candidate), `prompts/NN-cMM/`, `grading/NN-cMM/`, `logs/` (gate, score, assembly), `gate/` and `assemble/` (their work directories), `runs/` (the cycle runner's `LOYLY_RUNS_DIR`), `shadow/run-0N/` (the switch's shadow runs), `audit/`; `assets/` and `runtime-images/` (the VM images), in a worktree a link to the main checkout's. `MAP.md` records where each tree first lived. The layout: `../../harness/README.md`, "Where a spec-v2 cycle's run state goes". |

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

Recorded 2026-11-08 from the shadow runs' evidence (`.state/shadow/run-01` to `run-05`,
`evidence/inputs.txt` and `bin/builder.sh`; `../spec/decisions.md`, "The builder command the
shadow runs used"); the same in all five runs. `LOYLY_BUILDER_CMD <builder-room>
<instruction-file>` ran, with the builder room as its working directory and
`GONDOLIN_GUEST_DIR=$PI_HARNESS_STATE_ROOT/assets/builder`, that is
`.pi/harness/.state/assets/builder` of this checkout (in a worktree, `assets` links to the main
checkout's), the TypeScript builder VM image that `harness/Makefile` builds. A cycle runner's
runs directory, `LOYLY_RUNS_DIR`, goes under the same root: `.pi/harness/.state/runs`.

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

The maintained wrapper is [`bin/builder.sh`](bin/builder.sh) (spec round 9, D4 O5;
`../spec/decisions.md`, "Spec round 9"). It is the shadow runs' wrapper with its paths taken
from the environment (`PI_GONDOLIN_EXTENSION`, `BUILDER_EVIDENCE_DIR`, `PI_HARNESS_STATE_ROOT`)
and one change: a third argument, `<builder-image>`, which the runner hands in when the
configuration names `BUILDER_IMAGE`, becomes `GONDOLIN_GUEST_DIR`; without it the guest is
`assets/builder` as before. `harness/config.sh` names `BUILDER_IMAGE=builder-clojure` for every
spec-v2 slice, so the runner's environment gains `LOYLY_BUILDER_IMAGES=$PI_HARNESS_STATE_ROOT/assets`
(and, for D6, `LOYLY_MEASUREMENT_STORE`, one file outside the repository, for example
`.pi/harness/.state/measurement.sqlite`).

`assets/builder-clojure` is built by `make -C ../../harness builder-clojure` from
`harness/loyly/Dockerfile.clojure`: `pi-harness/java:21-web` (JDK 21) with the Clojure CLI
1.12.6.1673, babashka 1.13.226 and git, all in `/usr/bin`, because a Gondolin guest keeps none
of the image's `ENV` (its `PATH` is `/usr/sbin:/usr/bin:/sbin:/bin`, and `/root` is a tmpfs).
Java trusts Gondolin's TLS proxy through `java-cacerts`, regenerated when the guest boots.
