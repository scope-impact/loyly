import { runOracleFixtures } from "./oracle-fixture-runner.ts";

let input = "";
for await (const chunk of process.stdin) input += chunk;

try {
  const request = JSON.parse(input);
  const result = await runOracleFixtures(request);
  process.stdout.write(`${JSON.stringify({ ok: true, result })}\n`);
} catch (error) {
  process.stdout.write(`${JSON.stringify({ ok: false, error: error instanceof Error ? error.message : String(error) })}\n`);
  process.exitCode = 1;
}
