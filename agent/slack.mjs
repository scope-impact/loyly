// Slack: the cycle reports itself to a thread, durably.
//
// A notification channel is only worth having if it can be trusted in the case it exists
// for, which is the loop dying unattended. So a Slack message is never sent from the
// loop's own code path. It is queued in the same commit as the state change it reports
// (an outbox document in the loop's store), and a durable task drains the queue in order,
// retrying with backoff. A crash between a round closing and its message going out loses
// nothing: the next process finds the message queued and the sender task waiting.
//
// Delivery is at least once, not exactly once. Slack's chat.postMessage takes no
// idempotency key, so a crash after Slack accepted a message and before the queue recorded
// that can post it twice. A duplicate in a thread is the failure chosen over a silence.
//
// Credentials are read from the environment at every send and are never written to the
// store: the store is a record of the cycle, and the cycle has no business holding a token.
//
//   SLACK_BOT_TOKEN + SLACK_CHANNEL   chat.postMessage; the cycle gets one thread
//   SLACK_ALLOWED_USERS               comma-separated Slack user IDs whose thread replies
//                                     steer the agent and approve or reject a DONE; unset,
//                                     the thread is read by nobody (needs channels:history
//                                     or groups:history on the bot)
//   SLACK_WEBHOOK_URL                 an incoming webhook; no thread, one message per event
//   SLACK_API_URL                     the Web API base, default https://slack.com/api
//   LOYLY_SLACK_BACKOFF_MS            first retry delay, doubling to a minute; default 1000
//   LOYLY_SLACK_POLL_MS               how often the thread is read for replies; default 5000

import { ConversationIdle, defineDoc, defineExtension, defineTask } from "@earendil-works/pi-durable";

import { Cycle } from "./cycle.mjs";

const MAX_ATTEMPTS = 8;
const MAX_TEXT = 3500;

export const Outbox = defineDoc({
	kind: "loyly.slack",
	version: 1,
	scope: "session",
	initial: () => ({
		queue: [], // [{ seq, text }] oldest first
		nextSeq: 1,
		sent: 0,
		dropped: 0,
		threadTs: null, // the cycle's thread, once the first message created it
		channel: null,
		sender: null, // the id of the live sender task, while there is one
		lastError: null,
	}),
	checkpointWhen: () => true,
});

/** The Slack target from the environment, or null when Slack is not configured. */
export function slackFromEnv(env = process.env) {
	if (env.SLACK_BOT_TOKEN && env.SLACK_CHANNEL)
		return { mode: "api", token: env.SLACK_BOT_TOKEN, channel: env.SLACK_CHANNEL, api: env.SLACK_API_URL ?? "https://slack.com/api" };
	if (env.SLACK_WEBHOOK_URL) return { mode: "webhook", url: env.SLACK_WEBHOOK_URL };
	return null;
}

/**
 * Who may talk back, or null when nobody may. Reading the thread needs the bot token, and
 * an allowlist is required, not defaulted: a reply steers an agent that runs shell
 * commands, so "anyone in the channel" is never the answer by accident.
 */
export function slackInbound(env = process.env) {
	const target = slackFromEnv(env);
	const users = (env.SLACK_ALLOWED_USERS ?? "").split(",").map((u) => u.trim()).filter(Boolean);
	if (target?.mode !== "api" || users.length === 0) return null;
	return { ...target, users };
}

/** Slack timestamps are "seconds.micros" strings; compare them exactly, never as floats. */
export function tsCompare(a, b) {
	const n = (ts) => BigInt(ts.replace(".", "").padEnd(16, "0"));
	const x = n(a);
	const y = n(b);
	return x < y ? -1 : x > y ? 1 : 0;
}

/** Slack's mrkdwn treats these three as control characters. */
export function escape(text) {
	return text.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
}

/**
 * One message out. Returns { ok, ts?, retry, error? }; `retry: false` means a resend
 * cannot help (a bad token, an unknown channel) and the message is dropped, loudly.
 */
async function send(target, text, threadTs) {
	try {
		if (target.mode === "webhook") {
			const res = await fetch(target.url, {
				method: "POST",
				headers: { "content-type": "application/json" },
				body: JSON.stringify({ text }),
			});
			if (res.ok) return { ok: true };
			return { ok: false, retry: res.status === 429 || res.status >= 500, error: `webhook HTTP ${res.status}` };
		}
		const res = await fetch(`${target.api}/chat.postMessage`, {
			method: "POST",
			headers: { "content-type": "application/json; charset=utf-8", authorization: `Bearer ${target.token}` },
			body: JSON.stringify({ channel: target.channel, text, ...(threadTs ? { thread_ts: threadTs } : {}), unfurl_links: false }),
		});
		if (res.status === 429 || res.status >= 500) return { ok: false, retry: true, error: `chat.postMessage HTTP ${res.status}` };
		const body = await res.json();
		if (body.ok) return { ok: true, ts: body.ts, channel: body.channel };
		const permanent = ["invalid_auth", "not_authed", "account_inactive", "token_revoked", "channel_not_found", "not_in_channel", "is_archived", "missing_scope"];
		return { ok: false, retry: !permanent.includes(body.error), error: `chat.postMessage: ${body.error}` };
	} catch (error) {
		return { ok: false, retry: true, error: `network: ${error.message}` };
	}
}

