import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const scripted = path.join(path.dirname(fileURLToPath(import.meta.url)), "scripted.mjs");

/** A miniature repository with the shape the loop expects: docs, a gate, an impl dir. */
function fixture() {
	const root = mkdtempSync(path.join(tmpdir(), "loyly-agent-"));
	const git = (...args) => execFileSync("git", args, { cwd: root, stdio: "pipe" });
	mkdirSync(path.join(root, "docs/plan"), { recursive: true });
	mkdirSync(path.join(root, "harness"), { recursive: true });
	mkdirSync(path.join(root, "impl"), { recursive: true });
	writeFileSync(path.join(root, "docs/plan/slice.md"), "the slice\n");
	writeFileSync(path.join(root, "README.md"), "fixture\n");
	writeFileSync(path.join(root, "impl/README.md"), "build here\n");
	writeFileSync(path.join(root, ".gitignore"), ".loyly-agent/\n");
	// The gate: passes only when the suite exists and is green.
	writeFileSync(path.join(root, "harness/verify.sh"), '#!/usr/bin/env bash\ncd "$(dirname "$0")/.." && bash impl/test.sh && echo "gate: green"\n');
	chmodSync(path.join(root, "harness/verify.sh"), 0o755);
	git("init", "-q");
	git("add", "-A");
	git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "fixture");
	return { root, store: path.join(root, ".loyly-agent/cycle.sqlite") };
}

function drive(fx, script, options = {}) {
	const result = spawnSync(process.execPath, [scripted, fx.root, fx.store, JSON.stringify(script), JSON.stringify(options)], {
		encoding: "utf8",
		timeout: 60_000,
		// Never the developer's real Slack: these tests are about the loop, not the channel.
		env: Object.fromEntries(Object.entries(process.env).filter(([k]) => !k.startsWith("SLACK_"))),
	});
	const line = result.stdout.split("\n").find((l) => l.startsWith("CYCLE "));
	const calls = result.stdout.split("\n").find((l) => l.startsWith("FAUX_CALLS "));
	return {
		...result,
		cycle: line ? JSON.parse(line.slice(6)) : undefined,
		calls: calls ? Number(calls.slice(11)) : undefined,
	};
}

const write = (p, content) => ({ name: "write", args: { path: p, content } });
const bash = (command) => ({ name: "bash", args: { command } });

test("plan, then RED/GREEN rounds measured outside the agent, then a ratified DONE", () => {
	const fx = fixture();
	const r = drive(
		fx,
		[
			{ text: "plan: ticket 1 covers the weekly schedule" },
			{ tools: [write("impl/test.sh", "test -f impl/app.txt\n")] },
			{ text: "wrote the failing test" },
			{ tools: [write("impl/app.txt", "app\n")] },
			{ text: "made it pass" },
			{ tools: [{ name: "claim_done", args: { summary: "slice built" } }] },
		],
		{ reviewEvery: 0, maxRounds: 5 },
	);
	assert.equal(r.status, 0, r.stderr + r.stdout);
	const c = r.cycle;
	assert.equal(c.phase, "done");
	assert.equal(c.plan, "plan: ticket 1 covers the weekly schedule");
	assert.deepEqual(
		c.history.map((h) => [h.kind, h.tddBefore, h.tddAfter]),
		[
			["plan", "GREEN", "GREEN"],
			["build", "GREEN", "RED"],
			["build", "RED", "GREEN"],
			["build", "GREEN", "GREEN"],
		],
	);
	assert.match(c.ratified.output, /gate: green/);
});

test("spec-lock and scope fence: a blocked write, and a bash side effect reverted", () => {
	const fx = fixture();
	const r = drive(
		fx,
		[
			{ text: "plan" },
			{ tools: [write("docs/plan/slice.md", "rewritten mid-build\n"), bash("echo sneaky > README.md && echo new > stray.txt")] },
			{ text: "done with the round" },
		],
		{ reviewEvery: 0, maxRounds: 1 },
	);
	assert.equal(r.status, 0, r.stderr + r.stdout);
	assert.equal(readFileSync(path.join(fx.root, "docs/plan/slice.md"), "utf8"), "the slice\n");
	assert.equal(readFileSync(path.join(fx.root, "README.md"), "utf8"), "fixture\n");
	assert.equal(existsSync(path.join(fx.root, "stray.txt")), false);
	const round = r.cycle.history[1];
	assert.deepEqual(round.reverted.sort(), ["README.md", "stray.txt"]);
	assert.equal(r.cycle.phase, "exhausted");
});

