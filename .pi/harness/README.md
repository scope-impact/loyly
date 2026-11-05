# Pi harness: builder, graders, and the regenerated harness

The legacy harness in [`../../harness/`](../../harness/) regenerates a harness from the
contract in [`../model/`](../model/). This folder holds what that loop needs beyond the
legacy tools, and what it produces:

| Path | What it is |
|---|---|
| `src/builder-worker.ts` | Starts one fresh Pi/Codex builder session over a clean room, writing only `APP_DIR`. Reads a request on stdin, writes one JSON result on stdout. A session that ends on a model error fails and names it. |
| `src/builder-room.ts`, `host-exec.ts`, `gondolin.ts`, `execution-backend.ts` | The room the builder works in: the clean room read-only, the workspace writable. Host commands run in their own process group, so nothing a builder starts outlives its command. |
| `src/builder-session-store.ts`, `builder-outcome.ts` | The builder session's durable transcript and its outcome. |
| `instrument/` | Sources of the sealed graders and `calibrate.py`. A grader is calibrated carried/uncarried, sealed by digest under `.state/graders/sealed/`, and used only when its digest is in [`../../harness/graders.approved.json`](../../harness/graders.approved.json). |
| `harness-impl/` | The regenerated harness promoted from cycle 08, built on ORC and Grain from the contract alone. |
| `config/` | Runtime and VM configuration used by `harness/Makefile`. |
| `.state/` | Run state: clean rooms, builder sessions, candidates, sealed graders. Ignored by git. |

```bash
npm ci
npm test
```
