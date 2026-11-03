// PROTOTYPE: Three immersive ways to explore the same live software factory.
// Question: does spatial presence make Grain, Pi and Löyly easier to understand than a dashboard?
import React, { Suspense, useEffect, useMemo, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { Canvas, useFrame, useThree } from "@react-three/fiber";
import { Html, KeyboardControls, Line, OrbitControls, useKeyboardControls } from "@react-three/drei";
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
  { id: "cut", name: "Pin product model", role: "Receiving", icon: "blueprint", node: "cutworker", focus: ["git", "worker", "cutworker", "cuts", "gondolin"], hint: "The approved Löyly model enters the factory read-only." },
  { id: "calibrate", name: "Calibrate tooling", role: "Tool room", icon: "gauge", node: "registry", focus: ["extension", "worker", "registry", "git", "events"], hint: "Pi proves its tools before it touches the product." },
  { id: "build", name: "Assemble components", role: "Body shop", icon: "robot", node: "builder", focus: ["worker", "builder", "cuts", "llm", "outputs", "gondolin"], hint: "A fresh builder creates the C4 product containers." },
  { id: "instrument", name: "Wire runtime signals", role: "Sensor fit", icon: "signal", node: "outputs", focus: ["builder", "outputs", "grader", "events", "projections"], hint: "Telemetry is fitted without changing the candidate." },
  { id: "oracle", name: "Bind product contract", role: "Marriage", icon: "link", node: "worker", focus: ["extension", "worker", "registry", "git", "events"], hint: "The candidate meets the pinned product contract." },
  { id: "verify", name: "Boot system", role: "Road test", icon: "power", node: "docker", focus: ["worker", "grader", "cuts", "outputs", "gondolin", "docker", "subject"], hint: "The assembled system boots inside its isolated runtime." },
  { id: "repair", name: "Repair bay", role: "Rework", icon: "wrench", node: "builder", focus: ["worker", "builder", "cuts", "llm", "outputs", "gondolin"], hint: "A new builder receives failure evidence for one repair." },
  { id: "grade", name: "Independent inspection", role: "Quality gate", icon: "inspect", node: "grader", focus: ["worker", "grader", "cuts", "outputs", "gondolin", "docker", "subject"], hint: "Independent graders—not the builder—author the verdict." },
  { id: "signals", name: "Check telemetry", role: "Signal lab", icon: "pulse", node: "events", focus: ["worker", "grader", "outputs", "events", "projections"], hint: "Grain facts and runtime signals are checked for completeness." },
  { id: "record", name: "Register product", role: "Product yard", icon: "archive", node: "projections", focus: ["extension", "worker", "events", "projections"], hint: "Grain records the result; it does not advance the line." },
];

const STAGE_DETAILS = {
  cut: { owner: "Cut adapter", boundary: "Pins commit/tree and enforces the physical fence; it does not select scope.", input: "Approved commit + cut policy", output: "Immutable cut + manifest", product: "Löyly contract only · no candidate yet" },
  calibrate: { owner: "ORC worker · oracle components", boundary: "Descriptor digests and bindings must validate before a builder starts.", input: "Instrument + product OCI descriptors", output: "Calibrated immutable bindings", product: "Target stack: Node" },
  build: { owner: "Fresh Builder adapter session", boundary: "May create the candidate; cannot grade it or author the final measurement.", input: "Fenced cut + selected instruction/scope", output: "Candidate snapshot + builder transcript", product: "Booking API and Booking core assembled" },
  instrument: { owner: "Instrument stage + evidence store", boundary: "Fits observation without changing candidate behavior or denominator.", input: "Generated candidate + runtime contract", output: "OTel runtime evidence", product: "Event store and schedules become observable" },
  oracle: { owner: "ORC worker · model resolver", boundary: "The product contract is pinned independently of candidate claims.", input: "Product oracle digest + candidate binding", output: "Cross-oracle binding", product: "All Löyly contract surfaces bound" },
  verify: { owner: "Execution adapter", boundary: "Runs in an independent room and cannot change the candidate.", input: "Cut + candidate + Compose commands", output: "Command evidence + test result", product: "13 application tests after repair" },
  repair: { owner: "New Builder adapter session", boundary: "Receives failure evidence once; the failed builder is not resumed.", input: "Attempt 1 evidence: npm not found", output: "Attempt 2 candidate image", product: "Same product contract · rebuilt runtime" },
  grade: { owner: "Independent Execution adapter", boundary: "The builder cannot see or author the independent verdict.", input: "Sealed candidate + 11 product items", output: "9 pass / 2 fail verdicts", product: "Household booking behavior measured" },
  signals: { owner: "Instrument conformance grader", boundary: "Checks captured facts for completeness; product events stay product-owned.", input: "Domain events + projection snapshots", output: "8 / 8 signals complete", product: "Weekly, heater and billing projections correlated" },
  record: { owner: "Grain event/projection path", boundary: "Records observation and measurement; it does not advance ORC workflow.", input: "Sealed result + independent evidence", output: "Durable cycle measurement", product: "Historical verdict: 9 / 11" },
};

