#!/usr/bin/env bash
# allium-check.sh <context>: allium check and allium analyse each file of
# .pi/spec/<context>/allium with its siblings (the CLI resolves `use` only among the files
# it is given, and reports one spec_file per document). Prints, per file, only that file's
# own diagnostics:
#   CHECK   <file>:<line> <severity> <code> <message>   an error or a warning (the gate: none)
#   ANALYSE <file> <type> <summary>                      an analyse finding (the gate: none)
#   INFO    <file>:<line> <code> <message>               an info diagnostic (allowed, listed)
# Adapted from Halla Health's tools/grain-specs/allium-check.sh; uses jq instead of bb and
# drops nothing: every warning counts.
set -euo pipefail
ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
D="$ROOT/.pi/spec/$1/allium"; cd "$D"
for f in *.allium; do
  others=$(ls *.allium | grep -vx "$f" || true)
  # shellcheck disable=SC2086
  allium check "$f" $others | jq -r --arg f "$f" '
    select(.spec_file == $f) | .diagnostics[] | select(.location.file == $f) |
    if (.severity == "error" or .severity == "warning")
    then "CHECK \($f):\(.location.line) \(.severity) \(.code) \(.message)"
    else "INFO \($f):\(.location.line) \(.code) \(.message)" end' || true
  # shellcheck disable=SC2086
  allium analyse "$f" $others | jq -r --arg f "$f" '
    select(.spec_file == $f) | .findings[] | "ANALYSE \($f) \(.type) \(.summary)"' || true
done
