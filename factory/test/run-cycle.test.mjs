// One whole cycle through the runner, with the real fence, loop, gate and instrument, and a
// scripted Claude as builder and grader. It runs in a clone of this repository, so nothing
// it writes (the room, harness/cycles/97) touches the checkout the test is run from.
import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { cpSync, existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
const fake = path.join(repo, "agent", "test", "fake-claude.mjs");

function cloneOfThisRepo() {
	const base = mkdtempSync(path.join(tmpdir(), "loyly-factory-"));
	const clone = path.join(base, "loyly");
	execFileSync("git", ["clone", "-q", repo, clone]);
	// The working tree as it is now, factory/ included, committed in the clone so the fence sees it.
	for (const d of ["factory", "agent", "harness"]) cpSync(path.join(repo, d), path.join(clone, d), { recursive: true, filter: (s) => !s.includes("node_modules") });
	cpSync(path.join(repo, ".gitignore"), path.join(clone, ".gitignore"));
	// Real installs, never symlinks into this checkout: npm ci in the room clears node_modules,
	// and through a symlink it once cleared the real one (2026-11-18).
	execFileSync("npm", ["ci", "--prefix", "harness", "--no-audit", "--no-fund"], { cwd: clone, stdio: "ignore" });
	execFileSync("npm", ["ci", "--no-audit", "--no-fund"], { cwd: path.join(clone, "agent"), stdio: "ignore" });
	execFileSync("git", ["add", "-A"], { cwd: clone });
	execFileSync("git", ["-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "the working tree, for the test"], { cwd: clone });
	return { base, clone };
}

const startSh = `#!/usr/bin/env bash
exec node -e 'require("http").createServer((q, r) => r.end("<h1>sauna F</h1>")).listen(+process.argv[1], "127.0.0.1")' "$1"
`;

test("a whole cycle: fenced room, built, ratified, graded by a separate run, scored, recorded", () => {
	const { base, clone } = cloneOfThisRepo();
	const state = mkdtempSync(path.join(tmpdir(), "fake-claude-"));
	const checklistIds = JSON.parse(execFileSync("node", ["harness/gen_checklist.ts"], { cwd: clone, encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] })).items.map((i) => i.id);
	const verdicts = checklistIds.map((id, k) => ({ id, verdict: k === 0 ? "fail" : "pass", evidence: `observed ${id}` }));
	writeFileSync(
		path.join(state, "script.json"),
		JSON.stringify([
			{ answer: "plan: one ticket" },
			{
				writes: [
					{ path: "impl/test.sh", content: "#!/usr/bin/env bash\nexit 0\n", mode: "755" },
					{ path: "impl/start.sh", content: startSh, mode: "755" },
				],
				answer: "built it\nCLAIM_DONE: the slice is built",
			},
			// The grader: run in its own directory, it writes verdicts.json there.
			{ writes: [{ path: "verdicts.json", content: JSON.stringify(verdicts) }], answer: `${checklistIds.length} graded` },
		]),
	);
	const workdir = path.join(base, "cycles");
	const r = spawnSync(process.execPath, [path.join(clone, "factory", "run-cycle.mjs"), "--cycle", "97", "--workdir", workdir, "--isolation", "none", "--rounds", "3", "--port", "8961"], {
		encoding: "utf8",
		timeout: 300_000,
		env: { ...process.env, LOYLY_CLAUDE_BIN: fake, FAKE_CLAUDE_STATE: state, CLAUDE_CONFIG_DIR: path.join(state, "config"), LOYLY_AGENT_WORKER: "claude-code" },
	});
	assert.equal(r.status, 0, r.stdout + r.stderr);
	const cycleDir = path.join(workdir, "cycle-97");
	const st = JSON.parse(readFileSync(path.join(cycleDir, "state.json"), "utf8"));
	assert.equal(st.stage, "recorded");
	assert.equal(st.isolation, "none");
	assert.equal(st.outcome, "done");
	assert.equal(st.ratified.summary, "the slice is built");
	assert.match(st.score, new RegExp(`scored cycle 97: ${checklistIds.length - 1}/${checklistIds.length} pass`));
	assert.match(r.stdout, /WARNING: isolation none/);
	// The instrument's record, in the clone's own harness/cycles, and a copy in the records.
	const scores = JSON.parse(readFileSync(path.join(clone, "harness", "cycles", "97", "scores.json"), "utf8"));
	assert.equal(scores.items.length, checklistIds.length);
	for (const f of ["loop-store", "loop.log", "verdicts.json", "scoreboard.txt", "grader-transcript.jsonl", "state.json", "harness-cycle/scores.json"]) {
		assert.ok(existsSync(path.join(cycleDir, "records", f)), `records/${f}`);
	}
	// The builder never had the checklist: the room has no harness/cycles at all.
	assert.equal(existsSync(path.join(cycleDir, "room", "harness", "cycles")), false);
	// Run again: everything is recorded, so it only resumes and finishes.
	const again = spawnSync(process.execPath, [path.join(clone, "factory", "run-cycle.mjs"), "--cycle", "97", "--workdir", workdir], { encoding: "utf8", timeout: 60_000 });
	assert.equal(again.status, 0, again.stdout + again.stderr);
	assert.match(again.stdout, /resuming after stage "recorded"/);
});
