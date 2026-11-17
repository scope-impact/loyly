#!/usr/bin/env node
// gen_checklist: derive the scoring checklist from the spec, one item per stamped scenario.
//
// The checklist is never hand written. It sets the scoring denominator, and a denominator
// that can drift for any reason other than the spec moving makes every past cycle
// incomparable, silently. Hence the two refusals below: a scenario nobody stamped stops the
// run rather than shrinking the list, and an empty spec stops it rather than producing a
// checklist that has nothing in it that could fail.

import path from "node:path";

import { err, isFile, type JMap, type JValue, main, out, planDir, refuse, rel, root, RX, slurp, specFiles, spitUtf8, trim, write } from "./hjson.ts";

const ROOT = root(import.meta.url);

// This tool's own name, for the one message that tells an operator to run its sibling. The
// reason sentence is fixed by the spec; the command must name a file that exists
// (@harness-023).
const STAMPER = "harness/stamp_item_ids.ts";

// A title is "at least one character, then optional trailing whitespace", matched lazily,
// so "Scenario:" followed by three spaces is an item named " " (@harness-049) while
// "Scenario:" followed by nothing is not an item at all (@harness-021).
const scenarioLine = new RegExp(`^${RX.ws}*Scenario(?: Outline)?:${RX.ws}*(${RX.dot}+?)${RX.ws}*${RX.end}`, "u");
const ruleLine = new RegExp(`^${RX.ws}*Rule:${RX.ws}*(${RX.dot}*?)${RX.ws}*${RX.end}`, "u");
const itemTag = /@item-(\d+-\d+)/gu;
const statusLine = new RegExp(`${RX.lineStart}status:${RX.ws}*(${RX.nonWs}+)`, "u");

const SKIPPED_STATUSES = new Set(["planned", "backlog"]);

/**
 * The first line of the slice's plan.md beginning with "status:", and the non-whitespace
 * run after it. Front matter works, and so does a bare line anywhere in the file
 * (@harness-027).
 */
function sliceStatus(dir: string): string | undefined {
	const plan = path.join(dir, "plan.md");
	if (!isFile(plan)) return undefined;
	return statusLine.exec(slurp(plan))?.[1];
}

/**
 * Read one spec file into [items, errors]. The rule and the pending tag are both reset at
 * the start of each file; within it, a pending tag is cleared ONLY by being consumed by a
 * Scenario line, which is a wart and is specified as such (@harness-020).
 */
function scan(f: string): [JMap[], string[]] {
	const p = rel(ROOT, f);
	let rule: string | null = null;
	let pending: string | null = null;
	const items: JMap[] = [];
	const errors: string[] = [];
	slurp(f)
		.split("\n")
		.forEach((line, index) => {
			const n = index + 1;
			if (trim(line).startsWith("@")) {
				// A tag line is recognised only when the stripped line begins with "@"; the
				// LAST item tag seen before the scenario wins (@harness-019).
				const tags = [...line.matchAll(itemTag)];
				if (tags.length) pending = tags[tags.length - 1][1];
				return;
			}
			const r = ruleLine.exec(line);
			if (r) {
				rule = r[1];
				return;
			}
			const s = scenarioLine.exec(line);
			if (s) {
				const name = s[1];
				if (pending) {
					items.push(
						new Map<string, JValue>([
							["id", pending],
							["name", name],
							["rule", rule],
							["source", `${p}:${n}`],
						]),
					);
				} else {
					errors.push(`  ${p}:${n}: UNSTAMPED: ${name}`);
				}
				pending = null;
			}
		});
	return [items, errors];
}

main(() => {
	const args = process.argv.slice(2);
	const at = args.indexOf("--out");
	const outPath = at >= 0 ? args[at + 1] : undefined;
	// The planned-slice filter, and it is loud on purpose: it could obviously be abused to
	// shrink a denominator by demoting a slice (@harness-027). The skip lines go to stderr,
	// so stdout stays the checklist alone and `gen_checklist | jq` works on a repository
	// with a planned slice (@harness-028; they went to stdout until 2026-09-03).
	const files = specFiles(ROOT).filter((f) => {
		const dir = path.dirname(f);
		const status = sliceStatus(dir);
		if (status !== undefined && SKIPPED_STATUSES.has(status)) {
			err(`gen_checklist: skipping ${path.basename(dir)} (status: ${status})\n`);
			return false;
		}
		return true;
	});
	if (files.length === 0) {
		refuse(
			`gen_checklist: no NN-slug/spec.feature under ${planDir(ROOT)}. An empty checklist lets a scoring run report a flawless cycle in which nothing was ever checked, so none is written.`,
		);
	}
	const items: JMap[] = [];
	for (const f of files) {
		const [found, errors] = scan(f);
		// The run stops as soon as one file finishes with offenders, so later files are
		// never read (@harness-024).
		if (errors.length) {
			refuse(
				`gen_checklist: some scenarios carry no item id. A checklist built now would quietly score fewer items than the spec contains, so stamp them first with ${STAMPER}:\n${errors.join("\n")}`,
			);
		}
		items.push(...found);
	}
	const text = `${write(new Map<string, JValue>([["items", items], ["counts", new Map([["total", items.length]])]]))}\n`;
	if (outPath !== undefined) spitUtf8(outPath, text);
	else out(text);
	// The counts line goes to STDERR so that stdout stays pipeable JSON, and its shape is a
	// Python dict repr, which is now contract (@harness-012).
	err(`checklist: {'total': ${items.length}}\n`);
});
