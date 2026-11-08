#!/usr/bin/env bash
# Starts the regeneration-cycle candidate speaking JSON-RPC 2.0 over stdio: one JSON
# object per line on stdin and stdout, diagnostics on stderr.
# LOYLY_GRAIN_STORE names its SQLite Grain store; LOYLY_SCORING_TOOL_PATHS the scoring tools.
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"
export LOYLY_IMPL_DIR="$DIR"
if [ -d classes/loyly ]; then
  exec clojure -M:jvm:aot -m loyly.regen.seam
fi
exec clojure -M:jvm -m loyly.regen.seam
