#!/usr/bin/env bash
# Starts the Specification candidate speaking JSON-RPC 2.0 over stdio.
# The Grain store is the SQLite file LOYLY_GRAIN_STORE names (in memory if unset).
# Relative spec sources resolve against the caller's working directory.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export LOYLY_IMPL_DIR="$HERE"
if [ ! -s "$HERE/target/classpath.txt" ]; then
  "$HERE/build.sh" 1>&2
fi
exec java --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  --enable-native-access=ALL-UNNAMED \
  -XX:+UseSerialGC -XX:TieredStopAtLevel=1 \
  -cp "$HERE/target/classes:$(cat "$HERE/target/classpath.txt")" \
  clojure.main -m loyly.specification.main
