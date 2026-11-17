#!/usr/bin/env node
// stamp_item_ids: give every scenario under docs/plan/ a stable id, carried in the spec
// file itself.
//
// The id lives in the spec because anything computed from the scenario's wording loses the
// cycle to cycle join the moment a rule is reworded (harness/spec.feature, the Rule above
// @harness-008). Two consequences follow and both are load bearing: the stamper is
// idempotent, and it never renumbers.

import path from "node:path";

import { main, out, planDir, ralign, rel, root, RX, slurp, specFiles, spitUtf8, trim } from "./hjson.ts";

const ROOT = root(import.meta.url);

// A scenario heading is optional whitespace then "Scenario:" or "Scenario Outline:", and
// nothing else. "Scenario Template:" is not one (@harness-003).
const scenarioLine = new RegExp(`^${RX.ws}*Scenario(?: Outline)?:`, "u");
const itemTag = /@item-\d+-(\d+)/gu;
const anyItemTag = /@item-\d+-(\d+)/u;
const leadingWs = new RegExp(`^${RX.ws}*`, "u");

/** The slice number: the directory name up to the first "-", verbatim (@harness-001). */
const sliceOf = (f: string) => /^[^-]*/u.exec(path.basename(path.dirname(f)))![0];

/**
 * One past the highest number in ANY @item-<digits>-<digits> anywhere in the file, on any
 * line. Only the second group is compared, so a stray tag from another slice still pushes
 * the counter (@harness-004). A file with no tags starts at 1.
 */
function nextNumber(lines: string[]): bigint {
	let max = 0n;
	for (const line of lines) for (const m of line.matchAll(itemTag)) if (BigInt(m[1]) > max) max = BigInt(m[1]);
	return max + 1n;
}

/**
 * Walk upward from line `i` over the CONTIGUOUS run of lines whose stripped text starts
 * with "@", and ask whether any of them carries an item tag. A blank line, a comment or any
 * other text ends the run (@harness-005).
 */
function stamped(lines: string[], i: number): boolean {
	for (let j = i - 1; j >= 0 && trim(lines[j]).startsWith("@"); j--) if (anyItemTag.test(lines[j])) return true;
	return false;
}

/** The file's new text and how many tags it gained. */
function stampText(text: string, slice: string): [string, number] {
	const lines = text.split("\n");
	const result: string[] = [];
	let n = nextNumber(lines);
	let added = 0;
	lines.forEach((line, i) => {
		if (scenarioLine.test(line) && !stamped(lines, i)) {
			result.push(`${leadingWs.exec(line)![0]}@item-${slice}-${String(n).padStart(3, "0")}`);
			n++;
			added++;
		}
		result.push(line);
	});
	return [result.join("\n"), added];
}

main(() => {
	const files = specFiles(ROOT);
	if (files.length === 0) {
		// Exit 0, not 1: an absent spec is a legitimate state early in a project, and the
		// refusal for it lives at checklist time instead (@harness-011).
		out(
			`stamp: no NN-slug/spec.feature under ${planDir(ROOT)}: nothing to stamp yet.\n` +
				"       (the spec is written first; stamping is idempotent, run it again then)\n",
		);
		return;
	}
	let total = 0;
	for (const f of files) {
		const [newText, added] = stampText(slurp(f), sliceOf(f));
		if (added > 0) {
			spitUtf8(f, newText);
			out(`stamped ${ralign(added, 3)}  ${rel(ROOT, f)}\n`);
		}
		total += added;
	}
	out(`total stamped: ${total}\n`);
});
