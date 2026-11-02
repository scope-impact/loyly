import { cp, mkdir, readdir } from "node:fs/promises";
import { getModel } from "@earendil-works/pi-ai/compat";
import { createAgentSession, createBashToolDefinition, createExtensionRuntime, getAgentDir, ModelRuntime, SessionManager, SettingsManager, type ResourceLoader } from "@earendil-works/pi-coding-agent";
import { openBuilderRoom } from "./builder-room.ts";
import { executionBackend, type ExecutionBackend } from "./execution-backend.ts";

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
};

const room = await openBuilderRoom({
  backend: executionBackend(request.backend),
  cutsRoot: request.cutsRoot,
  cutId: request.cutId,
  scope: request.scope,
  targetStack: request.targetStack,
  initialWorkspaceRoot: request.initialWorkspaceRoot,
  evidenceRoot: request.verificationEvidenceRoot,
});
try {
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
  let toolCalls = 0;
  const sessionManager = SessionManager.inMemory(room.workspaceRoot);
  const builderSessionId = sessionManager.getSessionId();
  const { session } = await createAgentSession({
    cwd: room.workspaceRoot,
    model,
    modelRuntime,
    resourceLoader,
    sessionManager,
    settingsManager: SettingsManager.inMemory({ compaction: { enabled: false } }),
    tools: ["bash"],
    customTools: [bash],
  });
  session.subscribe((event) => {
    if (event.type === "tool_execution_start") toolCalls++;
  });
  try {
    await session.prompt(request.prompt);
  } finally {
    session.dispose();
  }
  const generated = await readdir(room.workspaceRoot);
  if (toolCalls === 0) throw new Error("Codex builder completed without executing a workspace tool");
  if (generated.length === 0) throw new Error("Codex builder produced no workspace output");
  await mkdir(request.outputRoot, { recursive: true });
  await cp(room.workspaceRoot, request.outputRoot, { recursive: true, force: true });
  process.stdout.write(JSON.stringify({
    ok: true,
    result: { outputRoot: request.outputRoot, builderSessionId, toolCalls, generated },
  }) + "\n");
} catch (error) {
  process.stdout.write(JSON.stringify({ ok: false, error: error instanceof Error ? error.message : String(error) }) + "\n");
  process.exitCode = 1;
} finally {
  await room.close();
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
