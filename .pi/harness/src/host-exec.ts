import { spawn } from "node:child_process";

export type ExecResult = { exitCode: number; stdout: string; stderr: string };

/**
 * Run one shell command in its own process group. The command is over when its shell exits
 * (or it is aborted); then the whole group is killed, so no process it started outlives it or
 * holds its output pipes open. A process left in the background would otherwise keep the
 * caller waiting forever.
 */
export function execOnHost(
  command: string,
  cwd: string,
  options: { signal?: AbortSignal; env?: NodeJS.ProcessEnv } = {},
): Promise<ExecResult> {
  return new Promise((resolve, reject) => {
    const child = spawn("/bin/sh", ["-lc", command], {
      cwd,
      env: options.env ?? process.env,
      detached: true,
      stdio: ["ignore", "pipe", "pipe"],
    });
    const killGroup = () => {
      if (child.pid === undefined) return;
      try { process.kill(-child.pid, "SIGKILL"); } catch { /* the group is already gone */ }
    };
    const onAbort = () => {
      killGroup();
      reject(Object.assign(new Error("The operation was aborted"), { name: "AbortError" }));
    };
    if (options.signal?.aborted) return onAbort();
    options.signal?.addEventListener("abort", onAbort, { once: true });
    let stdout = "";
    let stderr = "";
    child.stdout.setEncoding("utf8").on("data", (chunk) => stdout += chunk);
    child.stderr.setEncoding("utf8").on("data", (chunk) => stderr += chunk);
    child.once("error", (error) => {
      options.signal?.removeEventListener("abort", onAbort);
      killGroup();
      reject(error);
    });
    child.once("exit", (code, signal) => {
      options.signal?.removeEventListener("abort", onAbort);
      // Killing the group closes every writer of the pipes, so "close" follows with all the
      // output already written; the timer only guards a writer that escaped the group.
      killGroup();
      const finish = () => {
        clearTimeout(guard);
        child.stdout.destroy();
        child.stderr.destroy();
        resolve({
          exitCode: code ?? (signal ? 128 : 1),
          stdout,
          stderr: signal ? `${stderr}${stderr ? "\n" : ""}terminated by ${signal}` : stderr,
        });
      };
      const guard = setTimeout(finish, 2000);
      child.once("close", finish);
    });
  });
}
