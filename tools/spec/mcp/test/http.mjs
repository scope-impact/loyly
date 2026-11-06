// HTTP mode test: spawns `node dist/server.js --http <port>` on 127.0.0.1 and drives it with the
// SDK's Streamable HTTP client. No tunnel. Checks the token path works, other paths 404,
// spec_accept_draft is absent without SPEC_REVIEW_ALLOW_EDITS=1, the server refuses to start
// without a token, and tool calls are logged to stderr.   node test/http.mjs
import { Client } from "@modelcontextprotocol/client";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/client";
import { spawn, spawnSync } from "node:child_process";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const MCP = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const SERVER = path.join(MCP, "dist/server.js");
const TMP = fs.mkdtempSync(path.join(process.env.TMPDIR ?? os.tmpdir(), "spec-review-http-"));
const TOKEN = "test-" + Math.random().toString(36).slice(2) + Math.random().toString(36).slice(2);
const ok = (m) => console.log("ok  " + m);

const noToken = spawnSync("node", [SERVER, "--http", "39999"], { env: { ...process.env, SPEC_REVIEW_TOKEN: "" }, encoding: "utf8" });
assert.equal(noToken.status, 2);
ok("refuses to start without a token: " + noToken.stderr.split("\n")[0]);

async function start(env) {
  const port = 40000 + Math.floor(Math.random() * 10000);
  const p = spawn("node", [SERVER, "--http", String(port)], {
    env: { ...process.env, SPEC_REVIEW_TOKEN: TOKEN, SPEC_REVIEW_STORE: path.join(TMP, "review.edn"), ...env } });
  let err = "";
  p.stderr.on("data", (d) => (err += d));
  await new Promise((r) => { const t = setInterval(() => { if (err.includes("spec-review: http")) { clearInterval(t); r(); } }, 50); });
  return { p, port, log: () => err };
}

const tools = async (url) => {
  const c = new Client({ name: "http-test", version: "0" });
  await c.connect(new StreamableHTTPClientTransport(new URL(url)));
  return c;
};

let s;
try {
  s = await start({});
  const base = `http://127.0.0.1:${s.port}`;
  const c = await tools(`${base}/mcp/${TOKEN}`);
  const names = (await c.listTools()).tools.map((t) => t.name).sort();
  assert.ok(!names.includes("spec_accept_draft"));
  assert.ok(names.includes("spec_comment") && names.includes("spec_review"));
  ok("token path: " + names.join(", ") + " (no spec_accept_draft)");
  const card = await c.callTool({ name: "spec_rule_card", arguments: { rule: "ApproveSelection" } });
  assert.match(card.content[0].text, /ONLY IF/);
  ok("spec_rule_card over HTTP");
  const res = await c.readResource({ uri: "ui://spec-review/event-model.html" });
  assert.equal(res.contents[0].mimeType, "text/html;profile=mcp-app");
  ok(`ui:// resource over HTTP: ${res.contents[0].text.length} chars`);
  const acc = await c.callTool({ name: "spec_accept_draft", arguments: { slice: "01-regeneration-cycle", title: "x" } }).catch((e) => ({ isError: true, content: [{ text: String(e.message) }] }));
  assert.ok(acc.isError);
  ok("spec_accept_draft call refused: " + acc.content[0].text.split("\n")[0]);
  await c.close();

  for (const p of ["/mcp", "/mcp/wrong-token", `/mcp/${TOKEN}/x`, "/"]) {
    const r = await fetch(base + p, { method: "POST", headers: { "content-type": "application/json", accept: "application/json, text/event-stream" },
      body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/list" }) });
    assert.equal(r.status, 404, p);
  }
  ok("other paths 404: /mcp, /mcp/wrong-token, /mcp/<token>/x, /");
  assert.match(s.log(), /^\d{4}-\d\d-\d\dT[\d:.]+Z tool spec_rule_card \{"rule":"ApproveSelection"\}$/m);
  ok("stderr log: " + s.log().split("\n").filter((l) => l.includes(" tool ")).join(" | "));
  s.p.kill();

  s = await start({ SPEC_REVIEW_ALLOW_EDITS: "1" });
  const e = await tools(`http://127.0.0.1:${s.port}/mcp/${TOKEN}`);
  assert.ok((await e.listTools()).tools.some((t) => t.name === "spec_accept_draft"));
  ok("SPEC_REVIEW_ALLOW_EDITS=1 registers spec_accept_draft");
  await e.close();
} finally {
  s?.p.kill();
  fs.rmSync(TMP, { recursive: true, force: true });
}
