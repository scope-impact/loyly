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
import { mkdir, rm } from "node:fs/promises";
import path from "node:path";
import { promisify } from "node:util";

import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import {
	AssistantEntry,
	createRegistry,
	defineDoc,
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

const run = promisify(execFile);
const context = BACKGROUND_CONTEXT;

/** Bump when the shape of the document changes. */
const CYCLE_VERSION = 1;

/** The cycle's position. Everything the loop needs to continue after a crash is in here. */
const initialCycle = () => ({
	phase: "new", // new -> plan -> implement -> done | exhausted
	round: 0, // the last round that finished
	tdd: "GREEN", // GREEN: write the next failing test. RED: make it pass.
	planConversation: null,
	buildConversation: null,
	plan: null, // the planning answer, handed to every implementation round
	pending: null, // { round, kind, stage: "opened" | "reset" | "submitted", requestId }
	ratified: null, // { at, summary, output } once harness/verify.sh passed under claim_done
	findings: [], // the latest review's answer, handed to the next build rounds
	history: [],
});
export const Cycle = defineDoc({
	kind: "loyly.cycle",
	version: CYCLE_VERSION,
	scope: "session",
	initial: initialCycle,
	checkpointWhen: () => true,
});

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
function stateExtension() {
	return defineExtension({
		name: "loyly-state",
		sections: [
			section("loyly-method", () =>
				[
					"You are building Löyly by the method in docs/wow/. The docs are the source of truth; the code is a build product.",
					"Read order before any work: README.md, docs/wow/, docs/wiki/ (from index.md), docs/plan/ (the current slice). raw/ is background: read it to check a trace, never edit it.",
					"Every round starts from those documents, not from memory: this context was reset on purpose.",
				].join("\n"),
			),
			section("loyly-tdd", async (input, ctx) => {
				const cycle = await input.read.snapshot(Cycle, ctx);
				if (!cycle || cycle.phase !== "implement") return undefined;
				return cycle.tdd === "GREEN"
					? "TDD state GREEN: write the next failing test from the slice's spec.feature. Do not implement features. Do not look ahead. This overrides everything else about what to do next."
					: "TDD state RED: write the minimum implementation that makes the failing test pass. Do not write new tests. This overrides everything else about what to do next.";
			}),
		],
	});
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
			const result = await runCommand(config.verify, [], config.root, config.verifyTimeoutMs);
			const ratified = result.code === 0;
			if (ratified) {
				await api.commit(async (tx) => {
					const cycle = await tx.doc(Cycle);
					cycle.ratified = { at: new Date().toISOString(), summary: args.summary, output: tail(result.output, 4000) };
				}, ctx);
			}
			return {
				output: [
					{
						type: "text",
						text: ratified
							? `RATIFIED. The gate passed.\n${tail(result.output, 4000)}`
							: `NOT RATIFIED. The gate exited ${result.code}; "done" is a claim and this one did not hold.\n${tail(result.output, 4000)}`,
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
 * The scope fence, after the round: whatever changed outside the round's scope is put back
 * to HEAD, new files outside it are removed. Returns what it reverted, which is a finding.
 */
export async function revertOutside(root, scope) {
	const reverted = [];
	for (const { status, file } of await changedPaths(root)) {
		if (inside(file, scope)) continue;
		if (status === "??") await rm(path.join(root, file), { force: true });
		else await run("git", ["restore", "--source=HEAD", "--staged", "--worktree", "--", file], { cwd: root });
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
	};
	const log = options.log ?? (() => {});
	const scopeRef = { current: [] };

	const registry = createRegistry();
	registry.install(CodingTools);
	registry.install(stateExtension());
	registry.install(guardExtension(config, scopeRef));
	registry.install(defineExtension({ name: "loyly-gate", tools: [claimDoneTool(config)] }));
	const readOnlyTools = CodingTools.tools.filter((t) => t.name === "read" || t.name === "bash");

	await mkdir(path.dirname(config.store), { recursive: true });
	const harness = await Harness.open(
		await openNodeSqliteStorage(config.store),
		{
			models: options.models,
			registry,
			env: ({ cwd }) => new NodeExecutionEnv({ cwd: cwd ?? config.root }),
			settings: { afterFailedRun: "withdraw" },
			readOnly: Boolean(options.readOnly),
			onReport: (report) => log(`report: ${report.kind}${report.reason ? ` (${report.reason})` : ""}`),
		},
		context,
	);

	const read = async () => (await harness.snapshot(Cycle, context)) ?? initialCycle();
	const write = (change) => harness.commit(async (tx) => change(await tx.doc(Cycle)), context);
	// A snapshot of a document nobody has written is absent; create it so every read sees it.
	if (!options.readOnly && !(await read())) await write(() => {});

	async function conversations() {
		let cycle = await read();
		const agent = { model: options.model, cwd: config.root, maxTurns: config.maxTurns };
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
		const findings = cycle.findings.length ? `\nFindings from the last review, to address:\n${cycle.findings.join("\n")}` : "";
		if (kind === ROUND_KINDS.review)
			return [
				`ROUND ${round}: ADVERSARIAL REVIEW. Review, do not build: you can read and run commands, you cannot edit.`,
				`Hunt in ${config.appDir}/ for rules nobody wrote down (invented behaviour with no spec scenario or wiki citation), for drift from spec.feature, and for tests written after the code they test.`,
				"Answer with numbered findings, each citing the file and the spec or wiki line it contradicts. Answer 'no findings' if there are none.",
				plan,
			].join("\n");
		return [
			`ROUND ${round}: IMPLEMENT, one ticket at a time, in ${config.appDir}/ only; edits anywhere else are refused or reverted.`,
			`The gate's contract: ${config.appDir}/test.sh runs the suite, ${config.appDir}/start.sh PORT serves the app.`,
			"Follow the TDD state in your instructions. End the round when this step is done; when the whole slice is built, call claim_done.",
			plan,
			findings,
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
			await write((c) => void (c.pending = { round, kind, stage: "opened", requestId }));
			cycle = await read();
		} else {
			log(`resuming ${requestId} at stage ${cycle.pending.stage}`);
		}
		scopeRef.current = scopeOf(kind, config);
		const tools = kind === ROUND_KINDS.review ? readOnlyTools : null;
		await conversation.configure({ tools }, context);

		if (cycle.pending.stage === "opened") {
			await conversation.reset(undefined, context); // the orient step: no memory carried in
			await write((c) => void (c.pending.stage = "reset"));
		}
		const tddBefore = cycle.tdd;
		const submission = await conversation.submit({ type: "input", content: promptFor(kind, round, cycle), requestId }, context);
		await write((c) => void (c.pending.stage = "submitted"));
		log(`${requestId}: running`);
		const settled = await submission.wait(context);
		const answer = settled.status === "done" ? await answerText(conversation, settled.answer) : "";

		const reverted = await revertOutside(config.root, scopeOf(kind, config));
		const measured = kind === ROUND_KINDS.plan ? { tdd: cycle.tdd } : await measureTdd(config);
		const after = await read();
		const entry = {
			round,
			kind,
			at: new Date().toISOString(),
			outcome: settled.status === "done" ? "answered" : `unanswered: ${settled.reason ?? "unknown"}`,
			tddBefore,
			tddAfter: measured.tdd,
			reverted,
			ratified: Boolean(after.ratified),
			notes: [],
		};
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
			}
			if (c.ratified) c.phase = "done";
		});
		log(`${requestId}: ${entry.outcome}, TDD ${entry.tddBefore} -> ${entry.tddAfter}${entry.notes.length ? `; ${entry.notes.join("; ")}` : ""}`);
		return entry;
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
			await write((c) => void (c.phase = "plan"));
		}
		const { plan, build } = await conversations();
		for (;;) {
			cycle = await read();
			if (cycle.phase === "done" || cycle.phase === "exhausted") return cycle;
			if (until?.(cycle)) return cycle;
			if (cycle.phase === "plan") {
				await playRound(plan, ROUND_KINDS.plan, 0);
				continue;
			}
			const next = cycle.pending?.round ?? cycle.round + 1;
			if (next > config.maxRounds) {
				await write((c) => void (c.phase = "exhausted"));
				continue;
			}
			await playRound(build, cycle.pending?.kind ?? kindOf(next), next);
		}
	}

	return {
		harness,
		config,
		read,
		drive,
		close: () => harness.close(context),
	};
}

/** Remove the store (the database and its journal files): the next run starts a new cycle. */
export async function resetStore(store) {
	for (const suffix of ["", "-wal", "-shm"]) await rm(`${store}${suffix}`, { force: true });
}
