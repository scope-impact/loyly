// pi's ls, find and grep as pi-durable tools that run in the call's ExecutionEnv (the Gondolin VM).
// pi-durable ships read, write, edit and bash; the old `pi -p` + Gondolin extension setup also gave
// the model ls, find and grep. ls and find are pi-coding-agent's own tools with their operations
// bound to the env, so names, parameters, descriptions and limits are pi's. grep's search in pi
// always runs a local ripgrep, so, like pi's Gondolin extension, it keeps pi's definition and
// searches through the env; its matching and output follow that extension's executeGondolinGrep.
import path from "node:path";
import type { Context } from "@earendil-works/chord/context";
import {
	createFindTool,
	createGrepTool,
	createLsTool,
	DEFAULT_MAX_BYTES,
	type FindOperations,
	formatSize,
	type GrepToolDetails,
	type GrepToolInput,
	type LsOperations,
	truncateHead,
	truncateLine,
} from "@earendil-works/pi-coding-agent";
import { defineTool, type ToolRegistration } from "@earendil-works/pi-durable";
import type { ExecutionEnv } from "@earendil-works/pi-durable/env";
import { GUEST_WORKSPACE } from "./policy.ts";

type PiResult = { content: Array<{ type: string; text?: string }>; details?: unknown };
type PiTool = {
	name: string;
	description: string;
	parameters: object;
	execute(id: string, params: never, signal?: AbortSignal): Promise<PiResult>;
};

const DEFAULT_GREP_LIMIT = 100;
const listOptions = { exclude: ["node_modules"] };

function must<T>(result: { ok: true; value: T } | { ok: false; error: { message?: string } }): T {
	if (!result.ok) throw new Error(result.error.message ?? "env error");
	return result.value;
}

function lsOps(env: ExecutionEnv, context: Context): LsOperations {
	return {
		exists: async (p) => must(await env.exists(p, context)),
		stat: async (p) => {
			const info = must(await env.fileInfo(p, context));
			return { isDirectory: () => info.kind === "directory" };
		},
		readdir: async (p) => must(await env.listDir(p, context)).map((entry) => entry.name),
	};
}

function matchesToolGlob(relativePath: string, pattern: string): boolean {
	if (pattern.includes("/")) {
		return path.posix.matchesGlob(relativePath, pattern) || path.posix.matchesGlob(relativePath, `**/${pattern}`);
	}
	return path.posix.matchesGlob(path.posix.basename(relativePath), pattern);
}

async function listFiles(env: ExecutionEnv, root: string, context: Context): Promise<string[]> {
	if (!env.listFiles) throw new Error("the environment cannot list files");
	const listing = must(await env.listFiles(root, listOptions, context));
	return listing.entries.filter((entry) => entry.kind === "file").map((entry) => entry.path);
}

function findOps(env: ExecutionEnv, context: Context): FindOperations {
	return {
		exists: lsOps(env, context).exists,
		glob: async (pattern, cwd, options) => {
			const results: string[] = [];
			for (const relative of await listFiles(env, cwd, context)) {
				if (results.length >= options.limit) break;
				if (matchesToolGlob(relative, pattern)) results.push(path.posix.join(cwd, relative));
			}
			return results;
		},
	};
}

function lineMatcher(pattern: string, literal?: boolean, ignoreCase?: boolean) {
	if (literal) {
		const needle = ignoreCase ? pattern.toLowerCase() : pattern;
		return (line: string) => (ignoreCase ? line.toLowerCase() : line).includes(needle);
	}
	const regex = new RegExp(pattern, ignoreCase ? "i" : undefined);
	return (line: string) => regex.test(line);
}

