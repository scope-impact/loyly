import * as THREE from "three";
import { OrbitControls } from "three/addons/controls/OrbitControls.js";
import architectureSource from "../../../.pi/architecture.md?raw";
import { E2E_RECORDING } from "./e2e-replay.js";
import "./style.css";

// Three variants of the same playable factory, switchable via ?variant=.
const STAGES = [
  { id: "cut", name: "Pin blueprint", code: "ST–01 · cut", owner: "Gondolin", authority: "Isolation boundary", color: 0x74a5c2, description: "Seal the approved Löyly specification—the vehicle blueprint—and expose it read-only to the line." },
  { id: "harness_calibration", name: "Calibrate tooling", code: "ST–02 · harness calibration", owner: "Pi harness", authority: "Pinned instrument oracle", color: 0xf2b84b, description: "Prove the assembly line and inspection tools before candidate construction can begin." },
  { id: "builder", name: "Build vehicle", code: "ST–03 · builder", owner: "Pi / Codex", authority: "Fresh builder intelligence", color: 0xc76b3c, description: "A fresh builder manufactures the selected language model from the same Löyly blueprint." },
  { id: "instrument", name: "Fit sensors", code: "ST–04 · instrument", owner: "Harness observer", authority: "Diagnostic producer", color: 0x52d6d2, description: "Fit runtime sensors without changing the candidate vehicle's subject digest." },
  { id: "oracle", name: "Lock standard", code: "ST–05 · oracle", owner: "ORC", authority: "Workflow gate", color: 0x8d76b5, description: "Bind the vehicle to approved, digest-pinned inspection standards." },
  { id: "verify", name: "Road test", code: "ST–06 · verify", owner: "Verifier adapter", authority: "Application contract", color: 0xd9d2a7, description: "Boot and probe the vehicle against its independently detected target-stack contract." },
  { id: "repair_verification", name: "Repair bay", code: "ST–07 · repair", owner: "Fresh Pi builder", authority: "Bounded attempt", color: 0xd95555, description: "A fresh builder uses read-only failure evidence for one bounded repair attempt." },
  { id: "graders", name: "Independent inspection", code: "ST–08 · graders", owner: "Independent grader", authority: "Verdict authority", color: 0x75c18b, description: "Inspect Löyly behavior without accepting a quality verdict authored by its builder." },
  { id: "instrument_conformance", name: "Check telemetry", code: "ST–09 · instrument conformance", owner: "Independent grader", authority: "Evidence completeness", color: 0x4d9fa4, description: "Incomplete or sampled required telemetry becomes inconclusive; it cannot silently pass." },
  { id: "record_cycle", name: "Register vehicle", code: "ST–10 · record cycle", owner: "Grain", authority: "Durable observation", color: 0x52d6d2, description: "Grain appends the build and inspection facts to durable vehicle history without advancing ORC." },
];

const TARGET_STACKS = {
  rust: { label: "Rust", model: "Löyly Rally", detail: "compact performance build", color: 0xc76b3c, shape: "coupe" },
  java: { label: "Java", model: "Löyly Touring", detail: "long-running estate model", color: 0x4d9fa4, shape: "wagon" },
  rails: { label: "Ruby on Rails", model: "Löyly Coach", detail: "productive workshop van", color: 0xd95555, shape: "van" },
  node: { label: "Node family", model: "Löyly Modular", detail: "adaptable urban hatchback", color: 0x75c18b, shape: "hatch" },
};

const VARIANTS = {
  A: { label: "A · Assembly line", kicker: "Harness assembly line", title: "Build Löyly in any supported stack", camera: "isometric" },
  B: { label: "B · Inspection hall", kicker: "Independent inspection hall", title: "Every vehicle leaves a trace", camera: "perspective" },
  C: { label: "C · Line control", kicker: "Assembly line control", title: "Intervene at the boundary", camera: "top" },
};

const DIAGRAM_SPECS = [
  { id: "context", heading: "System Context", label: "System Context" },
  { id: "containers", heading: "Container view", label: "Container view" },
  { id: "components", heading: "ORC worker component view", label: "ORC worker components" },
];

const STAGE_TARGETS = {
  context: [
    ["source", "harness"], ["registry", "harness"], ["models", "harness"], ["candidate", "harness"], ["registry", "harness"],
    ["compose", "candidate"], ["models", "candidate"], ["candidate", "harness"], ["candidate", "harness"], ["harness", "pi"],
  ],
  containers: [
    ["git", "cutworker", "cuts"], ["registry", "worker"], ["builder", "llm", "outputs"], ["grader", "outputs"], ["registry", "worker"],
    ["grader", "docker", "subject"], ["builder", "outputs"], ["grader", "subject"], ["grader", "outputs"], ["worker", "events", "projections"],
  ],
  components: [
    ["workflows", "runtime"], ["oracle", "runtime"], ["build", "runtime"], ["evidence", "runtime"], ["oracle", "worker"],
    ["grading", "runtime"], ["build", "worker"], ["grading", "worker"], ["evidence", "grading"], ["grainmodel", "grain"],
  ],
};

// U-shaped automotive line: inbound fabrication runs west-to-east, end-of-line
// inspection returns east-to-west, and the repair bay sits on a visible rework spur.
const ASSEMBLY_ROUTE = [
  { x: -18, z: -6.5, short: "Receiving / blueprint" },
  { x: -13.5, z: -6.5, short: "Tooling setup" },
  { x: -9, z: -6.5, short: "Body shop / build" },
  { x: -4.5, z: -6.5, short: "Paint + sensor fit" },
  { x: 0, z: -6.5, short: "Marriage / standards" },
  { x: 5, z: -6.5, short: "End-line road test" },
  { x: 10.5, z: -2, short: "Rework bay" },
  { x: 10.5, z: 5.5, short: "Independent inspection" },
  { x: 5, z: 5.5, short: "Telemetry + finish" },
  { x: -.5, z: 5.5, short: "Finished vehicle yard" },
];

const $ = (selector) => document.querySelector(selector);
const refs = {
  status: $("#factory-status"), statusLight: $("#status-light"), score: $("#score"),
  cycleName: $("#cycle-name"), cycleId: $("#cycle-id"), start: $("#start-cycle"),
  pause: $("#pause-cycle"), step: $("#step-cycle"), speed: $("#speed"), speedLabel: $("#speed-label"),
  stageFocus: $("#stage-focus"), stageRail: $("#stage-rail"), progress: $("#pipeline-progress"),
  events: $("#events-view"), evidence: $("#evidence-view"), state: $("#state-view"), decision: $("#decision"),
  repair: $("#repair-cycle"), abort: $("#abort-cycle"), variantLabel: $("#variant-label"),
  viewKicker: $("#view-kicker"), viewTitle: $("#view-title"), stationCard: $("#station-card"), stack: $("#stack-select"),
  replaySeek: $("#replay-seek"), replayClock: $("#replay-clock"), replaySource: $("#replay-source"),
};

