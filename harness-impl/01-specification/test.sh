#!/usr/bin/env bash
# Builds the candidate, then runs the whole suite. Exits non-zero on any failure.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"
"$HERE/build.sh"
export LOYLY_IMPL_DIR="$HERE"
rm -rf "$HERE/target/test-work" "$HERE/target/run"
clojure -M:jvm:test
