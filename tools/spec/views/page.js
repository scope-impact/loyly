// The page's only script. Every view is already in the HTML; CSS shows the one the address
// targets. This draws the Mermaid diagrams of the view on screen (Mermaid cannot lay out a
// hidden one), and toggles a diagram between fit-to-width and actual size.
window.addEventListener("error", e => document.documentElement.setAttribute("data-js-error", String(e.message || e)));
function drawShown() {
  const t = document.getElementById(decodeURIComponent(location.hash.slice(1)));
  const v = t?.closest(".view") || document.getElementById("home");
  document.documentElement.dataset.view = v.id;
  if (!window.mermaid) return;
  window.mermaid.run({ nodes: v.querySelectorAll("pre.mermaid:not([data-processed])") })
    .catch(e => document.documentElement.setAttribute("data-js-error", "mermaid: " + (e?.message || e)));
}
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
document.addEventListener("click", e => { const b = e.target.closest(".mm .size"); if (b) mmSize(b); });
window.addEventListener("load", drawShown);   // after the address has picked its view
