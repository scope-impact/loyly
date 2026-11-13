#!/usr/bin/env bash
# improve-instruction.sh <cycle NN-cMM> [<out-dir>]
# After a cycle is done: one GEPA reflective mutation (ORC's components/gepa) of the builder
# instruction's operator-appended paragraphs, from the cycle's instruction, the supervisor's steers
# and stops (builders/<cycle>/steers.jsonl, stops.jsonl) and outcome signals (builder wall clock,
# gate attempts and their failing lines, the final score as an aggregate only), with one pi turn
# (../builder-host/turn.ts, pi's existing login) as GEPA's proposer LLM. Writes a proposal for a
# person to approve, default $PI_HARNESS_STATE_ROOT/proposals/<cycle>/proposal.md; never applies it
# (an instruction change is an operator decision, like the fence plan). ../improve/improve.clj.
#   LOYLY_PI_MODEL   the proposer's model (default openai-codex/gpt-6.1-sol)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CYCLE="${1:?cycle, as NN-cMM}"
STATE="${PI_HARNESS_STATE_ROOT:-$HERE/../.state}"; STATE="$(cd "$STATE" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
OUT="${2:-$STATE/proposals/$CYCLE}"; mkdir -p "$OUT"; OUT="$(cd "$OUT" && pwd)"
export LOYLY_HARNESS_DIR="$(cd "$HERE/.." && pwd)"
cd "$HERE/../improve"
exec clojure -M improve.clj "$STATE" "$REPO" "$CYCLE" "$OUT" 2> >(grep -v -E '^(SLF4J|WARNING: sequence)' >&2)
