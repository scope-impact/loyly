#!/usr/bin/env bash
# Build the spec pages from the specs a project.edn names (default: the Löyly one beside this
# script). Per context: its Allium (allium/*.allium), its Grain Event Model fragments
# (event-model/*.edn, merged one home per block), its spec.feature and drafts.feature, the
# coverage report when the config names a tool for it; the handoffs between contexts, and the C4
# views of the architecture file. Rendered at build time by src/specviews/ (Babashka + Replicant).
# Writes, under the config's :out-dir (never anything under the spec root):
#   event-model.html  the drill-down page (the sealed record, published to gh-pages)
#   review.html       the same page, self-contained, for review; with --base, the changes marked
#   story.svg         the event storm (L0), standalone
# Usage: tools/spec/views/build.sh [--config <project.edn>] [--base <git-ref>] [--built <date>] [--out-dir <dir>]
#        (a bare first argument that is a date is taken as --built, as before)
#
# Spec v1 (.pi/model) was rendered by this tool up to commit 6d42c8e; check that commit out to
# rebuild the v1 page (tools/spec/mcp, the v1 review app, still expects that version).
set -euo pipefail
V="$(cd "$(dirname "$0")" && pwd)"
args=()
if [[ $# -gt 0 && "$1" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then args+=(--built "$1"); shift; fi
[[ " $* " == *" --config "* ]] || args+=(--config "$V/project.edn")
bb --config "$V/bb.edn" -m specviews.main ${args[@]+"${args[@]}"} "$@"
