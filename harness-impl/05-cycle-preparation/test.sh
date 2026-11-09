#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
./test/launcher-test.sh
. ./env.sh
# A copied candidate must not reuse a classpath containing builder-room paths.
clojure -Sforce -M -m cycle-preparation.build
exec "$JAVA_CMD" -cp "product.jar:test" clojure.main -m cycle-preparation.test-runner
