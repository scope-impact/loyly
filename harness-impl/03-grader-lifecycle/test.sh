#!/usr/bin/env bash
# Runs the whole suite of the grader-lifecycle candidate; exits non-zero on any failure.
set -euo pipefail
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec clojure -J--add-opens=java.base/java.nio=ALL-UNNAMED \
  -J--add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  -J--enable-native-access=ALL-UNNAMED \
  -M:test "$@"
