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

The physical footprint is grounded in first-party descriptions of Toyota, BMW,
Mercedes-Benz, and Ford production rather than the C4 graph's automatic topology. See
[`automotive-assembly-layout-research.md`](automotive-assembly-layout-research.md). The dominant
vehicle path is a folded one-way line with side-fed infrastructure, overhead C4 data/utility
gantries, end-of-line testing, an inspection-adjacent rework loop returning to test, and a
finished-vehicle yard.

## World analogy

- **The Pi/ORC/Grain harness is the assembly line.** Its real C4 elements are the machinery,
  inspection stations, stores, and routes.
- **Löyly is the vehicle.** The moving 3D vehicle is the generated candidate application, not the
  harness itself.
- **A target language is a vehicle model.** Rust Rally, Java Touring, Rails Coach, and Node
  Modular have distinct 3D silhouettes but are manufactured from the same pinned Löyly product
  specification and judged against the same behavioral contract.
- **Grain is the vehicle history.** It records durable build, observation, and measurement facts;
  it does not move the assembly line forward.
- **Independent graders are vehicle inspection.** Builder claims and fitted telemetry cannot
  author the final quality verdict.

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

Select a station and choose **Zoom into station**, or double-click it, to enter a close inspection
view. Its live feed shows whether the station is processing the current Löyly model, waiting on an
operator repair decision, queued for a later stage, or complete. The feed also lists the station's
actual incoming and outgoing C4 relationships. **Return to overview** flies back to the full line.

## What this spike is testing

1. Whether Pi stages become understandable when represented as a physical material flow.
2. Whether an append-only Grain event stream is a useful game mechanic and debugging surface.
3. Whether independent grading feels meaningfully separate from builder activity.

The model seam is intentionally explicit in `src/main.js`: stage definitions mirror
`model/pi-harness/execution-pipeline.allium`, while authority labels mirror
`model/pi-harness/runtime-evidence.allium` and `docs/orc-harness-boundary.md`.
