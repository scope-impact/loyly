// The Löyly builder host: one durable pi session (@earendil-works/pi-durable) for one builder step.
//
//   node host.ts <builder-room> <instruction-file> <session.sqlite> <request-id> <pi-settings.json>
//
// The session, its SQLite store and the model credentials stay on the host; every tool call runs in a
// Gondolin VM that mounts only <builder-room>, through pi-env's RemoteExecutionEnv talking to pi-env's
// daemon inside the VM (vm-daemon.ts). The tools are those pi's settings file selects with its
// `defaultTools` key (pi's own format and resolution); the VM's limits are policy.ts, and its image
// is GONDOLIN_GUEST_DIR (Gondolin's manifest.json). The builder's prompt is generated from those three.
//
// The same host runs the grader (../bin/grader.sh), with these optional variables (unset: the builder's):
//   LOYLY_PI_MODEL   provider/modelId of the session's model (default openai-codex/gpt-6.1-sol)
//   LOYLY_PI_PROMPT  the line appended to the instruction (default PROMPT below)
//   LOYLY_ROOM_LABEL what the prompt calls the mounted room (default "the builder room")
//   LOYLY_BASH_TIMEOUT_FLOOR_SECONDS  the least timeout a bash call runs with (default 1800): a smaller
//                    `timeout` the model passes is raised to it, a larger one kept, a missing one stays
//                    missing (no timeout). A full suite in the VM outlasts the 600 s a model tends to pass.
//   LOYLY_STEER_DIR  a steer inbox: every <name>.md file that appears there while the run works is
//                    submitted to the running session as a steer (pi-durable's `whenBusy: "steer"`,
//                    `whenIdle: "reject"`, request id `steer-<name>`, so a rerun never steers twice),
//                    and answered with <name>.steered (the submission id) or <name>.refused (why).
//                    pi-durable allows one writer per store, so a steer goes through this process.
//
// ORC owns the workflow (attempts, retries, timeouts); this host owns only the session. Rerunning it
// with the same store and request id reopens the session, resumes unfinished work, and finds the
// existing submission instead of submitting again. Exit: 0 answered, 1 unanswered (reason printed;
// SIGTERM aborts the run, which ends unanswered with reason aborted), 2 setup error.
import { basename, dirname, join, resolve } from "node:path";
import { existsSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import type { Models } from "@earendil-works/pi-ai";
import { AssistantEntry, createRegistry, defineExtension, Harness, section, type ToolRegistration } from "@earendil-works/pi-durable";
import { openNodeSqliteStorage } from "@earendil-works/pi-durable/storage/sqlite/node";
import { CodingTools } from "@earendil-works/pi-durable/tools";
import { Connection, RemoteExecutionEnv } from "@earendil-works/pi-env";
import { ALLOWED_HOSTS, GUEST_USER, GUEST_WORKSPACE, IMAGE_ADDITIONS } from "./policy.ts";
import { VM_TOOLS } from "./vm-tools.ts";

const context = BACKGROUND_CONTEXT;
const HERE = dirname(fileURLToPath(import.meta.url));
export const PROMPT = "Build it now, as the attached instruction says.";
export const MODEL = { provider: "openai-codex", modelId: "gpt-6.1-sol" } as const;

/** The session's model: LOYLY_PI_MODEL (provider/modelId), or MODEL. */
export function modelFromEnv(value = process.env.LOYLY_PI_MODEL?.trim()): { provider: string; modelId: string } {
	if (!value) return MODEL;
	const slash = value.indexOf("/");
	if (slash <= 0 || slash === value.length - 1) throw new Error(`LOYLY_PI_MODEL=${value}: expected provider/modelId`);
	return { provider: value.slice(0, slash), modelId: value.slice(slash + 1) };
}

/** The builder room as an ExecutionEnv inside a Gondolin VM; the VM starts on first use. */
export function gondolinEnv(room: string, onLog?: (text: string) => void) {
	const connection = new Connection({
		command: [process.execPath, join(HERE, "vm-daemon.ts"), resolve(room)],
		...(onLog ? { onLog } : {}),
	});
	const env = new RemoteExecutionEnv({ connection, id: `gondolin:${basename(room)}`, cwd: GUEST_WORKSPACE });
	return { env, close: () => connection.close() };
}

/** The default least `timeout` of a bash call, in seconds (LOYLY_BASH_TIMEOUT_FLOOR_SECONDS). */
export const BASH_TIMEOUT_FLOOR = 1800;

/** The bash timeout floor: LOYLY_BASH_TIMEOUT_FLOOR_SECONDS (a positive number of seconds), or BASH_TIMEOUT_FLOOR. */
export function timeoutFloorFromEnv(value = process.env.LOYLY_BASH_TIMEOUT_FLOOR_SECONDS?.trim()): number {
	if (!value) return BASH_TIMEOUT_FLOOR;
	const n = Number(value);
	if (!Number.isFinite(n) || n <= 0) throw new Error(`LOYLY_BASH_TIMEOUT_FLOOR_SECONDS=${value}: expected a positive number of seconds`);
	return n;
}

/** The tool with a model-given `timeout` below `floor` raised to it; a larger one is kept, a missing one stays missing. */
export function withTimeoutFloor(tool: ToolRegistration, floor: number): ToolRegistration {
	return {
		...tool,
		execute: (args, api, ctx) => {
			const t = (args as { timeout?: unknown }).timeout;
			return tool.execute(typeof t === "number" && t < floor ? { ...(args as object), timeout: floor } : args, api, ctx);
		},
	};
}

/** Every tool the host can give, by pi's names: pi-durable's read/write/edit/bash and pi's ls/find/grep. */
const ALL_TOOLS: Record<string, ToolRegistration> = {
	...Object.fromEntries((CodingTools.tools ?? []).map((tool) => [tool.name, tool as ToolRegistration])),
	...VM_TOOLS,
};

/** The tools a pi settings file selects: its `defaultTools`, resolved by pi's own SettingsManager. */
export async function toolsFromPiSettings(file: string): Promise<string[]> {
	const { SettingsManager } = await import("@earendil-works/pi-coding-agent");
	const tools = SettingsManager.inMemory(JSON.parse(readFileSync(file, "utf8"))).getDefaultTools();
	if (!tools?.length) throw new Error(`${file}: no defaultTools`);
	const unknown = tools.filter((name) => !(name in ALL_TOOLS));
	if (unknown.length) throw new Error(`${file}: unknown tools ${unknown.join(", ")} (known: ${Object.keys(ALL_TOOLS).join(", ")})`);
	return tools;
}

/** Gondolin's build config of the guest image, from manifest.json in GONDOLIN_GUEST_DIR. */
function imageConfig(): { distro?: string; arch?: string; oci?: { image?: string }; alpine?: { version?: string } } | undefined {
	const dir = process.env.GONDOLIN_GUEST_DIR;
	return dir && existsSync(join(dir, "manifest.json")) ? JSON.parse(readFileSync(join(dir, "manifest.json"), "utf8")).config : undefined;
}

/** The guest image as Gondolin's manifest.json describes it. */
function imageLine(): string {
	const c = imageConfig();
	if (!c) return "an isolated Linux VM";
	const base = c.oci?.image ?? `${c.distro ?? "linux"} ${c.alpine?.version ?? ""}`.trim();
	return `an isolated Linux VM (image ${base}, ${c.arch})`;
}

/** The install command of the image's distribution (Gondolin's `distro`: alpine or nixos). */
function installCommand(): string | undefined {
	return imageConfig()?.distro === "alpine" ? "apk add" : undefined;
}

/** The builder's environment as a prompt section, generated from what the VM and tools enforce. */
export function environmentText(tools: readonly string[], roomLabel = "the builder room", timeoutFloor = BASH_TIMEOUT_FLOOR): string {
	const network =
		ALLOWED_HOSTS.length === 0
			? "Network: none. Every outbound connection from the VM is refused; nothing can be downloaded, so work with what the image already has."
			: `Network: HTTPS and HTTP only to ${ALLOWED_HOSTS.join(", ")}; every other outbound connection is refused, and there is no SSH.`;
	return [
		`You run as ${GUEST_USER} (superuser) in ${imageLine()}.`,
		network,
		`Your working directory is ${GUEST_WORKSPACE}: ${roomLabel}, read-write, and the only host directory the VM can see.`,
		"The image already carries the toolchain; use it first.",
		...(installCommand()
			? [
					`If something you need is missing, install it with \`${installCommand()}\` as ${GUEST_USER}, and append the exact install command to ${IMAGE_ADDITIONS} (outside the candidate scope) so the image's Dockerfile can be updated.`,
				]
			: []),
		`Your tools, all running inside the VM: ${tools.join(", ")}.`,
		...(tools.includes("bash")
			? [`A bash command may run up to ${minutes(timeoutFloor)}: whatever timeout you pass is raised to at least that.`]
			: []),
	].join("\n");
}

function minutes(seconds: number): string {
	return seconds % 60 === 0 ? `${seconds / 60} minutes` : `${seconds} seconds`;
}

export interface BuilderRun {
	room: string;
	instruction: string;
	store: string;
	requestId: string;
	models: Models;
	model?: { provider: string; modelId: string };
	/** pi tool names, as `toolsFromPiSettings()` resolves them. */
	tools: readonly string[];
	/** The line appended to the instruction (default PROMPT). */
	prompt?: string;
	/** What the environment prompt calls the room (default "the builder room"). */
	roomLabel?: string;
	/** A steer inbox directory (LOYLY_STEER_DIR), polled while the run works. */
	steerDir?: string;
	/** The bash timeout floor in seconds (default BASH_TIMEOUT_FLOOR). */
	timeoutFloor?: number;
	log?: (text: string) => void;
}

/** Open (or reopen) the session, submit once by request id, wait. Resolves with the settled submission and answer. */
export async function runBuilder(run: BuilderRun) {
	const log = run.log ?? ((text: string) => process.stderr.write(`${text}\n`));
	const { env, close: closeEnv } = gondolinEnv(run.room, (text) => log(`[pi-env] ${text.trimEnd()}`));
	const registry = createRegistry();
	const floor = run.timeoutFloor ?? BASH_TIMEOUT_FLOOR;
	const prompt = environmentText(run.tools, run.roomLabel, floor);
	registry.install(
		defineExtension({
			name: "loyly-builder",
			tools: run.tools.map((name) => (name === "bash" ? withTimeoutFloor(ALL_TOOLS[name]!, floor) : ALL_TOOLS[name]!)),
			sections: [section("environment", () => prompt, { tag: false })],
		}),
	);
	const harness = await Harness.open(
		await openNodeSqliteStorage(run.store),
		{ models: run.models, registry, env: () => env },
		context,
	);
	// SIGTERM aborts the run; the wait then settles `unanswered` (reason `aborted`) and the store closes below.
	let root: Awaited<ReturnType<typeof harness.root>> | undefined;
	let terminated = false;
	const onTerm = () => {
		terminated = true;
		log("SIGTERM: aborting the run");
		root?.abort(context).catch((error) => log(`abort: ${String(error)}`));
	};
	process.once("SIGTERM", onTerm);
	let steering: ReturnType<typeof setInterval> | undefined;
	try {
		harness.resume();
		root = await harness.root(context, {
			agent: { model: run.model ?? MODEL, thinkingLevel: "high", cwd: GUEST_WORKSPACE },
		});
		if (terminated) throw new Error("SIGTERM before the submission; nothing submitted");
		const content = `${readFileSync(run.instruction, "utf8")}\n\n${run.prompt ?? PROMPT}`;
		const submission = await root.submit({ type: "input", content, requestId: run.requestId }, context);
		log(`submission ${submission.id} request ${run.requestId}`);
		if (run.steerDir) {
			const dir = run.steerDir;
			const target = root;
			let busy = false;
			steering = setInterval(() => {
				if (busy) return;
				busy = true;
				steerOnce(dir, target, log).finally(() => (busy = false));
			}, 5000);
		}
		const settled = await submission.wait(context);
		let answer: string | undefined;
		if (settled.status === "done" && settled.type === "input") {
			const entry = await root.commit((tx) => tx.entry(AssistantEntry, settled.answer), context);
			answer = (entry?.model ?? [])
				.flatMap((m: { content?: unknown }) => (Array.isArray(m.content) ? m.content : []))
				.filter((c: { type?: string }) => c.type === "text")
				.map((c: { text?: string }) => c.text ?? "")
				.join("\n");
		}
		return { settled, answer };
	} finally {
		if (steering) clearInterval(steering);
		process.off("SIGTERM", onTerm);
		await harness.close(context);
		closeEnv();
	}
}

/** Submit each new <name>.md of the steer inbox as a steer of the running work; answer it with .steered or .refused. */
async function steerOnce(
	dir: string,
	root: { submit: (input: { type: "input"; content: string; requestId: string; whenBusy: "steer"; whenIdle: "reject" }, ctx: typeof context) => Promise<{ id: number | string }> },
	log: (text: string) => void,
) {
	let names: string[];
	try {
		names = readdirSync(dir).filter((f) => f.endsWith(".md")).sort();
	} catch {
		return;
	}
	for (const file of names) {
		const name = file.slice(0, -3);
		if (!/^[A-Za-z0-9._-]+$/.test(name)) continue;
		const base = join(dir, name);
		if (existsSync(`${base}.steered`) || existsSync(`${base}.refused`)) continue;
		try {
			const content = readFileSync(join(dir, file), "utf8");
			const steer = await root.submit({ type: "input", content, requestId: `steer-${name}`, whenBusy: "steer", whenIdle: "reject" }, context);
			writeFileSync(`${base}.steered`, `submission ${steer.id} request steer-${name}\n`);
			log(`steer ${name}: submission ${steer.id}`);
		} catch (error) {
			const why = error instanceof Error ? `${error.name}: ${error.message}` : String(error);
			writeFileSync(`${base}.refused`, `${why}\n`);
			log(`steer ${name} refused: ${why}`);
		}
	}
}

async function main(argv: string[]) {
	const [room, instruction, store, requestId, piSettings] = argv;
	if (!room || !instruction || !store || !requestId || !piSettings) {
		process.stderr.write("usage: host.ts <builder-room> <instruction-file> <session.sqlite> <request-id> <pi-settings.json>\n");
		return 2;
	}
	let tools: string[];
	try {
		tools = await toolsFromPiSettings(piSettings);
	} catch (error) {
		process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
		return 2;
	}
	let model: { provider: string; modelId: string };
	let timeoutFloor: number;
	try {
		model = modelFromEnv();
		timeoutFloor = timeoutFloorFromEnv();
	} catch (error) {
		process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
		return 2;
	}
	const roomLabel = process.env.LOYLY_ROOM_LABEL?.trim() || undefined;
	const prompt = process.env.LOYLY_PI_PROMPT?.trim() || undefined;
	const steerDir = process.env.LOYLY_STEER_DIR?.trim() || undefined;
	process.stderr.write(`model: ${model.provider}/${model.modelId}\n`);
	process.stderr.write(`bash timeout floor: ${timeoutFloor}s\n`);
	process.stderr.write(`environment prompt:\n${environmentText(tools, roomLabel, timeoutFloor)}\n`);
	// pi's own model runtime over pi's own auth store (~/.pi/agent/auth.json, or PI_CODING_AGENT_DIR):
	// the existing openai-codex login, read the way pi reads it. Nothing here logs in or writes a credential;
	// pi's store itself persists a rotated OAuth token when the access token needs a refresh, as pi does.
	const { ModelRuntime } = await import("@earendil-works/pi-coding-agent");
	const models = await ModelRuntime.create({ refreshOnCreate: false });
	if (!models.getModel(model.provider, model.modelId)) {
		process.stderr.write(`model not found: ${model.provider}/${model.modelId}\n`);
		return 2;
	}
	if (!(await models.listCredentials()).some((c) => c.providerId === model.provider)) {
		process.stderr.write(`no ${model.provider} login in pi's auth store; log in with pi first (this host never logs in)\n`);
		return 2;
	}
	try {
		const { settled, answer } = await runBuilder({ room, instruction, store, requestId, models, model, tools, prompt, roomLabel, steerDir, timeoutFloor });
		if (answer) process.stdout.write(`${answer}\n`);
		if (settled.status === "done") {
			process.stderr.write("status=done\n");
			return 0;
		}
		const reason = "reason" in settled ? settled.reason : "unknown";
		process.stderr.write(`status=unanswered reason=${reason}${"detail" in settled && settled.detail !== undefined ? ` detail=${JSON.stringify(settled.detail)}` : ""}\n`);
		return 1;
	} catch (error) {
		process.stderr.write(`status=error ${error instanceof Error ? error.message : String(error)}\n`);
		return 1;
	} finally {
		await models.settleCredentialWrites();
	}
}

// Exit explicitly: pi's model runtime keeps handles open (HTTP keep-alive) after the store is closed.
if (import.meta.main) process.exit(await main(process.argv.slice(2)));
