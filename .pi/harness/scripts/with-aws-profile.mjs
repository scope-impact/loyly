#!/usr/bin/env node
import { spawnSync } from "node:child_process";

const separator = process.argv.indexOf("--");
const profileFlag = process.argv.indexOf("--profile");
if (separator < 0 || separator === process.argv.length - 1) {
  fail("usage: with-aws-profile.mjs --profile NAME -- COMMAND [ARG ...]");
}
const profile = profileFlag >= 0 ? process.argv[profileFlag + 1] : process.env.AWS_PROFILE;
if (!profile || profile === "--") fail("an AWS profile is required");

const exported = spawnSync(
  "aws",
  ["configure", "export-credentials", "--profile", profile, "--format", "process"],
  { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] },
);
if (exported.status !== 0) fail(`AWS profile ${profile} could not export credentials`);

let credentials;
try {
  credentials = JSON.parse(exported.stdout);
} catch {
  fail(`AWS profile ${profile} returned an invalid credential response`);
}
if (!credentials.AccessKeyId || !credentials.SecretAccessKey || !credentials.SessionToken) {
  fail(`AWS profile ${profile} returned incomplete temporary credentials`);
}

const [command, ...args] = process.argv.slice(separator + 1);
const child = spawnSync(command, args, {
  stdio: "inherit",
  env: {
    ...process.env,
    AWS_PROFILE: profile,
    AWS_ACCESS_KEY_ID: credentials.AccessKeyId,
    AWS_SECRET_ACCESS_KEY: credentials.SecretAccessKey,
    AWS_SESSION_TOKEN: credentials.SessionToken,
  },
});
if (child.error) fail(`could not start ${command}: ${child.error.message}`);
if (child.signal) process.kill(process.pid, child.signal);
process.exit(child.status ?? 1);

function fail(message) {
  process.stderr.write(`${message}\n`);
  process.exit(1);
}
