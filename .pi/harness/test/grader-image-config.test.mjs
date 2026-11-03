import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const requiredDiagnosticPackages = ["perf", "bpftool", "strace"];
const requiredOtelBinaries = ["obi", "otelcol-contrib", "otelcol-ebpf-profiler"];
const projectFile = (name) => new URL(`../../../harness/loyly/${name}`, import.meta.url);

test("Löyly VM config and supported stack Dockerfiles follow the project convention", async () => {
  const config = JSON.parse(await readFile(projectFile("gondolin.json"), "utf8"));
  assert.equal(config.arch, "aarch64");
  for (const stack of ["typescript", "rust", "java", "rails"]) {
    await readFile(projectFile(`Dockerfile.${stack}`), "utf8");
  }
});

test("grader VM image bakes in the standard OTel eBPF stack", async () => {
  const config = JSON.parse(await readFile(projectFile("gondolin.json"), "utf8"));

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
  const dockerfile = await readFile(projectFile("Dockerfile"), "utf8");
  const installer = await readFile(projectFile("install-otel-ebpf.sh"), "utf8");

  for (const packageName of requiredDiagnosticPackages) {
    assert.match(dockerfile, new RegExp(`\\b${packageName}\\b`));
  }
  assert.doesNotMatch(dockerfile, /\bbpftrace\b/);
  for (const binary of requiredOtelBinaries) assert.match(installer, new RegExp(binary));
  assert.match(installer, /sha256sum -c/);
});

test("collector configurations emit native OTLP JSON into cycle evidence", async () => {
  const collector = await readFile(projectFile("otel-collector.yaml"), "utf8");
  const profiler = await readFile(projectFile("otel-profiler.yaml"), "utf8");

  assert.match(collector, /receivers:\s*[\s\S]*otlp:/);
  assert.match(collector, /\$\{env:OTEL_FILE_EXPORT_DIR\}\/traces\.jsonl/);
  assert.match(collector, /\$\{env:OTEL_FILE_EXPORT_DIR\}\/metrics\.jsonl/);
  assert.match(collector, /telemetry:\s*[\s\S]*metrics:\s*[\s\S]*level: none/);
  assert.match(profiler, /profiling:/);
  assert.match(profiler, /\$\{env:OTEL_FILE_EXPORT_DIR\}\/profiles\.jsonl/);
  assert.match(profiler, /telemetry:\s*[\s\S]*metrics:\s*[\s\S]*level: none/);
});

test("grader VM mounts tracing filesystems during image init", async () => {
  const init = await readFile(projectFile("grader-profile-init.sh"), "utf8");

  assert.ok(init.includes("mount -t tracefs tracefs /sys/kernel/tracing"));
  assert.ok(init.includes("mount -t bpf bpf /sys/fs/bpf"));
});
