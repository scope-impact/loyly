// The grader: the measurement the builder never sees.
//
// The checklist is derived from the cut's spec by the trusted tools in this checkout, never
// by the room's copies. A separate headless Claude Code run then grades each item against
// the running app, the way a resident would use it: over HTTP, through the pages and the
// forms the app serves. It is told never to read the build's source or its tests, and its
// permission rules deny reading the build at all, because a grader that reads the tests
// grades the tests. It writes one verdict per item with the evidence it saw; an item it
// never reached is not its to drop, and score.ts files it as a fail.

import { execFileSync, spawn } from "node:child_process";
import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import path from "node:path";

const q = (s) => `'${String(s).replace(/'/g, `'\\''`)}'`;

export function graderPrompt({ checklist, start, ports, impl }) {
	return [
		"You are grading one cycle of a software build. You are the measurement: be careful, be literal, and be fair.",
		"",
		"Read ./checklist.json (the items) and the spec files under ./spec/ (each item's scenario, by its @item tag). Grade every item.",
		"",
		"How to grade:",
		`- Start the application yourself, as many fresh instances as you need: \`${start} PORT\` serves it on http://127.0.0.1:PORT. Use ports ${ports}. Start a fresh instance whenever a scenario needs a fresh state, and stop the instances you start.`,
		"- Drive it the way a resident would: through the pages it serves and the forms on them, over HTTP. A behaviour a resident cannot reach through the interface does not count.",
		`- Never read anything under ${impl}: not the source, not its tests. A grader that reads the tests grades the tests.`,
		"- A scenario passes only if every one of its Then and And lines holds, observed by you. Anything you could not establish is a fail, with the reason.",
		"- A scenario judged by a person (one that says so) is graded as well as you can; say plainly in its evidence that a person's judgement was approximated.",
		"",
		"Write ./verdicts.json: a JSON list with one object per checklist item, in checklist order:",
		'  {"id": "<item id>", "verdict": "pass" | "fail", "evidence": "<what you did and what you saw, quoting the app>"}',
		"Nothing else needs writing. Your final answer: a one-line count, then any item you were unsure of, and why.",
		"",
		`There are ${checklist.items.length} items.`,
	].join("\n");
}

function waitForPort(port, ms) {
	const until = Date.now() + ms;
	return new Promise((resolve) => {
		const tick = () =>
			fetch(`http://127.0.0.1:${port}/`)
				.then(() => resolve(true))
				.catch(() => (Date.now() > until ? resolve(false) : setTimeout(tick, 200)));
		tick();
	});
}

export async function grade({ repo, room, cycle, cycleDir, port, model }) {
	// The checklist, from the cut's spec, by the trusted tools.
	const checklistRel = path.join("harness", "cycles", cycle, "checklist.json");
	if (!existsSync(path.join(repo, checklistRel))) execFileSync("node", ["harness/gen_checklist.ts", "--out", checklistRel], { cwd: repo, stdio: ["ignore", "ignore", "inherit"] });
	const checklist = JSON.parse(readFileSync(path.join(repo, checklistRel), "utf8"));

	const dir = path.join(cycleDir, "grader");
	mkdirSync(path.join(dir, "spec"), { recursive: true });
	cpSync(path.join(repo, checklistRel), path.join(dir, "checklist.json"));
	for (const slice of readdirSync(path.join(repo, "docs", "plan"))) {
		const f = path.join(repo, "docs", "plan", slice, "spec.feature");
		if (existsSync(f)) cpSync(f, path.join(dir, "spec", `${slice}.feature`));
	}

	const impl = path.join(room, "impl");
	const start = `bash ${q(path.join(impl, "start.sh"))}`;
	// A smoke start first: a build that cannot even boot is graded all fail without spending a grader.
	const probe = spawn("bash", [path.join(impl, "start.sh"), String(port)], { cwd: impl, stdio: "ignore", detached: true });
	const boots = await waitForPort(port, 20_000);
	try {
		process.kill(-probe.pid);
	} catch {}
	if (!boots) {
		const verdicts = checklist.items.map((i) => ({ id: i.id, verdict: "fail", evidence: "the application did not answer on its port within 20 seconds of impl/start.sh, so nothing could be graded" }));
		writeFileSync(path.join(cycleDir, "verdicts.json"), `${JSON.stringify(verdicts, null, 1)}\n`);
		return { costUsd: 0, booted: false };
	}

	const settings = {
		permissions: {
			allow: ["Read", "Glob", "Grep", "Bash", "Write(./verdicts.json)", "Write(./scratch/**)", "Edit(./verdicts.json)"],
			deny: ["WebFetch", "WebSearch", `Read(/${impl}/**)`, `Grep(/${impl}/**)`, `Glob(/${impl}/**)`],
		},
	};
	const prompt = graderPrompt({ checklist, start, ports: `${port + 1} to ${port + 40}`, impl });
	const bin = process.env.LOYLY_CLAUDE_BIN ?? "claude";
	const args = ["-p", prompt, "--output-format", "stream-json", "--verbose", "--permission-mode", "dontAsk", "--settings", JSON.stringify(settings), ...(model ? ["--model", model] : [])];
	const transcript = path.join(cycleDir, "grader-transcript.jsonl");
	const code = await new Promise((resolve) => {
		const child = spawn("bash", ["-c", `${q(bin)} ${args.map(q).join(" ")} > ${q(transcript)} 2>> ${q(path.join(cycleDir, "grader.stderr"))}`], { cwd: dir, stdio: "ignore" });
		child.on("close", resolve);
	});
	// Stop anything the grader left listening on its ports.
	for (let p = port + 1; p <= port + 40; p++) {
		try {
			execFileSync("bash", ["-c", `fuser -k ${p}/tcp`], { stdio: "ignore" });
		} catch {}
	}

	const result = readFileSync(transcript, "utf8")
		.split("\n")
		.filter(Boolean)
		.map((l) => {
			try {
				return JSON.parse(l);
			} catch {
				return null;
			}
		})
		.find((e) => e?.type === "result");
	const out = path.join(dir, "verdicts.json");
	if (!existsSync(out)) throw new Error(`the grader wrote no verdicts (exit ${code}); its transcript is ${transcript}. Run again to grade again.`);
	// Kept as the grader wrote it: score.ts is the one that refuses a bad record, with its own words.
	cpSync(out, path.join(cycleDir, "verdicts.json"));
	return { costUsd: result?.total_cost_usd ?? null, booted: true, answer: result?.result };
}
