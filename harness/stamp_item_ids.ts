#!/usr/bin/env node
// stamp_item_ids: give every scenario under docs/plan/ a stable id, carried in the spec
// file itself.
//
// The id lives in the spec because anything computed from the scenario's wording loses the
// cycle to cycle join the moment a rule is reworded (harness/spec.feature, the Rule above
// @harness-008). Two consequences follow and both are load bearing: the stamper is
// idempotent, and it never renumbers.

import path from "node:path";

import * as A from "effect/Array";
import * as Effect from "effect/Effect";
import { pipe } from "effect/Function";

import { out, planDir, ralign, re, rel, run, RX, slurp, specFiles, spit, trim } from "./hjson.ts";

// A scenario heading is optional whitespace then "Scenario:" or "Scenario Outline:", and
// nothing else. "Scenario Template:" is not one (@harness-003).
const scenarioLine = re(`^${RX.ws}*Scenario(?: Outline)?:`);
const itemTag = re("@item-\\d+-(\\d+)", "g");
const leadingWs = re(`^${RX.ws}*`);

/** The slice number: the directory name up to the first "-", verbatim (@harness-001). */
const sliceOf = (f: string) => path.basename(path.dirname(f)).split("-")[0];

/**
 * One past the highest number in ANY @item-<digits>-<digits> anywhere in the file, on any
 * line. Only the second group is compared, so a stray tag from another slice still pushes
 * the counter (@harness-004). A file with no tags starts at 1.
 */
const nextNumber = (lines: string[]): bigint =>
	pipe(
		lines,
		A.flatMap((line) => [...line.matchAll(itemTag)]),
		A.reduce(0n, (max, m) => (BigInt(m[1]) > max ? BigInt(m[1]) : max)),
	) + 1n;

/**
 * Walk upward from line `i` over the CONTIGUOUS run of lines whose stripped text starts
 * with "@", and ask whether any of them carries an item tag. A blank line, a comment or any
 * other text ends the run (@harness-005).
 */
const isStamped = (lines: string[], i: number): boolean =>
	pipe(
		lines.slice(0, i).reverse(),
		A.takeWhile((line) => trim(line).startsWith("@")),
		A.some((line) => re("@item-\\d+-\\d+").test(line)),
	);

/** The file's new text and how many tags it gained. */
const stampText = (text: string, slice: string): [string, number] => {
	const lines = text.split("\n");
	const [, added, stamped] = A.reduce(lines, [nextNumber(lines), 0, [] as string[]] as const, ([n, added, acc], line, i) =>
		scenarioLine.test(line) && !isStamped(lines, i)
			? ([n + 1n, added + 1, [...acc, `${leadingWs.exec(line)![0]}@item-${slice}-${String(n).padStart(3, "0")}`, line]] as const)
			: ([n, added, [...acc, line]] as const),
	);
	return [stamped.join("\n"), added];
};

run(import.meta.url, (root) =>
	Effect.gen(function* () {
		const files = yield* specFiles(root);
		if (A.isReadonlyArrayEmpty(files)) {
			// Exit 0, not 1: an absent spec is a legitimate state early in a project, and the
			// refusal for it lives at checklist time instead (@harness-011).
			return yield* out(
				`stamp: no NN-slug/spec.feature under ${planDir(root)}: nothing to stamp yet.\n` +
					"       (the spec is written first; stamping is idempotent, run it again then)\n",
			);
		}
		const total = yield* Effect.reduce(files, () => 0, (total, f) =>
			Effect.gen(function* () {
				const [text, added] = stampText(yield* slurp(f), sliceOf(f));
				if (added > 0) {
					yield* spit(f, text);
					yield* out(`stamped ${ralign(added, 3)}  ${rel(root, f)}\n`);
				}
				return total + added;
			}),
		);
		yield* out(`total stamped: ${total}\n`);
	}),
);
