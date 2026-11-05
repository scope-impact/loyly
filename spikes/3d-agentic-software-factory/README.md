# Pi Harness Event Model

> **Current artifact:** a generated, navigable projection of the Pi-harness behavioral model.

The GitHub Pages entry point is rebuilt from `.pi/model`: Allium behavior, stamped Gherkin slices under `.pi/model/plan/`, the Grain Event Model, and the maintained C4 diagrams. Löyly appears only as an example project where the harness model refers to it.

The generated page provides four connected views, with recorded room evidence overlaid on the primary EventStorm rather than split into another diagram:

```text
behavior modules → EventStorming rules, refusals, examples, and the Grain-recorded ORC brainstorm replay
Grain topology  → commands, facts, projections, queries and flows
C4 architecture → system context and containers
review          → active E2E, explicit skips and implementation conformance
comment mode    → semantic local review threads anchored to generated model identities
```

The earlier visual essay and Three.js experiments remain prototype history, but they are not loaded by or bundled into the published page.

## Why generated HTML

EventStorming is the primary behavioral view. The site therefore follows the Halla Event Model pattern: a Clojure extractor projects the committed specifications into JSON, and a committed HTML template turns that projection into one self-contained, hash-navigated page.

There is no application framework. The generated HTML contains the projection renderer and navigation, while Vite bundles only the independent review adapter. The page fetches the separately generated, deeply frozen `event-model.json`; routing, filtering, diagram state, and review state never mutate that model. Mermaid is used only for the supporting C4 route.

The room evidence overlay has its own isolated `architecture-brainstorm.json` data source, while sharing the EventStorm presentation vocabulary. It was extracted through Grain's event-store API and ORC read models from `.pi/harness/.state/rooms/harness-regen-9/harness-impl/.state`; the browser never opens SQLite or LMDB directly. It contains all 60 recorded events: 30 architectural facts and their 30 reified transaction receipts. Playback applies only the architectural facts, automatically scrolls the EventStorm to the affected ORC node, and exposes the matching transaction receipt and raw event.

This particular snapshot proves that the sheet, typed blackboard, workflow nodes, code executors, I/O edges, order, and final content hash were recorded. It does **not** prove a cycle ran: all six nodes are idle, every blackboard value is `nil` at version 0, and all recorded `:instruction` fields are `nil`. The UI preserves those absences instead of inventing prompts or execution.

## Comment and review mode

Choose **Comment** and then select a behavior module, command, rule, refusal, scenario, Grain block, or C4 view. Each thread is attached to a deterministic semantic identity and records the source commit, whole-model digest, target digest, and the text the reviewer saw. After regeneration, a thread is shown as unchanged, changed, or orphaned against the complete target index—not merely against the route currently on screen.

Review data is a separate local evidence store:

- The official SQLite WASM build runs in a dedicated worker.
- `opfs-sahpool` persists the database in browser OPFS without requiring cross-origin isolation from GitHub Pages.
- Review threads support replies and open/resolved state.
- JSON export/import provides an explicit backup and hand-off path; browser storage is not a shared service or backup.
- A thread can be promoted to a prefilled GitHub issue, but no comment can edit `event-model.json`, write into `.pi/model`, or enter a builder.

The browser UI exposes whether it has durable **Browser SQLite** or session-only fallback storage. OPFS is scoped to the browser profile and site origin, may be removed when site data is cleared, and this single-writer spike should be used from one active page tab at a time. The implementation and trade-off research are in `src/review/` and `html-annotation-review-research.md`.

## Model boundaries

- Pi explores intent, composes the system view, and presents an exact deterministic selection for human approval.
- ORC executes an immutable approved contract and owns bounded prompts, constrained tools, retries, termination, verification, grading, and durable evidence.
- ORC owns one cycle in the order `fence → regenerate → gate → score → scoreboard`.
- The fence and gate are the harness and may feed the build loop; score and scoreboard are the instrument and never do.
- The clean room contains the approved specification, domain sources, and public application contract—not prior implementations, measurements, reports, graders, or calibration fixtures.
- The builder cannot declare completion. The independent gate must boot the candidate fresh and prove its suite green.
- Scoring uses only an operator-approved sealed grader digest. A failed score is measured and never starts another regeneration within the cycle.

## Generation stack

- `event-model/extract.clj` reads the committed `.pi/model` contract without executing it.
- `event-model/extract-brainstorm.clj` reads the actual Grain stream and ORC projections; it never decodes SQLite or LMDB itself.
- `event-model/refresh-brainstorm.sh` refreshes the committed, read-only room projection from a selected generated harness implementation.
- `event-model/template.html` owns the EventStorm HTML structure and rendering.
- `event-model/build.mjs` generates presentation-only `index.html` from the template.
- `event-model/build.sh` regenerates `index.html` and the separate `public/event-model.json` data projection.
- Mermaid renders the maintained C4 system-context and container views on their supporting route.
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

To refresh the room evidence after the state changes:

```bash
cd spikes/3d-agentic-software-factory
bash event-model/refresh-brainstorm.sh \
  /path/to/.pi/harness/.state/rooms/harness-regen-9/harness-impl
npm run build
```

The refresh command runs on the generated implementation's Clojure classpath because that immutable candidate owns the exact Grain and ORC versions that wrote the state. GitHub Pages consumes only the generated JSON projection, never the disposable `.state` database files.

The alternative library and factory-layout research remains in `animation-library-research.md` and `automotive-assembly-layout-research.md`.
