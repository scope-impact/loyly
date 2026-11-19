import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmodSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { test } from "node:test";

const bin = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../bin");
const dir = mkdtempSync(path.join(tmpdir(), "loyly-agent-process-"));
const host = path.join(dir, "host with spaces.sh");
writeFileSync(host, "#!/usr/bin/env bash\nprintf '%s\\n' \"$@\"\nexit \"${HOST_EXIT:-0}\"\n");
chmodSync(host, 0o755);

test("builder process host receives the exact ORC arguments and exit status", () => {
  const args = ["room with spaces", "instruction; echo unsafe", "image"];
  const run = spawnSync(path.join(bin, "builder-process.sh"), args, {
    encoding: "utf8", env: { ...process.env, LOYLY_BUILDER_ADAPTER: "process", LOYLY_BUILDER_PROCESS_HOST: host, HOST_EXIT: "7" },
  });
  assert.equal(run.status, 7);
  assert.deepEqual(run.stdout.trimEnd().split("\n"), args);
});

test("grader process host receives the same bounded command shape", () => {
  const args = ["grading room", "item instruction", "grader image"];
  const run = spawnSync(path.join(bin, "grader-process.sh"), args, {
    encoding: "utf8", env: { ...process.env, LOYLY_GRADER_ADAPTER: "process", LOYLY_GRADER_PROCESS_HOST: host },
  });
  assert.equal(run.status, 0, run.stderr);
  assert.deepEqual(run.stdout.trimEnd().split("\n"), args);
});

test("a missing host, unknown provider, or malformed call fails before launch", () => {
  for (const [entry, args, env] of [
    ["builder-process.sh", ["room", "instruction"], { LOYLY_BUILDER_ADAPTER: "process" }],
    ["grader-process.sh", ["room", "instruction", "image"], { LOYLY_GRADER_ADAPTER: "unknown" }],
    ["builder-process.sh", ["room"], { LOYLY_BUILDER_ADAPTER: "process", LOYLY_BUILDER_PROCESS_HOST: host }],
  ]) {
    const run = spawnSync(path.join(bin, entry), args, { encoding: "utf8", env: { ...process.env, ...env } });
    assert.equal(run.status, 2);
  }
});
