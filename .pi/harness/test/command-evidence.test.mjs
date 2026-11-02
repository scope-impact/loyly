import assert from "node:assert/strict";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { persistCommandEvidence } from "../src/command-evidence.ts";

test("command evidence is durable and namespaced by observer", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "loyly-command-evidence-"));
  try {
    await persistCommandEvidence(root, "compose", "compose-logs", {
      exitCode: 0,
      stdout: "app ready\n",
      stderr: "",
    });
    assert.equal(await readFile(path.join(root, "compose/compose-logs.stdout.txt"), "utf8"), "app ready\n");
    assert.deepEqual(JSON.parse(await readFile(path.join(root, "compose/compose-logs.result.json"), "utf8")), {
      exitCode: 0,
      stdout: "app ready\n",
      stderr: "",
    });
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("command evidence rejects path traversal", async () => {
  await assert.rejects(
    persistCommandEvidence("/tmp/not-used", "../escape", "command", { exitCode: 0, stdout: "", stderr: "" }),
    /unsafe evidence path segment/,
  );
});

test("command evidence permits validated observer subnamespaces", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "loyly-command-evidence-nested-"));
  try {
    await persistCommandEvidence(root, "independent/01-001", "grade", {
      exitCode: 0, stdout: "pass\n", stderr: "",
    });
    assert.equal(await readFile(path.join(root, "independent/01-001/grade.stdout.txt"), "utf8"), "pass\n");
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
