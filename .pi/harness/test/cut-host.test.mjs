import assert from "node:assert/strict";
import { access, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { createCutPreparer } from "../src/cut.ts";
import { execOnHost } from "../src/host-exec.ts";

test("host cut preparation preserves the fence contract", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "loyly-host-cut-test-"));
  const sourceRoot = path.join(root, "source");
  const cutsRoot = path.join(root, "cuts");
  await mkdir(path.join(sourceRoot, ".pi", "harness", "config"), { recursive: true });
  await mkdir(path.join(sourceRoot, "legacy"), { recursive: true });
  await writeFile(
    path.join(sourceRoot, ".pi", "harness", "config", "cut-policy.json"),
    JSON.stringify({ version: 1, fencedPaths: ["legacy"] }),
  );
  await writeFile(path.join(sourceRoot, "legacy", "implementation.txt"), "remove me\n");
  await writeFile(path.join(sourceRoot, "contract.txt"), "keep me\n");
  const initialized = await execOnHost(
    "git init -q && git config user.name test && git config user.email test@example.com && git add -A && git commit -qm initial",
    sourceRoot,
  );
  assert.equal(initialized.exitCode, 0, initialized.stderr);
  const revision = await execOnHost("git rev-parse HEAD", sourceRoot);
  const sourceCommit = revision.stdout.trim();
  try {
    const receipt = await createCutPreparer({ backend: "host", sourceRoot, cutsRoot })(sourceCommit);
    assert.equal(receipt.sourceCommit, sourceCommit);
    assert.equal(await readFile(path.join(cutsRoot, receipt.cutId, "contract.txt"), "utf8"), "keep me\n");
    await assert.rejects(access(path.join(cutsRoot, receipt.cutId, "legacy")));
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
