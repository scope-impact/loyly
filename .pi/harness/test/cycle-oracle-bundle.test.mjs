import assert from "node:assert/strict";
import { mkdtemp, mkdir, readFile, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { sealCycleOracleBundle } from "../src/cycle-oracle-bundle.ts";

test("Grade seals one self-contained candidate-blind cycle oracle", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "loyly-cycle-oracle-test-"));
  const authoredRoot = path.join(root, "authored");
  const validatorsRoot = path.join(root, "validators");
  const outputRoot = path.join(root, "sealed");
  await mkdir(authoredRoot, { recursive: true });
  await mkdir(validatorsRoot, { recursive: true });
  await writeFile(path.join(authoredRoot, "grade.mjs"), "console.log('grade')\n");
  await writeFile(path.join(validatorsRoot, "affordance-conformance.mjs"), "console.log('ux')\n");
  await writeFile(path.join(validatorsRoot, "runtime-conformance.py"), "print('runtime')\n");

  const result = await sealCycleOracleBundle({
    authoredRoot, validatorsRoot, outputRoot,
    bindingDigest: "sha256:binding",
    harnessSnapshotDigest: "sha256:snapshot",
    selectionDigest: "sha256:selection",
    sessionId: "oracle-author-1", model: "test-model",
    applicability: { affordance: true, runtime: false },
  });

  assert.deepEqual(result.evaluatorKinds, ["behavior", "affordance"]);
  assert.match(result.cycleOracleDigest, /^sha256:[a-f0-9]{64}$/);
  await readFile(path.join(outputRoot, "grade.mjs"));
  await readFile(path.join(outputRoot, "affordance-conformance.mjs"));
  await assert.rejects(readFile(path.join(outputRoot, "runtime-conformance.py")));
  const manifest = JSON.parse(await readFile(path.join(outputRoot, "manifest.json"), "utf8"));
  assert.equal(manifest.candidateBlind, true);
  assert.equal(manifest.harnessSnapshotDigest, "sha256:snapshot");
  assert.equal(manifest.selectionDigest, "sha256:selection");
  assert.deepEqual(manifest.evaluatorKinds, ["behavior", "affordance"]);
});
