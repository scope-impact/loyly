import assert from "node:assert/strict";
import { chmod, mkdtemp, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";

test("AWS profile runner injects short-lived credentials without printing them", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "loyly-aws-profile-"));
  const fakeAws = path.join(root, "aws");
  await writeFile(fakeAws, `#!/bin/sh
printf '%s' '{"AccessKeyId":"temporary-access","SecretAccessKey":"temporary-secret","SessionToken":"temporary-token","Expiration":"2099-01-01T00:00:00Z"}'
`);
  await chmod(fakeAws, 0o755);

  const result = spawnSync(process.execPath, [
    "--experimental-strip-types",
    "scripts/with-aws-profile.mjs",
    "--profile",
    "sandbox",
    "--",
    process.execPath,
    "-e",
    "process.stdout.write(process.env.AWS_ACCESS_KEY_ID && process.env.AWS_SESSION_TOKEN ? 'credentials-present' : 'credentials-missing')",
  ], {
    cwd: new URL("..", import.meta.url).pathname,
    env: { ...process.env, PATH: `${root}:${process.env.PATH}` },
    encoding: "utf8",
  });

  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.stdout, "credentials-present");
  assert.doesNotMatch(`${result.stdout}${result.stderr}`, /temporary-(?:access|secret|token)/);
});