/**
 * The sender: drains the outbox in order, one message per phase, so every delivered
 * message is a committed checkpoint. It ends when the queue is empty, clearing itself from
 * the outbox in the same commit that saw the queue empty, so an enqueue can never land
 * between "the sender looked" and "the sender left" unnoticed.
 */
export const SlackSender = defineTask({
	name: "loyly.slack-sender",
	version: 1,
	initial: () => ({ phase: "send", delivered: 0, attempt: 0, looks: 0 }),
	phases: {
		send: async (task, runtime, context) => {
			const target = slackFromEnv();
			const head = (await runtime.snapshot(Outbox, context))?.queue[0];
			if (!head || !target) {
				// Nothing to send, or nobody to send it to in this process: leave the queue for a
				// process that has a target, and step aside so it can start a sender of its own.
				await runtime.commit(async (tx, current) => {
					const outbox = await tx.doc(Outbox);
					// An enqueue landed after the snapshot: go round again, with a changed checkpoint.
					if (outbox.queue.length && target)
						return { status: "running", checkpoint: { ...current.state.checkpoint, attempt: 0, looks: current.state.checkpoint.looks + 1 } };
					if (outbox.sender === task.id) outbox.sender = null;
					if (!target && outbox.queue.length) outbox.lastError = "no Slack target in this process; messages kept";
					return { status: "terminal", outcome: { status: "completed", result: null } };
				}, context);
				return;
			}
			const outbox = await runtime.snapshot(Outbox, context);
			const result = await send(target, head.text, target.mode === "api" ? outbox.threadTs : null);
			if (result.ok || !result.retry || task.state.checkpoint.attempt + 1 >= MAX_ATTEMPTS) {
				await runtime.commit(async (tx) => {
					const o = await tx.doc(Outbox);
					if (o.queue[0]?.seq === head.seq) o.queue.shift();
					if (result.ok) {
						o.sent++;
						if (!o.threadTs && result.ts) {
							o.threadTs = result.ts;
							o.channel = result.channel ?? null;
						}
						o.lastError = null;
					} else {
						o.dropped++;
						o.lastError = `dropped message ${head.seq}: ${result.error}`;
					}
					return { status: "running", checkpoint: { ...task.state.checkpoint, delivered: task.state.checkpoint.delivered + 1, attempt: 0 } };
				}, context);
				return;
			}
			const attempt = task.state.checkpoint.attempt + 1;
			const base = Number(process.env.LOYLY_SLACK_BACKOFF_MS ?? 1000);
			const backoff = Math.min(60_000, base * 2 ** (attempt - 1));
			await runtime.commit(async (tx) => {
				(await tx.doc(Outbox)).lastError = `${result.error}; retry ${attempt} of ${MAX_ATTEMPTS - 1}`;
				return { status: "sleeping", until: runtime.now() + backoff, checkpoint: { ...task.state.checkpoint, attempt } };
			}, context);
		},
	},
	abort: (_task, runtime, context) =>
		runtime.commit(async (tx) => {
			const o = await tx.doc(Outbox);
			o.sender = null;
			return { status: "terminal", outcome: { status: "aborted" } };
		}, context),
});

// ---------------------------------------------------------------------------------------
// Inbound: replies in the thread
// ---------------------------------------------------------------------------------------

export const Inbox = defineDoc({
	kind: "loyly.slack-inbox",
	version: 1,
	scope: "session",
	initial: () => ({
		cursor: null, // the newest reply already acted on; replies at or before it are history
		listener: null,
		steered: 0,
		noted: 0,
		approvals: 0,
		rejections: 0,
		ignored: 0,
		lastError: null,
	}),
	checkpointWhen: () => true,
});

/** Replies after `after` in the thread, oldest first, across pages. */
async function repliesAfter(target, threadTs, after) {
	const out = [];
	let cursor;
	do {
		const q = new URLSearchParams({ channel: target.channel, ts: threadTs, oldest: after, inclusive: "false", limit: "200" });
		if (cursor) q.set("cursor", cursor);
		const res = await fetch(`${target.api}/conversations.replies?${q}`, { headers: { authorization: `Bearer ${target.token}` } });
		if (!res.ok) throw new Error(`conversations.replies HTTP ${res.status}`);
		const body = await res.json();
		if (!body.ok) throw new Error(`conversations.replies: ${body.error}`);
		out.push(...(body.messages ?? []));
		cursor = body.response_metadata?.next_cursor || undefined;
	} while (cursor);
	return out.filter((m) => m.ts !== threadTs && tsCompare(m.ts, after) > 0).sort((a, b) => tsCompare(a.ts, b.ts));
}

