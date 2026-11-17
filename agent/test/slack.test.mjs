import assert from "node:assert/strict";
import { execFileSync, spawn } from "node:child_process";
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { escape } from "../slack.mjs";

const scripted = path.join(path.dirname(fileURLToPath(import.meta.url)), "scripted.mjs");
const TOKEN = "xoxb-test-token-never-stored";

function fixture() {
	const root = mkdtempSync(path.join(tmpdir(), "loyly-slack-"));
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
	return { root, store: path.join(root, ".loyly-agent/cycle.sqlite") };
}

/**
 * A fake Slack Web API. `behave(n)` decides the n-th request: "ok", "500", "hang", or
 * "auth" (a permanent error). Only messages answered "ok" count as delivered.
 */
async function fakeSlack(behave = () => "ok") {
	const delivered = [];
	const requests = [];
	let n = 0;
	const server = createServer((req, res) => {
		let body = "";
		req.on("data", (c) => (body += c));
		req.on("end", () => {
			const msg = JSON.parse(body);
			requests.push({ auth: req.headers.authorization, url: req.url, msg });
			const how = behave(n++, msg);
			if (how === "hang") return; // never answered: Slack did not accept it
			if (how === "500") return res.writeHead(500).end();
			res.writeHead(200, { "content-type": "application/json" });
			if (how === "auth") return res.end(JSON.stringify({ ok: false, error: "invalid_auth" }));
			const ts = `1700000000.${String(delivered.length + 1).padStart(6, "0")}`;
			delivered.push({ ...msg, ts });
			res.end(JSON.stringify({ ok: true, ts, channel: msg.channel }));
		});
	});
	await new Promise((r) => server.listen(0, "127.0.0.1", r));
	const api = `http://127.0.0.1:${server.address().port}/api`;
	return { api, delivered, requests, close: () => (server.closeAllConnections(), server.close()) };
}

function drive(fx, slack, script, options = {}, target) {
	return new Promise((resolve) => {
		const env = {
			...Object.fromEntries(Object.entries(process.env).filter(([k]) => !k.startsWith("SLACK_"))),
			...(target ?? { SLACK_BOT_TOKEN: TOKEN, SLACK_CHANNEL: "C0LOYLY", SLACK_API_URL: slack.api }),
			LOYLY_SLACK_BACKOFF_MS: "50",
		};
		const child = spawn(process.execPath, [scripted, fx.root, fx.store, JSON.stringify(script), JSON.stringify(options)], { env });
		let stdout = "";
		let stderr = "";
		child.stdout.on("data", (d) => (stdout += d));
		child.stderr.on("data", (d) => (stderr += d));
		const timer = setTimeout(() => child.kill("SIGKILL"), 60_000);
		child.on("exit", (status, signal) => {
			clearTimeout(timer);
			const line = (p) => stdout.split("\n").find((l) => l.startsWith(p));
			const outbox = line("OUTBOX ");
			resolve({ status, signal, stdout, stderr, outbox: outbox ? JSON.parse(outbox.slice(7)) : undefined });
		});
	});
}

const write = (p, content) => ({ name: "write", args: { path: p, content } });

test("a cycle reports to one Slack thread, in order, and the token never reaches the store", async () => {
	const fx = fixture();
	const slack = await fakeSlack();
	try {
		const r = await drive(
			fx,
			slack,
			[
				{ text: "plan" },
				{ tools: [write("impl/test.sh", "test -f impl/app.txt\n")] },
				{ text: "failing test" },
				{ tools: [write("impl/app.txt", "app\n")] },
				{ text: "passes" },
				{ tools: [{ name: "claim_done", args: { summary: "slice <built> & ready" } }] },
			],
			{ reviewEvery: 0, maxRounds: 5 },
		);
		assert.equal(r.status, 0, r.stderr + r.stdout);
		const texts = slack.delivered.map((m) => m.text);
		assert.match(texts[0], /Löyly cycle started/);
		assert.match(texts[1], /\*Plan\*/);
		assert.match(texts[2], /Round 1 \(build\).*GREEN → RED/);
		assert.match(texts[3], /Round 2 \(build\).*RED → GREEN/);
		assert.match(texts[4], /Round 3 \(build\)/);
		assert.match(texts[5], /\*RATIFIED\* in round 3: slice &lt;built&gt; &amp; ready/);
		assert.equal(texts.length, 6);
		// One thread: the first message starts it, every later one is a reply in it.
		assert.equal(slack.delivered[0].thread_ts, undefined);
		for (const m of slack.delivered.slice(1)) assert.equal(m.thread_ts, slack.delivered[0].ts);
		assert.ok(slack.requests.every((q) => q.auth === `Bearer ${TOKEN}` && q.url === "/api/chat.postMessage"));
		assert.equal(r.outbox.queue.length, 0);
		assert.equal(r.outbox.sent, 6);
		for (const f of ["", "-wal"]) {
			let bytes = "";
			try {
				bytes = readFileSync(`${fx.store}${f}`, "latin1");
			} catch {}
			assert.ok(!bytes.includes(TOKEN), `the token is in ${fx.store}${f}`);
		}
	} finally {
		slack.close();
	}
});

