// PROTOTYPE: Three immersive ways to explore the same live software factory.
// Question: does spatial presence make Grain, Pi and Löyly easier to understand than a dashboard?
import React, { Suspense, useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { Canvas, useFrame, useThree } from "@react-three/fiber";
import { Html, KeyboardControls, OrbitControls, useKeyboardControls } from "@react-three/drei";
import { CuboidCollider, Physics, RigidBody } from "@react-three/rapier";
import { Ecctrl } from "ecctrl";
import { Joystick, useButtonStore, useJoystickStore } from "ecctrl/input";
import * as THREE from "three";
import architectureSource from "../../../.pi/architecture.md?raw";
import { E2E_RECORDING } from "./e2e-replay.js";
import "./open-world.css";

const keyboardMap = [
  { name: "forward", keys: ["ArrowUp", "KeyW"] },
  { name: "backward", keys: ["ArrowDown", "KeyS"] },
  { name: "leftward", keys: ["ArrowLeft", "KeyA"] },
  { name: "rightward", keys: ["ArrowRight", "KeyD"] },
  { name: "jump", keys: ["Space"] },
  { name: "run", keys: ["Shift"] },
];

const STAGES = [
  { id: "cut", name: "Pin product model", role: "Receiving", icon: "blueprint", node: "cutworker", hint: "The approved Löyly model enters the factory read-only." },
  { id: "calibrate", name: "Calibrate tooling", role: "Tool room", icon: "gauge", node: "registry", hint: "Pi proves its tools before it touches the product." },
  { id: "build", name: "Assemble components", role: "Body shop", icon: "robot", node: "builder", hint: "A fresh builder creates the C4 product containers." },
  { id: "instrument", name: "Wire runtime signals", role: "Sensor fit", icon: "signal", node: "outputs", hint: "Telemetry is fitted without changing the candidate." },
  { id: "oracle", name: "Bind product contract", role: "Marriage", icon: "link", node: "worker", hint: "The candidate meets the pinned product contract." },
  { id: "verify", name: "Boot system", role: "Road test", icon: "power", node: "docker", hint: "The assembled system boots inside its isolated runtime." },
  { id: "repair", name: "Repair bay", role: "Rework", icon: "wrench", node: "builder", hint: "A new builder receives failure evidence for one repair." },
  { id: "grade", name: "Independent inspection", role: "Quality gate", icon: "inspect", node: "grader", hint: "Independent graders—not the builder—author the verdict." },
  { id: "signals", name: "Check telemetry", role: "Signal lab", icon: "pulse", node: "events", hint: "Grain facts and runtime signals are checked for completeness." },
  { id: "record", name: "Register product", role: "Product yard", icon: "archive", node: "projections", hint: "Grain records the result; it does not advance the line." },
];

const ROUTE = [
  [-22, -10], [-16, -10], [-10, -10], [-4, -10], [2, -10], [8, -10],
  [14, -4], [14, 6], [7, 10], [0, 10],
];

const OPERATOR_ROUTE = [
  [-22, -14], [-16, -14], [-10, -14], [-4, -14], [2, -14], [8, -14],
  [18, -4], [18, 6], [7, 14], [0, 14],
];

const COMPONENTS = [
  { name: "Booking API", stage: 2, color: "#72a9c4", shape: "api" },
  { name: "Booking core", stage: 2, color: "#d47542", shape: "gear" },
  { name: "Event store", stage: 3, color: "#e8b84d", shape: "store" },
  { name: "Weekly schedule", stage: 3, color: "#50d0cc", shape: "calendar" },
  { name: "Heater schedule", stage: 4, color: "#9c83bd", shape: "heat" },
  { name: "Billing signals", stage: 4, color: "#77c48d", shape: "signal" },
];

const VARIANTS = {
  A: { name: "Walk the line", description: "Third-person exploration" },
  B: { name: "Factory simulation", description: "Overhead operations view" },
  C: { name: "Guided tour", description: "Station-by-station story" },
};

function parseContainerDiagram() {
  const marker = "## Container view";
  const section = architectureSource.indexOf(marker);
  const start = architectureSource.indexOf("```mermaid", section);
  const source = architectureSource.slice(architectureSource.indexOf("\n", start) + 1, architectureSource.indexOf("```", start + 10));
  const nodes = new Map();
  const edges = [];
  for (const raw of source.split("\n")) {
    const line = raw.trim();
    const node = line.match(/^(\w+)\s*(?:\[\("([\s\S]*?)"\)\]|\["([\s\S]*?)"\])/);
    if (node) {
      const parts = (node[2] ?? node[3]).replaceAll("<br/>", "\n").replaceAll("&amp;", "&").split("\n").map((part) => part.trim()).filter(Boolean);
      const type = parts.find((part) => part.startsWith("[") && part.endsWith("]"))?.slice(1, -1) ?? "C4 element";
      nodes.set(node[1], { id: node[1], name: parts[0], type, description: parts.filter((part, index) => index > 0 && !part.startsWith("[")).join(" ") });
    }
    const edge = line.match(/^(\w+)\s*-->\s*(?:\|"([^"]*)"\|\s*)?(\w+)/);
    if (edge) edges.push({ from: edge[1], to: edge[3], label: edge[2] ?? "connects to" });
  }
  return { nodes, edges };
}

const C4 = parseContainerDiagram();
const stationData = STAGES.map((stage, index) => ({
  ...stage,
  index,
  position: ROUTE[index],
  c4: C4.nodes.get(stage.node) ?? { id: stage.node, name: stage.name, type: "C4 container", description: stage.hint },
}));

function stationConnections(station) {
  return C4.edges.filter((edge) => edge.from === station.node || edge.to === station.node).slice(0, 4).map((edge) => {
    const other = edge.from === station.node ? edge.to : edge.from;
    return `${edge.from === station.node ? "To" : "From"} ${C4.nodes.get(other)?.name ?? other}: ${edge.label}`;
  });
}

function useReplay() {
  const [playing, setPlaying] = useState(true);
  const [elapsed, setElapsed] = useState(0);
  useEffect(() => {
    const timer = window.setInterval(() => {
      if (!playing) return;
      setElapsed((current) => current >= 18400 ? 0 : current + 25);
    }, 100);
    return () => window.clearInterval(timer);
  }, [playing]);
  const eventIndex = E2E_RECORDING.timeline.findLastIndex((event) => event.offset <= elapsed);
  const event = E2E_RECORDING.timeline[Math.max(0, eventIndex)];
  return {
    playing,
    setPlaying,
    elapsed,
    setElapsed,
    event,
    eventIndex,
    stage: event?.stageIndex ?? 0,
    progress: elapsed / 18400,
  };
}

function MachineIcon({ kind, active }) {
  const accent = active ? "#f2b84b" : "#6e8497";
  if (kind === "gauge" || kind === "power") return <>
    <mesh position={[0, .7, 0]} rotation={[Math.PI / 2, 0, 0]}><torusGeometry args={[.55, .12, 8, 24]} /><meshStandardMaterial color={accent} emissive={accent} emissiveIntensity={active ? 2 : .25} /></mesh>
    <mesh position={[0, .7, .03]} rotation={[0, 0, -.55]}><boxGeometry args={[.08, .55, .08]} /><meshStandardMaterial color="#e9ece5" /></mesh>
  </>;
  if (kind === "robot" || kind === "wrench") return <>
    <mesh position={[0, .65, 0]}><cylinderGeometry args={[.18, .24, 1.2, 8]} /><meshStandardMaterial color={accent} metalness={.7} /></mesh>
    <mesh position={[.38, 1.05, 0]} rotation={[0, 0, -.8]}><boxGeometry args={[.7, .18, .18]} /><meshStandardMaterial color="#d5ddd8" metalness={.8} /></mesh>
    <mesh position={[.68, 1.32, 0]}><sphereGeometry args={[.2, 12, 8]} /><meshStandardMaterial color={accent} emissive={accent} emissiveIntensity={active ? 2 : 0} /></mesh>
  </>;
  if (kind === "archive") return <>
    {[0, .28, .56].map((y) => <mesh key={y} position={[0, .35 + y, 0]}><cylinderGeometry args={[.52, .52, .22, 20]} /><meshStandardMaterial color={y === .56 ? accent : "#41596b"} metalness={.55} /></mesh>)}
  </>;
  return <>
    <mesh position={[0, .65, 0]}><boxGeometry args={[1.15, 1.15, .35]} /><meshStandardMaterial color="#31485b" metalness={.55} /></mesh>
    {[-.3, 0, .3].map((y) => <mesh key={y} position={[0, .65 + y, .2]}><boxGeometry args={[.8, .07, .05]} /><meshStandardMaterial color={accent} emissive={accent} emissiveIntensity={active ? 2 : .3} /></mesh>)}
  </>;
}

function Station({ station, active, complete, onSelect }) {
  const [x, z] = station.position;
  const color = active ? "#f2b84b" : complete ? "#50d0cc" : "#6f8494";
  return <RigidBody type="fixed" colliders={false} position={[x, 0, z]}>
    <CuboidCollider args={[2.05, 1.35, 1.65]} position={[0, 1.35, 0]} />
    <group onClick={(event) => { event.stopPropagation(); onSelect(station); }}>
      <mesh position={[0, .12, 0]} receiveShadow castShadow><boxGeometry args={[4.1, .24, 3.3]} /><meshStandardMaterial color="#1b2c3c" metalness={.7} roughness={.4} /></mesh>
      <mesh position={[0, 1.3, 0]} castShadow><boxGeometry args={[3.55, 2.25, 2.75]} /><meshStandardMaterial color="#132433" metalness={.45} roughness={.52} /></mesh>
      <mesh position={[0, 2.48, 0]}><boxGeometry args={[3.9, .12, 3.05]} /><meshStandardMaterial color={color} emissive={color} emissiveIntensity={active ? 2.6 : .35} /></mesh>
      <group position={[0, .3, 1.55]}><MachineIcon kind={station.icon} active={active} /></group>
      {active && <mesh position={[0, 7, 0]}><cylinderGeometry args={[.05, .42, 8, 12]} /><meshBasicMaterial color="#f2b84b" transparent opacity={.32} depthWrite={false} /></mesh>}
      {active && <pointLight position={[0, 3, 0]} color="#f2b84b" intensity={42} distance={9} decay={2} />}
    </group>
  </RigidBody>;
}

function Conveyor() {
  const segments = [];
  for (let index = 0; index < ROUTE.length - 1; index += 1) {
    const [ax, az] = ROUTE[index];
    const [bx, bz] = ROUTE[index + 1];
    const dx = bx - ax;
    const dz = bz - az;
    const length = Math.hypot(dx, dz);
    segments.push(<mesh key={index} position={[(ax + bx) / 2, .22, (az + bz) / 2]} rotation={[0, -Math.atan2(dz, dx), 0]} receiveShadow>
      <boxGeometry args={[length, .18, 1.15]} /><meshStandardMaterial color="#263c4c" metalness={.8} roughness={.35} />
    </mesh>);
  }
  return <group>{segments}</group>;
}

function ProductCarrier({ stage }) {
  const ref = useRef();
  const target = useMemo(() => new THREE.Vector3(ROUTE[stage][0], .95, ROUTE[stage][1]), [stage]);
  useFrame((_, delta) => ref.current?.position.lerp(target, Math.min(1, delta * 1.7)));
  return <group ref={ref} position={[ROUTE[0][0], .95, ROUTE[0][1]]}>
    <mesh castShadow><boxGeometry args={[3.5, .34, 2.2]} /><meshStandardMaterial color="#2d4353" metalness={.8} /></mesh>
    <mesh position={[0, .8, 0]}><boxGeometry args={[3.2, 1.5, 1.9]} /><meshStandardMaterial color="#d47542" transparent opacity={.12} wireframe /></mesh>
    {COMPONENTS.map((component, index) => {
      const visible = stage >= component.stage;
      const x = (index % 3 - 1) * .95;
      const z = (Math.floor(index / 3) - .5) * .82;
      return <group key={component.name} position={[x, visible ? .65 : 3.2, z]} scale={visible ? 1 : .001}>
        <mesh castShadow><boxGeometry args={[.68, .72, .6]} /><meshStandardMaterial color={component.color} metalness={.45} roughness={.35} emissive={component.color} emissiveIntensity={.16} /></mesh>
        <Html position={[0, .64, 0]} center distanceFactor={13} className="component-tag"><span>{component.name}</span></Html>
      </group>;
    })}
  </group>;
}

function FactoryAgents({ stage }) {
  return <>{stationData.slice(1, 9).map((station, index) => {
    const active = station.index === stage;
    return <group key={station.id} position={[station.position[0] + (index % 2 ? 2.7 : -2.7), .15, station.position[1] + 2.4]}>
      <mesh position={[0, .65, 0]} castShadow><cylinderGeometry args={[.28, .36, .9, 8]} /><meshStandardMaterial color={active ? "#f2b84b" : "#597082"} metalness={.55} /></mesh>
      <mesh position={[0, 1.25, 0]}><sphereGeometry args={[.28, 12, 8]} /><meshStandardMaterial color="#142433" emissive={active ? "#f2b84b" : "#50d0cc"} emissiveIntensity={active ? 2 : .4} /></mesh>
    </group>;
  })}</>;
}

function FactoryShell() {
  return <>
    <RigidBody type="fixed" colliders={false}>
      <CuboidCollider args={[35, .2, 24]} position={[0, -.2, 0]} />
      <mesh position={[0, -.25, 0]} receiveShadow><boxGeometry args={[70, .5, 48]} /><meshStandardMaterial color="#0d1b27" roughness={.82} /></mesh>
    </RigidBody>
    {[-25, -15, -5, 5, 15, 25].map((x) => <group key={x} position={[x, 0, 0]}>
      <mesh position={[0, 5, -16]}><boxGeometry args={[.28, 10, .28]} /><meshStandardMaterial color="#365065" metalness={.8} /></mesh>
      <mesh position={[0, 5, 16]}><boxGeometry args={[.28, 10, .28]} /><meshStandardMaterial color="#365065" metalness={.8} /></mesh>
      <mesh position={[0, 9.8, 0]}><boxGeometry args={[.25, .25, 32]} /><meshStandardMaterial color="#365065" metalness={.8} /></mesh>
    </group>)}
    <gridHelper args={[70, 70, "#294050", "#172a38"]} position={[0, .02, 0]} />
  </>;
}

function OperatorModel() {
  return <group>
    <mesh position={[0, .72, 0]} castShadow><capsuleGeometry args={[.34, .65, 8, 12]} /><meshStandardMaterial color="#d47542" metalness={.4} roughness={.45} /></mesh>
    <mesh position={[0, 1.52, 0]} castShadow><sphereGeometry args={[.31, 14, 10]} /><meshStandardMaterial color="#e9ece5" roughness={.45} /></mesh>
    <mesh position={[0, 1.54, -.28]}><boxGeometry args={[.4, .12, .08]} /><meshStandardMaterial color="#50d0cc" emissive="#50d0cc" emissiveIntensity={1.8} /></mesh>
  </group>;
}

function OperatorInput({ operatorRef, replay }) {
  const [, getKeyboard] = useKeyboardControls();
  const { camera } = useThree();
  const direction = useMemo(() => new THREE.Vector3(), []);
  const lastManualAt = useRef(-Infinity);
  useFrame((state) => {
    const keyboard = getKeyboard();
    const joystick = useJoystickStore.getState().joysticks.default ?? { x: 0, y: 0 };
    const buttons = useButtonStore.getState().buttons;
    const manual = keyboard.forward || keyboard.backward || keyboard.leftward || keyboard.rightward || keyboard.jump || joystick.active || buttons.jump;
    if (manual) lastManualAt.current = state.clock.elapsedTime;
    const manualOverride = state.clock.elapsedTime - lastManualAt.current < 2.5;
    const operator = operatorRef.current;
    if (!operator) return;
    if (!manualOverride && replay.playing) {
      const [targetX, targetZ] = OPERATOR_ROUTE[replay.stage];
      direction.set(targetX - operator.currPos.x, 0, targetZ - operator.currPos.z);
      const distance = direction.length();
      if (distance > .65) operator.setForwardDir(direction.normalize());
      operator.setMovement({
        forward: distance > .65,
        backward: false,
        leftward: false,
        rightward: false,
        run: distance > 7,
        jump: false,
        joystick: { x: 0, y: 0 },
      });
      return;
    }
    camera.getWorldDirection(direction);
    direction.y = 0;
    if (direction.lengthSq() > 0) operator.setForwardDir(direction.normalize());
    operator.setMovement({
      forward: keyboard.forward,
      backward: keyboard.backward,
      leftward: keyboard.leftward,
      rightward: keyboard.rightward,
      run: keyboard.run,
      jump: keyboard.jump || Boolean(buttons.jump),
      joystick: { x: joystick.x, y: joystick.y },
    });
  });
  return null;
}

function FollowOperator({ operatorRef, onNearest, replay, onAutoInspect }) {
  const { camera } = useThree();
  const lastNearest = useRef(null);
  const lastInspectedStage = useRef(-1);
  const desired = useMemo(() => new THREE.Vector3(), []);
  const look = useMemo(() => new THREE.Vector3(), []);
  useFrame((_, delta) => {
    const operator = operatorRef.current;
    if (!operator) return;
    const pos = operator.currPos;
    desired.set(pos.x + 8, pos.y + 7, pos.z + 9);
    camera.position.lerp(desired, Math.min(1, delta * 3.5));
    look.set(pos.x, pos.y + .8, pos.z);
    camera.lookAt(look);
    let nearest = null;
    let distance = 5;
    stationData.forEach((station) => {
      const next = Math.hypot(pos.x - station.position[0], pos.z - station.position[1]);
      if (next < distance) { nearest = station; distance = next; }
    });
    if (nearest?.id !== lastNearest.current?.id) {
      lastNearest.current = nearest;
      onNearest(nearest);
    }
    if (replay.playing && nearest?.index === replay.stage && distance < 4.6 && lastInspectedStage.current !== replay.stage) {
      lastInspectedStage.current = replay.stage;
      onAutoInspect(nearest);
    }
  });
  return null;
}

function GuidedCamera({ stage }) {
  const { camera } = useThree();
  const look = useMemo(() => new THREE.Vector3(), []);
  const desired = useMemo(() => new THREE.Vector3(), []);
  useFrame((_, delta) => {
    const [x, z] = ROUTE[stage];
    desired.set(x + 8, 7, z + 10);
    look.set(x, 1, z);
    camera.position.lerp(desired, Math.min(1, delta * 1.6));
    camera.lookAt(look);
  });
  return null;
}

function World({ variant, replay, inspectStation, setNearest, onAutoInspect }) {
  const operatorRef = useRef();
  const explorer = variant === "A";
  return <Canvas tabIndex={0} shadows dpr={[1, 1.6]} camera={{ position: explorer ? [-15, 7, 0] : [30, 34, 34], fov: explorer ? 48 : 42 }} gl={{ antialias: true }}>
    <color attach="background" args={["#07111b"]} />
    <fog attach="fog" args={["#07111b", 30, 82]} />
    <ambientLight intensity={.62} />
    <hemisphereLight args={["#84b6c8", "#18222b", 2.2]} />
    <directionalLight position={[-15, 24, 12]} intensity={3.2} castShadow shadow-mapSize={[2048, 2048]} />
    <Suspense fallback={null}>
      <Physics gravity={[0, -18, 0]} timeStep="vary">
        <FactoryShell />
        <Conveyor />
        {stationData.map((station) => <Station key={`${station.id}-${station.index}`} station={station} active={station.index === replay.stage} complete={station.index < replay.stage} onSelect={inspectStation} />)}
        <ProductCarrier stage={replay.stage} />
        <FactoryAgents stage={replay.stage} />
        {explorer && <KeyboardControls map={keyboardMap}>
          <Ecctrl ref={operatorRef} position={[OPERATOR_ROUTE[0][0], 2, OPERATOR_ROUTE[0][1]]} useCustomForward enableToggleRun={false} maxWalkVel={5} maxRunVel={8} jumpVel={5} floatHeight={.65} capsuleHalfHeight={.6} capsuleRadius={.38}>
            <OperatorModel />
          </Ecctrl>
          <OperatorInput operatorRef={operatorRef} replay={replay} />
          <FollowOperator operatorRef={operatorRef} onNearest={setNearest} replay={replay} onAutoInspect={onAutoInspect} />
        </KeyboardControls>}
      </Physics>
    </Suspense>
    {variant === "B" && <OrbitControls makeDefault target={[-2, 0, 0]} minDistance={18} maxDistance={75} maxPolarAngle={Math.PI * .46} />}
    {variant === "C" && <GuidedCamera stage={replay.stage} />}
  </Canvas>;
}

function Header({ replay }) {
  return <header className="experience-header">
    <div className="experience-brand"><span className="factory-mark"><i /><i /><i /></span><span><strong>Regeneration Yard</strong><small>Immersive software factory</small></span></div>
    <div className="live-cycle"><i className={replay.playing ? "live" : ""} /><span><small>Recorded cycle</small><strong>{replay.playing ? "Factory running" : "Simulation paused"}</strong></span></div>
    <div className="cycle-score"><small>Independent result</small><strong>9</strong><span>/ 11</span></div>
  </header>;
}

function Timeline({ replay }) {
  return <div className="replay-line">
    <button type="button" onClick={() => replay.setPlaying(!replay.playing)}>{replay.playing ? "Pause" : "Play"}</button>
    <div><span style={{ width: `${replay.progress * 100}%` }} /><i style={{ left: `${replay.progress * 100}%` }} /></div>
    <output>{(replay.elapsed / 1000).toFixed(1)}s</output>
  </div>;
}

function Inspector({ station, replay, onClose, automatic }) {
  if (!station) return null;
  const connections = stationConnections(station);
  const state = station.index === replay.stage ? "Working now" : station.index < replay.stage ? "Completed" : "Waiting";
  return <aside className={`station-inspector ${automatic ? "auto-inspection" : ""}`} role="dialog" aria-modal={automatic ? undefined : "true"} aria-labelledby={`station-title-${station.id}`}>
    <button className="close-inspector" type="button" onClick={onClose} aria-label="Close station">×</button>
    <small>{automatic ? "Cycle inspection" : station.role} · station {station.index + 1}</small>
    <h2 id={`station-title-${station.id}`}>{station.name}</h2>
    <p>{station.hint}</p>
    <div className="inspector-state"><i className={state === "Working now" ? "live" : ""} /><span><small>Simulation</small><strong>{state}</strong></span></div>
    <dl><div><dt>Actual C4 element</dt><dd>{station.c4.name}</dd></div><div><dt>Element type</dt><dd>{station.c4.type}</dd></div></dl>
    {connections.length > 0 && <section><strong>Repository relationships</strong>{connections.map((item) => <p key={item}>{item}</p>)}</section>}
    <footer>{automatic ? "Following cycle · closes automatically · " : ""}Source: .pi/architecture.md · Container view</footer>
  </aside>;
}

function ExplorerOverlay({ replay, nearest, setSelected }) {
  const setJump = (active) => useButtonStore.getState().setButtonActive("jump", active);
  const pulseJump = (event) => {
    if (event.detail !== 0) return;
    setJump(true);
    window.requestAnimationFrame(() => setJump(false));
  };
  return <>
    <section className="explorer-objective"><small>Current objective · follow the amber beacon</small><strong>{STAGES[replay.stage].name}</strong><p>{STAGES[replay.stage].hint}</p></section>
    <div className="controls-help"><span className={replay.playing ? "cycle-follow active" : "cycle-follow"}>Cycle follow <b>{replay.playing ? "ON" : "PAUSED"}</b></span><span><kbd>WASD</kbd> Take control</span><span><kbd>Shift</kbd> Run</span><span><kbd>Space</kbd> Jump</span></div>
    {nearest && <button className="interact-prompt" type="button" onPointerDown={() => setSelected(nearest)} onClick={() => setSelected(nearest)}><kbd>E</kbd><span>Inspect <strong>{nearest.name}</strong></span></button>}
    <div className="touch-controls">
      <div className="touch-joystick" role="application" aria-label="Movement joystick"><Joystick /></div>
      <button className="touch-jump" type="button" aria-label="Jump" onPointerDown={(event) => { event.currentTarget.setPointerCapture(event.pointerId); setJump(true); }} onPointerUp={() => setJump(false)} onPointerCancel={() => setJump(false)} onLostPointerCapture={() => setJump(false)} onBlur={() => setJump(false)} onClick={pulseJump}>Jump</button>
      {nearest && <button className="touch-inspect" type="button" onClick={() => setSelected(nearest)}>Inspect</button>}
    </div>
  </>;
}

function SimulationOverlay({ replay, setSelected }) {
  return <section className="simulation-deck">
    <header><small>Factory simulation</small><h1>Agentic assembly line</h1><p>Select any station or orbit the world.</p></header>
    <ol>{stationData.map((station) => <li key={`${station.id}-${station.index}`} className={station.index === replay.stage ? "active" : station.index < replay.stage ? "complete" : ""}>
      <button type="button" onClick={() => setSelected(station)}><span>{station.index < replay.stage ? "✓" : station.index + 1}</span><div><strong>{station.name}</strong><small>{station.c4.name}</small></div></button>
    </li>)}</ol>
  </section>;
}

function TourOverlay({ replay, setSelected }) {
  const station = stationData[replay.stage];
  return <section className="tour-card">
    <div className="tour-count">{replay.stage + 1}<span>/10</span></div>
    <small>{station.role}</small>
    <h1>{station.name}</h1>
    <p>{station.hint}</p>
    <dl><div><dt>Factory machinery</dt><dd>{station.c4.name}</dd></div><div><dt>Product on line</dt><dd>Löyly / Node</dd></div></dl>
    <button type="button" onClick={() => setSelected(station)}>Open station evidence</button>
    <footer><span style={{ width: `${(replay.stage + 1) * 10}%` }} /></footer>
  </section>;
}

function PrototypeSwitcher({ variant, setVariant }) {
  const keys = Object.keys(VARIANTS);
  const move = (direction) => setVariant(keys[(keys.indexOf(variant) + direction + keys.length) % keys.length]);
  return <nav className="prototype-switcher" aria-label="Prototype variants">
    <button type="button" onClick={() => move(-1)} aria-label="Previous variant">←</button>
    <span><small>Alt + ← / → · Prototype {variant}</small><strong>{VARIANTS[variant].name}</strong></span>
    <button type="button" onClick={() => move(1)} aria-label="Next variant">→</button>
  </nav>;
}

function App() {
  const params = new URLSearchParams(location.search);
  const [variant, setVariantState] = useState(VARIANTS[params.get("variant")] ? params.get("variant") : "A");
  const [selected, setSelected] = useState(null);
  const [nearest, setNearest] = useState(null);
  const [automaticInspection, setAutomaticInspection] = useState(false);
  const replay = useReplay();
  const showSwitcher = import.meta.env.DEV || params.get("prototype") === "1";
  const setVariant = (next) => {
    const url = new URL(location.href);
    url.searchParams.set("variant", next);
    if (!import.meta.env.DEV) url.searchParams.set("prototype", "1");
    history.replaceState({}, "", url);
    setVariantState(next);
    setSelected(null);
    setAutomaticInspection(false);
  };
  const inspectStation = (station) => {
    setAutomaticInspection(false);
    setSelected(station);
  };
  const inspectAutomatically = (station) => {
    if (selected && !automaticInspection) return;
    setAutomaticInspection(true);
    setSelected(station);
  };
  const closeInspector = () => {
    setAutomaticInspection(false);
    setSelected(null);
  };
  useEffect(() => {
    if (!automaticInspection || !selected) return undefined;
    const timer = window.setTimeout(() => {
      setAutomaticInspection(false);
      setSelected(null);
    }, 3200);
    return () => window.clearTimeout(timer);
  }, [automaticInspection, selected]);
  useEffect(() => {
    const handle = (event) => {
      if (event.key === "Escape" && selected) {
        closeInspector();
        return;
      }
      if (event.key.toLowerCase() === "e" && nearest && variant === "A") inspectStation(nearest);
      if (!showSwitcher || event.target.matches("input, textarea, select, [contenteditable]")) return;
      if (event.altKey && event.key === "ArrowLeft") {
        event.preventDefault();
        setVariant(Object.keys(VARIANTS)[(Object.keys(VARIANTS).indexOf(variant) + 2) % 3]);
      }
      if (event.altKey && event.key === "ArrowRight") {
        event.preventDefault();
        setVariant(Object.keys(VARIANTS)[(Object.keys(VARIANTS).indexOf(variant) + 1) % 3]);
      }
    };
    window.addEventListener("keydown", handle);
    return () => window.removeEventListener("keydown", handle);
  }, [nearest, selected, variant, showSwitcher]);
  return <main className={`experience variant-${variant}`}>
    <Header replay={replay} />
    <section className="world-shell" aria-label={`${VARIANTS[variant].name} factory world`}>
      <World variant={variant} replay={replay} inspectStation={inspectStation} setNearest={setNearest} onAutoInspect={inspectAutomatically} />
      <Timeline replay={replay} />
      {variant === "A" && <ExplorerOverlay replay={replay} nearest={nearest} setSelected={inspectStation} />}
      {variant === "B" && <SimulationOverlay replay={replay} setSelected={inspectStation} />}
      {variant === "C" && <TourOverlay replay={replay} setSelected={inspectStation} />}
    </section>
    <Inspector station={selected} replay={replay} onClose={closeInspector} automatic={automaticInspection} />
    {showSwitcher && !selected && <PrototypeSwitcher variant={variant} setVariant={setVariant} />}
  </main>;
}

createRoot(document.getElementById("root")).render(<App />);
