// Render every view of the page headless (a stub DOM in node) and fail on an exception, an
// empty view, or an undefined/NaN in the HTML. Also writes every Mermaid diagram to <dir>
// for check.sh to render.   node smoke.mjs <page.html> [dir]
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
const html = readFileSync(process.argv[2], "utf8");
const dir = process.argv[3];
const js = html.split("<script>")[1].split("</script>")[0];
const els = {};
const get = (id) => (els[id] ||= { innerHTML: "" });
let hash = "";
globalThis.document = { getElementById: get };
globalThis.location = { get hash() { return hash; } };
globalThis.window = { addEventListener() {}, scrollTo() {} };
const api = new Function(js + "\nreturn {route, ROUTES};")();
const hashes = api.ROUTES();
const un = (s) => s.replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&amp;/g, "&");
let n = 0;
const seen = new Set();
if (dir) mkdirSync(dir, { recursive: true });
for (const h of hashes) {
  hash = "#" + h;
  try { api.route(); } catch (e) { throw new Error(`view #${h} threw: ${e.stack}`); }
  const v = get("view").innerHTML;
  if (!v.length || /undefined|NaN|\[object Object\]/.test(v))
    throw new Error("bad view #" + h + " " + (v.match(/.{60}(undefined|NaN|\[object Object\]).{40}/) || ["empty"])[0]);
  if (dir)
    [...v.matchAll(/<pre class="mermaid">([\s\S]*?)<\/pre>/g)].forEach((m) => {
      const src = un(m[1]); if (seen.has(src)) return; seen.add(src);
      writeFileSync(`${dir}/${(h || "home").replace(/[^\w-]/g, "_")}-${n}.mmd`, src); n++;
    });
}
console.log(`views ${hashes.length} ok` + (dir ? `, distinct diagrams ${n}` : ""));
