#!/usr/bin/env node
// scoreboard: read one cycle's record and report the rate, and the comparison with the
// cycle before it.
//
// The two numbers a reader wants to compare are the two headline rates, and when the spec
// grows between cycles those rates have different denominators, so the rate can fall with
// no regression behind it. So the comparison is restated on the shared items only, both
// directions are shown separately, and a net is never computed: a net hides a regression
// behind an unrelated repair.

import path from "node:path";

import * as A from "effect/Array";
import * as Effect from "effect/Effect";
import * as Option from "effect/Option";

import { get, isFile, isMap, type JValue, keyOf, out, ralign, readJson, refuse, run, slurp } from "./hjson.ts";

/**
 * One decimal, ties rounded to EVEN. 1/16 is 6.25 exactly and must render as 6.2: the
 * obvious formatter in most languages rounds ties away from zero and would print 6.3, which
 * is the single easiest way to make this instrument disagree with its own history
 * (@harness-046). The fraction is rounded exactly, in integers, never through a double:
 * 1/2000 is 0.05% exactly and renders 0.0, where the double nearest 0.05 is a hair above
 * it and would render 0.1 (@harness-080).
 */
const pct = (n: number, d: number): string => {
	const num = 1000n * BigInt(n);
	const den = BigInt(d);
	const q = num / den;
	const twice = (num % den) * 2n;
	const tenths = twice > den || (twice === den && q % 2n === 1n) ? q + 1n : q;
	return `${tenths / 10n}.${tenths % 10n}`;
};

/**
 * "<n>/<d>" right aligned in seven, then " = ", then the percentage right aligned in five
 * with one decimal and a "%". A zero denominator renders as " =   n/a" and no percentage is
 * computed (@harness-045).
 */
const rate = (n: number, d: number) => ralign(`${n}/${d}`, 7) + (d === 0 ? " =   n/a" : ` = ${ralign(pct(n, d), 5)}%`);

/**
 * id -> verdict, or none when there is no record at all. An id named twice counts once, its
 * last entry winning (@harness-078).
 */
const verdictsOf = (root: string, scoresRel: string) =>
	Effect.gen(function* () {
		const f = path.join(root, scoresRel);
		if (!(yield* isFile(f))) return Option.none<Map<string, JValue>>();
		// A record is an "items" list of objects, each with a string id, as score writes it;
		// anything else is not read as far as it can be, it is refused (@harness-085).
		const notARecord = refuse(
			`scoreboard: ${scoresRel} is not a record: it should hold an "items" list of objects, each with a string "id".`,
		);
		const items = get(yield* readJson(yield* slurp(f)).pipe(Effect.catchTag("JsonError", () => notARecord)), "items");
		if (!Array.isArray(items) || !items.every((i) => isMap(i) && typeof i.get("id") === "string")) return yield* notARecord;
		return Option.some(new Map(items.map((i) => [keyOf(get(i, "id")), get(i, "verdict")] as const)));
	});

/** `(format "%0<w>d" n)`: the sign, then zeros to the width. */
const zeroPad = (n: bigint, width: number) =>
	n < 0n ? `-${(-n).toString().padStart(width - 1, "0")}` : n.toString().padStart(width, "0");

/** The comparison with the previous cycle, on the items both carried. */
const diffLines = (cycle: string, prevCycle: string, prev: Map<string, JValue>, current: Map<string, JValue>): string[] => {
	const keys = [...current.keys()];
	const shared = keys.filter((k) => prev.has(k));
	const added = keys.filter((k) => !prev.has(k));
	const dropped = [...prev.keys()].filter((k) => !current.has(k));
	const count = (pred: (k: string) => boolean) => A.countBy(shared, pred);
	return [
		`  against cycle ${prevCycle}, counting only the ${shared.length} items both cycles carried:`,
		`    cycle ${prevCycle}  ${rate(
			count((k) => prev.get(k) === "pass"),
			shared.length,
		)}`,
		`    cycle ${cycle}  ${rate(
			count((k) => current.get(k) === "pass"),
			shared.length,
		)}   <- these two are the fair comparison`,
		// Three literal spaces, then the count right aligned in three, so that the counts land
		// in the same column as the rates above them.
		`    now passing   ${ralign(
			count((k) => prev.get(k) === "fail" && current.get(k) === "pass"),
			3,
		)}   <- repaired since then`,
		`    now failing   ${ralign(
			count((k) => prev.get(k) === "pass" && current.get(k) === "fail"),
			3,
		)}   <- broken since then`,
		// Printed only when the item set moved, in either direction (@harness-047). The
		// wrapping of the sentence is contract.
		...(added.length || dropped.length
			? [
					"",
					`  THE ITEM SET MOVED: ${prev.size} items -> ${current.size} (${added.length} added, ${dropped.length} dropped), so the two headline`,
					"  rates count different things and must not be read against each other.",
					"  The added items cover work last cycle's spec never asked for.",
				]
			: []),
	];
};

run(import.meta.url, (root) =>
	Effect.gen(function* () {
		const cycle = process.argv[2];
		// The message on this path is deliberately NOT contract (@harness-044).
		if (cycle === undefined) return yield* refuse("scoreboard: usage: scoreboard <cycle>");
		const scoresRel = `harness/cycles/${cycle}/scores.json`;
		// A record of nothing and no record are the same amount of measurement (@harness-043).
		const current = Option.getOrUndefined(yield* verdictsOf(root, scoresRel));
		if (current === undefined || current.size === 0) return yield* refuse(`scoreboard: nothing recorded at ${scoresRel}`);
		const passes = A.countBy([...current.values()], (v) => v === "pass");
		// The predecessor is the argument read as an integer minus one, formatted back to the
		// same width. A non-numeric argument has no predecessor (@harness-042).
		const prevCycle = /^\d+$/.test(cycle) ? Option.some(zeroPad(BigInt(cycle) - 1n, cycle.length)) : Option.none<string>();
		// A predecessor that recorded nothing is no predecessor: @harness-043, applied to the
		// cycle before (@harness-079).
		const prev = Option.isSome(prevCycle)
			? Option.filter(yield* verdictsOf(root, `harness/cycles/${prevCycle.value}/scores.json`), (m) => m.size > 0)
			: Option.none();
		const body = Option.isSome(prev) && Option.isSome(prevCycle)
			? diffLines(cycle, prevCycle.value, prev.value, current)
			: ["  no previous cycle to diff against: baseline cycle."];
		yield* out([`cycle ${cycle}: ${passes}/${current.size} checklist items pass`, "", ...body, ""].join("\n"));
	}),
);
