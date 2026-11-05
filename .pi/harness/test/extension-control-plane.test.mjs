import assert from "node:assert/strict";
import test from "node:test";
import register from "../extension.ts";

test("Pi session explores slices while approved contracts submit directly to ORC", () => {
  const tools = [];
  register({ registerTool: (tool) => tools.push(tool), on: () => {} });

  const sourceCut = tools.find(({ name }) => name === "prepare_source_cut");
  const composition = tools.find(({ name }) => name === "inspect_slice_composition");
  const approvedSlice = tools.find(({ name }) => name === "submit_approved_slice");
  assert.ok(sourceCut);
  assert.deepEqual(composition.parameters.required, ["cut"]);
  assert.ok(approvedSlice);
  assert.match(approvedSlice.description, /directly to ORC/i);
  assert.equal(approvedSlice.parameters.properties.operation, undefined);
  assert.equal(tools.some(({ name }) => /submit_(initial_generation|regeneration_cycle)/.test(name)), false);
  assert.equal(tools.some(({ name }) => name === "review_harness_operation"), false);
  assert.equal(tools.some(({ name }) => name === "get_outer_orchestrator_status"), false);
});

test("Pi extension exposes progress reconciliation and builder observation as read-only tools", () => {
  const tools = [];
  register({ registerTool: (tool) => tools.push(tool), on: () => {} });

  const progress = tools.find(({ name }) => name === "get_harness_operation_status");
  const builder = tools.find(({ name }) => name === "inspect_builder_session");
  assert.deepEqual(progress.parameters.required, ["tickId"]);
  assert.deepEqual(builder.parameters.required, ["builderSessionRef"]);
  assert.deepEqual(
    tools.filter(({ name }) => name.includes("builder_session")).map(({ name }) => name),
    ["inspect_builder_session"],
  );
});
