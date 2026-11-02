import assert from "node:assert/strict";
import test from "node:test";
import { spawnOrcClient } from "../src/orc-client.ts";

test("Pi adapter requires an approved slice selection before regeneration", async () => {
  const worker = spawnOrcClient({
    projectRoot: new URL("../../..", import.meta.url).pathname,
    command: new URL("../fixtures/fake-selection-worker.mjs", import.meta.url).pathname,
  });
  try {
    const selection = await worker.client.previewSelection("cut-smoke", "01-booking-core");
    assert.equal(selection.status, "awaiting-approval");
    assert.equal(selection.items[0].id, "@item-01-001");

    const approved = await worker.client.approveSelection(
      selection.selectionId,
      selection.selectionDigest,
    );
    assert.equal(approved.status, "approved");

    const receipt = await worker.client.submit({
      requestId: `selection-${Date.now()}`,
      selectionId: approved.selectionId,
      scope: ["workspace"],
      targetStack: "typescript",
      executionBackend: "gondolin",
    });
    assert.equal(receipt.taskId, 7);
  } finally {
    await worker.close();
  }
});
