import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import test from "node:test";

const makefileUrl = new URL("../../../harness/Makefile", import.meta.url);
const harnessRoot = fileURLToPath(new URL("../../../harness/", import.meta.url));

test("the Makefile builds the builder and grader VMs from harness/<project>", async () => {
  const makefile = await readFile(makefileUrl, "utf8");
  const dryRun = execFileSync("make", ["-n", "-C", harnessRoot, "bootstrap", "PROJECT=sample"], {
    encoding: "utf8",
  });

  for (const projectFile of [
    "sample/Dockerfile.typescript",
    "sample/Dockerfile.rust",
    "sample/Dockerfile.java",
    "sample/Dockerfile.rails",
    "sample/Dockerfile.rails-offline",
    "sample/Dockerfile.typescript-postgres",
    "sample/Dockerfile",
    "sample/gondolin.json",
  ]) {
    assert.ok(dryRun.includes(projectFile), `bootstrap must resolve ${projectFile}`);
  }
  assert.match(makefile, /PROJECT \?= loyly/);
  assert.doesNotMatch(makefile, /loyly\/Dockerfile|loyly\/gondolin\.json/);
  assert.doesNotMatch(makefile, /loyly-(builder|cut|instrument|grader|rails)/);
  for (const image of [
    "pi-harness/builder-typescript:oci",
    "pi-harness/builder-rust:oci",
    "pi-harness/builder-java:oci",
    "pi-harness/builder-rails:oci",
    "pi-harness/grader:oci",
    "pi-harness/rails:8-offline",
  ]) {
    assert.ok(dryRun.includes(image), `bootstrap must use the generic image slot ${image}`);
  }
  for (const runtime of ["builder", "builder-rust", "builder-java", "builder-rails", "grader"]) {
    assert.ok(makefile.includes(`$(ASSETS)/${runtime}`), `bootstrap must build ${runtime}`);
  }
  assert.match(makefile, /build --verify/);
  assert.doesNotMatch(makefile, /publish:oracles|submit_approved_slice/);
});