test("a claim of done that the gate refuses is not done", () => {
	const fx = fixture();
	const r = drive(
		fx,
		[
			{ text: "plan" },
			{ tools: [{ name: "claim_done", args: { summary: "trust me" } }] },
			{ text: "the gate said no" },
		],
		{ reviewEvery: 0, maxRounds: 1 },
	);
	assert.equal(r.status, 0, r.stderr + r.stdout);
	assert.equal(r.cycle.ratified, null);
	assert.equal(r.cycle.phase, "exhausted");
});

test("every Nth round reviews, cannot edit, and its findings reach the next round", () => {
	const fx = fixture();
	const r = drive(
		fx,
		[
			{ text: "plan" },
			{ text: "round 1 built nothing" },
			{ tools: [write("impl/x.txt", "review edits")] },
			{ text: "1. impl/README.md invents a rule" },
			{ text: "round 3" },
		],
		{ reviewEvery: 2, maxRounds: 3 },
	);
	assert.equal(r.status, 0, r.stderr + r.stdout);
	assert.deepEqual(r.cycle.history.map((h) => h.kind), ["plan", "build", "review", "build"]);
	assert.equal(existsSync(path.join(fx.root, "impl/x.txt")), false);
	assert.deepEqual(r.cycle.findings, ["1. impl/README.md invents a rule"]);
});

test("killed mid-round, a new process continues that round: nothing repeated, nothing skipped", () => {
	const fx = fixture();
	// Process 1: plan, round 1 writes a failing test, round 2 dies in the middle of a tool call.
	const first = drive(
		fx,
		[
			{ text: "plan" },
			{ tools: [write("impl/test.sh", "test -f impl/app.txt\n")] },
			{ text: "wrote the failing test" },
			{ tools: [write("impl/app.txt", "app\n")] },
			{ tools: [bash("kill -9 $PPID; sleep 5")] },
		],
		{ reviewEvery: 0, maxRounds: 2 },
	);
	assert.equal(first.signal, "SIGKILL", `expected the driver to be killed, got ${first.status}\n${first.stdout}\n${first.stderr}`);
	assert.equal(first.cycle, undefined);

	// Process 2: the same store. Only one model answer is scripted, so any repeated plan or
	// round 1 would run out of responses and fail.
	const second = drive(fx, [{ text: "recovered; the test passes now" }], { reviewEvery: 0, maxRounds: 2 });
	assert.equal(second.status, 0, second.stderr + second.stdout);
	assert.match(second.stdout, /resuming build-2 at stage submitted/);
	assert.equal(second.calls, 1);
	const c = second.cycle;
	assert.deepEqual(
		c.history.map((h) => [h.kind, h.round, h.tddBefore, h.tddAfter]),
		[
			["plan", 0, "GREEN", "GREEN"],
			["build", 1, "GREEN", "RED"],
			["build", 2, "RED", "GREEN"],
		],
	);
	assert.equal(c.history[2].outcome, "answered");
	assert.equal(c.pending, null);
	assert.equal(c.phase, "exhausted");
});

test("refuses to start a cycle over uncommitted work outside the slice's scope", () => {
	const fx = fixture();
	writeFileSync(path.join(fx.root, "README.md"), "my unsaved edit\n");
	const r = drive(fx, [{ text: "plan" }], { reviewEvery: 0, maxRounds: 1 });
	assert.notEqual(r.status, 0);
	assert.match(r.stderr, /refusing to start a cycle over uncommitted changes/);
	assert.equal(readFileSync(path.join(fx.root, "README.md"), "utf8"), "my unsaved edit\n");
});