refs.stack.innerHTML = Object.entries(TARGET_STACKS).map(([id, stack]) => `<option value="${id}">${stack.label} · ${stack.model}</option>`).join("");

let eventLog = [];
let stageElapsed = 0;
let lastFrame = performance.now();
let activeVariant = "A";
let selectedNode = null;
let focusedNode = null;
let cameraFlight = null;
let vehicleWaypoints = [];
let lastVehicleStage = -1;
let state = project(eventLog);
let replay = { active: false, playing: false, cursor: 0, elapsed: 0, lastTick: performance.now() };

function nowStamp() {
  return new Date().toLocaleTimeString([], { hour12: false, hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

function append(type, payload = {}) {
  eventLog.push({ sequence: eventLog.length + 1, type, at: nowStamp(), ...payload });
  state = project(eventLog);
  renderState();
}

function project(events) {
  const next = {
    cycleId: null, status: "ready", activeStage: -1, completed: [], failed: [],
    paused: false, awaitingDecision: false, attempt: 0, score: 0, evidence: [], speed: 1, targetStack: refs.stack?.value ?? "rust",
  };
  for (const event of events) {
    switch (event.type) {
      case "CycleStarted":
        Object.assign(next, { cycleId: event.cycleId, targetStack: event.targetStack, status: "running", activeStage: 0, attempt: 1, completed: [], failed: [], score: 0, evidence: [], awaitingDecision: false, paused: false });
        break;
      case "StageEntered": next.activeStage = event.stageIndex; break;
      case "StageCompleted":
        next.activeStage = event.stageIndex;
        if (!next.completed.includes(event.stageIndex)) next.completed.push(event.stageIndex);
        next.score = Math.min(100, next.score + event.points);
        next.evidence.push(event.evidence);
        break;
      case "VerificationFailed":
        next.failed.push(event.stageIndex); next.awaitingDecision = true; next.paused = true; next.status = "decision";
        next.evidence.push({ verdict: "fail", name: "Application contract", digest: event.digest, authority: "verifier_adapter" });
        break;
      case "RepairAuthorized":
        next.awaitingDecision = false; next.paused = false; next.status = "running"; next.attempt = 2; next.activeStage = 6;
        break;
      case "CyclePaused": next.paused = true; break;
      case "CycleResumed": next.paused = false; break;
      case "SpeedChanged": next.speed = event.speed; break;
      case "IndependentVerdict":
        next.activeStage = 7;
        if (event.itemId === "01-011" && !next.completed.includes(7)) next.completed.push(7);
        if (event.verdict === "pass") next.score = Math.min(100, next.score + 4);
        next.evidence.push({ verdict: event.verdict, name: `Invariant ${event.itemId}`, digest: event.digest ?? "sealed fixture", authority: "independent_gondolin", detail: event.label });
        break;
      case "RuntimeSignalObserved":
        next.activeStage = 8;
        next.evidence.push({ verdict: "pass", name: event.label, digest: event.digest, authority: "runtime_signal", detail: event.operationId });
        break;
      case "CycleRecorded": next.activeStage = 9; next.completed.push(9); break;
      case "CycleFailed": next.status = "failed"; next.paused = true; next.activeStage = 9; next.score = 82; break;
      case "CycleAborted": next.status = "failed"; next.paused = true; next.awaitingDecision = false; break;
      case "CyclePassed": next.status = "passed"; next.paused = true; next.activeStage = 9; next.score = 100; break;
    }
  }
  return next;
}

function startCycle() {
  const cycleId = `RY-${String(Math.floor(1000 + Math.random() * 8999))}`;
  eventLog = [];
  stageElapsed = 0;
  append("CycleStarted", { cycleId, targetStack: refs.stack.value, product: "Löyly" });
  append("StageEntered", { stageIndex: 0, stage: STAGES[0].id });
}

function resetReplay() {
  eventLog = [];
  replay = { active: true, playing: true, cursor: 0, elapsed: 0, lastTick: performance.now() };
  refs.stack.value = E2E_RECORDING.targetStack;
  buildVehicle(E2E_RECORDING.targetStack);
  refs.replaySeek.value = 0;
  emitReplayUntil(0);
  renderState();
}

function replayEvent(record) {
  const stage = STAGES[record.stageIndex];
  const common = { ...record, at: `+${(record.offset / 1000).toFixed(1)}s`, stage: stage?.id, evidence: record.type === "StageCompleted" ? { verdict: "pass", name: record.label, digest: record.digest ?? "sealed fixture", authority: "recorded_e2e" } : undefined };
  if (record.type === "CycleStarted") append("CycleStarted", { ...common, cycleId: E2E_RECORDING.cycleId, targetStack: E2E_RECORDING.targetStack, product: "Löyly" });
  else append(record.type, common);
}

function emitReplayUntil(targetMs) {
  while (replay.cursor < E2E_RECORDING.timeline.length && E2E_RECORDING.timeline[replay.cursor].offset <= targetMs) {
    replayEvent(E2E_RECORDING.timeline[replay.cursor]); replay.cursor += 1;
  }
  const duration = E2E_RECORDING.timeline.at(-1).offset;
  replay.elapsed = Math.min(targetMs, duration);
  refs.replaySeek.value = replay.elapsed;
  refs.replayClock.textContent = `${(replay.elapsed / 1000).toFixed(1)} / ${(duration / 1000).toFixed(1)}s`;
  if (replay.cursor >= E2E_RECORDING.timeline.length) replay.playing = false;
}

function seekReplay(targetMs) {
  eventLog = [];
  replay.cursor = 0;
  replay.elapsed = 0;
  emitReplayUntil(targetMs);
  replay.lastTick = performance.now();
  renderState();
}

function completeActiveStage() {
  const index = state.activeStage;
  if (index < 0 || state.status !== "running") return;
  if (index === 5 && state.attempt === 1) {
    stageElapsed = 0;
    append("VerificationFailed", { stageIndex: index, stage: STAGES[index].id, digest: shortDigest(index, "fault") });
    return;
  }
  const stage = STAGES[index];
  const verdict = index === 8 ? "pass · complete collection" : "pass";
  append("StageCompleted", {
    stageIndex: index, stage: stage.id, points: index === 9 ? 19 : 9,
    evidence: { verdict, name: `${stage.name} evidence`, digest: shortDigest(index, state.cycleId), authority: stage.owner.toLowerCase().replaceAll(" ", "_") },
  });
  stageElapsed = 0;
  if (index === STAGES.length - 1) {
    append("CyclePassed", { measurement: "10/10", subjectDigest: shortDigest(99, state.cycleId) });
  } else {
    const nextIndex = index + 1;
    append("StageEntered", { stageIndex: nextIndex, stage: STAGES[nextIndex].id });
  }
}

function shortDigest(seed, salt) {
  let value = 2166136261 ^ seed;
  for (const char of String(salt)) value = Math.imul(value ^ char.charCodeAt(0), 16777619);
  return `sha256:${(value >>> 0).toString(16).padStart(8, "0")}`;
}

function renderState() {
  const stage = STAGES[state.activeStage];
  refs.status.textContent = state.status === "ready" ? "Ready for a cut" : state.status === "decision" ? "Operator decision needed" : state.status === "passed" ? "Cycle independently passed" : state.status === "failed" && replay.active ? "Recorded result · 9 passed / 2 failed" : state.status === "failed" ? "Cycle stopped" : state.paused ? "Cycle paused" : `${stage?.name ?? "Factory"} in progress`;
  refs.statusLight.className = `status-light ${state.status === "decision" ? "failed" : state.status}`;
  refs.score.textContent = state.score;
  refs.cycleName.textContent = state.cycleId ? "Booking core regeneration" : "No cycle loaded";
  refs.cycleId.textContent = state.cycleId ?? "—";
  refs.start.querySelector("span").textContent = replay.active ? (replay.playing ? "Replay from start" : "Replay recording") : "Replay E2E result";
  refs.pause.disabled = !state.cycleId || ["passed", "failed", "decision"].includes(state.status);
  refs.step.disabled = !state.cycleId || ["passed", "failed", "decision"].includes(state.status);
  refs.pause.textContent = replay.active ? (replay.playing ? "Pause" : "Resume") : (state.paused ? "Resume" : "Pause");
  refs.stack.disabled = Boolean(state.cycleId) && !["passed", "failed"].includes(state.status);
  const stack = TARGET_STACKS[state.targetStack] ?? TARGET_STACKS.rust;
  refs.cycleName.textContent = state.cycleId ? stack.model : "No cycle loaded";
  $("#model-name").textContent = stack.model;
  $("#model-detail").textContent = `${stack.label} · ${stack.detail}`;
  $("#model-swatch").style.background = `#${stack.color.toString(16).padStart(6, "0")}`;
  refs.decision.hidden = !state.awaitingDecision;
  const terminalStages = new Set([...state.completed, ...state.failed]).size;
  refs.progress.textContent = `${terminalStages} / ${STAGES.length} stages`;

  if (stage && state.cycleId) {
    refs.stageFocus.innerHTML = `<span class="stage-number">${String(state.activeStage + 1).padStart(2, "0")}</span><div><small>${stage.code} · attempt ${state.attempt}</small><strong>${stage.name}</strong><p>${stage.description}</p></div>`;
  } else {
    refs.stageFocus.innerHTML = `<span class="stage-number">00</span><div><small>Waiting stage</small><strong>${state.status === "failed" ? "Cycle aborted" : "Choose start cycle"}</strong><p>The yard stays inert until an operator approves a run.</p></div>`;
  }

  refs.stageRail.innerHTML = STAGES.map((item, index) => {
    const className = state.failed.includes(index) ? "failed" : state.completed.includes(index) ? "complete" : state.activeStage === index && state.cycleId ? "active" : "";
    return `<li class="${className}" title="${item.description}">${item.name}</li>`;
  }).join("");

  refs.events.innerHTML = eventLog.length ? [...eventLog].reverse().map((event, index) => `<div class="event-row ${index === 0 ? "new" : ""}"><time>${event.at}</time><strong>${event.type}<br><small>${event.label ?? event.stage ?? event.cycleId ?? event.measurement ?? "projection updated"}</small></strong></div>`).join("") : `<div class="event-row"><time>—</time><strong>No recorded signal yet<br><small>Replay the sealed E2E result to energize the line.</small></strong></div>`;
  refs.evidence.innerHTML = state.evidence.length ? [...state.evidence].reverse().map((item) => `<div class="evidence-item"><span>${item.verdict.includes("pass") ? "✓" : "×"}</span>${item.name}<small>${item.digest} · ${item.authority}</small></div>`).join("") : `<div class="evidence-item">No evidence captured.<small>Candidate claims cannot determine an independent verdict.</small></div>`;
  refs.state.textContent = JSON.stringify({
    projection: state,
    productOrder: { product: "Löyly", vehicleModel: stack.model, targetStack: stack.label, contract: "model/loyly" },
    architecture: activeDiagram ? {
      source: ".pi/architecture.md",
      diagram: activeDiagram.label,
      sourceLine: activeDiagram.sourceLine,
      elements: activeDiagram.nodes.map(({ id, name, type }) => ({ id, name, type })),
      relations: activeDiagram.edges,
      highlightedElementIds: activeTargetIds(),
    } : null,
    replay: replay.active ? { source: E2E_RECORDING.source, historical: true, elapsedMs: replay.elapsed, summary: E2E_RECORDING.summary, binding: E2E_RECORDING.binding } : null,
    eventCount: eventLog.length,
    activeEvent: eventLog.at(-1) ?? null,
  }, null, 2);
  updateSceneFromState();
  refreshStationInspector();
}

refs.start.addEventListener("click", resetReplay);
refs.pause.addEventListener("click", () => {
  if (replay.active) { replay.playing = !replay.playing; replay.lastTick = performance.now(); renderState(); }
  else append(state.paused ? "CycleResumed" : "CyclePaused");
});
refs.step.addEventListener("click", () => {
  if (replay.active) {
    replay.playing = false;
    const next = E2E_RECORDING.timeline[replay.cursor];
    if (next) seekReplay(next.offset);
  } else completeActiveStage();
});
refs.speed.addEventListener("input", () => { refs.speedLabel.textContent = `${refs.speed.value}×`; append("SpeedChanged", { speed: Number(refs.speed.value) }); });
refs.stack.addEventListener("change", () => { buildVehicle(refs.stack.value); state = project(eventLog); renderState(); });
refs.repair.addEventListener("click", () => { append("RepairAuthorized", { failedStage: "verify", attempt: 2 }); append("StageEntered", { stageIndex: 6, stage: STAGES[6].id }); });
refs.abort.addEventListener("click", () => append("CycleAborted", { reason: "operator_refused_repair" }));
refs.replaySeek.max = E2E_RECORDING.timeline.at(-1).offset;
refs.replaySource.textContent = E2E_RECORDING.cycleId;
refs.replaySource.title = E2E_RECORDING.source;
refs.replaySeek.addEventListener("input", () => { replay.active = true; replay.playing = false; seekReplay(Number(refs.replaySeek.value)); });

document.querySelectorAll(".evidence-tabs button").forEach((button) => button.addEventListener("click", () => {
  document.querySelectorAll(".evidence-tabs button").forEach((item) => item.setAttribute("aria-selected", String(item === button)));
  ["events", "evidence", "state"].forEach((name) => { $(`#${name}-view`).hidden = name !== button.dataset.tab; });
}));

// Live C4 world ---------------------------------------------------------------
function extractMermaid(spec) {
  const marker = `## ${spec.heading}`;
  const sectionStart = architectureSource.indexOf(marker);
  const fenceStart = architectureSource.indexOf("```mermaid", sectionStart);
  const contentStart = architectureSource.indexOf("\n", fenceStart) + 1;
  const fenceEnd = architectureSource.indexOf("```", contentStart);
  const sourceLine = architectureSource.slice(0, sectionStart).split("\n").length;
  return { ...spec, sourceLine, source: architectureSource.slice(contentStart, fenceEnd) };
}

function parseMermaid(spec) {
  const nodes = new Map();
  const edges = [];
  const groups = [];
  for (const rawLine of spec.source.split("\n")) {
    const line = rawLine.trim();
    const subgraph = line.match(/^subgraph\s+(\w+)(?:\["([^"]+)"\])?/);
    if (subgraph) { groups.push({ id: subgraph[1], label: subgraph[2] ?? subgraph[1] }); continue; }
    if (line === "end") { groups.pop(); continue; }
    const nodeMatch = line.match(/^(\w+)\s*(?:\[\("([\s\S]*?)"\)\]|\["([\s\S]*?)"\])/);
    if (nodeMatch) {
      const text = (nodeMatch[2] ?? nodeMatch[3]).replaceAll("<br/>", "\n").replaceAll("&amp;", "&");
      const parts = text.split("\n").map((part) => part.trim()).filter(Boolean);
      const typeIndex = parts.findIndex((part) => part.startsWith("[") && part.endsWith("]"));
      const type = typeIndex >= 0 ? parts[typeIndex].slice(1, -1) : "Architecture element";
      const description = parts.filter((_, index) => index !== 0 && index !== typeIndex).join(" ");
      nodes.set(nodeMatch[1], { id: nodeMatch[1], name: parts[0], type, description, group: groups.at(-1)?.id ?? null, groupLabel: groups.at(-1)?.label ?? null });
    }
    const edgeMatch = line.match(/^(\w+)\s*-->\s*(?:\|"([^"]*)"\|\s*)?(\w+)/);
    if (edgeMatch) edges.push({ from: edgeMatch[1], to: edgeMatch[3], label: edgeMatch[2] ?? "relates to" });
  }
  return { ...spec, nodes: [...nodes.values()], edges: edges.filter((edge) => nodes.has(edge.from) && nodes.has(edge.to)) };
}

const diagrams = Object.fromEntries(DIAGRAM_SPECS.map((spec) => {
  const parsed = parseMermaid(extractMermaid(spec));
  return [parsed.id, parsed];
}));
let activeDiagram = diagrams.containers;

const diagramSelect = $("#diagram-select");
diagramSelect.innerHTML = Object.values(diagrams).map((diagram) => `<option value="${diagram.id}">${diagram.label}</option>`).join("");
diagramSelect.value = activeDiagram.id;

const canvas = $("#factory-canvas");
const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: false });
renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
renderer.shadowMap.enabled = true;
renderer.shadowMap.type = THREE.PCFSoftShadowMap;
renderer.outputColorSpace = THREE.SRGBColorSpace;
renderer.setClearColor(0x0b1623);

const scene = new THREE.Scene();
scene.fog = new THREE.FogExp2(0x0b1623, 0.018);
const camera = new THREE.PerspectiveCamera(42, 1, 0.1, 160);
const controls = new OrbitControls(camera, canvas);
controls.enableDamping = true; controls.dampingFactor = 0.065; controls.maxPolarAngle = Math.PI * 0.48; controls.minDistance = 10; controls.maxDistance = 58;
scene.add(new THREE.HemisphereLight(0x90b6ca, 0x1d2630, 2.35));
const keyLight = new THREE.DirectionalLight(0xffd8a4, 3.5);
keyLight.position.set(-12, 24, 10); keyLight.castShadow = true; keyLight.shadow.mapSize.set(2048, 2048); scene.add(keyLight);
const cyanLight = new THREE.PointLight(0x52d6d2, 42, 30, 2); cyanLight.position.set(9, 7, -6); scene.add(cyanLight);

const worldGroup = new THREE.Group(); scene.add(worldGroup);
let nodeMeshes = [];
let edgeMeshes = [];
let nodePositions = new Map();

const productVehicle = new THREE.Group();
productVehicle.userData.product = true;
productVehicle.scale.setScalar(1.1); scene.add(productVehicle);

function buildVehicle(stackId) {
  const stack = TARGET_STACKS[stackId] ?? TARGET_STACKS.rust;
  while (productVehicle.children.length) {
    const child = productVehicle.children.pop(); child.traverse((object) => { object.geometry?.dispose(); object.material?.dispose?.(); });
  }
  const paint = new THREE.MeshStandardMaterial({ color: stack.color, roughness: .32, metalness: .62 });
  const glass = new THREE.MeshStandardMaterial({ color: 0x9ac1c9, roughness: .15, metalness: .25, transparent: true, opacity: .78 });
  const tire = new THREE.MeshStandardMaterial({ color: 0x0b0e12, roughness: .88 });
  const specs = {
    coupe: { length: 2.75, bodyH: .42, cabin: [1.25, .43, 1.04], cabinX: -.12 },
    wagon: { length: 3.15, bodyH: .5, cabin: [2.05, .67, 1.08], cabinX: -.2 },
    van: { length: 3.0, bodyH: .62, cabin: [2.2, 1.14, 1.12], cabinX: -.15 },
    hatch: { length: 2.55, bodyH: .5, cabin: [1.55, .73, 1.08], cabinX: -.18 },
  }[stack.shape];
  const chassis = new THREE.Mesh(new THREE.BoxGeometry(specs.length, specs.bodyH, 1.35), paint); chassis.position.y = .58; chassis.castShadow = true; productVehicle.add(chassis);
  const cabin = new THREE.Mesh(new THREE.BoxGeometry(...specs.cabin), glass); cabin.position.set(specs.cabinX, .58 + specs.bodyH / 2 + specs.cabin[1] / 2, 0); cabin.castShadow = true; productVehicle.add(cabin);
  const bumper = new THREE.Mesh(new THREE.BoxGeometry(.18, .2, 1.18), new THREE.MeshStandardMaterial({ color: 0xd9d2a7, metalness: .85, roughness: .2 })); bumper.position.set(specs.length / 2 + .07, .5, 0); productVehicle.add(bumper);
  [-1, 1].forEach((xSide) => [-1, 1].forEach((zSide) => {
    const wheel = new THREE.Mesh(new THREE.CylinderGeometry(.32, .32, .2, 18), tire); wheel.rotation.x = Math.PI / 2; wheel.position.set(xSide * specs.length * .32, .34, zSide * .72); wheel.castShadow = true; productVehicle.add(wheel);
  }));
  if (stack.shape === "coupe") { chassis.rotation.z = -.025; }
  if (stack.shape === "van") {
    const sideDoor = new THREE.Mesh(new THREE.BoxGeometry(.04, .72, .76), new THREE.MeshStandardMaterial({ color: 0xf1f0e8, roughness: .5 })); sideDoor.position.set(.15, 1.2, -.69); productVehicle.add(sideDoor);
  }
  if (stack.shape === "hatch") {
    [-.52, 0, .52].forEach((x) => { const module = new THREE.Mesh(new THREE.BoxGeometry(.38, .18, .82), paint); module.position.set(x, 1.53, 0); productVehicle.add(module); });
  }
  const productLabel = makeTextSprite([stack.model, `${stack.label} target stack`], `#${stack.color.toString(16).padStart(6, "0")}`, .66); productLabel.position.set(0, 2.35, 0); productVehicle.add(productLabel);
}
buildVehicle("rust");
const agents = [0x52d6d2, 0xc76b3c, 0xf1f0e8].map((color) => {
  const agent = new THREE.Group();
  const torso = new THREE.Mesh(new THREE.BoxGeometry(.48, .53, .36), new THREE.MeshStandardMaterial({ color, roughness: .4, metalness: .5 })); torso.position.y = .43; torso.castShadow = true; agent.add(torso);
  const head = new THREE.Mesh(new THREE.SphereGeometry(.2, 12, 8), new THREE.MeshStandardMaterial({ color: 0x172332, emissive: color, emissiveIntensity: .45 })); head.position.y = .86; agent.add(head);
  scene.add(agent); return agent;
});

function layoutDiagram(diagram) {
  const positions = new Map();
  const factoryMaps = {
    context: {
      source: [-18, -6.5], registry: [-13.5, -10.5], models: [-9, -10.5], harness: [-1, 0], gondolin: [1, -11], compose: [5, -10.5], candidate: [10.5, 5.5], operator: [-17, 10], pi: [-11.5, 10],
    },
    containers: {
      cutworker: [-18, -6.5], cuts: [-16.5, -2.5], git: [-20, -11], registry: [-13.5, -10.5], builder: [-9, -6.5], llm: [-9, -11], outputs: [-4.5, -2.5], worker: [0, -2], grader: [7.5, 1.5], docker: [5, -10.5], subject: [10.5, 5.5], events: [-.5, 9.5], projections: [4, 9.5], gondolin: [0, -11], extension: [-11.5, 10], pi: [-17, 10],
    },
    components: {
      rpc: [-17, 10], selection: [-18, -6.5], worker: [0, -2], workflows: [0, 1.5], build: [-9, -6.5], oracle: [0, -6.5], grading: [10.5, 5.5], evidence: [5, 5.5], runtime: [0, -11], grainmodel: [-.5, 5.5], orc: [-6, 10], grain: [4, 10],
    },
  };
  const fixed = factoryMaps[diagram.id] ?? {};
  diagram.nodes.forEach((node, index) => {
    const fallback = [-18 + (index % 8) * 5, index % 2 ? -11 : 10];
    const pos = fixed[node.id] ?? fallback;
    positions.set(node.id, new THREE.Vector3(pos[0], 0, pos[1]));
  });
  return positions;
}

function c4Color(node) {
  if (/data store/i.test(node.type)) return 0xf2b84b;
  if (/external/i.test(node.type)) return 0x60778a;
  if (/component/i.test(node.type)) return 0xc76b3c;
  if (/person/i.test(node.type)) return 0xd9d2a7;
  return node.group === "system" || /software system/i.test(node.type) ? 0x52d6d2 : 0x74a5c2;
}

function makeTextSprite(lines, accent = "#f1f0e8", scale = 1) {
  const labelCanvas = document.createElement("canvas"); labelCanvas.width = 512; labelCanvas.height = 160;
  const ctx = labelCanvas.getContext("2d"); ctx.clearRect(0, 0, 512, 160); ctx.textAlign = "center";
  ctx.fillStyle = "rgba(8,17,28,.88)"; ctx.fillRect(6, 9, 500, 142);
  ctx.strokeStyle = accent; ctx.lineWidth = 4; ctx.strokeRect(6, 9, 500, 142);
  ctx.fillStyle = "#f1f0e8"; ctx.font = "600 34px sans-serif"; ctx.fillText(lines[0].slice(0, 30), 256, 67);
  ctx.fillStyle = accent; ctx.font = "500 22px sans-serif"; ctx.fillText(lines[1].slice(0, 42), 256, 111);
  const texture = new THREE.CanvasTexture(labelCanvas); texture.colorSpace = THREE.SRGBColorSpace;
  const sprite = new THREE.Sprite(new THREE.SpriteMaterial({ map: texture, transparent: true, depthTest: false }));
  sprite.scale.set(5.1 * scale, 1.6 * scale, 1); return sprite;
}

function addRelationship(edge) {
  const from = nodePositions.get(edge.from); const to = nodePositions.get(edge.to);
  if (!from || !to) return;
  const middle = from.clone().lerp(to, .5); middle.y = 7 + Math.min(2.2, from.distanceTo(to) * .05);
  const curve = new THREE.QuadraticBezierCurve3(from.clone().setY(3.6), middle, to.clone().setY(3.6));
  const material = new THREE.MeshStandardMaterial({ color: 0x3d6071, emissive: 0x183442, emissiveIntensity: .5, roughness: .45, metalness: .5 });
  const route = new THREE.Mesh(new THREE.TubeGeometry(curve, 18, .075, 6, false), material);
  route.userData.edge = edge; route.userData.baseColor = 0x3d6071; worldGroup.add(route); edgeMeshes.push(route);
}

function addC4Node(node) {
  const color = c4Color(node); const height = 2.4 + (node.description.length % 5) * .34;
  const group = new THREE.Group(); group.position.copy(nodePositions.get(node.id)); group.userData.node = node;
  const material = new THREE.MeshStandardMaterial({ color, roughness: .5, metalness: .42, emissive: color, emissiveIntensity: .09 });
  const base = new THREE.Mesh(new THREE.BoxGeometry(3.4, .34, 2.7), new THREE.MeshStandardMaterial({ color: 0x213347, roughness: .8 })); base.position.y = .17; base.receiveShadow = true; group.add(base);
  const body = /data store/i.test(node.type)
    ? new THREE.Mesh(new THREE.CylinderGeometry(1.15, 1.15, height, 24), material)
    : new THREE.Mesh(new THREE.BoxGeometry(2.45, height, 1.8), material);
  body.position.y = .34 + height / 2; body.castShadow = true; group.add(body);
  const cap = new THREE.Mesh(new THREE.BoxGeometry(2.15, .1, 1.48), new THREE.MeshStandardMaterial({ color, emissive: color, emissiveIntensity: 2 })); cap.position.y = height + .38; group.add(cap);
  const label = makeTextSprite([node.name, node.type], `#${new THREE.Color(color).getHexString()}`, .58); label.position.set(0, height + 1.25, 0); group.add(label);
  worldGroup.add(group); nodeMeshes.push(group);
}

function addFactoryBeam(from, to, color = 0xc76b3c) {
  const midpoint = from.clone().add(to).multiplyScalar(.5);
  const length = from.distanceTo(to);
  const beam = new THREE.Mesh(new THREE.BoxGeometry(length, .16, .66), new THREE.MeshStandardMaterial({ color, roughness: .58, metalness: .48 }));
  beam.position.copy(midpoint); beam.position.y = .18; beam.rotation.y = -Math.atan2(to.z - from.z, to.x - from.x); beam.receiveShadow = true; worldGroup.add(beam);
  const direction = to.clone().sub(from).normalize();
  const arrow = new THREE.Mesh(new THREE.ConeGeometry(.21, .65, 3), new THREE.MeshStandardMaterial({ color: 0xf2b84b, emissive: 0x6a4510, emissiveIntensity: .7 }));
  arrow.position.copy(midpoint).setY(.38); arrow.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), direction); worldGroup.add(arrow);
}

