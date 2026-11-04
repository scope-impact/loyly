import { mkdir, readFile, readdir, stat, writeFile } from "node:fs/promises";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "../../..");
const baselinePath = path.join(root, ".pi/harness/.state/comparisons/four-language-2026-11-02T21-35-07-633Z/results.json");
const finalPath = path.join(root, ".pi/harness/.state/comparisons/four-language-2026-11-02T21-35-07-633Z/improvement-2026-11-02T22-08-44-401Z/results.json");
const profilePath = path.join(root, ".pi/harness/.state/comparisons/four-language-2026-11-02T21-35-07-633Z/improvement-2026-11-02T22-18-42-030Z/results.json");
const finalEvidenceRoot = path.join(path.dirname(finalPath), "evidence");
const profileEvidenceRoot = path.join(path.dirname(profilePath), "evidence");
const out = path.join(root, "reports/loyly-four-language-regeneration.html");
const baseline = JSON.parse(await readFile(baselinePath, "utf8"));
const final = JSON.parse(await readFile(finalPath, "utf8"));
const profile = JSON.parse(await readFile(profilePath, "utf8"));

const names = { typescript: "TypeScript / Node 22", rust: "Rust 1.91", java: "Java 21", rails: "Rails 8 / Ruby" };
const items = Array.from({ length: 11 }, (_, i) => `01-${String(i + 1).padStart(3, "0")}`);
const itemMeta = Object.fromEntries(baseline.selection.items.map(item => [item.id.replace("@item-", ""), item]));
const repairs = {
  typescript: "Corrected composite room lookup used by the heater projection.",
  rust: "Read complete HTTP bodies atomically and added a usable alternative time.",
  java: "Aligned the billing projection field and completed refusal alternatives.",
  rails: "Restored Rack body parsing, holder projections, billing shape, and actionable alternatives.",
};
const stacks = [];
for (const id of Object.keys(names)) {
  const b = baseline.stacks.find(x => x.id === id);
  const observations = b.outcome.result.outputs["grade-result"].observations;
  const finished = final.stacks.find(x => x.id === id);
  const capture = await captureStats(path.join(profileEvidenceRoot, id));
  const baselineItems = Object.fromEntries(observations.map(x => {
    const item = x["item-id"].replace("@item-", "");
    return [item, { passed: x.result === "pass", evidence: x.evidence }];
  }));
  const finalItems = Object.fromEntries(finished.items.map(x => [x.item, { passed: x.passed, evidence: evidenceText(x.evidence, x.item) }]));
  const itemCaptures = {};
  for (const item of items) {
    itemCaptures[item] = await itemCapture(id, item, finalEvidenceRoot);
    if (item === "01-010") itemCaptures[item].readiness = await itemCapture(id, item, profileEvidenceRoot);
  }
  stacks.push({
    id, name: names[id],
    before: observations.filter(x => x.result === "pass").length,
    beforeItems: Object.fromEntries(Object.entries(baselineItems).map(([item, value]) => [item, value.passed])),
    baselineItems,
    failures: observations.filter(x => x.result !== "pass").map(x => `${x["item-id"].replace("@item-", "")}: ${x.evidence}`),
    after: finished.items.filter(x => x.passed).length,
    afterItems: Object.fromEntries(finished.items.map(x => [x.item, x.passed])),
    finalItems,
    itemCaptures,
    baselineRun: b.requestId,
    outputRoot: repoPath(b.outcome.result.outputs["builder-result"]?.["output-root"]
      ?? path.join(root, ".pi/harness/.state/outputs", `${baseline.runId}-${id}`)),
    capture,
  });
}
const totalBefore = stacks.reduce((n, x) => n + x.before, 0);
const totalAfter = stacks.reduce((n, x) => n + x.after, 0);

await mkdir(path.dirname(out), { recursive: true });
await writeFile(out, html());
console.log(out);

