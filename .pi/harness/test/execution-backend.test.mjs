import assert from "node:assert/strict";
import { mkdir, mkdtemp, readFile, realpath, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { executionBackend } from "../src/execution-backend.ts";
import { openBuilderRoom } from "../src/builder-room.ts";
import { openGraderRoom } from "../src/grader-room.ts";
import { openOracleFixtureRoom } from "../src/oracle-fixture-room.ts";

test("host builder uses the same logical cut and workspace roots", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "loyly-host-builder-test-"));
  const cutsRoot = path.join(root, "cuts");
  const cutRoot = path.join(cutsRoot, "cut-1");
  await mkdir(cutRoot, { recursive: true });
  await writeFile(path.join(cutRoot, "contract.txt"), "approved\n");
  const room = await openBuilderRoom({ backend: "host", cutsRoot, cutId: "cut-1", scope: ["impl"] });
  try {
    const result = await room.exec("test \"$(cat \"$HARNESS_CUT_ROOT/contract.txt\")\" = approved && printf generated > \"$HARNESS_WORKSPACE_ROOT/impl/result.txt\"");
    assert.equal(result.exitCode, 0, result.stderr);
    assert.equal(await readFile(path.join(room.workspaceRoot, "impl", "result.txt"), "utf8"), "generated");
  } finally {
    await room.close();
    await rm(root, { recursive: true, force: true });
  }
});

test("gondolin builder selects the requested language image", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "loyly-language-builder-test-"));
  const cutsRoot = path.join(root, "cuts");
  await mkdir(path.join(cutsRoot, "cut-1"), { recursive: true });
  const expected = {
    rust: "builder-rust",
    java: "builder-java",
    rails: "builder-rails",
  };
  try {
    for (const [targetStack, directory] of Object.entries(expected)) {
      let vmOptions;
      const room = await openBuilderRoom({
        backend: "gondolin", cutsRoot, cutId: "cut-1", scope: ["impl"], targetStack,
        createVm: async (options) => {
          vmOptions = options;
          return { exec: async () => ({ exitCode: 0, stdout: "", stderr: "" }), close: async () => {} };
        },
      });
      try {
        assert.equal(path.basename(vmOptions.sandbox.imagePath), directory);
      } finally {
        await room.close();
      }
    }
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("gondolin repair builder starts from the candidate and points at shared verification evidence", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "loyly-repair-builder-test-"));
  const cutsRoot = path.join(root, "cuts");
  const candidateRoot = path.join(root, "candidate");
  const evidenceRoot = path.join(root, "candidate.evidence");
  await mkdir(path.join(cutsRoot, "cut-1"), { recursive: true });
  await mkdir(path.join(candidateRoot, "impl"), { recursive: true });
  await mkdir(path.join(evidenceRoot, "compose"), { recursive: true });
  await writeFile(path.join(candidateRoot, "impl", "candidate.txt"), "round one\n");
  await writeFile(path.join(evidenceRoot, "compose", "compose-logs.stdout.txt"), "broken\n");
  let vmOptions;
  let vmCommand;
  const room = await openBuilderRoom({
    backend: "gondolin", cutsRoot, cutId: "cut-1", scope: ["impl"], targetStack: "rust",
    initialWorkspaceRoot: candidateRoot,
    evidenceRoot,
    createVm: async (options) => {
      vmOptions = options;
      return {
        exec: async (command) => {
          vmCommand = command;
          return { exitCode: 0, stdout: "", stderr: "" };
        },
        close: async () => {},
      };
    },
  });
  try {
    assert.equal(await readFile(path.join(room.workspaceRoot, "impl", "candidate.txt"), "utf8"), "round one\n");
    assert.deepEqual(Object.keys(vmOptions.vfs.mounts).sort(), ["/cut", "/evidence", "/workspace"]);
    await room.exec("true");
    assert.match(vmCommand, /HARNESS_VERIFICATION_EVIDENCE_ROOT=\/evidence/);
  } finally {
    await room.close();
    await rm(root, { recursive: true, force: true });
  }
});