function addAssemblyLine() {
  const points = ASSEMBLY_ROUTE.map(({ x, z }) => new THREE.Vector3(x, 0, z));
  [[0,1],[1,2],[2,3],[3,4],[4,5],[5,7],[7,8],[8,9]].forEach(([from, to]) => addFactoryBeam(points[from], points[to]));
  addFactoryBeam(points[5], points[6], 0xd95555);
  addFactoryBeam(points[6].clone().add(new THREE.Vector3(.38, 0, -.38)), points[5].clone().add(new THREE.Vector3(.38, 0, -.38)), 0xd95555);
  ASSEMBLY_ROUTE.forEach((station, index) => {
    const stage = STAGES[index];
    const padColor = index === 6 ? 0x542b34 : index >= 7 ? 0x24463f : 0x263b4c;
    const pad = new THREE.Mesh(new THREE.BoxGeometry(4, .12, 3.1), new THREE.MeshStandardMaterial({ color: padColor, roughness: .82, metalness: .1 }));
    pad.position.set(station.x, .02, station.z); pad.receiveShadow = true; worldGroup.add(pad);
    const marker = makeTextSprite([`${String(index + 1).padStart(2, "0")}  ${station.short}`, stage.id.replaceAll("_", " ")], index === 6 ? "#d95555" : index >= 7 ? "#75c18b" : "#f2b84b", .46);
    marker.position.set(station.x, .62, station.z + 1.72); worldGroup.add(marker);
  });
  // Service lanes keep infrastructure and inputs visually separate from product flow.
  [-11.8, 10.8].forEach((z) => {
    const lane = new THREE.Mesh(new THREE.BoxGeometry(45, .035, .28), new THREE.MeshStandardMaterial({ color: 0x60778a, emissive: 0x24384a, emissiveIntensity: .45 }));
    lane.position.set(0, .02, z); worldGroup.add(lane);
  });
  addFactoryFixtures();
}

