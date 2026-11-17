// The agent harness of docs/wow/harness.md, as a build loop that survives its own death.
//
// Every guardrail in that document is a rule about rounds: plan and implement are separate
// invocations, the TDD state is tracked outside the agent, edits outside the slice are
// reverted, "done" counts only when the gate ratifies it, and every Nth round reviews
// instead of builds. A loop that keeps those rules in process memory forgets all of them
// the moment the process dies, and the round it was in is then either repeated or skipped
// without anybody saying which. So the loop keeps its position in a durable document
// (`loyly.cycle`) committed through pi-durable, the model turns and tool calls of each
// round are pi-durable runs, and reopening the store continues the round that was cut off
// instead of starting it again.
//
// What is durable, and where it lives:
//   - the cycle's position (phase, round, TDD state, the stage of the round in flight) and
//     its history, in the session document `loyly.cycle`
//   - every model turn and tool call, in the plan and build conversations
//   - a ratified DONE, committed by the claim_done tool in the same store
// The store sits in a git-ignored directory, so the fence (harness/fence.sh, which clones)
// never carries one cycle's transcripts into the next cycle's clean room.

import { execFile } from "node:child_process";
import { randomUUID } from "node:crypto";
import { mkdir, rm } from "node:fs/promises";
import path from "node:path";
import { promisify } from "node:util";

import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { createModels } from "@earendil-works/pi-ai";
import {
	AssistantEntry,
	createRegistry,
	defineExtension,
	defineTool,
	Harness,
	hook,
	section,
	ToolTask,
} from "@earendil-works/pi-durable";
import { NodeExecutionEnv } from "@earendil-works/pi-durable/env/node";
import { openNodeSqliteStorage } from "@earendil-works/pi-durable/storage/sqlite/node";
import { CodingTools } from "@earendil-works/pi-durable/tools";
import { obj, str } from "@earendil-works/pi-schema";

import { runClaudeRound } from "./claude-worker.mjs";
import { Cycle, initialCycle } from "./cycle.mjs";
import { enqueue, ensureListener, Inbox, Outbox, say, SlackExtension, slackFromEnv, slackInbound } from "./slack.mjs";

const run = promisify(execFile);
const context = BACKGROUND_CONTEXT;

const ROUND_KINDS = { plan: "plan", build: "build", review: "review" };

/** Paths a phase may change. Anything else it changes is reverted at the end of its round. */
export function scopeOf(kind, config) {
	if (kind === ROUND_KINDS.plan) return [config.planDir];
	if (kind === ROUND_KINDS.review) return [];
	return [config.appDir];
}

function inside(rel, scope) {
	return scope.some((dir) => rel === dir || rel.startsWith(`${dir}/`));
}

// ---------------------------------------------------------------------------------------
// Guardrails as code
// ---------------------------------------------------------------------------------------

/**
 * Spec-lock and the scope fence, at the moment of the edit: write and edit outside the
 * round's scope are blocked before they run. bash cannot be judged by its arguments, which
 * is why the same scope is enforced again on the working tree after the round.
 */
function guardExtension(config, scopeRef) {
	return defineExtension({
		name: "loyly-guard",
		hooks: [
			hook(ToolTask, {
				beforeTool: (call) => {
					if (call.name !== "write" && call.name !== "edit") return undefined;
					const target = call.arguments?.path;
					if (typeof target !== "string") return { block: "a write needs a path" };
					const rel = path.relative(config.root, path.resolve(config.root, target));
					if (rel.startsWith("..") || path.isAbsolute(rel)) return { block: `${target} is outside the repository` };
					const scope = scopeRef.current;
					if (inside(rel, scope)) return undefined;
					return {
						block:
							scope.length === 0
								? `this is a review round: it reads and reports, it edits nothing (${rel} refused)`
								: `${rel} is outside this round's scope (${scope.join(", ")}). Specs change in planning rounds, on purpose, not mid-build.`,
					};
				},
			}),
		],
	});
}

