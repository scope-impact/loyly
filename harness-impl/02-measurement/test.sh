#!/usr/bin/env bash
# Run the whole suite; exits non-zero on any failure or when no test is found.
# Also compiles the product ahead of time into classes/, so start.sh boots faster.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
rm -rf classes && mkdir -p classes
clojure -M:aot >&2
exec clojure -M:test "$@"
