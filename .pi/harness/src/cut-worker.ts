import { createCutPreparer } from "./cut.ts";
import { executionBackend, type ExecutionBackend } from "./execution-backend.ts";

const line = await new Promise<string>((resolve, reject) => {
  let value = "";
  process.stdin.setEncoding("utf8");
  process.stdin.on("data", (chunk) => value += chunk);
  process.stdin.on("end", () => resolve(value.trim()));
  process.stdin.on("error", reject);
});

try {
  const request = JSON.parse(line) as { sourceCommit: string; backend: ExecutionBackend };
  const sourceRoot = process.env.PI_HARNESS_SOURCE_ROOT ?? process.env.LOYLEY_SOURCE_ROOT
    ?? process.env.PI_HARNESS_PROJECT_ROOT ?? process.env.LOYLEY_PROJECT_ROOT;
  const policyRoot = process.env.PI_HARNESS_PROJECT_ROOT ?? process.env.LOYLEY_PROJECT_ROOT;
  const cutsRoot = process.env.PI_HARNESS_CUTS_ROOT ?? process.env.LOYLEY_CUTS_ROOT;
  if (!sourceRoot || !cutsRoot) throw new Error("cut adapter requires PI_HARNESS_SOURCE_ROOT and PI_HARNESS_CUTS_ROOT");
  const result = await createCutPreparer({
    backend: executionBackend(request.backend),
    sourceRoot,
    policyRoot,
    cutsRoot,
  })(request.sourceCommit);
  process.stdout.write(JSON.stringify({ ok: true, result }) + "\n");
} catch (error) {
  process.stdout.write(JSON.stringify({ ok: false, error: error instanceof Error ? error.message : String(error) }) + "\n");
  process.exitCode = 1;
}