/** The round's standing instructions, rendered from the committed cycle document. */
const METHOD = [
	"You are building Löyly by the method in docs/wow/. The docs are the source of truth; the code is a build product.",
	"Read order before any work: README.md, docs/wow/, docs/wiki/ (from index.md), docs/plan/ (the current slice). raw/ is background: read it to check a trace, never edit it.",
	"Every round starts from those documents, not from memory: this context was reset on purpose.",
].join("\n");

function tddInstruction(cycle) {
	if (!cycle || cycle.phase !== "implement") return undefined;
	return cycle.tdd === "GREEN"
		? "TDD state GREEN: write the next failing test from the slice's spec.feature. Do not implement features. Do not look ahead. This overrides everything else about what to do next."
		: "TDD state RED: write the minimum implementation that makes the failing test pass. Do not write new tests. This overrides everything else about what to do next.";
}

function stateExtension() {
	return defineExtension({
		name: "loyly-state",
		sections: [
			section("loyly-method", () => METHOD),
			section("loyly-tdd", async (input, ctx) => tddInstruction(await input.read.snapshot(Cycle, ctx))),
		],
	});
}

/** Run the gate. Shared by the claim_done tool (pi worker) and the loop (Claude Code worker). */
async function ratify(config) {
	const result = await runCommand(config.verify, [], config.root, config.verifyTimeoutMs);
	return { ratified: result.code === 0, code: result.code, output: tail(result.output, 4000) };
}

/**
 * Ratified DONE. The agent may claim done; only harness/verify.sh, a script it does not
 * control, can make the claim count. A passing gate is committed to the cycle document by
 * the tool itself, so a crash between the gate and the loop noticing it loses nothing.
 */
function claimDoneTool(config) {
	return defineTool({
		name: "claim_done",
		description:
			"Claim that the slice is done. The claim is ratified only if harness/verify.sh passes; its output is returned either way. Do not call this to check progress, call it when you believe the whole slice is built.",
		parameters: obj({ summary: str() }),
		replay: "safe", // the gate is idempotent: running it again after a crash is the correct recovery
		execute: async (args, api, ctx) => {
			const result = await ratify(config);
			const ratified = result.ratified;
			if (ratified) {
				await api.commit(async (tx) => {
					const cycle = await tx.doc(Cycle);
					cycle.ratified = { at: new Date().toISOString(), summary: args.summary, output: result.output };
				}, ctx);
			}
			return {
				output: [
					{
						type: "text",
						text: ratified
							? `RATIFIED. The gate passed.\n${result.output}`
							: `NOT RATIFIED. The gate exited ${result.code}; "done" is a claim and this one did not hold.\n${result.output}`,
					},
				],
				control: ratified ? { terminate: true } : undefined,
			};
		},
	});
}

// ---------------------------------------------------------------------------------------
// The working tree
// ---------------------------------------------------------------------------------------

async function runCommand(cmd, args, cwd, timeout) {
	try {
		const { stdout, stderr } = await run(cmd, args, { cwd, timeout, maxBuffer: 16 * 1024 * 1024 });
		return { code: 0, output: `${stdout}${stderr}` };
	} catch (error) {
		const code = typeof error.code === "number" ? error.code : 1;
		return { code, output: `${error.stdout ?? ""}${error.stderr ?? ""}${error.killed ? "\n(timed out)" : ""}` };
	}
}

function tail(text, n) {
	return text.length <= n ? text : `…${text.slice(-n)}`;
}

/** Paths git sees as changed or new, relative to the root. Ignored files are not listed. */
export async function changedPaths(root) {
	const { stdout } = await run("git", ["status", "--porcelain=v1", "-z", "--untracked-files=all"], { cwd: root });
	const out = [];
	const parts = stdout.split("\0").filter(Boolean);
	for (let i = 0; i < parts.length; i++) {
		const status = parts[i].slice(0, 2);
		const file = parts[i].slice(3);
		out.push({ status, file });
		if (status.startsWith("R") || status.startsWith("C")) {
			out.push({ status: "D ", file: parts[++i] }); // the rename's source
		}
	}
	return out;
}

/**
 * The state a round starts from: the index, written as a tree. It is committed with the
 * round, so the scope fence puts things back to where this round found them, not to HEAD.
 * A plan round's staged edits are part of it, so the build rounds after it keep the plan;
 * and a build round that `git add`s a change outside its scope does not get to keep it.
 */
