import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, existsSync } from "node:fs";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { nextCycle } from "../dispatcher.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const dispatcher = path.join(here, "..", "dispatcher.mjs");

/** A fake Slack: a channel people post into, and every message the bot sends. */
async function fakeSlack() {
	const channel = [];
	const posts = [];
	let last = 0;
	const ts = () => {
		last = Math.max(last + 1, Math.round(Date.now() * 1000));
		return `${String(last).slice(0, -6)}.${String(last).slice(-6)}`;
	};
	const server = createServer((req, res) => {
		const url = new URL(req.url, "http://x");
		const reply = (body) => (res.writeHead(200, { "content-type": "application/json" }), res.end(JSON.stringify(body)));
		if (url.pathname.endsWith("/conversations.history")) {
			const oldest = url.searchParams.get("oldest");
			return reply({ ok: true, messages: channel.filter((m) => !oldest || Number(m.ts) > Number(oldest)) });
		}
		let body = "";
		req.on("data", (c) => (body += c));
		req.on("end", () => {
			const msg = JSON.parse(body);
			const t = ts();
			posts.push({ ...msg, ts: t });
			reply({ ok: true, ts: t, channel: msg.channel });
		});
	});
	await new Promise((r) => server.listen(0, "127.0.0.1", r));
	return {
		api: `http://127.0.0.1:${server.address().port}/api`,
		posts,
		say: (user, text, extra = {}) => {
			const m = { type: "message", user, text, ts: ts(), ...extra };
			channel.push(m);
			return m;
		},
		close: () => (server.closeAllConnections(), server.close()),
	};
}

function startDispatcher(slack, workdir, runnerLog, mode = "finish") {
	const child = spawn(process.execPath, [dispatcher], {
		env: {
			...process.env,
			SLACK_API_URL: slack.api,
			SLACK_BOT_TOKEN: "xoxb-test",
			SLACK_CHANNEL: "C0FACTORY",
			SLACK_ALLOWED_USERS: "U1",
			LOYLY_WORKDIR: workdir,
			LOYLY_DISPATCH_POLL_MS: "100",
			LOYLY_RUNNER: path.join(here, "fake-runner.mjs"),
			FAKE_RUNNER_LOG: runnerLog,
			FAKE_RUNNER_MODE: mode,
		},
		stdio: ["ignore", "pipe", "pipe"],
	});
	let out = "";
	child.stdout.on("data", (d) => (out += d));
	child.stderr.on("data", (d) => (out += d));
	return { child, output: () => out, stop: () => new Promise((r) => (child.on("exit", r), child.kill("SIGTERM"))) };
}

async function until(pred, ms = 15_000) {
	const end = Date.now() + ms;
	while (Date.now() < end) {
		if (pred()) return;
		await new Promise((r) => setTimeout(r, 50));
	}
	throw new Error("timed out");
}

const runs = (f) => (existsSync(f) ? readFileSync(f, "utf8").trim().split("\n").filter(Boolean).map((l) => JSON.parse(l)) : []);

test("an allowed person's 'run cycle 05' starts a cycle in that message's thread, and its finish is reported there", async () => {
	const slack = await fakeSlack();
	const workdir = mkdtempSync(path.join(tmpdir(), "dispatch-"));
	const log = path.join(workdir, "runner.jsonl");
	const d = startDispatcher(slack, workdir, log);
	try {
		await new Promise((r) => setTimeout(r, 400));
		slack.say("U2", "run cycle 05"); // not allowed
		slack.say("U1", "run cycle 06", { bot_id: "B1", subtype: "bot_message" }); // a bot
		const ask = slack.say("U1", "<@UBOT> run cycle 05");
		await until(() => slack.posts.some((p) => /Cycle 05 is finished/.test(p.text)));
		const r = runs(log);
		assert.equal(r.length, 1, "exactly one runner start");
		assert.equal(r[0].thread, ask.ts);
		assert.deepEqual(r[0].args.slice(0, 2), ["--cycle", "05"]);
		assert.ok(r[0].args.includes("--approve"));
		assert.ok(slack.posts.every((p) => p.thread_ts === ask.ts), "every post is in the asker's thread");
		assert.match(slack.posts[0].text, /Starting cycle 05 for <@U1>/);
		assert.match(slack.posts.at(-1).text, /scored cycle 05: 11\/11 pass. Built in 27 rounds for \$17.03/);
		const state = JSON.parse(readFileSync(path.join(workdir, "dispatcher.json"), "utf8"));
		assert.equal(state.active, null);
		assert.equal(state.finished[0].cycle, "05");
	} finally {
		await d.stop();
		slack.close();
	}
});

test("one cycle at a time, and status says which", async () => {
	const slack = await fakeSlack();
	const workdir = mkdtempSync(path.join(tmpdir(), "dispatch-"));
	const log = path.join(workdir, "runner.jsonl");
	const d = startDispatcher(slack, workdir, log, "slow");
	try {
		await new Promise((r) => setTimeout(r, 400));
		const first = slack.say("U1", "run cycle 07");
		await until(() => slack.posts.some((p) => /Starting cycle 07/.test(p.text)));
		const second = slack.say("U1", "run cycle 08");
		const status = slack.say("U1", "status");
		await until(() => slack.posts.some((p) => p.thread_ts === second.ts) && slack.posts.some((p) => p.thread_ts === status.ts));
		assert.match(slack.posts.find((p) => p.thread_ts === second.ts).text, /Cycle 07 is already running; one cycle at a time/);
		assert.match(slack.posts.find((p) => p.thread_ts === status.ts).text, /Cycle 07 is running/);
		await until(() => slack.posts.some((p) => /Cycle 07 is finished/.test(p.text)));
		assert.equal(runs(log).length, 1);
		assert.ok(slack.posts.filter((p) => /finished/.test(p.text)).every((p) => p.thread_ts === first.ts));
	} finally {
		await d.stop();
		slack.close();
	}
});

test("a runner that dies is started again and the cycle resumes; a restarted dispatcher acts on nothing twice", async () => {
	const slack = await fakeSlack();
	const workdir = mkdtempSync(path.join(tmpdir(), "dispatch-"));
	const log = path.join(workdir, "runner.jsonl");
	let d = startDispatcher(slack, workdir, log, "die-once");
	try {
		await new Promise((r) => setTimeout(r, 400));
		slack.say("U1", "run cycle 09");
		await until(() => slack.posts.some((p) => /Cycle 09 is finished/.test(p.text)));
		assert.ok(slack.posts.some((p) => /stopped at stage "built"; started it again \(1 of 3\)/.test(p.text)));
		assert.equal(runs(log).length, 2);
		await d.stop();
		// The same channel, read again by a new dispatcher: the cursor is in dispatcher.json.
		const before = slack.posts.length;
		d = startDispatcher(slack, workdir, log);
		await new Promise((r) => setTimeout(r, 800));
		assert.equal(slack.posts.length, before);
		assert.equal(runs(log).length, 2);
	} finally {
		await d.stop();
		slack.close();
	}
});

test("the next cycle is one past the highest in harness/cycles", () => {
	const repo = mkdtempSync(path.join(tmpdir(), "repo-"));
	assert.equal(nextCycle(repo), "01");
	for (const c of ["01", "02", "notes"]) mkdirSync(path.join(repo, "harness", "cycles", c), { recursive: true });
	assert.equal(nextCycle(repo), "03");
});
