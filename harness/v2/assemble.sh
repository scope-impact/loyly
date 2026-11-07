#!/usr/bin/env bash
# harness/v2/assemble.sh ROOM01 ROOM02 ROOM03 ROOM04 ROOM05: the assembly gate of spec v2
# (.pi/spec/decisions.md, "First light" 5, "Spec round 4" 3 and "Spec round 5"), run from THIS
# checkout against the five gated candidates, ROOMnn/harness-impl/NN-<context>/start.sh, in
# slice order. It drives them only through their public seams (each context's seam.md: JSON-RPC
# 2.0 over stdio, a fresh Grain store through LOYLY_GRAIN_STORE), reads their stores directly
# with Grain's own reader (v2/store_reader.clj, on the deps of v2/deps.edn) and reads no
# candidate's source. In order (harness/v2/assemble.clj):
#
#   1. the shared-store experiment: producers and consumers on ONE store, one after the other,
#      then concurrently; does a consumer see a producer's facts with nothing handed in?
#      Reported, not a check (the shared store is the deferred end state)
#   2. the relay assembly: each candidate on its own store; one story (stamp, pin, write and
#      move a golden, derive the checklist, a regeneration cycle to a passed gate attempt, item
#      verdicts and the score, the scoreboard, the cycle passed); after each command every new
#      handoff is taken from the producer's store file (its handoff types, its tenant, store
#      order) and handed in unchanged, in that order, to every consumer the event models name;
#      then a cycle the stage clock fails at the score stage, measured anyway
#   3. checks, PASS/FAIL with evidence: contracts fit, one tenant per producer, command results
#      found in the store, no hand-in refused, echoes equal, order kept, the cycle passed
#      measured and reported, cut id, candidate digest, goldens digest and item ids agree; and
#      the round-5 reading: the cycle failed at score stays failed, its measurement stays filed
#
# Exit 0 only when every check passes. Every process it starts is stopped.
set -uo pipefail
(( $# == 5 )) || { echo "usage: harness/v2/assemble.sh ROOM01 ROOM02 ROOM03 ROOM04 ROOM05"; exit 2; }
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
exec bb "$ROOT/harness/v2/assemble.clj" "$ROOT" "$@"
