#!/usr/bin/env bash
# Build the event-model page from the specs: .pi/model/*.allium, the Grain Event Model as Grain
# registers it from .pi/model/event-model/**/*.clj, the coverage gate's own report, every
# slice's spec.feature and drafts.feature, and the C4 views of .pi/architecture.md.
# Writes tools/spec/views/dist/event-model.html (never anything under .pi/).
# Usage: tools/spec/views/build.sh [built-date]   (needs bb, the Clojure CLI, python3, allium)
set -euo pipefail
V="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(git -C "$V" rev-parse --show-toplevel)"
OUT="$V/dist/event-model.html"; mkdir -p "$V/dist"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
cd "$ROOT"
clojure -Sdeps '{:paths [".pi/model/event-model"] :deps {g/cat {:local/root "grain/projects/grain-code-agent-tools"}}}' \
  -M tools/spec/grain-validate.clj --dump "$TMP/model.edn" >/dev/null
# the gate may fail (uncovered obligations); the page shows that. It may not crash (exit 2).
rc=0; python3 tools/spec/coverage.py --report "$TMP/coverage.json" >/dev/null || rc=$?
[[ $rc -le 1 ]] || { echo "coverage.py failed ($rc)"; exit 1; }
bb "$V/extract.clj" "$ROOT" "$TMP/model.edn" "$TMP/coverage.json" "$TMP/spec.json"
python3 - "$V/template.html" "$TMP/spec.json" "$OUT" "${1:-$(date +%F)}" <<'PY'
import sys
t, d, out, built = open(sys.argv[1]).read(), open(sys.argv[2]).read().replace("</", "<\\/"), sys.argv[3], sys.argv[4]
assert "/*DOMAIN*/null" in t
open(out, "w").write(t.replace("/*DOMAIN*/null", d).replace("/*BUILT*/", built))
PY
echo "wrote ${OUT#$ROOT/}"
