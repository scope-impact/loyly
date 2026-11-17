// hjson: JSON in the one dialect the harness speaks, plus the small pieces of plumbing every
// tool in it needs. The namespace the four tools require, as hjson.clj was.
//
// The writer reproduces a one-space-indented pretty printer with non-ASCII written through
// literally: that byte shape is contract (harness/spec.feature @harness-012), so it is
// spelled out here rather than delegated to JSON.stringify, whose defaults could move
// underneath it.
//
// The reader is hand written for two reasons. A scored item is the checklist item's keys
// IN THE CHECKLIST'S OWN ORDER (@harness-029), so objects come back as ordered Maps, the
// array maps of the Clojure. And a number keeps the form it was written in: 1 and 1.0 are
// different values to this instrument and are written back as they came, which
// JSON.parse cannot do.
//
// Shape. Values are plain data and the functions over them are pure, as they were in the
// Clojure. What touches the world (files, stdout, stderr) is the Io service, and what can go
// wrong is a typed failure: a Refusal is the instrument declining on purpose, a JsonError a
// document it cannot read. `run` is the one place either becomes an exit code.
//
// These tools replaced Babashka ones on 2026-11-17, accepted only because harness/oracle.sh
// found no byte of difference between them. Wherever the JVM's behaviour reached the output
// (what counts as whitespace, how a double prints, how a rate rounds, what an unpaired
// surrogate becomes), it is reproduced here on purpose, and each place says so.

