// triage-steers.mjs <state-root> <out-dir> <NN-cMM>...: groups a set of cycles' steers, stops and
// gate failures and classifies each group by deterministic rules (../bin/triage-steers.sh).
//
// Reads, per cycle NN-cMM, nothing else:
//   builders/<cycle>[-aN]/steers.jsonl, stops.jsonl and launch-*/steers.jsonl, stops.jsonl
//   logs/gate-c<MM>[-aN]-slice<NN>.log: each gate attempt's FAIL/ERROR/FATAL lines
// Never reads grading verdicts, grading evidence or scores.
//
// Groups: a gate failure by its test (`FAIL in (<test>)`) or its store-check rule (`FAIL  rule "<r>"`);
// a steer or stop by the @item it names, else by the first harness term it uses, else on its own.
// Classes:
//   harness/tool  a FATAL other than "suite red"/"(above)", or a steer/stop naming the VM, image,
//                 world, egress or gate environment
//   spec gap      a gate failure in every attempt of a cycle with two or more attempts, or in two or
//                 more cycles; a steer/stop naming an @item, scenario or seam
//   one-off       everything else
// Writes <out-dir>/triage.md: candidate spec inbox items with their evidence, for a person to take
// into a spec round. It never writes an instruction or a spec file.
import fs from "node:fs";
import path from "node:path";

const [state, out, ...cycles] = process.argv.slice(2);
if (!state || !out || cycles.length === 0) {
	console.error("usage: triage-steers.mjs <state-root> <out-dir> <NN-cMM>...");
	process.exit(2);
}

const HARNESS = /\b(VM|Gondolin|image|Dockerfile|apk|egress|allow ?list|network|DNS|world|disk|memory|OOM|out of memory|liblmdb|LMDB|native lib|JAVA_TOOL_OPTIONS|permission denied|read-only file system|gate room|gate environment|boot(?:ed|ing)? (?:the )?VM)\b/i;
const SPEC = /@item-\d\d-\d\d\d|\bscenario\b|\bseam\b|\bspec\b|\bevent model\b/i;
const GENERIC_FATAL = /^FATAL: (suite red\.|.*\(above\)\.?)$/;
const read = (f) => (fs.existsSync(f) ? fs.readFileSync(f, "utf8") : null);
const jsonl = (f) => (read(f) ?? "").split("\n").filter((l) => l.trim()).map((l) => JSON.parse(l));
const itemOf = (s) => {
	const m = s.match(/item-(\d\d)-(\d\d\d)/);
	return m ? `@item-${m[1]}-${m[2]}` : null;
};

const groups = new Map(); // key -> {key, kind, cls?, evidence: [{cycle, attempt, source, text}]}
const add = (key, kind, ev) => {
	if (!groups.has(key)) groups.set(key, { key, kind, evidence: [] });
	groups.get(key).evidence.push(ev);
};
const attemptsPerCycle = {};
const inputs = [];

for (const cycle of cycles) {
	const m = cycle.match(/^(\d\d)-c(\d+)$/);
	if (!m) {
		console.error(`cycle is NN-cMM, not ${cycle}`);
		process.exit(2);
	}
	const [, nn, mm] = m;
	// steers and stops
	const bdir = path.join(state, "builders");
	const bdirs = (fs.existsSync(bdir) ? fs.readdirSync(bdir) : [])
		.filter((d) => new RegExp(`^${cycle}(-a\\d+)?$`).test(d))
		.map((d) => path.join(bdir, d));
	const evdirs = bdirs.flatMap((b) => [b, ...fs.readdirSync(b).filter((d) => /^launch-\d+$/.test(d)).map((d) => path.join(b, d))]);
	let nSteers = 0, nStops = 0;
	for (const e of evdirs) {
		for (const [file, field, source] of [["steers.jsonl", "message", "steer"], ["stops.jsonl", "reason", "stop"]]) {
			for (const r of jsonl(path.join(e, file))) {
				const text = String(r[field] ?? "").trim();
				const launch = String(r.launch ?? e).match(/launch-\d+/)?.[0] ?? "?";
				source === "steer" ? nSteers++ : nStops++;
				const item = itemOf(text);
				const term = text.match(HARNESS)?.[0];
				const key = item ? `${item}` : term ? `harness: ${term.toLowerCase()}` : `${source}: ${text.slice(0, 60)}`;
				add(key, item ? "item" : term ? "harness" : "steer", { cycle, attempt: launch, source: `${source} ${r.time ?? ""}`.trim(), text });
			}
		}
	}
	// gate attempts
	const ldir = path.join(state, "logs");
	const logs = (fs.existsSync(ldir) ? fs.readdirSync(ldir) : [])
		.filter((f) => new RegExp(`^gate-c0*${Number(mm)}(-a\\d+)?-slice${nn}\\.log$`).test(f))
		.sort((a, b) => Number(a.match(/-a(\d+)-/)?.[1] ?? 1) - Number(b.match(/-a(\d+)-/)?.[1] ?? 1));
	attemptsPerCycle[cycle] = logs.length;
	for (const f of logs) {
		const attempt = `gate attempt ${f.match(/-a(\d+)-/)?.[1] ?? 1}`;
		const lines = read(path.join(ldir, f)).split("\n");
		lines.forEach((l, i) => {
			const t = l.trim();
			let key, kind;
			const test = t.match(/^(?:FAIL|ERROR) in \(([^)]+)\)/);
			const rule = t.match(/^FAIL\s+rule "([^"]+)"/);
			if (test) [key, kind] = [itemOf(test[1]) ?? `test ${test[1]}`, "gate"];
			else if (rule) [key, kind] = [`store rule "${rule[1]}"`, "gate"];
			else if (/^FATAL\b/.test(t) && !GENERIC_FATAL.test(t)) [key, kind] = [`gate: ${t.slice(0, 80)}`, "fatal"];
			else return;
			const detail = test ? lines.slice(i, i + 3).map((x) => x.trim()).filter(Boolean).join("\n") : t;
			add(key, kind, { cycle, attempt, source: `${f}:${i + 1}`, text: detail });
		});
	}
	inputs.push(`${cycle}: ${bdirs.length ? evdirs.length - bdirs.length : 0} builder launch(es), ${nSteers} steer(s), ${nStops} stop(s), ${logs.length} gate attempt(s)`);
}

