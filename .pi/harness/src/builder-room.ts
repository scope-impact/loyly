import { cp, lstat, mkdir, mkdtemp, realpath, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { executionBackend, type ExecutionBackend } from "./execution-backend.ts";
import { ReadonlyProvider, RealFSProvider, ShadowProvider, VM } from "./gondolin.ts";
import { execOnHost } from "./host-exec.ts";

export type BuilderRoom = {
  exec(command: string, options?: { signal?: AbortSignal }): Promise<{ exitCode: number; stdout: string; stderr: string }>;
  workspaceRoot: string;
  close(): Promise<void>;
};

type Vm = BuilderRoom & { close(): Promise<void> };

export async function openBuilderRoom(options: {
  backend: ExecutionBackend;
  cutsRoot: string;
  cutId: string;
  scope: readonly string[];
  targetStack?: string;
  initialWorkspaceRoot?: string;
  evidenceRoot?: string;
  createVm?: (options: Record<string, unknown>) => Promise<Vm>;
}): Promise<BuilderRoom> {
  const backend = executionBackend(options.backend);
  validateScope(options.scope);
  const cutRoot = path.join(await realpath(options.cutsRoot), options.cutId);
  if (!(await lstat(cutRoot)).isDirectory()) throw new Error(`unknown cut: ${options.cutId}`);
  const workspaceRoot = await mkdtemp(path.join(os.tmpdir(), "pi-harness-builder-"));
  for (const entry of options.scope) {
    const destination = path.join(workspaceRoot, entry);
    if (options.initialWorkspaceRoot) {
      await cp(path.join(await realpath(options.initialWorkspaceRoot), entry), destination, {
        recursive: true,
        force: true,
      });
    } else {
      await mkdir(destination, { recursive: true });
    }
  }
  const evidenceRoot = options.evidenceRoot ? await realpath(options.evidenceRoot) : undefined;
  if (backend === "host") {
    return {
      workspaceRoot,
      exec: (command, execOptions) => execOnHost(command, workspaceRoot, {
        ...execOptions,
        env: {
          ...process.env,
          HARNESS_CUT_ROOT: cutRoot,
          HARNESS_WORKSPACE_ROOT: workspaceRoot,
          ...(evidenceRoot ? { HARNESS_VERIFICATION_EVIDENCE_ROOT: evidenceRoot } : {}),
        },
      }),
      close: () => rm(workspaceRoot, { recursive: true, force: true }),
    };
  }
  const allowed = options.scope.map((entry) => `/${entry.replace(/\\/g, "/")}`);
  const workspace = new ShadowProvider(new RealFSProvider(workspaceRoot), {
    shouldShadow: ({ path: candidate }) => !allowed.some((entry) =>
      entry === candidate ||
      candidate === "/" ||
      entry.startsWith(candidate.endsWith("/") ? candidate : `${candidate}/`) ||
      candidate.startsWith(`${entry}/`)),
    writeMode: "deny",
    denySymlinkBypass: true,
  });
  const createVm = options.createVm ?? (VM.create as unknown as typeof options.createVm);
  if (!createVm) throw new Error("Gondolin VM factory is unavailable");
  const assetsRoot = path.resolve(path.dirname(new URL(import.meta.url).pathname), "../.state/assets");
  const targetStack = options.targetStack ?? "typescript";
  const imageDirectory = builderImageDirectory(targetStack);
  const runtimeEnvironment = builderRuntimeEnvironment(targetStack);
  const mounts: Record<string, unknown> = {
    "/cut": new ReadonlyProvider(new RealFSProvider(cutRoot)),
    "/workspace": workspace,
  };
  if (evidenceRoot) mounts["/evidence"] = new ReadonlyProvider(new RealFSProvider(evidenceRoot));
  const vm = await createVm({
    dns: { mode: "synthetic" },
    allowWebSockets: false,
    httpHooks: { isRequestAllowed: () => false },
    vfs: {
      mounts,
    },
    sandbox: { imagePath: path.join(assetsRoot, imageDirectory) },
  });
  return {
    exec: (command, execOptions) => vm.exec(
      `export HARNESS_CUT_ROOT=/cut HARNESS_WORKSPACE_ROOT=/workspace${evidenceRoot ? " HARNESS_VERIFICATION_EVIDENCE_ROOT=/evidence" : ""} ${runtimeEnvironment}; ${command}`,
      execOptions,
    ),
    workspaceRoot,
    async close() {
      try { await vm.close(); } finally { await rm(workspaceRoot, { recursive: true, force: true }); }
    },
  };
}

function builderRuntimeEnvironment(targetStack: string): string {
  const stack = targetStack.toLowerCase();
  if (stack.includes("rust")) {
    return "PATH=/usr/local/cargo/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin RUSTUP_HOME=/usr/local/rustup CARGO_HOME=/usr/local/cargo";
  }
  if (stack.includes("java")) {
    return "PATH=/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin JAVA_HOME=/opt/java/openjdk";
  }
  if (stack.includes("rails") || stack.includes("ruby")) {
    return "PATH=/usr/local/bundle/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin GEM_HOME=/usr/local/bundle BUNDLE_APP_CONFIG=/usr/local/bundle BUNDLE_SILENCE_ROOT_WARNING=1";
  }
  return "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";
}

function builderImageDirectory(targetStack: string): string {
  const stack = targetStack.toLowerCase();
  if (stack.includes("rust")) return "builder-rust";
  if (stack.includes("java")) return "builder-java";
  if (stack.includes("rails") || stack.includes("ruby")) return "builder-rails";
  if (stack.includes("node") || stack.includes("typescript") || stack.includes("javascript")) return "builder";
  throw new Error(`unsupported Gondolin builder stack: ${targetStack}`);
}

function validateScope(scope: readonly string[]): void {
  if (scope.length === 0) throw new Error("builder scope must not be empty");
  const normalized = scope.map((entry) => {
    if (!entry || path.isAbsolute(entry) || entry.split(/[\\/]/).includes("..")) throw new Error(`invalid builder scope: ${entry}`);
    return entry.replace(/\\/g, "/").replace(/\/+$/, "");
  });
  for (const entry of normalized) {
    for (const other of normalized) {
      if (entry !== other && (entry.startsWith(`${other}/`) || other.startsWith(`${entry}/`))) {
        throw new Error(`builder scope entries overlap: ${entry}, ${other}`);
      }
    }
  }
}