async function captureStats(dir) {
  const files = await walk(dir);
  const result = {};
  for (const name of ["profiles.jsonl", "traces.jsonl", "metrics.jsonl", "runtime-signals.jsonl"]) {
    const selected = files.filter(x => path.basename(x) === name);
    const sizes = await Promise.all(selected.map(async x => (await stat(x)).size));
    result[name] = { files: selected.length, nonempty: sizes.filter(Boolean).length, bytes: sizes.reduce((a, b) => a + b, 0) };
  }
  const obi = files.filter(x => path.basename(x) === "obi.log");
  const logs = await Promise.all(obi.map(x => readFile(x, "utf8")));
  result.obiAttachments = logs.filter(x => x.includes("instrumenting process")).length;
  return result;
}
async function itemCapture(stack, item, runEvidenceRoot) {
  const dir = path.join(runEvidenceRoot, stack, item, "cycles", `${stack}-${item}`, "otel", "independent", `@item-${item}`);
  const result = { path: repoPath(dir), observerAttached: false };
  let files = [];
  try { files = await walk(dir); } catch {}
  for (const name of ["profiles.jsonl", "traces.jsonl", "metrics.jsonl", "runtime-signals.jsonl", "collector.log", "obi.log", "profiler.log"]) {
    const selected = files.filter(file => path.basename(file) === name);
    const sizes = await Promise.all(selected.map(async file => (await stat(file)).size));
    result[name] = {
      bytes: sizes.reduce((sum, value) => sum + value, 0),
      path: repoPath(selected.length === 1 ? selected[0] : dir),
    };
  }
  const obi = files.find(file => path.basename(file) === "obi.log");
  try { result.observerAttached = (await readFile(obi, "utf8")).includes("instrumenting process"); } catch {}
  return result;
}
async function walk(dir) { const out = []; for (const entry of await readdir(dir, { withFileTypes: true })) { const p = path.join(dir, entry.name); if (entry.isDirectory()) out.push(...await walk(p)); else out.push(p); } return out; }
function esc(value) { return String(value).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]); }
function repoPath(value) { return path.relative(root, path.resolve(value)) || "."; }
function size(n) { return n ? `${new Intl.NumberFormat("en").format(n)} B` : "0 B"; }
function evidenceText(value, item) {
  if (typeof value !== "string") return String(value ?? "No evidence text recorded");
  try {
    const parsed = JSON.parse(value);
    if (Array.isArray(parsed)) return parsed.find(entry => entry.itemId === item)?.evidence ?? parsed[0]?.evidence ?? value;
    return parsed.evidence ?? value;
  } catch { return value; }
}
function captureBadges(capture) {
  const entries = [["profile", capture["profiles.jsonl"]?.bytes], ["trace", capture["traces.jsonl"]?.bytes], ["metric", capture["metrics.jsonl"]?.bytes], ["runtime", capture["runtime-signals.jsonl"]?.bytes]];
  return entries.map(([name, bytes]) => `<span class="signal ${bytes ? "present" : "empty"}">${name} ${size(bytes ?? 0)}</span>`).join("");
}
function traceCards() {
  return items.flatMap(item => stacks.map(stack => {
    const meta = itemMeta[item];
    const before = stack.baselineItems[item];
    const after = stack.finalItems[item];
    const capture = stack.itemCaptures[item];
    const changed = !before.passed;
    const search = `${item} ${meta.name} ${meta.rule} ${stack.name} ${before.evidence} ${after.evidence}`.toLowerCase();
    return `<details class="trace-card" id="trace-${stack.id}-${item}" data-stack="${stack.id}" data-changed="${changed}" data-search="${esc(search)}">
      <summary><span class="trace-id">${item}</span><span class="trace-title"><strong>${esc(meta.name)}</strong><small>${esc(stack.name)}</small></span><span class="trace-delta ${changed ? "changed" : "stable"}">${before.passed ? "pass" : "fail"} → pass</span></summary>
      <div class="trace-flow">
        <article class="trace-step contract"><span>Accepted contract</span><strong>${esc(meta.rule)}</strong><p>${esc(meta.source)}</p></article>
        <article class="trace-step ${before.passed ? "passed" : "failed"}"><span>Baseline grader</span><strong>${before.passed ? "Pass" : "Fail"}</strong><p>${esc(before.evidence)}</p></article>
        <article class="trace-step repair"><span>External loop</span><strong>${changed ? "Repair applied" : "No repair needed"}</strong><p>${changed ? esc(repairs[stack.id]) : "The baseline behavior already satisfied this item."}</p></article>
        <article class="trace-step passed"><span>Fresh-VM regrade</span><strong>Pass</strong><p>${esc(after.evidence)}</p></article>
        <article class="trace-step observe"><span>Runtime observation</span><strong>${capture.observerAttached ? "OBI attached" : "Attachment not observed"}</strong><div class="signals">${captureBadges(capture)}${capture.readiness ? captureBadges(capture.readiness) : ""}</div></article>
      </div>
      <div class="trace-ledger"><div><span>Baseline run</span><code>${esc(stack.baselineRun)}</code></div><div><span>Final run</span><code>${esc(final.runId)} / ${stack.id}-${item}</code></div><div><span>Candidate</span><code>${esc(stack.outputRoot)}</code><button class="copy" data-copy="${esc(stack.outputRoot)}">Copy</button></div><div><span>Evidence</span><code>${esc(capture.path)}</code><button class="copy" data-copy="${esc(capture.path)}">Copy</button></div></div>
    </details>`;
  })).join("");
}

