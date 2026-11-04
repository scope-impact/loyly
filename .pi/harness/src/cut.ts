import { lstat, mkdir, mkdtemp, readFile, realpath, rename, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { executionBackend, type ExecutionBackend } from "./execution-backend.ts";
import { ReadonlyProvider, RealFSProvider, VM } from "./gondolin.ts";
import { execOnHost } from "./host-exec.ts";

type CutVm = {
  exec(command: string, options?: { signal?: AbortSignal }): Promise<{ exitCode: number; stdout: string; stderr: string }>;
  close(): Promise<void>;
};

type CutVmFactory = (options: Record<string, unknown>) => Promise<CutVm>;

export type CutReceipt = { cutId: string; treeId: string; sourceCommit: string };

export function createCutPreparer(options: {
  backend: ExecutionBackend;
  sourceRoot: string;
  cutsRoot: string;
  policyRoot?: string;
  createVm?: CutVmFactory;
}): (sourceCommit: string, signal?: AbortSignal) => Promise<CutReceipt> {
  return async (sourceCommit, signal) => {
    const backend = executionBackend(options.backend);
    if (!/^[a-f0-9]{40}$/.test(sourceCommit)) throw new Error("sourceCommit must be a full lowercase Git commit ID");
    const sourceRoot = await realpath(options.sourceRoot);
    const git = await lstat(path.join(sourceRoot, ".git"));
    if (!git.isDirectory()) throw new Error("cut source must be a normal Git checkout");
    const cutsRoot = path.resolve(options.cutsRoot);
    await mkdir(cutsRoot, { recursive: true });
    const resolvedCutsRoot = await realpath(cutsRoot);
    if (within(sourceRoot, resolvedCutsRoot)) throw new Error("cutsRoot must be outside the project source tree");

    const stagingRoot = await mkdtemp(path.join(resolvedCutsRoot, ".preparing-cut-"));
    let vm: CutVm | undefined;
    try {
      const hidden = await readCutPolicy(options.policyRoot ?? sourceRoot);
      let result: { exitCode: number; stdout: string; stderr: string };
      if (backend === "host") {
        const command = fenceCommand(sourceCommit, hidden)
          .replaceAll("/source", shellQuote(sourceRoot))
          .replaceAll("/output", shellQuote(stagingRoot));
        result = await execOnHost(command, sourceRoot, { signal });
      } else {
        const createVm = options.createVm ?? (VM.create as unknown as CutVmFactory);
        vm = await createVm({
          dns: { mode: "synthetic" },
          allowWebSockets: false,
          httpHooks: { isRequestAllowed: () => false },
          vfs: {
            mounts: {
              "/source": new ReadonlyProvider(new RealFSProvider(sourceRoot)),
              "/output": new RealFSProvider(stagingRoot),
            },
          },
          sandbox: { imagePath: path.resolve(path.dirname(new URL(import.meta.url).pathname), "../.state/assets/cut") },
        });
        result = await vm.exec(fenceCommand(sourceCommit, hidden), { signal });
      }
      if (result.exitCode !== 0) throw new Error(`cut preparation failed: ${result.stderr || result.stdout}`);
      const cutId = result.stdout.match(/CUT_COMMIT=([a-f0-9]{40})/)?.[1];
      const treeId = result.stdout.match(/CUT_TREE=([a-f0-9]{40})/)?.[1];
      if (!cutId || !treeId) throw new Error("cut preparation did not return valid pins");
      const manifest = { cutId, treeId, sourceCommit };
      const destination = path.join(resolvedCutsRoot, cutId);
      const manifestPath = path.join(resolvedCutsRoot, `${cutId}.json`);
      try {
        const existing = JSON.parse(await readFile(manifestPath, "utf8"));
        if (JSON.stringify(existing) !== JSON.stringify(manifest)) throw new Error(`cut identity collision: ${cutId}`);
        return manifest;
      } catch (error) {
        if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
      }
      await rename(path.join(stagingRoot, "cut"), destination);
      await writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, { mode: 0o600 });
      return manifest;
    } finally {
      try { await vm?.close(); } finally { await rm(stagingRoot, { recursive: true, force: true }); }
    }
  };
}

