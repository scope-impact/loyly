# Pi harness ORC worker

This directory is the Clojure owner of the regeneration workflow. TypeScript
files under `.pi/harness/src/` are process and VM adapters only; stage ordering,
state transitions, retries, evidence interpretation, and scoring remain here.

The public execution boundary is `interface/submit-approved-slice!`: after slice approval,
ORC persists an operation route by request ID, then chooses ungraded generation when there
is no candidate lineage for the project/container/component or measured regeneration from the
latest candidate when lineage exists. Candidate outputs are durably recorded for later routing.
Tick and correlation identities are deterministic from the request ID; numeric wait-task
receipts remain process-local, while ORC owns durable workflow state and resumes abandoned
leaf frontiers on startup.

`execution-status!` reconciles that authoritative ORC status with the harness-owned Grain
projection of stage, attempt, and builder-session reference. `builder-session!` is a separate
read-only adapter over append-only Pi transcripts; neither query can advance ORC or mutate a
candidate.

## Reading order

1. `interface.clj` — public API used by entrypoints and consumers.
2. `workflows/` — ORC workflow definitions and their blackboard contracts.
3. `core/worker.clj` — workflow executors and task coordination. Its supporting
   implementation is split into lifecycle components under `core/`.
4. `grain/interface.clj` — public API for the Grain-backed harness facts.
   Its `core/commands.clj`, `core/read_models.clj`, and `interface/schemas.clj`
   follow the same CQRS structure as ORC components.
5. `entrypoint.clj` and `durable_entrypoint.clj` — executable runtime
   composition. They translate environment or stdin data into calls on the
   public interface; they do not own workflow behavior.

## Namespace layout

The layout mirrors ORC components:

```text
loyly.pi-harness
├── interface                 public consumer boundary
├── core/worker               serialized ORC executors and coordination
├── core/build/*              prompt rendering, project profiles, candidate sealing
├── core/grading/*            Compose and independent grade interpretation
├── core/evidence/*           manifests and Allure evidence projections
├── core/oracle/*             product/instrument OCI oracle resolution
├── core/runtime/*            process adapters, transport, durable system
├── core/selection/*          cut-slice selection resolution
├── workflows/*               ORC behavior-tree definitions
├── grain/interface           public Grain model boundary
├── grain/interface/schemas   Grain command/query/event schemas
├── grain/core/*              Grain commands and read models
├── entrypoint                demo/in-process runtime composition
├── durable-entrypoint        SQLite/LMDB runtime composition
└── prompt                    persisted-executor compatibility boundary
```

Tests mirror these ownership namespaces under `test/loyly/pi_harness/`.
Consumers must require `loyly.pi-harness.interface` or
`loyly.pi-harness.grain.interface`, not a `core` namespace.

## Dependency direction

```text
entrypoint / durable_entrypoint
              |
              v
           interface
               |
       +-------+--------+
       v                v
 core.worker       workflows
       |
  +----+--------------------------+
  v                               v
grain.interface          core lifecycle components
  |                       |              |
  v                       v              v
Grain + ORC        core.runtime.adapters  pure interpretation
interfaces                 |
                           v
                 core.runtime.json-process
                           |
                           v
                   TypeScript workers
```

Supporting components must not require `interface` or `core.worker`. In
particular, `core.runtime.adapters` must not decide stage order, retry policy,
scoring, or prompt selection. Domain modules must persist through Grain's
command-to-event path rather than append events directly.

The `grain/` package is intentionally narrow. ORC task receipts, workflow
stages, prompt optimization, evidence files, Allure reports, and product-domain
events do not belong there because the canonical event model does not declare
them as harness-owned Grain facts.

`core.worker` retains the executor Vars named by serialized ORC code nodes.
The root `prompt` namespace similarly retains
`loyly.pi-harness.prompt/expose-candidate-instruction`; its implementation is
in `core.build.prompt`. Moving either executor boundary requires updating the
`:fn` value in `workflows/` and considering already-persisted workflow versions.
`orc_worker.clj` is only a temporary source-compatibility façade; new code must
use `interface.clj`.

## Checks

From the repository root:

```bash
.pi/harness/orc-worker/check.sh
```

The check runs clj-kondo with local hooks for Grain's `defcommand`,
`defreadmodel`, and `defschemas` macros, followed by the complete Clojure worker
test suite.

The model-to-ORC RPC conformance E2E is TypeScript/Vitest and projects every
executable modeled step through `allure.step`:

```bash
cd .pi/harness
npm run test:e2e
```

It parses every component-owned scenario directly from `.pi/model/`—there is no
duplicate E2E or coverage feature—and lints it against one explicit native Vitest test per
scenario. Translated `@e2e` scenarios query the real `workflow-contract` JSON boundary and
emit Given/When/Then `allure.step` records; untranslated scenarios use native `test.skip` and
remain visible in Vitest's run summary.
The current passing scenarios cover fenced cut identity, deterministic selection and approval
refusals, known and unknown task waiting, prompt-input refusal, the ordered ORC pipeline, the
separately identified builder session, and artifact digests on persisted observations.