for (const g of groups.values()) {
	const cyclesHit = new Set(g.evidence.map((e) => e.cycle));
	const gateAttempts = (c) => new Set(g.evidence.filter((e) => e.cycle === c && e.attempt.startsWith("gate")).map((e) => e.attempt)).size;
	const everyAttempt = [...cyclesHit].some((c) => attemptsPerCycle[c] >= 2 && gateAttempts(c) === attemptsPerCycle[c]);
	const recurs = cyclesHit.size >= 2 || new Set(g.evidence.map((e) => `${e.cycle} ${e.attempt}`)).size >= 2;
	const texts = g.evidence.map((e) => e.text).join("\n");
	g.cyclesHit = [...cyclesHit];
	g.recurs = recurs;
	if (g.kind === "fatal" || HARNESS.test(texts.replace(/@item-\S+/g, ""))) g.cls = "harness/tool issue";
	else if (g.kind === "gate" ? everyAttempt || cyclesHit.size >= 2 : SPEC.test(texts)) g.cls = "spec gap";
	else g.cls = "one-off";
	g.why =
		g.cls === "harness/tool issue" ? "names the VM, image, world, egress or gate environment, not the product"
		: g.kind === "gate" && g.cls === "spec gap"
			? everyAttempt
				? "fails in every gate attempt of a cycle: each fresh builder, repairing it, kept the reading the suite rejects"
				: "fails in two or more cycles"
		: g.cls === "spec gap" ? "names a scenario, item or seam"
		: recurs ? "recurs, but names nothing in the spec or the environment" : "was seen once";
}

const order = { "spec gap": 0, "harness/tool issue": 1, "one-off": 2 };
const sorted = [...groups.values()].sort((a, b) => order[a.cls] - order[b.cls] || b.evidence.length - a.evidence.length);
const fence = (s) => "```\n" + s + "\n```";
let md = `# Steer triage: ${cycles.join(", ")}\n\n`;
md += "Candidate spec inbox items for a person to take into a spec round. **Nothing here is applied**: the builder\n";
md += "instruction is frozen and generic, and this triage never writes an instruction or a spec file. Inputs are the\n";
md += "supervisor's steers and stops and the gate attempts' failing lines only; no grading verdict, grading evidence\n";
md += "or score was read. Classes are deterministic rules (`scripts/triage-steers.mjs`); a group that recurs across\n";
md += "attempts or cycles is stronger evidence.\n\n## Inputs\n\n" + inputs.map((s) => `- ${s}`).join("\n") + "\n";
if (sorted.length === 0) md += "\nNo steer, stop or gate failure: nothing to triage.\n";
for (const cls of Object.keys(order)) {
	const gs = sorted.filter((g) => g.cls === cls);
	if (!gs.length) continue;
	md += `\n## ${cls === "spec gap" ? "Spec gap candidates" : cls === "harness/tool issue" ? "Harness/tool issues" : "One-offs"}\n`;
	for (const g of gs) {
		const attempts = new Set(g.evidence.map((e) => `${e.cycle} ${e.attempt}`));
		md += `\n### ${g.key}\n\n- Class: **${cls}**, because it ${g.why}.\n`;
		md += `- Strength: ${g.evidence.length} line(s) over ${attempts.size} attempt(s) in ${g.cyclesHit.length} cycle(s)${g.recurs ? " (recurs)" : ""}.\n`;
		if (cls === "spec gap")
			md += `- Inbox candidate: ${g.key.startsWith("@item") ? `${g.key}'s scenario or its seam leaves the value the suite asserts open to a reading it rejects; state that value in the scenario or the context's \`seam.md\`` : `say in the spec what \`${g.key}\` expects`}.\n`;
		if (cls === "harness/tool issue") md += "- Inbox candidate: none for the spec; a change to the harness or the builder image, for the operator.\n";
		md += "\nEvidence:\n\n";
		for (const e of g.evidence) md += `- ${e.cycle}, ${e.attempt}, \`${e.source}\`:\n\n${fence(e.text).replace(/^/gm, "  ")}\n`;
	}
}
fs.mkdirSync(out, { recursive: true });
fs.writeFileSync(path.join(out, "triage.md"), md);
console.log(path.join(out, "triage.md"));
