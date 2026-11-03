import * as THREE from "three";
import { OrbitControls } from "three/addons/controls/OrbitControls.js";
import architectureSource from "../../../.pi/architecture.md?raw";
import "./style.css";

// Three variants of the same playable factory, switchable via ?variant=.
const STAGES = [
  { id: "cut", name: "Pin cut", code: "ST–01", owner: "Gondolin", authority: "Isolation boundary", color: 0x74a5c2, description: "Seal the approved specification and expose it read-only to the factory." },
  { id: "harness_calibration", name: "Calibrate", code: "ST–02", owner: "Pi harness", authority: "Pinned instrument oracle", color: 0xf2b84b, description: "Resolve instrument and product oracles before candidate work is allowed to begin." },
  { id: "builder", name: "Build", code: "ST–03", owner: "Pi / Codex", authority: "Fresh builder intelligence", color: 0xc76b3c, description: "A fresh agent session changes only the declared workspace scope." },
  { id: "instrument", name: "Instrument", code: "ST–04", owner: "Harness observer", authority: "Diagnostic producer", color: 0x52d6d2, description: "Attach runtime observation without changing the candidate subject digest." },
  { id: "oracle", name: "Resolve oracle", code: "ST–05", owner: "ORC", authority: "Workflow gate", color: 0x8d76b5, description: "Bind the candidate to approved, digest-pinned evaluation inputs." },
  { id: "verify", name: "Verify", code: "ST–06", owner: "Verifier adapter", authority: "Application contract", color: 0xd9d2a7, description: "Boot and probe the candidate against its independently detected stack contract." },
  { id: "repair_verification", name: "Repair", code: "ST–07", owner: "Fresh Pi builder", authority: "Bounded attempt", color: 0xd95555, description: "Use read-only verification evidence for one bounded repair attempt." },
  { id: "graders", name: "Grade", code: "ST–08", owner: "Independent grader", authority: "Verdict authority", color: 0x75c18b, description: "Measure black-box behavior without accepting verdicts authored by the candidate." },
  { id: "instrument_conformance", name: "Check signals", code: "ST–09", owner: "Independent grader", authority: "Evidence completeness", color: 0x4d9fa4, description: "Incomplete or sampled required signals become inconclusive; they cannot silently pass." },
  { id: "record_cycle", name: "Record", code: "ST–10", owner: "Grain", authority: "Durable observation", color: 0x52d6d2, description: "Append cycle facts and derive reconnectable read models without advancing ORC." },
];

