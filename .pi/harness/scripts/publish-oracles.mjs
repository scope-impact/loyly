import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import {
  copyFileSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const harnessRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const projectRoot = path.resolve(harnessRoot, "../..");
const sourceUrl = "https://github.com/scope-impact/loyly";
const instrumentRegistry = "ghcr.io/scope-impact/loyly/instrument-oracle";
const args = process.argv.slice(2);
const noPush = args.includes("--no-push");
const outputIndex = args.indexOf("--output");
const outputRoot = path.resolve(outputIndex >= 0 ? requiredArg(args[outputIndex + 1], "--output")
  : path.join(harnessRoot, ".state", "oracle-publish"));
const commit = run("git", ["rev-parse", "HEAD"], projectRoot).trim();
const commitCreated = run("git", ["show", "-s", "--format=%cI", commit], projectRoot).trim();

if (!noPush && run("git", ["status", "--porcelain"], projectRoot).trim() !== "") {
  throw new Error("refusing to publish oracles from a dirty Git checkout");
}

mkdirSync(outputRoot, { recursive: true });
const stagingRoot = mkdtempSync(path.join(os.tmpdir(), "loyly-oracle-publish-"));

try {
  const instrument = buildInstrumentOracle();
  if (!noPush) {
    publish(instrument, instrumentRegistry);
    updateInstrumentLock(instrument.digest);
  }
  process.stdout.write(`${JSON.stringify({ commit, instrument })}\n`);
} finally {
  rmSync(stagingRoot, { recursive: true, force: true });
}

function buildInstrumentOracle() {
  const definition = readJson(path.join(harnessRoot, "config", "instrument-oracle.json"));
  const stage = path.join(stagingRoot, "instrument");
  const layout = path.join(outputRoot, "instrument-oracle");
  mkdirSync(stage, { recursive: true });
  const artifacts = discoverArtifacts(definition.roots, definition.files);
  const digestInput = artifacts
    .sort((left, right) => left.path < right.path ? -1 : left.path > right.path ? 1 : 0)
    .map((artifact) => `${artifact.path}\0${artifact.digest}\n`)
    .join("");
  const instrumentDigest = `sha256:${sha256(Buffer.from(digestInput))}`;
  const goldenRoot = path.join(harnessRoot, "test", "fixtures", "instrument-oracle");
  const goldenNames = readdirSync(goldenRoot).filter((name) => name.endsWith(".golden.json")).sort();
  const goldens = goldenNames.map((name) => readJson(path.join(goldenRoot, name)));
  const fixtures = goldens.map((golden) => ({
    id: golden.labels.fixture_id,
    instrumentId: golden.labels.instrument_id,
    role: golden.parameters.find((parameter) => parameter.name === "role")?.value,
  }));
  writeJson(path.join(stage, "policy.json"), { version: 1, minimumFixturesPerInstrument: 2 });
  writeJson(path.join(stage, "instruments.json"), {
    instruments: [{
      id: definition.instrumentId,
      digest: instrumentDigest,
      roots: definition.roots,
      files: definition.files,
      components: definition.components,
    }],
  });
  writeJson(path.join(stage, "fixtures.json"), { fixtures });
  for (const name of goldenNames) copyFileSync(path.join(goldenRoot, name), path.join(stage, name));
  const layers = [
    ["policy.json", "application/vnd.loyly.instrument-oracle.policy.v1+json"],
    ["instruments.json", "application/vnd.loyly.instrument-oracle.instruments.v1+json"],
    ["fixtures.json", "application/vnd.loyly.instrument-oracle.fixtures.v1+json"],
    ...goldenNames.map((name) => [name, "application/vnd.loyly.allure-golden.v1+json"]),
  ];
  return buildLayout(layout, stage, "application/vnd.loyly.instrument-oracle.v1", layers);
}

function buildLayout(layout, stage, artifactType, layers) {
  rmSync(layout, { recursive: true, force: true });
  const ref = `${layout}:${commit}`;
  run("oras", [
    "push", "--oci-layout", ref,
    "--artifact-type", artifactType,
    "--annotation", `org.opencontainers.image.created=${commitCreated}`,
    "--annotation", `org.opencontainers.image.revision=${commit}`,
    "--annotation", `org.opencontainers.image.source=${sourceUrl}`,
    "--no-tty",
    ...layers.map(([name, mediaType]) => `${name}:${mediaType}`),
  ], stage);
  const descriptor = JSON.parse(run("oras", ["manifest", "fetch", "--oci-layout", ref, "--descriptor"]));
  return { layout, tag: commit, digest: descriptor.digest };
}

function publish(artifact, registry) {
  run("oras", ["cp", "--from-oci-layout", "--no-tty", `${artifact.layout}:${commit}`,
    `${registry}:${commit},accepted`], projectRoot);
  const descriptor = JSON.parse(run("oras", ["manifest", "fetch", `${registry}:${commit}`, "--descriptor"]));
  if (descriptor.digest !== artifact.digest) throw new Error(`published digest mismatch for ${registry}`);
}

function updateInstrumentLock(instrumentDigest) {
  const instrumentLock = path.join(harnessRoot, "config", "oracles.json");
  const instrument = readJson(instrumentLock);
  instrument.instrumentOracleRef = `${instrumentRegistry}@${instrumentDigest}`;
  writeJson(instrumentLock, instrument);
}

function discoverArtifacts(roots, files) {
  const found = new Set(files);
  for (const root of roots) walk(path.join(projectRoot, root), root, found);
  return [...found].map((relative) => {
    const file = path.join(projectRoot, relative);
    if (!statSync(file).isFile()) throw new Error(`instrument input is not a file: ${relative}`);
    return { path: relative, digest: `sha256:${sha256(readFileSync(file))}` };
  });
}

function walk(directory, relative, found) {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const childRelative = path.posix.join(relative.replaceAll(path.sep, "/"), entry.name);
    if (entry.isDirectory()) walk(path.join(directory, entry.name), childRelative, found);
    else if (entry.isFile()) found.add(childRelative);
  }
}

function readJson(file) {
  return JSON.parse(readFileSync(file, "utf8"));
}

function writeJson(file, value) {
  mkdirSync(path.dirname(file), { recursive: true });
  writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`);
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function run(command, commandArgs, cwd) {
  return execFileSync(command, commandArgs, { cwd, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
}

function requiredArg(value, name) {
  if (!value || value.startsWith("--")) throw new Error(`${name} requires a value`);
  return value;
}
