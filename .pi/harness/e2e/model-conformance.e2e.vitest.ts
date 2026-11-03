import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, mkdtempSync, readFileSync, readdirSync } from "node:fs";
import { mkdir, rm, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import os from "node:os";
import path from "node:path";
import * as allure from "allure-js-commons";
import { SessionManager } from "@earendil-works/pi-coding-agent";
import { afterAll, beforeAll, test } from "vitest";
import { spawnOrcClient, type WorkflowContract } from "../src/orc-client.ts";
import { writeBuilderSessionState } from "../src/builder-session-store.ts";
import { openGraderRoom } from "../src/grader-room.ts";

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
  builderSessionRef?: string;
  builderSessionView?: any;
  builderSessionFilesBefore?: Array<{ file: string; content: string }>;
  persistedEvaluation?: Record<string, unknown>;
  evidenceProjections?: { allure: any; otel: any };
  gradingProbe?: {
    cycleId: string;
    evidenceRoot: string;
    result?: { exitCode: number; stdout: string; stderr: string };
    root: string;
    subjectDigest?: string;
  };
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
test.skip("A first regeneration starts without product or instrument oracles", () => {});
test.skip("The first candidate is recorded without an independent verdict", () => {});
test.skip("A builder that produces no candidate fails the first regeneration", () => {});
test.skip("An ungraded candidate cannot pass the independent grade gate", () => {});
test.skip("make bootstrap builds every harness and supported-stack runtime", () => {});
test.skip("make bootstrap does not run Löyly application or oracle workflows", () => {});
test.skip("An invalid runtime build fails the Makefile target", () => {});
test.skip("Löyly resolves its Gondolin config and Dockerfile for each supported stack", () => {});
test.skip("An unsupported or missing project stack Dockerfile is refused", () => {});

test("ORC exposes the declared regeneration stage order", async () => {
  const context = scenarioContext();
  await scenarioLabels("execution-pipeline", "item-01-024", ["e2e"]);

  await given("the Pi harness ORC worker is running", async () => {
    assert.ok(worker, "the ORC worker must be started by the E2E harness");
  });
  await when("Pi requests the executable regeneration workflow contract", async () => {
    context.contract = await worker.client.workflowContract();
    await allure.attachment("workflow-contract.json", JSON.stringify(context.contract, null, 2), "application/json");
  });
  await then("ORC exposes these stages in order:", async (table) => {
    const expectedStages = table?.map(([stage]) => stage);
    assert.ok(expectedStages, "the modeled stage table is required");
    assert.deepEqual(required(context.contract).stages, expectedStages);
  }, [
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
test.skip("The product-oracle OCI is bound to the approved Git revision", () => {});
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

  await given("the requested slice has missing, duplicate, unstamped, or unknown items", async () => {
    context.invalidSelections = await invalidSelectionRequests();
  });
  await when("the operator previews the selection", async () => {
    context.selections = [];
    context.outcome = [];
    for (const request of assertRequired(context.invalidSelections, "invalid selection requests")) {
      try {
        context.selections.push(await worker.client.previewSelection(request.cut, request.slice, request.itemIds));
      } catch (error) {
        context.outcome.push(error as Error);
      }
    }
  });
  await then("no selection contract is recorded", async () => {
    assert.deepEqual(context.selections, []);
    assert.equal(context.outcome.length, context.invalidSelections?.length);
  });
  await and("the refusal identifies the unresolved denominator", async () => {
    const messages = context.outcome.map((error: Error) => error.message);
    assert.ok(messages.some((message: string) => /no spec\.feature/.test(message)));
    assert.ok(messages.some((message: string) => /duplicate item identities/.test(message)));
    assert.ok(messages.some((message: string) => /stable @item identity/.test(message)));
    assert.ok(messages.some((message: string) => /requested item does not belong/.test(message)));
  });
});

test.skip("Submission refuses an unsafe candidate scope", () => {});

test("Waiting for a known task returns its one terminal outcome", async () => {
  const context = scenarioContext();
  await scenarioLabels("operator-control-plane", "item-01-018", ["e2e"]);

  await given("a cut or regeneration task receipt was returned", async () => {
    context.cut = await preparedCut();
    context.taskId = context.cut.receipt.taskId;
  });
  await when("the operator waits for its task identity", async () => {
    context.outcome = await worker.client.wait(assertRequired(context.taskId, "task identity"));
  });
  await then("the result is completed or failed with the ORC trace summary", async () => {
    assert.match(context.outcome?.status, /^(completed|failed)$/);
    assert.match(context.outcome?.result?.traceId, /^[0-9a-f-]{36}$/);
    assert.equal(typeof context.outcome?.result?.durationMs, "number");
  });
  await and("waiting again returns the same terminal outcome", async () => {
    context.repeatedOutcome = await worker.client.wait(assertRequired(context.taskId, "task identity"));
    assert.deepEqual(context.repeatedOutcome, context.outcome);
  });
});

test("Waiting refuses an unknown task identity", async () => {
  const context = scenarioContext();
  await scenarioLabels("operator-control-plane", "item-01-019", ["e2e"]);

  await given("no task receipt has the supplied identity", async () => {
    context.taskId = Number.MAX_SAFE_INTEGER;
  });
  await when("the operator waits for that identity", async () => {
    try {
      await worker.client.wait(assertRequired(context.taskId, "task identity"));
    } catch (error) {
      context.error = error as Error;
    }
  });
  await then("the request is refused without starting new work", async () => {
    assert.match(assertRequired(context.error, "wait refusal").message, /unknown taskId/);
  });
});

test.skip("A durable task remains observable after worker restart", () => {});
test.skip("Approval binds the complete cycle preparation and diagram", () => {});
test.skip("The control plane does not depend on one interactive session implementation", () => {});
test.skip("Live progress reconciles with durable ORC execution state", () => {});
test("An operator observes a builder session read-only", async () => {
  const context = scenarioContext();
  await scenarioLabels("operator-control-plane", "item-01-038", ["e2e"]);

  await given("ORC opened a separately identified builder session for one build attempt", async () => {
    const builderSessionRef = "e2e-builder-session-38";
    const sessionRoot = path.join(e2eStateRoot, "builder-sessions");
    const workspace = path.join(e2eStateRoot, "builder-workspace-38");
    await mkdir(workspace, { recursive: true });
    await writeFile(path.join(workspace, "generated.ts"), "export const generated = true;\n");
    const session = SessionManager.create(workspace, sessionRoot, { id: builderSessionRef });
    session.appendMessage({ role: "user", content: "build only the approved slice", timestamp: Date.now() });
    session.appendMessage({
      role: "assistant",
      content: [{ type: "text", text: "implemented the approved slice" }],
      api: "responses",
      provider: "test",
      model: "test",
      usage: { input: 1, output: 1, cacheRead: 0, cacheWrite: 0, totalTokens: 2, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } },
      stopReason: "stop",
      timestamp: Date.now(),
    });
    await writeBuilderSessionState(sessionRoot, {
      builderSessionRef,
      sessionFile: session.getSessionFile(),
      status: "completed",
      toolCalls: 2,
      workspaceGeneration: ["generated.ts"],
      progress: { stage: "builder", attempt: 1 },
    });
    context.builderSessionRef = builderSessionRef;
    context.builderSessionFilesBefore = [session.getSessionFile(), path.join(workspace, "generated.ts")]
      .map((file) => ({ file: assertRequired(file, "builder session file"), content: readFileSync(assertRequired(file, "builder session file"), "utf8") }));
  });
  await when("an operator session opens that builder-session reference", async () => {
    context.builderSessionView = await worker.client.builderSession(
      assertRequired(context.builderSessionRef, "builder session reference"),
    );
  });
  await then("it can inspect committed model turns, tool calls, workspace generation, and progress", async () => {
    const view = assertRequired(context.builderSessionView, "builder session view");
    assert.equal(view.entries.length, 2);
    assert.equal(view.toolCalls, 2);
    assert.deepEqual(view.workspaceGeneration, ["generated.ts"]);
    assert.deepEqual(view.progress, { stage: "builder", attempt: 1 });
    await allure.attachment("builder-session.json", JSON.stringify(view, null, 2), "application/json");
  });
  await but("observation cannot change the builder transcript or candidate", async () => {
    for (const snapshot of assertRequired(context.builderSessionFilesBefore, "builder files")) {
      assert.equal(readFileSync(snapshot.file, "utf8"), snapshot.content);
    }
  });
  await and("the builder cannot read the operator transcript or independent grader evidence", async () => {
    const transcript = JSON.stringify(assertRequired(context.builderSessionView, "builder session view").entries);
    assert.doesNotMatch(transcript, /operator transcript|independent grader evidence/i);
  });
});
test.skip("Intervention is explicit and provenance-recorded", () => {});
test.skip("A builder transcript is restored with its matching workspace generation", () => {});
test("Prompt optimization requires training and validation examples", async () => {
  const context = scenarioContext();
  await scenarioLabels("prompt-optimization", "item-01-021", ["e2e"]);

  await given("either example set is missing or not a collection", async () => {});
  await when("the operator requests builder-prompt optimization", async () => {
    try {
      await worker.client.optimizePrompt([], []);
    } catch (error) {
      context.error = error as Error;
    }
  });
  await then("optimization is refused before an instruction candidate is evaluated", async () => {
    assert.match(assertRequired(context.error, "optimization refusal").message, /non-empty trainset and valset/);
  });
});

test.skip("Every instruction candidate is scored by independent regeneration gates", () => {});
test.skip("Only the winning instruction becomes active", () => {});
test("Preparing a cut returns immutable commit and tree identities", async () => {
  const context = scenarioContext();
  await scenarioLabels("regeneration-cycle", "item-01-001", ["e2e"]);

  await given("the operator names a full source commit and an execution backend", async () => {
    context.cut = await preparedCut();
  });
  await when("the Pi harness prepares the regeneration cut", async () => {
    context.cut ??= await preparedCut();
  });
  await then("the prepared cut is rooted at exactly that source commit", async () => {
    const cut = assertRequired(context.cut, "prepared cut");
    assert.equal(cut["source-commit"], cut.sourceCommit);
    assert.equal(cut.receipt.cutId, cut.sourceCommit);
    assert.match(cut.cutId, /^[a-f0-9]{40}$/);
  });
  await and("its fenced tree identity is recorded", async () => {
    assert.match(assertRequired(context.cut, "prepared cut").treeId, /^[a-f0-9]{40}$/);
  });
  await and("prior implementation and measurement paths are absent from the reachable cut history", async () => {
    const cut = assertRequired(context.cut, "prepared cut");
    const cutRoot = path.join(os.homedir(), ".pi/agent/loyly/cuts", cut.cutId);
    const objects = execFileSync("git", ["-C", cutRoot, "rev-list", "--objects", "--all"], { encoding: "utf8" });
    const policy = JSON.parse(readFileSync(path.join(projectRoot, ".pi/harness/config/cut-policy.json"), "utf8"));
    for (const fencedPath of policy.fencedPaths as string[]) {
      assert.equal(existsSync(path.join(cutRoot, fencedPath)), false);
      assert.doesNotMatch(objects, new RegExp(` ${escapeRegex(fencedPath)}(?:/|$)`));
    }
  });
});

test.skip("The builder cannot modify or escape the cut", () => {});

test("Previewing the same slice produces the same selection identity", async () => {
  const context = scenarioContext();
  await scenarioLabels("regeneration-cycle", "item-01-003", ["e2e"]);

  await given("the same pinned cut, project, slice, and requested item subset", async () => {
    context.cut = await preparedCut();
  });
  await and("the selection has not already been approved", async () => {});
  await when("the selection is previewed more than once", async () => {
    const cut = assertRequired(context.cut, "prepared cut");
    context.selections = [
      await worker.client.previewSelection(cut.cutId, "01-booking-core"),
      await worker.client.previewSelection(cut.cutId, "01-booking-core"),
    ];
  });
  await then("each preview contains the same ordered item denominator", async () => {
    const [first, second] = assertRequired(context.selections, "selection previews");
    assert.deepEqual(first.items.map(({ id }: any) => id), second.items.map(({ id }: any) => id));
    assert.ok(first.items.length > 0);
  });
  await and("each preview has the same selection identity and digest", async () => {
    const [first, second] = assertRequired(context.selections, "selection previews");
    assert.equal(first.selectionId, second.selectionId);
    assert.equal(first.selectionDigest, second.selectionDigest);
  });
  await and("its status is awaiting approval", async () => {
    assert.equal(assertRequired(context.selections, "selection previews").at(-1).status, "awaiting-approval");
  });
});

test("Approval refuses a digest other than the reviewed digest", async () => {
  const context = scenarioContext();
  await scenarioLabels("regeneration-cycle", "item-01-004", ["e2e"]);

  await given("a selection contract is awaiting approval", async () => {
    const cut = await preparedCut();
    context.cut = cut;
    context.selections = [await worker.client.previewSelection(cut.cutId, "01-booking-core")];
    assert.equal(context.selections[0].status, "awaiting-approval");
  });
  await when("approval names its selection identity with a different digest", async () => {
    const selection = assertRequired(context.selections, "selection preview")[0];
    try {
      await worker.client.approveSelection(selection.selectionId, "0".repeat(64));
    } catch (error) {
      context.error = error as Error;
    }
  });
  await then("approval is refused", async () => {
    assert.match(assertRequired(context.error, "approval refusal").message, /digest/i);
  });
  await and("the selection remains awaiting approval", async () => {
    const selection = assertRequired(context.selections, "selection preview")[0];
    const current = await worker.client.previewSelection(
      assertRequired(context.cut, "prepared cut").cutId,
      "01-booking-core",
    );
    assert.equal(current.selectionId, selection.selectionId);
    assert.equal(current.status, "awaiting-approval");
  });
});

test("An unapproved selection cannot start a regeneration cycle", async () => {
  const context = scenarioContext();
  await scenarioLabels("regeneration-cycle", "item-01-005", ["e2e"]);

  await given("a recorded selection has not been approved", async () => {
    const cut = await preparedCut();
    context.cut = cut;
    context.selections = [await worker.client.previewSelection(cut.cutId, "01-booking-core")];
    assert.equal(context.selections[0].status, "awaiting-approval");
  });
  await when("the operator submits a regeneration cycle for that selection", async () => {
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
  });
  await then("submission is refused before a builder starts", async () => {
    assert.match(assertRequired(context.error, "submission refusal").message, /approved slice selection/);
  });
});

test.skip("Repeating the same request identity returns the original receipt", () => {});
test.skip("The builder starts fresh and must produce workspace output", () => {});

test("ORC exposes builder session identity in its executable contract", async () => {
  const context = scenarioContext();
  await scenarioLabels("regeneration-cycle", "item-01-007", ["e2e"]);

  await given("the Pi harness ORC worker is running", async () => {
    assert.ok(worker, "the ORC worker must be started by the E2E harness");
  });
  await when("Pi requests the executable regeneration workflow contract", async () => {
    context.contract = await worker.client.workflowContract();
    await allure.attachment("workflow-contract.json", JSON.stringify(context.contract, null, 2), "application/json");
  });
  await then('the builder result includes the field "builderSessionId"', async () => {
    assert.ok(required(context.contract).builderResultFields.includes("builderSessionId"));
  });
});

test.skip("Verification rejects an incomplete runnable application contract", () => {});
test.skip("Independent grading fails closed when no readable verdict exists", () => {});
test.skip("An unreached approved item remains a failed denominator item", () => {});

test("A final result requires every independent gate to pass", async () => {
  const context = scenarioContext();
  await scenarioLabels("regeneration-cycle", "item-01-011", ["e2e", "playback"]);

  await given("the oracle, verifier, grader, and measurement have completed", async () => {
    const completed = await fullCycle();
    context.outcome = completed.outcome;
    context.selection = completed.selection;
  });
  await when("the final result is derived", async () => {});
  await then("it does not pass when any independent gate failed", async () => {
    const outputs = context.outcome.result.outputs;
    assert.equal(outputs["oracle-result"].passed, true);
    assert.equal(outputs["verify-result"].passed, true);
    assert.equal(outputs["result-result"].passed, false);
  });
  await and("every approved item was graded", async () => {
    const measurement = context.outcome.result.outputs["cycle-result"];
    assert.equal(measurement.total, context.selection.items.length);
    assert.equal(measurement.passed + measurement.failed, measurement.total);
  });
  await and("failed approved items remain in the denominator", async () => {
    assert.ok(context.outcome.result.outputs["cycle-result"].failed > 0);
  });
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

  await given("the Pi harness ORC worker is running", async () => {
    assert.ok(worker, "the ORC worker must be started by the E2E harness");
  });
  await when("Pi requests the executable regeneration workflow contract", async () => {
    context.contract = await worker.client.workflowContract();
    await allure.attachment("workflow-contract.json", JSON.stringify(context.contract, null, 2), "application/json");
  });
  await then('the observation includes the field "artifactDigests"', async () => {
    assert.ok(required(context.contract).observationFields.includes("artifactDigests"));
  });
});

test.skip("Instrument conformance is independent of the behavioral grade", () => {});
test.skip("Instrument conformance is selected entirely from the pinned project model", () => {});
test.skip("An operator Pi session profiles a Gondolin VM directly", () => {});
test("OTel and Allure project the persisted invariant evaluation", async () => {
  const context = scenarioContext();
  await scenarioLabels("runtime-evidence", "item-01-034", ["e2e"]);

  await given("an independent invariant evaluation and its evidence digest were persisted", async () => {
    context.persistedEvaluation = {
      evaluationId: "evaluation-e2e-item-01-034",
      evidenceDigest: "evidence-digest-e2e-34",
      verdict: "fail",
    };
  });
  await when("an OTel result span and Allure report are emitted", async () => {
    const projectionRoot = mkdtempSync(path.join(os.tmpdir(), "loyly-otel-e2e-"));
    extractedPlaybackRoots.push(projectionRoot);
    const evaluation = assertRequired(context.persistedEvaluation, "persisted evaluation");
    const observation = {
      "evaluation-id": evaluation.evaluationId,
      "evidence-digest": evaluation.evidenceDigest,
      role: "observed", builder: "openai-codex/e2e", "evidence-kind": "grader",
      producer: "runtime-instrument-conformance", "container-id": "loyly",
      "component-id": "01-booking-core", "item-id": "@item-01-034",
      "cycle-id": "cycle-e2e-34", "cut-digest": "cut-e2e",
      "subject-digest": "subject-e2e", "oracle-digest": "oracle-e2e",
      room: "gondolin", scope: "impl", result: evaluation.verdict,
      "reached?": true, evidence: "known failed invariant",
    };
    const expression = "(require '[clojure.data.json :as json] '[loyly.pi-harness.core.evidence.evidence-projection :as projection]) (projection/project! (System/getenv \"LOYLEY_E2E_PROJECTION_ROOT\") (json/read-str (System/getenv \"LOYLEY_E2E_EVALUATION\") :key-fn keyword))";
    execFileSync("clojure", [
      "-Sdeps", `{:paths [${JSON.stringify(path.join(projectRoot, ".pi/harness/orc-worker/src"))}]}`,
      "-M:dev", "-e", expression,
    ], {
      cwd: path.join(projectRoot, "orc"),
      env: { ...process.env, LOYLEY_E2E_PROJECTION_ROOT: projectionRoot, LOYLEY_E2E_EVALUATION: JSON.stringify(observation) },
    });
    const allureFile = readdirSync(path.join(projectionRoot, "allure-results")).find((file) => file.endsWith("-result.json"));
    const otelFile = readdirSync(path.join(projectionRoot, "otel-results")).find((file) => file.endsWith(".otlp.json"));
    context.evidenceProjections = {
      allure: JSON.parse(readFileSync(path.join(projectionRoot, "allure-results", assertRequired(allureFile, "Allure result")), "utf8")),
      otel: JSON.parse(readFileSync(path.join(projectionRoot, "otel-results", assertRequired(otelFile, "OTel result")), "utf8")),
    };
  });
  await then("both identify the persisted evaluation and evidence digest", async () => {
    const evaluation = assertRequired(context.persistedEvaluation, "persisted evaluation");
    const projections = assertRequired(context.evidenceProjections, "evidence projections");
    const labels = Object.fromEntries(projections.allure.labels.map(({ name, value }: any) => [name, value]));
    const span = projections.otel.resourceSpans[0].scopeSpans[0].spans[0];
    const attributes = Object.fromEntries(span.attributes.map(({ key, value }: any) => [key, value.stringValue]));
    assert.equal(labels.evaluation_id, evaluation.evaluationId);
    assert.equal(attributes["loyly.evaluation.id"], evaluation.evaluationId);
    assert.equal(labels.evidence_digest, evaluation.evidenceDigest);
    assert.equal(attributes["loyly.evidence.digest"], evaluation.evidenceDigest);
  });
  await and("both name the same candidate subject and oracle digests", async () => {
    const projections = assertRequired(context.evidenceProjections, "evidence projections");
    const labels = Object.fromEntries(projections.allure.labels.map(({ name, value }: any) => [name, value]));
    const span = projections.otel.resourceSpans[0].scopeSpans[0].spans[0];
    const attributes = Object.fromEntries(span.attributes.map(({ key, value }: any) => [key, value.stringValue]));
    assert.equal(labels.subject_digest, "subject-e2e");
    assert.equal(attributes["loyly.subject.digest"], labels.subject_digest);
    assert.equal(labels.oracle_digest, "oracle-e2e");
    assert.equal(attributes["loyly.oracle.digest"], labels.oracle_digest);
  });
  await and("neither projection can change the recorded verdict", async () => {
    const evaluation = assertRequired(context.persistedEvaluation, "persisted evaluation");
    const projections = assertRequired(context.evidenceProjections, "evidence projections");
    const span = projections.otel.resourceSpans[0].scopeSpans[0].spans[0];
    const attributes = Object.fromEntries(span.attributes.map(({ key, value }: any) => [key, value.stringValue]));
    assert.equal(evaluation.verdict, "fail");
    assert.equal(projections.allure.status, "failed");
    assert.equal(attributes["loyly.evaluation.verdict"], "fail");
  });
});

test("Each grading cycle isolates evidence from its pinned OTel capture policy", async () => {
  const context = scenarioContext();
  await scenarioLabels("runtime-evidence", "item-01-042", ["e2e", "gondolin"]);

  await given("the pinned product-oracle OCI supplies a declarative OTel capture policy", async () => {
    const root = mkdtempSync(path.join(os.tmpdir(), "loyly-grading-probe-e2e-"));
    extractedPlaybackRoots.push(root);
    const oracleRoot = path.join(root, "oracle");
    const productOracleRef = process.env.PI_HARNESS_PRODUCT_ORACLE_REF
      ?? process.env.LOYLEY_PRODUCT_ORACLE_REF;
    assert.ok(productOracleRef, "set PI_HARNESS_PRODUCT_ORACLE_REF to run oracle-backed evidence E2E");
    execFileSync("oras", [
      "pull", productOracleRef,
      "--output", oracleRoot, "--no-tty",
    ]);
    assert.ok(existsSync(path.join(oracleRoot, "runtime-observability", "capture-policy.json")));
    assert.equal(existsSync(path.join(oracleRoot, "runtime-probes")), false);
    context.gradingProbe = {
      cycleId: "e2e-cycle-01-042",
      evidenceRoot: path.join(root, "evidence"),
      root,
    };
  });
  await when("an independent Gondolin grader room opens for one regeneration cycle", async () => {
    const probe = assertRequired(context.gradingProbe, "grading probe");
    const cutsRoot = path.join(probe.root, "cuts");
    const outputRoot = path.join(probe.root, "output");
    await mkdir(path.join(cutsRoot, "cut-1"), { recursive: true });
    await mkdir(outputRoot, { recursive: true });
    const candidateContent = "candidate-e2e-item-01-042\n";
    await writeFile(path.join(cutsRoot, "cut-1", "candidate.txt"), candidateContent);
    probe.subjectDigest = createHash("sha256").update(candidateContent).digest("hex");
    const room = await openGraderRoom({
      backend: "gondolin",
      cycleId: probe.cycleId,
      cutsRoot,
      cutId: "cut-1",
      outputRoot,
      evidenceRoot: probe.evidenceRoot,
      graderAssetsRoot: path.join(probe.root, "oracle", "grader"),
      observabilityAssetsRoot: path.join(probe.root, "oracle", "runtime-observability"),
    });
    try {
      probe.result = await room.exec([
        "test -r /observability/capture-policy.json",
        "sha256sum /workspace/candidate.txt | cut -d ' ' -f1 > /evidence/subject.before",
        "! touch /observability/not-writable 2>/dev/null",
        "! touch /workspace/not-writable 2>/dev/null",
        "sha256sum /workspace/candidate.txt | cut -d ' ' -f1 > /evidence/subject.after",
        "touch /evidence/capture.ok",
        "command -v perf",
        "command -v bpftool",
        "command -v obi",
        "command -v otelcol-contrib",
        "command -v otelcol-ebpf-profiler",
        "command -v strace",
        "grep -q ' /sys/kernel/tracing tracefs ' /proc/mounts",
        "grep -q ' /sys/fs/bpf bpf ' /proc/mounts",
      ].join(" && "));
    } finally {
      await room.close();
    }
  });
  await then("the candidate and OTel capture policy are mounted read-only", async () => {
    const result = assertRequired(assertRequired(context.gradingProbe, "grading probe").result, "grading probe result");
    assert.equal(result.exitCode, 0, result.stderr);
  });
  await and("the candidate subject digest remains unchanged after observation", async () => {
    const probe = assertRequired(context.gradingProbe, "grading probe");
    const expected = assertRequired(probe.subjectDigest, "candidate subject digest");
    const cycleEvidenceRoot = path.join(probe.evidenceRoot, "cycles", probe.cycleId);
    assert.equal(readFileSync(path.join(cycleEvidenceRoot, "subject.before"), "utf8").trim(), expected);
    assert.equal(readFileSync(path.join(cycleEvidenceRoot, "subject.after"), "utf8").trim(), expected);
  });
  await and("a fresh cycle evidence volume is the only writable grading mount", async () => {
    const probe = assertRequired(context.gradingProbe, "grading probe");
    assert.ok(existsSync(path.join(probe.evidenceRoot, "cycles", probe.cycleId, "capture.ok")));
    assert.equal(existsSync(path.join(probe.evidenceRoot, "capture.ok")), false);
  });
  await and("the grader image supplies OBI and the OTel eBPF profiler with tracefs and BPF FS mounted", async () => {
    const result = assertRequired(assertRequired(context.gradingProbe, "grading probe").result, "grading probe result");
    assert.equal(result.exitCode, 0, result.stderr);
  });
  await and("native OTLP traces, metrics, and profiles are cycle-scoped diagnostic evidence", async () => {
    const policy = JSON.parse(readFileSync(path.join(
      assertRequired(context.gradingProbe, "grading probe").root,
      "oracle/runtime-observability/capture-policy.json",
    ), "utf8"));
    assert.deepEqual(policy.signals, ["traces", "metrics", "profiles"]);
    assert.equal(policy.format, "otlp-json");
    assert.equal(policy.purpose, "diagnostic");
    assert.equal(policy.authoritativeGrade, false);
  });
});

function scenarioContext(): ScenarioContext {
  return {};
}

async function scenarioLabels(feature: string, story: string, tags: string[]): Promise<void> {
  await allure.feature(feature);
  await allure.story(story);
  await allure.tags(...tags);
}

type StepTable = string[][];
type StepActual = (table?: StepTable) => void | Promise<void>;

function given(text: string, actual: StepActual, table?: StepTable): PromiseLike<void> {
  return modelStep("Given", text, actual, table);
}

function when(text: string, actual: StepActual, table?: StepTable): PromiseLike<void> {
  return modelStep("When", text, actual, table);
}

// This must remain module-private. Exporting `then` makes the ESM namespace a thenable,
// so the module loader invokes it during collection before Allure's runtime is installed.
function then(text: string, actual: StepActual, table?: StepTable): PromiseLike<void> {
  return modelStep("Then", text, actual, table);
}

function and(text: string, actual: StepActual, table?: StepTable): PromiseLike<void> {
  return modelStep("And", text, actual, table);
}

function but(text: string, actual: StepActual, table?: StepTable): PromiseLike<void> {
  return modelStep("But", text, actual, table);
}

function modelStep(
  keyword: "Given" | "When" | "Then" | "And" | "But",
  text: string,
  actual: StepActual,
  table?: StepTable,
): PromiseLike<void> {
  return allure.step(`${keyword} ${text}`, () => actual(table));
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
