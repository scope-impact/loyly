import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const makefileUrl = new URL("../../../harness/Makefile", import.meta.url);

test("root harness bootstrap builds and verifies every configured runtime", async () => {
  const makefile = await readFile(makefileUrl, "utf8");

  for (const image of [
    "loyly/Dockerfile.typescript",
    "loyly/Dockerfile.rust",
    "loyly/Dockerfile.java",
    "loyly/Dockerfile.rails",
    "Dockerfile.cut",
    "Dockerfile.instrument",
    "loyly/Dockerfile",
  ]) {
    assert.ok(makefile.includes(image), `bootstrap must build ${image}`);
  }
  for (const runtime of [
    "bootstrap",
    "builder",
    "builder-rust",
    "builder-java",
    "builder-rails",
    "cut",
    "instrument",
    "grader",
  ]) {
    assert.ok(makefile.includes(`$(ASSETS)/${runtime}`), `bootstrap must build ${runtime}`);
  }
  assert.match(makefile, /build --verify/);
  assert.doesNotMatch(makefile, /publish:oracles|submit_regeneration_cycle/);
});
