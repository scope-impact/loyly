#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd -P)"
ORC_ROOT="$ROOT/orc"
WORKER_SRC="$ROOT/.pi/harness/orc-worker/src"
export PI_HARNESS_PROJECT_ROOT="$ROOT"
export PI_HARNESS_SOURCE_ROOT="${PI_HARNESS_SOURCE_ROOT:-${LOYLEY_SOURCE_ROOT:-$ROOT}}"
export PI_HARNESS_CUTS_ROOT="${PI_HARNESS_CUTS_ROOT:-${LOYLEY_CUTS_ROOT:-$HOME/.pi/agent/loyly/cuts}}"
mkdir -p "$PI_HARNESS_CUTS_ROOT"
cd "$ORC_ROOT"
exec clojure -Sdeps "{:paths [\"$WORKER_SRC\"]
                      :deps {tech.unravel/litellm-clj-bedrock
                             {:git/url \"https://github.com/ObneyAI/litellm-clj.git\"
                              :git/sha \"0570e9459b8751d4a0c3dc1cd48595804426a84d\"
                              :deps/root \"contrib/bedrock\"}}}" \
  -J-Dlogback.configurationFile="$(dirname "$WORKER_SRC")/logback-stderr.xml" \
  -M:dev -m loyly.pi-harness.durable-entrypoint "$@"