test("host grader runs from a disposable merged candidate", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "loyly-host-grader-test-"));
  const cutsRoot = path.join(root, "cuts");
  const cutRoot = path.join(cutsRoot, "cut-1");
  const outputRoot = path.join(root, "output");
  const evidenceRoot = path.join(root, "evidence");
  await mkdir(cutRoot, { recursive: true });
  await mkdir(path.join(outputRoot, "impl"), { recursive: true });
  await writeFile(path.join(cutRoot, "contract.txt"), "cut\n");
  await writeFile(path.join(outputRoot, "impl", "candidate.txt"), "candidate\n");
  const room = await openGraderRoom({ backend: "host", cutsRoot, cutId: "cut-1", outputRoot, evidenceRoot });
  try {
    const result = await room.exec("test -f contract.txt && test -f impl/candidate.txt");
    assert.equal(result.exitCode, 0, result.stderr);
    const envResult = await room.exec("printf %s \"$HARNESS_EVIDENCE_ROOT\"");
    assert.equal(envResult.stdout, await realpath(evidenceRoot));
  } finally {
    await room.close();
    await rm(root, { recursive: true, force: true });
  }
});

test("gondolin grader isolates cycle evidence and mounts OCI probes read-only", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "loyly-gondolin-grader-test-"));
  const cutsRoot = path.join(root, "cuts");
  const outputRoot = path.join(root, "output");
  const graderAssetsRoot = path.join(root, "grader-assets");
  const observabilityAssetsRoot = path.join(root, "runtime-observability");
  const runtimeImagesRoot = path.join(root, "runtime-images");
  const evidenceRoot = path.join(root, "evidence");
  await mkdir(path.join(cutsRoot, "cut-1"), { recursive: true });
  await mkdir(path.join(outputRoot, "impl"), { recursive: true });
  await mkdir(graderAssetsRoot, { recursive: true });
  await mkdir(observabilityAssetsRoot, { recursive: true });
  await mkdir(runtimeImagesRoot, { recursive: true });
  let vmOptions;
  let vmCommand;
  const room = await openGraderRoom({
    backend: "gondolin", cutsRoot, cutId: "cut-1", outputRoot, evidenceRoot, graderAssetsRoot,
    observabilityAssetsRoot, cycleId: "cycle-42",
    runtimeImagesRoot,
    createVm: async (options) => {
      vmOptions = options;
      return { exec: async (command) => { vmCommand = command; return { exitCode: 0, stdout: "", stderr: "" }; }, close: async () => {} };
    },
  });
  try {
    await room.exec("true");
    assert.deepEqual(Object.keys(vmOptions.vfs.mounts).sort(), ["/evidence", "/grader", "/observability", "/runtime-images", "/workspace"]);
    assert.equal(vmOptions.vfs.mounts["/workspace"].readonly, true);
    assert.equal(vmOptions.vfs.mounts["/grader"].readonly, true);
    assert.equal(vmOptions.vfs.mounts["/observability"].readonly, true);
    assert.equal(vmOptions.vfs.mounts["/evidence"].readonly, false);
    assert.equal(vmOptions.vfs.mounts["/evidence"].rootPath, await realpath(path.join(evidenceRoot, "cycles", "cycle-42")));
    assert.deepEqual(vmOptions.rootfs, { mode: "cow", size: "4G" });
    assert.match(vmCommand, /HARNESS_EVIDENCE_ROOT=\/evidence/);
    assert.match(vmCommand, /HARNESS_OBSERVABILITY_ROOT=\/observability/);
  } finally {
    await room.close();
    await rm(root, { recursive: true, force: true });
  }
});

test("oracle calibration fixtures execute only in a deny-egress Gondolin room", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "loyly-oracle-fixture-room-test-"));
  const fixtureRoot = path.join(root, "fixture");
  const graderRoot = path.join(root, "grader");
  await mkdir(fixtureRoot, { recursive: true });
  await mkdir(graderRoot, { recursive: true });
  let vmOptions;
  let vmCommand;
  let closed = false;
  const room = await openOracleFixtureRoom({
    fixtureRoot,
    graderRoot,
    createVm: async (options) => {
      vmOptions = options;
      return {
        exec: async (command) => {
          vmCommand = command;
          return { exitCode: 0, stdout: "ok", stderr: "" };
        },
        close: async () => { closed = true; },
      };
    },
  });
  try {
    const result = await room.exec("true");
    assert.equal(result.exitCode, 0);
    assert.deepEqual(Object.keys(vmOptions.vfs.mounts).sort(), ["/grader", "/oracle"]);
    assert.equal(vmOptions.allowWebSockets, false);
    assert.equal(vmOptions.httpHooks.isRequestAllowed(), false);
    assert.match(vmCommand, /cd \/oracle && true/);
  } finally {
    await room.close();
    assert.equal(closed, true);
    await rm(root, { recursive: true, force: true });
  }
});

test("execution backend fails closed", () => {
  assert.equal(executionBackend("host"), "host");
  assert.equal(executionBackend("gondolin"), "gondolin");
  assert.throws(() => executionBackend("docker"), /unsupported execution backend/);
});
