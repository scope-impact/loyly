#!/usr/bin/env bash
# Check the built pages the way a reader sees them.
#   1. check.clj static: every view is in the file and not empty, ids are unique, every
#      internal #link resolves, no Clojure value leaked into the text; against the spec root
#      itself, every context has its view and card, every handoff edge is drawn on the
#      architecture view, and the item count shown is the number of distinct stamped item tags;
#      the C4 views of the architecture file, each with its Mermaid diagram. The drill-down: L0,
#      the event storm on home and in story.svg (one sticky per event); L1, a swimlane board per
#      flow; L2, a spec card per rule, each with its L3 folded beneath. review.html is one
#      self-contained page.
#   2. mmdc, when installed: every distinct Mermaid diagram renders, the C4 ones included.
#      Skipped, with a note, if not.
#   3. headless Chrome, when installed: the page loads at every top-level address (home, review,
#      c4, each context and each Allium file) and shows that view, with no JavaScript error
#      and every Mermaid block of the view drawn (or deferred under a closed "under the hood"),
#      none as Mermaid's syntax-error box.
#   4. Playwright (after npm install here): every view fits a phone (375) and a desktop (1280),
#      light and dark, folded and with every <details> open; no storm cluster overlaps.
#   5. The example project (example/project.edn) builds and passes 1 and 3, proving the code
#      holds no Löyly assumption.
# Usage: tools/spec/views/check.sh [--config <project.edn>]   (run build.sh first; needs bb)
#        CHROME may name a Chromium.
set -euo pipefail
V="$(cd "$(dirname "$0")" && pwd)"
CONFIG="$V/project.edn"; [[ "${1:-}" == "--config" ]] && CONFIG="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
fail=0
out_dir() { bb -e "(let [f (java.io.File. \"$1\") c (clojure.edn/read-string (slurp f)) d (.getParentFile (.getAbsoluteFile f)) r (if (:root c) (java.io.File. d (:root c)) (java.io.File. (clojure.string/trim (:out (clojure.java.shell/sh \"git\" \"rev-parse\" \"--show-toplevel\" :dir (str d))))))] (println (.getCanonicalPath (java.io.File. r (:out-dir c \"dist\")))))"; }
check_page() {   # <page.html> <config> <label>
  local page="$1" cfg="$2" label="$3"
  [[ -f "$page" ]] || { echo "no $page: run build.sh first"; exit 1; }
  echo "== $label"
  bb "$V/check.clj" static "$page" "$TMP/mmd-$label" "$cfg" > "$TMP/static-$label" || exit 1
  grep -v '^top:' "$TMP/static-$label"
  if [[ -x "$CHROME" ]]; then
    local total=0 pages=0 n
    for h in $(sed -n 's/^top: //p' "$TMP/static-$label"); do
      n=$("$CHROME" --headless=new --disable-gpu --virtual-time-budget=20000 --dump-dom "file://$page#$h" 2>/dev/null | bb "$V/check.clj" dom "$h") || { fail=1; continue; }
      total=$((total + n)); pages=$((pages + 1))
    done
    echo "chrome: $pages addresses shown, $total Mermaid blocks drawn or deferred"
  else
    echo "note: no Chrome at $CHROME, browser check skipped"
  fi
}
PAGE="$(out_dir "$CONFIG")/event-model.html"
check_page "$PAGE" "$CONFIG" project
if command -v mmdc >/dev/null; then
  n=0; bad=0
  for f in "$TMP"/mmd-project/*.mmd; do
    n=$((n + 1))
    mmdc -q -i "$f" -o "${f%.mmd}.svg" >"$TMP/mmdc.log" 2>&1 || { bad=$((bad + 1)); echo "mmdc: $(basename "$f") failed: $(head -3 "$TMP/mmdc.log")"; }
  done
  echo "mmdc: $((n - bad)) of $n diagrams render"; [[ $bad == 0 ]] || fail=1
else
  echo "note: mmdc not installed, Mermaid render check skipped (npm i -g @mermaid-js/mermaid-cli)"
fi
if [[ -d "$V/node_modules/playwright-core" ]]; then
  node "$V/layout.mjs" "$PAGE" "$(dirname "$PAGE")/review.html" || fail=1
else
  echo "note: run 'npm install' in tools/spec/views for the Playwright layout check"
fi
if [[ "$CONFIG" == "$V/project.edn" ]]; then   # the example project: no Löyly assumption
  bb -e "(spit \"$TMP/example.edn\" (pr-str (assoc (clojure.edn/read-string (slurp \"$V/example/project.edn\")) :root \"$V/example\" :out-dir \"$TMP/example\")))"
  bash "$V/build.sh" --config "$TMP/example.edn" > "$TMP/example.log" 2>&1 || { cat "$TMP/example.log"; exit 1; }
  echo "== example build: $(tr '\n' ' ' < "$TMP/example.log")"
  check_page "$TMP/example/event-model.html" "$TMP/example.edn" example
fi
[[ $fail == 0 ]] && echo "every view renders" || { echo "CHECK FAILED"; exit 1; }
