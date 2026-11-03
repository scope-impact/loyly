# Regeneration Yard — hybrid 2D/3D prototype

> **Prototype question:** What should an agentic software factory feel like as a live control room when
> Grain owns durable facts and the Pi harness owns the regeneration cycle?

This is deliberately throwaway code on `spike/3d-agentic-software-factory`. It is a visual and
interaction model, not a production harness client. The simulation replays all game state from an
in-memory event log and uses the committed Pi pipeline vocabulary. No command reaches the real
harness.

The default 2D control-room map and optional 3D world are not hand-authored approximations of the architecture. Vite imports
`.pi/architecture.md` as source, and the prototype parses the repository's actual Mermaid C4
blocks at runtime. In 2D, nodes become compact control plates and relationships stay visible as a
schematic; in 3D, the same nodes become buildings or data-store cylinders. Recorded regeneration
signals highlight the responsible architecture elements in both views.

The physical footprint is grounded in first-party descriptions of Toyota, BMW,
Mercedes-Benz, and Ford production rather than the C4 graph's automatic topology. See
[`automotive-assembly-layout-research.md`](automotive-assembly-layout-research.md). The dominant
product-carrier path is a folded one-way line with side-fed infrastructure, overhead C4 data/utility
gantries, end-of-line testing, an inspection-adjacent rework loop returning to test, and a
accepted-product yard.

## Recorded E2E replay

The main control replays the repository's sealed `e2e-full-1793630744537.zip` fixture instead of
inventing a successful demo run. The normalized timeline in `src/e2e-replay.js` preserves its two
builder attempts, attempt-one container failure, repaired 13-test application run, eleven Gondolin
item verdicts, eight correlated runtime signals, instrument-conformance result, source artifact
paths, and terminal 9/11 product verdict. It is labelled as historical playback throughout the UI;
it does not imply a fresh regeneration.

Replay can be paused, stepped one recorded signal at a time, scrubbed, or accelerated. Scrubbing
rebuilds the projection from the start, so station activity, product position, evidence, score,
and the full-state view remain deterministic.

## World analogy

- **The Pi/ORC/Grain harness is the assembly line.** Its real C4 elements are the machinery,
  inspection stations, stores, and routes.
- **Löyly is the assembled C4 product.** A moving steel carrier begins empty. Booking API,
  Booking Core, Event Store, Weekly Schedule, Heater Schedule, and Billing Signals containers
  descend onto it as the build reaches their stages.
- **A target language is an implementation profile.** Rust, Java, Rails, and Node use the same C4
  product boundary and behavioral contract; the stack changes the container build, not the product
  architecture.
- **Grain is the product history.** It records durable build, observation, and measurement facts;
  it does not move the assembly line forward.
- **Independent graders are product inspection.** Builder claims and fitted telemetry cannot
  author the final quality verdict.

## Run

```bash
cd spikes/3d-agentic-software-factory
npm install
npm run spike
```

Open <http://127.0.0.1:5173/?variant=A>. The 2D map is the responsive default. Use the persistent
**2D map / 3D floor** switch or add `&view=floor` to a shareable URL.

- `A` — Factory floor: spatial process and operator action.
- `B` — Evidence observatory: provenance and runtime evidence first.
- `C` — Command deck: dense stage control and intervention.

The alternative layout experiments are intentionally hidden from the primary journey. Add
`&prototype=1` to expose their bottom switcher and left/right keyboard controls. Press Space to
replay or pause the recorded cycle. Its first attempt really failed, so the repair boundary remains
visible without fabricating a fault. Select any 2D or 3D element to see its C4 type, description,
and source view.

Use the **C4 world** selector to move between the repository's System Context, Container, and ORC
Worker Component views. Selecting a building or relationship shows the exact parsed C4 name, type,
description, and diagram. The full-state tab includes the active source line, parsed elements,
relations, and stage-highlighted element IDs.

Select a map station and choose **Open 3D station** (or select **Zoom into station** on the floor)
to enter a close inspection view. Its live feed shows whether the station is processing the current Löyly model, waiting on an
operator repair decision, queued for a later stage, or complete. The feed also lists the station's
actual incoming and outgoing C4 relationships. **Return to overview** flies back to the full line.

## What this spike is testing

1. Whether Pi stages become understandable when represented as a physical material flow.
2. Whether an append-only Grain event stream is a useful game mechanic and debugging surface.
3. Whether independent grading feels meaningfully separate from builder activity.

The model seam is intentionally explicit in `src/main.js`: stage definitions mirror
`model/pi-harness/execution-pipeline.allium`, while authority labels mirror
`model/pi-harness/runtime-evidence.allium` and `docs/orc-harness-boundary.md`.