const APPROVE = /^\s*approve\b/i;
const REJECT = /^\s*reject\b[\s:,-]*([\s\S]*)$/i;

/**
 * The listener: reads the cycle's thread and acts on one reply per commit, and that
 * commit also advances the cursor past it. So a reply is acted on exactly once, crash or
 * not: either its effect and the cursor are both stored, or neither is and the next read
 * finds it again. (Outbound is at least once; inbound, because the cursor and the effect
 * share a commit, is exactly once.)
 *
 * What a reply does depends on the cycle, read in the same commit:
 *   - awaiting approval, "approve"          the cycle is done
 *   - awaiting approval, "reject: reason"   the ratification is withdrawn and the reason
 *                                           becomes the next round's finding
 *   - a round is running                    a steer: it joins the run after its current
 *                                           tool round, and the guardrails still apply
 *   - nothing is running                    a note for the next round's prompt
 */
export const SlackListener = defineTask({
	name: "loyly.slack-listener",
	version: 1,
	initial: () => ({ phase: "poll", polls: 0, seen: 0, failures: 0 }),
	phases: {
		poll: async (task, runtime, context) => {
			const cp = task.state.checkpoint;
			const pollMs = Number(process.env.LOYLY_SLACK_POLL_MS ?? 5000);
			const inbound = slackInbound();
			const cycle = await runtime.snapshot(Cycle, context);
			const finished = !cycle || cycle.phase === "done" || cycle.phase === "exhausted";
			if (finished || !inbound) {
				await runtime.commit(async (tx) => {
					const inbox = await tx.doc(Inbox);
					if (inbox.listener === task.id) inbox.listener = null;
					if (!inbound && !finished) inbox.lastError = "no SLACK_ALLOWED_USERS or bot token in this process; the thread is not read";
					return { status: "terminal", outcome: { status: "completed", result: null } };
				}, context);
				return;
			}
			const outbox = await runtime.snapshot(Outbox, context);
			const inbox = await runtime.snapshot(Inbox, context);
			if (!outbox?.threadTs) {
				await runtime.sleepUntil(runtime.now() + pollMs, { ...cp, polls: cp.polls + 1 }, context);
				return;
			}
			let replies;
			try {
				replies = await repliesAfter(inbound, outbox.threadTs, inbox?.cursor ?? outbox.threadTs);
			} catch (error) {
				const failures = cp.failures + 1;
				await runtime.commit(async (tx) => {
					(await tx.doc(Inbox)).lastError = error.message;
					return { status: "sleeping", until: runtime.now() + Math.min(60_000, pollMs * 2 ** Math.min(failures, 6)), checkpoint: { ...cp, failures } };
				}, context);
				return;
			}
			const reply = replies[0];
			if (!reply) {
				await runtime.sleepUntil(runtime.now() + pollMs, { ...cp, polls: cp.polls + 1, failures: 0 }, context);
				return;
			}
			// Handles are taken before the commit, as pi-durable asks of a task that submits.
			const plan = cycle.planConversation && (await runtime.conversation(cycle.planConversation, context));
			const build = cycle.buildConversation && (await runtime.conversation(cycle.buildConversation, context));
			await runtime.commit(async (tx) => {
				const c = await tx.doc(Cycle);
				const text = (reply.text ?? "").trim();
				const who = `<@${reply.user}>`;
				const acks = [];
				const human = reply.user && !reply.bot_id && !reply.subtype && text;
				if (!human || !inbound.users.includes(reply.user)) {
					(await tx.doc(Inbox)).ignored++;
				} else if (c.phase === "awaiting-approval" && APPROVE.test(text)) {
					c.approval = { by: reply.user, at: new Date().toISOString(), ts: reply.ts };
					c.phase = "done";
					(await tx.doc(Inbox)).approvals++;
					acks.push(`:white_check_mark: *Approved* by ${who}. The cycle is done.`);
				} else if (c.phase === "awaiting-approval" && REJECT.test(text)) {
					const reason = REJECT.exec(text)[1].trim() || "no reason given";
					c.ratified = null;
					c.phase = "implement";
					c.findings = [`A person rejected the ratified DONE after looking at it: ${reason}`];
					c.history.push({ round: c.round, kind: "approval", at: new Date().toISOString(), outcome: `rejected by ${reply.user}`, tddBefore: c.tdd, tddAfter: c.tdd, reverted: [], ratified: false, notes: [reason] });
					(await tx.doc(Inbox)).rejections++;
					acks.push(`:x: *Rejected* by ${who}: ${escape(reason)}. The next round gets the reason as a finding.`);
				} else {
					const running = c.pending?.stage === "submitted" ? (c.pending.kind === "plan" ? plan : build) : null;
					let steered = false;
					if (running) {
						try {
							// Admission first: pi-durable wants it before this commit's own table writes.
							await running.submit(tx, {
								type: "input",
								content: `A person steering from Slack (${reply.user}): ${text}`,
								whenBusy: "steer",
								whenIdle: "reject",
								requestId: `slack-${reply.ts}`,
							});
							steered = true;
						} catch (error) {
							if (!(error instanceof ConversationIdle)) throw error;
						}
					}
					const box = await tx.doc(Inbox);
					if (steered) {
						box.steered++;
						acks.push(`:steering_wheel: Steered into the running ${c.pending.kind === "plan" ? "plan" : `round ${c.pending.round}`}, from ${who}.`);
					} else {
						(c.humanNotes ??= []).push(`${reply.user}: ${text}`);
						box.noted++;
						acks.push(`:memo: Noted from ${who}; the next round gets it.`);
					}
				}
				(await tx.doc(Inbox)).cursor = reply.ts;
				(await tx.doc(Inbox)).lastError = null;
				await enqueue(tx, acks, runtime.conversationId);
				return { status: "running", checkpoint: { ...cp, seen: cp.seen + 1, failures: 0 } };
			}, context);
		},
	},
	abort: (_task, runtime, context) =>
		runtime.commit(async (tx) => {
			(await tx.doc(Inbox)).listener = null;
			return { status: "terminal", outcome: { status: "aborted" } };
		}, context),
});

