import * as THREE from "three";
import { OrbitControls } from "three/addons/controls/OrbitControls.js";
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
  refs.state.textContent = JSON.stringify({ projection: state, eventCount: eventLog.length, activeEvent: eventLog.at(-1) ?? null }, null, 2);
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

// 3D factory floor -----------------------------------------------------------
const canvas = $("#factory-canvas");
const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: false });
renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
renderer.shadowMap.enabled = true;
renderer.shadowMap.type = THREE.PCFSoftShadowMap;
renderer.outputColorSpace = THREE.SRGBColorSpace;
renderer.setClearColor(0x0b1623);

const scene = new THREE.Scene();
scene.fog = new THREE.FogExp2(0x0b1623, 0.024);
const camera = new THREE.PerspectiveCamera(42, 1, 0.1, 120);
const controls = new OrbitControls(camera, canvas);
controls.enableDamping = true;
controls.dampingFactor = 0.065;
controls.maxPolarAngle = Math.PI * 0.48;
controls.minDistance = 10;
controls.maxDistance = 42;

scene.add(new THREE.HemisphereLight(0x90b6ca, 0x1d2630, 2.1));
const keyLight = new THREE.DirectionalLight(0xffd8a4, 3.3);
keyLight.position.set(-10, 18, 9); keyLight.castShadow = true; keyLight.shadow.mapSize.set(2048, 2048); scene.add(keyLight);
const cyanLight = new THREE.PointLight(0x52d6d2, 32, 22, 2); cyanLight.position.set(8, 5, -5); scene.add(cyanLight);

const floor = new THREE.Mesh(new THREE.BoxGeometry(30, .45, 17), new THREE.MeshStandardMaterial({ color: 0x172332, roughness: .87, metalness: .15 }));
floor.position.y = -.28; floor.receiveShadow = true; scene.add(floor);
const grid = new THREE.GridHelper(30, 30, 0x496179, 0x293b4e); grid.position.y = -.045; scene.add(grid);

const stationMeshes = [];
const stationPositions = [
  [-11, -4], [-6, -4], [-1, -4], [4, -4], [9, -4],
  [9, 4], [4, 4], [-1, 4], [-6, 4], [-11, 4],
];

const sharedDark = new THREE.MeshStandardMaterial({ color: 0x24364a, roughness: .62, metalness: .42 });
const sharedSteel = new THREE.MeshStandardMaterial({ color: 0x536779, roughness: .55, metalness: .55 });

STAGES.forEach((stage, index) => {
  const group = new THREE.Group();
  const [x, z] = stationPositions[index]; group.position.set(x, 0, z); group.userData.stageIndex = index;
  const base = new THREE.Mesh(new THREE.BoxGeometry(3.2, .5, 2.55), sharedDark); base.position.y = .24; base.castShadow = base.receiveShadow = true; group.add(base);
  const body = new THREE.Mesh(new THREE.BoxGeometry(2.2, 1.75 + (index % 3) * .24, 1.7), index === 7 ? sharedSteel : sharedDark); body.position.y = 1.32; body.castShadow = true; group.add(body);
  const signal = new THREE.Mesh(new THREE.BoxGeometry(1.5, .11, .08), new THREE.MeshStandardMaterial({ color: stage.color, emissive: stage.color, emissiveIntensity: 2 })); signal.position.set(0, 2.1 + (index % 3) * .12, .88); group.add(signal);
  const chimney = new THREE.Mesh(new THREE.CylinderGeometry(.15, .21, 1.5, 8), sharedSteel); chimney.position.set(.73, 2.5, -.5); chimney.castShadow = true; group.add(chimney);
  const number = makeLabel(String(index + 1).padStart(2, "0")); number.position.set(0, .72, .89); group.add(number);
  scene.add(group); stationMeshes.push(group);
});

const pathPoints = stationPositions.map(([x, z]) => new THREE.Vector3(x, .25, z));
const pathMaterial = new THREE.MeshStandardMaterial({ color: 0x33485a, roughness: .7, metalness: .35 });
for (let i = 0; i < pathPoints.length - 1; i++) addBeam(pathPoints[i], pathPoints[i + 1]);

function addBeam(from, to) {
  const midpoint = from.clone().add(to).multiplyScalar(.5);
  const length = from.distanceTo(to);
  const beam = new THREE.Mesh(new THREE.BoxGeometry(length, .16, .52), pathMaterial);
  beam.position.copy(midpoint); beam.position.y = .12; beam.rotation.y = -Math.atan2(to.z - from.z, to.x - from.x); beam.receiveShadow = true; scene.add(beam);
}

function makeLabel(text) {
  const labelCanvas = document.createElement("canvas"); labelCanvas.width = 128; labelCanvas.height = 64;
  const ctx = labelCanvas.getContext("2d"); ctx.fillStyle = "#e5e0cf"; ctx.font = "600 40px sans-serif"; ctx.textAlign = "center"; ctx.fillText(text, 64, 44);
  const texture = new THREE.CanvasTexture(labelCanvas); texture.colorSpace = THREE.SRGBColorSpace;
  const material = new THREE.SpriteMaterial({ map: texture, transparent: true, depthTest: false });
  const sprite = new THREE.Sprite(material); sprite.scale.set(1.2, .6, 1); return sprite;
}

