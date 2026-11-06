#!/usr/bin/env node
// spec-review: an MCP App for reviewing the Pi harness spec (.pi/model) with an agent.
// Data comes from the spec tools themselves: tools/spec/coverage.py --report (the gate's
// classification) and tools/spec/views/extract.clj (the page's extraction, via bb/spec-data.clj).
// Review threads live in .pi/model/review.edn (read and written as EDN by bb/review-store.clj).
// The UI is the event-model page rendered by the page's own renderer in app mode (RENDER_APP).
import { registerAppResource, registerAppTool, RESOURCE_MIME_TYPE } from "@modelcontextprotocol/ext-apps/server";
import { McpServer, type CallToolResult } from "@modelcontextprotocol/server";
import { StdioServerTransport } from "@modelcontextprotocol/server/stdio";
import { NodeStreamableHTTPServerTransport } from "@modelcontextprotocol/node";
import http from "node:http";
import { execFileSync, spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { z } from "zod";

const HERE = path.dirname(fileURLToPath(import.meta.url)); // tools/spec/mcp/dist
const MCP = path.resolve(HERE, "..");
const ROOT = process.env.SPEC_REVIEW_ROOT ?? path.resolve(MCP, "../../..");
const STORE = process.env.SPEC_REVIEW_STORE ?? path.join(ROOT, ".pi/model/review.edn");
const PLAN = path.join(ROOT, ".pi/model/plan");
const APP_JS = path.join(MCP, "dist/app.js");
const UI_URI = "ui://spec-review/event-model.html";

// The page renderer in app mode: the page plus review controls (data-review-action,
// data-review-target, data-threads-for) with our wiring script inlined. The one place that
// names the command; tools/spec/README.md documents it.
// It renders the checkout it lives in (tools/spec/views), not SPEC_REVIEW_ROOT.
const RENDER_APP = (out: string, threads: string) => ({
  cmd: "bb",
  args: ["--config", "tools/spec/views/bb.edn", "tools/spec/views/render.clj",
         "--mode", "app", "--out", out, "--app-js", APP_JS, "--threads", threads],
  cwd: path.resolve(MCP, "../../.."),
});
// Until app mode lands: the built page with the wiring script inlined (no review controls).
const STUB_PAGE = path.resolve(MCP, "../views/dist/event-model.html");
// Origins the page loads from: Mermaid (jsDelivr) and its fonts (Google Fonts).
const CSP = { resourceDomains: ["https://cdn.jsdelivr.net", "https://fonts.googleapis.com", "https://fonts.gstatic.com"] };

// ---------- types (the JSON of coverage.py --report and extract.clj) ----------
type Scen = { slice: string; kind: "spec" | "drafts"; rule: string | null; title: string; item: string | null;
              tier: string; tags: string[]; steps: string[]; file: string; line: number; module: string | null };
type Rule = { name: string; src: string; when: string | null; requires: string[]; emits: string[] };
type Obl = { id: string; kind: string; file: string; rule?: string; n?: number; clause?: string; unit: string | null;
             status: "covered" | "drafted" | "unit" | "uncovered" | "gated"; scenarios: { item: string | null; title: string; kind: string }[] };
type Spec = { commit: string; dirty: boolean; modules: Record<string, { id: string; file: string; rules: Rule[] }>;
              slices: { id: string; module: string | null }[]; scenarios: Scen[];
              coverage: { summary: Record<string, number>; obligations: Obl[]; unknownTags: string[] } };
type Entry = { author: string; at: string; text: string };
type Thread = { id: string; target: string; status: "open" | "resolved"; entries: Entry[] };

// ---------- data ----------
function run(cmd: string, args: string[], input?: string) {
  const r = spawnSync(cmd, args, { cwd: ROOT, input, encoding: "utf8", maxBuffer: 64 << 20 });
  if (r.error) throw r.error;
  return r;
}
function coverageLine(): string {
  const r = run("python3", [path.join(ROOT, "tools/spec/coverage.py")]);
  return (r.stdout.split("\n")[0] || "").trim();
}
function fingerprint(): string {
  const files = fs.readdirSync(path.join(ROOT, ".pi/model")).filter(f => f.endsWith(".allium")).map(f => path.join(ROOT, ".pi/model", f));
  for (const d of fs.readdirSync(PLAN)) for (const f of ["spec.feature", "drafts.feature"]) files.push(path.join(PLAN, d, f));
  return files.map(f => (fs.existsSync(f) ? fs.statSync(f).mtimeMs : 0)).join(",");
}
let cache: { fp: string; spec: Spec } | null = null;
function loadSpec(): Spec {
  const fp = fingerprint();
  if (cache?.fp === fp) return cache.spec;
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "spec-review-"));
  try {
    const cov = path.join(tmp, "coverage.json"), model = path.join(tmp, "model.edn");
    const c = run("python3", [path.join(ROOT, "tools/spec/coverage.py"), "--report", cov]);
    if ((c.status ?? 2) > 1) throw new Error(`coverage.py failed (${c.status}): ${c.stderr}`);
    fs.writeFileSync(model, "{:model {} :files {}}");
    const out = execFileSync("bb", [path.join(MCP, "bb/spec-data.clj"), ROOT, model, cov], { cwd: ROOT, encoding: "utf8", maxBuffer: 64 << 20 });
    const spec = JSON.parse(out) as Spec;
    cache = { fp, spec };
    return spec;
  } finally { fs.rmSync(tmp, { recursive: true, force: true }); }
}
function readThreads(): Thread[] {
  return JSON.parse(execFileSync("bb", [path.join(MCP, "bb/review-store.clj"), "read", STORE], { encoding: "utf8" })).threads;
}
function writeThreads(threads: Thread[]) {
  execFileSync("bb", [path.join(MCP, "bb/review-store.clj"), "write", STORE], { input: JSON.stringify({ threads }), encoding: "utf8" });
}

