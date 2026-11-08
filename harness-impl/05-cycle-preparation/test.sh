#!/usr/bin/env bash
# Build and run the whole suite. Exits non-zero on any failure.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"
MODEL_DIR="$(cd "$HERE/../../.pi/spec/cycle-preparation/event-model" && pwd)"

# Ahead-of-time compile, so start.sh boots quickly (start.sh is timed; this is not).
rm -rf target/classes && mkdir -p target/classes
clojure -J-Dloyly.event-model.dir="$MODEL_DIR" -M:aot -e "(binding [*compile-path* \"target/classes\"] (compile 'loyly.cycle-preparation.main) (compile 'loyly.cycle-preparation.system) (compile 'loyly.cycle-preparation.seam))" 1>&2 </dev/null

# The whole suite: every test drives start.sh through its public seam.
clojure -J-Dloyly.event-model.dir="$MODEL_DIR" -M:test -d test
