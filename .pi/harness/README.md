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
| `builder-host/` | The durable Pi builder host ([`builder-host/host.ts`](builder-host/host.ts), `@earendil-works/pi-durable` 1.5.1, versions pinned in its `package.json`; `npm ci` there), which [`bin/builder.sh`](bin/builder.sh) runs as `LOYLY_BUILDER_CMD`. One durable session per builder step: the session, its SQLite store and the model credentials (pi's own `openai-codex` login, read through pi's `ModelRuntime` over `~/.pi/agent/auth.json`) stay on the host; every tool call runs in a Gondolin VM booted from `GONDOLIN_GUEST_DIR`, through pi-env's `RemoteExecutionEnv` and its daemon copied into the VM ([`vm-daemon.ts`](builder-host/vm-daemon.ts)). The tools are pi's own selection key, `defaultTools`, in a pi settings file ([`config/pi.builder.settings.json`](config/pi.builder.settings.json), or `LOYLY_BUILDER_PI_SETTINGS`): read, bash, edit, write (pi-durable's) and ls, find, grep (pi-coding-agent's, bound to the VM), the seven the old `pi -p` + Gondolin extension gave. The VM runs as root, mounts only the room at `/workspace`, and reaches only a shortlist of hosts over HTTP(S), by exact name: `registry.npmjs.org`, `repo1.maven.org`, `repo.clojars.org`, `github.com`, `codeload.github.com`, `objects.githubusercontent.com`, and `dl-cdn.alpinelinux.org`, the Alpine package mirror of both builder images (git over HTTPS works; there is no SSH). The image comes first: what a builder needs is built into its Dockerfile. A builder that still has to install something does it with `apk add` as root and appends the exact command to `/workspace/.builder/image-additions.txt`, outside `APP_DIR`, so it never enters the candidate digest; `builder.sh` copies that file to `launch-N/image-additions.txt`, for the Dockerfile's next edit. [`builder-host/policy.ts`](builder-host/policy.ts) holds these, because Gondolin's JSON (its build config) has no runtime keys for them; `vm-daemon.ts` enforces them (other hosts refused before any lookup, no other egress routed, a non-root guest refused). The builder's prompt section is generated from the settings, the image's Gondolin `manifest.json` and that policy; `launch.txt` records each with its sha256. **ORC keeps the workflow durable, pi-durable the session:** the store key comes from the caller, `LOYLY_BUILDER_SESSION_ID` (store `$BUILDER_EVIDENCE_DIR/sessions/<id>/session.sqlite`, request id `<id>`; without it, `launch-N/session.sqlite` and request id `launch-N`), so rerunning `builder.sh` with the same id reopens the store, resumes unfinished work and finds the existing submission instead of submitting again; the host keeps no workflow state: no attempts, no retries across sessions, no timeouts beyond pi-durable's own settings. Exit 0 answered, 1 unanswered (reason on `pi.stderr`; SIGTERM aborts the run, reason `aborted`), 2 setup error; `launch.txt` records `store=` and `request_id=`. Query a store read-only, also beside a running host: `bin/builder-status.sh <session.sqlite> [request-id]` (submission status and reason, non-terminal tasks, `next_seq`). Offline check, no model: `GONDOLIN_GUEST_DIR=<image> node smoke.ts ../.state/builder-host-smoke` (the generated prompt against the VM: root, only the allowed hosts, only the room; every tool through a faux model; resubmit), with `term` or `crash` as a second argument for the SIGTERM and SIGKILL-resume cases. `harness/regen.clj`, the spec-v1 driver that generated from [`../model/`](../model/README.md), predates it and still refuses to regenerate. |
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

Since 2026-11-10 (not yet reviewed) `bin/builder.sh` runs the durable builder host
(`builder-host/`, the table above) instead of `pi -p`, with the same arguments, image choice and
evidence (`launch-N/launch.txt`, `instruction-as-handed.md`, `pi.stdout`, `pi.stderr`), stdin from
`/dev/null`, and no `PI_GONDOLIN_EXTENSION`: the same model (`openai-codex/gpt-6.1-sol`, thinking
high), no skills or prompt templates, the same seven tools, in a Gondolin VM over the builder room.
One change on purpose: the old VM had Gondolin's default HTTP(S) egress to any public host; the
builder host's VM reaches only the package registries, GitHub hosts and Alpine mirror in `builder-host/policy.ts`.

`assets/builder-clojure` is built by `make -C ../../harness builder-clojure` from
`harness/loyly/Dockerfile.clojure`: `pi-harness/java:21-web` (JDK 21) with the Clojure CLI
1.12.6.1673, babashka 1.13.226 and git, all in `/usr/bin`, because a Gondolin guest keeps none
of the image's `ENV` (its `PATH` is `/usr/sbin:/usr/bin:/sbin:/bin`, and `/root` is a tmpfs).
Java trusts Gondolin's TLS proxy through `java-cacerts`, regenerated when the guest boots.
