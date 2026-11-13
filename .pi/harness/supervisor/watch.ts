// A compact, read-only digest of a pi-durable session (a builder's or a grader's), for the
// supervising Claude session (README.md). Run by ../bin/watch.sh:
//
//   node watch.ts <evidence-dir> [--since <cursor>] [--tools N] [--stall-minutes N] [--at <iso-time>]
//
// <evidence-dir> is a launch directory (holding launch.txt) or a directory of launch-N/ (the latest
// is taken). The store is launch.txt's store=, opened read-only with node:sqlite (SQLite's own
// locking keeps a reader safe beside the writer, pi-durable README, "Storage"); nothing is written.
// The cursor is the last entry id seen; the digest covers entries after it and prints the new one.
// --stall-minutes N blocks until no entry has been added for N minutes, or the session ended, then
// prints the digest. --at replays a recorded store as of that time (entries after it are ignored).
import { DatabaseSync } from "node:sqlite";
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join, resolve } from "node:path";

type Args = { dir: string; since: number; tools: number; stall?: number; at?: number };

function parseArgs(argv: string[]): Args {
	const [dir, ...rest] = argv;
	if (!dir) throw new Error("usage: watch.ts <evidence-dir> [--since <cursor>] [--tools N] [--stall-minutes N] [--at <iso-time>]");
	const a: Args = { dir, since: 0, tools: 8 };
	for (let i = 0; i < rest.length; i += 2) {
		const [k, v] = [rest[i], rest[i + 1]];
		if (v === undefined) throw new Error(`${k}: missing value`);
		if (k === "--since") a.since = Number(v);
		else if (k === "--tools") a.tools = Number(v);
		else if (k === "--stall-minutes") a.stall = Number(v);
		else if (k === "--at") a.at = Date.parse(v);
		else throw new Error(`unknown option ${k}`);
	}
	if (![a.since, a.tools, a.stall ?? 0, a.at ?? 0].every(Number.isFinite)) throw new Error("bad number or time");
	return a;
}

/** The launch directory: <dir> itself, or its latest launch-N. */
function launchDir(dir: string): string {
	if (existsSync(join(dir, "launch.txt"))) return resolve(dir);
	const n = readdirSync(dir)
		.map((f) => /^launch-(\d+)$/.exec(f)?.[1])
		.filter((x): x is string => !!x)
		.map(Number)
		.sort((x, y) => x - y)
		.pop();
	if (n === undefined) throw new Error(`no launch.txt or launch-N/ in ${dir}`);
	return resolve(dir, `launch-${n}`);
}

function launchInfo(dir: string): Record<string, string> {
	const info: Record<string, string> = {};
	for (const line of readFileSync(join(dir, "launch.txt"), "utf8").split("\n")) {
		const eq = line.indexOf("=");
		if (eq > 0) info[line.slice(0, eq)] = line.slice(eq + 1);
	}
	return info;
}

type Entry = { id: number; kind: string; ts: number; model: any[] };

function readEntries(store: string): { entries: Entry[]; submission?: { status: string; record: any } } {
	const db = new DatabaseSync(store, { readOnly: true });
	try {
		const entries = (db.prepare("select id, record from entries order by id").all() as { id: number; record: string }[]).map(({ id, record }) => {
			const r = JSON.parse(record);
			const model = Array.isArray(r.model) ? r.model : [];
			const ts = Math.max(0, ...model.map((m: any) => Number(m.timestamp) || 0));
			return { id, kind: String(r.kind ?? "?"), ts, model };
		});
		const sub = db.prepare("select status, record from submissions order by id limit 1").get() as { status: string; record: string } | undefined;
		return { entries, submission: sub ? { status: sub.status, record: JSON.parse(sub.record) } : undefined };
	} finally {
		db.close();
	}
}

