import { spawn } from "node:child_process";

export type ExecResult = { exitCode: number; stdout: string; stderr: string };

export function execOnHost(
  command: string,
  cwd: string,
  options: { signal?: AbortSignal; env?: NodeJS.ProcessEnv } = {},
): Promise<ExecResult> {
  return new Promise((resolve, reject) => {
    const child = spawn("/bin/sh", ["-lc", command], {
      cwd,
      env: options.env ?? process.env,
      signal: options.signal,
      stdio: ["ignore", "pipe", "pipe"],
    });
    let stdout = "";
    let stderr = "";
    child.stdout.setEncoding("utf8").on("data", (chunk) => stdout += chunk);
    child.stderr.setEncoding("utf8").on("data", (chunk) => stderr += chunk);
    child.once("error", reject);
    child.once("close", (code, signal) => {
      resolve({
        exitCode: code ?? (signal ? 128 : 1),
        stdout,
        stderr: signal ? `${stderr}${stderr ? "\n" : ""}terminated by ${signal}` : stderr,
      });
    });
  });
}
