// Claude Code as the worker of a round.
//
// The loop in loop.mjs decides what a round is (its prompt, its scope, its TDD state) and
// keeps its place in the pi-durable store. Which agent does the round's work is a separate
// question. With `worker: "claude-code"` it is headless Claude Code (`claude -p`), which
// brings its own tools and runs on whatever credentials Claude Code has, including those of
// a Claude Code session the loop is started from, so no model API key is needed.
//
// The guardrails carry over, each to its nearest Claude Code equivalent:
//   - spec-lock and scope fence: permission rules. Edit/Write are denied on everything
//     beside the round's scope (see permissionsFor for why it is deny rules and not allow
//     rules). Refusals come back in `permission_denials` and are recorded. Bash is allowed,
//     so its side effects are still reverted by the loop after the round.
//   - orient step: every round is a new Claude Code session, so nothing is remembered.
//   - ratified DONE: Claude cannot call the loop's claim_done tool, so it ends its answer
//     with a `CLAIM_DONE:` line and the loop runs the gate itself. The claimant still
//     controls nothing about the verdict.
//   - durability: the session id is committed to the store before Claude starts. A loop
//     that dies mid-round resumes that same Claude session (`--resume`) and tells it it was
//     interrupted, instead of starting the round over.

import { spawn } from "node:child_process";
import { existsSync, readdirSync } from "node:fs";
import { homedir } from "node:os";
import path from "node:path";

export const CLAIM = /^\s*CLAIM_DONE:\s*(.+)$/m;

/**
 * Claude Code permission rules for a round whose edits are limited to `scope`.
 *
 * Edit and Write are allowed in general and denied on every sibling of the scope, at every
 * level of its path: for impl/ that is every other top-level entry; for docs/plan it is
 * every top-level entry but docs/, and every entry of docs/ but plan/. A round with no scope
 * (a review) gets no Edit or Write at all.
 *
 * Until 2026-11-17 it was the other way round, Edit and Write allowed only under the scope
 * and everything else left to dontAsk's default refusal. In the first real cycle that
 * default refused edits inside impl/ from round 5 on, while the same rules had allowed them
 * in round 1, and the builder (rightly) would not route around a refusal through Bash, so
 * the cycle stalled. The cause did not reproduce outside the loop. A deny rule that
 * misfires refuses something that should be refused anyway; and either way the scope fence
 * after the round, not these rules, is what enforces the scope.
 */
export function permissionsFor(scope, root) {
	const allow = ["Read", "Glob", "Grep", "Bash"];
	const deny = ["WebFetch", "WebSearch"];
	if (scope.length === 0) return { permissions: { allow, deny } };
	allow.push("Edit", "Write");
	const keep = new Set();
	for (const dir of scope) {
		const parts = dir.split("/");
		parts.forEach((_, i) => keep.add(parts.slice(0, i + 1).join("/")));
	}
	const walk = (rel) => {
		for (const entry of readdirSync(path.join(root, rel), { withFileTypes: true })) {
			const p = rel ? `${rel}/${entry.name}` : entry.name;
			if (p === ".git" || entry.name === "node_modules") continue;
			if (!keep.has(p)) deny.push(`Edit(./${p}${entry.isDirectory() ? "/**" : ""})`, `Write(./${p}${entry.isDirectory() ? "/**" : ""})`);
			else if (entry.isDirectory() && !scope.includes(p)) walk(p);
		}
	};
	walk("");
	return { permissions: { allow, deny } };
}

/** Where Claude Code keeps a session's transcript; its absence means the session never started. */
function sessionExists(root, sessionId) {
	const dir = path.join(process.env.CLAUDE_CONFIG_DIR ?? path.join(homedir(), ".claude"), "projects", root.replace(/[^a-zA-Z0-9]/g, "-"));
	return existsSync(path.join(dir, `${sessionId}.jsonl`));
}

/**
 * Run one round in Claude Code. `resume` means the loop died while this session ran: the
 * session is continued rather than started again, when Claude Code has it.
 *
 * @returns {Promise<{ ok: boolean, answer: string, claim: string | null, denials: string[], costUsd: number | null, error?: string }>}
 */
export async function runClaudeRound(options) {
	const first = await attempt(options, options.resume && sessionExists(options.root, options.sessionId));
	// The transcript can exist without Claude Code accepting the resume (it died before
	// writing a turn, or keeps sessions elsewhere): then the round starts fresh, once.
	if (first.resumeFailed) return attempt(options, false);
	return first;
}

async function attempt({ root, sessionId, prompt, system, scope, model, bin, timeoutMs, log }, continuing) {
	const settings = JSON.stringify(permissionsFor(scope, root));
	const args = [
		"-p",
		continuing
			? "The process running this round was interrupted. Look at the working tree to see how far you got, then finish the same round. Do not start it over."
			: prompt,
		"--output-format",
		"json",
		"--permission-mode",
		"dontAsk",
		"--settings",
		settings,
		"--append-system-prompt",
		system,
		...(continuing ? ["--resume", sessionId] : ["--session-id", sessionId]),
		...(model ? ["--model", model] : []),
	];
	log?.(`claude ${continuing ? `--resume ${sessionId}` : `--session-id ${sessionId}`}`);
	const { code, stdout, stderr } = await run(bin, args, root, timeoutMs);
	let result;
	try {
		result = JSON.parse(stdout.trim().split("\n").pop());
	} catch {
		if (continuing) return { resumeFailed: true };
		return { ok: false, answer: "", claim: null, denials: [], costUsd: null, error: `claude exited ${code} without a JSON result: ${(stderr || stdout).slice(-500)}` };
	}
	const answer = typeof result.result === "string" ? result.result : "";
	const denials = (result.permission_denials ?? []).map((d) => {
		const target = d.tool_input?.file_path ?? d.tool_input?.command ?? "";
		return `${d.tool_name} ${path.isAbsolute(target) ? path.relative(root, target) : target}`.trim();
	});
	return {
		ok: code === 0 && !result.is_error,
		answer,
		claim: CLAIM.exec(answer)?.[1].trim() ?? null,
		denials,
		costUsd: typeof result.total_cost_usd === "number" ? result.total_cost_usd : null,
		error: code === 0 && !result.is_error ? undefined : `claude: ${result.subtype ?? `exit ${code}`}`,
	};
}

function run(bin, args, cwd, timeoutMs) {
	return new Promise((resolve) => {
		const child = spawn(bin, args, { cwd, stdio: ["ignore", "pipe", "pipe"] });
		let stdout = "";
		let stderr = "";
		child.stdout.on("data", (d) => (stdout += d));
		child.stderr.on("data", (d) => (stderr += d));
		const timer = setTimeout(() => child.kill("SIGTERM"), timeoutMs);
		child.on("error", (error) => {
			clearTimeout(timer);
			resolve({ code: 127, stdout, stderr: `${stderr}${error.message}` });
		});
		child.on("close", (code) => {
			clearTimeout(timer);
			resolve({ code: code ?? 1, stdout, stderr });
		});
	});
}