/** A suite run: a command segment (heredoc bodies removed) that starts with a test.sh or a test alias. */
function isSuite(command: string): boolean {
	const body = command.replace(/<<-?\s*['"]?(\w+)['"]?[^\n]*\n[\s\S]*?\n\1(\n|$)/g, "\n");
	return body
		.split(/\n|;|&&|\|\||\|/)
		.map((seg) => seg.trim().replace(/^(cd\s+\S+\s*)$/, ""))
		.some((seg) => /^(\S*\/)?test\.sh(\s|$)/.test(seg) || /^(timeout\s+\S+\s+)?clojure\b.*-[MX]:test\b/.test(seg) || /^(bb|npm)\s+test\b/.test(seg));
}
const TALLY = /Ran (\d+) tests containing (\d+) assertions\.\s*(\d+) failures?, (\d+) errors?\./;

type Call = { id: number; ts: number; name: string; what: string; exit?: string; ms?: number; out: string; suite: boolean; tally?: string };

function calls(entries: Entry[]): Call[] {
	const byId = new Map<string, Call>();
	const out: Call[] = [];
	for (const e of entries) {
		for (const m of e.model) {
			if (m.role === "assistant" && Array.isArray(m.content)) {
				for (const c of m.content) {
					if (c.type !== "toolCall") continue;
					const args = c.arguments ?? {};
					const what = String(args.command ?? args.path ?? args.pattern ?? JSON.stringify(args)).replace(/\s+/g, " ");
					const call: Call = { id: e.id, ts: e.ts, name: c.name, what, out: "", suite: c.name === "bash" && isSuite(String(args.command ?? "")) };
					byId.set(c.id, call);
					out.push(call);
				}
			} else if (m.role === "toolResult") {
				const call = byId.get(m.toolCallId);
				if (!call) continue;
				const text = (Array.isArray(m.content) ? m.content : []).map((c: any) => c.text ?? "").join("\n");
				const code = /Command exited with code (\d+)/.exec(text)?.[1];
				call.exit = code ?? (m.isError ? "error" : "0");
				call.ms = m.durationMs;
				call.out = text.replace(/<harness>[\s\S]*?<\/harness>/g, "").trim();
				const t = [...call.out.matchAll(new RegExp(TALLY, "g"))].pop();
				if (t) call.tally = `${Number(t[3]) + Number(t[4]) === 0 ? "GREEN" : "RED"} ${t[1]} tests, ${t[2]} assertions, ${t[3]} failures, ${t[4]} errors`;
				if (/timed out/i.test(text) && call.exit !== "0") call.exit += " (timed out)";
			}
		}
	}
	return out;
}

const mins = (ms: number) => `${(ms / 60000).toFixed(1)} min`;
const clip = (s: string, n: number) => (s.length > n ? `${s.slice(0, n)}…` : s);
const iso = (ms: number) => new Date(ms).toISOString().replace(/\.\d+Z$/, "Z");

function digest(a: Args, dir: string): { text: string; idleMs: number; ended: boolean } {
	const info = launchInfo(dir);
	const store = info.store ?? join(dir, "session.sqlite");
	const now = a.at ?? Date.now();
	const start = Date.parse(info.start ?? "");
	const end = info.end ? Date.parse(info.end) : undefined;
	const ended = end !== undefined && end <= now;
	const { entries: all, submission } = readEntries(store);
	const entries = all.filter((e) => a.at === undefined || e.ts <= a.at);
	const fresh = entries.filter((e) => e.id > a.since);
	const last = entries.at(-1);
	const until = ended ? end! : now;
	const idleMs = last ? until - last.ts : until - start;
	const cs = calls(entries);
	const newCalls = cs.filter((c) => c.id > a.since);
	const suites = cs.filter((c) => c.suite && c.exit !== undefined);
	const kinds: Record<string, number> = {};
	for (const e of fresh) kinds[e.kind] = (kinds[e.kind] ?? 0) + 1;
	const repeats = new Map<string, number>();
	for (const c of newCalls) repeats.set(`${c.name} ${c.what}`, (repeats.get(`${c.name} ${c.what}`) ?? 0) + 1);
	const rep = [...repeats].filter(([, n]) => n >= 3).sort((x, y) => y[1] - x[1]);
	const usage = entries.flatMap((e) => e.model).reduce((s, m) => s + (Number(m.usage?.totalTokens) || 0), 0);
	const steered = entries.filter((e) => e.kind === "pi.user").length - 1;
	const pid = info.host_pid;
	let alive = "";
	if (pid && a.at === undefined) {
		try {
			process.kill(Number(pid), 0);
			alive = ` host_pid=${pid} alive`;
		} catch {
			alive = ` host_pid=${pid} gone`;
		}
	}
	const L: string[] = [];
	L.push(`session ${dir} (${info.kind ?? "builder"}, ${info.model ?? "openai-codex/gpt-6.1-sol"})${a.at ? ` replayed at ${iso(now)}` : ""}`);
	L.push(
		`state: ${ended ? `ended ${info.end} exit=${info.exit}` : "running"}; submission${a.at ? " (now)" : ""} ${submission?.status ?? "none"}${submission?.record?.reason ? ` (${submission.record.reason})` : ""};${alive} elapsed ${mins((ended ? end! : now) - start)}`,
	);
	L.push(`entries: ${entries.length} total, ${fresh.length} since cursor ${a.since} ${JSON.stringify(kinds)}; tool calls ${cs.length} (${newCalls.length} new); tokens ${usage}; later inputs (steers, grader rounds) ${Math.max(0, steered)}`);
	L.push(`last entry: ${last ? `${iso(last.ts)}, ${mins(idleMs)} ago` : "none"}`);
	const open = cs.at(-1);
	if (open && open.exit === undefined && !ended) L.push(`running now: #${open.id} ${open.name} for ${mins(until - open.ts)}: ${clip(open.what, 100)}`);
	if (suites.length) {
		const s = suites.at(-1)!;
		const word = (x: Call) =>
			x.tally ? x.tally.split(" ")[0] : x.exit?.includes("timed out") ? "TIMEOUT" : x.exit !== "0" ? `RED(exit ${x.exit})` : "exit0";
		const green = suites.filter((x) => x.tally?.startsWith("GREEN")).length;
		const red = suites.filter((x) => word(x).startsWith("RED") || word(x) === "TIMEOUT").length;
		L.push(`test runs: ${suites.length} (green by tally ${green}, red or timed out ${red}, unknown ${suites.length - green - red}); last ${Math.min(12, suites.length)}: ${suites.slice(-12).map(word).join(" ")}`);
		const lastGreen = suites.filter((x) => x.tally?.startsWith("GREEN")).at(-1);
		L.push(`last green test run: ${lastGreen ? `${iso(lastGreen.ts)}, ${mins(until - lastGreen.ts)} ago` : `never, in ${mins(until - start)}`}`);
		L.push(`latest test run: ${iso(s.ts)} exit=${s.exit} ${s.ms ? mins(s.ms) : ""} ${s.tally ?? "(no clojure.test tally)"}: ${clip(s.what, 140)}`);
		const tail = s.out.split("\n").filter((l) => /FAIL|ERROR|fail|error|Ran |Exception/.test(l)).slice(-6);
		for (const l of tail) L.push(`    | ${clip(l, 160)}`);
	} else L.push("test runs: none yet");
	if (rep.length) L.push(`repeated since cursor: ${rep.slice(0, 4).map(([k, n]) => `${n}x ${clip(k, 80)}`).join("; ")}`);
	L.push(`last ${Math.min(a.tools, newCalls.length)} tool calls since cursor:`);
	for (const c of newCalls.slice(-a.tools)) {
		const first = c.out.split("\n").filter((l) => l.trim()).slice(0, 2).map((l) => clip(l, 120)).join(" / ");
		L.push(`  #${c.id} ${iso(c.ts).slice(11)} ${c.name} exit=${c.exit ?? "running"}${c.ms ? ` ${(c.ms / 1000).toFixed(0)}s` : ""}: ${clip(c.what, 120)}`);
		if (first) L.push(`      > ${first}`);
	}
	L.push(`cursor=${last?.id ?? a.since}`);
	return { text: L.join("\n"), idleMs, ended };
}

async function main() {
	const a = parseArgs(process.argv.slice(2));
	const dir = launchDir(a.dir);
	if (a.stall !== undefined && a.at === undefined) {
		for (;;) {
			const d = digest(a, dir);
			if (d.ended || d.idleMs >= a.stall * 60000) {
				console.log(`${d.ended ? "ENDED" : `STALLED: no entry for ${mins(d.idleMs)} (>= ${a.stall} min)`}\n${d.text}`);
				return;
			}
			await new Promise((r) => setTimeout(r, 30000));
		}
	}
	console.log(digest(a, dir).text);
}

main().catch((error) => {
	process.stderr.write(`watch: ${error instanceof Error ? error.message : String(error)}\n`);
	process.exit(2);
});
