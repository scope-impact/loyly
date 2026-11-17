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

import * as A from "effect/Array";
import * as Effect from "effect/Effect";
import { pipe } from "effect/Function";
import * as Option from "effect/Option";

import { arrayMap, blank, get, isFile, isMap, type JMap, type JValue, keyOf, out, readJson, refuse, run, slurp, spit, str, typeName, write } from "./hjson.ts";

// The command the missing-checklist refusal tells an operator to run. It must name the
// generator as it is actually invoked here, not the one being replaced (@harness-040).
const genCmd = "node harness/gen_checklist.ts";

const unreachedEvidence = "not scored: the grader never reached this item";

const nothingScored =
	"score: 0 items scored. A run that graded nothing tells you the grading did not happen, not that every item failed. Nothing recorded.";

/** `(second (drop-while #(not= flag %) args))`. */
const flagValue = (args: string[], flag: string) =>
	pipe(
		args,
		A.dropWhile((a) => a !== flag),
		A.get(1),
	);

/** A checklist: an "items" list of objects, each with a string "id". An empty list is one (@harness-073). */
const checklistItems = (doc: JValue): JMap[] | undefined => {
	const items = get(doc, "items");
	return Array.isArray(items) && items.every((i) => isMap(i) && typeof i.get("id") === "string") ? (items as JMap[]) : undefined;
};

/** The checklist item with the verdict filed against it, or the fail an unreached item is. */
const scoreItem =
	(byId: Map<string, JValue>) =>
	(item: JValue): JMap => {
		const v = byId.get(keyOf(get(item, "id")));
		// The item's own keys in checklist order, then the verdict and the evidence, always
		// last, whatever the checklist carried under those names (@harness-086).
		return new Map([
			...[...(isMap(item) ? item : [])].filter(([k]) => k !== "verdict" && k !== "evidence"),
			["verdict", v === undefined ? "fail" : get(v, "verdict")],
			["evidence", v === undefined ? unreachedEvidence : get(v, "evidence", "")],
		]);
	};

run(import.meta.url, (root) =>
	Effect.gen(function* () {
		const args = process.argv.slice(2);
		const verdictsPath = Option.getOrUndefined(flagValue(args, "--verdicts"));
		const cycle = args.find((a) => a !== "--verdicts" && a !== verdictsPath);
		if (cycle === undefined) return yield* refuse("score: usage: score <cycle> --verdicts <file>");
		if (verdictsPath === undefined) return yield* refuse("score: --verdicts <file> is required");

		const checklistRel = `harness/cycles/${cycle}/checklist.json`;
		const scoresRel = `harness/cycles/${cycle}/scores.json`;
		const checklistFile = path.join(root, checklistRel);
		if (!(yield* isFile(checklistFile))) {
			return yield* refuse(`score: no ${checklistRel}: generate it first:\n  ${genCmd} --out ${checklistRel}`);
		}
		const notAChecklist = refuse(
			`score: ${checklistRel} is not a checklist: it should hold an "items" list of objects, each with a string "id". Regenerate it:\n  ${genCmd} --out ${checklistRel}`,
		);
		const checklist = yield* readJson(yield* slurp(checklistFile)).pipe(Effect.catchTag("JsonError", () => notAChecklist));
		const items = checklistItems(checklist);
		if (items === undefined) return yield* notAChecklist;

		if (!(yield* isFile(verdictsPath))) {
			return yield* refuse(`score: no verdicts at ${verdictsPath}. A measurement that is not there is not a measurement: nothing was written.`);
		}
		const raw = yield* slurp(verdictsPath);
		// A blank file is the shape the incident actually took, and it dies the same clean
		// death as an empty list rather than on the parser (@harness-033).
		if (blank(raw)) return yield* refuse(nothingScored);
		const verdicts = yield* readJson(raw).pipe(
			// The parenthesised detail is the only text in this tool that is not fixed;
			// everything around it is (@harness-034).
			Effect.catchTag("JsonError", (e) =>
				refuse(`score: ${verdictsPath} is not valid JSON (${e.detail}). A measurement that cannot be read is not a measurement: nothing was written.`),
			),
		);
		if (!Array.isArray(verdicts)) return yield* refuse(`score: ${verdictsPath} should hold a list of verdicts, found ${typeName(verdicts)}. Nothing was written.`);
		if (A.isReadonlyArrayEmpty(verdicts)) return yield* refuse(nothingScored);
		// A verdict is an object; the first entry that is not one is named (@harness-076).
		const stranger = verdicts.find((v) => !isMap(v));
		if (stranger !== undefined) {
			return yield* refuse(`score: ${verdictsPath} should hold a list of verdicts, found a list holding ${typeName(stranger)}. Nothing was written.`);
		}

		// The two checks run in this order, so a verdicts file with both faults reports the
		// unknown ids and stops there (@harness-039).
		const known = new Set(items.map((i) => keyOf(get(i, "id"))));
		const unknown = pipe(
			verdicts,
			A.map((v) => get(v, "id")),
			A.filter((id) => !known.has(keyOf(id))),
		);
		if (A.isReadonlyArrayNonEmpty(unknown)) {
			return yield* refuse(`score: verdicts for items not on the checklist (the spec is the only author of the item list): ${unknown.map(str).join(", ")}`);
		}
		const bad = pipe(
			verdicts,
			A.filter((v) => get(v, "verdict") !== "pass" && get(v, "verdict") !== "fail"),
			A.map((v) => get(v, "id")),
		);
		if (A.isReadonlyArrayNonEmpty(bad)) return yield* refuse(`score: verdict must be 'pass' or 'fail' for: ${bad.map(str).join(", ")}`);

		// The verdicts file is a lookup table by id and its order is discarded; duplicates
		// keep the last, silently (@harness-031).
		const byId = new Map(verdicts.map((v) => [keyOf(get(v, "id")), v] as const));
		const scored = A.map(items, scoreItem(byId));
		const passes = A.countBy(scored, (s) => s.get("verdict") === "pass");
		const unreached = items.length - A.countBy(items, (i) => byId.has(keyOf(get(i, "id"))));
		yield* spit(path.join(root, scoresRel), `${write(arrayMap("items", scored))}\n`);
		yield* out(
			`scored cycle ${cycle}: ${passes}/${items.length} pass` +
				(unreached > 0 ? ` (${unreached} unreached, recorded as fail)` : "") +
				`\nfiled: ${scoresRel}\n`,
		);
	}),
);
