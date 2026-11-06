import assert from "node:assert/strict";
import os from "node:os";
import test from "node:test";
import { execOnHost } from "../src/host-exec.ts";

const alive = (pid) => {
  try { process.kill(pid, 0); return true; } catch { return false; }
};

test("a command returns when its shell exits, and a process it left behind does not outlive it", async () => {
  const started = Date.now();
  const result = await execOnHost("sleep 30 & echo $!", os.tmpdir());
  assert.ok(Date.now() - started < 5000, "a background process holding stdout must not keep the command open");
  assert.equal(result.exitCode, 0);
  const leftover = Number(result.stdout.trim());
  await new Promise((resolve) => setTimeout(resolve, 200));
  assert.equal(alive(leftover), false, "the command's process group is killed when its shell exits");
});

test("an aborted command kills its whole process group, not only the shell", async () => {
  const marker = `${os.tmpdir()}/host-exec-abort-${process.pid}`;
  const controller = new AbortController();
  setTimeout(() => controller.abort(), 300);
  const result = await execOnHost(`sh -c 'sleep 30' & echo $! > ${marker}; wait`, os.tmpdir(), {
    signal: controller.signal,
  }).catch((error) => ({ aborted: error.name }));
  assert.ok(result.aborted === "AbortError" || result.exitCode !== 0);
  const { readFile, rm } = await import("node:fs/promises");
  const leftover = Number((await readFile(marker, "utf8")).trim());
  await rm(marker, { force: true });
  await new Promise((resolve) => setTimeout(resolve, 200));
  assert.equal(alive(leftover), false);
});