function addFactoryFixtures() {
  const steel = new THREE.MeshStandardMaterial({ color: 0x6b7d89, roughness: .52, metalness: .65 });
  const safety = new THREE.MeshStandardMaterial({ color: 0xf2b84b, roughness: .5, metalness: .4 });
  const addGantry = (stageIndex, color = 0x6b7d89) => {
    const { x, z } = ASSEMBLY_ROUTE[stageIndex]; const material = new THREE.MeshStandardMaterial({ color, roughness: .48, metalness: .65 });
    [-1.55, 1.55].forEach((offset) => { const post = new THREE.Mesh(new THREE.BoxGeometry(.16, 2.8, .16), material); post.position.set(x, 1.4, z + offset); worldGroup.add(post); });
    const top = new THREE.Mesh(new THREE.BoxGeometry(.18, .18, 3.25), material); top.position.set(x, 2.75, z); worldGroup.add(top);
  };
  // Body shop: paired robot arms around the vehicle path.
  [-1.45, 1.45].forEach((zOffset) => [-.85, .85].forEach((xOffset) => {
    const { x, z } = ASSEMBLY_ROUTE[2];
    const base = new THREE.Mesh(new THREE.CylinderGeometry(.2, .28, .42, 10), safety); base.position.set(x + xOffset, .22, z + zOffset); worldGroup.add(base);
    const arm = new THREE.Mesh(new THREE.BoxGeometry(.2, 1.2, .2), steel); arm.position.set(x + xOffset, .93, z + zOffset); arm.rotation.z = xOffset > 0 ? .42 : -.42; worldGroup.add(arm);
  }));
  // Enclosed paint/instrument tunnel.
  const paint = ASSEMBLY_ROUTE[3];
  const tunnel = new THREE.Mesh(new THREE.BoxGeometry(4, 2.6, 2.8), new THREE.MeshStandardMaterial({ color: 0x52d6d2, transparent: true, opacity: .1, wireframe: true })); tunnel.position.set(paint.x, 1.35, paint.z); worldGroup.add(tunnel);
  addGantry(4, 0x8d76b5); // marriage / standards gate
  // End-of-line rollers and inspection/rework arches.
  const test = ASSEMBLY_ROUTE[5];
  [-.8, 0, .8].forEach((xOffset) => { const roller = new THREE.Mesh(new THREE.CylinderGeometry(.16, .16, 2.4, 12), steel); roller.rotation.x = Math.PI / 2; roller.position.set(test.x + xOffset, .24, test.z); worldGroup.add(roller); });
  addGantry(6, 0xd95555); addGantry(7, 0x75c18b);
  // Finished-vehicle yard markings.
  const yard = ASSEMBLY_ROUTE[9];
  [-1.2, 0, 1.2].forEach((offset) => { const stripe = new THREE.Mesh(new THREE.BoxGeometry(.08, .025, 2.6), new THREE.MeshStandardMaterial({ color: 0xf1f0e8 })); stripe.position.set(yard.x + offset, .11, yard.z); worldGroup.add(stripe); });
}

