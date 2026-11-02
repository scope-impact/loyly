#!/usr/bin/env node
import readline from "node:readline";

const rl = readline.createInterface({ input: process.stdin });
rl.on("line", (line) => {
  const request = JSON.parse(line);
  const result = request.method === "preview-selection"
    ? {
        selectionId: "selection-1",
        selectionDigest: "sha256:test-selection",
        status: "awaiting-approval",
        cut: request.params.cut,
        slice: request.params.slice,
        items: [{ id: "@item-01-001", name: "A selected scenario", rule: "A rule" }],
      }
    : request.method === "approve-selection"
      ? {
          selectionId: request.params.selectionId,
          selectionDigest: request.params.selectionDigest,
          status: "approved",
        }
      : { cycleId: request.params.requestId, taskId: 7 };
  process.stdout.write(JSON.stringify({ id: request.id, ok: true, result }) + "\n");
});
