// Smoke test over stdio with the MCP SDK client. Reads the real spec; writes only a temporary
// review store and a temporary git worktree (for spec_accept_draft), both removed at the end.
//   node test/smoke.mjs        (after npm run build)
import { Client } from "@modelcontextprotocol/client";
import { StdioClientTransport } from "@modelcontextprotocol/client/stdio";
import { execFileSync } from "node:child_process";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const MCP = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const ROOT = execFileSync("git", ["-C", MCP, "rev-parse", "--show-toplevel"], { encoding: "utf8" }).trim();
const TMP = fs.mkdtempSync(path.join(process.env.TMPDIR ?? os.tmpdir(), "spec-review-smoke-"));
const STORE = path.join(TMP, "review.edn");
const WT = path.join(TMP, "worktree");
const ok = (msg) => console.log("ok  " + msg);
const textOf = (r) => r.content.map((c) => c.text).join("\n");

async function connect(env) {
  const client = new Client({ name: "smoke", version: "0" });
  await client.connect(new StdioClientTransport({ command: "node", args: [path.join(MCP, "dist/server.js")], env: { ...process.env, ...env } }));
  return client;
}

try {
  const c = await connect({ SPEC_REVIEW_STORE: STORE });
  const { tools } = await c.listTools();
  const names = tools.map((t) => t.name).sort();
  ok("tools: " + names.join(", "));
  const review = tools.find((t) => t.name === "spec_review");
  const uri = review._meta?.ui?.resourceUri;
  assert.match(uri, /^ui:\/\//);
  ok("spec_review _meta.ui.resourceUri = " + uri);
  const accept = tools.find((t) => t.name === "spec_accept_draft");
  assert.equal(accept.annotations.readOnlyHint, false);
  assert.equal(accept.annotations.destructiveHint, true);
  ok("spec_accept_draft annotated as an edit (readOnlyHint false, destructiveHint true)");

  const res = await c.readResource({ uri });
  const content = res.contents[0];
  assert.equal(content.mimeType, "text/html;profile=mcp-app");
  assert.ok(content.text.length > 100000 && content.text.includes('id="review-app"'));
  assert.ok(content.text.includes("ApproveSelection"));
  const controls = (content.text.match(/data-review-action=/g) ?? []).length;
  assert.ok(controls > 0, "the renderer's app mode drew no review controls");
  ok(`resource: ${content.mimeType}, ${content.text.length} chars, ${controls} review controls, review-app script; csp ${JSON.stringify(content._meta?.ui?.csp)}`);

  const sr = await c.callTool({ name: "spec_review", arguments: { rule: "ApproveSelection" } });
  ok("spec_review text:\n" + textOf(sr).replace(/^/gm, "      "));

  const card = await c.callTool({ name: "spec_rule_card", arguments: { rule: "ApproveSelection" } });
  assert.ok(!card.isError && /ONLY IF/.test(textOf(card)) && /01-006/.test(textOf(card)));
  ok("spec_rule_card ApproveSelection:\n" + textOf(card).replace(/^/gm, "      "));

  const unc = await c.callTool({ name: "spec_uncovered", arguments: {} });
  ok("spec_uncovered: " + textOf(unc).split("\n")[0]);
  const dr = await c.callTool({ name: "spec_drafts", arguments: { module: "regeneration-cycle" } });
  assert.ok(dr.structuredContent.drafts.length > 0);
  ok("spec_drafts regeneration-cycle: " + textOf(dr).split("\n").slice(0, 3).join(" | "));

  const cm = await c.callTool({ name: "spec_comment", arguments: { target: "rule:ApproveSelection", text: "Is the digest the only binding?", author: "smoke" } });
  const id = cm.structuredContent.thread.id;
  await c.callTool({ name: "spec_comment", arguments: { target: "rule:ApproveSelection", text: "Second entry on the same open thread." } });
  const th = await c.callTool({ name: "spec_threads", arguments: { target: "rule:ApproveSelection" } });
  assert.equal(th.structuredContent.threads[0].entries.length, 2);
  const rs = await c.callTool({ name: "spec_resolve_thread", arguments: { id, note: "Yes: the digest names the selection." } });
  assert.equal(rs.structuredContent.thread.status, "resolved");
  ok(`spec_comment x2 + spec_threads + spec_resolve_thread (${id}); store:\n` + fs.readFileSync(STORE, "utf8").replace(/^/gm, "      "));
  const card2 = await c.callTool({ name: "spec_rule_card", arguments: { rule: "ApproveSelection" } });
  assert.match(textOf(card2), /OPEN THREADS\*\* \(0\)/);
  await c.close();

  // spec_accept_draft, on a temporary worktree only
  execFileSync("git", ["-C", ROOT, "worktree", "add", "--detach", WT, "HEAD"], { stdio: "ignore" });
  const w = await connect({ SPEC_REVIEW_ROOT: WT, SPEC_REVIEW_STORE: STORE });
  const title = "Approving the reviewed digest approves the selection contract";
  const acc = await w.callTool({ name: "spec_accept_draft", arguments: { slice: "01-regeneration-cycle", title } });
  assert.ok(!acc.isError, textOf(acc));
  ok("spec_accept_draft (worktree):\n" + textOf(acc).replace(/^/gm, "      "));
  const diff = execFileSync("git", ["-C", WT, "diff", "--", ".pi/model/plan"], { encoding: "utf8" });
  assert.match(diff, /\+\s+@item-01-\d{3} @balance @allium-rule-success\.ApproveSelection/);
  ok("worktree diff:\n" + diff.replace(/^/gm, "      "));
  const again = await w.callTool({ name: "spec_accept_draft", arguments: { slice: "01-regeneration-cycle", title } });
  assert.ok(again.isError);
  ok("accepting it again is refused: " + textOf(again));
  // a draft that goes under another slice's Rule, and one that opens a new Rule
  for (const [slice, t, into] of [["01-regeneration-cycle", "A cycle preparation with no outer-cycle budget is refused", "04-operator-control-plane"],
                                   ["02-execution-pipeline", "A cycle whose complete measurement failed no item passes and names its candidate", "02-execution-pipeline"]]) {
    const r = await w.callTool({ name: "spec_accept_draft", arguments: { slice, title: t } });
    assert.ok(!r.isError, textOf(r));
    assert.equal(r.structuredContent.into, into);
    ok(textOf(r).split("\n").slice(0, 2).join(" | "));
  }
  await w.close();
  // the real spec is untouched
  assert.equal(execFileSync("git", ["-C", ROOT, "status", "--porcelain", "--", ".pi/model/plan"], { encoding: "utf8" }), "");
  ok("real .pi/model/plan untouched");
} finally {
  if (fs.existsSync(WT)) execFileSync("git", ["-C", ROOT, "worktree", "remove", "--force", WT]);
  fs.rmSync(TMP, { recursive: true, force: true });
}