import { mkdirSync, readdirSync, readFileSync, realpathSync, statSync, writeFileSync, writeSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import * as A from "effect/Array";
import * as Cause from "effect/Cause";
import * as Context from "effect/Context";
import * as Data from "effect/Data";
import * as Effect from "effect/Effect";
import * as Exit from "effect/Exit";
import { pipe } from "effect/Function";
import * as Option from "effect/Option";
import * as Order from "effect/Order";

// ---------------------------------------------------------------- values

/** A number written with a fraction or an exponent. Kept apart from integers so 1.0 stays 1.0. */
export class JFloat {
	readonly value: number;
	constructor(value: number) {
		this.value = value;
	}
}

export type JValue = null | boolean | string | number | bigint | JFloat | JValue[] | JMap;
export type JMap = Map<string, JValue>;

export const isMap = (v: unknown): v is JMap => v instanceof Map;

/** `(array-map k v ...)`: an ordered map from alternating keys and values. */
export const arrayMap = (...kvs: JValue[]): JMap => new Map(A.chunksOf(kvs, 2).map(([k, v]) => [k as string, v ?? null]));

/** `(get m k not-found)`: a value from a map, `fallback` from anything else. */
export const get = (m: unknown, k: string, fallback: JValue = null): JValue => (isMap(m) && m.has(k) ? (m.get(k) as JValue) : fallback);

/** `(seq v)` over a parsed value: nil is empty, a map is its entries, a string its characters. */
export const seq = (v: JValue | undefined): JValue[] =>
	v == null ? [] : Array.isArray(v) ? v : isMap(v) ? [...v].map(([k, x]) => [k, x]) : typeof v === "string" ? [...v] : [];

/**
 * A key under which two values are one exactly when Clojure's `=` says so: strings by text,
 * integers by value whatever their width, 1 and 1.0 apart, maps whatever their order. It is
 * what a hash map or a set keyed by these values compares.
 */
export const keyOf = (v: JValue): string =>
	v === null
		? "n"
		: typeof v === "boolean"
			? `b${v}`
			: typeof v === "string"
				? `s${v}`
				: typeof v === "number" || typeof v === "bigint"
					? `i${v}`
					: v instanceof JFloat
						? `f${javaDouble(v.value)}`
						: Array.isArray(v)
							? `[${v.map(keyOf).join(",")}]`
							: `{${pipe(
									[...v].map(([k, x]) => `${JSON.stringify(k)}:${keyOf(x)}`),
									A.sort(Order.String),
									A.join(","),
								)}}`;

// ---------------------------------------------------------------- the JVM, where it shows

/** java.lang.Character.isWhitespace, which is what Clojure's trim and blank? and the reader use. */
export const isWhitespace = (c: number): boolean =>
	(c >= 0x09 && c <= 0x0d) ||
	(c >= 0x1c && c <= 0x20) ||
	c === 0x1680 ||
	(c >= 0x2000 && c <= 0x200a && c !== 0x2007) ||
	c === 0x2028 ||
	c === 0x2029 ||
	c === 0x205f ||
	c === 0x3000;

const wsAt = (s: string, i: number) => isWhitespace(s.charCodeAt(i));

/** clojure.string/trim. */
export const trim = (s: string): string => {
	let end = s.length;
	while (end > 0 && wsAt(s, end - 1)) end--;
	let start = 0;
	while (start < end && wsAt(s, start)) start++;
	return s.slice(start, end);
};

/** clojure.string/blank?. */
export const blank = (s: string): boolean => Array.from({ length: s.length }, (_, i) => i).every((i) => wsAt(s, i));

/**
 * Pieces of java.util.regex, for the patterns the tools were specified in. Java's \s is
 * ASCII whitespace only; JavaScript's is Unicode-wide.
 */
export const RX = {
	ws: "[ \\t\\n\\x0B\\f\\r]",
	nonWs: "[^ \\t\\n\\x0B\\f\\r]",
	// A title may hold any character but a line break, and a "\r" before the "\n" is not
	// part of it (@harness-082). Java's "." also stopped at U+0085, U+2028 and U+2029.
	dot: "[^\\n]",
	end: "(?=\\r?$)",
	lineStart: "(?<=^|[\\n\\r\\u0085\\u2028\\u2029])",
};

/** `(re-pattern ...)` over a Java pattern assembled from RX, matching code points as Java does. */
export const re = (source: string, flags = "") => new RegExp(source, `u${flags}`);

/**
 * Double.toString (JDK 19 and later): the shortest digits that round-trip, written plainly
 * between 10^-3 and 10^7 with at least one digit after the point, and as d.dddE<n> outside
 * that range. When the shortest has one digit, the two-digit ones are weighed as well and
 * the closest to the double wins: 4.9E-324, not 5.0E-324.
 */
export const javaDouble = (x: number): string => {
	if (Number.isNaN(x)) return "NaN";
	if (!Number.isFinite(x)) return x > 0 ? "Infinity" : "-Infinity";
	if (x === 0) return Object.is(x, -0) ? "-0.0" : "0.0";
	const sign = x < 0 ? "-" : "";
	const a = Math.abs(x);
	const shortest = a.toExponential();
	const two = a.toExponential(1);
	const chosen = !shortest.includes(".") && Number(two) === a ? two.replace(/\.?0+e/, "e") : shortest;
	const [mantissa, e] = chosen.split("e");
	const digits = mantissa.replace(".", "");
	const exp = Number(e);
	if (a >= 1e-3 && a < 1e7) {
		const point = exp + 1;
		return point <= 0
			? `${sign}0.${"0".repeat(-point)}${digits}`
			: point >= digits.length
				? `${sign}${digits}${"0".repeat(point - digits.length)}.0`
				: `${sign}${digits.slice(0, point)}.${digits.slice(point)}`;
	}
	return `${sign}${digits[0]}.${digits.slice(1) || "0"}E${exp}`;
};

/**
 * A value as a message prints it (@harness-077): a string as it is, anything else as one
 * line of JSON with ", " between elements and ": " after a key. A missing value is null.
 */
export const str = (v: JValue | undefined): string => (typeof v === "string" ? v : inline(v ?? null));

/** One line of JSON. */
export const inline = (v: JValue): string =>
	isMap(v)
		? `{${[...v].map(([k, x]) => `${writeString(k)}: ${inline(x)}`).join(", ")}}`
		: Array.isArray(v)
			? `[${v.map(inline).join(", ")}]`
			: write(v);

// ---------------------------------------------------------------- writing

const escapes: Record<string, string> = { "\\": "\\\\", '"': '\\"', "\b": "\\b", "\f": "\\f", "\n": "\\n", "\r": "\\r", "\t": "\\t" };

/** One UTF-16 unit at a time, as Java's StringBuilder walked it: below 0x20 is \u00xx. */
const writeString = (s: string): string =>
	`"${Array.from({ length: s.length }, (_, i) => {
		const c = s[i];
		const code = s.charCodeAt(i);
		return escapes[c] ?? (code < 0x20 ? `\\u${code.toString(16).padStart(4, "0")}` : c);
	}).join("")}"`;

const indent = (n: number) => " ".repeat(n);

/** Render `v` as JSON text, one space of indent per level, no trailing newline. */
export const write = (v: JValue, level = 0): string => {
	if (v === null) return "null";
	if (typeof v === "boolean") return String(v);
	if (typeof v === "string") return writeString(v);
	if (typeof v === "number" || typeof v === "bigint") return String(v);
	if (v instanceof JFloat) return javaDouble(v.value);
	const lines = isMap(v)
		? [...v].map(([k, x]) => `${indent(level + 1)}${writeString(k)}: ${write(x, level + 1)}`)
		: v.map((x) => `${indent(level + 1)}${write(x, level + 1)}`);
	const [open, close] = isMap(v) ? ["{", "}"] : ["[", "]"];
	return lines.length === 0 ? `${open}${close}` : `${open}\n${lines.join(",\n")}\n${indent(level)}${close}`;
};

// ---------------------------------------------------------------- reading
//
// A recursive descent in the Clojure's own shape: every reader takes the text and a
// position and returns [value, next position]. It throws where the Clojure threw, and
// readJson is the one place a throw becomes a failure.

/** A document that cannot be read. `detail` is the parser's own words, the one message that carries them. */
export class JsonError extends Data.TaggedError("JsonError")<{ readonly detail: string }> {}

class Malformed extends Error {}

const fail = (i: number, msg: string): never => {
	throw new Malformed(`${msg} at position ${i}`);
};

type Read<T> = (s: string, i: number) => [T, number];

/** Between tokens, JSON's four whitespace characters and no others (@harness-071). */
const skipWs = (s: string, i: number): number => {
	while (i < s.length && (s[i] === " " || s[i] === "\t" || s[i] === "\n" || s[i] === "\r")) i++;
	return i;
};

const simpleEscapes: Record<string, string> = { '"': '"', "\\": "\\", "/": "/", b: "\b", f: "\f", n: "\n", r: "\r", t: "\t" };


const readStringLit: Read<string> = (s, start) => {
	let acc = "";
	for (let i = start + 1; ; ) {
		if (i >= s.length) fail(i, "unterminated string");
		const c = s[i];
		const e = s[i + 1];
		if (c === '"') return [acc, i + 1];
		if (c.charCodeAt(0) < 0x20) fail(i, "a raw control character in a string");
		if (c !== "\\") {
			acc += c;
			i += 1;
		} else if (e !== undefined && e in simpleEscapes) {
			acc += simpleEscapes[e];
			i += 2;
		} else if (e === "u" && /^[0-9a-fA-F]{4}$/.test(s.slice(i + 2, i + 6))) {
			acc += String.fromCharCode(Number.parseInt(s.slice(i + 2, i + 6), 16));
			i += 6;
		} else {
			fail(i, `invalid escape \\${e ?? ""}`);
		}
	}
};

/** JSON's number, exactly: no sign but "-", no leading zero, digits on both sides of a point. */
const NUMBER = /-?(?:0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?/y;

/** An integer is kept exactly, at any size; anything else is a double (@harness-072). */
const readNumber: Read<JValue> = (s, i) => {
	NUMBER.lastIndex = i;
	const m = NUMBER.exec(s);
	if (!m) fail(i, `unexpected character '${s[i]}'`);
	const [text, fraction, exponent] = m!;
	const end = i + text.length;
	if (fraction === undefined && exponent === undefined) {
		const n = BigInt(text);
		return [Number.isSafeInteger(Number(n)) ? Number(n) : n, end];
	}
	const x = Number(text);
	if (!Number.isFinite(x)) fail(i, `a number no double can hold, ${text}`);
	return [new JFloat(x), end];
};

/** The shared body of array and object reading: `close` ends it, `element` reads one. */
const readSeq =
	<T>(close: string, element: Read<T>): Read<T[]> =>
	(s, start) => {
		const acc: T[] = [];
		const first = skipWs(s, start + 1);
		if (s[first] === close) return [acc, first + 1];
		// After a comma comes an element, never the close: a trailing comma is not JSON (@harness-071).
		for (let i = first; ; ) {
			if (i >= s.length) fail(i, "unexpected end of input");
			const [x, after] = element(s, i);
			acc.push(x);
			i = skipWs(s, after);
			if (i >= s.length) fail(i, "unexpected end of input");
			if (s[i] === close) return [acc, i + 1];
			if (s[i] !== ",") fail(i, `unexpected character '${s[i]}'`);
			i = skipWs(s, i + 1);
		}
	};

const readPair: Read<[string, JValue]> = (s, i) => {
	if (s[i] !== '"') fail(i, "expected a key");
	const [k, afterKey] = readStringLit(s, i);
	const colon = skipWs(s, afterKey);
	if (s[colon] !== ":") fail(colon, "expected ':'");
	const [v, after] = readValue(s, skipWs(s, colon + 1));
	return [[k, v], after];
};

const readValue: Read<JValue> = (s, i) => {
	if (i >= s.length) fail(i, "unexpected end of input");
	if (s[i] === "{") {
		// `(apply array-map kvs)`: a repeated key keeps its first position and its last value.
		const [pairs, after] = readSeq("}", readPair)(s, i);
		return [new Map(pairs), after];
	}
	if (s[i] === "[") return readSeq("]", readValue)(s, i);
	if (s[i] === '"') return readStringLit(s, i);
	if (s.startsWith("true", i)) return [true, i + 4];
	if (s.startsWith("false", i)) return [false, i + 5];
	if (s.startsWith("null", i)) return [null, i + 4];
	return readNumber(s, i);
};

/** Parse JSON text, or fail with the parser's own words. */
export const readJson = (s: string): Effect.Effect<JValue, JsonError> =>
	Effect.try({
		try: () => {
			const [v, after] = readValue(s, skipWs(s, 0));
			const i = skipWs(s, after);
			if (i < s.length) fail(i, `trailing data '${s[i]}'`);
			return v;
		},
		catch: (e) => new JsonError({ detail: e instanceof Error ? e.message : String(e) }),
	});

/** The Python type names, which @harness-035 fixes as contract. */
export const typeName = (v: JValue): string =>
	isMap(v) ? "dict" : typeof v === "string" ? "str" : v === null ? "NoneType" : typeof v === "boolean" ? "bool" : Array.isArray(v) ? "list" : "int";

// ---------------------------------------------------------------- the world

/** The instrument declining on purpose: the message goes to stderr, the exit code is 1. */
export class Refusal extends Data.TaggedError("Refusal")<{ readonly message: string }> {}

export const refuse = (message: string) => Effect.fail(new Refusal({ message }));

/** Everything the tools do to the world, and nothing else. */
export class Io extends Context.Service<
	Io,
	{
		readonly slurp: (f: string) => Effect.Effect<string>;
		readonly spit: (f: string, text: string) => Effect.Effect<void>;
		readonly isFile: (f: string) => Effect.Effect<boolean>;
		readonly isDir: (f: string) => Effect.Effect<boolean>;
		readonly list: (dir: string) => Effect.Effect<string[]>;
		readonly out: (s: string) => Effect.Effect<void>;
		readonly err: (s: string) => Effect.Effect<void>;
	}
>()("harness/Io") {}

/** UTF-8 as the JVM encodes it: an unpaired surrogate becomes "?". */
const utf8 = (s: string) => Buffer.from(s.replace(/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/g, "?"), "utf8");

const emit = (fd: number, s: string) => {
	const b = utf8(s);
	for (let off = 0; off < b.length; ) off += writeSync(fd, b, off);
};

const statIs = (f: string, test: (s: ReturnType<typeof statSync>) => boolean) => {
	try {
		return test(statSync(f));
	} catch {
		return false;
	}
};

/** The live world: node's filesystem and the process's two streams, synchronously, as the JVM did it. */
export const nodeIo = Io.of({
	slurp: (f) => Effect.sync(() => readFileSync(f, "utf8")),
	spit: (f, text) =>
		Effect.sync(() => {
			mkdirSync(path.dirname(f), { recursive: true });
			writeFileSync(f, utf8(text));
		}),
	isFile: (f) => Effect.sync(() => statIs(f, (s) => s.isFile())),
	isDir: (f) => Effect.sync(() => statIs(f, (s) => s.isDirectory())),
	list: (dir) => Effect.sync(() => (statIs(dir, (s) => s.isDirectory()) ? readdirSync(dir) : [])),
	out: (s) => Effect.sync(() => emit(1, s)),
	err: (s) => Effect.sync(() => emit(2, s)),
});

export const slurp = (f: string) => Io.use((io) => io.slurp(f));
export const spit = (f: string, text: string) => Io.use((io) => io.spit(f, text));
export const isFile = (f: string) => Io.use((io) => io.isFile(f));
export const out = (s: string) => Io.use((io) => io.out(s));
export const err = (s: string) => Io.use((io) => io.err(s));

// ---------------------------------------------------------------- paths

/** `f` as a path relative to `root`, with forward slashes. */
export const rel = (root: string, f: string): string => f.slice(root.length + 1).split(path.sep).join("/");

export const planDir = (root: string): string => (root === "." ? "./docs/plan" : path.join(root, "docs", "plan"));

/** Keep the elements whose effectful test passes: `(filter pred coll)` where pred touches the world. */
const filterE = <X, E, R>(xs: readonly X[], pred: (x: X) => Effect.Effect<boolean, E, R>) =>
	Effect.map(
		Effect.forEach(xs, (x) => Effect.map(pred(x), (keep) => (keep ? Option.some(x) : Option.none<X>()))),
		A.getSomes,
	);

/**
 * Every docs/plan/[0-9]*\/spec.feature under `root`, sorted by path as text. A slice whose
 * number, the name up to its first "-", is not all digits stops `tool` (@harness-081):
 * its scenarios could never carry ids the checklist recognises.
 */
export const specFiles = (root: string, tool: string) =>
	Effect.gen(function* () {
		const io = yield* Io;
		const plan = planDir(root);
		const dirs = yield* filterE(
			pipe(
				yield* io.list(plan),
				A.filter((name) => /^[0-9]/.test(name)),
				A.map((name) => path.join(plan, name)),
			),
			io.isDir,
		);
		const files = yield* filterE(
			A.map(dirs, (d) => path.join(d, "spec.feature")),
			io.isFile,
		);
		const sorted = A.sort(files, Order.mapInput(Order.String, (f: string) => rel(root, f)));
		for (const f of sorted) {
			const dir = path.dirname(f);
			const number = path.basename(dir).split("-")[0];
			if (!/^\d+$/.test(number)) {
				return yield* refuse(
					`${tool}: ${rel(root, dir)} looks like a slice, but its number "${number}" is not all digits, so its scenarios could never carry ids the checklist recognises. Rename it.`,
				);
			}
		}
		return sorted;
	});

export const ralign = (s: unknown, w: number): string => String(s).padStart(w, " ");

// ---------------------------------------------------------------- running a tool

/**
 * Run a tool: `program` gets the repository root, which is the parent of the harness/
 * directory holding the script, resolved through symlinks from the script's own location
 * and never from the working directory (harness/spec.feature, INVOCATION). A Refusal is
 * printed with one trailing newline and exits 1; anything else that escapes is a crash,
 * said in one line, and exits 1 too.
 */
export const run = (scriptUrl: string, program: (root: string) => Effect.Effect<unknown, Refusal | JsonError, Io>): void => {
	const root = path.dirname(path.dirname(realpathSync(fileURLToPath(scriptUrl))));
	const exit = Effect.runSyncExit(Effect.provideService(program(root), Io, nodeIo));
	if (Exit.isSuccess(exit)) return;
	const line = Option.match(Cause.findErrorOption(exit.cause), {
		onSome: (e) => (e._tag === "Refusal" ? e.message : `JsonError: ${e.detail}`),
		onNone: () => {
			const defect = Cause.squash(exit.cause);
			return defect instanceof Error ? `${defect.name}: ${defect.message}` : String(defect);
		},
	});
	emit(2, `${line}\n`);
	process.exit(1);
};
