import { spawnOrcClient } from "./src/orc-client.ts";

const promptExampleSchema = {
  type: "object",
  properties: {
    task: { type: "string" },
    "request-id": { type: "string" },
    selection: {
      type: "object",
      properties: {
        selectionId: { type: "string" },
        selectionDigest: { type: "string" },
        status: { type: "string", const: "approved" },
        projectId: { type: "string" },
        containerId: { type: "string" },
        componentId: { type: "string" },
        cut: { type: "string" },
        slice: { type: "string" },
        items: {
          type: "array",
          minItems: 1,
          items: {
            type: "object",
            properties: {
              id: { type: "string" },
              name: { type: "string" },
              rule: { type: "string" },
              source: { type: "string" },
            },
            required: ["id", "name", "rule", "source"],
            additionalProperties: false,
          },
        },
      },
      required: ["selectionId", "selectionDigest", "status", "projectId", "containerId", "componentId", "cut", "slice", "items"],
      additionalProperties: false,
    },
    scope: { type: "array", minItems: 1, items: { type: "string" } },
    "target-stack": { type: "string" },
    "execution-backend": { type: "string", const: "gondolin" },
  },
  required: ["task", "request-id", "selection", "scope", "execution-backend"],
  additionalProperties: false,
} as const;

export default function register(pi: {
  registerTool(tool: Record<string, unknown>): void;
  on(event: string, handler: () => void | Promise<void>): void;
}) {
  const projectRoot = process.cwd();
  let worker: ReturnType<typeof spawnOrcClient> | undefined;
  const getWorker = () => worker ??= spawnOrcClient({ projectRoot });

  pi.registerTool({
    name: "prepare_regeneration_cut",
    description: "Prepare a pinned, fenced regeneration cut on the host or in a deny-egress Gondolin VM.",
    parameters: {
      type: "object",
      properties: { sourceCommit: { type: "string" }, executionBackend: { type: "string", enum: ["host", "gondolin"] } },
      required: ["sourceCommit", "executionBackend"],
      additionalProperties: false,
    },
    execute: async (_toolCallId: string, args: { sourceCommit: string; executionBackend: "host" | "gondolin" }, signal: AbortSignal) => {
      const receipt = await getWorker().client.prepareCut(args.sourceCommit, args.executionBackend);
      const outcome = await getWorker().client.wait(receipt.taskId);
      if (outcome.status !== "completed") throw new Error(outcome.error ?? "cut preparation failed");
      const result = outcome.result?.outputs?.["cut-result"];
      return { content: [{ type: "text", text: JSON.stringify(result) }], details: result };
    },
  });

  pi.registerTool({
    name: "optimize_builder_prompt",
    description: "Run the ORC GEPA optimizer against approved project regeneration selections using independent black-box scores.",
    parameters: {
      type: "object",
      properties: {
        trainset: { type: "array", minItems: 1, items: promptExampleSchema },
        valset: { type: "array", minItems: 1, items: promptExampleSchema },
      },
      required: ["trainset", "valset"],
      additionalProperties: false,
    },
    execute: async (_toolCallId: string, args: { trainset: unknown[]; valset: unknown[] }) => {
      const result = await getWorker().client.optimizePrompt(args.trainset, args.valset);
      return { content: [{ type: "text", text: JSON.stringify(result) }], details: result };
    },
  });

  pi.registerTool({
    name: "start_builder_prompt_optimization",
    description: "Start a calibrated GEPA optimization in external-proposer mode. Returns the independently scored seed prompt and evidence for this Pi/Codex session to improve.",
    parameters: {
      type: "object",
      properties: {
        trainset: { type: "array", minItems: 1, items: promptExampleSchema },
        valset: { type: "array", minItems: 1, items: promptExampleSchema },
      },
      required: ["trainset", "valset"],
      additionalProperties: false,
    },
    execute: async (_toolCallId: string, args: { trainset: unknown[]; valset: unknown[] }) => {
      const result = await getWorker().client.startExternalPromptOptimization(args.trainset, args.valset);
      return { content: [{ type: "text", text: JSON.stringify(result) }], details: result };
    },
  });

  pi.registerTool({
    name: "submit_builder_prompt_candidate",
    description: "Submit a prompt authored by this active Pi/Codex session as a durable GEPA candidate. ORC runs the calibrated builder, Gondolin verifier, and independent black-box grader, then returns score and evidence.",
    parameters: {
      type: "object",
      properties: {
        optimizationId: { type: "string" },
        prompt: { type: "string", minLength: 1 },
        proposer: { type: "string", const: "pi-codex" },
      },
      required: ["optimizationId", "prompt", "proposer"],
      additionalProperties: false,
    },
    execute: async (_toolCallId: string, args: { optimizationId: string; prompt: string; proposer: "pi-codex" }) => {
      const result = await getWorker().client.submitExternalPromptCandidate(args.optimizationId, args.prompt, args.proposer);
      return { content: [{ type: "text", text: JSON.stringify(result) }], details: result };
    },
  });

  pi.registerTool({
    name: "preview_slice_selection",
    description: "Resolve a deterministic slice contract for this Pi session to diagram and confirm.",
    parameters: {
      type: "object",
      properties: {
        cut: { type: "string" },
        slice: { type: "string" },
        itemIds: { type: "array", items: { type: "string" } },
      },
      required: ["cut", "slice"],
      additionalProperties: false,
    },
    execute: async (_id: string, args: { cut: string; slice: string; itemIds?: string[] }) => {
      const result = await getWorker().client.previewSelection(args.cut, args.slice, args.itemIds);
      return { content: [{ type: "text", text: JSON.stringify(result) }], details: result };
    },
  });

  pi.registerTool({
    name: "approve_slice_selection",
    description: "Approve the reviewed selection by identity and digest.",
    parameters: { type: "object", properties: { selectionId: { type: "string" }, selectionDigest: { type: "string" } }, required: ["selectionId", "selectionDigest"], additionalProperties: false },
    execute: async (_id: string, args: { selectionId: string; selectionDigest: string }) => {
      const result = await getWorker().client.approveSelection(args.selectionId, args.selectionDigest);
      return { content: [{ type: "text", text: JSON.stringify(result) }], details: result };
    },
  });

  pi.registerTool({
    name: "submit_regeneration_cycle",
    description: "Submit one idempotent regeneration cycle on the host or in Gondolin for an approved slice selection.",
    parameters: {
      type: "object",
      properties: {
        requestId: { type: "string" },
        selectionId: { type: "string" },
        scope: { type: "array", items: { type: "string" } },
        targetStack: { type: "string", minLength: 1 },
        executionBackend: { type: "string", enum: ["host", "gondolin"] },
      },
      required: ["requestId", "selectionId", "scope", "targetStack", "executionBackend"],
      additionalProperties: false,
    },
    execute: async (_toolCallId: string, args: { requestId: string; selectionId: string; scope: string[]; targetStack: string; executionBackend: "host" | "gondolin" }) => {
      const result = await getWorker().client.submit(args);
      return { content: [{ type: "text", text: JSON.stringify(result) }], details: result };
    },
  });

  pi.registerTool({
    name: "wait_regeneration_cycle",
    description: "Wait for a durable ORC regeneration cycle task.",
    parameters: {
      type: "object",
      properties: { taskId: { type: "number" } },
      required: ["taskId"],
      additionalProperties: false,
    },
    execute: async (_toolCallId: string, args: { taskId: number }) => {
      const result = await getWorker().client.wait(args.taskId);
      return { content: [{ type: "text", text: JSON.stringify(result) }], details: result };
    },
  });

  pi.registerTool({
    name: "get_regeneration_cycle_status",
    description: "Reconcile a progress view from ORC's durable tick state and the harness stage projection. This read never advances the workflow.",
    parameters: {
      type: "object",
      properties: { tickId: { type: "string" } },
      required: ["tickId"],
      additionalProperties: false,
    },
    execute: async (_toolCallId: string, args: { tickId: string }) => {
      const result = await getWorker().client.executionStatus(args.tickId);
      return { content: [{ type: "text", text: JSON.stringify(result) }], details: result };
    },
  });

  pi.registerTool({
    name: "inspect_builder_session",
    description: "Inspect committed turns, tool calls, generated workspace entries, and progress for one builder session. This interface is read-only.",
    parameters: {
      type: "object",
      properties: { builderSessionRef: { type: "string" } },
      required: ["builderSessionRef"],
      additionalProperties: false,
    },
    execute: async (_toolCallId: string, args: { builderSessionRef: string }) => {
      const result = await getWorker().client.builderSession(args.builderSessionRef);
      return { content: [{ type: "text", text: JSON.stringify(result) }], details: result };
    },
  });

  pi.on("shutdown", async () => {
    await worker?.close();
    worker = undefined;
  });
}
