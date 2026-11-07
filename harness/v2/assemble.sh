#!/usr/bin/env bash
# harness/v2/assemble.sh ROOM01 ROOM02 ROOM03 ROOM04 ROOM05: the first assembly gate of spec v2
# (.pi/spec/decisions.md, "First light" 5 and "Spec round 4" 3), run from THIS checkout against
# the five gated candidates, ROOMnn/harness-impl/NN-<context>/start.sh, in slice order. It drives
# them only through their public seams (each context's seam.md: JSON-RPC 2.0 over stdio, a fresh
# Grain store through LOYLY_GRAIN_STORE) and reads no candidate's source. In order
# (harness/v2/assemble.clj):
#
#   1. the shared-store experiment: producers and consumers on ONE store, one after the other,
#      then concurrently; does a consumer see a producer's facts with nothing handed in?
#   2. the relay assembly: each candidate on its own store; one story (stamp, pin, write and
#      move a golden, derive the checklist, a regeneration cycle to a passed gate attempt, item
#      verdicts and the score, the scoreboard, the cycle passed); every handoff a producer
#      emits is handed in unchanged, in the producer's order, to every consumer the event
#      models name
#   3. checks, PASS/FAIL with evidence: contracts fit, order kept, the cycle passed measured
#      and reported, cut id, candidate digest, goldens digest and item ids agree; then the
#      round-4 open question (a cycle failed at the score stage, measured anyway), a finding
#
# Exit 0 only when every check passes. Every process it starts is stopped.
set -uo pipefail
(( $# == 5 )) || { echo "usage: harness/v2/assemble.sh ROOM01 ROOM02 ROOM03 ROOM04 ROOM05"; exit 2; }
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
exec bb "$ROOT/harness/v2/assemble.clj" "$ROOT" "$@"
