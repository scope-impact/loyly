#!/usr/bin/env node
// Stands in for factory/run-cycle.mjs: records how it was started, then behaves as told.
//   FAKE_RUNNER_LOG   a file each start appends one JSON line to
//   FAKE_RUNNER_MODE  finish (default) | slow (2 s, then finish) | die-once (exit 1 at stage "built" the first time)
import { appendFileSync, existsSync, mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { parseArgs } from "node:util";

const { values } = parseArgs({ options: { cycle: { type: "string" }, workdir: { type: "string" }, approve: { type: "boolean" } }, strict: false });
appendFileSync(process.env.FAKE_RUNNER_LOG, `${JSON.stringify({ args: process.argv.slice(2), thread: process.env.SLACK_THREAD_TS })}\n`);
const dir = path.join(values.workdir, `cycle-${values.cycle}`);
mkdirSync(dir, { recursive: true });
const write = (stage, extra = {}) => writeFileSync(path.join(dir, "state.json"), JSON.stringify({ cycle: values.cycle, stage, ...extra }));
const mode = process.env.FAKE_RUNNER_MODE ?? "finish";
const marker = path.join(dir, "died-once");
if (mode === "die-once" && !existsSync(marker)) {
	writeFileSync(marker, "");
	write("built");
	process.exit(1);
}
if (mode === "slow") await new Promise((r) => setTimeout(r, 2000));
write("recorded", { score: `scored cycle ${values.cycle}: 11/11 pass`, scoreboard: `cycle ${values.cycle}: 11/11 checklist items pass\n`, rounds: 27, costUsd: 17.03 });
