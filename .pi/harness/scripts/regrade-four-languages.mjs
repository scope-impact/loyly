import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";

import { openGraderRoom } from "../src/grader-room.ts";

const projectRoot = path.resolve(import.meta.dirname, "../../..");
const baselineId = "four-language-2026-11-02T21-35-07-633Z";
const oracle = "e328f56447a5eb888e958da29fb90a4e2500adf86463249f2d69738df4a021a6";
const requested = process.argv.slice(2);
const items = requested.length ? requested.map(normalizeItem) : Array.from({ length: 11 }, (_, i) => `01-${String(i + 1).padStart(3, "0")}`);
const runId = `improvement-${new Date().toISOString().replace(/[:.]/g, "-")}`;
const runRoot = path.join(projectRoot, ".pi/harness/.state/comparisons", baselineId, runId);
const cutsRoot = path.join(runRoot, "cuts");
await mkdir(path.join(cutsRoot, "empty"), { recursive: true });

const stacks = [
  { id: "typescript", output: `${baselineId}-typescript` },
  { id: "rust", output: `${baselineId}-rust-repair-02`, archive: "rust-1.91-web.tar" },
  { id: "java", output: `${baselineId}-java`, archive: "java-21-web.tar" },
  { id: "rails", output: `${baselineId}-rails`, archive: "rails-8-offline.tar" },
];
const report = { schemaVersion: 1, runId, baselineId, startedAt: new Date().toISOString(), items, stacks: [] };
await Promise.all(stacks.map(async stack => {
  const entry = { id: stack.id, items: [] };
  report.stacks.push(entry);
  for (const item of items) {
    console.log(`[${stack.id}] ${item}: fresh Gondolin VM`);
    const result = await grade(stack, item);
    entry.items.push(result);
    await persist();
    console.log(`[${stack.id}] ${item}: ${result.passed ? "PASS" : "FAIL"} ${result.evidence}`);
  }
}));
report.finishedAt = new Date().toISOString();
await persist();
console.log(JSON.stringify({ runRoot, results: path.join(runRoot, "results.json") }));

async function grade(stack, item) {
  const itemId = `@item-${item}`;
  const evidenceRoot = path.join(runRoot, "evidence", stack.id, item);
  const room = await openGraderRoom({
    backend: "gondolin",
    cutsRoot,
    cutId: "empty",
    outputRoot: path.join(projectRoot, ".pi/harness/.state/outputs", stack.output),
    evidenceRoot,
    cycleId: `${stack.id}-${item}`,
    graderAssetsRoot: path.join(projectRoot, `.pi/harness/.state/oracles/${oracle}/grader`),
    observabilityAssetsRoot: path.join(projectRoot, `.pi/harness/.state/oracles/${oracle}/runtime-observability`),
    runtimeImagesRoot: stack.archive ? path.join(projectRoot, ".pi/harness/.state/runtime-images") : undefined,
  });
  const root = `/evidence/otel/independent/${itemId}`;
  const compose = "-f impl/compose.yaml -f /tmp/pi-harness-evidence-compose.json -f /tmp/pi-harness-independent-evidence-compose.json";
  const outputs = {};
  try {
    outputs.runtime = await run(room, runtimeCommand(stack.archive));
    outputs.up = await run(room, `echo '${evidenceCompose()}' > /tmp/pi-harness-evidence-compose.json; echo '${independentCompose(root)}' > /tmp/pi-harness-independent-evidence-compose.json; mkdir -p ${root}; docker compose ${compose} up --detach --build --wait --wait-timeout 10 app; container=$(docker compose ${compose} ps -q app); ip=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$container"); test -n "$ip"; echo http://$ip:8080 >/tmp/pi-harness-independent-base-url; curl -fsS "http://$ip:8080/health" >/dev/null`);
    if (outputs.up.exitCode !== 0) return verdict(item, false, "application startup failed", outputs);
    outputs.observeStart = await run(room, observeStart(root));
    outputs.grade = await run(room, `OTEL_FILE_EXPORT_DIR=${root} bash /grader/booking-core-${item}.sh "$(cat /tmp/pi-harness-independent-base-url)"`);
    outputs.observeStop = await run(room, observeStop(root));
    outputs.logs = await run(room, `docker compose ${compose} logs --no-color`);
    const parsed = parseGrade(outputs.grade.stdout, outputs.grade.stderr);
    return verdict(item, outputs.grade.exitCode === 0, parsed, outputs);
  } catch (error) {
    return verdict(item, false, error instanceof Error ? error.message : String(error), outputs);
  } finally {
    await run(room, `docker compose ${compose} down --volumes --remove-orphans`).catch(() => {});
    await room.close();
  }
}

