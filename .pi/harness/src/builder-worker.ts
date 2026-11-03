import { cp, mkdir, readdir } from "node:fs/promises";
import { getModel } from "@earendil-works/pi-ai/compat";
import { createAgentSession, createBashToolDefinition, createExtensionRuntime, getAgentDir, ModelRuntime, SessionManager, SettingsManager, type ResourceLoader } from "@earendil-works/pi-coding-agent";
import { openBuilderRoom } from "./builder-room.ts";
import { executionBackend, type ExecutionBackend } from "./execution-backend.ts";
import { writeBuilderSessionState } from "./builder-session-store.ts";

const request = JSON.parse(await readStdin()) as {
  backend: ExecutionBackend;
  cutId: string;
  scope: string[];
  prompt: string;
  cutsRoot: string;
  outputRoot: string;
  targetStack: string;
  initialWorkspaceRoot?: string;
  verificationEvidenceRoot?: string;
  builderSessionRef: string;
  builderSessionsRoot: string;
  attempt: number;
};

let toolCalls = 0;
let workspaceGeneration: string[] = [];
const builderSessionId = request.builderSessionRef;
let room: Awaited<ReturnType<typeof openBuilderRoom>> | undefined;
let sessionManager: SessionManager | undefined;
await writeBuilderSessionState(request.builderSessionsRoot, {
  builderSessionRef: builderSessionId,
  status: "running",
  toolCalls,
  workspaceGeneration,
  progress: { stage: "builder", attempt: request.attempt },
});
let stateWrites = Promise.resolve();
try {
  room = await openBuilderRoom({
    backend: executionBackend(request.backend),
    cutsRoot: request.cutsRoot,
    cutId: request.cutId,
    scope: request.scope,
    targetStack: request.targetStack,
    initialWorkspaceRoot: request.initialWorkspaceRoot,
    evidenceRoot: request.verificationEvidenceRoot,
  });
  const manager = SessionManager.create(
    room.workspaceRoot,
    request.builderSessionsRoot,
    { id: builderSessionId },
  );
  sessionManager = manager;
  const bash = createBashToolDefinition("/workspace", {
    operations: {
      exec: async (command, _cwd, options) => {
        const result = await room.exec(command, { signal: options.signal });
        if (result.stdout) options.onData(Buffer.from(result.stdout));
        if (result.stderr) options.onData(Buffer.from(result.stderr));
        return { exitCode: result.exitCode };
      },
    },
    exposeSessionEnvironment: false,
  });
  const resourceLoader: ResourceLoader = {
    getExtensions: () => ({ extensions: [], errors: [], runtime: createExtensionRuntime() }),
    getSkills: () => ({ skills: [], diagnostics: [] }),
    getPrompts: () => ({ prompts: [], diagnostics: [] }),
    getThemes: () => ({ themes: [], diagnostics: [] }),
    getAgentsFiles: () => ({ agentsFiles: [] }),
    getSystemPrompt: () => "You are a regeneration builder. Read only from $HARNESS_CUT_ROOT. Write generated code only under $HARNESS_WORKSPACE_ROOT. Do not access paths outside those roots.",
    getSystemPromptSource: () => undefined,
    getAppendSystemPrompt: () => [],
    getAppendSystemPromptSources: () => [],
    extendResources: () => {},
    reload: async () => {},
  };
  const agentDir = getAgentDir();
  const modelRuntime = await ModelRuntime.create({
    authPath: process.env.PI_AUTH_PATH ?? `${agentDir}/auth.json`,
    modelsPath: process.env.PI_MODELS_PATH ?? `${agentDir}/models.json`,
  });
  const model = getModel("openai-codex", process.env.PI_BUILDER_MODEL ?? "gpt-5.6-luna");
  if (!model) throw new Error("Codex builder model is unavailable");
  const { session } = await createAgentSession({
    cwd: room.workspaceRoot,
    model,
    modelRuntime,
    resourceLoader,
    sessionManager: manager,
    settingsManager: SettingsManager.inMemory({ compaction: { enabled: false } }),
    tools: ["bash"],
    customTools: [bash],
  });
  session.subscribe((event) => {
    if (event.type === "tool_execution_start") toolCalls++;
    stateWrites = stateWrites.then(() => writeBuilderSessionState(request.builderSessionsRoot, {
      builderSessionRef: builderSessionId,
      sessionFile: manager.getSessionFile(),
      status: "running",
      toolCalls,
      workspaceGeneration,
      progress: { stage: "builder", attempt: request.attempt },
    }));
  });
  try {
    await session.prompt(request.prompt);
  } finally {
    session.dispose();
  }
  await stateWrites;
  const generated = await readdir(room.workspaceRoot);
  workspaceGeneration = generated;
  if (toolCalls === 0) throw new Error("Codex builder completed without executing a workspace tool");
  if (generated.length === 0) throw new Error("Codex builder produced no workspace output");
  await mkdir(request.outputRoot, { recursive: true });
  await cp(room.workspaceRoot, request.outputRoot, { recursive: true, force: true });
  await writeBuilderSessionState(request.builderSessionsRoot, {
    builderSessionRef: builderSessionId,
    sessionFile: manager.getSessionFile(),
    status: "completed",
    toolCalls,
    workspaceGeneration,
    progress: { stage: "builder", attempt: request.attempt },
  });
  process.stdout.write(JSON.stringify({
    ok: true,
    result: { outputRoot: request.outputRoot, builderSessionId, toolCalls, generated },
  }) + "\n");
} catch (error) {
  await stateWrites.catch(() => undefined);
  await writeBuilderSessionState(request.builderSessionsRoot, {
    builderSessionRef: builderSessionId,
    sessionFile: sessionManager?.getSessionFile(),
    status: "failed",
    toolCalls,
    workspaceGeneration,
    progress: { stage: "builder", attempt: request.attempt },
  });
  process.stdout.write(JSON.stringify({ ok: false, error: error instanceof Error ? error.message : String(error) }) + "\n");
  process.exitCode = 1;
} finally {
  await room?.close();
}

function readStdin(): Promise<string> {
  return new Promise((resolve, reject) => {
    let value = "";
    process.stdin.setEncoding("utf8");
    process.stdin.on("data", (chunk) => value += chunk);
    process.stdin.on("end", () => resolve(value.trim()));
    process.stdin.on("error", reject);
  });
}