const ROUTE = [
  [-18, -8], [-10, -12], [0, -13], [10, -12], [18, -8],
  [21, 2], [15, 10], [5, 14], [-7, 13], [-17, 8],
];

const OPERATOR_ROUTE = [
  [-20, -11], [-11, -16], [0, -17], [11, -16], [21, -11],
  [25, 2], [18, 13], [6, 18], [-9, 17], [-21, 10],
];

const PHOENIX_PHASES = [
  { name: "Intent", range: [0, 0], truth: "Sources and specification survive" },
  { name: "Fence", range: [1, 1], truth: "Prior implementation is physically removed" },
  { name: "Generate", range: [2, 4], truth: "Fresh candidates read the same contract" },
  { name: "Ratify", range: [5, 7], truth: "Independent gates—not builders—decide" },
  { name: "Measure", range: [8, 8], truth: "The delta measures specification quality" },
  { name: "Learn", range: [9, 9], truth: "Evidence returns to the specification" },
];

const RUNTIME_FLEET = [
  { name: "TypeScript / Node 22", before: 10, after: 11, color: "#72a9c4" },
  { name: "Rust 1.91", before: 9, after: 11, color: "#d47542" },
  { name: "Java 21", before: 9, after: 11, color: "#e8b84d" },
  { name: "Rails 8 / Ruby", before: 0, after: 11, color: "#9c83bd" },
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
  A: { name: "Walk the Phoenix loop", description: "Third-person regeneration cycle" },
  B: { name: "Phoenix system map", description: "Overhead architecture view" },
  C: { name: "Guided regeneration", description: "Phase-by-phase story" },
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

function stationTopology(station) {
  const ids = new Set(station.focus);
  const nodes = station.focus.map((id) => C4.nodes.get(id)).filter(Boolean);
  const edges = C4.edges.filter((edge) => ids.has(edge.from) && ids.has(edge.to));
  return { nodes, edges };
}

function stageEvents(station) {
  return E2E_RECORDING.timeline.filter((event) => event.stageIndex === station.index);
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

function ReturnSignal({ curve, index }) {
  const ref = useRef();
  useFrame(({ clock }) => {
    if (!ref.current) return;
    const point = curve.getPoint((clock.elapsedTime * .055 + index * .2) % 1);
    ref.current.position.copy(point);
  });
  return <mesh ref={ref}><octahedronGeometry args={[.22, 0]} /><meshBasicMaterial color="#f2b84b" toneMapped={false} /></mesh>;
}

function PhoenixArchitecture({ stage }) {
  const feedbackCurve = useMemo(() => new THREE.CatmullRomCurve3([
    new THREE.Vector3(-17, 3.2, 8), new THREE.Vector3(-25, 7, 6),
    new THREE.Vector3(-26, 8, -2), new THREE.Vector3(-23, 5, -9), new THREE.Vector3(-18, 3.2, -8),
  ]), []);
  const feedbackPoints = useMemo(() => feedbackCurve.getPoints(48), [feedbackCurve]);
  return <group>
    <group position={[-24, 0, -1]}>
      {[0, 1, 2, 3].map((level) => <mesh key={level} position={[0, .35 + level * .5, 0]} castShadow>
        <boxGeometry args={[4.2 - level * .35, .34, 3.1 - level * .24]} />
        <meshStandardMaterial color={level === 3 ? "#f2b84b" : "#294759"} metalness={.35} roughness={.62} emissive={level === 3 ? "#f2b84b" : "#000"} emissiveIntensity={.35} />
      </mesh>)}
      <Html position={[0, 3.1, 0]} center distanceFactor={13} className="phoenix-landmark-label"><span><small>WHAT SURVIVES</small><strong>Sources → Wiki → Plan → Spec</strong><em>Stack-neutral intent</em></span></Html>
    </group>
    <group position={[0, 0, 1]}>
      {[0, 1, 2].map((level) => <mesh key={level} position={[0, .45 + level * .38, 0]}><cylinderGeometry args={[2.3 - level * .3, 2.3 - level * .3, .28, 32]} /><meshStandardMaterial color={level === 2 ? "#50d0cc" : "#19384b"} metalness={.72} emissive="#50d0cc" emissiveIntensity={level === 2 ? .5 : .08} /></mesh>)}
      <Html position={[0, 2.6, 0]} center distanceFactor={13} className="phoenix-landmark-label grain"><span><small>DURABLE MEMORY</small><strong>Grain lineage</strong><em>facts · evidence · measurements</em></span></Html>
    </group>
    <group position={[0, 0, 5]}>
      {RUNTIME_FLEET.map((runtime, index) => {
        const x = (index - 1.5) * 4.1;
        const born = stage >= 2;
        return <group key={runtime.name} position={[x, .6, 0]}>
          <mesh castShadow={born}><dodecahedronGeometry args={[1.05, 0]} /><meshStandardMaterial color={runtime.color} metalness={.52} roughness={.3} emissive={runtime.color} emissiveIntensity={born && stage >= 7 ? .38 : .08} transparent opacity={born ? 1 : .18} wireframe={!born} /></mesh>
          <Html position={[0, 1.5, 0]} center distanceFactor={12} className={`runtime-candidate-label ${born ? "born" : "awaiting"}`}><span><small>{born ? "DISPOSABLE CANDIDATE" : "CLEAN ROOM READY"}</small><strong>{runtime.name}</strong><em>{born ? `${runtime.before}/11 → ${runtime.after}/11` : "awaiting regeneration"}</em></span></Html>
        </group>;
      })}
      <Html position={[0, .2, 2.1]} center distanceFactor={13} className="fleet-proof-label"><span>SEPARATE FOUR-RUNTIME AUDIT · ONE CONTRACT · 44/44</span></Html>
    </group>
    <Line points={feedbackPoints} color="#f2b84b" lineWidth={2} transparent opacity={.7} />
    {[0, 1, 2, 3, 4].map((index) => <ReturnSignal key={index} curve={feedbackCurve} index={index} />)}
    <Html position={[-26, 9.2, -1]} center distanceFactor={14} className="feedback-label"><span><small>THE PHOENIX RETURN</small><strong>Delta becomes better intent</strong><em>Never patch only the code</em></span></Html>
  </group>;
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

function OperatorInput({ operatorRef, replay, disabled }) {
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
    if (disabled) {
      operator.setMovement({ forward: false, backward: false, leftward: false, rightward: false, run: false, jump: false, joystick: { x: 0, y: 0 } });
      return;
    }
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

function World({ variant, replay, inspectStation, setNearest, onAutoInspect, inspectionOpen }) {
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
        <PhoenixArchitecture stage={replay.stage} />
        <Conveyor />
        {stationData.map((station) => <Station key={`${station.id}-${station.index}`} station={station} active={station.index === replay.stage} complete={station.index < replay.stage} onSelect={inspectStation} />)}
        <ProductCarrier stage={replay.stage} />
        <FactoryAgents stage={replay.stage} />
        {explorer && <KeyboardControls map={keyboardMap}>
          <Ecctrl ref={operatorRef} position={[OPERATOR_ROUTE[0][0], 2, OPERATOR_ROUTE[0][1]]} useCustomForward enableToggleRun={false} maxWalkVel={5} maxRunVel={8} jumpVel={5} floatHeight={.65} capsuleHalfHeight={.6} capsuleRadius={.38}>
            <OperatorModel />
          </Ecctrl>
          <OperatorInput operatorRef={operatorRef} replay={replay} disabled={inspectionOpen} />
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
    <div className="experience-brand"><span className="factory-mark"><i /><i /><i /></span><span><strong>Phoenix Yard</strong><small>Specification becomes disposable systems</small></span></div>
    <div className="live-cycle"><i className={replay.playing ? "live" : ""} /><span><small>Recorded cycle</small><strong>{replay.playing ? "Factory running" : "Simulation paused"}</strong></span></div>
    <div className="cycle-score"><small>Independent result</small><strong>9</strong><span>/ 11</span></div>
  </header>;
}

function PhoenixLoopOverlay({ replay }) {
  const active = PHOENIX_PHASES.findIndex((phase) => replay.stage >= phase.range[0] && replay.stage <= phase.range[1]);
  return <section className="phoenix-loop-map">
    <header><small>PHOENIX ARCHITECTURE</small><strong>Code burns. Learning returns.</strong></header>
    <ol>{PHOENIX_PHASES.map((phase, index) => <li key={phase.name} className={index === active ? "active" : index < active ? "complete" : ""}>
      <i>{index < active ? "✓" : index + 1}</i><span><strong>{phase.name}</strong><small>{phase.truth}</small></span>
    </li>)}</ol>
    <footer><span><b>Survives</b> intent · contract · evidence · lineage</span><span><b>Reborn</b> implementation · stack · runtime</span></footer>
  </section>;
}

function Timeline({ replay }) {
  return <div className="replay-line">
    <button type="button" onClick={() => replay.setPlaying(!replay.playing)}>{replay.playing ? "Pause" : "Play"}</button>
    <div><span style={{ width: `${replay.progress * 100}%` }} /><i style={{ left: `${replay.progress * 100}%` }} /></div>
    <output>{(replay.elapsed / 1000).toFixed(1)}s</output>
  </div>;
}

function TopologyPacket({ from, to, index, running }) {
  const ref = useRef();
  useFrame(({ clock }) => {
    if (!ref.current) return;
    const progress = running ? (clock.elapsedTime * .2 + index * .19) % 1 : .5;
    ref.current.position.set(
      THREE.MathUtils.lerp(from[0], to[0], progress),
      THREE.MathUtils.lerp(from[1], to[1], progress) + Math.sin(progress * Math.PI) * .3,
      THREE.MathUtils.lerp(from[2], to[2], progress),
    );
  });
  return <mesh ref={ref}>
    <octahedronGeometry args={[.11, 0]} />
    <meshStandardMaterial color="#f2b84b" emissive="#f2b84b" emissiveIntensity={4} toneMapped={false} />
  </mesh>;
}

function TopologyNode({ node, position, central, running }) {
  const ref = useRef();
  useFrame(({ clock }) => {
    if (!ref.current) return;
    const pulse = central && running ? 1 + Math.sin(clock.elapsedTime * 4.5) * .06 : 1;
    ref.current.scale.setScalar(pulse);
  });
  const dataStore = node.type.includes("data store");
  const external = node.type.includes("External");
  const color = central ? "#50d0cc" : external ? "#d47542" : dataStore ? "#f2b84b" : "#7598aa";
  return <group ref={ref}>
    <mesh position={position} castShadow>
      {dataStore ? <cylinderGeometry args={[central ? .9 : .7, central ? .9 : .7, central ? 1.5 : 1.15, 20]} /> : <boxGeometry args={central ? [1.8, 1.5, 1.35] : [1.45, 1.12, 1.05]} />}
      <meshStandardMaterial color="#122b3d" metalness={.64} roughness={.3} emissive={color} emissiveIntensity={central && running ? .45 : .12} />
    </mesh>
    <mesh position={[position[0], position[1] + (central ? .82 : .62), position[2]]}>
      <boxGeometry args={[central ? 1.84 : 1.49, .08, central ? 1.39 : 1.09]} />
      <meshStandardMaterial color={color} emissive={color} emissiveIntensity={2.3} />
    </mesh>
    <Html position={[position[0], position[1] + (central ? 1.35 : 1.05), position[2]]} center distanceFactor={9} className={`system-node-label ${central ? "central" : ""}`}>
      <span><small>{node.type}</small><strong>{node.name}</strong>{central && node.description && <em>{node.description}</em>}</span>
    </Html>
  </group>;
}

function ProductModule({ component, index, visible }) {
  const x = (index - (COMPONENTS.length - 1) / 2) * 1.18;
  return <group position={[x, .55, 4.1]}>
    <mesh castShadow>
      {component.shape === "store" ? <cylinderGeometry args={[.38, .38, .62, 18]} /> : component.shape === "signal" ? <octahedronGeometry args={[.42, 0]} /> : <boxGeometry args={[.72, .66, .62]} />}
      <meshStandardMaterial color={visible ? component.color : "#314453"} metalness={.48} roughness={.34} emissive={component.color} emissiveIntensity={visible ? .3 : 0} transparent opacity={visible ? 1 : .4} />
    </mesh>
    <Html position={[0, .68, 0]} center distanceFactor={9} className={`product-node-label ${visible ? "assembled" : "pending"}`}><span>{component.name}</span></Html>
  </group>;
}

function StationInterior({ station, replay }) {
  const running = station.index === replay.stage;
  const topology = stationTopology(station);
  const positions = useMemo(() => {
    const map = new Map([[station.node, [0, 1.25, 0]]]);
    topology.nodes.filter((node) => node.id !== station.node).forEach((node, index, nodes) => {
      const angle = -Math.PI / 2 + (index / Math.max(1, nodes.length)) * Math.PI * 2;
      map.set(node.id, [Math.cos(angle) * 4, .85, Math.sin(angle) * 2.65]);
    });
    return map;
  }, [station, topology.nodes]);
  return <Canvas dpr={[1, 1.5]} camera={{ position: [8.8, 7.2, 11.5], fov: 44 }} gl={{ antialias: true }}>
    <color attach="background" args={["#07131e"]} />
    <fog attach="fog" args={["#07131e", 17, 30]} />
    <ambientLight intensity={.7} />
    <hemisphereLight args={["#8fc8d2", "#101923", 2.6]} />
    <directionalLight position={[5, 9, 5]} intensity={4} castShadow />
    <pointLight position={[0, 3, 2]} color={running ? "#50d0cc" : "#718795"} intensity={running ? 38 : 14} distance={12} />
    <group position={[0, -.25, 0]}>
      <mesh position={[0, -.12, -.75]} receiveShadow><boxGeometry args={[12, .22, 10.5]} /><meshStandardMaterial color="#0d2130" metalness={.45} roughness={.72} /></mesh>
      <gridHelper args={[12, 24, "#315367", "#172f3f"]} position={[0, .01, -.75]} />
      <Html position={[-4.8, 4.1, -1]} distanceFactor={10} className="cutaway-layer-label"><span>HARNESS C4 · ACTUAL REPOSITORY TOPOLOGY</span></Html>
      {topology.edges.map((edge, index) => {
        const from = positions.get(edge.from);
        const to = positions.get(edge.to);
        if (!from || !to) return null;
        const midpoint = [(from[0] + to[0]) / 2, (from[1] + to[1]) / 2 + .2, (from[2] + to[2]) / 2];
        return <group key={`${edge.from}-${edge.to}`}>
          <Line points={[from, to]} color={edge.from === station.node || edge.to === station.node ? "#50d0cc" : "#3e6072"} lineWidth={1.1} transparent opacity={.78} />
          <TopologyPacket from={from} to={to} index={index} running={running} />
          <Html position={midpoint} center distanceFactor={10} className="topology-edge-label"><span>{edge.label}</span></Html>
        </group>;
      })}
      {topology.nodes.map((node) => <TopologyNode key={node.id} node={node} position={positions.get(node.id)} central={node.id === station.node} running={running} />)}
      <mesh position={[0, .08, 4.1]}><boxGeometry args={[8.2, .15, 1.25]} /><meshStandardMaterial color="#1d3545" metalness={.7} /></mesh>
      <Html position={[-4, 1.55, 4.1]} distanceFactor={10} className="cutaway-layer-label product"><span>LÖYLY PRODUCT · COMPONENT STATE</span></Html>
      {COMPONENTS.map((component, index) => <ProductModule key={component.name} component={component} index={index} visible={station.index >= component.stage} />)}
    </group>
    <OrbitControls makeDefault target={[0, .8, .45]} minDistance={9} maxDistance={20} maxPolarAngle={Math.PI * .49} enablePan={false} autoRotate={false} />
  </Canvas>;
}

function Inspector({ station, replay, onClose, automatic }) {
  if (!station) return null;
  const connections = stationConnections(station);
  const detail = STAGE_DETAILS[station.id];
  const events = stageEvents(station);
  const observedEvents = events.filter((event) => event.offset <= replay.elapsed);
  const evidence = observedEvents.at(-1) ?? events[0];
  const state = station.index === replay.stage ? "Working now" : station.index < replay.stage ? "Completed" : "Waiting";
  if (!automatic) return <section className="station-cutaway" role="dialog" aria-modal="true" aria-labelledby={`station-title-${station.id}`}>
    <div className="cutaway-world" aria-label={`Three-dimensional cutaway of ${station.name}`}><StationInterior station={station} replay={replay} /></div>
    <aside className="cutaway-evidence">
      <button className="close-inspector" type="button" onClick={onClose} aria-label="Exit station cutaway">×</button>
      <small>Inside station {station.index + 1} · {station.role}</small>
      <h2 id={`station-title-${station.id}`}>{station.name}</h2>
      <p>{station.hint}</p>
      <div className="cycle-identity"><span><small>CYCLE</small><strong>{E2E_RECORDING.cycleId}</strong></span><span><small>STACK</small><strong>{E2E_RECORDING.targetStack}</strong></span></div>
      <div className="inspector-state"><i className={state === "Working now" ? "live" : ""} /><span><small>Live replay state</small><strong>{state}</strong></span></div>
      <div className="system-facts">
        <div><small>OWNER</small><strong>{detail.owner}</strong></div>
        <div><small>INPUT</small><strong>{detail.input}</strong></div>
        <div><small>OUTPUT</small><strong>{detail.output}</strong></div>
      </div>
      <section className="boundary-rule"><small>RESPONSIBILITY BOUNDARY</small><p>{detail.boundary}</p></section>
      <section className="product-state"><header><small>LÖYLY PRODUCT</small><strong>{detail.product}</strong></header><div>{COMPONENTS.map((component) => <span key={component.name} className={station.index >= component.stage ? "assembled" : "pending"}>{component.name}</span>)}</div></section>
      {evidence && <section className="recorded-evidence"><header><small>RECORDED E2E EVIDENCE</small><strong>{evidence.type}</strong></header><p>{evidence.label}</p><dl><div><dt>Artifact</dt><dd>{evidence.sourcePath}</dd></div>{evidence.digest && <div><dt>Digest</dt><dd>{evidence.digest}</dd></div>}{evidence.itemId && <div><dt>Item / verdict</dt><dd>{evidence.itemId} · {evidence.verdict}</dd></div>}</dl></section>}
      <section className="station-event-log"><header><small>THIS STATION’S REPLAY EVENTS</small><strong>{events.length}</strong></header>{events.map((event) => <div key={`${event.offset}-${event.itemId ?? event.type}`} className={event.offset <= replay.elapsed ? "observed" : "future"}><time>{(event.offset / 1000).toFixed(1)}s</time><span><strong>{event.itemId ?? event.type}</strong><small>{event.label}</small></span></div>)}</section>
      {connections.length > 0 && <details><summary>Direct C4 relationships</summary>{connections.map((item) => <p key={item}>{item}</p>)}</details>}
      <details className="cycle-bindings"><summary>Sealed cycle bindings</summary><dl><div><dt>Cut</dt><dd>{E2E_RECORDING.binding.cutDigest}</dd></div><div><dt>Subject</dt><dd>{E2E_RECORDING.binding.subjectDigest}</dd></div><div><dt>Oracle</dt><dd>{E2E_RECORDING.binding.oracleDigest}</dd></div></dl></details>
      <footer><span>.pi/architecture.md + sealed E2E fixture</span><span><kbd>Esc</kbd> Exit</span></footer>
    </aside>
  </section>;
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
    <PhoenixLoopOverlay replay={replay} />
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
    <header><small>Phoenix architecture</small><h1>Regeneration loop</h1><p>One specification births disposable systems; evidence returns as learning.</p></header>
    <ol>{PHOENIX_PHASES.map((phase, index) => {
      const active = replay.stage >= phase.range[0] && replay.stage <= phase.range[1];
      const complete = replay.stage > phase.range[1];
      const station = stationData[active ? replay.stage : phase.range[0]];
      return <li key={phase.name} className={active ? "active" : complete ? "complete" : ""}>
        <button type="button" onClick={() => setSelected(station)}><span>{complete ? "✓" : index + 1}</span><div><strong>{phase.name}</strong><small>{phase.truth}</small></div></button>
      </li>;
    })}</ol>
    <button className="current-station-button" type="button" onClick={() => setSelected(stationData[replay.stage])}><small>Inside this phase</small><strong>{STAGES[replay.stage].name}</strong><span>Inspect C4 station →</span></button>
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
      <World variant={variant} replay={replay} inspectStation={inspectStation} setNearest={setNearest} onAutoInspect={inspectAutomatically} inspectionOpen={Boolean(selected && !automaticInspection)} />
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
