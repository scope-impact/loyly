# Regeneration Yard — open-world factory prototype

> **Throwaway prototype question:** Does moving through the factory as a player make the Grain/Pi/Löyly model easier to understand than operating a dashboard?

Three variants of the same agentic factory are switchable with `?variant=` on the existing spike route:

- `A` — **Walk the line.** A physics-driven third-person operator walks, runs and jumps through the factory. Entering a station's proximity exposes an inspection action.
- `B` — **Factory simulation.** An overhead, orbitable operations world makes the whole material flow and ten stations visible at once.
- `C` — **Guided tour.** The camera follows the active product and explains one station at a time.

Use `?prototype=1` to expose the floating variant switcher in a production build. In development it is always visible. Left and right arrow keys also change variants when a form control is not focused.

This is deliberately throwaway code on `spike/3d-agentic-software-factory`. It does not call the live Pi harness and it does not write Grain facts.

## What is real

- The C4 station names, types and relationships are parsed at runtime from the repository's `.pi/architecture.md` Container diagram.
- The factory activity replays the committed `e2e-full-1793630744537.zip` normalization in `src/e2e-replay.js`.
- The recording includes the failed first container attempt, fresh repair builder, independent 9/11 verdict, runtime signals and final recorded result.
- Löyly is the product moving through the line. Its Booking API, Booking Core, Event Store, Weekly Schedule, Heater Schedule and Billing Signals modules appear on the carrier as assembly progresses.

The replay runs at one quarter recorded speed so a player has time to move between stations. It remains historical playback, not a claim that a new regeneration is running.

## Game stack

- React Three Fiber renders the Three.js world.
- Rapier supplies the physical world and station colliders.
- Ecctrl supplies the physics-driven player, keyboard movement and mobile joystick/buttons.
- Ordinary DOM overlays provide accessible controls, the replay timeline and station evidence.

## Run

```bash
cd spikes/3d-agentic-software-factory
npm install
npm run spike
```

Open <http://127.0.0.1:5173/?variant=A&prototype=1>.

Desktop controls: `WASD`/arrow keys to move, `Shift` to run, `Space` to jump and `E` to inspect a nearby station. Mobile uses an on-screen joystick, jump and inspect controls.

## Interpretation

- **Pi/ORC is the assembly line:** it moves a bound product cut through build, repair and independent inspection.
- **Löyly is the product:** C4 components emerge on its carrier as the implementation is assembled.
- **Language is the product model:** the behavior and C4 boundary stay stable while the implementation profile changes.
- **Grain is the durable product history:** it records build and observation facts but does not move the line forward.
- **Independent graders are the quality gate:** builder claims cannot author the product verdict.

The alternative factory-layout research remains in `automotive-assembly-layout-research.md`.
