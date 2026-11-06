#!/usr/bin/env bash
# Build the event-model page from the specs: .pi/model/*.allium, the Grain Event Model as Grain
# registers it from .pi/model/event-model/**/*.clj, the coverage gate's own report, every
# slice's spec.feature and drafts.feature, and the C4 views of .pi/architecture.md, rendered at
# build time into one static page by render.clj (with extract.clj, page.css, page.js).
# Writes tools/spec/views/dist/event-model.html (never anything under .pi/).
# Usage: tools/spec/views/build.sh [built-date]   (needs bb, the Clojure CLI, python3, allium)
set -euo pipefail
V="$(cd "$(dirname "$0")" && pwd)"
bb --config "$V/bb.edn" "$V/render.clj" --built "${1:-$(date +%F)}"
