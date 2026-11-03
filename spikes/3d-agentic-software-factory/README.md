# The Next Body — complete interactive animatic

> **Throwaway prototype question:** Can a warm, character-led Three.js short make Phoenix architecture emotionally legible before the audience sees its technical detail?

The complete fifteen-beat story has procedural animatic characters, directed cameras, captions, chapter seeking, evidence inspection, and post-film exploration. Three treatments are switchable with `?variant=`:

- `A` — **Cinema:** a quiet widescreen presentation with evidence hidden until requested.
- `B` — **Living storybook:** the complete chapter journey stays visible and directly navigable.
- `C` — **Inside Runi:** character and evidence share the frame as an anatomical cutaway.

This is an animatic, not final character art. Procedural Three.js characters establish staging, silhouette, emotion, and timing. Validated characters can later be replaced by rigged Blender GLBs without changing the story structure.

## What is real

- The animatic uses the committed `e2e-full-1793630744537.zip` normalization in `src/e2e-replay.js`.
- The recording includes the failed first container attempt, fresh repair builder, independent 9/11 verdict, runtime signals and final recorded result.
- Löyly's Booking API, Booking Core, Event Store, Weekly Schedule, Heater Schedule and Billing Signals use distinct product forms.
- The four-language montage preserves the TypeScript, Rust, Java, and Rails baseline/final audit.

## Experience stack

- React Three Fiber renders the Three.js world.
- Three `useFrame` animation supplies the throwaway procedural performances.
- Drei supplies camera control, rounded forms, particles, and environmental lighting.
- Ordinary React DOM keeps captions, playback, inspection, and accessibility crisp.

## Run

```bash
cd spikes/3d-agentic-software-factory
npm install
npm run spike
```

Open <http://127.0.0.1:5173/?variant=A>.

Use `?chapter=0` through `?chapter=14` to open a specific beat. Add `&progress=.5` to select a deterministic point inside that performance for review or screenshots. `Alt` + left/right arrow changes variants.

Play, pause, scrub, or jump between chapters. **Inspect this moment** freezes playback and reveals the historical evidence behind the dramatic metaphor. The final scene unlocks orbit-based exploration without making navigation a prerequisite for understanding the story.

The alternative library and factory-layout research remains in `animation-library-research.md` and `automotive-assembly-layout-research.md`.
