import assert from "node:assert/strict";
import test from "node:test";
import register from "../extension.ts";

test("Pi extension exposes progress reconciliation and builder observation as read-only tools", () => {
  const tools = [];
  register({ registerTool: (tool) => tools.push(tool), on: () => {} });

  const progress = tools.find(({ name }) => name === "get_regeneration_cycle_status");
  const builder = tools.find(({ name }) => name === "inspect_builder_session");
  assert.deepEqual(progress.parameters.required, ["tickId"]);
  assert.deepEqual(builder.parameters.required, ["builderSessionRef"]);
  assert.deepEqual(
    tools.filter(({ name }) => name.includes("builder_session")).map(({ name }) => name),
    ["inspect_builder_session"],
  );
});
