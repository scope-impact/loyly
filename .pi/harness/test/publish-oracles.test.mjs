import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import test from "node:test";

const exec = promisify(execFile);

test("harness publisher builds only the harness instrument OCI artifact", async () => {
  const output = await mkdtemp(path.join(os.tmpdir(), "loyly-publish-oracles-"));
  try {
    const commit = (await exec("git", ["rev-parse", "HEAD"], { encoding: "utf8" })).stdout.trim();
    const source = "https://github.com/scope-impact/loyly";
    const published = JSON.parse((await exec(
      "scripts/publish-oracles",
      ["--no-push", "--output", output],
      { encoding: "utf8" },
    )).stdout);

    assert.equal(published.commit, commit);
    assert.equal(published.instrument.tag, commit);
    assert.equal("product" in published, false);

    const manifest = JSON.parse((await exec(
      "oras",
      ["manifest", "fetch", "--oci-layout", `${published.instrument.layout}:${commit}`],
      { encoding: "utf8" },
    )).stdout);
    assert.equal(manifest.annotations["org.opencontainers.image.revision"], commit);
    assert.equal(manifest.annotations["org.opencontainers.image.source"], source);
    assert.ok(manifest.layers.every((layer) =>
      !layer.annotations["org.opencontainers.image.title"].startsWith("grader/")));
  } finally {
    await rm(output, { recursive: true, force: true });
  }
});
