import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const scripted = path.join(here, "scripted.mjs");
const fake = path.join(here, "fake-claude.mjs");

function fixture(steps) {
	const root = mkdtempSync(path.join(tmpdir(), "loyly-claude-"));
	const git = (...args) => execFileSync("git", args, { cwd: root, stdio: "pipe" });
	for (const d of ["docs/plan", "harness", "impl"]) mkdirSync(path.join(root, d), { recursive: true });
	writeFileSync(path.join(root, "docs/plan/slice.md"), "the slice\n");
	writeFileSync(path.join(root, "impl/README.md"), "build here\n");
	writeFileSync(path.join(root, ".gitignore"), ".loyly-agent/\n");
	writeFileSync(path.join(root, "harness/verify.sh"), '#!/usr/bin/env bash\ncd "$(dirname "$0")/.." && bash impl/test.sh && echo "gate: green"\n');
	chmodSync(path.join(root, "harness/verify.sh"), 0o755);
	git("init", "-q");
	git("add", "-A");
	git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "fixture");
	const state = mkdtempSync(path.join(tmpdir(), "fake-claude-"));
	writeFileSync(path.join(state, "script.json"), JSON.stringify(steps));
	return { root, state, store: path.join(root, ".loyly-agent/cycle.sqlite") };
}

function drive(fx, options) {
	const r = spawnSync(process.execPath, [scripted, fx.root, fx.store, "[]", JSON.stringify({ worker: "claude-code", reviewEvery: 0, ...options })], {
		encoding: "utf8",
		timeout: 60_000,
		env: {
			...Object.fromEntries(Object.entries(process.env).filter(([k]) => !k.startsWith("SLACK_"))),
			LOYLY_CLAUDE_BIN: fake,
			FAKE_CLAUDE_STATE: fx.state,
			CLAUDE_CONFIG_DIR: path.join(fx.state, "config"),
		},
	});
	const line = r.stdout.split("\n").find((l) => l.startsWith("CYCLE "));
	return { ...r, cycle: line ? JSON.parse(line.slice(6)) : undefined };
}

function calls(fx) {
	return readFileSync(path.join(fx.state, "calls.jsonl"), "utf8")
		.trim()
		.split("\n")
		.map((l) => JSON.parse(l))
		.map(({ args }) => {
			const flag = (name) => (args.includes(name) ? args[args.indexOf(name) + 1] : undefined);
			return {
				prompt: flag("-p"),
				system: flag("--append-system-prompt"),
				allow: JSON.parse(flag("--settings")).permissions.allow,
				mode: flag("--permission-mode"),
				session: flag("--session-id"),
				resume: flag("--resume"),
				model: flag("--model"),
			};
		});
}

test("Claude Code as the worker: scoped permissions, a session per round, TDD measured, a claim ratified by the gate", () => {
	const fx = fixture([
		{ answer: "plan: one ticket" },
		{ writes: [{ path: "impl/test.sh", content: "test -f impl/app.txt\n" }], answer: "wrote the failing test" },
		{ writes: [{ path: "impl/app.txt", content: "app\n" }], answer: "made it pass" },
		{ answer: "all built\nCLAIM_DONE: slice built" },
	]);
	const r = drive(fx, { maxRounds: 5, claudeModel: "opus" });
	assert.equal(r.status, 0, r.stderr + r.stdout);
	const c = r.cycle;
	assert.equal(c.phase, "done");
	assert.equal(c.ratified.summary, "slice built");
	assert.match(c.ratified.output, /gate: green/);
	assert.deepEqual(
		c.history.map((h) => [h.kind, h.tddBefore, h.tddAfter, h.worker, h.costUsd]),
		[
			["plan", "GREEN", "GREEN", "claude-code", 0.25],
			["build", "GREEN", "RED", "claude-code", 0.25],
			["build", "RED", "GREEN", "claude-code", 0.25],
			["build", "GREEN", "GREEN", "claude-code", 0.25],
		],
	);
	const k = calls(fx);
	assert.equal(k.length, 4);
	assert.ok(k.every((x) => x.mode === "dontAsk" && x.model === "opus" && x.session && !x.resume));
	assert.equal(new Set(k.map((x) => x.session)).size, 4, "every round is a new session: the orient step");
	assert.ok(k[0].allow.includes("Edit(./docs/plan/**)") && !k[0].allow.some((a) => a.includes("impl")));
	assert.ok(k[1].allow.includes("Edit(./impl/**)") && !k[1].allow.some((a) => a.includes("docs")));
	assert.match(k[1].system, /TDD state GREEN/);
	assert.match(k[2].system, /TDD state RED/);
	assert.match(k[1].prompt, /CLAIM_DONE:/);
});

test("refused edits are recorded, and a claim the gate refuses goes back to the next round", () => {
	const fx = fixture([
		{ answer: "plan" },
		{ denied: ["docs/plan/slice.md"], answer: "tried to change the spec\nCLAIM_DONE: trust me" },
		{ answer: "fixing what the gate said" },
	]);
	const r = drive(fx, { maxRounds: 2 });
	assert.equal(r.status, 0, r.stderr + r.stdout);
	const round1 = r.cycle.history[1];
	assert.deepEqual(round1.refused, ["Write docs/plan/slice.md"]);
	assert.ok(round1.notes.some((n) => /spec-lock refused 1 edit/.test(n)));
	assert.ok(round1.notes.some((n) => /claimed done \("trust me"\) and the gate refused it/.test(n)));
	assert.equal(r.cycle.ratified, null);
	assert.match(calls(fx)[2].prompt, /You claimed done and the gate refused it/);
});

test("the loop killed while Claude Code works: the next process resumes that Claude session", () => {
	const fx = fixture([
		{ answer: "plan" },
		{ writes: [{ path: "impl/test.sh", content: "exit 1\n" }], crash: true },
		{ answer: "picked up where I was" },
	]);
	const first = drive(fx, { maxRounds: 1 });
	assert.equal(first.signal, "SIGKILL", first.stdout + first.stderr);
	const second = drive(fx, { maxRounds: 1 });
	assert.equal(second.status, 0, second.stderr + second.stdout);
	assert.match(second.stdout, /resuming build-1 at stage submitted/);
	const k = calls(fx);
	assert.equal(k.length, 3);
	assert.equal(k[2].resume, k[1].session, "the same Claude session, resumed");
	assert.equal(k[2].session, undefined);
	assert.match(k[2].prompt, /interrupted/);
	assert.deepEqual(
		second.cycle.history.map((h) => [h.kind, h.round, h.tddAfter, h.outcome]),
		[
			["plan", 0, "GREEN", "answered"],
			["build", 1, "RED", "answered"],
		],
	);
});