export async function baselineTree(root) {
	const { stdout } = await run("git", ["write-tree"], { cwd: root });
	return stdout.trim();
}

async function pathsChangedSince(root, tree) {
	const names = async (...args) => (await run("git", args, { cwd: root, maxBuffer: 16 * 1024 * 1024 })).stdout.split("\0").filter(Boolean);
	const tracked = await names("diff", "--name-only", "-z", "--no-renames", tree);
	const staged = await names("diff", "--cached", "--name-only", "-z", "--no-renames", tree);
	const untracked = await names("ls-files", "--others", "--exclude-standard", "-z");
	return [...new Set([...tracked, ...staged, ...untracked])].sort();
}

/**
 * The scope fence, after the round: whatever changed outside the round's scope since
 * `tree` is put back to `tree`, in the index and the working tree; files that did not
 * exist there are removed. Returns what it reverted, which is a finding.
 */
export async function revertOutside(root, scope, tree) {
	const base = tree ?? (await run("git", ["rev-parse", "HEAD^{tree}"], { cwd: root })).stdout.trim();
	const reverted = [];
	for (const file of await pathsChangedSince(root, base)) {
		if (inside(file, scope)) continue;
		const existed = await run("git", ["cat-file", "-e", `${base}:${file}`], { cwd: root }).then(
			() => true,
			() => false,
		);
		if (existed) {
			await run("git", ["restore", `--source=${base}`, "--staged", "--worktree", "--", file], { cwd: root });
		} else {
			await run("git", ["rm", "-q", "--cached", "--ignore-unmatch", "--", file], { cwd: root });
			await rm(path.join(root, file), { force: true });
		}
		reverted.push(file);
	}
	return reverted;
}

/** The TDD state is the suite's verdict, measured outside the agent. */
async function measureTdd(config) {
	const suite = path.join(config.root, config.appDir, "test.sh");
	const result = await runCommand("bash", [suite], config.root, config.testTimeoutMs);
	if (/No such file/.test(result.output) && result.code !== 0) return { tdd: "GREEN", note: "no suite yet" };
	return { tdd: result.code === 0 ? "GREEN" : "RED", note: tail(result.output, 1500) };
}

// ---------------------------------------------------------------------------------------
// The loop
// ---------------------------------------------------------------------------------------

/**
 * @param {object} options
 * @param {string} options.root           repository root
 * @param {import("@earendil-works/pi-ai").Models} options.models
 * @param {{provider: string, modelId: string}} options.model
 * @param {string} [options.store]        sqlite file; default <root>/.loyly-agent/cycle.sqlite
 * @param {number} [options.maxRounds]    implementation rounds before the cycle is called exhausted
 * @param {number} [options.reviewEvery]  every Nth round reviews instead of builds; 0 never
 * @param {number} [options.maxTurns]     tool rounds one round may take
 * @param {boolean} [options.slack]      report to Slack (agent/slack.mjs); default: when the environment configures it
 * @param {boolean} [options.approval]   a ratified DONE also waits for a person's "approve" in the Slack thread
 * @param {"pi" | "claude-code"} [options.worker]  who does a round's work: a pi-ai model run inside pi-durable (default), or headless Claude Code
 * @param {string} [options.claudeModel] the Claude Code worker's --model (an alias such as "opus", or a model id); default Claude Code's own
 * @param {string} [options.claudeBin]   the Claude Code executable, default "claude"
 * @param {boolean} [options.readOnly]   open to look, never to run: recovers nothing, counts no restart
 * @param {(line: string) => void} [options.log]
 */