function buildDiagram(diagramId) {
  activeDiagram = diagrams[diagramId] ?? diagrams.containers;
  refs.stationCard.hidden = true;
  refs.stationCard.classList.remove("focused");
  document.body.classList.remove("station-focus");
  selectedNode = null; focusedNode = null; cameraFlight = null;
  controls.enabled = true;
  while (worldGroup.children.length) {
    const child = worldGroup.children.pop(); child.traverse((object) => { object.geometry?.dispose(); if (Array.isArray(object.material)) object.material.forEach((material) => material.dispose()); else object.material?.dispose(); });
  }
  nodeMeshes = []; edgeMeshes = []; nodePositions = layoutDiagram(activeDiagram);
  const floor = new THREE.Mesh(new THREE.BoxGeometry(48, .4, 29), new THREE.MeshStandardMaterial({ color: 0x172332, roughness: .9, metalness: .12 })); floor.position.y = -.25; floor.receiveShadow = true; worldGroup.add(floor);
  const grid = new THREE.GridHelper(48, 48, 0x496179, 0x293b4e); grid.position.y = -.04; worldGroup.add(grid);
  addAssemblyLine();
  activeDiagram.edges.forEach(addRelationship);
  activeDiagram.nodes.forEach(addC4Node);
  if (!state.cycleId) productVehicle.position.set(-22, .08, -6.5);
  $("#diagram-count").textContent = `${activeDiagram.nodes.length} elements · ${activeDiagram.edges.length} relations`;
  $("#diagram-source").textContent = `Source: .pi/architecture.md:${activeDiagram.sourceLine}`;
  updateSceneFromState();
}

