import { execFileSync } from "node:child_process";
import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";

import { spawnOrcClient } from "../src/orc-client.ts";

const projectRoot = path.resolve(import.meta.dirname, "../../..");
const runId = `four-language-${new Date().toISOString().replace(/[:.]/g, "-")}`;
const runRoot = path.join(projectRoot, ".pi/harness/.state/comparisons", runId);
await mkdir(runRoot, { recursive: true });

const stacks = [
  { id: "typescript", label: "TypeScript / Node 22" },
  { id: "rust", label: "Rust 1.91" },
  { id: "java", label: "Java 21" },
  { id: "rails", label: "Rails 8 / Ruby" },
];
const run = {
  schemaVersion: 1,
  runId,
  startedAt: new Date().toISOString(),
  sourceCommit: execFileSync("git", ["rev-parse", "HEAD"], { cwd: projectRoot, encoding: "utf8" }).trim(),
  slice: "01-booking-core",
  expectedItems: 11,
  stacks: [],
};

const worker = spawnOrcClient({ projectRoot });
try {
  console.log(`[${runId}] preparing clean-room cut ${run.sourceCommit}`);
  const cutReceipt = await worker.client.prepareCut(run.sourceCommit, "host");
  const cutOutcome = await worker.client.wait(cutReceipt.taskId);
  if (cutOutcome.status !== "completed") throw new Error(cutOutcome.error ?? "cut preparation failed");
  const cut = cutOutcome.result?.outputs?.["cut-result"];
  if (!cut?.cutId) throw new Error("cut preparation returned no cut identity");
  run.cut = cut;

  let selection = await worker.client.previewSelection(cut.cutId, run.slice);
  if (selection.status !== "approved") {
    selection = await worker.client.approveSelection(selection.selectionId, selection.selectionDigest);
  }
  if (selection.items?.length !== run.expectedItems) {
    throw new Error(`expected ${run.expectedItems} whole-app items, found ${selection.items?.length ?? 0}`);
  }
  run.selection = selection;
  await persist();

  for (const stack of stacks) {
    const requestId = `${runId}-${stack.id}`;
    const startedAt = new Date().toISOString();
    console.log(`[${runId}] regenerating whole app as ${stack.label}`);
    const receipt = await worker.client.submitApprovedSlice({
      requestId,
      selectionId: selection.selectionId,
      scope: ["impl"],
      targetStack: stack.id,
      executionBackend: "gondolin",
    });
    const outcome = await worker.client.wait(receipt.taskId);
    const entry = {
      ...stack,
      requestId,
      startedAt,
      finishedAt: new Date().toISOString(),
      receipt,
      outcome,
    };
    run.stacks.push(entry);
    await persist();
    console.log(`[${runId}] ${stack.label}: ${outcome.status}`);
  }
} finally {
  run.finishedAt = new Date().toISOString();
  await persist();
  await worker.close();
}

console.log(JSON.stringify({ runRoot, results: path.join(runRoot, "results.json") }));

async function persist() {
  await writeFile(path.join(runRoot, "results.json"), `${JSON.stringify(run, null, 2)}\n`);
}
