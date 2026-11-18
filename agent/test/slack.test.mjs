import assert from "node:assert/strict";
import { execFileSync, spawn } from "node:child_process";
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { escape, tsCompare } from "../slack.mjs";

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
	const replies = []; // people's replies in the thread
	const watchers = []; // [pattern, fn]: run fn once when a delivered message matches
	let clock = 0;
	const nextTs = () => `1700000000.${String(++clock).padStart(6, "0")}`;
	let n = 0;
	const server = createServer((req, res) => {
		if (req.method === "GET" && req.url.startsWith("/api/conversations.replies")) {
			requests.push({ url: req.url, auth: req.headers.authorization, msg: null });
			const q = new URL(req.url, "http://x").searchParams;
			const parent = delivered.find((m) => m.ts === q.get("ts"));
			for (const r of replies) r.thread_ts ??= q.get("ts");
			const after = q.get("oldest");
			const thread = [parent, ...replies].filter(Boolean).filter((m) => q.get("inclusive") === "true" || m.ts > after);
			res.writeHead(200, { "content-type": "application/json" });
			return res.end(JSON.stringify({ ok: true, messages: thread }));
		}
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
			const ts = nextTs();
			delivered.push({ ...msg, ts });
			res.end(JSON.stringify({ ok: true, ts, channel: msg.channel }));
			for (const w of watchers) if (!w.done && w.pattern.test(msg.text)) (w.done = true), w.fn();
		});
	});
	await new Promise((r) => server.listen(0, "127.0.0.1", r));
	const api = `http://127.0.0.1:${server.address().port}/api`;
	return {
		api,
		delivered,
		requests,
		/** A person (or a bot, with { bot: true }) replies in the thread. */
		reply: (user, text, { bot = false } = {}) =>
			replies.push({ type: "message", user, text, ts: nextTs(), thread_ts: delivered[0]?.ts, ...(bot ? { bot_id: "B1", subtype: "bot_message" } : {}) }),
		when: (pattern, fn) => watchers.push({ pattern, fn, done: false }),
		texts: () => delivered.map((m) => m.text),
		close: () => (server.closeAllConnections(), server.close()),
	};
}

