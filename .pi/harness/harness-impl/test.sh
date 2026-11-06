#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
exec clojure -J--add-opens=java.base/java.nio=ALL-UNNAMED -J--add-opens=java.base/sun.nio.ch=ALL-UNNAMED -M:test