function shellQuote(value: string): string {
  return "'" + value.replaceAll("'", "'\\''") + "'";
}
function fenceCommand(sourceCommit: string, hidden: readonly string[]): string {
  return [
    "set -eu",
    // Trust the cut per invocation, never through a config file: git honours safe.directory
    // only from system, global, or command-line config, and GIT_CONFIG_* is command-line
    // scope. Writing ~/.gitconfig made concurrent cuts contend on its lock and leave entries.
    "export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=safe.directory GIT_CONFIG_VALUE_0=/output/cut",
    "git clone --quiet --depth 1 file:///source /output/cut",
    `test \"$(git -C /output/cut rev-parse HEAD)\" = '${sourceCommit}'`,
    "CUT_DATE=\"$(git -C /output/cut show -s --format=%cI HEAD)\"",
    "export GIT_AUTHOR_DATE=\"$CUT_DATE\" GIT_COMMITTER_DATE=\"$CUT_DATE\"",
    ...hidden.map((entry) => `test -e /output/cut/${entry} || { echo 'FENCE FAILED: ${entry} matches nothing' >&2; exit 1; }`),
    ...hidden.map((entry) => `rm -rf /output/cut/${entry}`),
    "git -C /output/cut add -A",
    "git -C /output/cut -c user.name=fence -c user.email=fence@localhost commit -q -m 'the fence: prior implementations and measurements removed'",
    "root=\"$(git -C /output/cut -c user.name=fence -c user.email=fence@localhost commit-tree \"$(git -C /output/cut rev-parse 'HEAD^{tree}')\" -m 'fenced clean room')\"",
    "git -C /output/cut reset -q --hard \"$root\"",
    "git -C /output/cut remote remove origin",
    "git -C /output/cut reflog expire --expire=now --all",
    "git -C /output/cut gc -q --prune=now --aggressive",
    "test \"$(git -C /output/cut rev-list --max-parents=0 --all | wc -l | tr -d ' ')\" = 1",
    "test \"$(git -C /output/cut rev-list --all --count)\" = 1",
    ...hidden.map((entry) => `test ! -e /output/cut/${entry}`),
    ...hidden.map((entry) => `! git -C /output/cut rev-list --objects --all | grep -qE '(^| )${escapeRegex(entry.replace(/\/$/, ""))}(/|$)'`),
    "printf 'CUT_COMMIT='; git -C /output/cut rev-parse --verify HEAD",
    "printf 'CUT_TREE='; git -C /output/cut rev-parse --verify 'HEAD^{tree}'",
  ].join(" && ");
}

function escapeRegex(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

async function readCutPolicy(sourceRoot: string): Promise<string[]> {
  const policyPath = path.join(sourceRoot, ".pi", "harness", "config", "cut-policy.json");
  const value = JSON.parse(await readFile(policyPath, "utf8")) as { version?: number; fencedPaths?: unknown };
  if (value.version !== 1 || !Array.isArray(value.fencedPaths) || value.fencedPaths.length === 0) {
    throw new Error(`invalid cut policy: ${policyPath}`);
  }
  const paths = value.fencedPaths.map(String);
  for (const entry of paths) {
    if (!/^[A-Za-z0-9._/-]+$/.test(entry) || entry.startsWith("/") || entry.includes("..")) {
      throw new Error(`invalid fenced path: ${entry}`);
    }
  }
  return paths;
}

function within(root: string, target: string): boolean {
  const relative = path.relative(root, target);
  return relative === "" || (!relative.startsWith(`..${path.sep}`) && relative !== ".." && !path.isAbsolute(relative));
}