function runtimeCommand(archive) {
  const daemon = "mkdir -p /sys/fs/cgroup && (mountpoint -q /sys/fs/cgroup || mount -t cgroup2 none /sys/fs/cgroup) || exit 1; docker info >/dev/null 2>&1 || (dockerd --storage-driver=overlay2 >/tmp/dockerd.log 2>&1 & attempts=0; until docker info >/dev/null 2>&1; do attempts=$((attempts + 1)); [ $attempts -lt 100 ] || { cat /tmp/dockerd.log >&2; exit 1; }; sleep 0.1; done)";
  if (archive) return `${daemon}; docker load -i /runtime-images/${archive}`;
  return `${daemon}; base=$(head -n 1 impl/Dockerfile); base=${"${base#FROM }"}; (cd / && tar -c bin tmp lib/ld-musl-aarch64.so.1 usr/bin/node usr/bin/dirname usr/lib/*.so*) | docker import - "$base" >/dev/null`;
}

function evidenceCompose() {
  return JSON.stringify({ services: { app: { environment: { HARNESS_EVIDENCE_ROOT: "/evidence", PLAYWRIGHT_OUTPUT_DIR: "/evidence/playwright", OTEL_FILE_EXPORT_DIR: "/evidence/otel" }, volumes: [{ type: "bind", source: "${HARNESS_EVIDENCE_ROOT}", target: "/evidence" }] }, test: { environment: { HARNESS_EVIDENCE_ROOT: "/evidence", PLAYWRIGHT_OUTPUT_DIR: "/evidence/playwright", OTEL_FILE_EXPORT_DIR: "/evidence/otel" }, volumes: [{ type: "bind", source: "${HARNESS_EVIDENCE_ROOT}", target: "/evidence" }] } } });
}

function independentCompose(root) { return JSON.stringify({ services: { app: { environment: { OTEL_FILE_EXPORT_DIR: root } } } }); }

function observeStart(root) {
  return `OTEL_FILE_EXPORT_DIR=${root} nohup /usr/local/bin/otelcol-contrib --config=/etc/loyly/otel/collector.yaml >${root}/collector.log 2>&1 & echo $! >/tmp/pi-harness-otel-collector.pid; OTEL_FILE_EXPORT_DIR=${root} nohup /usr/local/bin/otelcol-ebpf-profiler --feature-gates=+service.profilesSupport --config=/etc/loyly/otel/profiler.yaml >${root}/profiler.log 2>&1 & echo $! >/tmp/pi-harness-otel-profiler.pid; OTEL_EBPF_OPEN_PORT=8080 OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4318 OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf OTEL_METRIC_EXPORT_INTERVAL=5000 nohup /usr/local/bin/obi >${root}/obi.log 2>&1 & echo $! >/tmp/pi-harness-obi.pid; sleep 2; for pidfile in /tmp/pi-harness-otel-collector.pid /tmp/pi-harness-otel-profiler.pid /tmp/pi-harness-obi.pid; do kill -0 "$(cat "$pidfile")" || exit 1; done; attempts=0; until grep -q 'Launching p.Tracer' ${root}/obi.log; do attempts=$((attempts + 1)); [ $attempts -lt 40 ] || exit 1; sleep 0.25; done; sleep 1`;
}

function observeStop(root) {
  return `test -r /tmp/pi-harness-obi.pid && kill -INT "$(cat /tmp/pi-harness-obi.pid)" 2>/dev/null || true; sleep 2; test -r /tmp/pi-harness-otel-profiler.pid && kill -INT "$(cat /tmp/pi-harness-otel-profiler.pid)" 2>/dev/null || true; sleep 1; test -r /tmp/pi-harness-otel-collector.pid && kill -INT "$(cat /tmp/pi-harness-otel-collector.pid)" 2>/dev/null || true; sleep 2; find ${root} -maxdepth 1 -type f -print -exec wc -c {} \\;`;
}

async function run(room, command) { const value = await room.exec(command); return { exitCode: value.exitCode, stdout: value.stdout ?? "", stderr: value.stderr ?? "" }; }
function parseGrade(stdout, stderr) { try { const value = JSON.parse(stdout.trim().split("\n").at(-1)); return value.evidence ?? stdout.trim(); } catch { return (stderr || stdout).trim().split("\n").at(-1) || "no grader evidence"; } }
function verdict(item, passed, evidence, outputs) { return { item, passed, evidence, outputs }; }
function normalizeItem(value) { const clean = value.replace(/^@?item-/, ""); if (!/^01-0(?:0[1-9]|1[01])$/.test(clean)) throw new Error(`invalid item ${value}`); return clean; }
async function persist() { await mkdir(runRoot, { recursive: true }); await writeFile(path.join(runRoot, "results.json"), `${JSON.stringify(report, null, 2)}\n`); }
