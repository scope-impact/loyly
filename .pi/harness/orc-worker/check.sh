#!/usr/bin/env bash
set -euo pipefail

WORKER_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_ROOT="$(cd "$WORKER_ROOT/../../.." && pwd -P)"
ORC_ROOT="$PROJECT_ROOT/orc"
CLJ_KONDO_VERSION="2024.05.24"

cd "$PROJECT_ROOT"
clojure -Sdeps "{:deps {clj-kondo/clj-kondo {:mvn/version \"$CLJ_KONDO_VERSION\"}}}" \
  -M -m clj-kondo.main \
  --config-dir "$WORKER_ROOT/.clj-kondo" \
  --lint "$WORKER_ROOT/src" "$WORKER_ROOT/test"

cd "$ORC_ROOT"
clojure -Sdeps "{:paths [\"$WORKER_ROOT/src\" \"$WORKER_ROOT/test\"]
                 :deps {tech.unravel/litellm-clj-bedrock
                        {:git/url \"https://github.com/ObneyAI/litellm-clj.git\"
                         :git/sha \"0570e9459b8751d4a0c3dc1cd48595804426a84d\"
                         :deps/root \"contrib/bedrock\"}}}" \
  -M:dev -m loyly.pi-harness.test-runner
