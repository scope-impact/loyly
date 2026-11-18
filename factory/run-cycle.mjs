#!/usr/bin/env node
// One Löyly cycle, end to end, resumable: rerun the same command and it continues from the
// stage it reached. What a person did by hand for cycle 02 on 2026-11-17, as one command.
//
//   node factory/run-cycle.mjs --cycle 03 [--workdir DIR] [--isolation user|namespace|none]
//        [--builder-user NAME] [--rounds N] [--review-every N] [--model M] [--approve]
//
// The stages, each recorded in <workdir>/cycle-<NN>/state.json before the next begins:
//   room       harness/fence.sh builds a clean room from this checkout: no impl/, no
//              harness/cycles/, proved absent from history
//   installed  npm ci in the room's harness/ and agent/
//   built      the durable loop (agent/run.mjs, Claude Code worker) builds the slice in
//              the room until harness/verify.sh ratifies a DONE, or the rounds run out;
//              the builder runs isolated (below), so it cannot read the checklist
//   graded     the checklist is derived from THIS checkout's spec with the trusted tools;
//              a separate grader (factory/grade.mjs) drives the running app and files a
//              verdict with evidence per item
//   scored     harness/score.ts and harness/scoreboard.ts, in this checkout
//   recorded   the loop's store, its log, the builder's transcripts, the grader's
//              transcript and the verdicts are copied to <workdir>/cycle-<NN>/records/
//
// ISOLATION. The method's fence is physical: a regeneration that can read last cycle's
// answer, or this cycle's checklist, is not one. The room has neither, but the machine
// does: this checkout, other rooms, earlier transcripts. So the builder runs:
//   user       as its own Unix user (--builder-user), with the room handed to it and
//              everything else unreadable to it. Needs this process to be root; it is the
//              server's mode, and needs no special kernel privileges.
//   namespace  as this user, in a mount namespace where this checkout, the workdir's other
//              cycles and Claude Code's session records are covered by empty filesystems.
//              Needs root with CAP_SYS_ADMIN, which a Claude Code cloud sandbox has and a
//              default Docker container does not.
//   none       no isolation. Said loudly, and recorded in the state, because a cycle built
//              this way may have read its own checklist.

