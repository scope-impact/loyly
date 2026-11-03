# Regeneration Yard — throwaway 3D prototype

> **Prototype question:** What should an agentic software factory feel like as a 3D game when
> Grain owns durable facts and the Pi harness owns the regeneration cycle?

This is deliberately throwaway code on `spike/3d-agentic-software-factory`. It is a visual and
interaction model, not a production harness client. The simulation replays all game state from an
in-memory event log and uses the committed Pi pipeline vocabulary. No command reaches the real
harness.

The 3D world is not a hand-authored approximation of the architecture. Vite imports
`.pi/architecture.md` as source, and the prototype parses the repository's actual Mermaid C4
blocks at runtime. Nodes become buildings or data-store cylinders, C4 relationships become
labeled routes, and the selected regeneration stage highlights the responsible architecture
elements.

## Run

```bash
cd spikes/3d-agentic-software-factory
npm install
npm run spike
```

Open <http://127.0.0.1:5173/?variant=A>.

- `A` — Factory floor: spatial process and operator action.
- `B` — Evidence observatory: provenance and runtime evidence first.
- `C` — Command deck: dense stage control and intervention.

Use the bottom switcher or the left/right arrow keys. Press Space to start a cycle. The first
verification intentionally fails so the repair boundary is playable. Select a 3D element to see
its C4 type, description, and source view.

Use the **C4 world** selector to move between the repository's System Context, Container, and ORC
Worker Component views. Selecting a building or relationship shows the exact parsed C4 name, type,
description, and diagram. The full-state tab includes the active source line, parsed elements,
relations, and stage-highlighted element IDs.

## What this spike is testing

1. Whether Pi stages become understandable when represented as a physical material flow.
2. Whether an append-only Grain event stream is a useful game mechanic and debugging surface.
3. Whether independent grading feels meaningfully separate from builder activity.

The model seam is intentionally explicit in `src/main.js`: stage definitions mirror
`model/pi-harness/execution-pipeline.allium`, while authority labels mirror
`model/pi-harness/runtime-evidence.allium` and `docs/orc-harness-boundary.md`.
