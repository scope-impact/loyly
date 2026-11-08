#!/usr/bin/env bash
# Builds the candidate (ahead-of-time compiled into classes/, so start.sh boots fast),
# then runs the whole suite. Exits non-zero on any failure or when no test is discovered.
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"
export LOYLY_IMPL_DIR="$DIR"
unset LOYLY_GRAIN_STORE LOYLY_SCORING_TOOL_PATHS

rm -rf classes
mkdir -p classes
clojure -M:jvm -e "(binding [*compile-path* \"classes\"] (compile 'loyly.regen.seam))" >&2

out="$(mktemp)"
trap 'rm -f "$out"' EXIT
set +e
clojure -M:jvm:test -d test 2>&1 | tee "$out"
status=${PIPESTATUS[0]}
set -e
if [ "$status" -ne 0 ]; then exit "$status"; fi
if ! grep -Eq '^Ran [1-9][0-9]* tests' "$out"; then
  echo "test.sh: no test was discovered" >&2
  exit 1
fi
