#!/usr/bin/env node
// The dispatcher: a Slack channel becomes the factory's front door.
//
//   SLACK_BOT_TOKEN=xoxb-… SLACK_CHANNEL=C… SLACK_ALLOWED_USERS=U…,U… node factory/dispatcher.mjs
//
// It reads one channel. A top-level message from an allowed person saying
//   run cycle            the next cycle number after the highest in harness/cycles/
//   run cycle 03         that one
//   status               where the running cycle is
// is answered in its own thread, and "run cycle" starts factory/run-cycle.mjs for it with
// SLACK_THREAD_TS set to that message, so the whole cycle (each round, the review
// findings, the ratified DONE, the score) reports into that one thread, and replies there
// steer the builder and approve or reject its DONE (agent/slack.mjs). Messages from anyone
// else, and from bots, are ignored.
//
// One cycle at a time: a builder, a grader and a gate each want the machine, and two
// cycles of one slice would measure each other. A second "run cycle" while one runs is
// answered with the one that is running.
//
// DURABLE. Everything it knows is in <workdir>/dispatcher.json, written atomically before
// any side effect it guards: the channel cursor (so a message is acted on once, across
// restarts) and the running cycle. Restart it at any time. On every tick it looks at the
// running cycle: if its runner has died before the cycle's last stage, it starts the runner
// again, which resumes the cycle where it stopped (the runner and the loop are both
// resumable); after three restarts in a row it gives up and says so in the thread.
//
//   LOYLY_WORKDIR        where cycles and dispatcher.json live (default ../loyly-cycles)
//   LOYLY_DISPATCH_POLL_MS   how often the channel is read, default 10000
//   LOYLY_RUNNER         the runner to start, default factory/run-cycle.mjs (tests swap it)
//   LOYLY_ISOLATION, LOYLY_BUILDER_USER   passed through to the runner

