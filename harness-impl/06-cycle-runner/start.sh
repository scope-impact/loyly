#!/bin/sh
# Start the cycle runner speaking its seam (JSON-RPC 2.0 over stdio) on the
# Grain store LOYLY_GRAIN_STORE names. Diagnostics go to stderr.
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR" || exit 1
exec clojure -J-Dloyly.home="$DIR" -M:jvm:aot -m loyly.cycle-runner.main
