import * as THREE from "three";
import { OrbitControls } from "three/addons/controls/OrbitControls.js";
import architectureSource from "../../../.pi/architecture.md?raw";
import { E2E_RECORDING } from "./e2e-replay.js";
import "./style.css";

// Three variants of the same playable factory, switchable via ?variant=.
const STAGES = [
  { id: "cut", glyph: "▱", name: "Pin product model", code: "ST–01 · cut", owner: "Gondolin", authority: "Isolation boundary", color: 0x74a5c2, description: "Seal the approved Löyly product specification and expose it read-only to the line." },
  { id: "harness_calibration", glyph: "◎", name: "Calibrate tooling", code: "ST–02 · harness calibration", owner: "Pi harness", authority: "Pinned instrument oracle", color: 0xf2b84b, description: "Prove the assembly line and inspection tools before candidate construction can begin." },
  { id: "builder", glyph: "⚙", name: "Assemble components", code: "ST–03 · builder", owner: "Pi / Codex", authority: "Fresh builder intelligence", color: 0xc76b3c, description: "A fresh builder emits the first Löyly C4 containers in the selected implementation stack." },
  { id: "instrument", glyph: "⌁", name: "Wire runtime signals", code: "ST–04 · instrument", owner: "Harness observer", authority: "Diagnostic producer", color: 0x52d6d2, description: "Connect domain-event and projection telemetry without changing the candidate subject digest." },
  { id: "oracle", glyph: "⊣", name: "Bind product contract", code: "ST–05 · oracle", owner: "ORC", authority: "Workflow gate", color: 0x8d76b5, description: "Bind the assembled components to approved, digest-pinned inspection standards." },
  { id: "verify", glyph: "⏻", name: "Boot system", code: "ST–06 · verify", owner: "Verifier adapter", authority: "Application contract", color: 0xd9d2a7, description: "Boot and probe the assembled Löyly system against its detected target-stack contract." },
  { id: "repair_verification", glyph: "⚒", name: "Repair bay", code: "ST–07 · repair", owner: "Fresh Pi builder", authority: "Bounded attempt", color: 0xd95555, description: "A fresh builder uses read-only failure evidence for one bounded repair attempt." },
  { id: "graders", glyph: "⌕", name: "Independent inspection", code: "ST–08 · graders", owner: "Independent grader", authority: "Verdict authority", color: 0x75c18b, description: "Inspect Löyly behavior without accepting a quality verdict authored by its builder." },
  { id: "instrument_conformance", glyph: "≋", name: "Check telemetry", code: "ST–09 · instrument conformance", owner: "Independent grader", authority: "Evidence completeness", color: 0x4d9fa4, description: "Incomplete or sampled required telemetry becomes inconclusive; it cannot silently pass." },
  { id: "record_cycle", glyph: "✓", name: "Register product", code: "ST–10 · record cycle", owner: "Grain", authority: "Durable observation", color: 0x52d6d2, description: "Grain appends the build and inspection facts to durable product history without advancing ORC." },
];

const TARGET_STACKS = {
  rust: { label: "Rust", model: "Löyly / Rust", detail: "native service containers", color: 0xc76b3c },
  java: { label: "Java", model: "Löyly / Java", detail: "JVM service containers", color: 0x4d9fa4 },
  rails: { label: "Ruby on Rails", model: "Löyly / Rails", detail: "Rails application containers", color: 0xd95555 },
  node: { label: "Node family", model: "Löyly / Node", detail: "Node service containers", color: 0x75c18b },
};

const PRODUCT_COMPONENTS = [
  { id: "api", glyph: "⇥", name: "Booking API", type: "Container", stage: 2, x: -1.05, z: -.6, color: 0x74a5c2 },
  { id: "booking", glyph: "⚙", name: "Booking core", type: "Component", stage: 2, x: 0, z: -.6, color: 0xc76b3c },
  { id: "events", glyph: "▤", name: "Event store", type: "Data store", stage: 3, x: 1.05, z: -.6, color: 0xf2b84b },
  { id: "weekly", glyph: "▦", name: "Weekly schedule", type: "Projection", stage: 3, x: -1.05, z: .68, color: 0x52d6d2 },
  { id: "heater", glyph: "♨", name: "Heater schedule", type: "Projection", stage: 4, x: 0, z: .68, color: 0x8d76b5 },
  { id: "billing", glyph: "€", name: "Billing signals", type: "Projection", stage: 4, x: 1.05, z: .68, color: 0x75c18b },
];

const VARIANTS = {
  A: { label: "A · Assembly line", kicker: "Harness assembly line", title: "Assemble Löyly from C4 components", camera: "isometric" },
  B: { label: "B · Inspection hall", kicker: "Independent inspection hall", title: "Every component leaves a trace", camera: "perspective" },
  C: { label: "C · Line control", kicker: "Assembly line control", title: "Track every product container", camera: "top" },
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
  { x: -.5, z: 5.5, short: "Accepted product yard" },
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
  componentStrip: $("#component-strip"), focusProduct: $("#focus-product"), missionCopy: $("#mission-copy"), missionPanel: $(".mission-panel"),
};

refs.stack.innerHTML = Object.entries(TARGET_STACKS).map(([id, stack]) => `<option value="${id}">${stack.label} · ${stack.model}</option>`).join("");

