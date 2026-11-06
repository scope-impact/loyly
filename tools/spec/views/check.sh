#!/usr/bin/env bash
# Check the page the way a reader sees it.
#   1. smoke.mjs: every view (each aggregate, block and rule address) renders in node with no
#      exception, no empty view and no undefined/NaN; it writes out every Mermaid diagram.
#   2. mmdc, when installed: every one of those diagrams renders. Skipped, with a note, if not.
#   3. headless Chrome, when installed: the page loads at every top-level address and each
#      aggregate, with no JavaScript error, a non-empty view, and every Mermaid block drawn
#      and none drawn as Mermaid's syntax-error box.
# Usage: tools/spec/views/check.sh   (run build.sh first; needs node; CHROME may name a Chromium)
set -euo pipefail
V="$(cd "$(dirname "$0")" && pwd)"; PAGE="$V/dist/event-model.html"
[[ -f "$PAGE" ]] || { echo "no $PAGE: run build.sh first"; exit 1; }
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
fail=0
node "$V/smoke.mjs" "$PAGE" "$TMP/mmd" || exit 1
if command -v mmdc >/dev/null; then
  n=0; bad=0
  for f in "$TMP"/mmd/*.mmd; do
    n=$((n + 1))
    mmdc -q -i "$f" -o "${f%.mmd}.svg" >"$TMP/mmdc.log" 2>&1 || { bad=$((bad + 1)); echo "mmdc: $(basename "$f") failed: $(head -3 "$TMP/mmdc.log")"; }
  done
  echo "mmdc: $((n - bad)) of $n diagrams render"; [[ $bad == 0 ]] || fail=1
else
  echo "note: mmdc not installed, Mermaid render check skipped (npm i -g @mermaid-js/mermaid-cli)"
fi
if [[ -x "$CHROME" ]]; then
  hashes="$(node -e '
    const html = require("fs").readFileSync(process.argv[1], "utf8");
    const js = html.split("<script>")[1].split("</script>")[0];
    globalThis.document = { getElementById: () => ({}) }; globalThis.location = { hash: "" }; globalThis.window = { addEventListener() {}, scrollTo() {} };
    const api = new Function(js + "\nreturn {ROUTES};")();
    console.log(api.ROUTES().filter(h => !h.startsWith("blk-") && !h.startsWith("rule-")).map(h => h || "home").join(" "));' "$PAGE")"
  total=0; pages=0
  for h in $hashes; do
    [[ "$h" == home ]] && url="file://$PAGE" || url="file://$PAGE#$h"
    read -r n drawn err size jserr < <("$CHROME" --headless=new --disable-gpu --virtual-time-budget=20000 --dump-dom "$url" 2>/dev/null | python3 "$V/count-drawn.py")
    total=$((total + n)); pages=$((pages + 1))
    if [[ "$n" != "$drawn" || "$err" != 0 || "$size" == 0 || "$jserr" != "-" ]]; then
      echo "chrome #$h: $drawn of $n diagrams drawn, $err syntax errors, view ${size} chars, js error: $jserr"; fail=1
    fi
  done
  echo "chrome: $pages addresses loaded, $total Mermaid blocks drawn"
else
  echo "note: no Chrome at $CHROME, browser check skipped"
fi
[[ $fail == 0 ]] && echo "every view renders" || { echo "CHECK FAILED"; exit 1; }
