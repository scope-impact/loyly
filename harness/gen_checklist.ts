#!/usr/bin/env node
// gen_checklist: derive the scoring checklist from the spec, one item per stamped scenario.
//
// The checklist is never hand written. It sets the scoring denominator, and a denominator
// that can drift for any reason other than the spec moving makes every past cycle
// incomparable, silently. Hence the two refusals below: a scenario nobody stamped stops the
// run rather than shrinking the list, and an empty spec stops it rather than producing a
// checklist that has nothing in it that could fail.

import path from "node:path";

import * as A from "effect/Array";
import * as Effect from "effect/Effect";
import { pipe } from "effect/Function";
import * as Option from "effect/Option";

import { arrayMap, err, isFile, type JMap, out, planDir, re, refuse, rel, run, RX, slurp, specFiles, spit, trim, write } from "./hjson.ts";

// This tool's own name, for the one message that tells an operator to run its sibling. The
// reason sentence is fixed by the spec; the command must name a file that exists
// (@harness-023).
const stamper = "harness/stamp_item_ids.ts";

// A title is "at least one character, then optional trailing whitespace", matched lazily,
// so "Scenario:" followed by three spaces is an item named " " (@harness-049) while
// "Scenario:" followed by nothing is not an item at all (@harness-021).
const scenarioLine = re(`^${RX.ws}*Scenario(?: Outline)?:${RX.ws}*(${RX.dot}+?)${RX.ws}*${RX.end}`);
const ruleLine = re(`^${RX.ws}*Rule:${RX.ws}*(${RX.dot}*?)${RX.ws}*${RX.end}`);
const itemTag = re("@item-(\\d+-\\d+)", "g");
const statusLine = re(`${RX.lineStart}status:${RX.ws}*(${RX.nonWs}+)`);

const skippedStatuses = new Set(["planned", "backlog"]);

/**
 * The first line of the slice's plan.md beginning with "status:", and the non-whitespace
 * run after it. Front matter works, and so does a bare line anywhere in the file
 * (@harness-027).
 */
const sliceStatus = (dir: string) =>
	Effect.gen(function* () {
		const plan = path.join(dir, "plan.md");
		if (!(yield* isFile(plan))) return Option.none<string>();
		return Option.fromNullishOr(statusLine.exec(yield* slurp(plan))?.[1]);
	});

type Scan = { rule: string | null; pending: string | null; items: JMap[]; errors: string[] };

/**
 * Read one spec file into its items and its errors. The rule and the pending tag are both
 * reset at the start of each file; within it, a pending tag is cleared ONLY by being
 * consumed by a Scenario line, which is a wart and is specified as such (@harness-020).
 */
const scan = (path: string, text: string): Scan =>
	A.reduce(text.split("\n"), { rule: null, pending: null, items: [], errors: [] } as Scan, (state, line, i) => {
		const n = i + 1;
		// A tag line is recognised only when the stripped line begins with "@"; the LAST item
		// tag seen before the scenario wins (@harness-019).
		if (trim(line).startsWith("@")) {
			return { ...state, pending: A.last([...line.matchAll(itemTag)]).pipe(Option.map((m) => m[1]), Option.getOrElse(() => state.pending)) };
		}
		const rule = ruleLine.exec(line);
		if (rule) return { ...state, rule: rule[1] };
		const scenario = scenarioLine.exec(line);
		if (!scenario) return state;
		const name = scenario[1];
		return state.pending
			? { ...state, pending: null, items: [...state.items, arrayMap("id", state.pending, "name", name, "rule", state.rule, "source", `${path}:${n}`)] }
			: { ...state, pending: null, errors: [...state.errors, `  ${path}:${n}: UNSTAMPED: ${name}`] };
	});

run(import.meta.url, (root) =>
	Effect.gen(function* () {
		const args = process.argv.slice(2);
		const outPath = pipe(
			args,
			A.dropWhile((a) => a !== "--out"),
			A.get(1),
		);
		// The planned-slice filter, and it is loud on purpose: it could obviously be abused
		// to shrink a denominator by demoting a slice (@harness-027). The skip lines go to
		// stderr, so stdout stays the checklist alone and `gen_checklist | jq` works on a
		// repository with a planned slice (@harness-028; they went to stdout until 2026-09-03).
		const files = yield* Effect.filter(yield* specFiles(root), (f) =>
			Effect.gen(function* () {
				const dir = path.dirname(f);
				const status = yield* sliceStatus(dir);
				if (Option.isSome(status) && skippedStatuses.has(status.value)) {
					yield* err(`gen_checklist: skipping ${path.basename(dir)} (status: ${status.value})\n`);
					return false;
				}
				return true;
			}),
		);
		if (A.isReadonlyArrayEmpty(files)) {
			return yield* refuse(
				`gen_checklist: no NN-slug/spec.feature under ${planDir(root)}. An empty checklist lets a scoring run report a flawless cycle in which nothing was ever checked, so none is written.`,
			);
		}
		const items = yield* Effect.reduce(files, (): JMap[] => [], (acc, f) =>
			Effect.gen(function* () {
				const { items, errors } = scan(rel(root, f), yield* slurp(f));
				// The run stops as soon as one file finishes with offenders, so later files are
				// never read (@harness-024).
				if (A.isReadonlyArrayNonEmpty(errors)) {
					return yield* refuse(
						`gen_checklist: some scenarios carry no item id. A checklist built now would quietly score fewer items than the spec contains, so stamp them first with ${stamper}:\n${errors.join("\n")}`,
					);
				}
				return [...acc, ...items];
			}),
		);
		const text = `${write(arrayMap("items", items, "counts", arrayMap("total", items.length)))}\n`;
		yield* Option.match(outPath, { onSome: (p) => spit(p, text), onNone: () => out(text) });
		// The counts line goes to STDERR so that stdout stays pipeable JSON, and its shape is
		// a Python dict repr, which is now contract (@harness-012).
		yield* err(`checklist: {'total': ${items.length}}\n`);
	}),
);
