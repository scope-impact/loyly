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

import { get, isFile, type JValue, keyOf, out, ralign, readJson, refuse, run, seq, slurp } from "./hjson.ts";

/**
 * One decimal, ties rounded to EVEN. 1/16 is 6.25 exactly and must render as 6.2: the
 * obvious formatter in most languages rounds ties away from zero and would print 6.3, which
 * is the single easiest way to make this instrument disagree with its own history
 * (@harness-046). And it is the double's exact binary value that is rounded, as the JVM's
 * new BigDecimal(double) does, never its shortest decimal: 0.15 is a hair under 0.15.
 */
const pct = (n: number, d: number): string => {
	const x = (100.0 * n) / d;
	const bits = new DataView(Float64Array.of(x).buffer).getBigUint64(0, true);
	const biased = Number((bits >> 52n) & 0x7ffn);
	const fraction = bits & ((1n << 52n) - 1n);
	// x = mantissa * 2^exp, exactly; then round(x * 10), ties to even, in integers.
	const mantissa = biased === 0 ? fraction : fraction | (1n << 52n);
	const exp = (biased === 0 ? 1 : biased) - 1075;
	const tenths =
		exp >= 0
			? mantissa * 10n * (1n << BigInt(exp))
			: ((num: bigint, den: bigint) => {
					const q = num / den;
					const twice = (num % den) * 2n;
					return twice > den || (twice === den && q % 2n === 1n) ? q + 1n : q;
				})(mantissa * 10n, 1n << BigInt(-exp));
	return `${tenths / 10n}.${tenths % 10n}`;
};

/**
 * "<n>/<d>" right aligned in seven, then " = ", then the percentage right aligned in five
 * with one decimal and a "%". A zero denominator renders as " =   n/a" and no percentage is
 * computed (@harness-045).
 */
const rate = (n: number, d: number) => ralign(`${n}/${d}`, 7) + (d === 0 ? " =   n/a" : ` = ${ralign(pct(n, d), 5)}%`);

/** id -> verdict, or none when there is no record at all. Ids are one exactly when Clojure's = says so. */
const verdictsOf = (f: string) =>
	Effect.gen(function* () {
		if (!(yield* isFile(f))) return Option.none<Map<string, JValue>>();
		const items = seq(get(yield* readJson(yield* slurp(f)), "items"));
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
		const current = Option.getOrUndefined(yield* verdictsOf(path.join(root, scoresRel)));
		if (current === undefined || current.size === 0) return yield* refuse(`scoreboard: nothing recorded at ${scoresRel}`);
		const passes = A.countBy([...current.values()], (v) => v === "pass");
		// The predecessor is the argument read as an integer minus one, formatted back to the
		// same width. A non-numeric argument has no predecessor (@harness-042).
		const prevCycle = /^\d+$/.test(cycle) ? Option.some(zeroPad(BigInt(cycle) - 1n, cycle.length)) : Option.none<string>();
		const prev = Option.isSome(prevCycle) ? yield* verdictsOf(path.join(root, `harness/cycles/${prevCycle.value}/scores.json`)) : Option.none();
		const body = Option.isSome(prev) && Option.isSome(prevCycle)
			? diffLines(cycle, prevCycle.value, prev.value, current)
			: ["  no previous cycle to diff against: baseline cycle."];
		yield* out([`cycle ${cycle}: ${passes}/${current.size} checklist items pass`, "", ...body, ""].join("\n"));
	}),
);
