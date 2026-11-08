#!/usr/bin/env bash
# Check the built page the way a reader sees it.
#   1. check.clj static: every view is in the file and not empty, ids are unique, every
#      internal #link resolves, no Clojure value leaked into the text; against .pi/spec itself,
#      every context has its view and card, every handoff edge is drawn, and the item count
#      shown is the number of distinct stamped @item tags; the C4 view (#c4) is linked from home
#      and shows the three C4 views of .pi/architecture.md (Level 1: System Context, Level 2:
#      Container, Level 3: Component (cycle runner)), each with its Mermaid diagram.
#   2. mmdc, when installed: every distinct Mermaid diagram renders, the three C4 ones included.
#      Skipped, with a note, if not.
#   3. headless Chrome, when installed: the page loads at every top-level address (home, review,
#      c4, each context and each Allium file) and shows that view, with no JavaScript error
#      and every Mermaid block of the view drawn, none as Mermaid's syntax-error box.
#   4. Playwright (after npm install here): every view, #c4 included, fits a phone; no storm
#      cluster overlaps.
# Usage: tools/spec/views/check.sh   (run build.sh first; needs bb; CHROME may name a Chromium)
set -euo pipefail
V="$(cd "$(dirname "$0")" && pwd)"; PAGE="$V/dist/event-model.html"
[[ -f "$PAGE" ]] || { echo "no $PAGE: run build.sh first"; exit 1; }
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
fail=0
bb "$V/check.clj" static "$PAGE" "$TMP/mmd" > "$TMP/static" || exit 1
grep -v '^top:' "$TMP/static"
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
  total=0; pages=0
  for h in $(sed -n 's/^top: //p' "$TMP/static"); do
    n=$("$CHROME" --headless=new --disable-gpu --virtual-time-budget=20000 --dump-dom "file://$PAGE#$h" 2>/dev/null | bb "$V/check.clj" dom "$h") || { fail=1; continue; }
    total=$((total + n)); pages=$((pages + 1))
  done
  echo "chrome: $pages addresses shown, $total Mermaid blocks drawn"
else
  echo "note: no Chrome at $CHROME, browser check skipped"
fi
if [[ -d "$V/node_modules/playwright-core" ]]; then
  node "$V/layout.mjs" "$PAGE" || fail=1
else
  echo "note: run 'npm install' in tools/spec/views for the Playwright layout check"
fi
[[ $fail == 0 ]] && echo "every view renders" || { echo "CHECK FAILED"; exit 1; }