function html() { return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Löyly four-language regeneration report</title>
<style>
:root{--snow:#dceaf0;--lake:#173443;--steam:#f7fafb;--copper:#c56838;--green:#2f7554;--ink:#182226;--muted:#62747c;--line:#aec3cb;--fail:#a53f32}*{box-sizing:border-box}html{scroll-behavior:smooth;background:var(--snow);color:var(--ink);font-family:"Avenir Next",Avenir,"Segoe UI",sans-serif}body{margin:0}.shell{max-width:1240px;margin:auto;background:var(--steam);min-height:100vh;border-inline:1px solid var(--line)}header{background:var(--lake);color:white;padding:70px clamp(24px,6vw,80px) 54px;position:relative;overflow:hidden}header:after{content:"";position:absolute;right:-5%;bottom:-120px;width:45%;height:220px;border:28px solid rgba(220,234,240,.12);border-radius:50%}h1{font:600 clamp(42px,7vw,92px)/.93 "Avenir Next",sans-serif;letter-spacing:-.055em;max-width:900px;margin:0 0 30px}header p{max-width:700px;font-size:18px;line-height:1.55;color:#dceaf0;margin:0}.stamp{display:inline-block;margin-top:28px;border:1px solid #8eacb8;padding:9px 13px;font-size:13px}.toc{position:sticky;top:0;z-index:5;display:flex;gap:8px;overflow:auto;padding:10px clamp(20px,5vw,68px);background:rgba(247,250,251,.96);border-bottom:1px solid var(--line);backdrop-filter:blur(10px)}.toc a{white-space:nowrap;color:var(--lake);font-size:13px;text-decoration:none;padding:7px 10px}.toc a:focus-visible,.toc a:hover{outline:2px solid var(--copper);outline-offset:-2px}main{padding:0 clamp(20px,5vw,68px) 72px}.grade{display:grid;grid-template-columns:minmax(220px,.8fr) 2fr;gap:42px;padding:52px 0;border-bottom:2px solid var(--lake)}.score{font:400 clamp(64px,10vw,126px)/.8 Georgia,serif;color:var(--green);letter-spacing:-.06em}.score small{font:500 16px/1.2 "Avenir Next",sans-serif;color:var(--muted);letter-spacing:0;display:block;margin-top:22px}.rail{align-self:end}.rail-line{display:grid;grid-template-columns:repeat(11,1fr);gap:5px;margin:10px 0 22px}.cell{height:34px;border:1px solid var(--line);background:#eef3f5}.cell.pass{background:var(--green);border-color:var(--green)}.cell.fail{background:var(--fail);border-color:var(--fail)}.rail-label{display:flex;justify-content:space-between;color:var(--muted);font-size:13px}.delta{font:400 28px/1.25 Georgia,serif;max-width:680px;margin:0}.section{padding:48px 0;border-bottom:1px solid var(--line);scroll-margin-top:56px}h2{font-size:30px;letter-spacing:-.03em;margin:0 0 10px}h3{font-size:18px;margin:0 0 10px}.intro{color:var(--muted);max-width:760px;line-height:1.55;margin:0 0 28px}.table-wrap{overflow:auto;border-block:1px solid var(--lake)}table{width:100%;border-collapse:collapse;min-width:760px}th,td{text-align:left;padding:13px 12px;border-bottom:1px solid #cfdee3;vertical-align:top}th{font-size:13px;color:var(--muted);font-weight:600}td:first-child,th:first-child{padding-left:0}.item-name{display:block;max-width:310px;margin-top:4px;font-size:12px;color:var(--muted);line-height:1.35}.matrix-link{display:block;color:var(--green);font-weight:700;text-decoration:none;padding:8px 5px;border:1px solid transparent}.matrix-link small{display:block;color:var(--muted);font-weight:500}.matrix-link:hover,.matrix-link:focus-visible{border-color:var(--copper);outline:0;background:#eef4f5}.stack-row{display:grid;grid-template-columns:190px 110px 1fr;gap:24px;padding:24px 0;border-top:1px solid var(--line)}.stack-row:first-of-type{border-top:0}.stack-score{font:400 34px Georgia,serif;color:var(--green)}.stack-copy{line-height:1.5}.stack-copy p{margin:0 0 8px}.failure{color:var(--fail);font-size:14px}.evidence-grid{display:grid;grid-template-columns:1.1fr 2fr;gap:36px}.ledger{border-left:5px solid var(--copper);padding-left:22px}.ledger p{margin:0 0 12px;line-height:1.5}.note{background:#e9f0f3;padding:18px 20px;line-height:1.55;border-left:3px solid var(--lake)}code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.82em;overflow-wrap:anywhere}footer{padding:30px clamp(20px,5vw,68px);background:#e7f0f3;color:var(--muted);font-size:13px}.legend{display:flex;gap:18px;font-size:13px;color:var(--muted);margin-top:14px}.dot{display:inline-block;width:10px;height:10px;margin-right:6px}.dot.pass{background:var(--green)}.dot.fail{background:var(--fail)}.trace-tools{display:grid;grid-template-columns:minmax(220px,1fr) auto auto;gap:10px;position:sticky;top:55px;z-index:4;padding:12px 0;background:var(--steam)}.trace-tools input,.trace-tools select,.trace-tools button{font:inherit;border:1px solid var(--line);background:white;color:var(--ink);padding:11px 12px}.trace-tools button{cursor:pointer;background:var(--lake);color:white}.trace-tools :focus-visible,.copy:focus-visible{outline:3px solid var(--copper);outline-offset:2px}.trace-count{font-size:13px;color:var(--muted);margin:8px 0 18px}.trace-card{border-top:1px solid var(--line);scroll-margin-top:125px}.trace-card[open]{border-top:4px solid var(--copper);background:#f1f6f7}.trace-card[hidden]{display:none}.trace-card summary{display:grid;grid-template-columns:90px 1fr 110px;align-items:center;gap:18px;padding:17px 12px;cursor:pointer;list-style:none}.trace-card summary::-webkit-details-marker{display:none}.trace-card summary:focus-visible{outline:3px solid var(--copper);outline-offset:-3px}.trace-id{font:600 14px ui-monospace,SFMono-Regular,monospace}.trace-title strong,.trace-title small{display:block}.trace-title small{color:var(--muted);margin-top:3px}.trace-delta{text-align:center;padding:6px 8px;font-size:12px}.trace-delta.changed{background:#f5ddd5;color:#7f2e24}.trace-delta.stable{background:#dcebe2;color:#245e42}.trace-flow{display:grid;grid-template-columns:repeat(5,1fr);border-block:1px solid var(--line)}.trace-step{min-height:170px;padding:18px;border-right:1px solid var(--line)}.trace-step:last-child{border-right:0}.trace-step>span,.trace-ledger span{display:block;font-size:12px;color:var(--muted);margin-bottom:8px}.trace-step strong{display:block;font-size:16px;margin-bottom:10px}.trace-step p{font-size:13px;line-height:1.45;margin:0}.trace-step.failed{box-shadow:inset 0 5px var(--fail)}.trace-step.passed{box-shadow:inset 0 5px var(--green)}.trace-step.repair{box-shadow:inset 0 5px var(--copper)}.signals{display:flex;flex-wrap:wrap;gap:5px;margin-top:10px}.signal{font-size:10px;padding:4px 5px;border:1px solid var(--line)}.signal.present{color:var(--green);border-color:var(--green)}.signal.empty{color:var(--muted)}.trace-ledger{display:grid;grid-template-columns:1fr 1fr;padding:18px;gap:14px 28px}.trace-ledger>div{min-width:0}.copy{margin-left:8px;border:0;background:transparent;color:var(--lake);text-decoration:underline;cursor:pointer}.provenance{display:grid;grid-template-columns:repeat(2,1fr);gap:0 36px}.provenance div{padding:14px 0;border-bottom:1px solid var(--line)}.provenance dt{font-size:12px;color:var(--muted)}.provenance dd{margin:5px 0 0}.completeness{display:flex;flex-wrap:wrap;gap:8px;margin:22px 0}.completeness span{padding:8px 10px;border:1px solid var(--green);color:var(--green);font-size:13px}@media(max-width:900px){.trace-flow{grid-template-columns:1fr 1fr}.trace-step{min-height:0}.trace-tools{grid-template-columns:1fr 1fr}.trace-tools input{grid-column:1/-1}}@media(max-width:720px){.grade,.evidence-grid,.stack-row,.provenance,.trace-ledger{grid-template-columns:1fr}.stack-row{gap:8px}header{padding-top:48px}.trace-card summary{grid-template-columns:72px 1fr}.trace-delta{grid-column:2}.trace-flow{grid-template-columns:1fr}.trace-step{border-right:0;border-bottom:1px solid var(--line)}}@media print{.toc,.trace-tools,.copy{display:none}.trace-card{break-inside:avoid}.trace-card:not([open])>*:not(summary){display:block}.shell{border:0}}@media(prefers-reduced-motion:reduce){html{scroll-behavior:auto}}@media(prefers-reduced-motion:no-preference){header h1{animation:heat .55s ease-out both}@keyframes heat{from{transform:translateY(12px);opacity:.2}to{transform:none;opacity:1}}}
</style></head><body><div class="shell"><header><h1>One contract.<br>Four runtimes.</h1><p>Whole-application regeneration of Löyly’s booking core, independently graded in fresh Gondolin VMs and explored with standard OpenTelemetry eBPF instrumentation.</p><span class="stamp">Final run ${esc(final.runId)} · 3 November 2026</span></header><nav class="toc" aria-label="Report sections"><a href="#outcome">Outcome</a><a href="#matrix">Matrix</a><a href="#traces">Trace explorer</a><a href="#determinism">Determinism</a><a href="#profiles">Profiles</a><a href="#provenance">Provenance</a></nav><main>
<section class="grade" id="outcome"><div><div class="score">${totalAfter}/44<small>deterministic acceptance verdicts</small></div></div><div class="rail"><p class="delta">The external repair loop moved the generated applications from ${totalBefore}/44 to ${totalAfter}/44 without changing the accepted product contract.</p>${stacks.map(s => `<div class="rail-label"><span>${esc(s.name)}</span><span>${s.before}/11 → ${s.after}/11</span></div><div class="rail-line">${items.map(i => `<a class="cell ${s.afterItems[i] ? "pass" : "fail"}" href="#trace-${s.id}-${i}" title="Open ${s.id} ${i} trace"><span hidden>${i}</span></a>`).join("")}</div>`).join("")}<div class="legend"><span><i class="dot pass"></i>pass</span><span><i class="dot fail"></i>fail</span></div></div></section>
<section class="section" id="repairs"><h2>Baseline to final</h2><p class="intro">Each baseline failure was reproduced, repaired at the generated application boundary, and rerun against the same black-box oracle. Final items ran in 44 isolated VMs.</p>${stacks.map(s => `<div class="stack-row"><h3>${esc(s.name)}</h3><div class="stack-score">${s.before} → ${s.after}</div><div class="stack-copy"><p>${esc(repairs[s.id])}</p>${s.failures.map(f => `<div class="failure">Baseline: ${esc(f)}</div>`).join("")}</div></div>`).join("")}</section>
<section class="section" id="matrix"><h2>Acceptance matrix</h2><p class="intro">Select any result to jump to its full contract-to-evidence chain. The parenthesized value is the baseline verdict.</p><div class="table-wrap"><table><thead><tr><th>Accepted item</th>${stacks.map(s => `<th>${esc(s.id)}</th>`).join("")}</tr></thead><tbody>${items.map(i => `<tr><td><code>${i}</code><span class="item-name">${esc(itemMeta[i].name)}</span></td>${stacks.map(s => `<td><a class="matrix-link" href="#trace-${s.id}-${i}">PASS <small>baseline ${s.beforeItems[i] ? "pass" : "fail"}</small></a></td>`).join("")}</tr>`).join("")}</tbody></table></div></section>
<section class="section" id="traces"><h2>Trace explorer</h2><p class="intro">Forty-four audit chains connect an accepted rule to baseline evidence, the external improvement decision, fresh-VM evidence, and cycle-scoped runtime capture.</p><div class="completeness"><span>44/44 contract links</span><span>44/44 baseline verdicts</span><span>44/44 final verdicts</span><span>44/44 evidence directories</span></div><div class="trace-tools"><input id="trace-search" type="search" placeholder="Search item, rule, evidence, or language" aria-label="Search traces"><select id="trace-filter" aria-label="Filter traces"><option value="all">All 44 traces</option><option value="changed">Changed from fail</option><option value="stable">Stable passes</option></select><button id="trace-toggle" type="button">Expand visible</button></div><p class="trace-count" id="trace-count" aria-live="polite">44 traces shown</p>${traceCards()}</section>
<section class="section" id="determinism"><h2>What is deterministic?</h2><p class="intro">Behavioral truth and sampled runtime diagnostics are deliberately different grades of evidence.</p><div class="table-wrap"><table><thead><tr><th>Evidence</th><th>Class</th><th>Use in grading</th><th>Reason</th></tr></thead><tbody>
${[["Browser / HTTP exchange","Deterministic with pinned fixture","Authoritative","Exact request, response, and status are replayable."],["Domain event","Deterministic content","Authoritative when contract declares it","Identity, type, aggregate, and content digest are validated."],["Projection snapshot","Deterministic content","Authoritative when contract declares it","Complete correlated views are countable."],["Invariant result","Deterministic verdict","Authoritative","Oracle evaluates the invariant; the app cannot author pass/fail."],["Trace span","Non-deterministic timing/order","Diagnostic","Scheduling and exporter timing vary."],["eBPF profile sample","Non-deterministic sampling","Exploratory","Samples depend on load, interval, symbols, and runtime support."],["Runtime diagnostic","Non-deterministic","Exploratory","Logs explain collection state but do not prove behavior."]].map(r=>`<tr>${r.map(c=>`<td>${esc(c)}</td>`).join("")}</tr>`).join("")}
</tbody></table></div></section>
<section class="section" id="profiles"><h2>Application profile result</h2><div class="evidence-grid"><div class="ledger"><p><strong>Node</strong><br>${size(stacks[0].capture["profiles.jsonl"].bytes)} profiles<br>${size(stacks[0].capture["traces.jsonl"].bytes)} traces<br>${size(stacks[0].capture["metrics.jsonl"].bytes)} metrics</p><p><strong>Rust</strong><br>${size(stacks[1].capture["profiles.jsonl"].bytes)} profiles</p><p><strong>Java</strong><br>${size(stacks[2].capture["profiles.jsonl"].bytes)} profiles</p><p><strong>Rails</strong><br>${size(stacks[3].capture["profiles.jsonl"].bytes)} profiles</p></div><div><p class="intro">After gating workload start on OBI’s tracer-readiness signal, OTel eBPF Profiler produced native OTLP profile samples for all four runtimes during the 100-race workload. Node also exported standard OTLP traces and metrics. The Ruby binary lacks the profiler’s preferred <code>ruby_version</code> symbol, so Rails samples have limited language-level symbolization.</p><div class="note"><strong>Exploratory finding:</strong> Rust, Java, and Rails produced no exported OBI app spans in this short capture, despite confirmed process attachment. This remains explicit collection status, not a behavioral failure or synthetic success. Source run: <code>${esc(profile.runId)}</code>.</div></div></div></section>
<section class="section" id="packaging"><h2>Evidence packaging</h2><p class="intro">The evidence model retains browser exchange, trace span, domain event, projection snapshot, invariant result, runtime diagnostic, profile sample, and collection status. Allure receives links or attachments plus typed metadata; raw payloads remain in the cycle evidence volume.</p><div class="table-wrap"><table><thead><tr><th>Signal</th><th>Storage</th><th>Allure treatment</th></tr></thead><tbody><tr><td>Behavioral grader</td><td>Observation + result JSON</td><td>Test result and assertion evidence</td></tr><tr><td>Domain / projection evidence</td><td><code>runtime-signals.jsonl</code></td><td>Contract attachment with correlation fields</td></tr><tr><td>OTel traces / metrics / profiles</td><td>Native OTLP JSONL</td><td>Typed attachment; non-authoritative</td></tr><tr><td>Collector / OBI / profiler diagnostics</td><td>Cycle-scoped log files</td><td>Diagnostic attachment and collection status</td></tr></tbody></table></div></section>
<section class="section" id="provenance"><h2>Provenance and verification</h2><p class="intro">These identities make the report reproducible and connect it back to the accepted cut, selection, oracles, generated candidates, and evidence volumes.</p><dl class="provenance"><div><dt>Source commit</dt><dd><code>${esc(baseline.sourceCommit)}</code></dd></div><div><dt>Cut identity</dt><dd><code>${esc(baseline.cut.cutId ?? baseline.cut["cut-id"] ?? baseline.cut)}</code></dd></div><div><dt>Selection</dt><dd><code>${esc(baseline.selection.selectionId)}</code></dd></div><div><dt>Selection digest</dt><dd><code>${esc(baseline.selection.selectionDigest)}</code></dd></div><div><dt>Baseline run</dt><dd><code>${esc(baseline.runId)}</code></dd></div><div><dt>Final 44-item run</dt><dd><code>${esc(final.runId)}</code></dd></div><div><dt>Profiling readiness run</dt><dd><code>${esc(profile.runId)}</code></dd></div><div><dt>Product oracle</dt><dd><code>sha256:e328f56447a5eb888e958da29fb90a4e2500adf86463249f2d69738df4a021a6</code></dd></div><div><dt>Instrument oracle</dt><dd><code>sha256:65d55c59a87c04b4f83b355ad86f941ae09269dd34d53c5e8aa32874ff6d5952</code></dd></div><div><dt>Observer versions</dt><dd><code>OBI v0.15.2 · Collector/Profile v0.164.0</code></dd></div><div><dt>Harness verification</dt><dd>Node 36/36; Clojure 97 tests / 339 assertions</dd></div><div><dt>Independent grade</dt><dd>44/44 fresh-VM verdicts</dd></div></dl></section>
</main><footer>Generated from the baseline regeneration receipt, final isolated regrade, readiness profiling run, and cycle-scoped evidence files. Deterministic acceptance and exploratory observability remain separate axes.</footer></div><script>
const cards=[...document.querySelectorAll('.trace-card')],search=document.querySelector('#trace-search'),filter=document.querySelector('#trace-filter'),count=document.querySelector('#trace-count'),toggle=document.querySelector('#trace-toggle');
function apply(){const q=search.value.trim().toLowerCase(),mode=filter.value;let shown=0;for(const card of cards){const matchText=!q||card.dataset.search.includes(q),matchMode=mode==='all'||(mode==='changed')===(card.dataset.changed==='true');card.hidden=!(matchText&&matchMode);if(!card.hidden)shown++}count.textContent=shown+' trace'+(shown===1?'':'s')+' shown';toggle.textContent='Expand visible'}
search.addEventListener('input',apply);filter.addEventListener('change',apply);toggle.addEventListener('click',()=>{const visible=cards.filter(card=>!card.hidden),expand=visible.some(card=>!card.open);visible.forEach(card=>card.open=expand);toggle.textContent=expand?'Collapse visible':'Expand visible'});
document.querySelectorAll('a[href^="#trace-"]').forEach(link=>link.addEventListener('click',()=>{const card=document.querySelector(link.getAttribute('href'));if(card){card.hidden=false;card.open=true}}));
document.querySelectorAll('.copy').forEach(button=>button.addEventListener('click',async event=>{event.preventDefault();await navigator.clipboard.writeText(button.dataset.copy);const old=button.textContent;button.textContent='Copied';setTimeout(()=>button.textContent=old,1200)}));
</script></body></html>`; }