diagramSelect.addEventListener("change", () => { buildDiagram(diagramSelect.value); renderState(); setCamera(VARIANTS[activeVariant].camera); });

const raycaster = new THREE.Raycaster();
const pointer = new THREE.Vector2();
function pickWorldObject(event) {
  const rect = canvas.getBoundingClientRect(); pointer.x = ((event.clientX - rect.left) / rect.width) * 2 - 1; pointer.y = -((event.clientY - rect.top) / rect.height) * 2 + 1;
  raycaster.setFromCamera(pointer, camera);
  const hit = raycaster.intersectObjects([...nodeMeshes, ...edgeMeshes, productVehicle], true)[0];
  if (!hit) return null;
  let target = hit.object;
  while (target.parent && !target.userData.node && !target.userData.edge && !target.userData.product) target = target.parent;
  return target;
}

canvas.addEventListener("click", (event) => {
  const target = pickWorldObject(event);
  if (!target) return;
  if (target.userData.node) showArchitectureElement(target.userData.node);
  if (target.userData.edge) showRelationship(target.userData.edge);
  if (target.userData.product) showProductVehicle();
});

canvas.addEventListener("dblclick", (event) => {
  const target = pickWorldObject(event);
  if (target?.userData.node) { showArchitectureElement(target.userData.node); focusOnStation(target.userData.node); }
});

