import { executionBackend, type ExecutionBackend } from "./execution-backend.ts";
import { openGraderRoom } from "./grader-room.ts";
import { persistCommandEvidence } from "./command-evidence.ts";

type Request = {
  backend: ExecutionBackend;
  cycleId: string;
  cutId: string;
  outputRoot: string;
  cutsRoot: string;
  evidenceRoot: string;
  evidenceNamespace: string;
  graderAssetsRoot?: string;
  observabilityAssetsRoot?: string;
  runtimeImagesRoot?: string;
  commands: Array<{ name: string; command: string }>;
};

const request = JSON.parse(await readStdin()) as Request;
const room = await openGraderRoom({ ...request, backend: executionBackend(request.backend) });
try {
  const results: Record<string, unknown> = {};
  for (const item of request.commands) {
    const result = await run(room, item.command);
    await persistCommandEvidence(room.evidenceRoot, request.evidenceNamespace, item.name, result);
    results[item.name] = result;
  }
  process.stdout.write(JSON.stringify({ ok: true, result: results }) + "\n");
} catch (error) {
  process.stdout.write(JSON.stringify({ ok: false, error: error instanceof Error ? error.message : String(error) }) + "\n");
  process.exitCode = 1;
} finally {
  await room.close();
}

async function run(room: Awaited<ReturnType<typeof openGraderRoom>>, command: string) {
  try {
    const result = await room.exec(command) as unknown as {
      exitCode: number;
      stdout?: string;
      stderr?: string;
      _stdout?: Buffer;
      _stderr?: Buffer;
    };
    return {
      exitCode: result.exitCode,
      stdout: result.stdout ?? result._stdout?.toString("utf8") ?? "",
      stderr: result.stderr ?? result._stderr?.toString("utf8") ?? "",
    };
  } catch (error) {
    return { exitCode: 1, stdout: "", stderr: error instanceof Error ? error.message : String(error) };
  }
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
