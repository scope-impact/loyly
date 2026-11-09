// The page's only script. Every view is already in the HTML; CSS shows the one the address
// targets. This draws the Mermaid diagrams of the view on screen (Mermaid cannot lay out a
// hidden one), draws the event-storm arrows (they depend on where the browser placed each
// cluster), and toggles a diagram (or the event storm on a phone) between fit-to-width and actual size.
window.addEventListener("error", e => document.documentElement.setAttribute("data-js-error", String(e.message || e)));

const escText = t => String(t).replace(/[&<>"]/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
function drawStorms(root) {
  root.querySelectorAll(".storm").forEach(board => {
    const flow = board.querySelector(".storm-flow"), svg = board.querySelector(".storm-svg"); if (!flow || !svg) return;
    const edges = JSON.parse(board.dataset.edges || "[]"), base = flow.getBoundingClientRect();
    svg.setAttribute("width", flow.scrollWidth); svg.setAttribute("height", flow.scrollHeight);
    const box = n => { const cl = flow.querySelector(`.clu[data-rule="${CSS.escape(n)}"]`); return cl && { e: cl.querySelector(".evt-spot").getBoundingClientRect(), c: cl.querySelector(".cmd-spot").getBoundingClientRect() }; };
    const col = { state: "#2d3833", event: "#6b3fc4", policy: "#6b3fc4" };
    let out = `<defs>${Object.entries(col).map(([k, v]) => `<marker id="ah-${k}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="${v}"/></marker>`).join("")}</defs>`;
    edges.forEach((ed, i) => { const A = box(ed.a), B = box(ed.b); if (!A || !B) return;
      const x1 = A.e.right - base.left, y1 = A.e.top + A.e.height / 2 - base.top, x2 = B.c.left - base.left, y2 = B.c.top + B.c.height / 2 - base.top;
      const dx = Math.max(40, Math.abs(x2 - x1) / 2), arc = ed.skip > 1 || x2 <= x1;
      const top = Math.min(A.c.top, B.c.top) - base.top - 26 - (i % 4) * 12;
      const d = arc ? `M${x1},${y1} C${x1 + 50},${top} ${x2 - 50},${top} ${x2},${y2}` : `M${x1},${y1} C${x1 + dx},${y1} ${x2 - dx},${y2} ${x2},${y2}`;
      const mx = (x1 + x2) / 2, my = arc ? top + 8 : (y1 + y2) / 2 - 7;
      out += `<path d="${d}" fill="none" stroke="${col[ed.kind]}" stroke-width="2.2" ${ed.kind === "state" ? "" : 'stroke-dasharray="7 5"'} marker-end="url(#ah-${ed.kind})"/>`;
      if (ed.lbl) { const first = ed.lbl.split(" · ")[0], shown = first.replace(/^[A-Z]\w*\./, "");
        out += `<text x="${mx}" y="${my}" text-anchor="middle" class="storm-lbl ${ed.kind}"><title>${escText(ed.lbl)}</title>${escText(shown.length > 24 ? shown.slice(0, 23) + "…" : shown)}</text>`; } });
    svg.innerHTML = out;
  });
}
// Mermaid cannot lay out a hidden diagram: draw those on screen now, and mark the ones folded
// away under a closed <details> (under the hood) as deferred; they are drawn when it opens.
function drawMermaid(root) {
  const all = [...root.querySelectorAll("pre.mermaid:not([data-processed])")];
  const shut = p => { for (let d = p.closest("details"); d; d = d.parentElement?.closest("details")) if (!d.open) return true; return false; };
  all.forEach(p => p.toggleAttribute("data-deferred", shut(p)));
  const now = all.filter(p => !p.hasAttribute("data-deferred"));
  if (!window.mermaid || !now.length) return;
  window.mermaid.run({ nodes: now }).catch(e => document.documentElement.setAttribute("data-js-error", "mermaid: " + (e?.message || e)));
}
function drawShown() {
  const t = document.getElementById(decodeURIComponent(location.hash.slice(1)));
  const v = t?.closest(".view") || document.getElementById("home");
  document.documentElement.dataset.view = v.id;
  // a target folded under the hood (a refusal, a rule card's source) opens its <details>
  for (let d = t?.parentElement?.closest("details"); d; d = d.parentElement?.closest("details")) d.open = true;
  if (t && t !== v) requestAnimationFrame(() => t.scrollIntoView({ block: "start" }));
  requestAnimationFrame(() => drawStorms(v));
  drawMermaid(v);
}
document.addEventListener("toggle", e => { const d = e.target; if (d.tagName === "DETAILS" && d.open) { drawMermaid(d); requestAnimationFrame(() => drawStorms(d)); } }, true);
function mmSize(btn) {
  const m = btn.parentElement, svg = m.querySelector("svg"); if (!svg) return;
  const on = m.classList.toggle("full"), w = svg.viewBox?.baseVal?.width;
  if (svg.dataset.mw === undefined) svg.dataset.mw = svg.style.maxWidth || "";
  svg.style.maxWidth = on ? "none" : svg.dataset.mw;
  svg.style.width = on && w ? w + "px" : "";
  svg.style.maxHeight = on ? "none" : "";
  btn.setAttribute("aria-pressed", String(on));
  btn.textContent = on ? "Fit to width" : "Actual size";
}
window.mermaid?.initialize({ startOnLoad: false, securityLevel: "strict", theme: "base", flowchart: { htmlLabels: true, curve: "basis", useMaxWidth: true }, state: { useMaxWidth: true }, themeVariables: { fontFamily: "Instrument Sans, system-ui, sans-serif", fontSize: "13px" } });
window.addEventListener("hashchange", drawShown);
// The event storm on a narrow screen: scroll it in its frame, or fit it to the screen.
function storyFit(btn) {
  const f = btn.closest(".storm-sec").querySelector(".story-frame"), on = f.classList.toggle("fit");
  btn.setAttribute("aria-pressed", String(on));
  btn.textContent = on ? "Actual size" : "Fit to screen";
  btn.previousElementSibling.textContent = on ? "Fitted to the screen: zoom in, or show it at actual size." : "Time runs left to right: scroll sideways.";
}
document.addEventListener("click", e => { const b = e.target.closest(".mm .size"); if (b) mmSize(b); const s = e.target.closest(".story-fit"); if (s) storyFit(s); });
window.addEventListener("load", drawShown);   // after the address has picked its view
window.addEventListener("resize", () => { const v = document.getElementById(document.documentElement.dataset.view || "home"); if (v) drawStorms(v); });
