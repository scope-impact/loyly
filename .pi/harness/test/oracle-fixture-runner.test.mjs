import assert from "node:assert/strict";
import test from "node:test";
import { runOracleFixtures } from "../src/oracle-fixture-runner.ts";

test("oracle fixture runner preserves grader verdicts from one Gondolin room", async () => {
  const commands = [];
  let closed = false;
  const result = await runOracleFixtures({
    fixtureSource: "fixture source",
    graderRoot: "/grader-root",
    cases: [
      { itemId: "@item-01-007", role: "carried", graderPath: "grade.mjs" },
      { itemId: "@item-01-007", role: "uncarried", graderPath: "grade.mjs" },
    ],
  }, {
    writeFixture: async () => ({ fixtureRoot: "/fixture-root", cleanup: async () => {} }),
    openRoom: async () => ({
      exec: async (command) => {
        commands.push(command);
        const failed = command.includes("uncarried");
        return {
          exitCode: 0,
          stdout: `${failed ? '[{\"verdict\":\"fail\"}]' : '[{\"verdict\":\"pass\"}]'}\n__LOyly_GRADER_EXIT__=${failed ? 1 : 0}\n`,
          stderr: "",
        };
      },
      close: async () => { closed = true; },
    }),
  });

  assert.equal(commands.length, 2);
  assert.match(commands[0], /node '\/grader\/grade\.mjs'/);
  assert.match(commands[0], /--item '@item-01-007'/);
  assert.equal(result.backend, "gondolin");
  assert.deepEqual(result.cases.map(({ itemId, role, exitCode }) => ({ itemId, role, exitCode })), [
    { itemId: "@item-01-007", role: "carried", exitCode: 0 },
    { itemId: "@item-01-007", role: "uncarried", exitCode: 1 },
  ]);
  assert.equal(closed, true);
});
