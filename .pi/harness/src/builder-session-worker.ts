import { readBuilderSession } from "./builder-session-store.ts";

const request = JSON.parse(await readStdin()) as {
  builderSessionsRoot: string;
  builderSessionRef: string;
};

try {
  const result = await readBuilderSession(request.builderSessionsRoot, request.builderSessionRef);
  process.stdout.write(`${JSON.stringify({ ok: true, result })}\n`);
} catch (error) {
  process.stdout.write(`${JSON.stringify({
    ok: false,
    error: error instanceof Error ? error.message : String(error),
  })}\n`);
  process.exitCode = 1;
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