function showArchitectureElement(node) {
  selectedNode = node;
  $("#station-code").textContent = node.id; $("#station-name").textContent = node.name;
  $("#station-description").textContent = node.description || "This element is named in the repository C4 source without an additional description.";
  $("#station-owner").textContent = node.type; $("#station-authority").textContent = activeDiagram.label; refs.stationCard.hidden = false;
  $("#station-live").hidden = false; $("#focus-station").hidden = false;
  $("#overview-station").hidden = focusedNode?.id !== node.id;
  refreshStationInspector();
}

function showRelationship(edge) {
  selectedNode = null;
  $("#station-code").textContent = `${edge.from} → ${edge.to}`; $("#station-name").textContent = edge.label;
  $("#station-description").textContent = "Relationship loaded from the selected Mermaid C4 view in .pi/architecture.md.";
  $("#station-owner").textContent = "C4 relationship"; $("#station-authority").textContent = activeDiagram.label; refs.stationCard.hidden = false;
  $("#station-live").hidden = true; $("#focus-station").hidden = true; $("#overview-station").hidden = !focusedNode;
}

function showProductVehicle() {
  selectedNode = null;
  const stack = TARGET_STACKS[state.targetStack] ?? TARGET_STACKS.rust;
  $("#station-code").textContent = "Löyly product"; $("#station-name").textContent = stack.model;
  $("#station-description").textContent = `The generated Löyly application, manufactured as the ${stack.label} target-stack model from the same pinned product specification.`;
  $("#station-owner").textContent = "Candidate application"; $("#station-authority").textContent = "Vehicle moving through the harness"; refs.stationCard.hidden = false;
  $("#station-live").hidden = true; $("#focus-station").hidden = true; $("#overview-station").hidden = !focusedNode;
}

function refreshStationInspector() {
  if (!selectedNode || refs.stationCard.hidden) return;
  const stack = TARGET_STACKS[state.targetStack] ?? TARGET_STACKS.rust;
  const stageIndexes = (STAGE_TARGETS[activeDiagram.id] ?? []).reduce((matches, ids, index) => ids.includes(selectedNode.id) ? [...matches, index] : matches, []);
  const isActive = Boolean(state.cycleId) && activeTargetIds().includes(selectedNode.id) && state.status !== "passed";
  const hasFailed = isActive && state.status === "decision";
  const nextIndex = stageIndexes.find((index) => index > state.activeStage);
  const lastIndex = [...stageIndexes].reverse().find((index) => index < state.activeStage);
  const liveLight = $("#station-live-light"); liveLight.className = hasFailed ? "failed" : isActive ? "active" : "";
  if (hasFailed) {
    $("#station-live-status").textContent = `Fault found on ${stack.model}`;
    $("#station-live-detail").textContent = `${STAGES[state.activeStage].name} stopped here. The line is waiting for an operator repair decision.`;
  } else if (isActive) {
    $("#station-live-status").textContent = `Processing ${stack.model}`;
    $("#station-live-detail").textContent = `${STAGES[state.activeStage].name} · attempt ${state.attempt}. Grain event ${eventLog.at(-1)?.type ?? "pending"} is the latest observed fact.`;
  } else if (!state.cycleId) {
    $("#station-live-status").textContent = "Standing by";
    $("#station-live-detail").textContent = stageIndexes.length ? `This station participates in ${stageIndexes.map((index) => STAGES[index].name).join(", ")}. Start a product order to see live work.` : "This supporting station is available to the harness but is not assigned a modeled pipeline stage.";
  } else if (nextIndex !== undefined) {
    $("#station-live-status").textContent = `Queued for ${STAGES[nextIndex].name}`;
    $("#station-live-detail").textContent = `${stack.model} is currently at ${STAGES[state.activeStage]?.name ?? "the loading bay"}.`;
  } else if (lastIndex !== undefined || state.status === "passed") {
    $("#station-live-status").textContent = "Station work complete";
    $("#station-live-detail").textContent = `${stack.model} has cleared this station for the current cycle.`;
  } else {
    $("#station-live-status").textContent = "Supporting the line";
    $("#station-live-detail").textContent = "This C4 element has no direct vehicle operation in the current stage mapping.";
  }
  const connections = activeDiagram.edges.filter((edge) => edge.from === selectedNode.id || edge.to === selectedNode.id);
  $("#station-connections").innerHTML = connections.slice(0, 5).map((edge) => {
    const inbound = edge.to === selectedNode.id; const otherId = inbound ? edge.from : edge.to;
    const other = activeDiagram.nodes.find((node) => node.id === otherId);
    return `<li><span>${inbound ? "Incoming" : "Outgoing"}</span> · ${edge.label} · ${other?.name ?? otherId}</li>`;
  }).join("") || "<li>No relationships in this view.</li>";
}

function focusOnStation(node) {
  const station = nodeMeshes.find((mesh) => mesh.userData.node.id === node.id);
  if (!station) return;
  focusedNode = node; selectedNode = node;
  const target = station.position.clone().add(new THREE.Vector3(0, 1.6, 0));
  const approach = new THREE.Vector3(5.3, 4.6, 6.2);
  cameraFlight = { position: target.clone().add(approach), target };
  controls.enabled = false;
  refs.stationCard.classList.add("focused");
  document.body.classList.add("station-focus");
  $("#focus-station").hidden = true;
  $("#overview-station").hidden = false;
}

