// THROWAWAY PROTOTYPE
// Three treatments of the complete "The Next Body" animated short, switchable via ?variant=.
import React, { Suspense, useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { Canvas, useFrame, useThree } from "@react-three/fiber";
import { Environment, Float, Line, OrbitControls, RoundedBox, Sparkles } from "@react-three/drei";
import * as THREE from "three";
import { E2E_RECORDING } from "./e2e-replay.js";
import "@fontsource/barlow-condensed/500.css";
import "@fontsource/barlow-condensed/600.css";
import "@fontsource/ibm-plex-sans/400.css";
import "@fontsource/ibm-plex-sans/600.css";
import "./story-prototype.css";

const FPS = 30;
const CHAPTERS = [
  { id: "cold-sauna", title: "Cold sauna", duration: 8, caption: "The booking appeared. Billing noticed. The heater stayed cold.", evidence: "Product behavior must agree across every projection.", source: "items 01-007 and 01-008" },
  { id: "brain", title: "A brain in darkness", duration: 8, caption: "“Build Löyly.” Lumo had ideas—but no eyes, hands, or memory.", evidence: "An LLM supplies creative inference, not embodied execution.", source: "builder adapter boundary" },
  { id: "runi-wakes", title: "Runi wakes", duration: 8, caption: "The harness gave creativity a body: senses, movement, memory, and witnesses.", evidence: "Pi/ORC coordinates tools, evidence, isolation, and independent gates.", source: ".pi/architecture.md" },
  { id: "direction", title: "The direction", duration: 9, caption: "The accepted stories, rules, architecture, scope, and stack entered as sealed intent.", evidence: "Previous answers are physically fenced from clean-room regeneration.", source: E2E_RECORDING.binding.cutDigest.slice(0, 12) },
  { id: "creates", title: "Lumo creates", duration: 11, caption: "Lumo imagined. Runi translated intention into careful construction.", evidence: "Booking API, core, event store, weekly schedule, heater schedule, billing signals.", source: "C4 container view" },
  { id: "done", title: "“Done!”", duration: 9, caption: "The Candidate opened its eyes. Runi quietly pointed toward verification.", evidence: "Attempt 1 failed before tests: the test container could not find npm.", source: "attempts/01/evidence/compose/compose-test.result.json" },
  { id: "pain", title: "Pain becomes information", duration: 10, caption: "Runi did not punish the failure. Grain made it useful.", evidence: "Attempt 1 · Verification failed · npm not found. The failed builder was not resumed.", source: "RepairAuthorized" },
  { id: "rebirth", title: "Rebirth", duration: 8, caption: "A fresh session received only permitted evidence. Thirteen lights turned green.", evidence: "Attempt 2 rebuilt the image; 13 application tests passed.", source: E2E_RECORDING.timeline.find((e) => e.label.includes("13 application"))?.digest },
  { id: "lo-performs", title: "Lo performs", duration: 12, caption: "One booking became a schedule, warm stones, and a billing signal—together.", evidence: "Confirm, refuse, race, release, and coordinated projection withdrawal.", source: "runtime-signals.jsonl" },
  { id: "graders", title: "The graders arrive", duration: 11, caption: "Nine returned green. Two held their red light. Lumo could not talk them out of it.", evidence: "01-003 lost household/duration detail; 01-011 lacked usable day-and-time information.", source: "independent 9 / 11" },
  { id: "physiology", title: "Healthy body, incomplete behavior", duration: 9, caption: "Every signal was present. Two behaviors were still wrong.", evidence: "Physiology 8/8 · Behavior 9/11. Observability and correctness are different claims.", source: "instrument-conformance.result.json" },
  { id: "memory", title: "Memory", duration: 10, caption: "The Candidate had not been wasted. Its evidence would outlive it.", evidence: "Grain records the sealed cut, lineage, digest, observations, measurement, and failures.", source: E2E_RECORDING.summary.verified ? "sealed historical cycle" : "unverified" },
  { id: "choice", title: "The choice", duration: 8, caption: "Do not secretly patch the body. Correct the intention—or begin a child generation.", evidence: "Stop · correct intent · begin a child generation.", source: "Phoenix learning loop" },
  { id: "expressions", title: "Four expressions", duration: 11, caption: "One behavior found four different bodies.", evidence: "Baseline 10/11 · 9/11 · 9/11 · 0/11. Fresh regrading: 11/11 each.", source: "four-runtime regeneration audit" },
  { id: "coherence", title: "The next body", duration: 12, caption: "Creativity begins in the brain. Dependability requires a body that can learn the truth.", evidence: "Senses · hands · boundaries · independent judgment · memory longer than one life.", source: "The Next Body" },
];

const VARIANTS = {
  A: { name: "Cinema", note: "Directed widescreen short" },
  B: { name: "Living storybook", note: "Visible chapter journey" },
  C: { name: "Inside Runi", note: "Character cutaway and evidence" },
};

const totalDuration = CHAPTERS.reduce((sum, chapter) => sum + chapter.duration, 0);
const chapterStarts = CHAPTERS.map((_, index) => CHAPTERS.slice(0, index).reduce((sum, chapter) => sum + chapter.duration, 0));
const clamp = (value, min = 0, max = 1) => Math.min(max, Math.max(min, value));
const ease = (value) => { const t = clamp(value); return t * t * (3 - 2 * t); };

function chapterAt(time) {
  const index = Math.max(0, CHAPTERS.findLastIndex((_, item) => time >= chapterStarts[item]));
  const chapter = CHAPTERS[index];
  return { index, chapter, local: clamp((time - chapterStarts[index]) / chapter.duration) };
}

function useFilmClock(initialTime = 0) {
  const [time, setTime] = useState(initialTime);
  const [playing, setPlaying] = useState(false);
  const last = useRef(performance.now());
  useEffect(() => {
    let frame;
    const tick = (now) => {
      const delta = Math.min(.1, (now - last.current) / 1000);
      last.current = now;
      if (playing) setTime((current) => current >= totalDuration ? totalDuration : current + delta);
      frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [playing]);
  useEffect(() => { if (time >= totalDuration) setPlaying(false); }, [time]);
  return { time, setTime, playing, setPlaying };
}

function Eye({ x, look = 0, worried = false }) {
  return <group position={[x, .12, .47]} rotation={[0, 0, worried ? x * .3 : 0]}>
    <mesh scale={[1, worried ? .7 : 1, .45]}><sphereGeometry args={[.13, 16, 12]} /><meshStandardMaterial color="#fdf8ed" /></mesh>
    <mesh position={[look * .04, 0, .1]}><sphereGeometry args={[.055, 12, 8]} /><meshBasicMaterial color="#111827" /></mesh>
  </group>;
}

function Lumo({ position = [0, 2.8, 0], scale = 1, mood = "bright", active = true }) {
  const ref = useRef();
  useFrame(({ clock }) => {
    if (!ref.current) return;
    ref.current.position.y = position[1] + Math.sin(clock.elapsedTime * 2.4) * .09;
    ref.current.rotation.z = Math.sin(clock.elapsedTime * 1.5) * .05;
  });
  const dim = mood === "hurt";
  return <group ref={ref} position={position} scale={scale}>
    <mesh><icosahedronGeometry args={[.76, 3]} /><meshPhysicalMaterial color={dim ? "#694d8f" : "#a98bff"} emissive="#7b52d9" emissiveIntensity={active ? 2.4 : .5} transmission={.24} roughness={.22} /></mesh>
    <Eye x={-.25} worried={dim} /><Eye x={.25} worried={dim} />
    <pointLight color="#9a74ff" intensity={active ? 18 : 3} distance={6} />
    <Sparkles count={18} scale={2.6} size={2.4} speed={.35} color="#c8b7ff" />
  </group>;
}

function Grain({ position = [0, 1.75, .5], scale = 1, awake = true }) {
  return <group position={position} scale={scale}>
    <RoundedBox args={[.9, 1.1, .52]} radius={.26} smoothness={4}><meshStandardMaterial color="#287d78" emissive="#53c8bd" emissiveIntensity={awake ? .45 : .08} /></RoundedBox>
    <Eye x={-.17} worried={false} /><Eye x={.17} worried={false} />
    <mesh position={[0, .63, 0]} rotation={[0, 0, -.18]}><cylinderGeometry args={[.38, .45, .18, 20]} /><meshStandardMaterial color="#d9c29c" /></mesh>
  </group>;
}

function Runi({ position = [0, 0, 0], scale = 1, awake = true, kneel = false, showLumo = true, showGrain = true }) {
  const ref = useRef();
  useFrame(({ clock }) => { if (ref.current) ref.current.rotation.y = Math.sin(clock.elapsedTime * .55) * .025; });
  return <group ref={ref} position={position} scale={scale} rotation={[kneel ? .08 : 0, 0, 0]}>
    <group position={[0, kneel ? -.5 : 0, 0]}>
      <RoundedBox args={[2.4, 2.8, 1.45]} radius={.38} smoothness={5} position={[0, 2.2, 0]}><meshStandardMaterial color="#294a59" roughness={.42} metalness={.38} /></RoundedBox>
      <mesh position={[0, 2.25, .76]}><boxGeometry args={[1.45, 1.65, .05]} /><meshPhysicalMaterial color="#102a36" transmission={.18} transparent opacity={.72} emissive="#53c8bd" emissiveIntensity={.12} /></mesh>
      {showGrain && <Grain />}
      <RoundedBox args={[2.05, 1.55, 1.45]} radius={.5} smoothness={5} position={[0, 4.38, 0]}><meshPhysicalMaterial color="#1c3544" transmission={.32} transparent opacity={.86} roughness={.15} /></RoundedBox>
      {showLumo && <Lumo position={[0, 4.35, .05]} scale={.72} active={awake} />}
      {[-1, 1].map((side) => <group key={side} position={[side * 1.55, 2.45, 0]} rotation={[0, 0, side * -.08]}>
        <mesh position={[0, -.65, 0]}><capsuleGeometry args={[.32, 1.35, 6, 12]} /><meshStandardMaterial color="#416979" metalness={.35} /></mesh>
        <mesh position={[0, -1.55, .12]} scale={[1.15, .7, 1.25]}><sphereGeometry args={[.42, 14, 10]} /><meshStandardMaterial color="#d9c29c" /></mesh>
      </group>)}
      {[-.72, .72].map((x) => <group key={x} position={[x, .65, 0]}>
        <mesh><capsuleGeometry args={[.38, 1.3, 6, 12]} /><meshStandardMaterial color="#294a59" metalness={.4} /></mesh>
        <mesh position={[0, -.95, .2]} scale={[1.25, .55, 1.8]}><sphereGeometry args={[.48, 14, 10]} /><meshStandardMaterial color="#172e3a" /></mesh>
      </group>)}
      <mesh position={[0, 3.32, -.78]}><boxGeometry args={[.16, 4.3, .12]} /><meshStandardMaterial color="#53c8bd" emissive="#53c8bd" emissiveIntensity={awake ? 3 : .2} /></mesh>
    </group>
  </group>;
}

function Lo({ position = [0, 1, 0], scale = 1, cold = false, split = false }) {
  const group = useRef();
  useFrame(({ clock }) => {
    if (!group.current) return;
    group.current.rotation.y = clock.elapsedTime * .35;
    group.current.children.forEach((child, index) => { child.position.y += Math.sin(clock.elapsedTime * 2 + index) * .0008; });
  });
  const wisps = split ? 15 : 9;
  return <group ref={group} position={position} scale={scale}>
    {Array.from({ length: wisps }, (_, index) => {
      const arm = split ? index % 3 - 1 : 0;
      const y = index * .17;
      return <mesh key={index} position={[arm * (.25 + y * .13) + Math.sin(index * 1.7) * .12, y, Math.cos(index * 1.3) * .12]} scale={1 - index / (wisps * 1.45)}>
        <sphereGeometry args={[.22, 12, 8]} /><meshBasicMaterial color={cold ? "#69809a" : "#f6bd47"} transparent opacity={.92 - index * .035} toneMapped={false} />
      </mesh>;
    })}
    <pointLight color={cold ? "#7fa0c6" : "#ffbd45"} intensity={cold ? 2 : 12} distance={5} />
  </group>;
}

function Candidate({ position = [0, 0, 0], scale = 1, alive = true, anxious = false, translucent = false }) {
  const ref = useRef();
  useFrame(({ clock }) => { if (ref.current && alive) ref.current.position.y = position[1] + Math.abs(Math.sin(clock.elapsedTime * 1.7)) * .035; });
  return <group ref={ref} position={position} scale={scale}>
    <RoundedBox args={[2.25, 2.4, 1.4]} radius={.55} smoothness={6} position={[0, 1.7, 0]}><meshStandardMaterial color="#c67d4e" transparent opacity={translucent ? .22 : 1} roughness={.7} /></RoundedBox>
    <group position={[0, 1.75, .3]}><Eye x={-.38} worried={anxious} /><Eye x={.38} worried={anxious} /></group>
    <mesh position={[0, .02, .2]} scale={[1.4, .5, 1.7]}><sphereGeometry args={[.65, 16, 10]} /><meshStandardMaterial color="#6c4637" transparent opacity={translucent ? .18 : 1} /></mesh>
    {translucent && <Sparkles count={32} scale={[2.2, 3.2, 1.4]} size={2.2} color="#53c8bd" />}
  </group>;
}

function Drone({ position, verdict = "idle", index = 0, verifier = false }) {
  const ref = useRef();
  useFrame(({ clock }) => { if (ref.current) ref.current.position.y = position[1] + Math.sin(clock.elapsedTime * 2.2 + index) * .09; });
  const color = verdict === "pass" ? "#53c8bd" : verdict === "fail" ? "#f05b59" : "#d7e2e4";
  return <group ref={ref} position={position}>
    <mesh><octahedronGeometry args={[verifier ? .52 : .38, 1]} /><meshStandardMaterial color="#e7ece9" metalness={.55} roughness={.25} /></mesh>
    <mesh position={[0, 0, .33]}><sphereGeometry args={[verifier ? .18 : .12, 12, 8]} /><meshBasicMaterial color={color} toneMapped={false} /></mesh>
    <mesh position={[-.55, 0, 0]} rotation={[0, 0, .22]}><boxGeometry args={[.7, .05, .22]} /><meshStandardMaterial color="#718897" /></mesh>
    <mesh position={[.55, 0, 0]} rotation={[0, 0, -.22]}><boxGeometry args={[.7, .05, .22]} /><meshStandardMaterial color="#718897" /></mesh>
  </group>;
}

function Sauna({ working = false, cold = false }) {
  return <group>
    <mesh position={[0, -.15, 0]}><boxGeometry args={[14, .3, 10]} /><meshStandardMaterial color="#9f704c" /></mesh>
    {[-5.2, -3.8, 3.8, 5.2].map((x) => <mesh key={x} position={[x, .7, 2.6]}><boxGeometry args={[1.05, .22, 7]} /><meshStandardMaterial color="#d9c29c" roughness={.9} /></mesh>)}
    <group position={[-3.8, .3, -1.8]}><ProductPart kind="calendar" color="#6ea7bf" /><Lo position={[0, 1.4, 0]} scale={.55} cold={cold} /></group>
    <group position={[0, .3, -1.8]}><ProductPart kind="heat" color={working ? "#f18a47" : "#61768a"} /><pointLight color="#ef7c39" intensity={working ? 28 : 0} distance={5} /></group>
    <group position={[3.8, .3, -1.8]}><ProductPart kind="billing" color="#53c8bd" /></group>
    {working && <Sparkles count={90} scale={[12, 7, 8]} size={5} speed={.18} color="#f7e0b3" opacity={.32} />}
  </group>;
}

function ProductPart({ kind, color = "#d9c29c", position = [0, 0, 0], assembled = true }) {
  const material = <meshStandardMaterial color={color} emissive={color} emissiveIntensity={assembled ? .2 : 0} transparent opacity={assembled ? 1 : .2} />;
  if (kind === "api") return <group position={position}><mesh>{material}<torusGeometry args={[.55, .13, 10, 24]} /></mesh><mesh position={[0, 0, .25]}>{material}<boxGeometry args={[.8, .12, .12]} /></mesh></group>;
  if (kind === "core") return <group position={position}><mesh>{material}<dodecahedronGeometry args={[.62, 0]} /></mesh><mesh>{material}<torusGeometry args={[.78, .08, 8, 12]} /></mesh></group>;
  if (kind === "store") return <group position={position}>{[0, .22, .44].map((y) => <mesh key={y} position={[0, y, 0]}>{material}<cylinderGeometry args={[.58, .58, .16, 20]} /></mesh>)}</group>;
  if (kind === "calendar") return <group position={position}><RoundedBox args={[1.25, 1.05, .28]} radius={.12} smoothness={3}>{material}</RoundedBox>{[-.3, 0, .3].map((x) => <mesh key={x} position={[x, .12, .18]}><boxGeometry args={[.12, .12, .04]} /><meshBasicMaterial color="#f7f1df" /></mesh>)}</group>;
  if (kind === "heat") return <group position={position}><mesh>{material}<cylinderGeometry args={[.58, .72, 1.1, 16]} /></mesh>{[-.28, 0, .28].map((x) => <mesh key={x} position={[x, .78, 0]} rotation={[0, 0, x]}><coneGeometry args={[.18, .58, 12]} /><meshBasicMaterial color={color} /></mesh>)}</group>;
  return <group position={position}><mesh>{material}<cylinderGeometry args={[.55, .55, .18, 24]} /></mesh><mesh position={[0, .24, 0]}>{material}<torusGeometry args={[.35, .1, 8, 18]} /></mesh></group>;
}

const PARTS = [
  ["api", "#6ea7bf"], ["core", "#a98bff"], ["store", "#e4a454"],
  ["calendar", "#53c8bd"], ["heat", "#f18a47"], ["billing", "#8bc56f"],
];

function Workshop({ built = 0, failure = false, reborn = false }) {
  return <group>
    <mesh position={[0, -.2, 0]}><boxGeometry args={[18, .4, 13]} /><meshStandardMaterial color="#132b37" /></mesh>
    <Runi position={[-3.4, 0, 0]} scale={.8} />
    <Candidate position={[3, .15, 0]} scale={.82} anxious={failure} alive={!failure} />
    {PARTS.map(([kind, color], index) => {
      const angle = index / PARTS.length * Math.PI * 2;
      const complete = index < built;
      return <ProductPart key={kind} kind={kind} color={color} assembled={complete} position={[3 + Math.cos(angle) * 2.2, complete ? 1.4 : 4.6, Math.sin(angle) * 1.55]} />;
    })}
    {failure && <group position={[5.9, 1, 0]}><RoundedBox args={[1.6, 1.1, 1]} radius={.2} smoothness={3}><meshStandardMaterial color="#6f4b3d" /></RoundedBox><mesh position={[0, .62, 0]} rotation={[0, 0, -.4]}><boxGeometry args={[1.7, .12, .8]} /><meshStandardMaterial color="#d9c29c" /></mesh></group>}
    {reborn && Array.from({ length: 13 }, (_, index) => <mesh key={index} position={[-.8 + (index % 7) * .48, 5 + Math.floor(index / 7) * .45, -1.4]}><sphereGeometry args={[.11, 10, 7]} /><meshBasicMaterial color="#63df9d" toneMapped={false} /></mesh>)}
  </group>;
}

function Archive({ dissolve = false }) {
  return <group>
    <mesh position={[0, -.2, 0]}><cylinderGeometry args={[8, 8, .4, 48]} /><meshStandardMaterial color="#0b3137" /></mesh>
    <Runi position={[-3.8, 0, 0]} scale={.78} kneel />
    <Candidate position={[2.7, .2, 0]} scale={.85} anxious={!dissolve} translucent={dissolve} />
    {Array.from({ length: 5 }, (_, index) => <group key={index} position={[-6 + index * 3, .4, -4.4]}><Candidate scale={.28} translucent /><Sparkles count={10} scale={1.8} color="#53c8bd" /></group>)}
    <Sparkles count={80} scale={[15, 8, 10]} size={2.8} speed={.12} color="#53c8bd" opacity={.45} />
  </group>;
}

function GraderFormation({ physiology = false }) {
  return <group>
    <mesh position={[0, -.2, 0]}><cylinderGeometry args={[9, 9, .35, 48]} /><meshStandardMaterial color="#dce3df" /></mesh>
    <Candidate position={[0, .1, 0]} scale={.75} anxious />
    <Lumo position={[-4.8, 3, 0]} scale={.8} mood="hurt" />
    {Array.from({ length: 11 }, (_, index) => {
      const angle = Math.PI * .12 + index / 10 * Math.PI * 1.76;
      const fail = index === 2 || index === 10;
      return <Drone key={index} index={index} verdict={fail ? "fail" : "pass"} position={[Math.cos(angle) * 5.3, 2.2 + (index % 2) * .3, Math.sin(angle) * 4.2]} />;
    })}
    {physiology && <group position={[4.8, 1.2, 0]}>{Array.from({ length: 8 }, (_, index) => <mesh key={index} position={[0, index * .42, 0]}><sphereGeometry args={[.13, 10, 7]} /><meshBasicMaterial color="#53c8bd" /></mesh>)}</group>}
  </group>;
}

function LanguageBodies() {
  const bodies = [
    ["#6ea7bf", -6], ["#d57545", -2], ["#e8b84d", 2], ["#b45b63", 6],
  ];
  return <group>
    <mesh position={[0, -.2, 0]}><boxGeometry args={[19, .4, 12]} /><meshStandardMaterial color="#172a34" /></mesh>
    {bodies.map(([color, x], index) => <group key={color} position={[x, 0, 0]} scale={[.72 + index * .05, .72, .72]}>
      <Runi showLumo={false} showGrain={false} />
      <mesh position={[0, 4.35, .5]}><sphereGeometry args={[.55, 16, 12]} /><meshStandardMaterial color={color} emissive={color} emissiveIntensity={.8} /></mesh>
      <Lo position={[0, 5.5, 0]} scale={.5} />
    </group>)}
  </group>;
}

function StoryStage({ chapterIndex, local, variant, explore }) {
  const reveal = ease(local);
  if (chapterIndex === 0) return <Sauna cold />;
  if (chapterIndex === 1) return <group><Lumo position={[0, 2.7, 0]} scale={1.25} /><Float speed={1.4} floatIntensity={1.2}>{Array.from({ length: 9 }, (_, i) => <mesh key={i} position={[Math.sin(i * 2) * 3.5, 1 + (i % 4), Math.cos(i) * 2]} rotation={[i, i * .4, 0]}><tetrahedronGeometry args={[.35 + (i % 3) * .15]} /><meshBasicMaterial color="#a98bff" wireframe /></mesh>)}</Float></group>;
  if (chapterIndex === 2) return <Runi position={[0, 0, 0]} scale={1.05} awake={local > .18} />;
  if (chapterIndex === 3) return <group><Runi position={[-2.2, 0, 0]} scale={.9} /><mesh position={[2.7, 3.1, 0]} rotation={[0, 0, -.1]}><boxGeometry args={[1.4, 2.2, .28]} /><meshStandardMaterial color="#f2a93b" emissive="#f2a93b" emissiveIntensity={1.2} /></mesh><Candidate position={[5.5, .2, -2]} scale={.45} translucent /></group>;
  if (chapterIndex === 4) return <Workshop built={Math.ceil(reveal * 6)} />;
  if (chapterIndex === 5) return <group><Workshop built={6} failure={local > .48} /><Drone verifier position={[6.1, 4.2, 0]} verdict={local > .48 ? "fail" : "idle"} /></group>;
  if (chapterIndex === 6) return <Archive dissolve={local > .56} />;
  if (chapterIndex === 7) return <Workshop built={6} reborn />;
  if (chapterIndex === 8) return <group><Sauna working /><Lo position={[-4 + reveal * 8, 1.9, 0]} scale={.9} split={local > .45} /></group>;
  if (chapterIndex === 9) return <GraderFormation />;
  if (chapterIndex === 10) return <GraderFormation physiology />;
  if (chapterIndex === 11) return <Archive dissolve />;
  if (chapterIndex === 12) return <group><Runi position={[-2.8, 0, 0]} scale={.9} kneel /><Lumo position={[2.6, 2.8, 0]} scale={1.05} mood="hurt" />{[-2.2, 0, 2.2].map((x, index) => <mesh key={x} position={[x + 2.4, .35, -3]}><cylinderGeometry args={[.7, .7, .22, 24]} /><meshStandardMaterial color={index === 1 ? "#f2a93b" : "#536b77"} emissive={index === 1 ? "#f2a93b" : "#000"} emissiveIntensity={.8} /></mesh>)}</group>;
  if (chapterIndex === 13) return <LanguageBodies />;
  return <group><Sauna working /><group position={[0, .4, -4]} scale={.42}><Runi /></group><Lo position={[0, 2, 0]} scale={1.1} /></group>;
}

const cameraShots = [
  [[9, 6, 11], [0, 1, 0]], [[0, 3, 10], [0, 2.6, 0]], [[8, 5.5, 10], [0, 2.5, 0]],
  [[9, 5, 11], [0, 2.4, 0]], [[11, 7, 13], [0, 2.2, 0]], [[10, 4.5, 10], [2.5, 1.9, 0]],
  [[10, 6, 12], [0, 2, 0]], [[10, 5, 11], [1, 2, 0]], [[9, 5, 11], [0, 1.5, 0]],
  [[0, 9, 13], [0, 1.5, 0]], [[9, 6, 11], [1.5, 2.2, 0]], [[10, 7, 12], [0, 2, 0]],
  [[8, 5, 10], [0, 2, 0]], [[0, 7, 17], [0, 2.2, 0]], [[10, 7, 13], [0, 1.8, 0]],
];

function CameraDirector({ chapterIndex, variant, explore }) {
  const { camera } = useThree();
  const controls = useRef();
  useFrame((_, delta) => {
    if (explore) return;
    const shot = cameraShots[chapterIndex];
    const position = new THREE.Vector3(...shot[0]);
    if (variant === "B") position.add(new THREE.Vector3(0, 3.5, 2));
    if (variant === "C") position.multiplyScalar(.78).add(new THREE.Vector3(1.5, .2, 0));
    camera.position.lerp(position, Math.min(1, delta * 2.3));
    camera.lookAt(...shot[1]);
  });
  return <OrbitControls ref={controls} enabled={explore} makeDefault enablePan={false} minDistance={5} maxDistance={22} maxPolarAngle={Math.PI * .48} />;
}

function World({ chapterIndex, local, variant, explore }) {
  const dark = chapterIndex === 1;
  const warm = chapterIndex === 0 || chapterIndex === 8 || chapterIndex === 14;
  return <>
    <color attach="background" args={[dark ? "#020307" : warm ? "#4b281d" : "#07111f"]} />
    <fog attach="fog" args={[dark ? "#020307" : warm ? "#4b281d" : "#07111f", 15, 36]} />
    <ambientLight intensity={dark ? .12 : .65} color={warm ? "#ffd6a0" : "#c9e2e8"} />
    <directionalLight position={[7, 12, 9]} intensity={warm ? 3.2 : 2.1} color={warm ? "#ffc47c" : "#d7ecff"} castShadow shadow-mapSize={[1024, 1024]} />
    <pointLight position={[-8, 5, -5]} intensity={18} distance={18} color={dark ? "#8f6cff" : "#53c8bd"} />
    <StoryStage chapterIndex={chapterIndex} local={local} variant={variant} explore={explore} />
    <CameraDirector chapterIndex={chapterIndex} variant={variant} explore={explore} />
    {!dark && <Environment preset="warehouse" environmentIntensity={.22} />}
  </>;
}

function Inspection({ chapter, index, onClose }) {
  const related = E2E_RECORDING.timeline.filter((event) => {
    if (index === 5 || index === 6) return event.type === "VerificationFailed" || event.type === "RepairAuthorized";
    if (index === 9) return event.type === "IndependentVerdict" && event.verdict === "fail";
    if (index === 10) return event.type === "RuntimeSignalObserved" || event.label?.includes("8/8");
    return event.stageIndex === Math.min(9, Math.floor(index * 10 / CHAPTERS.length));
  }).slice(0, 4);
  return <aside className="inspection" aria-label={`Evidence for ${chapter.title}`}>
    <button className="inspection-close" onClick={onClose} aria-label="Close inspection">×</button>
    <div className="inspection-character"><span>{index + 1}</span><i /></div>
    <p className="inspection-kicker">What this moment means</p>
    <h2>{chapter.title}</h2>
    <p className="inspection-meaning">{chapter.evidence}</p>
    <dl><div><dt>Evidence</dt><dd>{chapter.source}</dd></div><div><dt>Cycle</dt><dd>{E2E_RECORDING.cycleId}</dd></div></dl>
    {related.length > 0 && <section><h3>What actually happened</h3>{related.map((event) => <article key={`${event.offset}-${event.itemId ?? event.type}`}><span className={event.verdict ?? event.type.includes("Failed") ? "fail" : "pass"}>{event.verdict ?? "observed"}</span><p>{event.label}</p></article>)}</section>}
    <footer>Inspection freezes the story. Nothing here changes the recorded cycle.</footer>
  </aside>;
}

function PrototypeSwitcher({ variant }) {
  const keys = Object.keys(VARIANTS);
  const cycle = (direction) => {
    const next = keys[(keys.indexOf(variant) + direction + keys.length) % keys.length];
    const url = new URL(window.location.href); url.searchParams.set("variant", next); window.history.replaceState({}, "", url); window.dispatchEvent(new PopStateEvent("popstate"));
  };
  useEffect(() => {
    const listener = (event) => {
      if (event.target.matches("input, textarea, [contenteditable]")) return;
      if (event.key === "ArrowLeft" && event.altKey) cycle(-1);
      if (event.key === "ArrowRight" && event.altKey) cycle(1);
    };
    window.addEventListener("keydown", listener); return () => window.removeEventListener("keydown", listener);
  });
  return <nav className="prototype-switcher" aria-label="Prototype variants"><button onClick={() => cycle(-1)}>←</button><span><small>Prototype {variant}</small><strong>{VARIANTS[variant].name}</strong></span><button onClick={() => cycle(1)}>→</button></nav>;
}

function ChapterRail({ active, setTime }) {
  return <ol className="chapter-rail">{CHAPTERS.map((chapter, index) => <li key={chapter.id} className={index === active ? "active" : index < active ? "past" : ""}><button onClick={() => setTime(chapterStarts[index] + .01)}><span>{index + 1}</span><em>{chapter.title}</em></button></li>)}</ol>;
}

function App() {
  const initialParams = useMemo(() => new URLSearchParams(location.search), []);
  const initialChapter = clamp(Number(initialParams.get("chapter") ?? 0), 0, CHAPTERS.length - 1);
  const [variant, setVariant] = useState(() => initialParams.get("variant")?.toUpperCase() in VARIANTS ? initialParams.get("variant").toUpperCase() : "A");
  const [inspect, setInspect] = useState(false);
  const [explore, setExplore] = useState(false);
  const [started, setStarted] = useState(initialParams.has("chapter"));
  const film = useFilmClock(chapterStarts[initialChapter] + (initialParams.has("chapter") ? .01 : 0));
  const { index, chapter, local } = chapterAt(film.time);
  useEffect(() => { const listener = () => setVariant(new URLSearchParams(location.search).get("variant")?.toUpperCase() in VARIANTS ? new URLSearchParams(location.search).get("variant").toUpperCase() : "A"); addEventListener("popstate", listener); return () => removeEventListener("popstate", listener); }, []);
  useEffect(() => { if (inspect) film.setPlaying(false); }, [inspect]);
  const seek = (value) => { film.setTime(Number(value)); setExplore(false); };
  const jump = (direction) => film.setTime(chapterStarts[clamp(index + direction, 0, CHAPTERS.length - 1)] + .01);
  return <main className={`experience variant-${variant} ${inspect ? "is-inspecting" : ""} ${explore ? "is-exploring" : ""}`}>
    <div className="film-stage">
      <Canvas shadows dpr={[1, 1.6]} camera={{ fov: 38, position: [9, 6, 11], near: .1, far: 70 }} gl={{ antialias: true, powerPreference: "high-performance" }}>
        <Suspense fallback={null}><World chapterIndex={index} local={local} variant={variant} explore={explore} /></Suspense>
      </Canvas>
      <div className="film-grain" />
      {!started && <section className="opening-card"><div className="opening-orbit"><i /><i /><i /></div><p>An original interactive animatic</p><h1>The Next Body</h1><blockquote>Can creativity learn to become dependable?</blockquote><button onClick={() => { setStarted(true); film.setPlaying(true); }}>Begin the story</button><small>About 2½ minutes · captions included · inspect any chapter</small></section>}
      {variant === "B" && <ChapterRail active={index} setTime={(time) => { film.setTime(time); setStarted(true); }} />}
      {variant === "C" && <><div className="anatomy-legend" aria-hidden="true"><span className="brain">Lumo<i /></span><span className="spine">Direction<i /></span><span className="memory">Grain<i /></span><span className="senses">Evidence<i /></span></div><aside className="cutaway-pulse"><small>Inside this moment</small><strong>{chapter.title}</strong><p>{chapter.evidence}</p><dl><div><dt>Physiology</dt><dd>{index >= 10 ? "8 / 8" : "observing"}</dd></div><div><dt>Behavior</dt><dd>{index >= 9 ? "9 / 11" : "forming"}</dd></div></dl></aside></>}
      {started && <>
        <header className="story-header"><div><strong>The Next Body</strong><span>{String(index + 1).padStart(2, "0")} / {CHAPTERS.length}</span></div><button onClick={() => setInspect(true)}>Inspect this moment</button></header>
        <section className="caption" aria-live="polite"><p>{chapter.caption}</p><span>{chapter.title}</span></section>
        <div className="transport">
          <button onClick={() => jump(-1)} aria-label="Previous chapter">‹</button>
          <button className="play" onClick={() => { setStarted(true); setExplore(false); film.setPlaying(!film.playing); }} aria-label={film.playing ? "Pause" : "Play"}>{film.playing ? "Ⅱ" : "▶"}</button>
          <input type="range" min="0" max={totalDuration} step={1 / FPS} value={film.time} onChange={(event) => seek(event.target.value)} aria-label="Story position" style={{ "--progress": `${film.time / totalDuration * 100}%` }} />
          <time>{Math.floor(film.time / 60)}:{String(Math.floor(film.time % 60)).padStart(2, "0")}</time>
          <button onClick={() => jump(1)} aria-label="Next chapter">›</button>
        </div>
        {film.time >= totalDuration - .2 && <button className="explore-button" onClick={() => setExplore(!explore)}>{explore ? "Return to final shot" : "Explore the final world"}</button>}
        {explore && <p className="explore-help">Drag to look · pinch or scroll to move closer</p>}
      </>}
    </div>
    {inspect && <Inspection chapter={chapter} index={index} onClose={() => setInspect(false)} />}
    <PrototypeSwitcher variant={variant} />
  </main>;
}

createRoot(document.getElementById("root")).render(<App />);
