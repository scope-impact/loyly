#!/usr/bin/env bash
# Start the cycle runner speaking JSON-RPC 2.0 over stdio (seam.md). Works from any directory:
# relative paths handed in through LOYLY_* are read against the caller's directory.
set -euo pipefail
export LOYLY_CALLER_DIR="${LOYLY_CALLER_DIR:-$PWD}"
cd "$(dirname "${BASH_SOURCE[0]}")"
exec clojure -M:run "$@"
