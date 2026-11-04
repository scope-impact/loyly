import { chmod, cp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import path from "node:path";

export type CycleOracleApplicability = {
  affordance: boolean;
  runtime: boolean;
};

export type SealCycleOracleBundleRequest = {
  authoredRoot: string;
  validatorsRoot: string;
  outputRoot: string;
  bindingDigest: string;
  harnessSnapshotDigest: string;
  selectionDigest: string;
  sessionId: string;
  model: string;
  applicability: CycleOracleApplicability;
};

export async function sealCycleOracleBundle(request: SealCycleOracleBundleRequest) {
  const evaluators = [
    { kind: "behavior", file: "grade.mjs", root: request.authoredRoot, applicable: true },
    { kind: "affordance", file: "affordance-conformance.mjs", root: request.validatorsRoot, applicable: request.applicability.affordance },
    { kind: "runtime", file: "runtime-conformance.py", root: request.validatorsRoot, applicable: request.applicability.runtime },
  ].filter((entry) => entry.applicable);

  await rm(request.outputRoot, { recursive: true, force: true });
  await mkdir(request.outputRoot, { recursive: true });
  const files: Record<string, string> = {};
  for (const evaluator of evaluators) {
    const source = path.join(evaluator.root, evaluator.file);
    const destination = path.join(request.outputRoot, evaluator.file);
    await cp(source, destination);
    files[evaluator.file] = await fileDigest(destination);
  }

  const evaluatorKinds = evaluators.map(({ kind }) => kind);
  const sealed = {
    version: 1,
    candidateBlind: true,
    bindingDigest: request.bindingDigest,
    harnessSnapshotDigest: request.harnessSnapshotDigest,
    selectionDigest: request.selectionDigest,
    sessionId: request.sessionId,
    model: request.model,
    evaluatorKinds,
    files,
  };
  const cycleOracleDigest = digestValue(sealed);
  const manifestPath = path.join(request.outputRoot, "manifest.json");
  await writeFile(manifestPath, JSON.stringify({ ...sealed, cycleOracleDigest }, null, 2) + "\n");
  await Promise.all([...Object.keys(files).map((file) => chmod(path.join(request.outputRoot, file), 0o444)), chmod(manifestPath, 0o444)]);

  return {
    graderRoot: request.outputRoot,
    cycleOracleRoot: request.outputRoot,
    cycleOracleDigest,
    evaluatorKinds,
    bindingDigest: request.bindingDigest,
    sessionId: request.sessionId,
  };
}

export async function validateCycleOracleBundle(outputRoot: string, bindingDigest: string) {
  const manifest = JSON.parse(await readFile(path.join(outputRoot, "manifest.json"), "utf8"));
  if (!manifest.candidateBlind) throw new Error("cycle oracle is not candidate blind");
  if (manifest.bindingDigest !== bindingDigest) throw new Error("sealed cycle oracle binding changed");
  for (const [file, expected] of Object.entries(manifest.files as Record<string, string>)) {
    if (await fileDigest(path.join(outputRoot, file)) !== expected) throw new Error(`sealed cycle oracle content changed: ${file}`);
  }
  const { cycleOracleDigest, ...sealed } = manifest;
  if (digestValue(sealed) !== cycleOracleDigest) throw new Error("sealed cycle oracle manifest changed");
  return { ...manifest, graderRoot: outputRoot, cycleOracleRoot: outputRoot };
}

async function fileDigest(file: string) {
  return `sha256:${createHash("sha256").update(await readFile(file)).digest("hex")}`;
}

function digestValue(value: unknown) {
  return `sha256:${createHash("sha256").update(JSON.stringify(value)).digest("hex")}`;
}
