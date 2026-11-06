# Pi harness: provided builder runtime

The legacy harness in [`../../harness/`](../../harness/) generates the Pi harness cleanly
from the contract in [`../model/`](../model/) into `harness-impl/`. Nothing here is generated.
This folder holds what the spec treats as provided, like ORC and Grain, and stays in the clean
room so the generated harness can use it.

| Path | What it is |
|---|---|
| `config/`, `fixtures/platforms/`, `scripts/verify-platforms.mjs` | The Gondolin builder and grader VM configuration that `harness/Makefile` builds and verifies, and the platform probes it runs in the grader VM. |
| `builder-host/` | **Not built yet.** The durable Pi builder host (`@earendil-works/pi-durable`) that ORC launches once per builder tick, forwarding tool calls into the Gondolin room ORC chose (`.pi/model` 02-018, 02-019). Until it exists, `harness/regen.clj` refuses to regenerate. |
| `.state/` | Run state: clean rooms, builder sessions, candidates, VM assets, sealed graders. Ignored by git. |

The sealed graders the score runs live under `.state/graders/sealed/`, approved by digest in
[`../../harness/graders.approved.json`](../../harness/graders.approved.json).

```bash
npm ci
npm test
make -C ../../harness bootstrap   # Gondolin builder and grader VM images
```
