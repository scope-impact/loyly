import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import test from "node:test";

const oracleRoot = await mkdtemp(path.join(os.tmpdir(), "loyly-accepted-oracle-test-"));
const oraclePull = await runProgram("oras", ["pull", "--oci-layout",
  path.resolve("config/oracle-packs/loyly:accepted"), "--output", oracleRoot, "--no-tty"]);
if (oraclePull.exitCode !== 0) throw new Error(`could not extract accepted grader: ${oraclePull.stderr}`);
test.after(() => rm(oracleRoot, { recursive: true, force: true }));

const grader = path.join(oracleRoot, "grader", "booking-core-01-007.sh");
const releaseGrader = path.join(oracleRoot, "grader", "booking-core-01-008.sh");
const instrumentGrader = path.join(oracleRoot, "grader", "runtime-conformance.py");
const loylyInstrumentContract = path.resolve("../../model/loyly/runtime-evidence-contract.json");

test("independent grader proves booking and all three derived views", async () => {
  await withCandidate({ billing: true }, async (baseUrl) => {
    const result = await runGrader(baseUrl);
    assert.equal(result.exitCode, 0);
    assert.deepEqual(JSON.parse(result.stdout), [{
      itemId: "01-007",
      verdict: "pass",
      evidence: "booking, posted schedule, heater schedule, and billing signal agree",
    }]);
  });
});

test("independent grader rejects a booking with no billing signal", async () => {
  await withCandidate({ billing: false }, async (baseUrl) => {
    const result = await runGrader(baseUrl);
    assert.equal(result.exitCode, 1);
    const [verdict] = JSON.parse(result.stdout);
    assert.equal(verdict.itemId, "01-007");
    assert.equal(verdict.verdict, "fail");
    assert.match(verdict.evidence, /billing/);
  });
});

test("release grader proves all three derived views forget the booking", async () => {
  await withCandidate({ billing: true }, async (baseUrl) => {
    const result = await runScript(releaseGrader, [baseUrl], {});
    assert.equal(result.exitCode, 0);
    assert.deepEqual(JSON.parse(result.stdout), [{
      itemId: "01-008",
      verdict: "pass",
      evidence: "released booking disappeared from posted schedule, heater schedule, and billing signals",
    }]);
  });
});

test("release grader accepts an explicitly available slot that no view still claims", async () => {
  await withCandidate({ billing: true, retainAvailableSlot: true }, async (baseUrl) => {
    const result = await runScript(releaseGrader, [baseUrl], {});
    assert.equal(result.exitCode, 0, result.stderr);
    assert.equal(JSON.parse(result.stdout)[0].verdict, "pass");
  });
});

test("black-box grader does not author the instrument verdict", async () => {
  await withCandidate({ billing: true }, async (baseUrl) => {
    const result = await runGrader(baseUrl, { telemetry: false });
    assert.equal(result.exitCode, 0);
    const [verdict] = JSON.parse(result.stdout);
    assert.equal(verdict.verdict, "pass");
  });
});

