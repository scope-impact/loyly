#!/usr/bin/env bash
# Build the spec v2 event-model page from .pi/spec: per context, its Allium (allium/*.allium),
# its Grain Event Model fragments (event-model/*.edn, merged by tools/spec/v2/load.clj), its
# spec.feature and drafts.feature, and the coverage gate's own report
# (tools/spec/v2/coverage.py --report); plus the handoffs between contexts, and the C4 views of
# .pi/architecture.md (stamped with that file's own commit). Rendered at build
# time into one static page by render.clj (with extract.clj, common.clj, context.clj, page.css,
# page.js). Writes tools/spec/views/dist/event-model.html (never anything under .pi/).
# Usage: tools/spec/views/build.sh [built-date]   (needs bb and python3; coverage.py needs allium)
#
# Spec v1 (.pi/model: *.allium, the Grain Event Model in event-model/**/*.clj evaluated by Grain,
# plan/NN-slug/*.feature, the C4 views of .pi/architecture.md) was rendered by this tool up to
# commit 6d42c8e; check that commit out to rebuild the v1 page (tools/spec/mcp, the v1 review
# app, still expects that version of extract.clj and render.clj).
set -euo pipefail
V="$(cd "$(dirname "$0")" && pwd)"
bb --config "$V/bb.edn" "$V/render.clj" --built "${1:-$(date +%F)}"
