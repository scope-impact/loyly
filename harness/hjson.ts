// hjson: JSON in the one dialect the harness speaks, plus the small pieces of plumbing every
// tool in it needs.
//
// The writer reproduces a one-space-indented pretty printer with non-ASCII written through
// literally: that byte shape is contract (harness/spec.feature @harness-012), so it is
// spelled out here rather than delegated to JSON.stringify, whose defaults could move
// underneath it.
//
// The reader is hand written for two reasons. A scored item is the checklist item's keys
// IN THE CHECKLIST'S OWN ORDER (@harness-029), so objects come back as ordered Maps. And a
// number keeps the form it was written in: 1 and 1.0 are different values to this
// instrument and are written back as they came, which JSON.parse cannot do.
//
// These tools replaced Babashka ones on 2026-11-17, accepted only because harness/oracle.sh
// found no byte of difference between them. Wherever the JVM's behaviour reached the
// output (what counts as whitespace, how a double prints, how a rate rounds, what an
// unpaired surrogate becomes), it is reproduced here on purpose, and each place says so.

import { mkdirSync, readdirSync, readFileSync, realpathSync, statSync, writeFileSync, writeSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

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

/** Clojure's `get` with a string key: a value from a map, nil from anything else. */
export function get(v: unknown, key: string, fallback: JValue = null): JValue {
	if (isMap(v)) return v.has(key) ? (v.get(key) as JValue) : fallback;
	return fallback;
}

/** Clojure's `seq` over a parsed value: nil is empty, a map is its entries, a string its characters. */
export function seq(v: JValue | undefined): JValue[] {
	if (v === null || v === undefined) return [];
	if (Array.isArray(v)) return v;
	if (isMap(v)) return [...v.entries()].map(([k, x]) => [k, x]);
	if (typeof v === "string") return [...v];
	return [];
}

/**
 * A key under which two values are equal exactly when Clojure's `=` says so: strings by
 * text, integers by value whatever their width, 1 and 1.0 apart, maps whatever their order.
 */
export function keyOf(v: JValue): string {
	if (v === null) return "n";
	if (typeof v === "boolean") return `b${v}`;
	if (typeof v === "string") return `s${v}`;
	if (typeof v === "number" || typeof v === "bigint") return `i${v}`;
	if (v instanceof JFloat) return `f${javaDouble(v.value)}`;
	if (Array.isArray(v)) return `[${v.map(keyOf).join(",")}]`;
	return `{${[...v.entries()]
		.map(([k, x]) => `${JSON.stringify(k)}:${keyOf(x)}`)
		.sort()
		.join(",")}}`;
}

// ---------------------------------------------------------------- the JVM, where it shows

/** java.lang.Character.isWhitespace, which is what Clojure's trim and blank? and the reader use. */
export function isWhitespace(c: number): boolean {
	if (c === 0x09 || c === 0x0a || c === 0x0b || c === 0x0c || c === 0x0d) return true;
	if (c >= 0x1c && c <= 0x1f) return true;
	if (c === 0x20 || c === 0x1680 || c === 0x205f || c === 0x3000 || c === 0x2028 || c === 0x2029) return true;
	return c >= 0x2000 && c <= 0x200a && c !== 0x2007;
}

/** clojure.string/trim. */
export function trim(s: string): string {
	let end = s.length;
	while (end > 0 && isWhitespace(s.charCodeAt(end - 1))) end--;
	let start = 0;
	while (start < end && isWhitespace(s.charCodeAt(start))) start++;
	return s.slice(start, end);
}

/** clojure.string/blank?. */
export function blank(s: string): boolean {
	for (let i = 0; i < s.length; i++) if (!isWhitespace(s.charCodeAt(i))) return false;
	return true;
}

/**
 * Pieces of java.util.regex, for the patterns the tools were specified in. Java's \s is
 * ASCII whitespace only; JavaScript's is Unicode-wide. Java's `.` stops at more line
 * terminators than JavaScript's, and its `$` also matches before one final terminator.
 * Patterns using these are compiled with the `u` flag, so they see code points as Java does.
 */
export const RX = {
	ws: "[ \\t\\n\\x0B\\f\\r]",
	nonWs: "[^ \\t\\n\\x0B\\f\\r]",
	dot: "[^\\n\\r\\u0085\\u2028\\u2029]",
	end: "(?=(?:\\r\\n|[\\n\\r\\u0085\\u2028\\u2029])?$)",
	lineStart: "(?<=^|[\\n\\r\\u0085\\u2028\\u2029])",
};

/**
 * Double.toString (JDK 19 and later): the shortest digits that round-trip, written plainly
 * between 10^-3 and 10^7 with at least one digit after the point, and as d.dddE<n> outside
 * that range.
 */
export function javaDouble(x: number): string {
	if (Number.isNaN(x)) return "NaN";
	if (x === Infinity) return "Infinity";
	if (x === -Infinity) return "-Infinity";
	if (x === 0) return Object.is(x, -0) ? "-0.0" : "0.0";
	const sign = x < 0 ? "-" : "";
	const a = Math.abs(x);
	let [mantissa, e] = a.toExponential().split("e");
	// When the shortest decimal that round-trips has one digit, Java also weighs the
	// two-digit ones and takes whichever is closest to the double: 4.9E-324, not 5.0E-324.
	if (!mantissa.includes(".")) {
		const two = a.toExponential(1);
		if (Number(two) === a) [mantissa, e] = two.replace(/0e/, "e").replace(/\.e/, "e").split("e");
	}
	const digits = mantissa.replace(".", "");
	const exp = Number(e);
	if (a >= 1e-3 && a < 1e7) {
		const point = exp + 1;
		if (point <= 0) return `${sign}0.${"0".repeat(-point)}${digits}`;
		if (point >= digits.length) return `${sign}${digits}${"0".repeat(point - digits.length)}.0`;
		return `${sign}${digits.slice(0, point)}.${digits.slice(point)}`;
	}
	return `${sign}${digits[0]}.${digits.length > 1 ? digits.slice(1) : "0"}E${exp}`;
}

/** Clojure's `str` of a value: nil is empty, a float prints as Java prints it. */
export function str(v: JValue | undefined): string {
	if (v === null || v === undefined) return "";
	if (typeof v === "string") return v;
	if (typeof v === "boolean" || typeof v === "number" || typeof v === "bigint") return String(v);
	if (v instanceof JFloat) return javaDouble(v.value);
	return pr(v);
}

const charNames: Record<string, string> = { "\n": "newline", "\t": "tab", " ": "space", "\b": "backspace", "\f": "formfeed", "\r": "return" };

/** Clojure's pr-str, for the few values a message prints readably. */
export function pr(v: JValue | undefined): string {
	if (v === null || v === undefined) return "nil";
	if (typeof v === "string") {
		const esc: Record<string, string> = { '"': '\\"', "\\": "\\\\", "\n": "\\n", "\t": "\\t", "\r": "\\r", "\f": "\\f", "\b": "\\b" };
		return `"${[...v].map((c) => esc[c] ?? c).join("")}"`;
	}
	if (Array.isArray(v)) return `[${v.map(pr).join(" ")}]`;
	if (isMap(v)) return `{${[...v.entries()].map(([k, x]) => `${pr(k)} ${pr(x)}`).join(", ")}}`;
	return str(v);
}

/** pr-str of a single character, as the reader's "invalid escape" message shows it. */
function prChar(c: string | undefined): string {
	if (c === undefined) return "nil";
	return `\\${charNames[c] ?? c}`;
}

// ---------------------------------------------------------------- writing

const escapes: Record<string, string> = { "\\": "\\\\", '"': '\\"', "\b": "\\b", "\f": "\\f", "\n": "\\n", "\r": "\\r", "\t": "\\t" };

function writeString(s: string): string {
	let out = '"';
	for (let i = 0; i < s.length; i++) {
		const c = s[i];
		const code = s.charCodeAt(i);
		out += escapes[c] ?? (code < 0x20 ? `\\u${code.toString(16).padStart(4, "0")}` : c);
	}
	return `${out}"`;
}

const indent = (n: number) => " ".repeat(n);

/** Render `v` as JSON text, one space of indent per level, no trailing newline. */
export function write(v: JValue, level = 0): string {
	if (v === null) return "null";
	if (v === true) return "true";
	if (v === false) return "false";
	if (typeof v === "string") return writeString(v);
	if (typeof v === "number" || typeof v === "bigint") return String(v);
	if (v instanceof JFloat) return javaDouble(v.value);
	if (isMap(v)) {
		if (v.size === 0) return "{}";
		const body = [...v.entries()].map(([k, x]) => `${indent(level + 1)}${writeString(k)}: ${write(x, level + 1)}`);
		return `{\n${body.join(",\n")}\n${indent(level)}}`;
	}
	if (Array.isArray(v)) {
		if (v.length === 0) return "[]";
		return `[\n${v.map((x) => `${indent(level + 1)}${write(x, level + 1)}`).join(",\n")}\n${indent(level)}]`;
	}
	throw new Error(`cannot render ${String(v)} as JSON`);
}

// ---------------------------------------------------------------- reading

/** A malformed document. `detail` is the parser's own words, the one message that carries them. */
export class JsonError extends Error {
	readonly detail: string;
	constructor(detail: string) {
		super(detail);
		this.detail = detail;
	}
}

function fail(i: number, msg: string): never {
	throw new JsonError(`${msg} at position ${i}`);
}

function skipWs(s: string, i: number): number {
	while (i < s.length && isWhitespace(s.charCodeAt(i))) i++;
	return i;
}

const simpleEscapes: Record<string, string> = { '"': '"', "\\": "\\", "/": "/", b: "\b", f: "\f", n: "\n", r: "\r", t: "\t" };

function readStringLit(s: string, i: number): [string, number] {
	let out = "";
	i++;
	for (;;) {
		if (i >= s.length) fail(i, "unterminated string");
		const c = s[i];
		if (c === '"') return [out, i + 1];
		if (c !== "\\") {
			out += c;
			i++;
			continue;
		}
		const e = i + 1 < s.length ? s[i + 1] : undefined;
		if (e !== undefined && e in simpleEscapes) {
			out += simpleEscapes[e];
			i += 2;
		} else if (e === "u") {
			if (!(i + 5 < s.length)) fail(i, "truncated \\u escape");
			const hex = s.slice(i + 2, i + 6);
			// Integer.parseInt(hex, 16): a sign is allowed, and a negative value is no character.
			if (!/^[+-]?[0-9a-fA-F]+$/.test(hex)) throw new Error(`For input string: "${hex}" under radix 16`);
			const code = Number.parseInt(hex, 16);
			if (code < 0) throw new Error(`Value out of range for char: ${code}`);
			out += String.fromCharCode(code);
			i += 6;
		} else {
			fail(i, `invalid escape ${prChar(e)}`);
		}
	}
}

const LONG_MIN = -(2n ** 63n);
const LONG_MAX = 2n ** 63n - 1n;
// What Double.parseDouble accepts of the characters read-number collects.
const JAVA_DOUBLE = /^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$/;

function readNumber(s: string, i: number): [JValue, number] {
	let end = i;
	while (end < s.length && /[-+0-9.eE]/.test(s[end])) end++;
	const text = s.slice(i, end);
	if (i === end) fail(i, `unexpected character '${s[i]}'`);
	if (/^-?\d+$/.test(text)) {
		const n = BigInt(text);
		if (n < LONG_MIN || n > LONG_MAX) throw new Error(`For input string: "${text}"`); // Long.parseLong
		return [Number.isSafeInteger(Number(n)) ? Number(n) : n, end];
	}
	if (!JAVA_DOUBLE.test(text)) fail(i, `invalid number ${pr(text)}`);
	return [new JFloat(Number(text)), end];
}

function readSeq<T>(s: string, i: number, close: string, element: (s: string, i: number) => [T, number]): [T[], number] {
	i = skipWs(s, i + 1);
	const acc: T[] = [];
	for (;;) {
		if (i >= s.length) fail(i, "unexpected end of input");
		if (s[i] === close) return [acc, i + 1];
		const [part, after] = element(s, i);
		acc.push(part);
		i = skipWs(s, after);
		if (i >= s.length) fail(i, "unexpected end of input");
		if (s[i] === ",") i = skipWs(s, i + 1);
		else if (s[i] === close) return [acc, i + 1];
		else fail(i, `unexpected character '${s[i]}'`);
	}
}

function readPair(s: string, i: number): [[string, JValue], number] {
	if (s[i] !== '"') fail(i, "expected a key");
	const [k, afterKey] = readStringLit(s, i);
	i = skipWs(s, afterKey);
	if (!(i < s.length && s[i] === ":")) fail(i, "expected ':'");
	const [v, after] = readValue(s, skipWs(s, i + 1));
	return [[k, v], after];
}

function readValue(s: string, i: number): [JValue, number] {
	if (i >= s.length) fail(i, "unexpected end of input");
	const c = s[i];
	if (c === "{") {
		const [pairs, after] = readSeq(s, i, "}", readPair);
		// Repeated keys behave as repeated assoc: the first position, the last value.
		const m: JMap = new Map();
		for (const [k, v] of pairs) m.set(k, v);
		return [m, after];
	}
	if (c === "[") return readSeq(s, i, "]", readValue);
	if (c === '"') return readStringLit(s, i);
	if (c === "t" && s.slice(i, i + 4) === "true") return [true, i + 4];
	if (c === "f" && s.slice(i, i + 5) === "false") return [false, i + 5];
	if (c === "n" && s.slice(i, i + 4) === "null") return [null, i + 4];
	return readNumber(s, i);
}

/** Parse JSON text. Throws JsonError (or the JVM's number errors) on malformed input. */
export function readJson(s: string): JValue {
	const [v, after] = readValue(s, skipWs(s, 0));
	const i = skipWs(s, after);
	if (i < s.length) fail(i, `trailing data '${s[i]}'`);
	return v;
}

/** The parser's own words about a failure, for the one message that carries them. */
export function detail(e: unknown): string {
	if (e instanceof JsonError) return e.detail;
	return e instanceof Error ? e.message : String(e);
}

/** The Python type names, which @harness-035 fixes as contract. */
export function typeName(v: JValue): string {
	if (isMap(v)) return "dict";
	if (typeof v === "string") return "str";
	if (v === null) return "NoneType";
	if (typeof v === "boolean") return "bool";
	if (typeof v === "number" || typeof v === "bigint" || v instanceof JFloat) return "int";
	return "list";
}

// ---------------------------------------------------------------- plumbing

/**
 * The repository root: the parent of the harness/ directory holding the calling script,
 * resolved through symlinks from the script's own location and never from the working
 * directory (harness/spec.feature, INVOCATION). Pass the script's import.meta.url.
 */
export function root(scriptUrl: string): string {
	return path.dirname(path.dirname(realpathSync(fileURLToPath(scriptUrl))));
}

/** `f` as a path relative to `root`, with forward slashes. */
export function rel(rootDir: string, f: string): string {
	return f.slice(rootDir.length + 1).split(path.sep).join("/");
}

function isDir(p: string): boolean {
	try {
		return statSync(p).isDirectory();
	} catch {
		return false;
	}
}

export function isFile(p: string): boolean {
	try {
		return statSync(p).isFile();
	} catch {
		return false;
	}
}

/** Every docs/plan/[0-9]*\/spec.feature under `root`, sorted by path as text. */
export function specFiles(rootDir: string): string[] {
	const plan = path.join(rootDir, "docs", "plan");
	let names: string[];
	try {
		names = readdirSync(plan);
	} catch {
		return [];
	}
	return names
		.filter((n) => /^[0-9]/.test(n) && isDir(path.join(plan, n)))
		.map((n) => path.join(plan, n, "spec.feature"))
		.filter(isFile)
		.sort((a, b) => (rel(rootDir, a) < rel(rootDir, b) ? -1 : rel(rootDir, a) > rel(rootDir, b) ? 1 : 0));
}

export const planDir = (rootDir: string) => (rootDir === "." ? "./docs/plan" : path.join(rootDir, "docs", "plan"));

export const slurp = (f: string) => readFileSync(f, "utf8");

/** UTF-8 as the JVM encodes it: an unpaired surrogate becomes "?". */
function utf8(s: string): Buffer {
	return Buffer.from(s.replace(/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/g, "?"), "utf8");
}

function emit(fd: number, s: string): void {
	const b = utf8(s);
	let off = 0;
	while (off < b.length) off += writeSync(fd, b, off);
}

export const out = (s: string) => emit(1, s);
export const err = (s: string) => emit(2, s);

/** A refusal: the message to stderr with one trailing newline, then exit 1. */
export function refuse(msg: string): never {
	err(`${msg}\n`);
	process.exit(1);
}

/** Write `text` to `f`, creating missing parent directories. */
export function spitUtf8(f: string, text: string): void {
	const dir = path.dirname(f);
	if (dir) mkdirSync(dir, { recursive: true });
	writeFileSync(f, utf8(text));
}

export function ralign(s: unknown, w: number): string {
	const t = String(s);
	return " ".repeat(Math.max(0, w - t.length)) + t;
}

/** What an uncaught error looks like: one line and exit 1, as a crashed tool should. */
export function main(body: () => void): void {
	try {
		body();
	} catch (e) {
		err(`${e instanceof Error ? `${e.name}: ${e.message}` : String(e)}\n`);
		process.exit(1);
	}
}
