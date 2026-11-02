import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, mkdtempSync, readFileSync } from "node:fs";
import { mkdir, rm, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import os from "node:os";
import path from "node:path";
import * as allure from "allure-js-commons";
import { afterAll, beforeAll, test } from "vitest";
import { spawnOrcClient, type WorkflowContract } from "../src/orc-client.ts";

type ScenarioContext = {
  contract?: WorkflowContract;
  cut?: PreparedCut;
  error?: Error;
  invalidSelections?: Array<{ cut: string; itemIds?: string[]; slice: string }>;
  outcome?: any;
  repeatedOutcome?: any;
  selections?: any[];
  selection?: any;
  taskId?: number;
};

type PreparedCut = {
  "source-commit": string;
  cutId: string;
  executionBackend: string;
  outcome: any;
  receipt: { cutId: string; taskId: number; executionBackend: string };
  sourceCommit: string;
  treeId: string;
};

type RegenerationPlayback = {
  fixtureVersion: 1;
  recordingKind: "completed-failing-cycle";
  sourceCycleId: string;
  binding: {
    cutDigest: string;
    evidenceManifest: string;
    oracleDigest: string;
    projectId: string;
    subjectDigest: string;
  };
  oci: {
    acceptedContractDigest: string;
    instrumentOracle: OciArtifactBinding;
    nativeContractDigest: string;
    productOracle: OciArtifactBinding;
  };
  selection: { items: Array<{ id: string }> };
  outcome: any;
};

type OciArtifactBinding = {
  artifactType: string;
  descriptorDigest: `sha256:${string}`;
  path: string;
};

const projectRoot = fileURLToPath(new URL("../../../", import.meta.url));
const e2eStateRoot = mkdtempSync(path.join(os.tmpdir(), "loyly-pi-harness-e2e-"));

let worker: ReturnType<typeof spawnOrcClient>;
let preparedCutPromise: Promise<PreparedCut> | undefined;
let fullCyclePromise: Promise<any> | undefined;
const fixtureCuts: string[] = [];
const extractedPlaybackRoots: string[] = [];

beforeAll(() => {
  worker = spawnOrcClient({ projectRoot, env: { PI_HARNESS_STATE_ROOT: e2eStateRoot } });
});

afterAll(async () => {
  await worker?.close();
  await Promise.all(fixtureCuts.map((fixture) => rm(fixture, { recursive: true, force: true })));
  await Promise.all(extractedPlaybackRoots.map((fixture) => rm(fixture, { recursive: true, force: true })));
  await rm(e2eStateRoot, { recursive: true, force: true });
});

// Keep scenario tests at module root: allure-vitest installs its runtime with root hooks.
// Component ownership is projected through scenarioLabels instead of nested describe blocks.
test.skip("A regeneration cycle follows the declared stage order", () => {});

test("ORC exposes the declared regeneration stage order", async () => {
  const context = scenarioContext();
  await scenarioLabels("execution-pipeline", "item-01-024", ["e2e"]);

  await given("the Pi harness ORC worker is running", context);
  await when("Pi requests the executable regeneration workflow contract", context);
  await then("ORC exposes these stages in order:", context, [
    ["cut"],
    ["harness-calibration"],
    ["builder"],
    ["instrument"],
    ["oracle"],
    ["verify"],
    ["repair-verification"],
    ["graders"],
    ["instrument-conformance"],
    ["record-cycle"],
    ["grade-gate"],
  ]);
});

test.skip("Instrumentation records the subject without changing it", () => {});
test.skip("The oracle binds the calibrated instrument and complete pinned model contract", () => {});
test.skip("Independent observers cannot modify the candidate", () => {});
test.skip("Evidence identity cannot be reused for different content", () => {});
test.skip("Evidence reports are derived projections", () => {});
test.skip("A non-successful stage cannot disappear from the result [builder]", () => {});
test.skip("A non-successful stage cannot disappear from the result [instrument]", () => {});
test.skip("A non-successful stage cannot disappear from the result [oracle]", () => {});
test.skip("A non-successful stage cannot disappear from the result [verify]", () => {});
test.skip("A non-successful stage cannot disappear from the result [graders]", () => {});
test.skip("A non-successful stage cannot disappear from the result [instrument conformance]", () => {});
test.skip("Cut preparation refuses an invalid or colliding identity", () => {});

test("Selection preview refuses an empty or ambiguous denominator", async () => {
  const context = scenarioContext();
  await scenarioLabels("operator-control-plane", "item-01-016", ["e2e"]);

  await given("the requested slice has missing, duplicate, unstamped, or unknown items", context);
  await when("the operator previews the selection", context);
  await then("no selection contract is recorded", context);
  await and("the refusal identifies the unresolved denominator", context);
});

test.skip("Submission refuses an unsafe candidate scope", () => {});

test("Waiting for a known task returns its one terminal outcome", async () => {
  const context = scenarioContext();
  await scenarioLabels("operator-control-plane", "item-01-018", ["e2e"]);

  await given("a cut or regeneration task receipt was returned", context);
  await when("the operator waits for its task identity", context);
  await then("the result is completed or failed with the ORC trace summary", context);
  await and("waiting again returns the same terminal outcome", context);
});

test("Waiting refuses an unknown task identity", async () => {
  const context = scenarioContext();
  await scenarioLabels("operator-control-plane", "item-01-019", ["e2e"]);

  await given("no task receipt has the supplied identity", context);
  await when("the operator waits for that identity", context);
  await then("the request is refused without starting new work", context);
});

test.skip("A durable task remains observable after worker restart", () => {});
test.skip("Approval binds the complete cycle preparation and diagram", () => {});
test.skip("The control plane does not depend on one interactive session implementation", () => {});
test.skip("Live progress reconciles with durable ORC execution state", () => {});
test.skip("An operator observes a builder session read-only", () => {});
test.skip("Intervention is explicit and provenance-recorded", () => {});
test.skip("A builder transcript is restored with its matching workspace generation", () => {});
test("Prompt optimization requires training and validation examples", async () => {
  const context = scenarioContext();
  await scenarioLabels("prompt-optimization", "item-01-021", ["e2e"]);

  await given("either example set is missing or not a collection", context);
  await when("the operator requests builder-prompt optimization", context);
  await then("optimization is refused before an instruction candidate is evaluated", context);
});

test.skip("Every instruction candidate is scored by independent regeneration gates", () => {});
test.skip("Only the winning instruction becomes active", () => {});
test("Preparing a cut returns immutable commit and tree identities", async () => {
  const context = scenarioContext();
  await scenarioLabels("regeneration-cycle", "item-01-001", ["e2e"]);

  await given("the operator names a full source commit and an execution backend", context);
  await when("the Pi harness prepares the regeneration cut", context);
  await then("the prepared cut is rooted at exactly that source commit", context);
  await and("its fenced tree identity is recorded", context);
  await and("prior implementation and measurement paths are absent from the reachable cut history", context);
});

test.skip("The builder cannot modify or escape the cut", () => {});

test("Previewing the same slice produces the same selection identity", async () => {
  const context = scenarioContext();
  await scenarioLabels("regeneration-cycle", "item-01-003", ["e2e"]);

  await given("the same pinned cut, project, slice, and requested item subset", context);
  await and("the selection has not already been approved", context);
  await when("the selection is previewed more than once", context);
  await then("each preview contains the same ordered item denominator", context);
  await and("each preview has the same selection identity and digest", context);
  await and("its status is awaiting approval", context);
});

test("Approval refuses a digest other than the reviewed digest", async () => {
  const context = scenarioContext();
  await scenarioLabels("regeneration-cycle", "item-01-004", ["e2e"]);

  await given("a selection contract is awaiting approval", context);
  await when("approval names its selection identity with a different digest", context);
  await then("approval is refused", context);
  await and("the selection remains awaiting approval", context);
});

test("An unapproved selection cannot start a regeneration cycle", async () => {
  const context = scenarioContext();
  await scenarioLabels("regeneration-cycle", "item-01-005", ["e2e"]);

  await given("a recorded selection has not been approved", context);
  await when("the operator submits a regeneration cycle for that selection", context);
  await then("submission is refused before a builder starts", context);
});

test.skip("Repeating the same request identity returns the original receipt", () => {});
test.skip("The builder starts fresh and must produce workspace output", () => {});

test("ORC exposes builder session identity in its executable contract", async () => {
  const context = scenarioContext();
  await scenarioLabels("regeneration-cycle", "item-01-007", ["e2e"]);

  await given("the Pi harness ORC worker is running", context);
  await when("Pi requests the executable regeneration workflow contract", context);
  await then('the builder result includes the field "builderSessionId"', context);
});

test.skip("Verification rejects an incomplete runnable application contract", () => {});
test.skip("Independent grading fails closed when no readable verdict exists", () => {});
test.skip("An unreached approved item remains a failed denominator item", () => {});

test("A final result requires every independent gate to pass", async () => {
  const context = scenarioContext();
  await scenarioLabels("regeneration-cycle", "item-01-011", ["e2e", "playback"]);

  await given("the oracle, verifier, grader, and measurement have completed", context);
  await when("the final result is derived", context);
  await then("it does not pass when any independent gate failed", context);
  await and("every approved item was graded", context);
  await and("failed approved items remain in the denominator", context);
});

test.skip("Recording the same evidence twice is idempotent", () => {});
test.skip("A completed measurement is readable after the harness restarts", () => {});
test.skip("Host execution cannot be reported as independently graded", () => {});
test.skip("Candidate telemetry cannot author an independent verdict", () => {});
test.skip("Missing or dropped required signals are inconclusive", () => {});
test.skip("A passing invariant uses one correlated candidate subject", () => {});

test("ORC exposes artifact provenance in its executable contract", async () => {
  const context = scenarioContext();
  await scenarioLabels("runtime-evidence", "item-01-033", ["e2e"]);

  await given("the Pi harness ORC worker is running", context);
  await when("Pi requests the executable regeneration workflow contract", context);
  await then('the observation includes the field "artifactDigests"', context);
});

test.skip("Instrument conformance is independent of the behavioral grade", () => {});
test.skip("Instrument conformance is selected entirely from the pinned project model", () => {});
test.skip("OTel and Allure project the persisted invariant evaluation", () => {});

function scenarioContext(): ScenarioContext {
  return {};
}

async function scenarioLabels(feature: string, story: string, tags: string[]): Promise<void> {
  await allure.feature(feature);
  await allure.story(story);
  await allure.tags(...tags);
}

type StepTable = string[][];

function given(text: string, context: ScenarioContext, table?: StepTable): PromiseLike<void> {
  return modelStep("Given", text, context, table);
}

function when(text: string, context: ScenarioContext, table?: StepTable): PromiseLike<void> {
  return modelStep("When", text, context, table);
}

// This must remain module-private. Exporting `then` makes the ESM namespace a thenable,
// so the module loader invokes it during collection before Allure's runtime is installed.
function then(text: string, context: ScenarioContext, table?: StepTable): PromiseLike<void> {
  return modelStep("Then", text, context, table);
}

function and(text: string, context: ScenarioContext, table?: StepTable): PromiseLike<void> {
  return modelStep("And", text, context, table);
}

function but(text: string, context: ScenarioContext, table?: StepTable): PromiseLike<void> {
  return modelStep("But", text, context, table);
}

function modelStep(
  keyword: "Given" | "When" | "Then" | "And" | "But",
  text: string,
  context: ScenarioContext,
  table?: StepTable,
): PromiseLike<void> {
  const dataTable = table === undefined
    ? undefined
    : { rows: table.map((row) => ({ cells: row.map((value) => ({ value })) })) };
  return allure.step(`${keyword} ${text}`, () => executeStep(text, dataTable, context));
}

async function executeStep(
  text: string,
  dataTable: { rows: Array<{ cells: Array<{ value: string }> }> } | undefined,
  context: ScenarioContext,
): Promise<void> {
  if (text === "the Pi harness ORC worker is running") {
    assert.ok(worker, "the ORC worker must be started by the E2E harness");
    return;
  }
  if (text === "no task receipt has the supplied identity") {
    context.taskId = Number.MAX_SAFE_INTEGER;
    return;
  }
  if (text === "a cut or regeneration task receipt was returned") {
    context.cut = await preparedCut();
    context.taskId = context.cut.receipt.taskId;
    return;
  }
  if (text === "the operator waits for its task identity") {
    context.outcome = await worker.client.wait(assertRequired(context.taskId, "task identity"));
    return;
  }
  if (text === "the result is completed or failed with the ORC trace summary") {
    assert.match(context.outcome?.status, /^(completed|failed)$/);
    assert.match(context.outcome?.result?.traceId, /^[0-9a-f-]{36}$/);
    assert.equal(typeof context.outcome?.result?.durationMs, "number");
    return;
  }
  if (text === "waiting again returns the same terminal outcome") {
    context.repeatedOutcome = await worker.client.wait(assertRequired(context.taskId, "task identity"));
    assert.deepEqual(context.repeatedOutcome, context.outcome);
    return;
  }
  if (text === "the operator waits for that identity") {
    try {
      await worker.client.wait(assertRequired(context.taskId, "task identity"));
    } catch (error) {
      context.error = error as Error;
    }
    return;
  }
  if (text === "the request is refused without starting new work") {
    assert.match(assertRequired(context.error, "wait refusal").message, /unknown taskId/);
    return;
  }
  if (text === "either example set is missing or not a collection") {
    return;
  }
  if (text === "the operator requests builder-prompt optimization") {
    try {
      await worker.client.optimizePrompt([], []);
    } catch (error) {
      context.error = error as Error;
    }
    return;
  }
  if (text === "optimization is refused before an instruction candidate is evaluated") {
    assert.match(assertRequired(context.error, "optimization refusal").message, /non-empty trainset and valset/);
    return;
  }
  if (text === "the operator names a full source commit and an execution backend") {
    context.cut = await preparedCut();
    return;
  }
  if (text === "the Pi harness prepares the regeneration cut") {
    context.cut ??= await preparedCut();
    return;
  }
  if (text === "the prepared cut is rooted at exactly that source commit") {
    const cut = assertRequired(context.cut, "prepared cut");
    assert.equal(cut["source-commit"], cut.sourceCommit);
    assert.equal(cut.receipt.cutId, cut.sourceCommit);
    assert.match(cut.cutId, /^[a-f0-9]{40}$/);
    return;
  }
  if (text === "its fenced tree identity is recorded") {
    assert.match(assertRequired(context.cut, "prepared cut").treeId, /^[a-f0-9]{40}$/);
    return;
  }
  if (text === "prior implementation and measurement paths are absent from the reachable cut history") {
    const cut = assertRequired(context.cut, "prepared cut");
    const cutRoot = path.join(os.homedir(), ".pi/agent/loyly/cuts", cut.cutId);
    const objects = execFileSync("git", ["-C", cutRoot, "rev-list", "--objects", "--all"], { encoding: "utf8" });
    const policy = JSON.parse(readFileSync(path.join(projectRoot, ".pi/harness/config/cut-policy.json"), "utf8"));
    for (const fencedPath of policy.fencedPaths as string[]) {
      assert.equal(existsSync(path.join(cutRoot, fencedPath)), false);
      assert.doesNotMatch(objects, new RegExp(` ${escapeRegex(fencedPath)}(?:/|$)`));
    }
    return;
  }
  if (text === "the same pinned cut, project, slice, and requested item subset") {
    context.cut = await preparedCut();
    return;
  }
  if (text === "the selection has not already been approved") {
    return;
  }
  if (text === "the selection is previewed more than once") {
    const cut = assertRequired(context.cut, "prepared cut");
    context.selections = [
      await worker.client.previewSelection(cut.cutId, "01-booking-core"),
      await worker.client.previewSelection(cut.cutId, "01-booking-core"),
    ];
    return;
  }
  if (text === "each preview contains the same ordered item denominator") {
    const [first, second] = assertRequired(context.selections, "selection previews");
    assert.deepEqual(first.items.map(({ id }: any) => id), second.items.map(({ id }: any) => id));
    assert.ok(first.items.length > 0);
    return;
  }
  if (text === "each preview has the same selection identity and digest") {
    const [first, second] = assertRequired(context.selections, "selection previews");
    assert.equal(first.selectionId, second.selectionId);
    assert.equal(first.selectionDigest, second.selectionDigest);
    return;
  }
  if (text === "its status is awaiting approval") {
    assert.equal(assertRequired(context.selections, "selection previews").at(-1).status, "awaiting-approval");
    return;
  }
  if (text === "a selection contract is awaiting approval" || text === "a recorded selection has not been approved") {
    const cut = await preparedCut();
    context.cut = cut;
    context.selections = [await worker.client.previewSelection(cut.cutId, "01-booking-core")];
    assert.equal(context.selections[0].status, "awaiting-approval");
    return;
  }
  if (text === "approval names its selection identity with a different digest") {
    const selection = assertRequired(context.selections, "selection preview")[0];
    try {
      await worker.client.approveSelection(selection.selectionId, "0".repeat(64));
    } catch (error) {
      context.error = error as Error;
    }
    return;
  }
  if (text === "approval is refused") {
    assert.match(assertRequired(context.error, "approval refusal").message, /digest/i);
    return;
  }
  if (text === "the selection remains awaiting approval") {
    const selection = assertRequired(context.selections, "selection preview")[0];
    const current = await worker.client.previewSelection(
      assertRequired(context.cut, "prepared cut").cutId,
      "01-booking-core",
    );
    assert.equal(current.selectionId, selection.selectionId);
    assert.equal(current.status, "awaiting-approval");
    return;
  }
  if (text === "the operator submits a regeneration cycle for that selection") {
    const selection = assertRequired(context.selections, "selection preview")[0];
    try {
      await worker.client.submit({
        requestId: `unapproved-${Date.now()}`,
        selectionId: selection.selectionId,
        scope: ["workspace"],
        targetStack: "typescript",
        executionBackend: "host",
      });
    } catch (error) {
      context.error = error as Error;
    }
    return;
  }
  if (text === "submission is refused before a builder starts") {
    assert.match(assertRequired(context.error, "submission refusal").message, /approved slice selection/);
    return;
  }
  if (text === "the requested slice has missing, duplicate, unstamped, or unknown items") {
    context.invalidSelections = await invalidSelectionRequests();
    return;
  }
  if (text === "the operator previews the selection") {
    context.selections = [];
    context.outcome = [];
    for (const request of assertRequired(context.invalidSelections, "invalid selection requests")) {
      try {
        context.selections.push(await worker.client.previewSelection(request.cut, request.slice, request.itemIds));
      } catch (error) {
        context.outcome.push(error as Error);
      }
    }
    return;
  }
  if (text === "no selection contract is recorded") {
    assert.deepEqual(context.selections, []);
    assert.equal(context.outcome.length, context.invalidSelections?.length);
    return;
  }
  if (text === "the refusal identifies the unresolved denominator") {
    const messages = context.outcome.map((error: Error) => error.message);
    assert.ok(messages.some((message: string) => /no spec\.feature/.test(message)));
    assert.ok(messages.some((message: string) => /duplicate item identities/.test(message)));
    assert.ok(messages.some((message: string) => /stable @item identity/.test(message)));
    assert.ok(messages.some((message: string) => /requested item does not belong/.test(message)));
    return;
  }
  if (text === "the oracle, verifier, grader, and measurement have completed") {
    const completed = await fullCycle();
    context.outcome = completed.outcome;
    context.selection = completed.selection;
    return;
  }
  if (text === "the final result is derived") return;
  if (text === "it does not pass when any independent gate failed") {
    const outputs = context.outcome.result.outputs;
    assert.equal(outputs["oracle-result"].passed, true);
    assert.equal(outputs["verify-result"].passed, true);
    assert.equal(outputs["result-result"].passed, false);
    return;
  }
  if (text === "every approved item was graded") {
    const measurement = context.outcome.result.outputs["cycle-result"];
    assert.equal(measurement.total, context.selection.items.length);
    assert.equal(measurement.passed + measurement.failed, measurement.total);
    return;
  }
  if (text === "failed approved items remain in the denominator") {
    assert.ok(context.outcome.result.outputs["cycle-result"].failed > 0);
    return;
  }
  if (text === "Pi requests the executable regeneration workflow contract") {
    context.contract = await worker.client.workflowContract();
    await allure.attachment("workflow-contract.json", JSON.stringify(context.contract, null, 2), "application/json");
    return;
  }
  if (text === "ORC exposes these stages in order:") {
    const expectedStages = dataTable?.rows.map(({ cells }) => cells[0]?.value);
    assert.ok(expectedStages, "the modeled stage table is required");
    assert.deepEqual(required(context.contract).stages, expectedStages);
    return;
  }

  const builderField = text.match(/^the builder result includes the field "(.+)"$/)?.[1];
  if (builderField) {
    assert.ok(required(context.contract).builderResultFields.includes(builderField));
    return;
  }
  const observationField = text.match(/^the observation includes the field "(.+)"$/)?.[1];
  if (observationField) {
    assert.ok(required(context.contract).observationFields.includes(observationField));
    return;
  }
  throw new Error(`no Vitest E2E implementation for model step: ${text}`);
}

function required(contract: WorkflowContract | undefined): WorkflowContract {
  assert.notEqual(contract, undefined, "workflow contract is required");
  return contract as WorkflowContract;
}

function assertRequired<T>(value: T | undefined, name: string): T {
  assert.notEqual(value, undefined, `${name} is required`);
  return value as T;
}

function preparedCut(): Promise<PreparedCut> {
  return preparedCutPromise ??= (async () => {
    const sourceCommit = execFileSync("git", ["rev-parse", "HEAD"], { cwd: projectRoot, encoding: "utf8" }).trim();
    const receipt = await worker.client.prepareCut(sourceCommit, "host") as PreparedCut["receipt"];
    const outcome = await worker.client.wait(receipt.taskId);
    assert.equal(outcome.status, "completed", outcome.error);
    const result = (outcome.result as any)?.outputs?.["cut-result"];
    assert.ok(result, "cut workflow must return cut-result");
    return { ...result, receipt, outcome, sourceCommit } as PreparedCut;
  })();
}

function escapeRegex(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

async function invalidSelectionRequests(): Promise<Array<{ cut: string; itemIds?: string[]; slice: string }>> {
  const cutsRoot = path.join(os.homedir(), ".pi/agent/loyly/cuts");
  const suffix = `${process.pid}-${Date.now()}`;
  const create = async (kind: string, feature?: string) => {
    const cut = `e2e-${kind}-${suffix}`;
    const root = path.join(cutsRoot, cut);
    fixtureCuts.push(root);
    if (feature !== undefined) {
      const plan = path.join(root, "docs/plan/01-booking-core");
      await mkdir(plan, { recursive: true });
      await writeFile(path.join(plan, "spec.feature"), feature);
    } else {
      await mkdir(root, { recursive: true });
    }
    return cut;
  };
  const missing = await create("missing");
  const duplicate = await create(
    "duplicate",
    "Feature: duplicate\n  @item-01-001\n  Scenario: First\n  @item-01-001\n  Scenario: Second\n",
  );
  const unstamped = await create("unstamped", "Feature: unstamped\n  Scenario: Missing identity\n");
  const valid = await preparedCut();
  return [
    { cut: missing, slice: "01-booking-core" },
    { cut: duplicate, slice: "01-booking-core" },
    { cut: unstamped, slice: "01-booking-core" },
    { cut: valid.cutId, slice: "01-booking-core", itemIds: ["@item-01-999"] },
  ];
}

function fullCycle(): Promise<any> {
  return fullCyclePromise ??= (async () => {
    const archive = path.join(
      import.meta.dirname,
      "fixtures/regeneration-cycle/e2e-full-1793630744537.zip",
    );
    const fixtureRoot = mkdtempSync(path.join(os.tmpdir(), "loyly-regeneration-playback-"));
    extractedPlaybackRoots.push(fixtureRoot);
    execFileSync("unzip", ["-q", archive, "-d", fixtureRoot]);
    const playback = JSON.parse(
      readFileSync(path.join(fixtureRoot, "playback.json"), "utf8"),
    ) as RegenerationPlayback;
    assert.equal(playback.fixtureVersion, 1);
    assert.equal(playback.recordingKind, "completed-failing-cycle");
    assert.equal(playback.sourceCycleId, "e2e-full-1793630744537");
    const evidenceManifest = JSON.parse(
      readFileSync(path.join(fixtureRoot, playback.binding.evidenceManifest), "utf8"),
    );
    assert.equal(evidenceManifest["cycle-id"], playback.sourceCycleId);
    assert.equal(evidenceManifest["cut-digest"], playback.binding.cutDigest);
    assert.equal(evidenceManifest["subject-digest"], playback.binding.subjectDigest);
    assert.equal(evidenceManifest["oracle-digest"], playback.binding.oracleDigest);
    validateOciArtifact(fixtureRoot, playback.oci.instrumentOracle);
    validateOciArtifact(fixtureRoot, playback.oci.productOracle);
    const acceptedContractDigest = sha256(
      `${playback.oci.nativeContractDigest}\n${playback.oci.productOracle.descriptorDigest}`,
    );
    assert.equal(acceptedContractDigest, playback.oci.acceptedContractDigest);
    assert.equal(
      sha256(`${playback.oci.instrumentOracle.descriptorDigest}\n${acceptedContractDigest}`),
      playback.binding.oracleDigest,
    );
    assert.equal(playback.outcome.status, "completed");
    return { outcome: playback.outcome, selection: playback.selection };
  })();
}

function validateOciArtifact(fixtureRoot: string, binding: OciArtifactBinding): void {
  const layoutRoot = path.join(fixtureRoot, binding.path);
  const layout = JSON.parse(readFileSync(path.join(layoutRoot, "oci-layout"), "utf8"));
  assert.equal(layout.imageLayoutVersion, "1.0.0");
  const index = JSON.parse(readFileSync(path.join(layoutRoot, "index.json"), "utf8"));
  const descriptor = index.manifests.find(({ digest }: any) => digest === binding.descriptorDigest);
  assert.ok(descriptor, `OCI index must contain ${binding.descriptorDigest}`);
  assert.equal(descriptor.artifactType, binding.artifactType);
  const manifestBytes = readFileSync(ociBlobPath(layoutRoot, binding.descriptorDigest));
  assert.equal(`sha256:${sha256(manifestBytes)}`, binding.descriptorDigest);
  assert.equal(manifestBytes.byteLength, descriptor.size);
  const manifest = JSON.parse(manifestBytes.toString("utf8"));
  assert.equal(manifest.artifactType, binding.artifactType);
  for (const blob of [manifest.config, ...manifest.layers]) {
    const bytes = readFileSync(ociBlobPath(layoutRoot, blob.digest));
    assert.equal(`sha256:${sha256(bytes)}`, blob.digest);
    assert.equal(bytes.byteLength, blob.size);
  }
}

function ociBlobPath(layoutRoot: string, digest: string): string {
  const match = /^sha256:([0-9a-f]{64})$/.exec(digest);
  assert.ok(match, `unsupported OCI digest: ${digest}`);
  return path.join(layoutRoot, "blobs/sha256", match[1]);
}

function sha256(value: string | Buffer): string {
  return createHash("sha256").update(value).digest("hex");
}
