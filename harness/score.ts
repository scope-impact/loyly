#!/usr/bin/env node
// score: file one cycle's verdicts against that cycle's checklist.
//
// Who produced the verdicts is not this tool's business; what a measurement is allowed to
// look like is. Two things it will not do, both learned from a grading run whose every
// batch failed on a network outage and then committed "scored" over an empty record: it
// will not write a record from zero verdicts, and it will not drop an item the grader never
// reached. A measurement nobody filed can still be taken again; a measurement that was
// filed wrong cannot be untaken.

import path from "node:path";

import { blank, detail, get, isFile, isMap, type JMap, type JValue, keyOf, main, out, readJson, refuse, root, seq, slurp, spitUtf8, str, typeName, write } from "./hjson.ts";

const ROOT = root(import.meta.url);

// The command the missing-checklist refusal tells an operator to run. It must name the
// generator as it is actually invoked here, not the one being replaced (@harness-040).
const GEN_CMD = "node harness/gen_checklist.ts";

const UNREACHED_EVIDENCE = "not scored: the grader never reached this item";
const NOTHING_SCORED =
	"score: 0 items scored. A run that graded nothing tells you the grading did not happen, not that every item failed. Nothing recorded.";

main(() => {
	const args = process.argv.slice(2);
	const at = args.indexOf("--verdicts");
	const verdictsPath = at >= 0 ? args[at + 1] : undefined;
	const cycle = args.find((a) => a !== "--verdicts" && a !== verdictsPath);
	if (cycle === undefined) refuse("score: usage: score <cycle> --verdicts <file>");
	if (verdictsPath === undefined) refuse("score: --verdicts <file> is required");

	const checklistRel = `harness/cycles/${cycle}/checklist.json`;
	const checklistFile = path.join(ROOT, checklistRel);
	const scoresRel = `harness/cycles/${cycle}/scores.json`;
	if (!isFile(checklistFile)) refuse(`score: no ${checklistRel}: generate it first:\n  ${GEN_CMD} --out ${checklistRel}`);

	const items = seq(get(readJson(slurp(checklistFile)), "items"));
	if (!isFile(verdictsPath)) {
		refuse(`score: no verdicts at ${verdictsPath}. A measurement that is not there is not a measurement: nothing was written.`);
	}
	const raw = slurp(verdictsPath);
	// A blank file is the shape the incident actually took, and it dies the same clean death
	// as an empty list rather than on the parser (@harness-033).
	if (blank(raw)) refuse(NOTHING_SCORED);
	let verdicts: JValue;
	try {
		verdicts = readJson(raw);
	} catch (e) {
		// The parenthesised detail is the only text in this tool that is not fixed;
		// everything around it is (@harness-034).
		refuse(`score: ${verdictsPath} is not valid JSON (${detail(e)}). A measurement that cannot be read is not a measurement: nothing was written.`);
	}
	if (!Array.isArray(verdicts)) refuse(`score: ${verdictsPath} should hold a list of verdicts, found ${typeName(verdicts)}. Nothing was written.`);
	if (verdicts.length === 0) refuse(NOTHING_SCORED);

	// The two checks run in this order, so a verdicts file with both faults reports the
	// unknown ids and stops there (@harness-039).
	const known = new Set(items.map((i) => keyOf(get(i, "id"))));
	const unknown = verdicts.map((v) => get(v, "id")).filter((id) => !known.has(keyOf(id)));
	if (unknown.length) {
		refuse(`score: verdicts for items not on the checklist (the spec is the only author of the item list): ${unknown.map(str).join(", ")}`);
	}
	const bad = verdicts.filter((v) => !["pass", "fail"].includes(get(v, "verdict") as string)).map((v) => get(v, "id"));
	if (bad.length) refuse(`score: verdict must be 'pass' or 'fail' for: ${bad.map(str).join(", ")}`);

	// The verdicts file is a lookup table by id and its order is discarded; duplicates keep
	// the last, silently (@harness-031).
	const byId = new Map<string, JValue>();
	for (const v of verdicts) byId.set(keyOf(get(v, "id")), v);
	const scored: JMap[] = items.map((item) => {
		const v = byId.get(keyOf(get(item, "id")));
		const entry: JMap = new Map(isMap(item) ? item : []);
		entry.set("verdict", v !== undefined ? get(v, "verdict") : "fail");
		entry.set("evidence", v !== undefined ? get(v, "evidence", "") : UNREACHED_EVIDENCE);
		return entry;
	});
	const passes = scored.filter((s) => s.get("verdict") === "pass").length;
	const unreached = items.length - items.filter((i) => byId.has(keyOf(get(i, "id")))).length;
	spitUtf8(path.join(ROOT, scoresRel), `${write(new Map([["items", scored]]))}\n`);
	out(
		`scored cycle ${cycle}: ${passes}/${items.length} pass` +
			(unreached > 0 ? ` (${unreached} unreached, recorded as fail)` : "") +
			`\nfiled: ${scoresRel}\n`,
	);
});
