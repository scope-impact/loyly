import { realpath } from "node:fs/promises";
import path from "node:path";
import { ReadonlyProvider, RealFSProvider, VM } from "./gondolin.ts";

export type OracleFixtureRoom = {
  exec(command: string, options?: { signal?: AbortSignal }): Promise<{
    exitCode: number;
    stdout: string;
    stderr: string;
  }>;
  close(): Promise<void>;
};

type Vm = OracleFixtureRoom;

export async function openOracleFixtureRoom(options: {
  fixtureRoot: string;
  graderRoot: string;
  createVm?: (options: Record<string, unknown>) => Promise<Vm>;
}): Promise<OracleFixtureRoom> {
  const fixtureRoot = await realpath(options.fixtureRoot);
  const graderRoot = await realpath(options.graderRoot);
  const createVm = options.createVm ?? (VM.create as unknown as typeof options.createVm);
  if (!createVm) throw new Error("Gondolin VM factory is unavailable");

  const vm = await createVm({
    dns: { mode: "synthetic" },
    allowWebSockets: false,
    httpHooks: { isRequestAllowed: () => false },
    vfs: { mounts: {
      "/oracle": new ReadonlyProvider(new RealFSProvider(fixtureRoot)),
      "/grader": new ReadonlyProvider(new RealFSProvider(graderRoot)),
    } },
    sandbox: {
      imagePath: process.env.PI_HARNESS_GRADER_IMAGE ?? process.env.LOYLEY_GRADER_IMAGE
        ?? path.resolve(path.dirname(new URL(import.meta.url).pathname), "../config/assets/grader"),
    },
  });

  return {
    exec: (command, execOptions) => vm.exec(`cd /oracle && ${command}`, execOptions),
    close: () => vm.close(),
  };
}
