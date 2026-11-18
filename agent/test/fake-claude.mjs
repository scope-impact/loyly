#!/usr/bin/env node
// A stand-in for `claude -p`, for tests: each invocation takes the next step from a script,
// writes the files it says, records its argv, and prints a result shaped like Claude Code's
// `--output-format json`. A step with `crash: true` kills the process that started it.
//   FAKE_CLAUDE_STATE  a directory holding script.json; calls.jsonl and a counter go there too
import { appendFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import path from "node:path";

const state = process.env.FAKE_CLAUDE_STATE;
const args = process.argv.slice(2);
const flag = (name) => (args.includes(name) ? args[args.indexOf(name) + 1] : undefined);
const counterFile = path.join(state, "counter");
const n = existsSync(counterFile) ? Number(readFileSync(counterFile, "utf8")) : 0;
writeFileSync(counterFile, String(n + 1));
appendFileSync(path.join(state, "calls.jsonl"), `${JSON.stringify({ args, cwd: process.cwd() })}\n`);
const step = JSON.parse(readFileSync(path.join(state, "script.json"), "utf8"))[n] ?? { answer: "(no step scripted)" };

// Claude Code keeps a transcript per session; the loop checks for it before resuming.
const session = flag("--session-id") ?? flag("--resume");
const projects = path.join(process.env.CLAUDE_CONFIG_DIR, "projects", process.cwd().replace(/[^a-zA-Z0-9]/g, "-"));
mkdirSync(projects, { recursive: true });
appendFileSync(path.join(projects, `${session}.jsonl`), "{}\n");

for (const w of step.writes ?? []) {
	mkdirSync(path.dirname(w.path), { recursive: true });
	writeFileSync(w.path, w.content, w.mode ? { mode: Number.parseInt(w.mode, 8) } : undefined);
}
if (step.crash) {
	process.kill(process.ppid, "SIGKILL");
	setTimeout(() => process.exit(1), 2000);
} else {
	console.log(
		JSON.stringify({
			type: "result",
			subtype: "success",
			is_error: false,
			result: step.answer ?? "",
			session_id: session,
			total_cost_usd: 0.25,
			permission_denials: (step.denied ?? []).map((p) => ({ tool_name: "Write", tool_input: { file_path: path.resolve(p), content: "x" } })),
		}),
	);
}
