#!/usr/bin/env bash
# Start the measurement candidate speaking the seam (JSON-RPC 2.0 over stdio).
# LOYLY_GRAIN_STORE names the SQLite file of its Grain store (in-memory when unset).
# Only responses reach stdout; diagnostics go to stderr.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
exec clojure -M:run "$@"
