import assert from "node:assert/strict";
import test from "node:test";
import { OrcClient, spawnOrcClient } from "../src/orc-client.ts";

test("GEPA optimization pins a purpose and Bedrock reflection model", async () => {
  const calls = [];
  const client = new OrcClient(async (method, params) => {
    calls.push({ method, params });
    return { status: "completed" };
  });

  await client.optimizePrompt({
    purpose: "verification-repair",
    reflectionProvider: "bedrock",
    reflectionModel: "eu.amazon.nova-2-lite-v1:0",
    trainset: [{ task: "train" }],
    valset: [{ task: "validate" }],
  });

  assert.deepEqual(calls, [{
    method: "optimize-prompt",
    params: {
      purpose: "verification-repair",
      reflectionProvider: "bedrock",
      reflectionModel: "eu.amazon.nova-2-lite-v1:0",
      trainset: [{ task: "train" }],
      valset: [{ task: "validate" }],
    },
  }]);
});

test("Pi reads durable execution progress and builder sessions without mutation methods", async () => {
  const calls = [];
  const client = new OrcClient(async (method, params) => {
    calls.push({ method, params });
    return { status: "running" };
  });

  await client.executionStatus("00000000-0000-0000-0000-000000000037");
  await client.builderSession("builder-session-38");

  assert.deepEqual(calls, [
    {
      method: "execution-status",
      params: { tickId: "00000000-0000-0000-0000-000000000037" },
    },
    {
      method: "builder-session",
      params: { builderSessionRef: "builder-session-38" },
    },
  ]);
  assert.equal("appendBuilderSession" in client, false);
  assert.equal("promptBuilderSession" in client, false);
});

test("approved slices dispatch through one ORC operation selector", async () => {
  const calls = [];
  const client = new OrcClient(async (method, params) => {
    calls.push({ method, params });
    return { taskId: calls.length };
  });
  const request = {
    requestId: "run-1",
    selectionId: "selection-1",
    scope: ["impl"],
    targetStack: "typescript",
    executionBackend: "gondolin",
  };

  await client.submitApprovedSlice(request);
  await client.submitApprovedSlice(request);

  assert.deepEqual(calls.map(({ method }) => method), ["submit-approved-slice", "submit-approved-slice"]);
});

test("Pi explores immutable cut composition before previewing a selection", async () => {
  const calls = [];
  const client = new OrcClient(async (method, params) => {
    calls.push({ method, params });
    return { cut: params.cut, slices: [] };
  });

  await client.sliceComposition("cut-42");

  assert.deepEqual(calls, [{ method: "slice-composition", params: { cut: "cut-42" } }]);
});

test("Pi adapter submits and waits through the ORC worker", async () => {
  const worker = spawnOrcClient({
    projectRoot: new URL("../../..", import.meta.url).pathname,
    command: new URL("../fixtures/fake-worker.mjs", import.meta.url).pathname,
  });
  try {
    const receipt = await worker.client.submitApprovedSlice({
      requestId: `smoke-${Date.now()}`,
      cut: "cut-smoke",
      scope: ["workspace"],
      targetStack: "typescript",
      executionBackend: "gondolin",
    });
    assert.equal(receipt.taskId, 7);
    const outcome = await worker.client.wait(receipt.taskId);
    assert.equal(outcome.status, "completed");
    assert.equal(outcome.result.outputs["grade-result"].stage, "grade");
  } finally {
    await worker.close();
  }
});
