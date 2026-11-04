# Pi Harness Event Model

> **Current artifact:** a generated, navigable projection of the Pi-harness behavioral model.

The GitHub Pages entry point is rebuilt from `model/pi-harness`: Allium behavior, Gherkin examples, the Grain Event Model, and the maintained C4 diagrams. Löyly appears only as an example project where the harness model refers to it.

The generated page provides four connected views:

```text
behavior modules → EventStorming rules, refusals, invariants and examples
Grain topology  → commands, facts, projections, queries and flows
C4 architecture → context, containers and ORC components
review          → active E2E, explicit skips and implementation conformance
```

The earlier visual essay and Three.js experiments remain prototype history, but they are not loaded by or bundled into the published page.

## Why Svelte

This artifact needs application-like navigation and filtering, but not a server framework. Svelte keeps the EventStorm cards, source drill-down, review filters, and Mermaid lifecycle as small HTML-like components. Vite supplies a static build that GitHub Pages can serve directly.

The published bundle contains Svelte, the extracted model data, and a lazy Mermaid chunk used only on the C4 route. It does not load a game engine or the old Three.js prototype.

## Model boundaries

- Pi explores intent, composes the system view, and presents an exact deterministic selection for human approval.
- ORC executes an immutable approved contract and owns bounded prompts, constrained tools, retries, termination, verification, grading, and durable evidence.
- A Fresh Slice Generation Cycle begins without a parent candidate or accepted product oracle.
- A Measured Slice Regeneration Cycle begins from a named measured parent and an accepted product oracle.
- The builder cannot declare completion. Verification and candidate-blind grading exercise the public seam.
- A cycle oracle is provisional. Only a complete pass and manual approval of the exact proposal digest can publish an accepted product oracle.

## Generation stack

- `event-model/extract.clj` reads the committed model without executing it.
- `event-model/build.sh` writes the extracted projection to `src/event-model/model.json`.
- Svelte owns navigation, EventStorming views, topology, evidence review, and responsive interaction.
- Mermaid is loaded on demand to render the three maintained C4 structural views.
- Vite emits the static `dist/` directory deployed to GitHub Pages.

## Run

```bash
cd spikes/3d-agentic-software-factory
npm install
npm run spike
```

Open <http://127.0.0.1:5173/loyly/> and scroll.

The alternative library and factory-layout research remains in `animation-library-research.md` and `automotive-assembly-layout-research.md`.