let eventLog = [];
let stageElapsed = 0;
let lastFrame = performance.now();
let activeVariant = "A";
let selectedNode = null;
let focusedNode = null;
let cameraFlight = null;
let followProduct = false;
let productWaypoints = [];
let lastProductStage = -1;
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
  buildProductAssembly(E2E_RECORDING.targetStack);
  refs.replaySeek.value = 0;
  emitReplayUntil(0);
  renderState();
  requestAnimationFrame(() => { refs.missionPanel.scrollTop = 0; });
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
  refs.status.textContent = state.status === "ready" ? "Ready for a cut" : replay.active && !replay.playing && replay.cursor < E2E_RECORDING.timeline.length ? "Replay paused" : state.status === "decision" && replay.active ? "Recorded fault · repair follows" : state.status === "decision" ? "Operator decision needed" : state.status === "passed" ? "Cycle independently passed" : state.status === "failed" && replay.active ? "Recorded result · 9 passed / 2 failed" : state.status === "failed" ? "Cycle stopped" : state.paused ? "Cycle paused" : `${stage?.name ?? "Factory"} in progress`;
  refs.statusLight.className = `status-light ${state.status === "decision" ? "failed" : state.status}`;
  refs.score.textContent = state.score;
  refs.cycleName.textContent = state.cycleId ? "Booking core regeneration" : "No cycle loaded";
  refs.cycleId.textContent = state.cycleId ? `…${state.cycleId.slice(-6)}` : "—";
  refs.cycleId.title = state.cycleId ?? "No cycle loaded";
  refs.start.querySelector("span").textContent = replay.active ? "Restart" : "Replay run";
  refs.pause.disabled = replay.active ? replay.cursor >= E2E_RECORDING.timeline.length : !state.cycleId || ["passed", "failed", "decision"].includes(state.status);
  refs.step.disabled = replay.active ? replay.cursor >= E2E_RECORDING.timeline.length : !state.cycleId || ["passed", "failed", "decision"].includes(state.status);
  refs.pause.textContent = replay.active ? (replay.playing ? "Pause" : "Resume") : (state.paused ? "Resume" : "Pause");
  refs.stack.disabled = Boolean(state.cycleId) && !["passed", "failed"].includes(state.status);
  refs.focusProduct.disabled = !state.cycleId;
  refs.missionCopy.hidden = Boolean(state.cycleId);
  const stack = TARGET_STACKS[state.targetStack] ?? TARGET_STACKS.rust;
  refs.cycleName.textContent = state.cycleId ? stack.model : "No cycle loaded";
  $("#model-name").textContent = stack.model;
  $("#model-detail").textContent = `${stack.label} · ${stack.detail}`;
  $("#model-swatch").style.background = `#${stack.color.toString(16).padStart(6, "0")}`;
  refs.componentStrip.innerHTML = PRODUCT_COMPONENTS.map((component) => {
    const assembled = Boolean(state.cycleId) && (state.activeStage >= component.stage || state.completed.includes(component.stage));
    const active = Boolean(state.cycleId) && state.activeStage === component.stage;
    return `<li class="${assembled ? "assembled" : ""} ${active ? "active" : ""}" title="${component.name} · ${component.type}"><span>${component.glyph}</span>${component.name.replace(" schedule", "").replace(" signals", "")}</li>`;
  }).join("");
  refs.decision.hidden = !state.awaitingDecision || replay.active;
  const terminalStages = new Set([...state.completed, ...state.failed]).size;
  refs.progress.textContent = `${terminalStages} / ${STAGES.length} stages`;

  if (stage && state.cycleId) {
    refs.stageFocus.innerHTML = `<span class="stage-number">${String(state.activeStage + 1).padStart(2, "0")}</span><div><small>${stage.code} · attempt ${state.attempt}</small><strong>${stage.name}</strong><p>${stage.description}</p></div>`;
  } else {
    refs.stageFocus.innerHTML = `<span class="stage-number">00</span><div><small>Waiting stage</small><strong>${state.status === "failed" ? "Cycle aborted" : "Choose start cycle"}</strong><p>The yard stays inert until an operator approves a run.</p></div>`;
  }

  refs.stageRail.innerHTML = STAGES.map((item, index) => {
    const className = state.failed.includes(index) ? "failed" : state.completed.includes(index) ? "complete" : state.activeStage === index && state.cycleId ? "active" : "";
    return `<li class="${className}" data-icon="${item.glyph}" title="${item.description}">${item.name}</li>`;
  }).join("");

  refs.events.innerHTML = eventLog.length ? [...eventLog].reverse().map((event, index) => `<div class="event-row ${index === 0 ? "new" : ""}"><time>${event.at}</time><strong>${event.type}<br><small>${event.label ?? event.stage ?? event.cycleId ?? event.measurement ?? "projection updated"}</small></strong></div>`).join("") : `<div class="event-row"><time>—</time><strong>No recorded signal yet<br><small>Replay the sealed E2E result to energize the line.</small></strong></div>`;
  refs.evidence.innerHTML = state.evidence.length ? [...state.evidence].reverse().map((item) => `<div class="evidence-item"><span>${item.verdict.includes("pass") ? "✓" : "×"}</span>${item.name}<small>${item.digest} · ${item.authority}</small></div>`).join("") : `<div class="evidence-item">No evidence captured.<small>Candidate claims cannot determine an independent verdict.</small></div>`;
  refs.state.textContent = JSON.stringify({
    projection: state,
    productOrder: { product: "Löyly", systemBoundary: stack.model, targetStack: stack.label, containers: PRODUCT_COMPONENTS.map(({ id, name, type, stage }) => ({ id, name, type, emergesAtStage: stage })), contract: "model/loyly" },
    architecture: activeDiagram ? {
      source: ".pi/architecture.md",
      diagram: activeDiagram.label,
      sourceLine: activeDiagram.sourceLine,
      elements: activeDiagram.nodes.map(({ id, name, type }) => ({ id, name, type })),
      relations: activeDiagram.edges,
      highlightedElementIds: activeTargetIds(),
    } : null,
    replay: replay.active ? { source: E2E_RECORDING.source, historical: true, elapsedMs: replay.elapsed, summary: E2E_RECORDING.summary, binding: E2E_RECORDING.binding } : null,
    view: { variant: activeVariant, diagram: activeDiagram?.id, followProduct, focusedStation: focusedNode?.id ?? null },
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
refs.stack.addEventListener("change", () => { buildProductAssembly(refs.stack.value); state = project(eventLog); renderState(); });
refs.repair.addEventListener("click", () => { append("RepairAuthorized", { failedStage: "verify", attempt: 2 }); append("StageEntered", { stageIndex: 6, stage: STAGES[6].id }); });
refs.abort.addEventListener("click", () => append("CycleAborted", { reason: "operator_refused_repair" }));
refs.replaySeek.max = E2E_RECORDING.timeline.at(-1).offset;
refs.replaySource.textContent = `2 attempts · ${E2E_RECORDING.timeline.length} recorded events`;
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
let stationFixtures = [];
const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");

const productAssembly = new THREE.Group();
productAssembly.userData.product = true;
productAssembly.scale.setScalar(1.9); scene.add(productAssembly);
let productModules = [];

function buildComponentIcon(component, material, stackColor) {
  const icon = new THREE.Group();
  const accent = new THREE.MeshStandardMaterial({ color: stackColor, emissive: stackColor, emissiveIntensity: .8, roughness: .32, metalness: .55 });
  const pale = new THREE.MeshStandardMaterial({ color: 0xe7efe8, emissive: component.color, emissiveIntensity: .18, roughness: .42, metalness: .3 });
  const add = (geometry, iconMaterial = material, position = [0, 0, 0], rotation = [0, 0, 0]) => {
    const mesh = new THREE.Mesh(geometry, iconMaterial); mesh.position.set(...position); mesh.rotation.set(...rotation); mesh.castShadow = true; icon.add(mesh); return mesh;
  };
  if (component.id === "api") {
    add(new THREE.BoxGeometry(.14, .55, .16), material, [-.28, .3, 0]);
    add(new THREE.BoxGeometry(.14, .55, .16), material, [.28, .3, 0]);
    add(new THREE.BoxGeometry(.7, .14, .16), material, [0, .61, 0]);
    add(new THREE.CylinderGeometry(.035, .035, .36, 10), accent, [-.04, .33, .11], [0, 0, -Math.PI / 2]);
    add(new THREE.ConeGeometry(.1, .2, 4), accent, [.2, .33, .11], [0, 0, -Math.PI / 2]);
  } else if (component.id === "booking") {
    add(new THREE.TorusGeometry(.27, .075, 10, 24), material, [0, .38, 0]);
    for (let index = 0; index < 8; index += 1) {
      const angle = index * Math.PI / 4;
      add(new THREE.BoxGeometry(.12, .16, .15), material, [Math.cos(angle) * .34, .38 + Math.sin(angle) * .34, 0], [0, 0, angle]);
    }
    add(new THREE.OctahedronGeometry(.13), accent, [0, .38, .02]);
  } else if (component.id === "events") {
    [0, 1, 2].forEach((level) => add(new THREE.CylinderGeometry(.34, .34, .16, 22), level === 2 ? accent : material, [0, .14 + level * .2, 0]));
    add(new THREE.TorusGeometry(.27, .025, 6, 22), pale, [0, .55, 0], [Math.PI / 2, 0, 0]);
  } else if (component.id === "weekly") {
    add(new THREE.BoxGeometry(.7, .56, .12), material, [0, .34, 0]);
    add(new THREE.BoxGeometry(.7, .12, .15), accent, [0, .61, 0]);
    [-.22, 0, .22].forEach((x) => add(new THREE.BoxGeometry(.025, .34, .025), pale, [x, .31, .075]));
    [.22, .38].forEach((y) => add(new THREE.BoxGeometry(.58, .025, .025), pale, [0, y, .075]));
  } else if (component.id === "heater") {
    add(new THREE.BoxGeometry(.72, .6, .12), new THREE.MeshStandardMaterial({ color: component.color, wireframe: true, transparent: true, opacity: .6 }), [0, .35, 0]);
    const points = Array.from({ length: 13 }, (_, index) => new THREE.Vector3(-.31 + index * .052, .34 + Math.sin(index * Math.PI / 2) * .14, .08));
    const coil = new THREE.Mesh(new THREE.TubeGeometry(new THREE.CatmullRomCurve3(points), 32, .035, 8, false), accent); coil.castShadow = true; icon.add(coil);
    add(new THREE.CylinderGeometry(.06, .06, .48, 10), material, [-.4, .35, 0]);
    add(new THREE.CylinderGeometry(.06, .06, .48, 10), material, [.4, .35, 0]);
  } else {
    add(new THREE.BoxGeometry(.48, .62, .08), pale, [-.09, .37, 0]);
    [.22, .36, .5].forEach((y, index) => add(new THREE.BoxGeometry(index === 2 ? .22 : .32, .025, .025), material, [-.1, y, .055]));
    [0, 1, 2].forEach((level) => add(new THREE.CylinderGeometry(.15, .15, .07, 18), level === 2 ? accent : material, [.27, .1 + level * .075, .08]));
  }
  return icon;
}

function buildProductAssembly(stackId) {
  const stack = TARGET_STACKS[stackId] ?? TARGET_STACKS.rust;
  while (productAssembly.children.length) {
    const child = productAssembly.children.pop(); child.traverse((object) => { object.geometry?.dispose(); object.material?.dispose?.(); });
  }
  productModules = [];
  const carrier = new THREE.Mesh(new THREE.BoxGeometry(3.9, .22, 2.7), new THREE.MeshStandardMaterial({ color: 0x24384a, roughness: .55, metalness: .72 }));
  carrier.position.y = .18; carrier.castShadow = true; productAssembly.add(carrier);
  [-1.7, 1.7].forEach((x) => { const latch = new THREE.Mesh(new THREE.BoxGeometry(.14, .2, 2.85), new THREE.MeshStandardMaterial({ color: stack.color, emissive: stack.color, emissiveIntensity: .55 })); latch.position.set(x, .36, 0); productAssembly.add(latch); });
  const boundary = new THREE.Mesh(new THREE.BoxGeometry(3.55, 1.38, 2.25), new THREE.MeshStandardMaterial({ color: stack.color, transparent: true, opacity: .12, wireframe: true }));
  boundary.position.y = 1.03; productAssembly.add(boundary);
  PRODUCT_COMPONENTS.forEach((component, index) => {
    const module = new THREE.Group();
    module.userData.component = component; module.userData.targetY = .78; module.userData.revealed = false;
    module.position.set(component.x, 4.2 + index * .18, component.z); module.scale.setScalar(.001);
    const bodyMaterial = new THREE.MeshStandardMaterial({ color: component.color, roughness: .42, metalness: .48, emissive: component.color, emissiveIntensity: .2 });
    const icon = buildComponentIcon(component, bodyMaterial, stack.color); module.add(icon); module.userData.icon = icon;
    const stackBand = new THREE.Mesh(new THREE.BoxGeometry(.78, .06, .7), new THREE.MeshStandardMaterial({ color: stack.color, emissive: stack.color, emissiveIntensity: 1.2 }));
    stackBand.position.y = .03; module.add(stackBand);
    const label = makeTextSprite([component.name, component.type], `#${new THREE.Color(component.color).getHexString()}`, .23); label.position.set(0, 1.08, 0); module.add(label);
    productAssembly.add(module); productModules.push(module);
  });
  const productLabel = makeTextSprite(["Löyly system boundary", `${stack.label} · 6 product modules`], `#${stack.color.toString(16).padStart(6, "0")}`, .6); productLabel.position.set(0, 2.35, 0); productAssembly.add(productLabel);
}
buildProductAssembly("rust");
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
    const marker = makeTextSprite([`${stage.glyph}  ${String(index + 1).padStart(2, "0")}  ${station.short}`, stage.id.replaceAll("_", " ")], index === 6 ? "#d95555" : index >= 7 ? "#75c18b" : "#f2b84b", .46);
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
  stationFixtures = [];
  const steel = new THREE.MeshStandardMaterial({ color: 0x6b7d89, roughness: .52, metalness: .65 });
  const safety = new THREE.MeshStandardMaterial({ color: 0xf2b84b, roughness: .5, metalness: .4 });
  const stationMaterial = (stageIndex, intensity = .45) => {
    const color = STAGES[stageIndex].color;
    const material = new THREE.MeshStandardMaterial({ color, emissive: color, emissiveIntensity: intensity, roughness: .42, metalness: .52 });
    material.userData.stationAccent = true;
    return material;
  };
  const fixtureGroup = (stageIndex, zOffset = -1) => {
    const { x, z } = ASSEMBLY_ROUTE[stageIndex];
    const group = new THREE.Group();
    group.position.set(x, 0, z + zOffset);
    group.userData.station = STAGES[stageIndex].id; group.userData.stageIndex = stageIndex; group.userData.parts = {};
    stationFixtures[stageIndex] = group;
    worldGroup.add(group);
    return group;
  };
  const addFixtureMesh = (group, geometry, material, position, rotation = [0, 0, 0], role = null) => {
    const mesh = new THREE.Mesh(geometry, material);
    mesh.position.set(...position); mesh.rotation.set(...rotation); mesh.castShadow = true; group.add(mesh);
    if (role) {
      mesh.userData.basePosition = mesh.position.clone(); mesh.userData.baseRotation = mesh.rotation.clone(); mesh.userData.baseScale = mesh.scale.clone();
      (group.userData.parts[role] ??= []).push(mesh);
    }
    return mesh;
  };
  const addGantry = (stageIndex, color = 0x6b7d89) => {
    const { x, z } = ASSEMBLY_ROUTE[stageIndex]; const material = new THREE.MeshStandardMaterial({ color, roughness: .48, metalness: .65 });
    [-1.55, 1.55].forEach((offset) => { const post = new THREE.Mesh(new THREE.BoxGeometry(.16, 2.8, .16), material); post.position.set(x, 1.4, z + offset); worldGroup.add(post); });
    const top = new THREE.Mesh(new THREE.BoxGeometry(.18, .18, 3.25), material); top.position.set(x, 2.75, z); worldGroup.add(top);
  };
  // Receiving: a physical drafting table carries the sealed product blueprint.
  const blueprint = fixtureGroup(0);
  addFixtureMesh(blueprint, new THREE.BoxGeometry(1.5, .12, .86), steel, [0, 1.18, 0], [-.16, 0, 0]);
  addFixtureMesh(blueprint, new THREE.BoxGeometry(1.28, .025, .7), stationMaterial(0, .68), [0, 1.26, -.02], [-.16, 0, 0], "blueprint");
  [-.52, .52].forEach((x) => addFixtureMesh(blueprint, new THREE.BoxGeometry(.08, 1.1, .08), steel, [x, .58, 0]));
  [-.35, 0, .35].forEach((x) => addFixtureMesh(blueprint, new THREE.BoxGeometry(.025, .018, .62), safety, [x, 1.29, -.03], [-.16, 0, 0], "blueprint"));

  // Calibration: concentric target rings and crosshairs make tooling alignment visible.
  const calibration = fixtureGroup(1);
  const calibrationMaterial = stationMaterial(1, .72);
  addFixtureMesh(calibration, new THREE.TorusGeometry(.63, .075, 10, 28), calibrationMaterial, [0, 1.63, 0], [0, 0, 0], "target-ring");
  addFixtureMesh(calibration, new THREE.TorusGeometry(.29, .05, 10, 24), calibrationMaterial, [0, 1.63, .01], [0, 0, 0], "target-ring");
  addFixtureMesh(calibration, new THREE.BoxGeometry(1.48, .035, .035), calibrationMaterial, [0, 1.63, .02], [0, 0, 0], "crosshair");
  addFixtureMesh(calibration, new THREE.BoxGeometry(.035, 1.48, .035), calibrationMaterial, [0, 1.63, .02], [0, 0, 0], "crosshair");
  addFixtureMesh(calibration, new THREE.CylinderGeometry(.07, .11, .8, 10), steel, [0, .62, 0]);

  // Component assembly: paired robot arms place software containers onto the carrier.
  [-1.45, 1.45].forEach((zOffset) => [-.85, .85].forEach((xOffset) => {
    const { x, z } = ASSEMBLY_ROUTE[2];
    const base = new THREE.Mesh(new THREE.CylinderGeometry(.2, .28, .42, 10), safety); base.position.set(x + xOffset, .22, z + zOffset); worldGroup.add(base);
    const arm = new THREE.Mesh(new THREE.BoxGeometry(.2, 1.2, .2), steel); arm.position.set(x + xOffset, .93, z + zOffset); arm.rotation.z = xOffset > 0 ? .42 : -.42; worldGroup.add(arm);
  }));
  const assembly = fixtureGroup(2);
  const assemblyMaterial = stationMaterial(2, .6);
  addFixtureMesh(assembly, new THREE.TorusGeometry(.48, .13, 8, 16), assemblyMaterial, [0, 2.18, 0], [0, 0, 0], "gear");
  Array.from({ length: 8 }, (_, index) => index * Math.PI / 4).forEach((angle) => {
    addFixtureMesh(assembly, new THREE.BoxGeometry(.19, .34, .16), assemblyMaterial, [Math.cos(angle) * .7, 2.18 + Math.sin(angle) * .7, 0], [0, 0, angle], "gear");
  });

  // Instrumentation: a translucent wiring cell surrounds a live signal antenna.
  const paint = ASSEMBLY_ROUTE[3];
  const tunnel = new THREE.Mesh(new THREE.BoxGeometry(4, 2.6, 2.8), new THREE.MeshStandardMaterial({ color: 0x52d6d2, transparent: true, opacity: .1, wireframe: true })); tunnel.position.set(paint.x, 1.35, paint.z); worldGroup.add(tunnel);
  const signal = fixtureGroup(3);
  const signalMaterial = stationMaterial(3, .78);
  addFixtureMesh(signal, new THREE.CylinderGeometry(.055, .09, 1.55, 10), signalMaterial, [0, 1.02, 0]);
  [.28, .55, .82].forEach((radius) => addFixtureMesh(signal, new THREE.TorusGeometry(radius, .035, 8, 28), signalMaterial, [0, 1.82, 0], [0, 0, 0], "signal-ring"));
  addFixtureMesh(signal, new THREE.SphereGeometry(.11, 12, 8), signalMaterial, [0, 1.82, .03]);

  // Standards gate: the shackle closes over the pinned contract at marriage.
  addGantry(4, 0x8d76b5);
  const contract = fixtureGroup(4);
  const contractMaterial = stationMaterial(4, .56);
  addFixtureMesh(contract, new THREE.TorusGeometry(.46, .1, 10, 24), contractMaterial, [0, 1.9, 0], [0, 0, 0], "shackle");
  addFixtureMesh(contract, new THREE.BoxGeometry(1.15, .82, .32), contractMaterial, [0, 1.42, .02]);
  addFixtureMesh(contract, new THREE.CylinderGeometry(.08, .13, .32, 10), safety, [0, 1.42, -.19], [Math.PI / 2, 0, 0]);

  // End-of-line rollers and inspection/rework arches.
  const test = ASSEMBLY_ROUTE[5];
  [-.8, 0, .8].forEach((xOffset) => { const roller = new THREE.Mesh(new THREE.CylinderGeometry(.16, .16, 2.4, 12), steel); roller.rotation.x = Math.PI / 2; roller.position.set(test.x + xOffset, .24, test.z); worldGroup.add(roller); });
  const boot = fixtureGroup(5);
  const bootMaterial = stationMaterial(5, .45);
  addFixtureMesh(boot, new THREE.TorusGeometry(.56, .1, 10, 28), bootMaterial, [0, 1.6, 0], [0, 0, 0], "power");
  addFixtureMesh(boot, new THREE.BoxGeometry(.13, .72, .15), bootMaterial, [0, 2.02, .02], [0, 0, 0], "power");

  addGantry(6, 0xd95555);
  const repair = fixtureGroup(6);
  const repairMaterial = stationMaterial(6, .62);
  addFixtureMesh(repair, new THREE.BoxGeometry(.16, 1.48, .16), repairMaterial, [-.14, 1.45, 0], [0, 0, -.65], "repair-a");
  addFixtureMesh(repair, new THREE.BoxGeometry(.16, 1.48, .16), repairMaterial, [.14, 1.45, .03], [0, 0, .65], "repair-b");
  addFixtureMesh(repair, new THREE.BoxGeometry(.52, .15, .16), repairMaterial, [-.61, 1.9, 0], [0, 0, -.65], "repair-a");
  addFixtureMesh(repair, new THREE.CylinderGeometry(.2, .2, .16, 12), repairMaterial, [.62, 1.9, .03], [Math.PI / 2, 0, 0], "repair-b");

  addGantry(7, 0x75c18b);
  const inspection = fixtureGroup(7);
  const inspectionMaterial = stationMaterial(7, .58);
  addFixtureMesh(inspection, new THREE.TorusGeometry(.55, .11, 10, 28), inspectionMaterial, [-.12, 1.75, 0], [0, 0, -.12], "lens");
  addFixtureMesh(inspection, new THREE.BoxGeometry(.16, .92, .16), inspectionMaterial, [.48, 1.12, 0], [0, 0, -.72], "lens");

  // Telemetry finishing: a mast broadcasts the evidence trace before registration.
  const telemetry = fixtureGroup(8);
  const telemetryMaterial = stationMaterial(8, .7);
  addFixtureMesh(telemetry, new THREE.CylinderGeometry(.06, .12, 1.65, 10), telemetryMaterial, [0, 1, 0]);
  addFixtureMesh(telemetry, new THREE.ConeGeometry(.16, .34, 10), telemetryMaterial, [0, 2, 0]);
  [.36, .65].forEach((radius) => addFixtureMesh(telemetry, new THREE.TorusGeometry(radius, .04, 8, 24), telemetryMaterial, [0, 1.84, 0], [0, 0, 0], "telemetry-ring"));

  // Accepted-product yard markings.
  const yard = ASSEMBLY_ROUTE[9];
  [-1.2, 0, 1.2].forEach((offset) => { const stripe = new THREE.Mesh(new THREE.BoxGeometry(.08, .025, 2.6), new THREE.MeshStandardMaterial({ color: 0xf1f0e8 })); stripe.position.set(yard.x + offset, .11, yard.z); worldGroup.add(stripe); });
  const registry = fixtureGroup(9);
  const registryMaterial = stationMaterial(9, .62);
  addFixtureMesh(registry, new THREE.CylinderGeometry(.34, .48, .55, 16), registryMaterial, [0, 1.66, 0], [0, 0, 0], "stamp");
  addFixtureMesh(registry, new THREE.BoxGeometry(.22, .72, .22), registryMaterial, [0, 2.22, 0], [0, 0, 0], "stamp");
  addFixtureMesh(registry, new THREE.TorusGeometry(.64, .08, 10, 28), registryMaterial, [0, 1.25, .01]);
  addFixtureMesh(registry, new THREE.BoxGeometry(.52, .11, .11), safety, [-.14, 1.23, -.08], [0, 0, -.75]);
  addFixtureMesh(registry, new THREE.BoxGeometry(1.0, .11, .11), safety, [.2, 1.38, -.08], [0, 0, .72]);
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
  if (!state.cycleId) productAssembly.position.set(-22, 2.5, -6.5);
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
  const hit = raycaster.intersectObjects([...nodeMeshes, ...edgeMeshes, productAssembly], true)[0];
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
  if (target.userData.product) showProductAssembly();
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

function showProductAssembly() {
  selectedNode = null;
  const stack = TARGET_STACKS[state.targetStack] ?? TARGET_STACKS.rust;
  $("#station-code").textContent = "Löyly product"; $("#station-name").textContent = stack.model;
  $("#station-description").textContent = `The generated Löyly system boundary contains ${PRODUCT_COMPONENTS.length} C4-shaped product modules implemented in ${stack.label}. Components emerge as the recorded build advances.`;
  $("#station-owner").textContent = "Candidate system"; $("#station-authority").textContent = "Product assembly moving through the harness"; refs.stationCard.hidden = false;
  const assembled = PRODUCT_COMPONENTS.filter((component) => Boolean(state.cycleId) && (state.activeStage >= component.stage || state.completed.includes(component.stage))).length;
  $("#station-live").hidden = false; $("#station-live-light").className = assembled ? "active" : "";
  $("#station-live-status").textContent = `${assembled} / ${PRODUCT_COMPONENTS.length} components assembled`;
  $("#station-live-detail").textContent = assembled ? `${stack.label} containers share one Löyly product boundary and behavioral contract.` : "Replay the recorded cycle to emit product containers onto the carrier.";
  $("#station-connections").innerHTML = PRODUCT_COMPONENTS.map((component) => `<li><span>${component.glyph} ${component.type}</span> ${component.name} · ${state.activeStage >= component.stage || state.completed.includes(component.stage) ? "assembled" : `waiting for ${STAGES[component.stage].name}`}</li>`).join("");
  $("#focus-station").hidden = true; $("#overview-station").hidden = !focusedNode;
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
    $("#station-live-status").textContent = `Fault found in ${stack.model}`;
    $("#station-live-detail").textContent = `${STAGES[state.activeStage].name} stopped here. The line is waiting for an operator repair decision.`;
  } else if (isActive) {
    $("#station-live-status").textContent = `Processing ${stack.model}`;
    $("#station-live-detail").textContent = `${STAGES[state.activeStage].name} · attempt ${state.attempt}. Grain event ${eventLog.at(-1)?.type ?? "pending"} is the latest observed fact.`;
  } else if (!state.cycleId) {
    $("#station-live-status").textContent = "Standing by";
    $("#station-live-detail").textContent = stageIndexes.length ? `This station participates in ${stageIndexes.map((index) => STAGES[index].name).join(", ")}. Replay a product order to see live work.` : "This supporting station is available to the harness but is not assigned a modeled pipeline stage.";
  } else if (nextIndex !== undefined) {
    $("#station-live-status").textContent = `Queued for ${STAGES[nextIndex].name}`;
    $("#station-live-detail").textContent = `${stack.model} is currently at ${STAGES[state.activeStage]?.name ?? "the loading bay"}.`;
  } else if (lastIndex !== undefined || state.status === "passed") {
    $("#station-live-status").textContent = "Station work complete";
    $("#station-live-detail").textContent = `${stack.model} has cleared this station for the current cycle.`;
  } else {
    $("#station-live-status").textContent = "Supporting the line";
    $("#station-live-detail").textContent = "This C4 element has no direct product-assembly operation in the current stage mapping.";
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
  productAssembly.visible = true;
  productModules.forEach((module) => {
    const component = module.userData.component;
    module.userData.revealed = Boolean(state.cycleId) && (state.activeStage >= component.stage || state.completed.includes(component.stage));
  });
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

function animateStationFixtures(now) {
  const time = now * .001;
  const rotatePartsAround = (parts, pivotX, pivotY, angle) => parts.forEach((part) => {
    const dx = part.userData.basePosition.x - pivotX; const dy = part.userData.basePosition.y - pivotY;
    part.position.x = pivotX + dx * Math.cos(angle) - dy * Math.sin(angle);
    part.position.y = pivotY + dx * Math.sin(angle) + dy * Math.cos(angle);
    part.rotation.z = part.userData.baseRotation.z + angle;
  });
  stationFixtures.forEach((fixture, stageIndex) => {
    if (!fixture) return;
    const parts = fixture.userData.parts;
    const isCurrent = Boolean(state.cycleId) && state.activeStage === stageIndex && !["passed", "failed"].includes(state.status);
    const isComplete = state.completed.includes(stageIndex);
    const pulse = .82 + Math.sin(time * 5.2) * .28;
    fixture.traverse((object) => {
      if (object.material?.userData.stationAccent) object.material.emissiveIntensity = isCurrent ? pulse : isComplete ? .3 : .08;
    });
    const isPaused = isCurrent && (state.paused || (replay.active && !replay.playing));
    if (isPaused && !reducedMotion.matches) return;
    Object.values(parts).flat().forEach((part) => {
      part.position.copy(part.userData.basePosition); part.rotation.copy(part.userData.baseRotation); part.scale.copy(part.userData.baseScale);
    });
    const isWorking = isCurrent && !state.paused && state.status === "running" && (!replay.active || replay.playing);
    if (!isWorking || reducedMotion.matches) return;
    if (stageIndex === 0) {
      parts.blueprint.forEach((part) => { part.position.y += Math.sin(time * 3.2) * .045; part.rotation.x += Math.sin(time * 2.1) * .035; });
    } else if (stageIndex === 1) {
      parts.crosshair.forEach((part, index) => { part.rotation.z += (index ? -1 : 1) * time * .9; });
      parts["target-ring"].forEach((part, index) => part.scale.setScalar(1 + Math.sin(time * 4 - index) * .055));
    } else if (stageIndex === 2) {
      rotatePartsAround(parts.gear, 0, 2.18, time * 1.8);
    } else if (stageIndex === 3) {
      parts["signal-ring"].forEach((part, index) => part.scale.setScalar(.82 + ((time * 1.35 + index * .27) % 1) * .42));
    } else if (stageIndex === 4) {
      parts.shackle.forEach((part) => { part.position.y -= .18 * (.5 + .5 * Math.sin(time * 3)); });
    } else if (stageIndex === 5) {
      parts.power.forEach((part, index) => part.scale.setScalar(1 + Math.sin(time * 5 + index * .4) * .08));
    } else if (stageIndex === 6) {
      parts["repair-a"].forEach((part) => { part.rotation.z += Math.sin(time * 5) * .16; });
      parts["repair-b"].forEach((part) => { part.rotation.z -= Math.sin(time * 5) * .16; });
    } else if (stageIndex === 7) {
      rotatePartsAround(parts.lens, -.12, 1.75, Math.sin(time * 2.4) * .24);
    } else if (stageIndex === 8) {
      parts["telemetry-ring"].forEach((part, index) => part.scale.setScalar(.86 + ((time * 1.6 + index * .35) % 1) * .32));
    } else if (stageIndex === 9) {
      parts.stamp.forEach((part) => { part.position.y -= Math.pow(Math.abs(Math.sin(time * 2.8)), 7) * .32; });
    }
  });
}

function setCamera(mode) {
  followProduct = false; refs.focusProduct.classList.remove("active"); refs.focusProduct.textContent = "Follow product"; controls.enabled = true;
  if (mode === "top") { camera.position.set(0, 46, .01); controls.target.set(-3, 0, 0); }
  else if (mode === "perspective") { camera.position.set(27, 16, 24); controls.target.set(-3, 1, 0); }
  else { camera.position.set(23, 22, 26); controls.target.set(-3, 1, 0); }
  controls.update();
}
refs.focusProduct.addEventListener("click", () => {
  followProduct = !followProduct; cameraFlight = null; focusedNode = null;
  refs.focusProduct.classList.toggle("active", followProduct);
  refs.focusProduct.textContent = followProduct ? "Stop following" : "Follow product";
  controls.enabled = !followProduct;
  renderState();
});
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
  if (productAssembly.visible) {
    if (!state.cycleId) { productWaypoints = []; lastProductStage = -1; }
    if (state.cycleId && lastProductStage !== state.activeStage) {
      const stagePoint = (index) => new THREE.Vector3(ASSEMBLY_ROUTE[index].x, 0, ASSEMBLY_ROUTE[index].z);
      productWaypoints = lastProductStage === 6 && state.activeStage === 7 ? [stagePoint(5), stagePoint(7)] : [stagePoint(Math.max(0, state.activeStage))];
      lastProductStage = state.activeStage;
    }
    const destination = state.cycleId ? (productWaypoints[0] ?? new THREE.Vector3(ASSEMBLY_ROUTE[Math.max(0, state.activeStage)].x, 0, ASSEMBLY_ROUTE[Math.max(0, state.activeStage)].z)) : new THREE.Vector3(-22, 0, -6.5);
    productAssembly.position.lerp(new THREE.Vector3(destination.x, 2.5 + Math.sin(now * .003) * .035, destination.z), .045 * Math.max(1, state.speed));
    if (state.cycleId && productAssembly.position.distanceTo(new THREE.Vector3(destination.x, 2.5, destination.z)) < .45) productWaypoints.shift();
    productModules.forEach((module, index) => {
      const scale = module.userData.revealed ? 1 : .001;
      const targetY = module.userData.revealed ? module.userData.targetY : 4.2 + index * .18;
      module.scale.lerp(new THREE.Vector3(scale, scale, scale), .11);
      module.position.y += (targetY - module.position.y) * .1;
      module.rotation.y = module.userData.revealed ? Math.sin(now * .0015 + index) * .025 : 0;
    });
  }
  agents.forEach((agent, index) => { if (agent.userData.target) agent.position.lerp(agent.userData.target, .018 + index * .003); agent.rotation.y = Math.sin(now * .001 + index) * .16; });
  animateStationFixtures(now);
  if (followProduct) {
    const target = productAssembly.position.clone().add(new THREE.Vector3(0, 1.3, 0));
    const desired = target.clone().add(new THREE.Vector3(11.5, 8.5, 12.5));
    camera.position.lerp(desired, .055); controls.target.lerp(target, .085);
  } else if (cameraFlight) {
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
  if (!editing && event.key === "Escape") {
    if (!refs.stationCard.hidden) { if (focusedNode) returnToOverview(); refs.stationCard.hidden = true; selectedNode = null; }
    else if (followProduct) setCamera(VARIANTS[activeVariant].camera);
  }
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