// ---------- shared text ----------
const STATUS: Record<string, string> = { covered: "covered", drafted: "draft", unit: "unit level", uncovered: "UNCOVERED", gated: "gate" };
const oblStatus = (o: Obl) => {
  const items = o.scenarios.filter(s => s.kind === "spec").map(s => s.item).filter(Boolean);
  return o.status === "covered" ? `covered by ${items.join(", ")}` : STATUS[o.status] ?? o.status;
};
const moduleOf = (o: Obl) => o.file.replace(/\.allium$/, "");
const oblTags = (s: Scen) => s.tags.filter(t => t.startsWith("@allium-")).map(t => t.slice(8));
const threadText = (t: Thread) =>
  `- **${t.id}** \`${t.target}\` (${t.status})\n` + t.entries.map(e => `  - ${e.author} (${e.at}): ${e.text.replace(/\n/g, "\n    ")}`).join("\n");
const text = (s: string, structured?: Record<string, unknown>): CallToolResult =>
  ({ content: [{ type: "text", text: s }], ...(structured ? { structuredContent: structured } : {}) });
const fail = (s: string): CallToolResult => ({ content: [{ type: "text", text: s }], isError: true });

function findRule(spec: Spec, name: string) {
  for (const m of Object.values(spec.modules)) {
    const r = m.rules.find(r => r.name === name);
    if (r) return { mod: m, rule: r };
  }
  return null;
}