test("Slack failing with 500s is retried with backoff; nothing is lost or reordered", async () => {
	const fx = fixture();
	const slack = await fakeSlack((n) => (n < 3 ? "500" : "ok"));
	try {
		const r = await drive(fx, slack, [{ text: "plan" }, { text: "round 1" }], { reviewEvery: 0, maxRounds: 1 });
		assert.equal(r.status, 0, r.stderr + r.stdout);
		assert.deepEqual(
			slack.delivered.map((m) => m.text.split("*")[1]),
			["Löyly cycle started", "Plan", "Round 1 (build)", "Cycle exhausted"],
		);
		assert.equal(slack.requests.length, 7);
	} finally {
		slack.close();
	}
});

test("a permanent Slack error drops the message loudly instead of retrying forever", async () => {
	const fx = fixture();
	const slack = await fakeSlack((n) => (n === 0 ? "auth" : "ok"));
	try {
		const r = await drive(fx, slack, [{ text: "plan" }, { text: "round 1" }], { reviewEvery: 0, maxRounds: 1 });
		assert.equal(r.status, 0, r.stderr + r.stdout);
		assert.equal(r.outbox.dropped, 1);
		assert.equal(slack.delivered.length, 3);
		assert.match(slack.delivered[0].text, /\*Plan\*/);
	} finally {
		slack.close();
	}
});

test("killed with messages undelivered, the next process sends them, in order, once", async () => {
	const fx = fixture();
	// Process 1: Slack never answers, so everything it queues is still undelivered when it dies.
	const dead = await fakeSlack(() => "hang");
	try {
		const first = await drive(
			fx,
			dead,
			[{ text: "plan" }, { tools: [write("impl/test.sh", "exit 1\n")] }, { text: "failing test" }, { tools: [{ name: "bash", args: { command: "kill -9 $PPID; sleep 5" } }] }],
			{ reviewEvery: 0, maxRounds: 2 },
		);
		assert.equal(first.signal, "SIGKILL", first.stdout + first.stderr);
		assert.equal(dead.delivered.length, 0);
	} finally {
		dead.close();
	}
	// Process 2: Slack is back.
	const slack = await fakeSlack();
	try {
		const second = await drive(fx, slack, [{ text: "recovered" }], { reviewEvery: 0, maxRounds: 2 });
		assert.equal(second.status, 0, second.stderr + second.stdout);
		assert.deepEqual(
			slack.delivered.map((m) => m.text.split("*")[1]),
			["Löyly cycle started", "Plan", "Round 1 (build)", "Löyly cycle resumed", "Round 2 (build)", "Cycle exhausted"],
		);
		assert.match(slack.delivered[3].text, /continuing build-2 at stage submitted/);
		for (const m of slack.delivered.slice(1)) assert.equal(m.thread_ts, slack.delivered[0].ts);
	} finally {
		slack.close();
	}
});

test("an incoming webhook gets the same messages, unthreaded", async () => {
	const fx = fixture();
	const slack = await fakeSlack();
	try {
		const r = await drive(fx, slack, [{ text: "plan" }, { text: "round 1" }], { reviewEvery: 0, maxRounds: 1 }, {
			SLACK_WEBHOOK_URL: `${slack.api}/hook`,
		});
		assert.equal(r.status, 0, r.stderr + r.stdout);
		assert.equal(slack.delivered.length, 4);
		assert.ok(slack.requests.every((q) => q.url === "/api/hook" && q.auth === undefined && q.msg.thread_ts === undefined));
	} finally {
		slack.close();
	}
});

test("Slack text is escaped for mrkdwn", () => {
	assert.equal(escape("a <b> & c"), "a &lt;b&gt; &amp; c");
});
