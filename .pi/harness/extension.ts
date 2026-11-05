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
    "initial-workspace-root": { type: "string" },
    "verification-evidence-root": { type: "string" },
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
    name: "prepare_source_cut",
    description: "Prepare a pinned, fenced source cut on the host or in a deny-egress Gondolin VM. A cut is neutral input for generation or regeneration.",
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
        purpose: { type: "string", enum: ["fresh-generation", "verification-repair"] },
        reflectionProvider: { type: "string", const: "bedrock" },
        reflectionModel: { type: "string", minLength: 1 },
        trainset: { type: "array", minItems: 1, items: promptExampleSchema },
        valset: { type: "array", minItems: 1, items: promptExampleSchema },
      },
      required: ["purpose", "reflectionProvider", "reflectionModel", "trainset", "valset"],
      additionalProperties: false,
    },
    execute: async (_toolCallId: string, args: {
      purpose: "fresh-generation" | "verification-repair";
      reflectionProvider: "bedrock";
      reflectionModel: string;
      trainset: unknown[];
      valset: unknown[];
    }) => {
      const result = await getWorker().client.optimizePrompt(args);
      return { content: [{ type: "text", text: JSON.stringify(result) }], details: result };
    },
  });

  pi.registerTool({
    name: "inspect_slice_composition",
    description: "List every existing slice and stable Gherkin item in an immutable cut so the active Pi session can explore operator intent. This read-only tool never proposes, records, approves, or executes a selection.",
    parameters: {
      type: "object",
      properties: { cut: { type: "string" } },
      required: ["cut"],
      additionalProperties: false,
    },
    execute: async (_id: string, args: { cut: string }) => {
      const result = await getWorker().client.sliceComposition(args.cut);
      return { content: [{ type: "text", text: JSON.stringify(result) }], details: result };
    },
  });

  pi.registerTool({
    name: "preview_slice_selection",
    description: "Deterministically resolve a slice and optional stable item subset proposed by the active Pi session. The result awaits exact operator approval and does not start ORC.",
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
    name: "submit_approved_slice",
    description: "Submit one operator-approved fixed execution contract directly to ORC. ORC durably owns the bounded generation or regeneration cycle; the Pi session remains exploratory and non-authoritative.",
    parameters: {
      type: "object",
      properties: {
        requestId: { type: "string" },
        selectionId: { type: "string" },
        scope: { type: "array", minItems: 1, items: { type: "string" } },
        targetStack: { type: "string", minLength: 1 },
        executionBackend: { type: "string", enum: ["host", "gondolin"] },
      },
      required: ["requestId", "selectionId", "scope", "targetStack", "executionBackend"],
      additionalProperties: false,
    },
    execute: async (_toolCallId: string, args: { requestId: string; selectionId: string; scope: string[]; targetStack: string; executionBackend: "host" | "gondolin" }) => {
      const result = await getWorker().client.submitApprovedSlice(args);
      return { content: [{ type: "text", text: JSON.stringify(result) }], details: result };
    },
  });

  pi.registerTool({
    name: "wait_harness_operation",
    description: "Wait for the ORC-selected generation or regeneration operation to finish.",
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
    name: "get_harness_operation_status",
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
