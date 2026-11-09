#!/bin/sh
# The whole suite: compile the product ahead of time (so start.sh boots
# fast), run every test, and fail on any failure or when no test ran.
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"
command -v bb >/dev/null 2>&1 || { echo "test.sh: babashka (bb) runs the stand-in contexts and is not on PATH" >&2; exit 1; }
rm -rf classes && mkdir -p classes
clojure -J-Dloyly.home="$DIR" -M:jvm:aot -e "(compile 'loyly.cycle-runner.main)" >&2
status=0
clojure -J-Dloyly.home="$DIR" -M:jvm:aot:test -m loyly.cycle-runner.test-runner || status=$?
chmod -R u+w "$DIR/tmp/tests" 2>/dev/null || true
rm -rf "$DIR/tmp/tests" || true
exit $status
