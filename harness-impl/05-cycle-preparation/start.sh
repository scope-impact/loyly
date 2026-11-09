#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
. ./env.sh
if [ ! -f product.jar ]; then
  echo 'Missing build product. Run test.sh first.' >&2
  exit 1
fi
exec "$JAVA_CMD" -cp product.jar clojure.main -m cycle-preparation.product