test("instrument conformance is evaluated without rerunning black-box behavior", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "loyly-instrument-only-test-"));
  const otelRoot = path.join(root, "otel");
  try {
    await writeValidTelemetry(otelRoot);
    const result = await runProgram("python3", [instrumentGrader, "--evidence-root", otelRoot,
      "--item", "@item-01-007", loylyInstrumentContract]);
    assert.equal(result.exitCode, 0);
    assert.deepEqual(JSON.parse(result.stdout), [{
      itemId: "01-007",
      verdict: "pass",
      evidence: "one domain event and three complete correlated projection snapshots were independently validated",
    }]);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("instrument conformance fails closed when runtime evidence is missing", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "loyly-instrument-missing-test-"));
  try {
    const result = await runProgram("python3", [instrumentGrader, "--evidence-root", root,
      "--item", "@item-01-007", loylyInstrumentContract]);
    assert.equal(result.exitCode, 1);
    assert.match(JSON.parse(result.stdout)[0].evidence, /not emitted/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("instrument conformance fails every selected item without a declared contract", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "undeclared-instrument-item-test-"));
  try {
    const result = await runProgram("python3", [instrumentGrader, "--evidence-root", root,
      "--item", "@item-undeclared-001", loylyInstrumentContract]);
    assert.equal(result.exitCode, 1);
    assert.deepEqual(JSON.parse(result.stdout), [{
      itemId: "undeclared-001",
      verdict: "fail",
      evidence: "no runtime evidence contract exists for the selected item",
    }]);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("a second Loyly slice is governed by its declared invariant", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "loyly-second-contract-test-"));
  const evidenceRoot = path.join(root, "evidence");
  const contractPath = path.join(root, "contract.json");
  const contract = runtimeContract({
    itemId: "@item-01-008",
    relativePath: "release/signals.jsonl",
    discriminatorField: "kind",
    anchorKind: "domain-event",
    memberKind: "projection-snapshot",
    memberCount: 2,
    memberIdentityField: "projectionName",
    memberIdentityValues: ["active-bookings", "billing-ledger"],
    correlationFields: ["traceId", "bookingId"],
    invariantField: "bookingStateDigest",
  });
  const records = [
    { kind: "domain-event", traceId: "trace-8", bookingId: "booking-8", bookingStateDigest: "released" },
    { kind: "projection-snapshot", traceId: "trace-8", bookingId: "booking-8", bookingStateDigest: "released", projectionName: "active-bookings" },
    { kind: "projection-snapshot", traceId: "trace-8", bookingId: "booking-8", bookingStateDigest: "still-active", projectionName: "billing-ledger" },
  ];
  try {
    await writeFile(contractPath, JSON.stringify({ version: 1, contracts: [contract] }));
    await writeJsonLines(evidenceRoot, contract.relativePath, records);
    const result = await runProgram("python3", [instrumentGrader, "--evidence-root", evidenceRoot,
      "--item", contract.itemId, contractPath]);
    assert.equal(result.exitCode, 1);
    assert.match(JSON.parse(result.stdout)[0].evidence, /invariant/);

    records[2].bookingStateDigest = "released";
    await writeJsonLines(evidenceRoot, contract.relativePath, records);
    const corrected = await runProgram("python3", [instrumentGrader, "--evidence-root", evidenceRoot,
      "--item", contract.itemId, contractPath]);
    assert.equal(corrected.exitCode, 0);
    assert.equal(JSON.parse(corrected.stdout)[0].verdict, "pass");

    contract.invariants[0].recordKinds.push("undeclared-record-kind");
    await writeFile(contractPath, JSON.stringify({ version: 1, contracts: [contract] }));
    const invalidContract = await runProgram("python3", [instrumentGrader, "--evidence-root", evidenceRoot,
      "--item", contract.itemId, contractPath]);
    assert.equal(invalidContract.exitCode, 1);
    assert.match(JSON.parse(invalidContract.stdout)[0].evidence, /undeclared record kind/);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("the same evaluator grades an unrelated project without domain-specific code", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "inventory-contract-test-"));
  const evidenceRoot = path.join(root, "evidence");
  const contractPath = path.join(root, "contract.json");
  const contract = runtimeContract({
    itemId: "@item-stock-001",
    relativePath: "stock-001/facts.jsonl",
    discriminatorField: "signalType",
    anchorKind: "command-fact",
    memberKind: "materialized-fact",
    memberCount: 2,
    memberIdentityField: "viewCode",
    memberIdentityValues: ["available", "valuation"],
    correlationFields: ["requestKey", "entityKey"],
    invariantField: "revision",
  });
  const common = { requestKey: "request-stock", entityKey: "sku-42", revision: 7 };
  const records = [
    { ...common, signalType: "command-fact" },
    { ...common, signalType: "materialized-fact", viewCode: "available" },
    { ...common, signalType: "materialized-fact", viewCode: "valuation" },
  ];
  try {
    await writeFile(contractPath, JSON.stringify({ version: 1, contracts: [contract] }));
    await writeJsonLines(evidenceRoot, contract.relativePath, records);
    const result = await runProgram("python3", [instrumentGrader, "--evidence-root", evidenceRoot,
      "--item", contract.itemId, contractPath]);
    assert.equal(result.exitCode, 0);
    assert.deepEqual(JSON.parse(result.stdout), [{
      itemId: "stock-001",
      verdict: "pass",
      evidence: "runtime evidence satisfies the declared conformance contract",
    }]);
    const evaluatorSource = await readFile(instrumentGrader, "utf8");
    assert.doesNotMatch(evaluatorSource, /loyly|booking|weekly-schedule|heater-schedule|billing-signals/i);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

async function withCandidate(options, assertion) {
  let held = false;
  const server = http.createServer((request, response) => {
    response.setHeader("content-type", "application/json");
    if (request.method === "PUT" && request.url === "/api/rooms/pukuhuone%208/days") {
      response.end(JSON.stringify({ message: "Saved" }));
    } else if (request.method === "POST" && request.url === "/api/standing-slots") {
      held = true;
      response.statusCode = 201;
      response.end(JSON.stringify({ booking: { household: "12", dressingRoom: "pukuhuone 8", weekday: "tuesday", start: "17:00", end: "18:30" } }));
    } else if (request.method === "POST" && request.url === "/api/standing-slots/give-up") {
      held = false;
      response.end(JSON.stringify({ message: "Apartment 12 gave up tuesday 17:00 in pukuhuone 8." }));
    } else if (request.url === "/api/schedule?sauna=F") {
      const occupied = { household: "12", dressingRoom: "pukuhuone 8", weekday: "tuesday", start: "17:00", end: "18:30", available: false };
      const available = { household: null, dressingRoom: "pukuhuone 8", weekday: "saturday", start: "17:00", end: "18:30", available: true };
      response.end(JSON.stringify({ slots: held ? [occupied] : options.retainAvailableSlot ? [available] : [] }));
    } else if (request.url === "/api/heater-schedule?sauna=F") {
      response.end(JSON.stringify({ heating: held ? [{ dressingRoom: "pukuhuone 8", weekday: "tuesday", start: "17:00", end: "18:30" }] : [] }));
    } else if (request.url === "/api/billing-signals?household=12") {
      response.end(JSON.stringify({ signals: held && options.billing ? [{ household: "12", start: "17:00" }] : [] }));
    } else {
      response.statusCode = 404;
      response.end(JSON.stringify({ error: "not found" }));
    }
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  try {
    await assertion(`http://127.0.0.1:${address.port}`);
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}

async function runGrader(baseUrl, { telemetry = true } = {}) {
  const root = await mkdtemp(path.join(os.tmpdir(), "loyly-instrument-test-"));
  const otelRoot = path.join(root, "otel");
  await mkdir(path.join(otelRoot, "@item-01-007"), { recursive: true });
  if (telemetry) await writeValidTelemetry(otelRoot);
  try {
    return await runScript(grader, [baseUrl], { OTEL_FILE_EXPORT_DIR: otelRoot });
  } finally {
    await rm(root, { recursive: true, force: true });
  }
}

async function writeValidTelemetry(otelRoot) {
  await mkdir(path.join(otelRoot, "@item-01-007"), { recursive: true });
  const common = { operationId: "operation-1", eventId: "event-1", aggregateId: "slot-1", contentDigest: "sha256:test", complete: true };
  const records = [
    { ...common, kind: "domain-event", eventType: "loyly/standing-slot-confirmed", schemaVersion: "1" },
    ...["weekly-schedule", "heater-schedule", "billing-signals"].map((projectionName) =>
      ({ ...common, kind: "projection-snapshot", projectionName })),
  ];
  await writeFile(path.join(otelRoot, "@item-01-007", "runtime-signals.jsonl"),
    `${records.map((record) => JSON.stringify(record)).join("\n")}\n`);
}

function runtimeContract(options) {
  const required = [...options.correlationFields, options.invariantField];
  return {
    itemId: options.itemId,
    relativePath: options.relativePath,
    encoding: "application/x-ndjson",
    discriminator: { field: options.discriminatorField, values: [options.anchorKind, options.memberKind] },
    authority: "candidate-facts-only",
    records: {
      [options.anchorKind]: { count: { exactly: 1 }, required },
      [options.memberKind]: {
        count: { exactly: options.memberCount },
        required: [...required, options.memberIdentityField],
        fieldValues: { [options.memberIdentityField]: options.memberIdentityValues },
      },
    },
    correlation: {
      anchorKind: options.anchorKind,
      memberKinds: [options.memberKind],
      fields: options.correlationFields,
    },
    invariants: [{
      name: "shared-state",
      type: "fields-equal",
      recordKinds: [options.anchorKind, options.memberKind],
      field: options.invariantField,
    }],
    forbidden: ["verdict", "passed", "result"],
  };
}

async function writeJsonLines(root, relativePath, records) {
  const target = path.join(root, relativePath);
  await mkdir(path.dirname(target), { recursive: true });
  await writeFile(target, `${records.map((record) => JSON.stringify(record)).join("\n")}\n`);
}

function runScript(script, args, environment) {
  return runProgram("bash", [script, ...args], environment);
}

function runProgram(program, args, environment = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(program, args, { env: { ...process.env, ...environment } });
    let stdout = "";
    let stderr = "";
    child.stdout.setEncoding("utf8").on("data", (chunk) => stdout += chunk);
    child.stderr.setEncoding("utf8").on("data", (chunk) => stderr += chunk);
    child.once("error", reject);
    child.once("exit", (exitCode) => resolve({ exitCode, stdout: stdout.trim(), stderr }));
  });
}
