#!/usr/bin/env bash
# Resolves the classpath (absolute) and AOT-compiles the product into
# target/classes, so start.sh starts fast. Called by test.sh.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"
mkdir -p target/classes
clojure -Spath -A:jvm \
  | tr ':' '\n' \
  | while IFS= read -r entry; do
      case "$entry" in
        /*) printf '%s\n' "$entry" ;;
        "") ;;
        *) printf '%s\n' "$HERE/$entry" ;;
      esac
    done \
  | paste -sd ':' - > target/classpath.txt
rm -rf target/classes && mkdir -p target/classes
java --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  --enable-native-access=ALL-UNNAMED \
  -Dclojure.compile.path="$HERE/target/classes" \
  -cp "$HERE/target/classes:$(cat target/classpath.txt)" \
  clojure.main -e "(compile 'loyly.specification.main)" 1>&2
echo "build: classpath and AOT classes ready" 1>&2
