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

import { get, isFile, type JValue, keyOf, main, out, ralign, readJson, refuse, root, seq, slurp } from "./hjson.ts";

const ROOT = root(import.meta.url);

/**
 * One decimal, ties rounded to EVEN. 1/16 is 6.25 exactly and must render as 6.2: the
 * obvious formatter in most languages rounds ties away from zero and would print 6.3, which
 * is the single easiest way to make this instrument disagree with its own history
 * (@harness-046). And it is the double's exact binary value that is rounded, as the JVM's
 * new BigDecimal(double) does, never its shortest decimal: 0.15 is a hair under 0.15.
 */
function pct(n: number, d: number): string {
	const x = (100.0 * n) / d;
	if (!Number.isFinite(x)) return String(x);
	const view = new DataView(new ArrayBuffer(8));
	view.setFloat64(0, Math.abs(x));
	const bits = view.getBigUint64(0);
	const biased = Number((bits >> 52n) & 0x7ffn);
	const fraction = bits & ((1n << 52n) - 1n);
	// |x| = mantissa * 2^exp, exactly.
	const mantissa = biased === 0 ? fraction : fraction | (1n << 52n);
	const exp = (biased === 0 ? 1 : biased) - 1075;
	// round(|x| * 10), ties to even, in integers.
	let tenths: bigint;
	if (exp >= 0) tenths = mantissa * 10n * (1n << BigInt(exp));
	else {
		const den = 1n << BigInt(-exp);
		const num = mantissa * 10n;
		tenths = num / den;
		const twice = (num % den) * 2n;
		if (twice > den || (twice === den && tenths % 2n === 1n)) tenths += 1n;
	}
	const sign = x < 0 && tenths !== 0n ? "-" : "";
	return `${sign}${tenths / 10n}.${tenths % 10n}`;
}

/**
 * "<n>/<d>" right aligned in seven, then " = ", then the percentage right aligned in five
 * with one decimal and a "%". A zero denominator renders as " =   n/a" and no percentage is
 * computed (@harness-045).
 */
function rate(n: number, d: number): string {
	return ralign(`${n}/${d}`, 7) + (d === 0 ? " =   n/a" : ` = ${ralign(pct(n, d), 5)}%`);
}

/** id -> verdict, keyed so that two ids are one exactly when Clojure's = would say so. */
function verdictsOf(f: string): Map<string, JValue> | null {
	if (!isFile(f)) return null;
	const m = new Map<string, JValue>();
	for (const i of seq(get(readJson(slurp(f)), "items"))) m.set(keyOf(get(i, "id")), get(i, "verdict"));
	return m;
}

/** String.format("%0<w>d", n): the sign, then zeros to the width. */
function zeroPad(n: bigint, width: number): string {
	const digits = (n < 0n ? -n : n).toString();
	return n < 0n ? `-${digits.padStart(width - 1, "0")}` : digits.padStart(width, "0");
}

main(() => {
	const cycle = process.argv[2];
	// The message on this path is deliberately NOT contract (@harness-044).
	if (cycle === undefined) refuse("scoreboard: usage: scoreboard <cycle>");
	const scoresRel = `harness/cycles/${cycle}/scores.json`;
	const current = verdictsOf(path.join(ROOT, scoresRel));
	// A record of nothing and no record are the same amount of measurement (@harness-043).
	if (!current || current.size === 0) refuse(`scoreboard: nothing recorded at ${scoresRel}`);
	const total = current.size;
	const passes = [...current.values()].filter((v) => v === "pass").length;
	// The predecessor is the argument read as an integer minus one, formatted back to the
	// same width. A non-numeric argument has no predecessor (@harness-042).
	const prevCycle = /^\d+$/.test(cycle) ? zeroPad(BigInt(cycle) - 1n, cycle.length) : null;
	const prev = prevCycle === null ? null : verdictsOf(path.join(ROOT, `harness/cycles/${prevCycle}/scores.json`));
	const lines = [`cycle ${cycle}: ${passes}/${total} checklist items pass`, ""];
	if (prev === null) {
		lines.push("  no previous cycle to diff against: baseline cycle.");
	} else {
		const shared = [...current.keys()].filter((k) => prev.has(k));
		const added = [...current.keys()].filter((k) => !prev.has(k));
		const dropped = [...prev.keys()].filter((k) => !current.has(k));
		const prevPass = shared.filter((k) => prev.get(k) === "pass").length;
		const thisPass = shared.filter((k) => current.get(k) === "pass").length;
		const better = shared.filter((k) => prev.get(k) === "fail" && current.get(k) === "pass").length;
		const worse = shared.filter((k) => prev.get(k) === "pass" && current.get(k) === "fail").length;
		lines.push(
			`  against cycle ${prevCycle}, counting only the ${shared.length} items both cycles carried:`,
			`    cycle ${prevCycle}  ${rate(prevPass, shared.length)}`,
			`    cycle ${cycle}  ${rate(thisPass, shared.length)}   <- these two are the fair comparison`,
			// Three literal spaces, then the count right aligned in three, so that the counts
			// land in the same column as the rates above them.
			`    now passing   ${ralign(better, 3)}   <- repaired since then`,
			`    now failing   ${ralign(worse, 3)}   <- broken since then`,
		);
		// Printed only when the item set moved, in either direction (@harness-047). The
		// wrapping of the sentence is contract.
		if (added.length || dropped.length) {
			lines.push(
				"",
				`  THE ITEM SET MOVED: ${prev.size} items -> ${total} (${added.length} added, ${dropped.length} dropped), so the two headline`,
				"  rates count different things and must not be read against each other.",
				"  The added items cover work last cycle's spec never asked for.",
			);
		}
	}
	lines.push("");
	out(lines.join("\n"));
});