function returnToOverview() {
  focusedNode = null; cameraFlight = null; controls.enabled = true;
  refs.stationCard.classList.remove("focused");
  document.body.classList.remove("station-focus");
  $("#focus-station").hidden = !selectedNode;
  $("#overview-station").hidden = true;
  setCamera(VARIANTS[activeVariant].camera);
}

$("#focus-station").addEventListener("click", () => { if (selectedNode) focusOnStation(selectedNode); });
$("#overview-station").addEventListener("click", returnToOverview);
$("#close-station").addEventListener("click", () => { if (focusedNode) returnToOverview(); refs.stationCard.hidden = true; selectedNode = null; });

function activeTargetIds() {
  if (!state.cycleId) return [];
  return STAGE_TARGETS[activeDiagram.id]?.[Math.max(0, state.activeStage)] ?? [];
}

function updateSceneFromState() {
  if (!activeDiagram) return;
  const targets = new Set(activeTargetIds());
  productVehicle.visible = true;
  nodeMeshes.forEach((group) => {
    const active = targets.has(group.userData.node.id);
    group.scale.setScalar(active ? 1.1 : 1);
    group.children.forEach((child) => { if (child.material?.emissive) child.material.emissiveIntensity = active ? 1.25 : .09; });
  });
  edgeMeshes.forEach((route) => {
    const active = targets.has(route.userData.edge.from) && targets.has(route.userData.edge.to);
    route.material.color.setHex(active ? 0xf2b84b : route.userData.baseColor); route.material.emissiveIntensity = active ? 2 : .5;
  });
  const routeStage = ASSEMBLY_ROUTE[Math.max(0, state.activeStage)] ?? ASSEMBLY_ROUTE[0];
  const target = state.cycleId ? new THREE.Vector3(routeStage.x, 0, routeStage.z) : new THREE.Vector3(-22, 0, -6.5);
  agents.forEach((agent, index) => { agent.userData.target = target.clone().add(new THREE.Vector3((index - 1) * .72, 0, 1.75 + Math.abs(index - 1) * .2)); });
}

function setCamera(mode) {
  if (mode === "top") { camera.position.set(0, 46, .01); controls.target.set(-3, 0, 0); }
  else if (mode === "perspective") { camera.position.set(27, 16, 24); controls.target.set(-3, 1, 0); }
  else { camera.position.set(23, 22, 26); controls.target.set(-3, 1, 0); }
  controls.update();
}
$("#camera-reset").addEventListener("click", () => { if (focusedNode) returnToOverview(); else setCamera(VARIANTS[activeVariant].camera); });

function resize() {
  const rect = canvas.getBoundingClientRect(); if (!rect.width || !rect.height) return;
  renderer.setSize(rect.width, rect.height, false); camera.aspect = rect.width / rect.height; camera.updateProjectionMatrix();
}
new ResizeObserver(resize).observe(canvas);

function frame(now) {
  const delta = Math.min((now - lastFrame) / 1000, .1); lastFrame = now;
  if (replay.active && replay.playing) {
    const replayDelta = Math.min(now - replay.lastTick, 100);
    replay.lastTick = now;
    emitReplayUntil(replay.elapsed + replayDelta * state.speed);
  } else if (!replay.active && state.status === "running" && !state.paused) {
    stageElapsed += delta * state.speed; const duration = state.activeStage === 2 ? 4.3 : 2.8; if (stageElapsed >= duration) completeActiveStage();
  }
  if (productVehicle.visible) {
    if (!state.cycleId) { vehicleWaypoints = []; lastVehicleStage = -1; }
    if (state.cycleId && lastVehicleStage !== state.activeStage) {
      const stagePoint = (index) => new THREE.Vector3(ASSEMBLY_ROUTE[index].x, 0, ASSEMBLY_ROUTE[index].z);
      vehicleWaypoints = lastVehicleStage === 6 && state.activeStage === 7 ? [stagePoint(5), stagePoint(7)] : [stagePoint(Math.max(0, state.activeStage))];
      lastVehicleStage = state.activeStage;
    }
    const destination = state.cycleId ? (vehicleWaypoints[0] ?? new THREE.Vector3(ASSEMBLY_ROUTE[Math.max(0, state.activeStage)].x, 0, ASSEMBLY_ROUTE[Math.max(0, state.activeStage)].z)) : new THREE.Vector3(-22, 0, -6.5);
    productVehicle.position.lerp(new THREE.Vector3(destination.x, .08 + Math.sin(now * .003) * .025, destination.z), .045 * Math.max(1, state.speed));
    if (state.cycleId && productVehicle.position.distanceTo(new THREE.Vector3(destination.x, .08, destination.z)) < .45) vehicleWaypoints.shift();
  }
  agents.forEach((agent, index) => { if (agent.userData.target) agent.position.lerp(agent.userData.target, .018 + index * .003); agent.rotation.y = Math.sin(now * .001 + index) * .16; });
  if (cameraFlight) {
    camera.position.lerp(cameraFlight.position, .09);
    controls.target.lerp(cameraFlight.target, .09);
    if (camera.position.distanceTo(cameraFlight.position) < .06 && controls.target.distanceTo(cameraFlight.target) < .04) {
      camera.position.copy(cameraFlight.position); controls.target.copy(cameraFlight.target); cameraFlight = null; controls.enabled = true;
    }
  }
  controls.update(); renderer.render(scene, camera); requestAnimationFrame(frame);
}

// Shareable prototype variants ------------------------------------------------
function setVariant(key, updateUrl = true) {
  activeVariant = VARIANTS[key] ? key : "A";
  const variant = VARIANTS[activeVariant];
  document.body.dataset.variant = activeVariant;
  refs.variantLabel.textContent = variant.label; refs.viewKicker.textContent = variant.kicker; refs.viewTitle.textContent = variant.title;
  if (updateUrl) { const url = new URL(location.href); url.searchParams.set("variant", activeVariant); history.replaceState({}, "", url); }
  requestAnimationFrame(() => { resize(); setCamera(variant.camera); });
}

function cycleVariant(direction) {
  const keys = Object.keys(VARIANTS); const index = keys.indexOf(activeVariant);
  setVariant(keys[(index + direction + keys.length) % keys.length]);
}
$("#previous-variant").addEventListener("click", () => cycleVariant(-1));
$("#next-variant").addEventListener("click", () => cycleVariant(1));
document.addEventListener("keydown", (event) => {
  const editing = event.target.matches("input, textarea, [contenteditable]");
  if (!editing && event.key === "ArrowLeft") cycleVariant(-1);
  if (!editing && event.key === "ArrowRight") cycleVariant(1);
  if (!editing && event.code === "Space") {
    event.preventDefault();
    if (!replay.active || replay.cursor >= E2E_RECORDING.timeline.length) resetReplay();
    else { replay.playing = !replay.playing; replay.lastTick = performance.now(); renderState(); }
  }
});

buildDiagram("containers");
setVariant(new URLSearchParams(location.search).get("variant") ?? "A", false);
renderState();
requestAnimationFrame(frame);