import { execFileSync, spawn } from "node:child_process";
import { chmodSync, cpSync, existsSync, mkdirSync, readdirSync, readFileSync, renameSync, statSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";

import { grade } from "./grade.mjs";

const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const STAGES = ["room", "installed", "built", "graded", "scored", "recorded"];

export function parse(argv) {
	const { values } = parseArgs({
		args: argv,
		options: {
			cycle: { type: "string" },
			workdir: { type: "string", default: process.env.LOYLY_WORKDIR ?? path.join(REPO, "..", "loyly-cycles") },
			isolation: { type: "string", default: process.env.LOYLY_ISOLATION },
			"builder-user": { type: "string", default: process.env.LOYLY_BUILDER_USER },
			rounds: { type: "string", default: "40" },
			"review-every": { type: "string", default: "4" },
			model: { type: "string" },
			approve: { type: "boolean", default: false },
			"grader-model": { type: "string" },
			port: { type: "string", default: "8931" },
		},
	});
	if (!values.cycle || !/^\d+$/.test(values.cycle)) throw new Error("--cycle NN is required: the cycle number, as harness/cycles names it");
	return values;
}

const log = (line) => console.log(`[cycle] ${line}`);

function sh(cmd, args, opts = {}) {
	return execFileSync(cmd, args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"], ...opts });
}

function readState(dir) {
	const f = path.join(dir, "state.json");
	return existsSync(f) ? JSON.parse(readFileSync(f, "utf8")) : { stage: null, history: [] };
}

function writeState(dir, state) {
	const f = path.join(dir, "state.json");
	writeFileSync(`${f}.tmp`, `${JSON.stringify(state, null, 1)}\n`);
	renameSync(`${f}.tmp`, f); // atomic: a crash leaves the old state or the new one, never half
}

const reached = (state, stage) => state.stage !== null && STAGES.indexOf(state.stage) >= STAGES.indexOf(stage);

/** Which isolation this machine can give, when none was asked for. */
export function defaultIsolation(builderUser) {
	if (builderUser) return "user";
	if (process.getuid?.() === 0) {
		try {
			sh("unshare", ["--mount", "--propagation", "private", "true"]);
			return "namespace";
		} catch {}
	}
	return "none";
}

/** The command that runs the builder's loop, wrapped in the isolation chosen. */
export function builderCommand({ isolation, builderUser, room, cycleDir, workdir, loopArgs }) {
	const loop = `cd ${q(room)} && node agent/run.mjs ${loopArgs.map(q).join(" ")} >> ${q(path.join(cycleDir, "loop.log"))} 2>&1`;
	if (isolation === "none") return ["bash", ["-c", loop]];
	if (isolation === "user") {
		if (!builderUser) throw new Error("isolation user needs --builder-user");
		// The builder's own HOME keeps its own Claude Code login and its own transcripts.
		return ["runuser", ["-u", builderUser, "--", "bash", "-lc", loop]];
	}
	// namespace: cover every other copy of the answer and the checklist with empty filesystems,
	// and keep the builder's Claude transcripts in this cycle's records.
	const home = process.env.HOME ?? "/root";
	const hide = [REPO, path.join(home, ".claude", "projects"), path.join(home, ".claude", "sessions"), path.join(home, ".claude", "shell-snapshots"), path.join(home, ".claude", "backups"), "/tmp"];
	const otherCycles = existsSync(workdir) ? readdirSync(workdir).map((n) => path.join(workdir, n)).filter((p) => p !== cycleDir && statSync(p).isDirectory()) : [];
	const transcripts = path.join(cycleDir, "transcripts");
	mkdirSync(transcripts, { recursive: true });
	const mounts = [...hide, ...otherCycles]
		.filter((p) => existsSync(p) && !cycleDir.startsWith(`${p}/`) && p !== cycleDir)
		.map((p) => `mount -t tmpfs none ${q(p)} || exit 9`);
	const script = [...mounts, `mkdir -p ${q(path.join(home, ".claude", "projects"))}`, `mount --bind ${q(transcripts)} ${q(path.join(home, ".claude", "projects"))} || exit 9`, loop].join("\n");
	return ["unshare", ["--mount", "--propagation", "private", "bash", "-c", script]];
}

const q = (s) => `'${String(s).replace(/'/g, `'\\''`)}'`;

function run(cmd, args, opts = {}) {
	return new Promise((resolve) => {
		const child = spawn(cmd, args, { stdio: "inherit", ...opts });
		child.on("close", (code, signal) => resolve({ code, signal }));
	});
}

/** The loop's own record of where it is, read without disturbing it. */
async function loopCycle(room) {
	const out = sh("node", ["--input-type=module", "-e", `import {openLoop} from "./agent/loop.mjs"; const l = await openLoop({ root: process.cwd(), readOnly: true, worker: "claude-code", slack: false }); process.stdout.write(JSON.stringify(await l.read())); await l.close();`], { cwd: room, stdio: ["ignore", "pipe", "ignore"] });
	return JSON.parse(out);
}

export async function runCycle(opts) {
	const cycle = opts.cycle;
	const workdir = path.resolve(opts.workdir);
	const cycleDir = path.join(workdir, `cycle-${cycle}`);
	const room = path.join(cycleDir, "room");
	mkdirSync(cycleDir, { recursive: true });
	let state = readState(cycleDir);
	const advance = (stage, extra = {}) => {
		state = { ...state, ...extra, stage, history: [...state.history, { stage, at: new Date().toISOString() }] };
		writeState(cycleDir, state);
		log(`${cycle}: ${stage}`);
	};
	if (state.stage) log(`${cycle}: resuming after stage "${state.stage}"`);

	if (!reached(state, "room")) {
		if (existsSync(room)) throw new Error(`${room} exists but no stage was recorded: a half-built room is thrown away, not resumed. Remove it and run again.`);
		sh(path.join(REPO, "harness", "fence.sh"), [room], { stdio: "inherit" });
		const isolation = opts.isolation ?? defaultIsolation(opts["builder-user"]);
		if (isolation === "none") log("WARNING: isolation none. The builder can read this checkout, and with it the checklist it will be graded on.");
		advance("room", { cycle, isolation, cut: sh("git", ["rev-parse", "HEAD"], { cwd: REPO }).trim() });
	}

	if (!reached(state, "installed")) {
		sh("npm", ["ci", "--prefix", "harness", "--no-audit", "--no-fund"], { cwd: room });
		sh("npm", ["ci", "--no-audit", "--no-fund"], { cwd: path.join(room, "agent") });
		if (state.isolation === "user") {
			// The room and its log are the builder's; the cycle directory above them it may only
			// pass through, so the state, the grader and the records stay out of its reach.
			sh("chown", ["-R", opts["builder-user"], room]);
			writeFileSync(path.join(cycleDir, "loop.log"), "", { flag: "a" });
			sh("chown", [opts["builder-user"], path.join(cycleDir, "loop.log")]);
			chmodSync(cycleDir, 0o711);
		}
		advance("installed");
	}

	if (!reached(state, "built")) {
		const loopArgs = ["--worker", "claude-code", "--rounds", opts.rounds, "--review-every", opts["review-every"], ...(opts.model ? ["--model", opts.model] : []), ...(opts.approve ? ["--approve"] : [])];
		const [cmd, args] = builderCommand({ isolation: state.isolation, builderUser: opts["builder-user"], room, cycleDir, workdir, loopArgs });
		log(`${cycle}: building (isolation ${state.isolation}); the loop's log is ${path.join(cycleDir, "loop.log")}`);
		const { code, signal } = await run(cmd, args);
		const loop = await loopCycle(room);
		if (loop.phase !== "done" && loop.phase !== "exhausted") {
			throw new Error(`the loop stopped in phase "${loop.phase}" (exit ${code ?? signal}); run this command again to resume it`);
		}
		const cost = loop.history.reduce((t, h) => t + (h.costUsd ?? 0), 0);
		advance("built", { outcome: loop.phase, rounds: loop.round, costUsd: Number(cost.toFixed(2)), ratified: loop.ratified });
		if (loop.phase === "exhausted") log(`${cycle}: the rounds ran out without a ratified DONE; the cycle is graded anyway, because a measurement of an unfinished build is still a measurement`);
	}

	if (!reached(state, "graded")) {
		const result = await grade({ repo: REPO, room, cycle, cycleDir, port: Number(opts.port), model: opts["grader-model"] });
		advance("graded", { graderCostUsd: result.costUsd });
	}

	if (!reached(state, "scored")) {
		const verdicts = path.join(cycleDir, "verdicts.json");
		const scored = sh("node", ["harness/score.ts", cycle, "--verdicts", verdicts], { cwd: REPO });
		const board = sh("node", ["harness/scoreboard.ts", cycle], { cwd: REPO });
		writeFileSync(path.join(cycleDir, "scoreboard.txt"), board);
		advance("scored", { score: scored.split("\n")[0], scoreboard: board });
		process.stdout.write(board);
	}

	if (!reached(state, "recorded")) {
		const records = path.join(cycleDir, "records");
		mkdirSync(records, { recursive: true });
		cpSync(path.join(room, ".loyly-agent"), path.join(records, "loop-store"), { recursive: true });
		for (const f of ["loop.log", "verdicts.json", "scoreboard.txt", "grader-transcript.jsonl", "state.json"]) {
			if (existsSync(path.join(cycleDir, f))) cpSync(path.join(cycleDir, f), path.join(records, f));
		}
		if (state.isolation === "user") {
			// The builder's own Claude Code keeps its transcripts in its own home.
			const home = sh("getent", ["passwd", opts["builder-user"]]).split(":")[5];
			const projects = path.join(home, ".claude", "projects", room.replace(/[^a-zA-Z0-9]/g, "-"));
			if (existsSync(projects)) cpSync(projects, path.join(cycleDir, "transcripts"), { recursive: true });
		}
		if (existsSync(path.join(cycleDir, "transcripts"))) sh("tar", ["-czf", path.join(records, "builder-transcripts.tar.gz"), "-C", cycleDir, "transcripts"]);
		cpSync(path.join(REPO, "harness", "cycles", cycle), path.join(records, "harness-cycle"), { recursive: true });
		advance("recorded", { records });
	}
	return state;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
	try {
		const state = await runCycle(parse(process.argv.slice(2)));
		log(`${state.cycle}: done. ${state.score ?? ""}`);
	} catch (error) {
		console.error(`[cycle] ${error.message}`);
		process.exit(1);
	}
}

