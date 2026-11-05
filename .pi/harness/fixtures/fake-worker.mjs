#!/usr/bin/env node
import readline from "node:readline";
const rl = readline.createInterface({ input: process.stdin });
rl.on("line", (line) => {
  const request = JSON.parse(line);
  const result = request.method === "cwd"
    ? { cwd: process.cwd() }
    : request.method === "submit-approved-slice"
      ? { operation: "fresh-generation", operationId: request.params.requestId, taskId: 7 }
      : { status: "completed", result: { outputs: { "grade-result": { stage: "grade" } } } };
  process.stdout.write(JSON.stringify({ id: request.id, ok: true, result }) + "\n");
});
