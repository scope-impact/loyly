#!/usr/bin/env bash
# Run the whole suite; exit non-zero on any failure or when no test is discovered.
set -euo pipefail
export LOYLY_CALLER_DIR="${LOYLY_CALLER_DIR:-$PWD}"
cd "$(dirname "${BASH_SOURCE[0]}")"
exec clojure -M:test "$@"