function ruleCard(spec: Spec, name: string, threads: Thread[]): string | null {
  const hit = findRule(spec, name);
  if (!hit) return null;
  const { mod, rule } = hit;
  const obls = spec.coverage.obligations.filter(o => o.rule === name);
  const tag = (id: string) => `@allium-${id}`;
  // GIVEN: the rules that ensure a state this rule requires (x.status = v)
  const given: string[] = [];
  for (const req of rule.requires) {
    const m = req.match(/^\w+\.(status|stage|path)\s*=\s*(\w+)$/);
    if (!m) continue;
    const setters = Object.values(spec.modules).flatMap(md => md.rules)
      .filter(r => r.name !== name && new RegExp(`ensures:[\\s\\S]*\\b${m[1]}(\\s*=|:)\\s*${m[2]}\\b`).test(r.src)).map(r => r.name);
    given.push(`- ${m[1]} = ${m[2]}` + (setters.length ? `, after ${setters.join(" or ")}` : ""));
  }
  const ensures = rule.src.includes("ensures:")
    ? rule.src.slice(rule.src.indexOf("ensures:")).replace(/\n\}\s*$/, "").split("\n").map(l => l.replace(/^ {4}/, "")).join("\n")
    : "(nothing)";
  const succ = obls.find(o => o.kind === "rule-success");
  const ids = new Set(obls.map(o => o.id));
  const examples = spec.scenarios.filter(s => oblTags(s).some(t => ids.has(t)));
  const mine = threads.filter(t => t.target === `rule:${name}` || obls.some(o => t.target === `obligation:${o.id}`)
    || examples.some(s => t.target === (s.kind === "spec" ? `scenario:${s.item}` : `draft:${s.slice}:${s.title}`)));
  const L: string[] = [];
  L.push(`## Rule ${name}`, `${mod.file}` + (succ ? ` · \`${tag(succ.id)}\`: ${oblStatus(succ)}` : ""), "");
  L.push("**GIVEN**", given.length ? given.join("\n") : "- nothing yet: this rule starts the lifecycle (no state required)", "");
  L.push("**WHEN**", `\`${rule.when ?? "?"}\``, "");
  L.push("**ONLY IF**");
  if (!rule.requires.length) L.push("- (no requires)");
  rule.requires.forEach((req, i) => {
    const o = obls.find(o => o.kind === "rule-failure" && o.n === i + 1);
    L.push(`${i + 1}. \`${req}\` · \`${tag(`rule-failure.${name}.${i + 1}`)}\` · ${o ? oblStatus(o) : "not an obligation"}`);
  });
  L.push("", "**THEN**", "```", ensures, "```", "", "**EXAMPLES**");
  if (!examples.length) L.push("- (none)");
  for (const s of examples)
    L.push(s.kind === "spec"
      ? `- ${s.item} ${s.title} (${s.tier}) · ${oblTags(s).join(" ")}`
      : `- DRAFT (${s.slice}) ${s.title} · ${oblTags(s).join(" ")}`);
  const open = mine.filter(t => t.status === "open");
  L.push("", `**OPEN THREADS** (${open.length})`, open.length ? open.map(threadText).join("\n") : "- none");
  return L.join("\n");
}

function summary(spec: Spec, threads: Thread[]) {
  const s = spec.coverage.summary;
  const drafts = spec.scenarios.filter(x => x.kind === "drafts").length;
  const open = threads.filter(t => t.status === "open").length;
  const unc = spec.coverage.obligations.filter(o => o.status === "uncovered");
  const line = `must-cover ${s.mustCover} | covered ${s.covered} | drafted ${s.drafted} | uncovered ${s.uncovered} | unit-level ${s.unitLevel}`;
  return { line, drafts, open, uncovered: unc.slice(0, 10).map(o => o.id) };
}

// ---------- drafts.feature: find, move, stamp ----------
type Draft = { start: number; end: number; tags: string[]; indent: string; body: string[]; title: string;
               goesUnder: string | null; newRule: boolean; intoSlice: string | null; tier: string | null };
