// The review wiring, inlined into the rendered page (--app-js). It adds no markup of its own
// beyond an inline comment box and a status line: the renderer draws the controls
// ([data-review-action] with [data-review-target], badges [data-threads-for]); this attaches
// them to the MCP Apps SDK: tools for comment and accept, the host message API for discuss.
import { App } from "@modelcontextprotocol/ext-apps";

type Thread = { id: string; target: string; status: string; entries: { author: string; at: string; text: string }[] };
const app = new App({ name: "spec-review", version: "0.1.0" });
const textOf = (r: { content?: { type: string; text?: string }[] }) => (r.content ?? []).map(c => c.text ?? "").join("\n");

function status(msg: string) {
  let el = document.getElementById("review-status");
  if (!el) {
    el = document.createElement("div");
    el.id = "review-status";
    el.setAttribute("role", "status");
    el.style.cssText = "position:fixed;bottom:12px;right:12px;max-width:420px;padding:8px 12px;border-radius:6px;background:#1f2a24;color:#fff;font:13px system-ui;white-space:pre-wrap;z-index:1000";
    document.body.appendChild(el);
  }
  el.textContent = msg;
  clearTimeout((el as any)._t);
  (el as any)._t = setTimeout(() => el!.remove(), 6000);
}

async function refreshBadges() {
  const r = await app.callServerTool({ name: "spec_threads", arguments: { status: "open" } });
  const threads = ((r.structuredContent as { threads?: Thread[] })?.threads) ?? [];
  document.querySelectorAll<HTMLElement>("[data-threads-for]").forEach(b => {
    const n = threads.filter(t => t.target === b.dataset.threadsFor).length;
    b.textContent = n ? String(n) : "";
    b.hidden = n === 0;
  });
}

function commentBox(btn: HTMLElement, target: string) {
  const old = btn.parentElement?.querySelector(".review-box");
  if (old) { old.remove(); return; }
  const box = document.createElement("form");
  box.className = "review-box";
  box.style.cssText = "display:flex;gap:6px;margin:6px 0;align-items:flex-start";
  box.innerHTML = '<textarea rows="3" style="flex:1;font:13px system-ui" placeholder="Comment"></textarea><button type="submit">Post</button>';
  btn.insertAdjacentElement("afterend", box);
  const ta = box.querySelector("textarea")!;
  ta.focus();
  box.addEventListener("submit", async e => {
    e.preventDefault();
    if (!ta.value.trim()) return;
    const r = await app.callServerTool({ name: "spec_comment", arguments: { target, text: ta.value } });
    status(textOf(r).split("\n")[0]);
    box.remove();
    await refreshBadges();
  });
}

async function discuss(target: string) {
  const [kind, ...rest] = target.split(":");
  const r = kind === "rule"
    ? await app.callServerTool({ name: "spec_rule_card", arguments: { rule: rest.join(":") } })
    : await app.callServerTool({ name: "spec_threads", arguments: { target } });
  const body = textOf(r);
  const res = await app.sendMessage({ role: "user", content: [{ type: "text", text: `Let's discuss ${target}.\n\n${body}` }] });
  status(res.isError ? "The host did not accept the message." : `Sent ${target} to the conversation.`);
}

async function accept(btn: HTMLElement, target: string) {
  // draft:<slice>:<title>; two clicks, since this edits spec.feature
  if (btn.dataset.armed !== "1") {
    btn.dataset.armed = "1";
    btn.dataset.label = btn.textContent ?? "";
    btn.textContent = "Confirm accept";
    setTimeout(() => { btn.dataset.armed = ""; btn.textContent = btn.dataset.label ?? "Accept"; }, 4000);
    return;
  }
  btn.dataset.armed = "";
  const m = target.match(/^draft:([^:]+):(.+)$/);
  if (!m) return status(`Not a draft target: ${target}`);
  const r = await app.callServerTool({ name: "spec_accept_draft", arguments: { slice: m[1], title: m[2] } });
  status(textOf(r));
  if (!r.isError) btn.textContent = "Accepted";
}

document.addEventListener("click", e => {
  const btn = (e.target as HTMLElement).closest<HTMLElement>("[data-review-action]");
  if (!btn) return;
  e.preventDefault();
  const target = btn.dataset.reviewTarget ?? "";
  const run = { comment: () => commentBox(btn, target), discuss: () => discuss(target), accept: () => accept(btn, target) }[btn.dataset.reviewAction ?? ""];
  Promise.resolve(run?.()).catch(err => status(String(err?.message ?? err)));
});

app.ontoolresult = r => {
  const s = (r.structuredContent ?? {}) as { rule?: string | null; module?: string | null };
  if (s.rule) location.hash = `rule-${s.rule}`;
  else if (s.module) location.hash = `mod-${s.module}`;
};
app.connect().then(refreshBadges).catch(err => console.error("spec-review:", err));
