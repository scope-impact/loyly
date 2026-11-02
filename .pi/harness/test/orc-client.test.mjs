import assert from "node:assert/strict";
import test from "node:test";
import { OrcClient, spawnOrcClient } from "../src/orc-client.ts";

test("Pi/Codex proposals cross only the ORC external-proposer boundary", async () => {
  const calls = [];
  const client = new OrcClient(async (method, params) => {
    calls.push({ method, params });
    return { status: "running" };
  });

  await client.startExternalPromptOptimization([{ task: "train" }], [{ task: "validate" }]);
  await client.submitExternalPromptCandidate("optimization-1", "improved prompt", "pi-codex");

  assert.deepEqual(calls, [
    {
      method: "start-external-prompt-optimization",
      params: { trainset: [{ task: "train" }], valset: [{ task: "validate" }] },
    },
    {
      method: "submit-external-prompt-candidate",
      params: { optimizationId: "optimization-1", prompt: "improved prompt", proposer: "pi-codex" },
    },
  ]);
});

test("Pi adapter submits and waits through the ORC worker", async () => {
  const worker = spawnOrcClient({
    projectRoot: new URL("../../..", import.meta.url).pathname,
    command: new URL("../fixtures/fake-worker.mjs", import.meta.url).pathname,
  });
  try {
    const receipt = await worker.client.submit({
      requestId: `smoke-${Date.now()}`,
      cut: "cut-smoke",
      scope: ["workspace"],
      targetStack: "typescript",
      executionBackend: "gondolin",
    });
    assert.equal(receipt.taskId, 7);
    const outcome = await worker.client.wait(receipt.taskId);
    assert.equal(outcome.status, "completed");
    assert.equal(outcome.result.outputs["graders-result"].stage, "graders");
  } finally {
    await worker.close();
  }
});