import { spawn } from "node:child_process";
import { existsSync, mkdirSync, readdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const RESTART_LIMIT = 3;

const env = process.env;
const api = env.SLACK_API_URL ?? "https://slack.com/api";
const channel = env.SLACK_CHANNEL;
const allowed = (env.SLACK_ALLOWED_USERS ?? "").split(",").map((u) => u.trim()).filter(Boolean);
const workdir = path.resolve(env.LOYLY_WORKDIR ?? path.join(REPO, "..", "loyly-cycles"));
const pollMs = Number(env.LOYLY_DISPATCH_POLL_MS ?? 10_000);
const runner = env.LOYLY_RUNNER ?? path.join(REPO, "factory", "run-cycle.mjs");
const stateFile = path.join(workdir, "dispatcher.json");

const log = (line) => console.log(`[dispatcher] ${new Date().toISOString()} ${line}`);

// ---------------------------------------------------------------- state

function load() {
	return existsSync(stateFile) ? JSON.parse(readFileSync(stateFile, "utf8")) : { cursor: null, active: null, finished: [] };
}
function save(state) {
	writeFileSync(`${stateFile}.tmp`, `${JSON.stringify(state, null, 1)}\n`);
	renameSync(`${stateFile}.tmp`, stateFile);
}

// ---------------------------------------------------------------- Slack

async function slack(method, params, { get = false } = {}) {
	for (let attempt = 0; ; attempt++) {
		try {
			const res = get
				? await fetch(`${api}/${method}?${new URLSearchParams(params)}`, { headers: { authorization: `Bearer ${env.SLACK_BOT_TOKEN}` } })
				: await fetch(`${api}/${method}`, {
						method: "POST",
						headers: { "content-type": "application/json; charset=utf-8", authorization: `Bearer ${env.SLACK_BOT_TOKEN}` },
						body: JSON.stringify(params),
					});
			if (res.status === 429 || res.status >= 500) throw new Error(`${method} HTTP ${res.status}`);
			const body = await res.json();
			if (!body.ok) throw Object.assign(new Error(`${method}: ${body.error}`), { permanent: true });
			return body;
		} catch (error) {
			if (error.permanent || attempt >= 3) throw error;
			await new Promise((r) => setTimeout(r, 1000 * 2 ** attempt));
		}
	}
}

const say = (threadTs, text) => slack("chat.postMessage", { channel, thread_ts: threadTs, text, unfurl_links: false });

/** Top-level messages after the cursor, oldest first. */
async function newMessages(cursor) {
	const out = [];
	let page;
	do {
		const body = await slack("conversations.history", { channel, limit: "200", ...(cursor ? { oldest: cursor, inclusive: "false" } : {}), ...(page ? { cursor: page } : {}) }, { get: true });
		out.push(...(body.messages ?? []));
		page = body.response_metadata?.next_cursor || undefined;
	} while (page);
	const after = (m) => !cursor || BigInt(m.ts.replace(".", "")) > BigInt(cursor.replace(".", ""));
	return out.filter((m) => after(m) && (!m.thread_ts || m.thread_ts === m.ts)).sort((a, b) => (BigInt(a.ts.replace(".", "")) < BigInt(b.ts.replace(".", "")) ? -1 : 1));
}

// ---------------------------------------------------------------- cycles

const COMMAND = /^\s*(?:<@[A-Z0-9]+>\s*)?(run cycle(?:\s+(\d+))?|status)\s*$/i;

/** The next cycle number: one past the highest in harness/cycles/, two digits at least. */
export function nextCycle(repo = REPO) {
	const dir = path.join(repo, "harness", "cycles");
	const nums = existsSync(dir) ? readdirSync(dir).filter((n) => /^\d+$/.test(n)).map(Number) : [];
	return String((nums.length ? Math.max(...nums) : 0) + 1).padStart(2, "0");
}

const cycleState = (cycle) => {
	const f = path.join(workdir, `cycle-${cycle}`, "state.json");
	return existsSync(f) ? JSON.parse(readFileSync(f, "utf8")) : null;
};

const alive = (pid) => {
	try {
		process.kill(pid, 0);
		return true;
	} catch {
		return false;
	}
};

function startRunner(active) {
	const cycleDir = path.join(workdir, `cycle-${active.cycle}`);
	mkdirSync(cycleDir, { recursive: true });
	const out = path.join(cycleDir, "runner.log");
	const approve = env.SLACK_BOT_TOKEN && channel && allowed.length ? ["--approve"] : [];
	const child = spawn("bash", ["-c", `exec node ${JSON.stringify(runner)} --cycle ${active.cycle} --workdir ${JSON.stringify(workdir)} ${approve.join(" ")} >> ${JSON.stringify(out)} 2>&1`], {
		cwd: REPO,
		detached: true,
		stdio: "ignore",
		env: { ...env, SLACK_THREAD_TS: active.threadTs },
	});
	child.unref();
	return child.pid;
}

async function handle(state, m) {
	const human = m.user && !m.bot_id && !m.subtype;
	const match = human && allowed.includes(m.user) ? COMMAND.exec(m.text ?? "") : null;
	if (!match) return state;
	if (/^status$/i.test(match[1])) {
		const a = state.active;
		const st = a && cycleState(a.cycle);
		await say(m.ts, a ? `Cycle ${a.cycle} is running, at stage ${st?.stage ?? "starting"}. Its thread: <${permalink(a.threadTs)}|here>.` : "No cycle is running.");
		return state;
	}
	if (state.active) {
		await say(m.ts, `Cycle ${state.active.cycle} is already running; one cycle at a time. Its thread: <${permalink(state.active.threadTs)}|here>.`);
		return state;
	}
	const cycle = match[2] ? match[2].padStart(2, "0") : nextCycle();
	if (existsSync(path.join(REPO, "harness", "cycles", cycle, "scores.json"))) {
		await say(m.ts, `Cycle ${cycle} is already scored. Ask for "run cycle" for the next one, ${nextCycle()}.`);
		return state;
	}
	// Recorded before the runner starts: a crash between the two resumes the cycle, never starts it twice.
	const next = { ...state, active: { cycle, threadTs: m.ts, by: m.user, startedAt: new Date().toISOString(), pid: null, restarts: 0 } };
	save(next);
	next.active.pid = startRunner(next.active);
	save(next);
	await say(m.ts, `Starting cycle ${cycle} for <@${m.user}>. It reports here, round by round. Reply in this thread to steer the builder; when the gate ratifies a DONE, reply \`approve\` or \`reject: why\`.`);
	log(`cycle ${cycle} started for ${m.user}, runner pid ${next.active.pid}`);
	return next;
}

const permalink = (ts) => `https://slack.com/archives/${channel}/p${ts.replace(".", "")}`;

/** Look at the running cycle: finished, still going, or a runner to start again. */
async function supervise(state) {
	const a = state.active;
	if (!a || (a.pid && alive(a.pid))) return state;
	const st = cycleState(a.cycle);
	if (st?.stage === "recorded") {
		const board = st.scoreboard ? `\n\`\`\`${st.scoreboard.trim()}\`\`\`` : "";
		await say(a.threadTs, `Cycle ${a.cycle} is finished: ${st.score ?? "scored"}. Built in ${st.rounds} rounds for $${st.costUsd}.${board}`);
		log(`cycle ${a.cycle} finished`);
		const next = { ...state, active: null, finished: [...state.finished, { ...a, finishedAt: new Date().toISOString(), score: st.score }] };
		save(next);
		return next;
	}
	if (a.restarts >= RESTART_LIMIT) {
		await say(a.threadTs, `Cycle ${a.cycle} stopped at stage "${st?.stage ?? "none"}" and did not survive ${RESTART_LIMIT} restarts. Its runner log is ${path.join(workdir, `cycle-${a.cycle}`, "runner.log")} on the factory machine. Nothing more will be tried until someone asks for "run cycle ${a.cycle}".`);
		const next = { ...state, active: null, finished: [...state.finished, { ...a, failedAt: new Date().toISOString(), stage: st?.stage ?? null }] };
		save(next);
		return next;
	}
	const next = { ...state, active: { ...a, restarts: a.restarts + 1 } };
	save(next);
	next.active.pid = startRunner(next.active);
	save(next);
	await say(a.threadTs, `The runner for cycle ${a.cycle} stopped at stage "${st?.stage ?? "none"}"; started it again (${next.active.restarts} of ${RESTART_LIMIT}), and it resumes from there.`);
	log(`cycle ${a.cycle} runner restarted (${next.active.restarts})`);
	return next;
}

async function tick(state) {
	state = await supervise(state);
	const messages = await newMessages(state.cursor);
	for (const m of messages) {
		state = await handle(state, m);
		// The cursor moves past a message only once it has been handled.
		state = { ...state, cursor: m.ts };
		save(state);
	}
	return state;
}

async function main() {
	for (const [k, v] of Object.entries({ SLACK_BOT_TOKEN: env.SLACK_BOT_TOKEN, SLACK_CHANNEL: channel })) if (!v) throw new Error(`${k} is required`);
	if (!allowed.length) throw new Error("SLACK_ALLOWED_USERS is required: a message starts an agent that runs shell commands, so nobody is allowed by default");
	mkdirSync(workdir, { recursive: true });
	let state = load();
	if (state.cursor === null) {
		// A first start does not act on the channel's history, only on what is said from now.
		state = { ...state, cursor: (Date.now() / 1000).toFixed(6) };
		save(state);
	}
	log(`reading ${channel} every ${pollMs} ms; allowed: ${allowed.join(", ")}; workdir ${workdir}`);
	let stopping = false;
	for (const sig of ["SIGINT", "SIGTERM"]) process.on(sig, () => (stopping = true));
	while (!stopping) {
		try {
			state = await tick(state);
		} catch (error) {
			log(`tick failed: ${error.message}`);
		}
		await new Promise((r) => setTimeout(r, pollMs));
	}
	log("stopped; a running cycle keeps running, and the next start picks it up");
}

if (process.argv[1] === fileURLToPath(import.meta.url)) await main();
