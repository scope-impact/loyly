import assert from "node:assert/strict";
import test from "node:test";
import { modelFailure } from "../src/builder-outcome.ts";

const assistant = (stopReason, errorMessage) => ({ role: "assistant", content: [], stopReason, errorMessage });

test("a session whose last turn ended on a model error is a failed builder, named by the error", () => {
  assert.equal(
    modelFailure([assistant("toolUse"), assistant("error", "Codex SSE response headers timed out after 300000ms")]),
    "builder session ended on a model error: Codex SSE response headers timed out after 300000ms",
  );
  assert.equal(modelFailure([assistant("aborted")]), "builder session ended on a model error: aborted");
});

test("a session that stopped normally, or recovered from an earlier error, is not a model failure", () => {
  assert.equal(modelFailure([assistant("error", "fetch failed"), assistant("stop")]), undefined);
  assert.equal(modelFailure([assistant("stop"), { role: "toolResult", content: [] }]), undefined);
  assert.equal(modelFailure([]), undefined);
});
