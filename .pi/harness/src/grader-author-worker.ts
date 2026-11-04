import { cp, mkdir, mkdtemp, readFile, readdir, realpath, rm, stat, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { getModel } from "@earendil-works/pi-ai/compat";
import { createAgentSession, createBashToolDefinition, createExtensionRuntime, getAgentDir, ModelRuntime, SessionManager, SettingsManager, type ResourceLoader } from "@earendil-works/pi-coding-agent";
import { openBuilderRoom } from "./builder-room.ts";
import { executionBackend, type ExecutionBackend } from "./execution-backend.ts";
import { sealCycleOracleBundle, validateCycleOracleBundle } from "./cycle-oracle-bundle.ts";

type Request = {
  backend: ExecutionBackend; cutId: string; cutsRoot: string; projectId: string;
  slice: string; itemIds: string[]; prompt: string; outputRoot: string;
  bindingDigest: string; sessionsRoot: string;
  harnessSnapshotDigest: string; selectionDigest: string;
};

const request = JSON.parse(await readStdin()) as Request;
let room: Awaited<ReturnType<typeof openBuilderRoom>> | undefined;
let restrictedRoot: string | undefined;
try {
  validateRequest(request);
  const existing = await reusableResult(request).catch(() => undefined);
  if (existing) {
    process.stdout.write(JSON.stringify({ ok: true, result: existing }) + "\n");
  } else {
    const cutRoot = path.join(await realpath(request.cutsRoot), request.cutId);
    restrictedRoot = await mkdtemp(path.join(os.tmpdir(), "loyly-grader-contract-"));
    const restrictedCut = path.join(restrictedRoot, "contract");
    await mkdir(path.join(restrictedCut, "public-protocol"), { recursive: true });
    await cp(path.join(cutRoot, "docs", "plan", request.slice, "spec.feature"), path.join(restrictedCut, "spec.feature"));
    await cp(path.join(cutRoot, "model", request.projectId, "fixtures", "oracle-fixture-server.mjs"), path.join(restrictedCut, "public-protocol", "fixture-server.mjs"));
    await copyIfPresent(path.join(cutRoot, "model", request.projectId, "affordances.yaml"), path.join(restrictedCut, "affordances.yaml"));
    await copyIfPresent(path.join(cutRoot, "model", request.projectId, "runtime-evidence-contract.json"), path.join(restrictedCut, "runtime-evidence-contract.json"));
    await writeFile(path.join(restrictedCut, "selection.json"), JSON.stringify({ slice: request.slice, itemIds: request.itemIds }, null, 2) + "\n");

    room = await openBuilderRoom({
      backend: executionBackend(request.backend), cutsRoot: restrictedRoot, cutId: "contract",
      scope: ["grader"], targetStack: "typescript",
    });
    const sessionId = `grader-${request.bindingDigest.replace(/^sha256:/, "").slice(0, 24)}`;
    const manager = SessionManager.create(room.workspaceRoot, request.sessionsRoot, { id: sessionId });
    const bash = createBashToolDefinition("/workspace", {
      operations: { exec: async (command, _cwd, options) => {
        const result = await room!.exec(command, { signal: options.signal, timeout: options.timeout });
        if (result.stdout) options.onData(Buffer.from(result.stdout));
        if (result.stderr) options.onData(Buffer.from(result.stderr));
        return { exitCode: result.exitCode };
      } }, exposeSessionEnvironment: false,
    });
    const resourceLoader: ResourceLoader = {
      getExtensions: () => ({ extensions: [], errors: [], runtime: createExtensionRuntime() }),
      getSkills: () => ({ skills: [], diagnostics: [] }),
      getPrompts: () => ({ prompts: [], diagnostics: [] }),
      getThemes: () => ({ themes: [], diagnostics: [] }),
      getAgentsFiles: () => ({ agentsFiles: [] }),
      getSystemPrompt: () => "You are an independent black-box grader author. Read only the observable contract under $HARNESS_CUT_ROOT. Write only grader assets under $HARNESS_WORKSPACE_ROOT/grader. You cannot inspect any candidate implementation, builder transcript, verifier, prior verdict, or host credential.",
      getSystemPromptSource: () => undefined, getAppendSystemPrompt: () => [],
      getAppendSystemPromptSources: () => [], extendResources: () => {}, reload: async () => {},
    };
    const agentDir = getAgentDir();
    const modelRuntime = await ModelRuntime.create({
      authPath: process.env.PI_AUTH_PATH ?? `${agentDir}/auth.json`,
      modelsPath: process.env.PI_MODELS_PATH ?? `${agentDir}/models.json`,
    });
    const model = getModel("openai-codex", process.env.PI_GRADER_MODEL ?? process.env.PI_BUILDER_MODEL ?? "gpt-5.6-luna");
    if (!model) throw new Error("Codex grader-author model is unavailable");
    const { session } = await createAgentSession({
      cwd: room.workspaceRoot, model, modelRuntime, resourceLoader, sessionManager: manager,
      settingsManager: SettingsManager.inMemory({ compaction: { enabled: false } }),
      tools: ["bash"], customTools: [bash],
    });
    try { await session.prompt(request.prompt); } finally { session.dispose(); }

    const authoredRoot = path.join(room.workspaceRoot, "grader");
    if (!(await readdir(authoredRoot)).includes("grade.mjs")) throw new Error("grader author produced no grade.mjs");
    await stat(path.join(authoredRoot, "grade.mjs"));
    await mkdir(path.dirname(request.outputRoot), { recursive: true });
    const applicability = await detectApplicability(restrictedCut, request);
    const validatorsRoot = path.resolve(path.dirname(new URL(import.meta.url).pathname), "../conformance");
    const sealed = await sealCycleOracleBundle({
      authoredRoot, validatorsRoot, outputRoot: request.outputRoot,
      bindingDigest: request.bindingDigest, harnessSnapshotDigest: request.harnessSnapshotDigest,
      selectionDigest: request.selectionDigest, sessionId, model: model.id, applicability,
    });
    const playwrightRoot = path.resolve(path.dirname(new URL(import.meta.url).pathname), "../node_modules/playwright-core");
    await mkdir(path.join(request.outputRoot, "node_modules"), { recursive: true });
    await cp(playwrightRoot, path.join(request.outputRoot, "node_modules", "playwright-core"), { recursive: true, force: true });
    process.stdout.write(JSON.stringify({ ok: true, result: { ...sealed, reused: false } }) + "\n");
  }
} catch (error) {
  process.stdout.write(JSON.stringify({ ok: false, error: error instanceof Error ? error.message : String(error) }) + "\n");
  process.exitCode = 1;
} finally {
  await room?.close();
  if (restrictedRoot) await rm(restrictedRoot, { recursive: true, force: true });
}

async function reusableResult(value: Request) {
  const manifest = await validateCycleOracleBundle(value.outputRoot, value.bindingDigest);
  if (manifest.harnessSnapshotDigest !== value.harnessSnapshotDigest || manifest.selectionDigest !== value.selectionDigest) {
    throw new Error("sealed cycle oracle contract changed");
  }
  return { ...manifest, reused: true };
}

function validateRequest(value: Request) {
  if (!/^[a-f0-9]{40}$/.test(value.cutId)) throw new Error("grader author requires a full cut identity");
  if (!/^[A-Za-z0-9._-]+$/.test(value.projectId)) throw new Error("invalid grader project identity");
  if (!/^[A-Za-z0-9._-]+$/.test(value.slice)) throw new Error("invalid grader slice identity");
  if (!value.itemIds.length || !value.itemIds.every((id) => /^@item-[0-9]+-[0-9]+$/.test(id))) throw new Error("invalid grader item identities");
  if (!value.prompt || !value.bindingDigest.startsWith("sha256:") ||
      !value.harnessSnapshotDigest.startsWith("sha256:") || !value.selectionDigest.startsWith("sha256:")) {
    throw new Error("grader author binding is incomplete");
  }
}

async function copyIfPresent(source: string, destination: string) {
  try { await stat(source); await cp(source, destination, { recursive: true }); } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
  }
}

async function detectApplicability(restrictedCut: string, value: Request) {
  const affordance = await readFile(path.join(restrictedCut, "affordances.yaml"), "utf8")
    .then((text) => text.includes(value.slice)).catch(() => false);
  const runtime = await readFile(path.join(restrictedCut, "runtime-evidence-contract.json"), "utf8")
    .then((text) => value.itemIds.some((item) => text.includes(item)))
    .catch(() => false);
  return { affordance, runtime };
}

function readStdin(): Promise<string> {
  return new Promise((resolve, reject) => {
    let value = ""; process.stdin.setEncoding("utf8");
    process.stdin.on("data", (chunk) => value += chunk);
    process.stdin.on("end", () => resolve(value.trim())); process.stdin.on("error", reject);
  });
}