function parseDrafts(lines: string[]): Draft[] {
  const out: Draft[] = [];
  lines.forEach((l, i) => {
    const m = l.match(/^\s*Scenario(?: Outline)?:\s*(.*?)\s*$/);
    if (!m) return;
    let t = i; while (t > 0 && lines[t - 1].trim().startsWith("@")) t--;
    let c = t; while (c > 0 && lines[c - 1].trim() === "") c--;
    let start = c; while (start > 0 && lines[start - 1].trim().startsWith("#")) start--;
    if (start === c) start = t; // no comment block above
    const comments = lines.slice(start, t).join("\n");
    let end = i + 1;
    while (end < lines.length && !/^\s*(#|@|Scenario|Rule:)/.test(lines[end])) end++;
    while (end > i + 1 && lines[end - 1].trim() === "") end--;
    out.push({
      start, end, title: m[1], indent: (lines[t] ?? l).match(/^\s*/)![0],
      tags: lines.slice(t, i).join(" ").split(/\s+/).filter(Boolean),
      body: lines.slice(i, end),
      // "# Goes under: [new ]Rule: <title>[ (<NN-slug>, beside …)]": another slice's Rule when named
      ...(() => { const g = comments.match(/#\s*Goes under:\s*(new )?Rule:\s*(.+?)\s*(?:\((\d+-[\w-]+)[^)]*\))?\s*$/m);
                  return { goesUnder: g?.[2] ?? null, newRule: !!g?.[1], intoSlice: g?.[3] ?? null }; })(),
      tier: comments.match(/#\s*Suggested tier:\s*(\w+)/)?.[1] ?? null,
    });
  });
  return out;
}

function acceptDraft(slice: string, title: string, tierArg?: string): CallToolResult {
  const dir = path.join(PLAN, slice);
  const draftsF = path.join(dir, "drafts.feature"), specF = path.join(dir, "spec.feature");
  if (!/^\d+-[\w-]+$/.test(slice) || !fs.existsSync(draftsF) || !fs.existsSync(specF)) return fail(`No slice ${slice} with a drafts.feature and spec.feature under .pi/model/plan.`);
  const dl = fs.readFileSync(draftsF, "utf8").split("\n");
  const d = parseDrafts(dl).find(x => x.title === title.trim());
  if (!d) return fail(`No draft titled "${title}" in ${slice}/drafts.feature.`);
  if (!d.tags.includes("@draft")) return fail(`"${title}" is not tagged @draft.`);
  if (!d.goesUnder) return fail(`The draft has no "# Goes under: Rule: …" comment; the product owner must say where it goes.`);
  const into = d.intoSlice ?? slice, intoF = path.join(PLAN, into, "spec.feature");
  if (!fs.existsSync(intoF)) return fail(`The draft goes under slice ${into}, which has no spec.feature.`);
  const sl = fs.readFileSync(intoF, "utf8").split("\n");
  let ruleAt = sl.findIndex(l => l.trim() === `Rule: ${d.goesUnder}`);
  if (ruleAt < 0 && !d.newRule) return fail(`${into}/spec.feature has no "Rule: ${d.goesUnder}".`);
  const created = ruleAt < 0;
  if (created) { // "new Rule:": a Rule section at the end of the feature
    while (sl.length && sl[sl.length - 1].trim() === "") sl.pop();
    sl.push("", `  Rule: ${d.goesUnder}`);
    ruleAt = sl.length - 1;
  }
  const tier = tierArg ?? d.tier;
  const before = coverageLine();
  // the scenario, @draft dropped, its tier first; the stamper adds @item on the line above
  let tags = d.tags.filter(t => t !== "@draft");
  if (tier && !tags.some(t => ["@minimum", "@balance", "@deferred"].includes(t))) tags = [`@${tier}`, ...tags];
  let next = sl.findIndex((l, i) => i > ruleAt && /^\s*Rule:/.test(l)); if (next < 0) next = sl.length;
  let at = next; while (at > ruleAt + 1 && sl[at - 1].trim() === "") at--;
  sl.splice(at, 0, "", d.indent + tags.join(" "), ...d.body);
  // remove the draft and its comment block, and the blank lines after it
  let end = d.end; while (end < dl.length - 1 && dl[end].trim() === "") end++;
  dl.splice(d.start, end - d.start);
  fs.writeFileSync(intoF, sl.join("\n") + (sl[sl.length - 1] === "" ? "" : "\n"));
  fs.writeFileSync(draftsF, dl.join("\n"));
  const st = run("bb", [path.join(ROOT, "harness/stamp_item_ids.clj")]);
  // put the stamped @item on the scenario's tag line, as the tag convention says
  const sl2 = fs.readFileSync(intoF, "utf8").split("\n");
  const si = sl2.findIndex(l => l.match(/^\s*Scenario(?: Outline)?:\s*(.*?)\s*$/)?.[1] === d.title);
  let item: string | null = null;
  // the stamper puts a lone @item line directly above the Scenario, below our tag line
  if (si >= 2 && /^\s*@item-\d+-\d+\s*$/.test(sl2[si - 1]) && sl2[si - 2].trim().startsWith("@")) {
    item = sl2[si - 1].trim();
    sl2.splice(si - 2, 2, d.indent + item + " " + sl2[si - 2].trim());
    fs.writeFileSync(intoF, sl2.join("\n"));
  } else item = sl2.slice(Math.max(0, si - 3), si).join(" ").match(/@item-\d+-\d+/)?.[0] ?? null;
  cache = null;
  const after = coverageLine();
  return text([
    `Accepted "${d.title}" into .pi/model/plan/${into}/spec.feature under ${created ? "a new " : ""}Rule: ${d.goesUnder}`,
    `- stamped: ${item ?? "(no @item found: check the stamper output)"}; tags: ${tags.join(" ")}`,
    `- removed from drafts.feature (lines ${d.start + 1}-${d.end})`,
    `- stamper: ${(st.stdout + st.stderr).trim().replace(/\n/g, "; ")}`,
    `- coverage before: ${before}`,
    `- coverage after:  ${after}`,
  ].join("\n"), { slice, into, title: d.title, item, rule: d.goesUnder, coverageBefore: before, coverageAfter: after });
}

// ---------- UI ----------
let uiCache: { key: string; html: string } | null = null;
function renderUi(): string {
  const threads = readThreads();
  const counts = new Map<string, number>();
  for (const t of threads) if (t.status === "open") counts.set(t.target, (counts.get(t.target) ?? 0) + 1);
  const key = fingerprint() + JSON.stringify([...counts]);
  if (uiCache?.key === key) return uiCache.html;
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "spec-review-ui-"));
  try {
    const out = path.join(tmp, "app.html"), tf = path.join(tmp, "threads.edn");
    // open-thread counts per target, as the renderer's --threads takes them: {"rule:Name" 2 …}
    fs.writeFileSync(tf, `{${[...counts].map(([k, n]) => `${JSON.stringify(k)} ${n}`).join(" ")}}`);
    const { cmd, args, cwd } = RENDER_APP(out, tf);
    const r = spawnSync(cmd, args, { cwd, encoding: "utf8", maxBuffer: 64 << 20 });
    let html: string;
    if (r.status === 0 && fs.existsSync(out)) html = fs.readFileSync(out, "utf8");
    else {
      // stub: the static page and the wiring script (no review controls), if app mode fails
      process.stderr.write(`spec-review: app-mode render failed (${r.status}): ${(r.stderr ?? "").slice(0, 2000)}\n`);
      const page = fs.readFileSync(STUB_PAGE, "utf8");
      const js = fs.readFileSync(APP_JS, "utf8").replace(/<\/script/gi, "<\\/script");
      html = page.replace(/<\/body>/i, () => `<script id="review-app">${js}</script></body>`);
    }
    uiCache = { key, html };
    return html;
  } finally { fs.rmSync(tmp, { recursive: true, force: true }); }
}

// ---------- server ----------
// Every tool call, with a timestamp, to stderr (never stdout: that is the stdio transport).
function logged<A extends unknown[], R>(name: string, fn: (...a: A) => R): (...a: A) => R {
  return (...a: A) => {
    process.stderr.write(`${new Date().toISOString()} tool ${name} ${JSON.stringify(a[0]).slice(0, 500)}\n`);
    return fn(...a);
  };
}

/** allowEdits: register spec_accept_draft (the one tool that edits the spec). */
export function createServer({ allowEdits = true } = {}): McpServer {
  const server = new McpServer({ name: "spec-review", version: "0.1.0" });
  const reg = server.registerTool.bind(server);
  (server as any).registerTool = (name: string, config: unknown, cb: (...a: unknown[]) => unknown) => reg(name, config as any, logged(name, cb) as any);
  const target = z.string().regex(/^(rule|obligation|scenario|draft):.+/).describe("rule:<Name>, obligation:<id>, scenario:<item-id> (e.g. 01-006) or draft:<slice>:<title>");
  const module = z.string().optional().describe("an Allium file name without .allium, e.g. regeneration-cycle");

  registerAppTool(server, "spec_review", {
    title: "Review the spec",
    description: "Open the event-model page for review (MCP Apps hosts show it; others get a text summary: coverage, drafts, open threads, top uncovered). Optional rule or module to open at.",
    inputSchema: { rule: z.string().optional().describe("a rule name, e.g. ApproveSelection"), module },
    annotations: { readOnlyHint: true },
    _meta: { ui: { resourceUri: UI_URI } },
  }, async ({ rule, module }) => {
    const spec = loadSpec(), threads = readThreads(), s = summary(spec, threads);
    const top = s.uncovered.length ? s.uncovered.map(id => `- ${id}`).join("\n") : "- none uncovered";
    return text([`Spec review (${spec.commit}${spec.dirty ? ", uncommitted changes" : ""})`, s.line,
      `drafts awaiting the product owner: ${s.drafts} · open threads: ${s.open}`, "Top uncovered:", top,
      rule ? `Opened at rule ${rule}.` : module ? `Opened at module ${module}.` : ""].join("\n"),
      { rule: rule ?? null, module: module ?? null, ...s });
  });

  registerAppResource(server, "Spec review: event-model page", UI_URI,
    { mimeType: RESOURCE_MIME_TYPE, description: "The event-model page with review controls", _meta: { ui: { csp: CSP, prefersBorder: false } } },
    async () => ({ contents: [{ uri: UI_URI, mimeType: RESOURCE_MIME_TYPE, text: renderUi(), _meta: { ui: { csp: CSP, prefersBorder: false } } }] }));

  server.registerTool("spec_rule_card", {
    title: "Rule card",
    description: "One Allium rule as a review card in Markdown: GIVEN, WHEN, ONLY IF (each requires with its obligation tag and coverage status), THEN, EXAMPLES (scenarios with item ids, drafts marked) and open threads.",
    inputSchema: { rule: z.string().describe("the rule name, e.g. ApproveSelection") },
    annotations: { readOnlyHint: true },
  }, async ({ rule }) => {
    const card = ruleCard(loadSpec(), rule, readThreads());
    return card ? text(card, { rule, markdown: card }) : fail(`No rule ${rule} in .pi/model/*.allium.`);
  });

  server.registerTool("spec_uncovered", {
    title: "Uncovered obligations",
    description: "Must-cover Allium obligations no scenario or draft covers, optionally for one module.",
    inputSchema: { module }, annotations: { readOnlyHint: true },
  }, async ({ module }) => {
    const spec = loadSpec();
    const os_ = spec.coverage.obligations.filter(o => !module || moduleOf(o) === module);
    const unc = os_.filter(o => o.status === "uncovered"), dr = os_.filter(o => o.status === "drafted");
    const body = unc.length ? unc.map(o => `- ${o.id}${o.clause ? `: \`${o.clause}\`` : ""} (${o.file})`).join("\n") : "None uncovered.";
    return text(`${module ?? "All modules"}: ${unc.length} uncovered, ${dr.length} covered only by drafts.\n${body}`,
      { uncovered: unc.map(o => o.id), draftedOnly: dr.map(o => o.id) });
  });

  server.registerTool("spec_drafts", {
    title: "Draft scenarios",
    description: "Draft scenarios awaiting the product owner (drafts.feature), with their obligations and the Rule each goes under, optionally for one module.",
    inputSchema: { module }, annotations: { readOnlyHint: true },
  }, async ({ module }) => {
    const spec = loadSpec();
    const ds = spec.scenarios.filter(s => s.kind === "drafts" && (!module || s.module === module || s.slice === module));
    const under = new Map<string, Draft>();
    for (const sl of new Set(ds.map(d => d.slice)))
      for (const d of parseDrafts(fs.readFileSync(path.join(PLAN, sl, "drafts.feature"), "utf8").split("\n"))) under.set(`${sl}:${d.title}`, d);
    const rows = ds.map(s => ({ slice: s.slice, title: s.title, obligations: oblTags(s), goesUnder: under.get(`${s.slice}:${s.title}`)?.goesUnder ?? null,
                                tier: under.get(`${s.slice}:${s.title}`)?.tier ?? null, target: `draft:${s.slice}:${s.title}` }));
    const body = rows.map(r => `- [${r.slice}] ${r.title}\n  ${r.obligations.join(" ")} · goes under: ${r.goesUnder ?? "?"} · tier: ${r.tier ?? "?"}`).join("\n");
    return text(`${rows.length} drafts${module ? ` in ${module}` : ""}.\n${body}`, { drafts: rows });
  });

  server.registerTool("spec_threads", {
    title: "Review threads",
    description: "Review threads from .pi/model/review.edn, optionally for one target.",
    inputSchema: { target: target.optional(), status: z.enum(["open", "resolved", "all"]).optional().describe("default all") },
    annotations: { readOnlyHint: true },
  }, async ({ target, status }) => {
    const ts = readThreads().filter(t => (!target || t.target === target) && (!status || status === "all" || t.status === status));
    return text(ts.length ? ts.map(threadText).join("\n") : "No threads.", { threads: ts });
  });

  server.registerTool("spec_comment", {
    title: "Comment",
    description: "Add a comment to the open thread on a target (or start one) in .pi/model/review.edn. Writes only the review store, never the spec.",
    inputSchema: { target, text: z.string().min(1), author: z.string().optional().describe("default: reviewer") },
    annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false },
  }, async ({ target, text: body, author }) => {
    const ts = readThreads();
    const entry = { author: author ?? "reviewer", at: new Date().toISOString(), text: body };
    let t = ts.find(t => t.target === target && t.status === "open");
    if (t) t.entries.push(entry);
    else {
      const n = Math.max(0, ...ts.map(t => Number(t.id.replace(/^t/, "")) || 0)) + 1;
      t = { id: `t${n}`, target, status: "open", entries: [entry] };
      ts.push(t);
    }
    writeThreads(ts);
    return text(`Comment added to ${t.id} on ${target}.\n${threadText(t)}`, { thread: t });
  });

  server.registerTool("spec_resolve_thread", {
    title: "Resolve thread",
    description: "Resolve a review thread with a closing note.",
    inputSchema: { id: z.string(), note: z.string().min(1), author: z.string().optional() },
    annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true },
  }, async ({ id, note, author }) => {
    const ts = readThreads(), t = ts.find(t => t.id === id);
    if (!t) return fail(`No thread ${id}.`);
    t.entries.push({ author: author ?? "reviewer", at: new Date().toISOString(), text: note });
    t.status = "resolved";
    writeThreads(ts);
    return text(`Resolved ${id}.\n${threadText(t)}`, { thread: t });
  });

  if (allowEdits) server.registerTool("spec_accept_draft", {
    title: "Accept draft (edits spec.feature)",
    description: "PRODUCT OWNER ONLY. Edits the spec: moves a @draft scenario from drafts.feature into spec.feature at the end of its '# Goes under: Rule:', drops @draft, keeps its @allium tags, adds its tier (the suggested one unless given), runs the stamper (harness/stamp_item_ids.clj) and coverage.py, and reports what changed.",
    inputSchema: { slice: z.string().describe("e.g. 01-regeneration-cycle"), title: z.string().describe("the draft's Scenario title"),
                   tier: z.enum(["minimum", "balance", "deferred"]).optional() },
    annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: false },
  }, async ({ slice, title, tier }) => acceptDraft(slice, title, tier));

  return server;
}

// Streamable HTTP, for remote connectors (claude.ai, Claude mobile): 127.0.0.1 only, one secret
// path /mcp/<SPEC_REVIEW_TOKEN>, stateless (a server per request). Everything else is a 404.
// spec_accept_draft is left out unless SPEC_REVIEW_ALLOW_EDITS=1.
export function startHttp(port: number, token: string, allowEdits: boolean) {
  const endpoint = `/mcp/${token}`;
  const httpServer = http.createServer(async (req, res) => {
    if ((req.url ?? "").split("?")[0] !== endpoint) { res.writeHead(404).end("Not found"); return; }
    const server = createServer({ allowEdits });
    const transport = new NodeStreamableHTTPServerTransport({ sessionIdGenerator: undefined });
    res.on("close", () => { transport.close().catch(() => {}); server.close().catch(() => {}); });
    try {
      await server.connect(transport);
      await transport.handleRequest(req, res);
    } catch (e) {
      process.stderr.write(`${new Date().toISOString()} error ${e}\n`);
      if (!res.headersSent) res.writeHead(500).end();
    }
  });
  httpServer.listen(port, "127.0.0.1", () =>
    process.stderr.write(`spec-review: http://127.0.0.1:${port}/mcp/<token> (edits ${allowEdits ? "ENABLED" : "off"})\n`));
  return httpServer;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === fs.realpathSync(process.argv[1])) {
  const i = process.argv.indexOf("--http");
  if (i < 0) await createServer().connect(new StdioServerTransport());
  else {
    const port = Number(process.argv[i + 1]);
    const token = process.env.SPEC_REVIEW_TOKEN ?? "";
    if (!Number.isInteger(port) || port <= 0) { console.error("usage: node dist/server.js --http <port>"); process.exit(2); }
    if (token.length < 16 || !/^[\w-]+$/.test(token)) {
      console.error("spec-review: HTTP mode needs SPEC_REVIEW_TOKEN (16+ characters of [A-Za-z0-9_-]); the URL path is the only secret.\n" +
                    "  export SPEC_REVIEW_TOKEN=$(openssl rand -hex 24)");
      process.exit(2);
    }
    startHttp(port, token, process.env.SPEC_REVIEW_ALLOW_EDITS === "1");
  }
}
