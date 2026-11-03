import assert from "node:assert/strict";
import { mkdtemp } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { SessionManager } from "@earendil-works/pi-coding-agent";
import { readBuilderSession, writeBuilderSessionState } from "../src/builder-session-store.ts";

test("builder session view exposes persisted Pi entries and harness progress read-only", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "loyly-builder-session-"));
  const session = SessionManager.create("/workspace", root, { id: "builder-session-38" });
  session.appendMessage({ role: "user", content: "build the approved slice", timestamp: Date.now() });
  session.appendMessage({
    role: "assistant",
    content: [{ type: "text", text: "implemented" }],
    api: "responses",
    provider: "test",
    model: "test",
    usage: { input: 1, output: 1, cacheRead: 0, cacheWrite: 0, totalTokens: 2, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } },
    stopReason: "stop",
    timestamp: Date.now(),
  });
  await writeBuilderSessionState(root, {
    builderSessionRef: "builder-session-38",
    sessionFile: session.getSessionFile(),
    status: "completed",
    toolCalls: 3,
    workspaceGeneration: ["package.json", "src"],
    progress: { stage: "builder", attempt: 1 },
  });

  const view = await readBuilderSession(root, "builder-session-38");
  assert.equal(view.entries.length, 2);
  assert.equal(view.toolCalls, 3);
  assert.deepEqual(view.workspaceGeneration, ["package.json", "src"]);
  assert.deepEqual(view.progress, { stage: "builder", attempt: 1 });
  assert.equal("sessionFile" in view, false);
});

test("builder session lookup rejects path traversal", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "loyly-builder-session-"));
  await assert.rejects(() => readBuilderSession(root, "../operator-session"), /session/i);
});

test("builder session reference is observable before the first model turn", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "loyly-builder-session-"));
  await writeBuilderSessionState(root, {
    builderSessionRef: "builder-session-starting",
    status: "running",
    toolCalls: 0,
    workspaceGeneration: [],
    progress: { stage: "builder", attempt: 2 },
  });

  assert.deepEqual(await readBuilderSession(root, "builder-session-starting"), {
    builderSessionRef: "builder-session-starting",
    status: "running",
    toolCalls: 0,
    workspaceGeneration: [],
    progress: { stage: "builder", attempt: 2 },
    entries: [],
  });
});
