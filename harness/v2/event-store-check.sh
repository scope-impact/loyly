#!/usr/bin/env bash
# harness/v2/event-store-check.sh NN STORE: the gate's event-store check for slice NN (see
# event_store_check.clj). The event model is .pi/spec/<context>/event-model of THIS checkout,
# the context being the one .pi/plan/NN-* links to.
set -uo pipefail
SLICE="${1:?usage: event-store-check.sh NN STORE}"
STORE="${2:?usage: event-store-check.sh NN STORE}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
link="$(cd "$ROOT" && ls -d .pi/plan/"$SLICE"-* 2>/dev/null | head -1)"
[[ -n "$link" && -d "$ROOT/$link/event-model" ]] || {
  echo "FATAL: no .pi/plan/$SLICE-* link to a context with an event-model/ in $ROOT"; exit 2; }
STORE="$(cd "$(dirname "$STORE")" 2>/dev/null && pwd -P)/$(basename "$STORE")"
cd "$ROOT/harness/v2" && exec clojure -J--enable-native-access=ALL-UNNAMED -M event_store_check.clj "$(cd "$ROOT/$link/event-model" && pwd -P)" "$STORE"