const VARIANTS = {
  A: { label: "A · Factory floor", kicker: "Floor operator", title: "Build software you can prove", camera: "isometric" },
  B: { label: "B · Evidence observatory", kicker: "Evidence observatory", title: "Every claim leaves a trace", camera: "perspective" },
  C: { label: "C · Command deck", kicker: "Agent command deck", title: "Intervene at the boundary", camera: "top" },
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

const $ = (selector) => document.querySelector(selector);
const refs = {
  status: $("#factory-status"), statusLight: $("#status-light"), score: $("#score"),
  cycleName: $("#cycle-name"), cycleId: $("#cycle-id"), start: $("#start-cycle"),
  pause: $("#pause-cycle"), step: $("#step-cycle"), speed: $("#speed"), speedLabel: $("#speed-label"),
  stageFocus: $("#stage-focus"), stageRail: $("#stage-rail"), progress: $("#pipeline-progress"),
  events: $("#events-view"), evidence: $("#evidence-view"), state: $("#state-view"), decision: $("#decision"),
  repair: $("#repair-cycle"), abort: $("#abort-cycle"), variantLabel: $("#variant-label"),
  viewKicker: $("#view-kicker"), viewTitle: $("#view-title"), stationCard: $("#station-card"),
};

let eventLog = [];
let stageElapsed = 0;
let lastFrame = performance.now();
let activeVariant = "A";
let selectedStage = null;
let state = project(eventLog);

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
    paused: false, awaitingDecision: false, attempt: 0, score: 0, evidence: [], speed: 1,
  };
  for (const event of events) {
    switch (event.type) {
      case "CycleStarted":
        Object.assign(next, { cycleId: event.cycleId, status: "running", activeStage: 0, attempt: 1, completed: [], failed: [], score: 0, evidence: [], awaitingDecision: false, paused: false });
        break;
      case "StageEntered": next.activeStage = event.stageIndex; break;
      case "StageCompleted":
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
  append("CycleStarted", { cycleId });
  append("StageEntered", { stageIndex: 0, stage: STAGES[0].id });
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
  refs.status.textContent = state.status === "ready" ? "Ready for a cut" : state.status === "decision" ? "Operator decision needed" : state.status === "passed" ? "Cycle independently passed" : state.status === "failed" ? "Cycle stopped" : state.paused ? "Cycle paused" : `${stage?.name ?? "Factory"} in progress`;
  refs.statusLight.className = `status-light ${state.status === "decision" ? "failed" : state.status}`;
  refs.score.textContent = state.score;
  refs.cycleName.textContent = state.cycleId ? "Booking core regeneration" : "No cycle loaded";
  refs.cycleId.textContent = state.cycleId ?? "—";
  refs.start.querySelector("span").textContent = state.cycleId ? "Restart cycle" : "Start cycle";
  refs.pause.disabled = !state.cycleId || ["passed", "failed", "decision"].includes(state.status);
  refs.step.disabled = !state.cycleId || ["passed", "failed", "decision"].includes(state.status);
  refs.pause.textContent = state.paused ? "Resume" : "Pause";
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

  refs.events.innerHTML = eventLog.length ? [...eventLog].reverse().map((event, index) => `<div class="event-row ${index === 0 ? "new" : ""}"><time>${event.at}</time><strong>${event.type}<br><small>${event.stage ?? event.cycleId ?? event.measurement ?? "projection updated"}</small></strong></div>`).join("") : `<div class="event-row"><time>—</time><strong>No Grain facts yet<br><small>Start a cycle to append the first event.</small></strong></div>`;
  refs.evidence.innerHTML = state.evidence.length ? [...state.evidence].reverse().map((item) => `<div class="evidence-item"><span>${item.verdict.includes("pass") ? "✓" : "×"}</span>${item.name}<small>${item.digest} · ${item.authority}</small></div>`).join("") : `<div class="evidence-item">No evidence captured.<small>Candidate claims cannot determine an independent verdict.</small></div>`;
  refs.state.textContent = JSON.stringify({
    projection: state,
    architecture: activeDiagram ? {
      source: ".pi/architecture.md",
      diagram: activeDiagram.label,
      sourceLine: activeDiagram.sourceLine,
      elements: activeDiagram.nodes.map(({ id, name, type }) => ({ id, name, type })),
      relations: activeDiagram.edges,
      highlightedElementIds: activeTargetIds(),
    } : null,
    eventCount: eventLog.length,
    activeEvent: eventLog.at(-1) ?? null,
  }, null, 2);
  updateSceneFromState();
}

refs.start.addEventListener("click", startCycle);
refs.pause.addEventListener("click", () => append(state.paused ? "CycleResumed" : "CyclePaused"));
refs.step.addEventListener("click", completeActiveStage);
refs.speed.addEventListener("input", () => { refs.speedLabel.textContent = `${refs.speed.value}×`; append("SpeedChanged", { speed: Number(refs.speed.value) }); });
refs.repair.addEventListener("click", () => { append("RepairAuthorized", { failedStage: "verify", attempt: 2 }); append("StageEntered", { stageIndex: 6, stage: STAGES[6].id }); });
refs.abort.addEventListener("click", () => append("CycleAborted", { reason: "operator_refused_repair" }));

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

const workpiece = new THREE.Mesh(new THREE.IcosahedronGeometry(.48, 1), new THREE.MeshStandardMaterial({ color: 0xf2b84b, emissive: 0xc76b3c, emissiveIntensity: 1.4, metalness: .75, roughness: .22 }));
workpiece.castShadow = true; workpiece.visible = false; scene.add(workpiece);
const agents = [0x52d6d2, 0xc76b3c, 0xf1f0e8].map((color) => {
  const agent = new THREE.Group();
  const torso = new THREE.Mesh(new THREE.BoxGeometry(.48, .53, .36), new THREE.MeshStandardMaterial({ color, roughness: .4, metalness: .5 })); torso.position.y = .43; torso.castShadow = true; agent.add(torso);
  const head = new THREE.Mesh(new THREE.SphereGeometry(.2, 12, 8), new THREE.MeshStandardMaterial({ color: 0x172332, emissive: color, emissiveIntensity: .45 })); head.position.y = .86; agent.add(head);
  scene.add(agent); return agent;
});

function layoutDiagram(diagram) {
  const positions = new Map();
  if (diagram.id === "context") {
    const center = diagram.nodes.find((node) => node.id === "harness");
    if (center) positions.set(center.id, new THREE.Vector3(0, 0, 0));
    const outer = diagram.nodes.filter((node) => node !== center);
    outer.forEach((node, index) => {
      const angle = (index / outer.length) * Math.PI * 2 - Math.PI / 2;
      positions.set(node.id, new THREE.Vector3(Math.cos(angle) * 13, 0, Math.sin(angle) * 8));
    });
  } else if (diagram.id === "components") {
    const fixed = {
      rpc: [-12, -5], selection: [-12, 2], worker: [-6, -1], workflows: [0, 6], build: [0, 3], oracle: [0, 0], grading: [0, -3], evidence: [0, -6], runtime: [7, 4], grainmodel: [7, -4], orc: [13, 5], grain: [13, -5],
    };
    diagram.nodes.forEach((node, index) => { const pos = fixed[node.id] ?? [((index % 4) - 1.5) * 6, (Math.floor(index / 4) - 1) * 5]; positions.set(node.id, new THREE.Vector3(pos[0], 0, pos[1])); });
  } else {
    const internal = diagram.nodes.filter((node) => node.group === "system");
    const external = diagram.nodes.filter((node) => node.group !== "system");
    internal.forEach((node, index) => positions.set(node.id, new THREE.Vector3((index % 4 - 1.5) * 5.1, 0, (Math.floor(index / 4) - .5) * 5.2)));
    external.forEach((node, index) => {
      const angle = (index / external.length) * Math.PI * 2 - Math.PI / 2;
      positions.set(node.id, new THREE.Vector3(Math.cos(angle) * 16, 0, Math.sin(angle) * 10.5));
    });
  }
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
  const middle = from.clone().lerp(to, .5); middle.y = 1.05 + Math.min(2.5, from.distanceTo(to) * .08);
  const curve = new THREE.QuadraticBezierCurve3(from.clone().setY(.34), middle, to.clone().setY(.34));
  const material = new THREE.MeshStandardMaterial({ color: 0x3d6071, emissive: 0x183442, emissiveIntensity: .5, roughness: .45, metalness: .5 });
  const route = new THREE.Mesh(new THREE.TubeGeometry(curve, 18, .075, 6, false), material);
  route.userData.edge = edge; route.userData.baseColor = 0x3d6071; worldGroup.add(route); edgeMeshes.push(route);
  const label = makeTextSprite([edge.label, `${edge.from} → ${edge.to}`], "#91a0ae", .62); label.position.copy(middle).add(new THREE.Vector3(0, .48, 0)); worldGroup.add(label);
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
  const label = makeTextSprite([node.name, node.type], `#${new THREE.Color(color).getHexString()}`, .75); label.position.set(0, height + 1.5, 0); group.add(label);
  worldGroup.add(group); nodeMeshes.push(group);
}

function buildDiagram(diagramId) {
  activeDiagram = diagrams[diagramId] ?? diagrams.containers;
  refs.stationCard.hidden = true;
  while (worldGroup.children.length) {
    const child = worldGroup.children.pop(); child.traverse((object) => { object.geometry?.dispose(); if (Array.isArray(object.material)) object.material.forEach((material) => material.dispose()); else object.material?.dispose(); });
  }
  nodeMeshes = []; edgeMeshes = []; nodePositions = layoutDiagram(activeDiagram);
  const floor = new THREE.Mesh(new THREE.BoxGeometry(38, .4, 25), new THREE.MeshStandardMaterial({ color: 0x172332, roughness: .9, metalness: .12 })); floor.position.y = -.25; floor.receiveShadow = true; worldGroup.add(floor);
  const grid = new THREE.GridHelper(38, 38, 0x496179, 0x293b4e); grid.position.y = -.04; worldGroup.add(grid);
  if (activeDiagram.id === "containers") {
    const boundary = new THREE.Mesh(new THREE.BoxGeometry(19.5, .16, 12.2), new THREE.MeshStandardMaterial({ color: 0x183b47, transparent: true, opacity: .68, roughness: .8 })); boundary.position.y = -.02; worldGroup.add(boundary);
    const boundaryLabel = makeTextSprite(["Session-hosted ORC Regeneration Harness", "Software System boundary"], "#52d6d2", .82); boundaryLabel.position.set(0, .55, -6); worldGroup.add(boundaryLabel);
  }
  activeDiagram.edges.forEach(addRelationship);
  activeDiagram.nodes.forEach(addC4Node);
  $("#diagram-count").textContent = `${activeDiagram.nodes.length} elements · ${activeDiagram.edges.length} relations`;
  $("#diagram-source").textContent = `Source: .pi/architecture.md:${activeDiagram.sourceLine}`;
  updateSceneFromState();
}

diagramSelect.addEventListener("change", () => { buildDiagram(diagramSelect.value); renderState(); setCamera(VARIANTS[activeVariant].camera); });

const raycaster = new THREE.Raycaster();
const pointer = new THREE.Vector2();
canvas.addEventListener("click", (event) => {
  const rect = canvas.getBoundingClientRect(); pointer.x = ((event.clientX - rect.left) / rect.width) * 2 - 1; pointer.y = -((event.clientY - rect.top) / rect.height) * 2 + 1;
  raycaster.setFromCamera(pointer, camera);
  const hit = raycaster.intersectObjects([...nodeMeshes, ...edgeMeshes], true)[0];
  if (!hit) return;
  let target = hit.object;
  while (target.parent && !target.userData.node && !target.userData.edge) target = target.parent;
  if (target.userData.node) showArchitectureElement(target.userData.node);
  if (target.userData.edge) showRelationship(target.userData.edge);
});

function showArchitectureElement(node) {
  selectedStage = node.id;
  $("#station-code").textContent = node.id; $("#station-name").textContent = node.name;
  $("#station-description").textContent = node.description || "This element is named in the repository C4 source without an additional description.";
  $("#station-owner").textContent = node.type; $("#station-authority").textContent = activeDiagram.label; refs.stationCard.hidden = false;
}

function showRelationship(edge) {
  $("#station-code").textContent = `${edge.from} → ${edge.to}`; $("#station-name").textContent = edge.label;
  $("#station-description").textContent = "Relationship loaded from the selected Mermaid C4 view in .pi/architecture.md.";
  $("#station-owner").textContent = "C4 relationship"; $("#station-authority").textContent = activeDiagram.label; refs.stationCard.hidden = false;
}
$("#close-station").addEventListener("click", () => { refs.stationCard.hidden = true; selectedStage = null; });

function activeTargetIds() { return STAGE_TARGETS[activeDiagram.id]?.[Math.max(0, state.activeStage)] ?? []; }

function updateSceneFromState() {
  if (!activeDiagram) return;
  const targets = new Set(activeTargetIds());
  workpiece.visible = Boolean(state.cycleId) && state.status !== "failed";
  nodeMeshes.forEach((group) => {
    const active = targets.has(group.userData.node.id);
    group.scale.setScalar(active ? 1.1 : 1);
    group.children.forEach((child) => { if (child.material?.emissive) child.material.emissiveIntensity = active ? 1.25 : .09; });
  });
  edgeMeshes.forEach((route) => {
    const active = targets.has(route.userData.edge.from) && targets.has(route.userData.edge.to);
    route.material.color.setHex(active ? 0xf2b84b : route.userData.baseColor); route.material.emissiveIntensity = active ? 2 : .5;
  });
  const destinations = [...targets].map((id) => nodePositions.get(id)).filter(Boolean);
  const target = destinations[0] ?? nodePositions.values().next().value ?? new THREE.Vector3();
  agents.forEach((agent, index) => { const destination = destinations[index % Math.max(1, destinations.length)] ?? target; agent.userData.target = destination.clone().add(new THREE.Vector3((index - 1) * .7, 0, 1.8)); });
}

function setCamera(mode) {
  if (mode === "top") { camera.position.set(0, 38, .01); controls.target.set(0, 0, 0); }
  else if (mode === "perspective") { camera.position.set(27, 16, 24); controls.target.set(0, 1, 0); }
  else { camera.position.set(25, 23, 28); controls.target.set(0, 1, 0); }
  controls.update();
}
$("#camera-reset").addEventListener("click", () => setCamera(VARIANTS[activeVariant].camera));

function resize() {
  const rect = canvas.getBoundingClientRect(); if (!rect.width || !rect.height) return;
  renderer.setSize(rect.width, rect.height, false); camera.aspect = rect.width / rect.height; camera.updateProjectionMatrix();
}
new ResizeObserver(resize).observe(canvas);

function frame(now) {
  const delta = Math.min((now - lastFrame) / 1000, .1); lastFrame = now;
  if (state.status === "running" && !state.paused) { stageElapsed += delta * state.speed; const duration = state.activeStage === 2 ? 4.3 : 2.8; if (stageElapsed >= duration) completeActiveStage(); }
  workpiece.rotation.x += delta * 1.4; workpiece.rotation.y += delta * 2;
  if (workpiece.visible) {
    const targetId = activeTargetIds()[0]; const target = nodePositions.get(targetId) ?? new THREE.Vector3();
    workpiece.position.lerp(new THREE.Vector3(target.x, 4 + Math.sin(now * .003) * .2, target.z), .045 * Math.max(1, state.speed));
  }
  agents.forEach((agent, index) => { if (agent.userData.target) agent.position.lerp(agent.userData.target, .018 + index * .003); agent.rotation.y = Math.sin(now * .001 + index) * .16; });
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
  if (!editing && event.code === "Space") { event.preventDefault(); if (!state.cycleId || ["passed", "failed"].includes(state.status)) startCycle(); else if (!state.awaitingDecision) append(state.paused ? "CycleResumed" : "CyclePaused"); }
});

buildDiagram("containers");
setVariant(new URLSearchParams(location.search).get("variant") ?? "A", false);
renderState();
requestAnimationFrame(frame);
