import { cp, lstat, mkdir, mkdtemp, realpath, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { executionBackend, type ExecutionBackend } from "./execution-backend.ts";
import { ReadonlyProvider, RealFSProvider, VM } from "./gondolin.ts";
import { execOnHost } from "./host-exec.ts";

export type GraderRoom = {
  evidenceRoot: string;
  exec(command: string, options?: { signal?: AbortSignal }): Promise<{ exitCode: number; stdout: string; stderr: string }>;
  close(): Promise<void>;
};

type Vm = GraderRoom & { close(): Promise<void> };

export async function openGraderRoom(options: {
  backend: ExecutionBackend;
  cutsRoot: string;
  cutId: string;
  outputRoot: string;
  evidenceRoot?: string;
  cycleId?: string;
  graderAssetsRoot?: string;
  observabilityAssetsRoot?: string;
  runtimeImagesRoot?: string;
  createVm?: (options: Record<string, unknown>) => Promise<Vm>;
}): Promise<GraderRoom> {
  const backend = executionBackend(options.backend);
  const cutsRoot = await realpath(options.cutsRoot);
  const cutRoot = path.join(cutsRoot, options.cutId);
  if (!(await lstat(cutRoot)).isDirectory()) throw new Error(`unknown cut: ${options.cutId}`);
  const candidateRoot = await mkdtemp(path.join(os.tmpdir(), "pi-harness-grader-"));
  await cp(cutRoot, candidateRoot, { recursive: true });
  await cp(options.outputRoot, candidateRoot, { recursive: true, force: true });
  const requestedEvidenceRoot = options.evidenceRoot ?? `${options.outputRoot}.evidence`;
  const cycleDirectory = options.cycleId ? safeCycleDirectory(options.cycleId) : undefined;
  const requestedCycleEvidenceRoot = cycleDirectory
    ? path.join(requestedEvidenceRoot, "cycles", cycleDirectory)
    : requestedEvidenceRoot;
  await mkdir(requestedCycleEvidenceRoot, { recursive: true });
  const evidenceRoot = await realpath(requestedCycleEvidenceRoot);
  const observabilityAssetsRoot = options.observabilityAssetsRoot
    ? await realpath(options.observabilityAssetsRoot)
    : undefined;

  if (backend === "host") {
    return {
      evidenceRoot,
      exec: (command, execOptions) => execOnHost(command, candidateRoot, {
        ...execOptions,
        env: {
          ...process.env,
          HARNESS_EVIDENCE_ROOT: evidenceRoot,
          ...(observabilityAssetsRoot
            ? { HARNESS_OBSERVABILITY_ROOT: observabilityAssetsRoot }
            : {}),
        },
      }),
      close: () => rm(candidateRoot, { recursive: true, force: true }),
    };
  }

  const createVm = options.createVm ?? (VM.create as unknown as typeof options.createVm);
  if (!createVm) throw new Error("Gondolin VM factory is unavailable");
  const graderAssetsRoot = options.graderAssetsRoot
    ? await realpath(options.graderAssetsRoot)
    : undefined;
  const runtimeImagesRoot = options.runtimeImagesRoot
    ? await realpath(options.runtimeImagesRoot)
    : undefined;
  const mounts = {
    "/workspace": new ReadonlyProvider(new RealFSProvider(candidateRoot)),
    ...(graderAssetsRoot
      ? { "/grader": new ReadonlyProvider(new RealFSProvider(graderAssetsRoot)) }
      : {}),
    "/evidence": new RealFSProvider(evidenceRoot),
    ...(observabilityAssetsRoot
      ? { "/observability": new ReadonlyProvider(new RealFSProvider(observabilityAssetsRoot)) }
      : {}),
    ...(runtimeImagesRoot
      ? { "/runtime-images": new ReadonlyProvider(new RealFSProvider(runtimeImagesRoot)) }
      : {}),
  };
  const vm = await createVm({
    ...(runtimeImagesRoot
      ? { rootfs: { mode: "cow", size: process.env.PI_HARNESS_GRADER_ROOTFS_SIZE ?? "4G" } }
      : {}),
    dns: { mode: "synthetic" },
    allowWebSockets: false,
    httpHooks: { isRequestAllowed: () => false },
    vfs: { mounts },
    sandbox: {
      imagePath: process.env.PI_HARNESS_GRADER_IMAGE ?? process.env.LOYLEY_GRADER_IMAGE
        ?? path.resolve(path.dirname(new URL(import.meta.url).pathname), "../.state/assets/grader"),
    },
  });
  return {
    evidenceRoot,
    exec: (command, execOptions) => vm.exec(
      `export HARNESS_EVIDENCE_ROOT=/evidence${observabilityAssetsRoot ? " HARNESS_OBSERVABILITY_ROOT=/observability" : ""}; cd /workspace && ${command}`,
      execOptions,
    ),
    async close() {
      try { await vm.close(); } finally { await rm(candidateRoot, { recursive: true, force: true }); }
    },
  };
}

function safeCycleDirectory(cycleId: string): string {
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(cycleId)) {
    throw new Error(`unsafe grading cycle identity: ${cycleId}`);
  }
  return cycleId;
}