function drive(fx, slack, script, options = {}, target, onChild) {
	return new Promise((resolve) => {
		const env = {
			...Object.fromEntries(Object.entries(process.env).filter(([k]) => !k.startsWith("SLACK_"))),
			...(target ?? { SLACK_BOT_TOKEN: TOKEN, SLACK_CHANNEL: "C0LOYLY", SLACK_API_URL: slack.api, SLACK_ALLOWED_USERS: "U1,U3" }),
			LOYLY_SLACK_BACKOFF_MS: "50",
			LOYLY_SLACK_POLL_MS: "100",
		};
		const child = spawn(process.execPath, [scripted, fx.root, fx.store, JSON.stringify(script), JSON.stringify(options)], { env });
		onChild?.(child);
		let stdout = "";
		let stderr = "";
		child.stdout.on("data", (d) => (stdout += d));
		child.stderr.on("data", (d) => (stderr += d));
		const timer = setTimeout(() => child.kill("SIGKILL"), 60_000);
		child.on("exit", (status, signal) => {
			clearTimeout(timer);
			const line = (p) => stdout.split("\n").find((l) => l.startsWith(p));
			const outbox = line("OUTBOX ");
			const cycle = line("CYCLE ");
			const seen = stdout.split("\n").filter((l) => l.startsWith("SEEN ")).map((l) => JSON.parse(l.slice(5)).join("\n"));
			resolve({
				status,
				signal,
				stdout,
				stderr,
				seen,
				outbox: outbox ? JSON.parse(outbox.slice(7)) : undefined,
				cycle: cycle ? JSON.parse(cycle.slice(6)) : undefined,
			});
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
		assert.ok(slack.requests.filter((q) => q.msg).every((q) => q.auth === `Bearer ${TOKEN}` && q.url === "/api/chat.postMessage"));
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

test("a reply from an allowed person steers the running round; others are ignored", async () => {
	const fx = fixture();
	const slack = await fakeSlack();
	// Round 1 sleeps in a tool for three seconds: the replies arrive while it runs.
	// A second after the plan's message, round 1 is inside its three-second tool call: replying
	// at once would race the opening of the round, and a reply between rounds is a note.
	slack.when(/\*Plan\*/, () =>
		setTimeout(() => {
			slack.reply("U2", "not on the allowlist: delete everything");
			slack.reply("B0", "a bot talking", { bot: true });
			slack.reply("U1", "the posted slot list is a view, not a copy");
		}, 1000),
	);
	try {
		const r = await drive(fx, slack, [{ text: "plan" }, { tools: [{ name: "bash", args: { command: "sleep 3" } }] }, { text: "round 1 done" }], {
			reviewEvery: 0,
			maxRounds: 1,
		});
		assert.equal(r.status, 0, r.stderr + r.stdout);
		const afterSleep = r.seen[2];
		assert.match(afterSleep, /A person steering from Slack \(U1\): the posted slot list is a view, not a copy/);
		assert.doesNotMatch(r.seen.join("\n"), /delete everything|a bot talking/);
		assert.ok(slack.texts().some((t) => /Steered into the running round 1, from <@U1>/.test(t)));
		const status = spawnStatus(fx);
		assert.match(status, /slack replies: 1 steered, 0 noted, 0 approved, 0 rejected, 2 ignored/);
	} finally {
		slack.close();
	}
});

test("--approve: a ratified DONE waits for a person; a rejection becomes the next round's finding", async () => {
	const fx = fixture();
	const slack = await fakeSlack();
	let ratifications = 0;
	const onRatified = () => {
		ratifications++;
		if (ratifications === 1) {
			slack.reply("U1", "the mobile view is cramped");
			slack.reply("U2", "approve"); // not on the allowlist: ignored
			slack.reply("U3", "reject: the schedule page is empty");
		} else {
			slack.reply("U1", "approve");
		}
	};
	slack.when(/\*RATIFIED\* in round 1/, onRatified);
	slack.when(/\*RATIFIED\* in round 2/, onRatified);
	try {
		const r = await drive(
			fx,
			slack,
			[
				{ text: "plan" },
				{ tools: [write("impl/test.sh", "exit 0\n")] },
				{ tools: [{ name: "claim_done", args: { summary: "first claim" } }] },
				{ tools: [{ name: "claim_done", args: { summary: "second claim" } }] },
			],
			{ reviewEvery: 0, maxRounds: 3, approval: true },
		);
		assert.equal(r.status, 0, r.stderr + r.stdout);
		const c = r.cycle;
		assert.equal(c.phase, "done");
		assert.equal(c.approval.by, "U1");
		assert.equal(c.ratified.summary, "second claim");
		assert.deepEqual(
			c.history.map((h) => [h.kind, h.round]),
			[["plan", 0], ["build", 1], ["approval", 1], ["build", 2]],
		);
		// Round 2 was told why the first DONE was rejected, and what was said while it waited.
		const round2 = r.seen[3];
		assert.match(round2, /A person rejected the ratified DONE after looking at it: the schedule page is empty/);
		assert.match(round2, /U1: the mobile view is cramped/);
		const texts = slack.texts();
		assert.ok(texts.some((t) => /Waiting for a person/.test(t)));
		assert.ok(texts.some((t) => /\*Rejected\* by <@U3>: the schedule page is empty/.test(t)));
		assert.ok(texts.some((t) => /\*Approved\* by <@U1>/.test(t)));
		assert.equal(c.humanNotes.length, 0);
	} finally {
		slack.close();
	}
});

test("killed while waiting for approval: a reply already acted on is not acted on again", async () => {
	const fx = fixture();
	const slack = await fakeSlack();
	slack.when(/\*RATIFIED\*/, () => slack.reply("U1", "one note, exactly once"));
	let child;
	slack.when(/Noted from <@U1>/, () => setTimeout(() => child.kill("SIGKILL"), 50));
	try {
		const first = await drive(
			fx,
			slack,
			[{ text: "plan" }, { tools: [write("impl/test.sh", "exit 0\n")] }, { tools: [{ name: "claim_done", args: { summary: "built" } }] }],
			{ reviewEvery: 0, maxRounds: 3, approval: true },
			undefined,
			(c) => (child = c),
		);
		assert.equal(first.signal, "SIGKILL", first.stdout + first.stderr);
		slack.reply("U1", "approve");
		const second = await drive(fx, slack, [], { reviewEvery: 0, maxRounds: 3, approval: true });
		assert.equal(second.status, 0, second.stderr + second.stdout);
		assert.equal(second.cycle.phase, "done");
		assert.deepEqual(second.cycle.humanNotes, ["U1: one note, exactly once"]);
		assert.equal(slack.texts().filter((t) => /Noted from/.test(t)).length, 1);
	} finally {
		slack.close();
	}
});

test("--approve without a way to read replies refuses to start", async () => {
	const fx = fixture();
	const slack = await fakeSlack();
	try {
		const r = await drive(fx, slack, [{ text: "plan" }], { approval: true }, { SLACK_WEBHOOK_URL: `${slack.api}/hook` });
		assert.notEqual(r.status, 0);
		assert.match(r.stderr, /--approve needs somebody to ask/);
	} finally {
		slack.close();
	}
});

test("a cycle started from a thread reports into that thread and is steered from it", async () => {
	const fx = fixture();
	const slack = await fakeSlack();
	const thread = "1600000000.000042"; // the message that asked for the cycle, in the dispatcher's channel
	slack.when(/\*Plan\*/, () => setTimeout(() => slack.reply("U1", "steer from the asking thread"), 1000));
	try {
		const r = await drive(fx, slack, [{ text: "plan" }, { tools: [{ name: "bash", args: { command: "sleep 3" } }] }, { text: "done" }], { reviewEvery: 0, maxRounds: 1 }, {
			SLACK_BOT_TOKEN: TOKEN,
			SLACK_CHANNEL: "C0LOYLY",
			SLACK_API_URL: slack.api,
			SLACK_ALLOWED_USERS: "U1",
			SLACK_THREAD_TS: thread,
		});
		assert.equal(r.status, 0, r.stderr + r.stdout);
		assert.ok(slack.delivered.length >= 3);
		assert.ok(slack.delivered.every((m) => m.thread_ts === thread), "every message, the first included, is in the asking thread");
		assert.ok(slack.requests.filter((q) => q.url.startsWith("/api/conversations.replies")).every((q) => q.url.includes(`ts=${thread}`)));
		assert.match(r.seen[2], /A person steering from Slack \(U1\): steer from the asking thread/);
	} finally {
		slack.close();
	}
});

test("Slack timestamps compare exactly", () => {
	assert.equal(tsCompare("1700000000.000010", "1700000000.000009"), 1);
	assert.equal(tsCompare("1700000000.000009", "1700000000.000009"), 0);
	assert.equal(tsCompare("1699999999.999999", "1700000000.000000"), -1);
});

function spawnStatus(fx) {
	return execFileSync(process.execPath, [path.join(path.dirname(scripted), "..", "run.mjs"), "status", "--store", fx.store], {
		encoding: "utf8",
		stdio: ["ignore", "pipe", "ignore"],
	});
}

test("Slack text is escaped for mrkdwn", () => {
	assert.equal(escape("a <b> & c"), "a &lt;b&gt; &amp; c");
});