/** Start the listener inside the caller's commit, unless one is live. */
export async function ensureListener(tx, conversationId) {
	const inbox = await tx.doc(Inbox);
	if (inbox.listener || !conversationId) return;
	inbox.listener = await tx.createTask(SlackListener, {}, { ownership: { kind: "conversation" }, conversationId, background: true });
}

/** Installed in every process that opens a store, configured or not, so a queued task is never blocked on a missing definition. */
export const SlackExtension = defineExtension({ name: "loyly-slack", tasks: [SlackSender, SlackListener] });

/**
 * Queue messages inside a commit the caller is already making, and start the sender if
 * none is live. `conversationId` owns the sender task; without one the messages wait in
 * the queue for the next enqueue that has one.
 */
export async function enqueue(tx, texts, conversationId) {
	if (!texts.length) return;
	const outbox = await tx.doc(Outbox);
	for (const text of texts) {
		const clipped = text.length > MAX_TEXT ? `${text.slice(0, MAX_TEXT)}…` : text;
		outbox.queue.push({ seq: outbox.nextSeq++, text: clipped });
	}
	if (!outbox.sender && conversationId) {
		outbox.sender = await tx.createTask(SlackSender, {}, { ownership: { kind: "conversation" }, conversationId, background: true });
	}
}

/** What the loop says about itself. Plain text in, Slack mrkdwn out. */
export const say = {
	started: (root, model, resumed) =>
		resumed
			? `:arrows_counterclockwise: *Löyly cycle resumed* in \`${escape(root)}\`: ${escape(resumed)}`
			: `:hot_springs: *Löyly cycle started* in \`${escape(root)}\` with \`${escape(model)}\``,
	round: (entry, cycle) => {
		const icon = entry.kind === "plan" ? ":memo:" : entry.kind === "review" ? ":mag:" : entry.tddAfter === "GREEN" ? ":large_green_circle:" : ":red_circle:";
		const head = entry.kind === "plan" ? "Plan" : `Round ${entry.round} (${entry.kind})`;
		const lines = [`${icon} *${head}*: ${escape(entry.outcome)}, TDD ${entry.tddBefore} → ${entry.tddAfter}`];
		for (const note of entry.notes) lines.push(`> ${escape(note)}`);
		if (entry.reverted.length) lines.push(`> reverted: ${entry.reverted.map((f) => `\`${escape(f)}\``).join(", ")}`);
		if (entry.kind === "review" && cycle.findings[0]) lines.push(`\`\`\`${escape(cycle.findings[0])}\`\`\``);
		return lines.join("\n");
	},
	ratified: (ratified, round, approval) =>
		`:white_check_mark: *RATIFIED* in round ${round}: ${escape(ratified.summary)}. The gate passed.` +
		(approval ? "\n:raised_hand: *Waiting for a person.* Look at the running product, then reply `approve`, or `reject: <what is wrong>`." : ""),
	exhausted: (rounds) => `:hourglass: *Cycle exhausted* after ${rounds} rounds with no ratified DONE.`,
	failed: (what) => `:rotating_light: *Loop trouble*: ${escape(what)}`,
};
