# Browser animation stack research

Research date: 2026-11-03  
Scope: the existing React 19 + React Three Fiber prototype, delivered as a static GitHub Pages site.

## Decision

Build the experience as an **authored, deterministic 3D short first**, with an optional lightweight exploration mode after the credits.

Use this focused stack:

1. **React Three Fiber 9 + Drei** for the scene graph, asset loading and reusable helpers.
2. **Remotion 4.0.540 + `@remotion/player` + `@remotion/three`** as the single frame-based cinematic runtime for shots, camera, lights, prop motion, captions and signal-replay beats. The Player embeds the composition in the existing Vite app and supplies playback, seeking and mobile-friendly controls.
3. **Three.js `AnimationMixer`** for Blender-authored skeletal and morph-target clips. Drive mixers from Remotion's absolute frame (`frame / fps`) with `mixer.setTime()`, rather than letting each mixer advance from its own frame delta.
4. **Blender → GLB/glTF 2.0 → glTF Transform → `useGLTF`** for characters, C4-component “products”, machinery and environment assets.
5. **`@react-three/postprocessing`** for a restrained cinematic pass: tone/color treatment, bloom, vignette and selective depth of field, with a reduced mobile tier.
6. **Instanced meshes / points plus a small custom shader** for steam, sparks and signal pulses. Do not add a full particle engine until profiling shows a real need.
7. **Ordinary React DOM** above the canvas for captions, chapter title, progress, play/pause, reduced-motion mode and accessibility. Use Drei `Html` only for labels that must remain attached to objects in world space.
8. **Rapier + ecctrl, lazy-loaded only for Explore mode**, after deterministic playback has ended.
9. Continue to build the interactive Player with **Vite** and deploy the static `dist` directory to GitHub Pages. The same composition can later be registered for Remotion Studio/CLI video and still rendering.

This is deliberately one master clock: the Remotion frame. Do **not** combine Remotion, Theatre, GSAP, Motion and react-spring as peer timeline owners. That creates competing clocks, ambiguous ownership and difficult seeking.

