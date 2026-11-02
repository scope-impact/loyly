import { mkdir, writeFile } from "node:fs/promises";
import path from "node:path";

export type CommandEvidence = { exitCode: number; stdout: string; stderr: string };

function safeSegment(value: string): string {
  if (value === "." || value === ".." || !/^[A-Za-z0-9._-]+$/.test(value)) {
    throw new Error(`unsafe evidence path segment: ${value}`);
  }
  return value;
}

function safeRelativePath(value: string): string {
  const segments = value.split("/");
  if (!segments.length || segments.some((segment) => !segment)) {
    throw new Error(`unsafe evidence path segment: ${value}`);
  }
  return path.join(...segments.map(safeSegment));
}

export async function persistCommandEvidence(
  evidenceRoot: string,
  namespace: string,
  name: string,
  result: CommandEvidence,
): Promise<void> {
  const directory = path.join(evidenceRoot, safeRelativePath(namespace));
  const basename = safeSegment(name);
  await mkdir(directory, { recursive: true });
  await Promise.all([
    writeFile(path.join(directory, `${basename}.stdout.txt`), result.stdout),
    writeFile(path.join(directory, `${basename}.stderr.txt`), result.stderr),
    writeFile(path.join(directory, `${basename}.result.json`), `${JSON.stringify(result)}\n`),
  ]);
}
