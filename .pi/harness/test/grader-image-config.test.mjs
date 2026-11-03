import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const requiredDiagnosticPackages = ["perf", "bpftool", "strace"];
const requiredOtelBinaries = ["obi", "otelcol-contrib", "otelcol-ebpf-profiler"];

test("grader VM image bakes in the standard OTel eBPF stack", async () => {
  const config = JSON.parse(await readFile(new URL("../config/build/grader.json", import.meta.url), "utf8"));

  for (const packageName of requiredDiagnosticPackages) {
    assert.ok(
      config.alpine.rootfsPackages.includes(packageName),
      `${packageName} must be present in the grader rootfs`,
    );
  }
  assert.equal(config.alpine.rootfsPackages.includes("bpftrace"), false);
  for (const binary of requiredOtelBinaries) {
    assert.ok(config.postBuild.commands.some((command) => command.includes(`/usr/local/bin/${binary}`)));
  }
  assert.equal(config.init.rootfsInitExtra, "grader-profile-init.sh");
});

test("grader OCI image carries the same OTel eBPF stack", async () => {
  const dockerfile = await readFile(new URL("../config/build/Dockerfile.grader", import.meta.url), "utf8");
  const installer = await readFile(new URL("../config/build/install-otel-ebpf.sh", import.meta.url), "utf8");

  for (const packageName of requiredDiagnosticPackages) {
    assert.match(dockerfile, new RegExp(`\\b${packageName}\\b`));
  }
  assert.doesNotMatch(dockerfile, /\bbpftrace\b/);
  for (const binary of requiredOtelBinaries) assert.match(installer, new RegExp(binary));
  assert.match(installer, /sha256sum -c/);
});

test("collector configurations emit native OTLP JSON into cycle evidence", async () => {
  const collector = await readFile(new URL("../config/build/otel-collector.yaml", import.meta.url), "utf8");
  const profiler = await readFile(new URL("../config/build/otel-profiler.yaml", import.meta.url), "utf8");

  assert.match(collector, /receivers:\s*[\s\S]*otlp:/);
  assert.match(collector, /\$\{env:OTEL_FILE_EXPORT_DIR\}\/traces\.jsonl/);
  assert.match(collector, /\$\{env:OTEL_FILE_EXPORT_DIR\}\/metrics\.jsonl/);
  assert.match(collector, /telemetry:\s*[\s\S]*metrics:\s*[\s\S]*level: none/);
  assert.match(profiler, /profiling:/);
  assert.match(profiler, /\$\{env:OTEL_FILE_EXPORT_DIR\}\/profiles\.jsonl/);
  assert.match(profiler, /telemetry:\s*[\s\S]*metrics:\s*[\s\S]*level: none/);
});

test("grader VM mounts tracing filesystems during image init", async () => {
  const init = await readFile(new URL("../config/build/grader-profile-init.sh", import.meta.url), "utf8");

  assert.match(init, /mount -t tracefs tracefs \/sys\/kernel\/tracing/);
  assert.match(init, /mount -t bpf bpf \/sys\/fs\/bpf/);
});