export async function openLoop(options) {
	const config = {
		root: options.root,
		appDir: options.appDir ?? "impl",
		planDir: options.planDir ?? "docs/plan",
		verify: options.verify ?? path.join(options.root, "harness/verify.sh"),
		store: options.store ?? path.join(options.root, ".loyly-agent", "cycle.sqlite"),
		maxRounds: options.maxRounds ?? 20,
		reviewEvery: options.reviewEvery ?? 4,
		maxTurns: options.maxTurns ?? 40,
		testTimeoutMs: options.testTimeoutMs ?? 300_000,
		verifyTimeoutMs: options.verifyTimeoutMs ?? 600_000,
		worker: options.worker ?? "pi",
		claudeBin: options.claudeBin ?? process.env.LOYLY_CLAUDE_BIN ?? "claude",
		claudeTimeoutMs: options.claudeTimeoutMs ?? 3_600_000,
	};
	if (!["pi", "claude-code"].includes(config.worker)) throw new Error(`unknown worker "${config.worker}": pi or claude-code`);
	const log = options.log ?? (() => {});
	const notify = options.slack ?? Boolean(slackFromEnv());
	const listen = notify && Boolean(slackInbound());
	const approval = Boolean(options.approval);
	const scopeRef = { current: [] };

	const registry = createRegistry();
	registry.install(CodingTools);
	registry.install(stateExtension());
	registry.install(guardExtension(config, scopeRef));
	registry.install(defineExtension({ name: "loyly-gate", tools: [claimDoneTool(config)] }));
	registry.install(SlackExtension); // always: a sender queued by an earlier process must find its definition
	const readOnlyTools = CodingTools.tools.filter((t) => t.name === "read" || t.name === "bash");

	await mkdir(path.dirname(config.store), { recursive: true });
	const harness = await Harness.open(
		await openNodeSqliteStorage(config.store),
		{
			models: options.models ?? createModels(), // the Claude Code worker never calls it
			registry,
			env: ({ cwd }) => new NodeExecutionEnv({ cwd: cwd ?? config.root }),
			settings: { afterFailedRun: "withdraw" },
			readOnly: Boolean(options.readOnly),
			onReport: (report) => {
				const line = `${report.kind}${report.reason ? ` (${report.reason})` : ""}${report.task ? ` in ${report.task.kind}` : ""}`;
				log(`report: ${line}`);
				const ours = report.task?.kind?.startsWith("loyly.slack-");
				if (notify && !options.readOnly && !ours && (report.kind === "task_faulted" || report.kind === "task_interrupted"))
					void harness.commit(async (tx) => enqueue(tx, [say.failed(line)], await conversationFor(tx)), context).catch(() => {});
			},
		},
		context,
	);

	const read = async () => (await harness.snapshot(Cycle, context)) ?? initialCycle();
	/**
	 * Change the cycle document. `messages`, given the changed document, says what to tell
	 * Slack about it; they are queued in this same commit, so a state change and its report
	 * are stored together or not at all.
	 */
	const write = (change, messages) =>
		harness.commit(async (tx) => {
			const cycle = await tx.doc(Cycle);
			await change(cycle);
			if (notify && messages) await enqueue(tx, messages(cycle).filter(Boolean), cycle.buildConversation ?? cycle.planConversation);
		}, context);
	async function conversationFor(tx) {
		const cycle = await tx.doc(Cycle);
		return cycle.buildConversation ?? cycle.planConversation;
	}

	async function conversations() {
		let cycle = await read();
		// With the Claude Code worker these conversations never run a model; they stay as the
		// address a Slack steer is tried against, which then becomes a note (nothing is running).
		const agent = { ...(options.model && config.worker === "pi" ? { model: options.model } : {}), cwd: config.root, maxTurns: config.maxTurns };
		if (!cycle.planConversation) {
			const plan = await harness.createConversation({ ownership: { kind: "ownerless" }, agent }, context);
			await write((c) => void (c.planConversation = plan.id));
		}
		if (!cycle.buildConversation) {
			const build = await harness.createConversation({ ownership: { kind: "ownerless" }, agent }, context);
			await write((c) => void (c.buildConversation = build.id));
		}
		cycle = await read();
		return {
			plan: await harness.conversation(cycle.planConversation, context),
			build: await harness.conversation(cycle.buildConversation, context),
		};
	}

	function kindOf(round) {
		return config.reviewEvery > 0 && round % config.reviewEvery === 0 ? ROUND_KINDS.review : ROUND_KINDS.build;
	}

	function promptFor(kind, round, cycle) {
		if (kind === ROUND_KINDS.plan)
			return [
				"PHASE: PLAN. Plan only; do not implement anything in this phase.",
				`Study the docs in the read order, compare the slice plan against its spec and against whatever exists in ${config.appDir}/, and update the slice plan and tickets under ${config.planDir}/ where they are wrong or incomplete.`,
				"Answer with the plan the implementation rounds should follow, ticket by ticket, citing the spec scenarios each ticket covers.",
			].join("\n");
		const plan = cycle.plan ? `\nThe plan from the planning phase:\n${cycle.plan}` : "";
		const findings = cycle.findings.length ? `\nFindings to address (from the last review, or from a person who rejected a DONE):\n${cycle.findings.join("\n")}` : "";
		const notes = cycle.pending?.notes?.length
			? `\nPeople said this in the cycle's Slack thread while no round was running. It is input, not a change to the docs: where it conflicts with the spec, the spec wins and you say so.\n${cycle.pending.notes.join("\n")}`
			: "";
		if (kind === ROUND_KINDS.review)
			return [
				`ROUND ${round}: ADVERSARIAL REVIEW. Review, do not build: you can read and run commands, you cannot edit.`,
				`Hunt in ${config.appDir}/ for rules nobody wrote down (invented behaviour with no spec scenario or wiki citation), for drift from spec.feature, and for tests written after the code they test.`,
				"Answer with numbered findings, each citing the file and the spec or wiki line it contradicts. Answer 'no findings' if there are none.",
				plan,
				notes,
			].join("\n");
		return [
			`ROUND ${round}: IMPLEMENT, one ticket at a time, in ${config.appDir}/ only; edits anywhere else are refused or reverted.`,
			`The gate's contract: ${config.appDir}/test.sh runs the suite, ${config.appDir}/start.sh PORT serves the app.`,
			config.worker === "claude-code"
				? "Follow the TDD state in your instructions. End the round when this step is done. When the whole slice is built, end your answer with one line: CLAIM_DONE: <one-sentence summary>. The loop then runs the gate; the claim counts only if it passes."
				: "Follow the TDD state in your instructions. End the round when this step is done; when the whole slice is built, call claim_done.",
			plan,
			findings,
			notes,
		].join("\n");
	}

	async function answerText(conversation, entryId) {
		const entry = await conversation.commit((tx) => tx.entry(AssistantEntry, entryId), context);
		const message = entry?.model?.[0];
		const text = (message?.content ?? []).filter((b) => b.type === "text").map((b) => b.text);
		return text.join("\n").trim();
	}

	/**
	 * One round, in stages the document records, so a restart knows which of them already
	 * happened. A reset that already happened is not repeated (it would throw away the run
	 * the crash interrupted), and a submission is found again by its requestId rather than
	 * sent twice.
	 */
	async function playRound(conversation, kind, round) {
		let cycle = await read();
		const requestId = `${kind}-${round}`;
		if (!cycle.pending || cycle.pending.requestId !== requestId) {
			const baseTree = await baselineTree(config.root);
			// The notes move into the round in the same commit that opens it, so a restart rebuilds
			// the same prompt and a note is never handed to two rounds or to none.
			await write((c) => {
				c.pending = { round, kind, stage: "opened", requestId, notes: c.humanNotes ?? [], baseTree };
				c.humanNotes = [];
			});
			cycle = await read();
		} else {
			log(`resuming ${requestId} at stage ${cycle.pending.stage}`);
		}
		const tddBefore = cycle.tdd;
		const work = config.worker === "claude-code" ? await workInClaude(kind, round, cycle) : await workInPi(conversation, kind, round, cycle);
		const { answer } = work;

		const reverted = await revertOutside(config.root, scopeOf(kind, config), cycle.pending.baseTree);
		// A plan round's edits are the spec changing on purpose: staged, so the rounds after it
		// start from them (their baseline is the index) without anything being committed.
		if (kind === ROUND_KINDS.plan) await run("git", ["add", "-A", "--", config.planDir], { cwd: config.root });
		const measured = kind === ROUND_KINDS.plan ? { tdd: cycle.tdd } : await measureTdd(config);
		const after = await read();
		const entry = {
			round,
			kind,
			at: new Date().toISOString(),
			outcome: work.outcome,
			worker: config.worker,
			tddBefore,
			tddAfter: measured.tdd,
			reverted,
			ratified: Boolean(after.ratified),
			notes: [],
		};
		if (work.costUsd != null) entry.costUsd = work.costUsd;
		if (work.refused?.length) {
			entry.refused = work.refused;
			entry.notes.push(`spec-lock refused ${work.refused.length} edit(s)`);
		}
		if (work.claim && !after.ratified) entry.notes.push(`claimed done ("${work.claim}") and the gate refused it`);
		if (reverted.length) entry.notes.push(`scope fence reverted ${reverted.length} path(s)`);
		if (kind === ROUND_KINDS.build && tddBefore === "GREEN" && measured.tdd === "GREEN" && !after.ratified)
			entry.notes.push("GREEN round ended GREEN: no failing test was written this round");
		if (kind === ROUND_KINDS.build && tddBefore === "RED" && measured.tdd === "RED")
			entry.notes.push("RED round ended RED: the failing test still fails");

		await write((c) => {
			c.history.push(entry);
			c.tdd = measured.tdd;
			c.pending = null;
			if (kind === ROUND_KINDS.plan) {
				c.plan = answer || c.plan;
				c.phase = "implement";
			} else {
				c.round = round;
				if (kind === ROUND_KINDS.review) c.findings = answer ? [answer] : [];
				if (work.gateRefusal) c.findings = [work.gateRefusal];
			}
			if (c.ratified) c.phase = approval ? "awaiting-approval" : "done";
		}, (c) => [say.round(entry, c), c.ratified ? say.ratified(c.ratified, round, approval) : null]);
		log(`${requestId}: ${entry.outcome}, TDD ${entry.tddBefore} -> ${entry.tddAfter}${entry.notes.length ? `; ${entry.notes.join("; ")}` : ""}`);
		return entry;
	}

	/** A round's work as a pi-ai model run inside pi-durable: the conversation is reset, then given the prompt. */
	async function workInPi(conversation, kind, round, cycle) {
		const requestId = cycle.pending.requestId;
		scopeRef.current = scopeOf(kind, config);
		const tools = kind === ROUND_KINDS.review ? readOnlyTools : null;
		await conversation.configure({ tools }, context);
		if (cycle.pending.stage === "opened") {
			await conversation.reset(undefined, context); // the orient step: no memory carried in
			await write((c) => void (c.pending.stage = "reset"));
		}
		const submission = await conversation.submit({ type: "input", content: promptFor(kind, round, cycle), requestId }, context);
		await write((c) => void (c.pending.stage = "submitted"));
		log(`${requestId}: running`);
		const settled = await submission.wait(context);
		return {
			outcome: settled.status === "done" ? "answered" : `unanswered: ${settled.reason ?? "unknown"}`,
			answer: settled.status === "done" ? await answerText(conversation, settled.answer) : "",
		};
	}

	/**
	 * A round's work in headless Claude Code. The session id is committed before Claude
	 * starts, so a loop that dies mid-round resumes that session instead of starting over.
	 * A CLAIM_DONE line is ratified here, by the gate, and the verdict committed at once.
	 */
	async function workInClaude(kind, round, cycle) {
		const requestId = cycle.pending.requestId;
		const resume = cycle.pending.stage === "submitted" && Boolean(cycle.pending.claudeSession);
		const sessionId = cycle.pending.claudeSession ?? randomUUID();
		if (!resume) await write((c) => void Object.assign(c.pending, { stage: "submitted", claudeSession: sessionId }));
		log(`${requestId}: running in Claude Code`);
		const system = [METHOD, tddInstruction(cycle)].filter(Boolean).join("\n");
		const result = await runClaudeRound({
			root: config.root,
			sessionId,
			prompt: promptFor(kind, round, cycle),
			system,
			scope: scopeOf(kind, config),
			resume,
			model: options.claudeModel,
			bin: config.claudeBin,
			timeoutMs: config.claudeTimeoutMs,
			log,
		});
		let gateRefusal = null;
		if (result.claim && kind === ROUND_KINDS.build) {
			const gate = await ratify(config);
			if (gate.ratified) await write((c) => void (c.ratified = { at: new Date().toISOString(), summary: result.claim, output: gate.output }));
			else gateRefusal = `You claimed done and the gate refused it (exit ${gate.code}):\n${gate.output}`;
		}
		return {
			outcome: result.ok ? "answered" : `unanswered: ${result.error}`,
			answer: result.answer.replace(/^\s*CLAIM_DONE:.*$/m, "").trim(),
			claim: result.claim,
			refused: result.denials,
			costUsd: result.costUsd,
			gateRefusal,
		};
	}

	/** Run the cycle from wherever the store says it is, until DONE, exhaustion, or `until`. */
	async function drive({ until } = {}) {
		let cycle = await read();
		if (cycle.phase === "new") {
			const outside = (await changedPaths(config.root)).filter((c) => !inside(c.file, [config.appDir, config.planDir]));
			if (outside.length)
				throw new Error(
					`refusing to start a cycle over uncommitted changes the scope fence would revert:\n  ${outside.map((c) => c.file).join("\n  ")}\ncommit or stash them first`,
				);
		}
		if (approval && !listen)
			throw new Error(
				"--approve needs somebody to ask: set SLACK_BOT_TOKEN, SLACK_CHANNEL and SLACK_ALLOWED_USERS (an incoming webhook cannot read replies)",
			);
		const { plan, build } = await conversations();
		// Start the scheduler now, not at the first submit: a process that only waits (for an
		// approval, say) submits nothing, and the tasks a dead process left (the Slack
		// listener, a sender with a queue) would otherwise never run again.
		harness.resume();
		if (listen) await harness.commit(async (tx) => ensureListener(tx, build.id), context);
		if (cycle.phase === "new") {
			await write((c) => void (c.phase = "plan"), () => [say.started(config.root, `${options.model.provider}/${options.model.modelId}`)]);
		} else if (cycle.pending) {
			const what = `${cycle.pending.requestId} at stage ${cycle.pending.stage}`;
			await write(() => {}, () => [say.started(config.root, null, `continuing ${what} after the last process stopped`)]);
		}
		for (;;) {
			cycle = await read();
			if (cycle.phase === "done" || cycle.phase === "exhausted") return cycle;
			if (until?.(cycle)) return cycle;
			if (cycle.phase === "awaiting-approval") {
				// The listener moves the cycle on, in its own commit; this loop only waits for it.
				await waitForPhaseChange("awaiting-approval");
				continue;
			}
			if (cycle.phase === "plan") {
				await playRound(plan, ROUND_KINDS.plan, 0);
				continue;
			}
			const next = cycle.pending?.round ?? cycle.round + 1;
			if (next > config.maxRounds) {
				await write((c) => void (c.phase = "exhausted"), () => [say.exhausted(config.maxRounds)]);
				continue;
			}
			await playRound(build, cycle.pending?.kind ?? kindOf(next), next);
		}
	}

	async function waitForPhaseChange(phase) {
		let logged = false;
		for (;;) {
			const cycle = await read();
			if (cycle.phase !== phase) return cycle;
			if (!logged) log("ratified; waiting for a person to approve or reject in Slack");
			logged = true;
			await new Promise((r) => setTimeout(r, 250));
		}
	}

	/** Wait, at most `ms`, for the Slack outbox to drain; what is left is sent by the next process. */
	async function flush(ms = 15_000) {
		const deadline = Date.now() + ms;
		for (;;) {
			const outbox = await harness.snapshot(Outbox, context);
			if (!outbox || outbox.queue.length === 0 || !outbox.sender) return outbox;
			if (Date.now() > deadline) return outbox;
			await new Promise((r) => setTimeout(r, 100));
		}
	}

	return {
		harness,
		config,
		read,
		drive,
		flush,
		outbox: () => harness.snapshot(Outbox, context),
		inbox: () => harness.snapshot(Inbox, context),
		close: () => harness.close(context),
	};
}

/** Remove the store (the database and its journal files): the next run starts a new cycle. */
export async function resetStore(store) {
	for (const suffix of ["", "-wal", "-shm"]) await rm(`${store}${suffix}`, { force: true });
}
