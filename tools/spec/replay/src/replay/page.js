// The replay page's only script. The page is built at the run's end state; Replay rewinds and
// plays the merged events of every store in time order. Real time is compressed: a gap under a
// second plays as it was, a longer one grows only with its logarithm (the builder's minutes take
// a couple of seconds), and each new sticky gets a beat so the eye can follow it.
window.addEventListener("error", e => document.documentElement.setAttribute("data-js-error", String(e.message || e)));
const D = JSON.parse(document.getElementById("data").textContent);
const E = D.events, N = E.length, $ = id => document.getElementById(id);
const leads = new Set(D.items.map(it => it.at));
const R = new Float64Array(N);
for (let i = 1; i < N; i++) {
  const g = E[i].t - E[i - 1].t;
  let r = g <= 1000 ? g : 1000 + Math.log10(g / 1000) * 700;
  if (leads.has(i)) r = Math.max(r, 260);
  R[i] = R[i - 1] + r;
}
const dur = ms => { const s = Math.floor(ms / 1000); return ms < 1000 ? `${ms} ms` : s < 60 ? `${s} s` : s < 3600 ? `${Math.floor(s / 60)} min ${s % 60} s` : `${Math.floor(s / 3600)} h ${Math.floor(s % 3600 / 60)} min`; };
const clock = ms => { const s = Math.floor(ms / 1000); return `+${String(Math.floor(s / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`; };
const esc = t => String(t ?? "").replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
const stageEls = D.stages.map(s => ({ ...s, el: $("stage-" + s.id) }));
const itemEls = [...document.querySelectorAll(".item")];
const laneName = Object.fromEntries(D.lanes.map(l => [l.id, l.name]));

let pos = N - 1, vt = R[N - 1], playing = false, last = 0;
function render() {
  const reached = stageEls.filter(s => s.at >= 0 && s.at <= pos);
  const cur = reached.length ? reached.reduce((a, b) => (b.at >= a.at ? b : a)) : null;
  for (const s of stageEls) {
    if (s.at < 0) continue;
    s.el.classList.toggle("pending", s.at > pos);
    s.el.classList.toggle("current", s === cur && pos < N - 1);
    s.el.classList.toggle("done", s.at <= pos && (s !== cur || pos === N - 1));
  }
  let now = -1;
  D.items.forEach((it, i) => { if (it.at <= pos && (now < 0 || it.at >= D.items[now].at)) now = i; });
  itemEls.forEach((el, i) => { el.classList.toggle("future", D.items[i].at > pos); el.classList.toggle("now", i === now && pos < N - 1); });
  const e = E[pos], it = D.items[now], g = pos > 0 ? e.t - E[pos - 1].t : 0;
  $("now").innerHTML = (g > 30000 ? `<span class="gap">⏩ ${dur(g)} of waiting compressed. </span>` : "") +
    `${clock(e.t)} · event ${pos + 1} of ${N} · ${esc(laneName[e.l])}: ${esc(e.ty)}` + (it ? ` · ${esc(it.label)}` : "");
  $("scrub").value = pos;
  document.documentElement.toggleAttribute("data-replay-end", pos === N - 1);
}
function tick(ts) {
  if (!playing) return;
  const dt = last ? ts - last : 0; last = ts;
  vt += dt * Number($("speed").value);
  while (pos < N - 1 && R[pos + 1] <= vt) pos++;
  render();
  if (pos >= N - 1) return stop();
  requestAnimationFrame(tick);
}
function play() { if (pos >= N - 1) { pos = 0; vt = 0; } playing = true; last = 0; $("play").textContent = "❚❚ Pause"; $("play").setAttribute("aria-label", "Pause"); requestAnimationFrame(tick); }
function stop() { playing = false; $("play").textContent = pos >= N - 1 ? "▶ Replay" : "▶ Play"; $("play").setAttribute("aria-label", "Play"); }
function jump(p) { pos = Math.max(0, Math.min(N - 1, p)); vt = R[pos]; render(); }
$("play").addEventListener("click", () => (playing ? stop() : play()));
$("step").addEventListener("click", () => { stop(); if (pos >= N - 1) { jump(0); return; } const nx = D.items.map(it => it.at).filter(a => a > pos).sort((a, b) => a - b)[0]; jump(nx ?? N - 1); });
$("back").addEventListener("click", () => { stop(); const pv = D.items.map(it => it.at).filter(a => a < pos).sort((a, b) => b - a)[0]; jump(pv ?? 0); });
$("scrub").addEventListener("input", e => { stop(); jump(Number(e.target.value)); });

// a sticky's payload, its ORC tick and its Grain bookkeeping
const pre = d => `<pre class="code">${esc(JSON.stringify(d, null, 2))}</pre>`;
const evDetails = i => `<details><summary>${esc(E[i].ty)} <span class="muted">${clock(E[i].t)}</span></summary>${pre({ ...E[i].d, "event/tags": E[i].tags })}</details>`;
function open(i) {
  const it = D.items[i], main = new Set(it.mainx), rest = it.members.filter(m => !main.has(m));
  const orc = rest.filter(m => E[m].ty.startsWith("sheet/")), grain = rest.filter(m => E[m].ty.startsWith("grain/"));
  const mains = it.mainx.map(m => E[m]);
  const body = mains.length === 1 ? pre(mains[0].d) : mains.length ? `<details open><summary>${mains.length} events of this type</summary><div>${it.mainx.map(evDetails).join("")}</div></details>` : "";
  const ticks = new Set(orc.map(m => E[m].d["tick-id"]).filter(Boolean));
  $("detail").querySelector(".dlg").innerHTML = `
    <div class="top"><h3>${esc(it.label)}${it.n > 1 ? ` ×${it.n}` : ""}</h3><button type="button" class="close">Close</button></div>
    <div class="meta"><span>${esc(laneName[it.lane])} store</span><span>${clock(E[it.at].t)}</span>${it.type ? `<span>${esc(it.type)}</span>` : ""}
      ${it.kind === "relay" ? "<span>a relayed copy, handed in by the cycle runner</span>" : ""}${it.cmd ? `<span>produced by ${esc(it.cmd)}</span>` : ""}</div>
    ${it.desc ? `<p>${esc(it.desc)}</p>` : ""}
    ${it.href ? `<p><a href="${esc(it.href)}">This event on the event model ↗</a></p>` : ""}
    ${body}
    ${orc.length ? `<details><summary>ORC workflow${it.sheet ? ` ${esc(it.sheet)}` : ""}: ${ticks.size ? `${ticks.size} tick${ticks.size > 1 ? "s" : ""}, ` : ""}${orc.length} events</summary><div>${orc.map(evDetails).join("")}</div></details>` : ""}
    ${grain.length ? `<details><summary>Grain bookkeeping: ${grain.length} transactions and processor checkpoints</summary><div>${grain.map(evDetails).join("")}</div></details>` : ""}`;
  $("detail").showModal();
}
document.addEventListener("click", e => {
  const b = e.target.closest(".item"); if (b) open(Number(b.dataset.item));
  if (e.target.closest(".dlg .close") || e.target === $("detail")) $("detail").close();
});
render();
