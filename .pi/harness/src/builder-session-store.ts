import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import path from "node:path";
import { randomUUID } from "node:crypto";
import { SessionManager, type SessionEntry } from "@earendil-works/pi-coding-agent";

export type BuilderSessionState = {
  builderSessionRef: string;
  sessionFile?: string;
  status: "running" | "completed" | "failed";
  toolCalls: number;
  workspaceGeneration: string[];
  progress: { stage: string; attempt: number };
};

export type BuilderSessionView = Omit<BuilderSessionState, "sessionFile"> & {
  entries: SessionEntry[];
};

function statePath(root: string, builderSessionRef: string): string {
  if (!/^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(builderSessionRef)) {
    throw new Error("invalid builder session reference");
  }
  return path.join(root, `${builderSessionRef}.state.json`);
}

function sessionFileWithin(root: string, sessionFile: string): string {
  const resolvedRoot = path.resolve(root);
  const resolvedFile = path.resolve(sessionFile);
  if (path.dirname(resolvedFile) !== resolvedRoot) {
    throw new Error("builder session file is outside the session store");
  }
  return resolvedFile;
}

export async function writeBuilderSessionState(root: string, state: BuilderSessionState): Promise<void> {
  const target = statePath(root, state.builderSessionRef);
  if (state.sessionFile) sessionFileWithin(root, state.sessionFile);
  await mkdir(root, { recursive: true });
  const temporary = `${target}.${randomUUID()}.tmp`;
  await writeFile(temporary, JSON.stringify(state), { encoding: "utf8", mode: 0o600 });
  await rename(temporary, target);
}

export async function readBuilderSession(root: string, builderSessionRef: string): Promise<BuilderSessionView> {
  const serialized = await readFile(statePath(root, builderSessionRef), "utf8");
  const state = JSON.parse(serialized) as BuilderSessionState;
  if (state.builderSessionRef !== builderSessionRef) {
    throw new Error("builder session state identity does not match the requested session");
  }
  const entries = state.sessionFile
    ? SessionManager.open(sessionFileWithin(root, state.sessionFile), root).getEntries()
    : [];
  const { sessionFile: _sessionFile, ...publicState } = state;
  return { ...publicState, entries };
}