// Workpiece and three builder agents make progress spatially legible.
const workpiece = new THREE.Mesh(new THREE.IcosahedronGeometry(.5, 1), new THREE.MeshStandardMaterial({ color: 0xf2b84b, emissive: 0xc76b3c, emissiveIntensity: 1.2, metalness: .75, roughness: .22 }));
workpiece.castShadow = true; workpiece.visible = false; scene.add(workpiece);
const agents = [0x52d6d2, 0xc76b3c, 0xf1f0e8].map((color, index) => {
  const agent = new THREE.Group();
  const torso = new THREE.Mesh(new THREE.BoxGeometry(.5, .55, .38), new THREE.MeshStandardMaterial({ color, roughness: .4, metalness: .5 })); torso.position.y = .45; torso.castShadow = true; agent.add(torso);
  const head = new THREE.Mesh(new THREE.SphereGeometry(.22, 12, 8), new THREE.MeshStandardMaterial({ color: 0x172332, emissive: color, emissiveIntensity: .45 })); head.position.y = .9; agent.add(head);
  agent.position.set(-12 + index, .25, -6.5); scene.add(agent); return agent;
});

const raycaster = new THREE.Raycaster();
const pointer = new THREE.Vector2();
canvas.addEventListener("click", (event) => {
  const rect = canvas.getBoundingClientRect();
  pointer.x = ((event.clientX - rect.left) / rect.width) * 2 - 1;
  pointer.y = -((event.clientY - rect.top) / rect.height) * 2 + 1;
  raycaster.setFromCamera(pointer, camera);
  const hit = raycaster.intersectObjects(stationMeshes, true)[0];
  if (!hit) return;
  let target = hit.object;
  while (target.parent && target.userData.stageIndex === undefined) target = target.parent;
  if (target.userData.stageIndex !== undefined) showStation(target.userData.stageIndex);
});

function showStation(index) {
  selectedStage = index;
  const stage = STAGES[index];
  $("#station-code").textContent = stage.code; $("#station-name").textContent = stage.name;
  $("#station-description").textContent = stage.description; $("#station-owner").textContent = stage.owner;
  $("#station-authority").textContent = stage.authority; refs.stationCard.hidden = false;
}
$("#close-station").addEventListener("click", () => { refs.stationCard.hidden = true; selectedStage = null; });

function updateSceneFromState() {
  workpiece.visible = Boolean(state.cycleId) && !["failed"].includes(state.status);
  const active = Math.max(0, state.activeStage);
  stationMeshes.forEach((group, index) => {
    const scale = state.activeStage === index && state.status !== "passed" ? 1.07 : 1;
    group.scale.setScalar(scale);
  });
  const pos = pathPoints[active] ?? pathPoints[0];
  agents.forEach((agent, index) => {
    const offset = new THREE.Vector3((index - 1) * .7, 0, 1.6 + Math.abs(index - 1) * .25);
    agent.userData.target = pos.clone().add(offset);
  });
}

function setCamera(mode) {
  if (mode === "top") { camera.position.set(0, 31, .01); controls.target.set(0, 0, 0); }
  else if (mode === "perspective") { camera.position.set(20, 10, 17); controls.target.set(0, 1, 0); }
  else { camera.position.set(18, 17, 20); controls.target.set(0, 1, 0); }
  controls.update();
}
$("#camera-reset").addEventListener("click", () => setCamera(VARIANTS[activeVariant].camera));

function resize() {
  const rect = canvas.getBoundingClientRect();
  if (!rect.width || !rect.height) return;
  renderer.setSize(rect.width, rect.height, false); camera.aspect = rect.width / rect.height; camera.updateProjectionMatrix();
}
new ResizeObserver(resize).observe(canvas);

function frame(now) {
  const delta = Math.min((now - lastFrame) / 1000, .1); lastFrame = now;
  if (state.status === "running" && !state.paused) {
    stageElapsed += delta * state.speed;
    const duration = state.activeStage === 2 ? 4.3 : 2.8;
    if (stageElapsed >= duration) completeActiveStage();
  }
  workpiece.rotation.x += delta * 1.4; workpiece.rotation.y += delta * 2;
  if (workpiece.visible) {
    const target = pathPoints[Math.max(0, state.activeStage)] ?? pathPoints[0];
    workpiece.position.lerp(new THREE.Vector3(target.x, 2.9 + Math.sin(now * .003) * .18, target.z), .045 * Math.max(1, state.speed));
  }
  agents.forEach((agent, index) => {
    if (agent.userData.target) agent.position.lerp(agent.userData.target, .018 + index * .003);
    agent.rotation.y = Math.sin(now * .001 + index) * .16;
  });
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

setVariant(new URLSearchParams(location.search).get("variant") ?? "A", false);
renderState();
requestAnimationFrame(frame);