Compatibility is part of the decision, not an afterthought. The prototype currently has React 19.3.1 and R3F 9.8.2. The published [`@remotion/three` 4.0.540](https://www.npmjs.com/package/@remotion/three/v/4.0.540) accepts React `>=16.8` and R3F `>=8`, and [`@remotion/player` 4.0.540](https://www.npmjs.com/package/@remotion/player/v/4.0.540) accepts React `>=16.8`. In contrast, [`@theatre/r3f` 0.7.2](https://www.npmjs.com/package/@theatre/r3f/v/0.7.2) declares R3F `^8.13.6`. Remotion's official installation instructions also require all Remotion packages to be pinned to exactly the same version ([installation guide](https://www.remotion.dev/docs/player/installation)).

**License gate:** Remotion is source-available under its own license, not OSI open source. Individuals, non-profits and organizations with up to three people qualify for its free license; larger for-profit organizations need a Company License. Remotion classifies embedding `<Player>` as an automation use case for Company License users ([official license](https://github.com/remotion-dev/remotion/blob/main/packages/core/LICENSE.md), [license FAQ](https://www.remotion.dev/docs/license/faq)). Confirm the project owner's eligibility before making Remotion load-bearing. If that license is unsuitable, use the fallback at the end of this document: a small integer-frame store over R3F with GSAP as the sole timeline engine. GSAP is free for commercial use under GreenSock's standard no-charge license, but it is also not OSI-licensed ([official GSAP repository](https://github.com/greensock/GSAP), [standard license](https://gsap.com/standard-license/)).

## Why this fits the story

The film needs to reveal a living “physiology” of agentic creation: the LLM brain receives senses and direction through the harness, signals travel through machinery, and Löyly emerges as one creative behavior/product expressed through its real C4 components. This calls for shot composition, exact reveals, synchronized captions, close-ups and replayable signal events — not free-roaming physics during the core explanation.

R3F remains the right base because it is a React renderer for Three.js, makes scene elements reusable React components, and exposes the full Three.js surface rather than hiding it ([R3F introduction](https://r3f.docs.pmnd.rs/getting-started/introduction)). Drei is its maintained collection of helpers and includes cameras, controls, `useGLTF`, `useAnimations`, `Html`, instancing, LOD, adaptive DPR and performance monitoring ([Drei repository](https://github.com/pmndrs/drei)). This matches the prototype already in the worktree and avoids an engine rewrite.

## Library comparison

| Candidate | Best role here | Strengths supported by its official material | Decision |
|---|---|---|---|
| **React Three Fiber + Drei** | 3D runtime and React composition | R3F maps Three.js into JSX and provides a responsive Canvas/render loop; Drei supplies reusable loaders, cameras, controls, world-space HTML, instancing and performance helpers ([R3F first scene](https://r3f.docs.pmnd.rs/getting-started/your-first-scene), [Drei repository](https://github.com/pmndrs/drei)) | **Use** |
| **Remotion + Player + `@remotion/three`** | Master frame clock, embedded playback and later MP4/still output | Remotion's core idea is to render React from an explicit frame number; Player embeds a composition in Vite/React and exposes play, pause, seek, frame events and custom controls; `ThreeCanvas` makes Remotion frame hooks available inside R3F ([core idea](https://www.remotion.dev/docs/idea), [Player](https://www.remotion.dev/docs/player/player), [Remotion Three](https://www.remotion.dev/docs/three)) | **Use as master clock**. Pin all Remotion packages to the exact same version, as its installation guide requires ([Player installation](https://www.remotion.dev/docs/player/installation)). |
| **Theatre.js** | Visual keyframe editor, if compatibility catches up | Its R3F extension can bind scene objects, cameras and lights; Studio supports keyframes and curve editing; exported JSON initializes production state; sequences expose absolute position and a custom RAF driver ([R3F guide](https://www.theatrejs.com/docs/latest/getting-started/with-react-three-fiber), [sequence manual](https://www.theatrejs.com/docs/latest/manual/sequences), [core API](https://www.theatrejs.com/docs/latest/api/core)) | **Do not use in this stack now.** Published [`@theatre/r3f` 0.7.2](https://www.npmjs.com/package/@theatre/r3f/v/0.7.2) declares R3F `^8.13.6`, while this prototype uses React 19 / R3F 9.8.2. Re-evaluate after an officially compatible adapter release. |
| **Three.js `AnimationMixer`** | Skeletal, morph and clip playback from GLB | A mixer plays clips on an object; actions can blend and cross-fade; `setTime()` updates to an exact absolute time, which is essential for seeking and repeatable frames ([AnimationMixer](https://threejs.org/docs/pages/AnimationMixer.html), [AnimationAction](https://threejs.org/docs/pages/AnimationAction.html)) | **Use under Remotion's frame clock** |
| **GSAP Timeline** | Code-authored alternative master timeline | Timelines sequence and nest tweens and expose labels, callbacks, pause, seek, reverse, repeat and progress ([GSAP Timeline](https://gsap.com/docs/v3/GSAP/Timeline/)) | **Secondary/alternative, not additive**. It is strong for imperative transitions, but Remotion better unifies playback and offline rendering for this animation-first brief. |
| **Motion** | DOM UI motion or a code-only 3D alternative | Current `motion/three` can animate Three objects, materials, shader uniforms and TSL nodes, and can place Three and DOM targets in one sequence ([Motion Three.js](https://motion.dev/docs/three)) | **Not the master timeline**. Reasonable for small DOM transitions, but unnecessary if CSS/Remotion interpolation suffices. Avoid the legacy `framer-motion-3d` path, whose own page limits it to React 18 ([legacy R3F integration](https://motion.dev/motion/three-introduction/)). |
| **react-spring** | Interruptible interaction feedback | The official R3F guide emphasizes physically based, interruptible springs and direct Three updates ([react-spring R3F guide](https://www.react-spring.dev/docs/guides/react-three-fiber)) | **Not for authored shots**. Optional later for hover, focus and Explore-mode micro-motion. |
| **Rapier / `@react-three/rapier`** | Collision and sensors in Explore mode | It is the R3F wrapper around the WASM Rapier engine, supports fixed timesteps, sensors, manual stepping and snapshots; its docs warn that variable timesteps prevent full determinism ([react-three-rapier](https://pmndrs.github.io/react-three-rapier/)) | **Lazy-load after film**; no dynamic bodies in cinematic mode. |
| **ecctrl** | Walkable post-film exploration | It provides R3F/Rapier character and vehicle controllers, touch controls and runtime animation states ([ecctrl repository](https://github.com/pmndrs/ecctrl)) | **Explore mode only**. It solves navigation, not storytelling. |
| **postprocessing / react-postprocessing** | Cinematic screen-space treatment | The base library provides bloom, color grading, depth of field, vignette, outline and related effects; the React package is the R3F wrapper ([postprocessing repository](https://github.com/pmndrs/postprocessing), [react-postprocessing package](https://github.com/pmndrs/react-postprocessing)) | **Use sparingly with quality tiers** |
| **Lottie** | 2D fallback, loader or small overlay | The official web runtime plays Bodymovin/After Effects JSON through SVG, Canvas or HTML renderers ([Lottie web guide](https://github.com/airbnb/lottie/blob/master/web.md), [renderer options](https://github.com/airbnb/lottie-web/wiki/loadAnimation-options)) | **Optional 2D asset only**, not the 3D film runtime |
| **Rive** | Interactive vector fallback or UI character | Rive provides timeline animation, bones/meshes, state machines, data binding and responsive layouts in its web runtime ([Rive](https://rive.app/), [Rive Editor](https://rive.app/editor)) | **Optional alternative treatment**, not a dependency for this GLB/C4 world |
| **Three WebGPU renderer** | Future rendering path | Three's renderer selects WebGPU when available and falls back to WebGL 2; R3F v10's WebGPU-first path is explicitly still alpha in the current docs ([WebGPURenderer](https://threejs.org/docs/pages/WebGPURenderer.html), [R3F v10 introduction](https://r3f.docs.pmnd.rs/next/getting-started/introduction)) | **Not now**. Keep the stable R3F 9/WebGL path for this release. |

## Deterministic playback design

Treat `frame` as the only source of truth. At 30 fps, derive `filmTimeSeconds = frame / 30`; do not integrate elapsed wall-clock deltas into story state.

```text
Remotion Player play / pause / seek
                 │
                 ▼
       useCurrentFrame() integer
          ├─ camera + shot framing
          ├─ lights + machinery transforms
          ├─ effect intensities
          ├─ caption/chapter state
          ├─ signal-event visibility
          └─ characterMixer.setTime(frame / fps - clipStart)
```

Remotion is explicitly based on receiving a frame number and rendering React from it ([Remotion core idea](https://www.remotion.dev/docs/idea)). Its Player can seek to a specific frame and emits frame updates during playback and seeking ([Player API](https://www.remotion.dev/docs/player/player)). `@remotion/three` makes `useCurrentFrame()` available within its R3F canvas ([Remotion Three](https://www.remotion.dev/docs/three)). Three's mixer can jump to an exact time with `setTime()` ([AnimationMixer](https://threejs.org/docs/pages/AnimationMixer.html)). Together, those APIs give repeatable seeking, deterministic screenshots and stable chapter jumps.

Practical rules:

- Keep a declarative shot manifest containing shot ID, start/end frame, caption key, camera target and signal-event range. Remotion `Sequence`, `Series`, `interpolate()` and ordinary frame-derived functions supply timing; the manifest supplies semantic story structure.
- Map each Blender clip into a known interval. For example, the character's `observe` clip can occupy film seconds 14–18 while its mixer is set to `filmTime - 14`.
- Seed every procedural particle emitter and derive particle position from absolute film time. Never use unseeded `Math.random()` during playback.
- During signal replay, map e2e result timestamps to normalized story time once. Do not schedule a forest of `setTimeout` calls.
- Pause all Orbit/character controls during the film. At the final beat, transition camera ownership once and mount Explore controls.
- Use Remotion's synchronized audio component when audio is added, and start Player playback from a user gesture so browser autoplay rules are respected ([Remotion API](https://www.remotion.dev/docs/api), [Player API](https://www.remotion.dev/docs/player/player)).

Because the web Player and offline renderer share the same frame-derived composition, the project also gets a direct route to MP4, image-sequence and still output ([Remotion rendering](https://www.remotion.dev/docs/render)).

## Character and asset pipeline

Use Blender for the performance, not the browser timeline editor:

- Rig the guide character with a small skeleton and author named actions such as `idle`, `receive-signal`, `inspect`, `decide`, `craft` and `release`.
- Use shape keys for brows, eyes, mouth silhouettes, breathing/soft-tissue exaggeration and machine/product “expression”.
- Stash all exported actions on NLA tracks and name them intentionally.
- Export a `.glb` with skinning, actions and shape keys. Blender's glTF exporter supports object transforms, pose bones and shape-key values; it also explains how actions/NLA tracks become named glTF animations ([Blender glTF manual](https://docs.blender.org/manual/en/latest/addons/import_export/scene_gltf2.html)).
- Load clips through `GLTFLoader`/Drei `useGLTF`; the loader returns an `animations` array and supports Draco, Meshopt and KTX2/Basis extensions ([GLTFLoader](https://threejs.org/docs/pages/GLTFLoader.html)).

glTF is designed for efficient runtime transmission and includes scenes, nodes, cameras, meshes, materials, textures, skins and animations ([Khronos glTF overview](https://www.khronos.org/gltf/)). Pass production GLBs through glTF Transform. Its CLI can inspect a model and apply deduplication, pruning, instancing, geometry compression and texture compression ([glTF Transform CLI](https://gltf-transform.dev/cli.html)). Do not apply a blind aggressive optimization to animated assets: preserve node hierarchy and animation targets, then verify every named clip and morph target after transformation.

Recommended asset budgets should be established through device testing rather than a single global polygon number. The measurable constraints are compressed download size, decoded texture memory, draw calls, skinned vertices, shader complexity and postprocessing cost.

## Expressive style without a feature-film renderer

The stylized result should come from art direction and staging rather than expensive realism:

- Strong silhouettes, oversized readable gestures and simple facial morphs.
- Warm “living system” colors for intent/creative flow; cooler mechanical tones for harness infrastructure.
- C4 components keep their real names and relationships but become distinctive product forms, not generic labeled cubes.
- Use emissive signal paths and synchronized pulses to show senses entering the LLM brain and decisions propagating through the harness.
- Use steam as soft instanced billboards/points with shader-driven age, scale, drift and opacity. Use a small deterministic spark pool for rare accents.
- Prefer baked or simple shadows, a restrained bloom, mild vignette/color grading, and depth of field only for authored close-ups. Heavy ambient occlusion, multiple full-resolution blur passes and permanent DOF are poor mobile defaults.

The postprocessing library exposes the required effect classes, while R3F's own performance guidance recommends reducing resolution/effects dynamically when a device cannot sustain the target frame rate ([postprocessing effects](https://github.com/pmndrs/postprocessing), [R3F scaling performance](https://r3f.docs.pmnd.rs/advanced/scaling-performance)).

## Captions, navigation and mobile behavior

Captions should be normal DOM, outside the WebGL scene. That gives them crisp rendering at every DPR, predictable responsive layout, text selection and standard accessibility semantics. World-space station labels may use Drei `Html`, but no essential explanation should exist only inside the canvas.

The mobile version should preserve the same story, not expose a smaller free-roam game:

- Tap once to start; keep play/pause, replay, chapter picker, captions and mute in thumb reach.
- Use portrait-safe camera compositions and a responsive shot override table where necessary.
- Start mobile at DPR 1–1.25, reduce shadow resolution and disable DOF/SSAO. Let Drei `PerformanceMonitor` lower or raise quality from observed performance.
- Instance repeated pipes, signal particles and product carriers. Reuse geometry/materials. R3F documents caching, instancing, LOD, adaptive performance and on-demand rendering as its main scaling tools ([R3F scaling performance](https://r3f.docs.pmnd.rs/advanced/scaling-performance)).
- Use continuous rendering only while the film or exploration is active. Switch to `frameloop="demand"` on paused/static screens.
- Offer `Auto`, `Reduced` and `Still story` modes. Reduced mode removes camera shake, fast parallax and large zooms; Still story presents the same chapters as illustrated panels and text.
- Lazy-load the Explore chunk, Rapier WASM and optional high-detail assets after the cinematic has become interactive.

## Static deployment

All recommended playback pieces work in a client-only static build. The Remotion Player renders the composition in the browser; GLBs, textures, captions and replay data are static assets. No server is required for playback. Server/CLI infrastructure is needed only if the project later renders downloadable media rather than merely playing the composition.

Keep every asset URL base-aware rather than beginning with `/`, because a project Pages site is hosted under `/<repo>/`. Vite's official deployment guide requires the corresponding `base: '/<REPO>/'` and a GitHub Actions build/deploy workflow for Pages ([Vite static deployment](https://vite.dev/guide/static-deploy)). The build should fail on missing assets and include a smoke test against the production base path.

## Explicit avoid / not-now list

- **No engine rewrite to Babylon.js, PlayCanvas or Unity WebGL.** The prototype already has the necessary Three/R3F foundation, and the unresolved problem is authored communication, not missing engine capability.
- **No free-roam controller during the explanation.** Mount Rapier/ecctrl only after the film. Optional exploration must not compete with camera direction.
- **No multiple master animation libraries.** Use the Remotion frame as master. Do not layer a second GSAP/Theatre playhead over it.
- **No react-spring for shot timing.** Springs are valuable precisely because they respond naturally to changing goals; that is different from repeatable editorial timing.
- **No legacy `framer-motion-3d`.** Its official integration remains React-18-specific. Current `motion/three` is the viable Motion path, but still unnecessary for the first cut.
- **No Theatre R3F adapter in the current app.** Its published peer range targets R3F 8, not the installed R3F 9. Keep it on the re-evaluation list because its editor is attractive, but do not force-install an unsupported peer graph.
- **No Lottie or Rive replacement of the core 3D story.** They are credible 2D/vector fallback and overlay options, but replacing the GLB world would discard the spatial C4/harness metaphor.
- **No WebGPU dependency for launch.** Three has a WebGL 2 fallback, but R3F's WebGPU-first v10 remains alpha; the existing stable WebGL stack has the lowest compatibility risk.
- **No unbounded postprocessing or particle package.** Start with a few effects and an instanced deterministic emitter; profile before adding complexity.
- **No critical text rendered as textures or tiny labels on boxes.** Keep narrative text in responsive DOM captions and use iconography/silhouette for world readability.

## First implementation spike

Time-box the first integration around one 12–20 second sequence:

1. Import one rigged guide character with an idle and inspect clip plus two facial morphs.
2. Author three Remotion frame ranges: wide anatomy/factory reveal, signal travelling to the LLM brain, close-up of one harness station producing a named C4 component.
3. Drive both the character mixer and e2e signal pulse from `useCurrentFrame()` and `fps`.
4. Add DOM captions and a scrubber; verify frame stability at five fixed timestamps.
5. Test high and reduced quality on desktop, iOS Safari and mid-range Android Chrome.
6. Embed the composition in `@remotion/player`, build at the repository base path and deploy to Pages. Separately register the same component for Remotion Studio/CLI only if offline output is required.

Pass criteria: seeking to the same timestamp reproduces the same camera, character pose, signal state and caption; the three shots remain intelligible without free navigation; reduced/mobile mode preserves the complete explanation; Explore mode loads only after playback.

If `@remotion/three` cannot sustain the target mobile performance, the fallback is a lean R3F Player driven by an integer frame store, with **GSAP as the sole imperative sequencing option**. Retain AnimationMixer, the GLB pipeline, DOM captions and the same absolute-frame architecture.
