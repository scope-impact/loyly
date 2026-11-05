# Pi Harness Event Model

> **Current artifact:** a generated, navigable projection of the Pi-harness behavioral model.

The GitHub Pages entry point is rebuilt from `model/pi-harness`: Allium behavior, Gherkin examples, the Grain Event Model, and the maintained C4 diagrams. Löyly appears only as an example project where the harness model refers to it.

The generated page provides four connected views:

```text
behavior modules → EventStorming rules, refusals, invariants and examples
Grain topology  → commands, facts, projections, queries and flows
C4 architecture → context, containers and ORC components
review          → active E2E, explicit skips and implementation conformance
comment mode    → semantic local review threads anchored to generated model identities
```

The earlier visual essay and Three.js experiments remain prototype history, but they are not loaded by or bundled into the published page.

## Why generated HTML

EventStorming is the primary behavioral view. The site therefore follows the Halla Event Model pattern: a Clojure extractor projects the committed specifications into JSON, and a committed HTML template turns that projection into one self-contained, hash-navigated page.

There is no application framework. The generated HTML contains the projection renderer and navigation, while Vite bundles only the independent review adapter. The page fetches the separately generated, deeply frozen `event-model.json`; routing, filtering, diagram state, and review state never mutate that model. Mermaid is used only for the supporting C4 route.

## Comment and review mode

Choose **Comment** and then select a behavior module, command, rule, refusal, scenario, Grain block, or C4 view. Each thread is attached to a deterministic semantic identity and records the source commit, whole-model digest, target digest, and the text the reviewer saw. After regeneration, a thread is shown as unchanged, changed, or orphaned against the complete target index—not merely against the route currently on screen.

Review data is a separate local evidence store:

- The official SQLite WASM build runs in a dedicated worker.
- `opfs-sahpool` persists the database in browser OPFS without requiring cross-origin isolation from GitHub Pages.
- Review threads support replies and open/resolved state.
- JSON export/import provides an explicit backup and hand-off path; browser storage is not a shared service or backup.
- A thread can be promoted to a prefilled GitHub issue, but no comment can edit `event-model.json`, write into `model/`, or enter a fresh builder.

The browser UI exposes whether it has durable **Browser SQLite** or session-only fallback storage. OPFS is scoped to the browser profile and site origin, may be removed when site data is cleared, and this single-writer spike should be used from one active page tab at a time. The implementation and trade-off research are in `src/review/` and `html-annotation-review-research.md`.

## Model boundaries

- Pi explores intent, composes the system view, and presents an exact deterministic selection for human approval.
- ORC executes an immutable approved contract and owns bounded prompts, constrained tools, retries, termination, verification, grading, and durable evidence.
- A Fresh Slice Generation Cycle begins without a parent candidate or accepted product oracle.
- A Measured Slice Regeneration Cycle begins from a named measured parent and an accepted product oracle.
- The builder cannot declare completion. Verification and candidate-blind grading exercise the public seam.
- A cycle oracle is provisional. Only a complete pass and manual approval of the exact proposal digest can publish an accepted product oracle.

## Generation stack

- `event-model/extract.clj` reads the committed model without executing it.
- `event-model/template.html` owns the EventStorm HTML structure and rendering.
- `event-model/build.mjs` generates presentation-only `index.html` from the template.
- `event-model/build.sh` regenerates `index.html` and the separate `public/event-model.json` data projection.
- Mermaid renders the three maintained C4 structural views on their supporting route.
- `src/review/bootstrap.js` owns comment interaction and semantic target reconciliation.
- `src/review/sqlite-worker.js` owns the separate local review database and import/export boundary.
- Vite bundles the review adapter and copies the presentation and data projection into `dist/` for GitHub Pages.

## Run

```bash
cd spikes/3d-agentic-software-factory
npm install
npm run spike
```

Open <http://127.0.0.1:5173/loyly/> and scroll.

The alternative library and factory-layout research remains in `animation-library-research.md` and `automotive-assembly-layout-research.md`.