async function grep(env: ExecutionEnv, params: GrepToolInput, context: Context): Promise<PiResult> {
	const root = path.posix.resolve(GUEST_WORKSPACE, params.path ?? ".");
	const rootIsDirectory = must(await env.fileInfo(root, context)).kind === "directory";
	const files = rootIsDirectory ? await listFiles(env, root, context) : [path.posix.basename(root)];
	const base = rootIsDirectory ? root : path.posix.dirname(root);
	const matcher = lineMatcher(params.pattern, params.literal, params.ignoreCase);
	const contextLines = params.context && params.context > 0 ? params.context : 0;
	const limit = Math.max(1, params.limit ?? DEFAULT_GREP_LIMIT);
	const out: string[] = [];
	const details: GrepToolDetails = {};
	let matches = 0;
	let limitReached = false;
	let linesTruncated = false;
	for (const relative of files) {
		if (matches >= limit) break;
		if (params.glob && !matchesToolGlob(relative, params.glob)) continue;
		const text = await env.readTextFile(path.posix.join(base, relative), context);
		if (!text.ok) continue;
		const lines = text.value.replace(/\r\n/g, "\n").replace(/\r/g, "\n").split("\n");
		for (let i = 0; i < lines.length; i++) {
			if (context.abortSignal?.aborted) throw new Error("Operation aborted");
			if (!matcher(lines[i] ?? "")) continue;
			matches++;
			const start = Math.max(0, i - contextLines);
			const end = Math.min(lines.length - 1, i + contextLines);
			for (let j = start; j <= end; j++) {
				const { text: line, wasTruncated } = truncateLine((lines[j] ?? "").replace(/\r/g, ""));
				if (wasTruncated) linesTruncated = true;
				const sep = j === i ? ":" : "-";
				out.push(`${relative}${sep}${j + 1}${sep} ${line}`);
			}
			if (matches >= limit) {
				limitReached = true;
				break;
			}
		}
	}
	if (matches === 0) return { content: [{ type: "text", text: "No matches found" }] };
	const truncation = truncateHead(out.join("\n"), { maxLines: Number.MAX_SAFE_INTEGER });
	const notices: string[] = [];
	if (limitReached) {
		details.matchLimitReached = limit;
		notices.push(`${limit} matches limit reached`);
	}
	if (linesTruncated) {
		details.linesTruncated = true;
		notices.push("long lines truncated");
	}
	if (truncation.truncated) {
		details.truncation = truncation;
		notices.push(`${formatSize(DEFAULT_MAX_BYTES)} limit reached`);
	}
	const text = notices.length > 0 ? `${truncation.content}\n\n[${notices.join(". ")}]` : truncation.content;
	return { content: [{ type: "text", text }], details: Object.keys(details).length > 0 ? details : undefined };
}

/** A pi tool's definition, run per call with the call's env. */
function piTool(proto: PiTool, run: (env: ExecutionEnv, args: never, context: Context) => Promise<PiResult>): ToolRegistration {
	return defineTool({
		name: proto.name,
		description: proto.description,
		parameters: proto.parameters,
		execute: async (args, api, context) => {
			if (!api.env) throw new Error("no environment");
			const result = await run(api.env, args as never, context);
			return {
				output: result.content.map((c) => ({ type: "text" as const, text: c.text ?? "" })),
				...(result.details === undefined ? {} : { details: JSON.parse(JSON.stringify(result.details)) }),
			};
		},
	});
}

const at = (context: Context) => context.abortSignal;

export const VM_TOOLS: Record<string, ToolRegistration> = {
	ls: piTool(createLsTool(GUEST_WORKSPACE) as unknown as PiTool, (env, args, c) =>
		(createLsTool(GUEST_WORKSPACE, { operations: lsOps(env, c) }) as unknown as PiTool).execute("ls", args, at(c))),
	find: piTool(createFindTool(GUEST_WORKSPACE) as unknown as PiTool, (env, args, c) =>
		(createFindTool(GUEST_WORKSPACE, { operations: findOps(env, c) }) as unknown as PiTool).execute("find", args, at(c))),
	grep: piTool(createGrepTool(GUEST_WORKSPACE) as unknown as PiTool, (env, args, c) => grep(env, args, c)),
};
