#!/usr/bin/env bash
# Start the cycle-preparation candidate: JSON-RPC 2.0 over stdio (seam.md).
# LOYLY_GRAIN_STORE names the SQLite store; LOYLY_PROJECT_COMMITS the commits file.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"
MODEL_DIR="$(cd "$HERE/../../.pi/spec/cycle-preparation/event-model" && pwd)"
exec clojure -J-Dloyly.event-model.dir="$MODEL_DIR" -M:aot:run "$@"
