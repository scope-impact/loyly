#!/usr/bin/env bash
# harness/v2/gate.sh NN ROOM: the gate of one context's cycle (.pi/spec/decisions.md, "First
# light"), run from THIS checkout against the candidate a builder left in ROOM.
#
# The room holds no instrument: harness/config.sh fences the legacy driver, the stamper and
# harness/v2 from a context's room. So the gate cannot run inside it, as the legacy gate did.
# It runs here, with this checkout's tools, and harness/config.sh points APP_DIR at
# ROOM/harness-impl/NN-context (SLICE and CANDIDATE_ROOM below). In order:
#
#   1. harness/verify.sh, unchanged: the instrument (harness/check.sh --local), the whole
#      suite (APP_DIR/test.sh), then a fresh boot and the slice's identity probe
#      (harness/v2/probe-NN.sh) within the thirty-second boot budget
#   2. the event-store check (harness/v2/event-store-check.sh): the Grain store this gate
#      handed the candidate through LOYLY_GRAIN_STORE, read directly, holds events, each an
#      event of the slice's event model or a handoff it consumes (handed in through the seam,
#      as the stubbed producer's fact) with exactly its fields, or Grain's or ORC's own, and
#      each event a command produced lies inside a completed run of its ORC workflow there
#
# Exit 0 is the gate's verdict that the candidate is done. Nothing else is.
set -uo pipefail
SLICE="${1:?usage: harness/v2/gate.sh NN ROOM}"
ROOM="${2:?usage: harness/v2/gate.sh NN ROOM}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
[[ -d "$ROOM" ]] || { echo "FATAL: no room at $ROOM"; exit 1; }
export SLICE CANDIDATE_ROOM="$(cd "$ROOM" && pwd -P)"
. "$ROOT/harness/config.sh"
[[ "$APP_PROBE" == "harness/v2/probe-$SLICE.sh" ]] || {
  echo "FATAL: SLICE=$SLICE names no context: no .pi/plan/$SLICE-* link in $ROOT"; exit 1; }

# The gate's work directory, kept as run state (harness/README.md, "Where a spec-v2 cycle's run
# state goes"): PI_HARNESS_STATE_ROOT, by default this checkout's .pi/harness/.state.
STATE="${PI_HARNESS_STATE_ROOT:-$ROOT/.pi/harness/.state}"
mkdir -p "$STATE/gate" || { echo "FATAL: cannot make $STATE/gate"; exit 1; }
EVIDENCE="$(mktemp -d "$STATE/gate/$SLICE.XXXXXX")"
export GATE_STORE="$EVIDENCE/events.sqlite"
unset LOYLY_GRAIN_STORE   # the suite never writes into the store the gate will read
echo "=== the gate of slice $SLICE: candidate $CANDIDATE_ROOM/harness-impl/$(basename "$APP_DIR")"
echo "    the candidate's Grain store: $GATE_STORE"

"$ROOT/harness/verify.sh" || { echo "FATAL: the gate failed at harness/verify.sh (above)."; exit 1; }

echo "=== harness/v2/event-store-check.sh (the store, read directly, against the slice's event model) ==="
"$ROOT/harness/v2/event-store-check.sh" "$SLICE" "$GATE_STORE" 2>&1 | grep -v -e '^SLF4J' -e '^WARNING'
(( PIPESTATUS[0] == 0 )) || { echo "FATAL: the event-store check failed (above)."; exit 1; }

echo "=== gate OK: slice $SLICE's suite green, its identity probe answered, its store holds the model's events, each command's inside a completed ORC run ==="
