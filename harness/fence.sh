#!/usr/bin/env bash
# The fence: make a clean-room clone of this repo in which the prior implementations
# and all measurements are deleted from the working tree AND from history, then PROVE
# it. A generation that can read last week's answer is not regenerating anything.
#
# The fence is physical, and proved rather than asserted. In the production pipeline
# this was distilled from, a weaker probe once passed while the reference
# implementation was still readable one commit back; since then the probe checks the
# object graph, not the working tree.
#
# Usage: fence.sh /path/to/clean-room
set -euo pipefail
TARGET="${1:?usage: fence.sh /path/to/clean-room}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[[ -e "$TARGET" ]] && { echo "FATAL: $TARGET exists; the clean room must be fresh."; exit 1; }

# What the regeneration must not see: the current implementation and every measurement.
# The spec, the domain wiki, the sources and the way of working stay: they ARE the input.
FENCED_PATHS=(impl harness/cycles)

# --depth 1: the clone has ONE commit of history, so a deleted path is unreachable even
# by archaeology. file:// is LOAD-BEARING, not style: given a plain path, git makes a
# LOCAL clone that hardlinks the whole object store and silently ignores --depth, and
# the deleted trees stay readable at HEAD~1. With file:// the depth is real, and the
# probe below turns any regression of this into a hard failure.
git clone -q --depth 1 "file://$ROOT" "$TARGET"

for p in "${FENCED_PATHS[@]}"; do
  rm -rf "${TARGET:?}/$p"
done
git -C "$TARGET" add -A
git -C "$TARGET" -c user.name=fence -c user.email=fence@localhost \
  commit -q -m "the fence: prior implementations and measurements removed"

# Squash to a single root commit so nothing is one commit back.
NEW_ROOT="$(git -C "$TARGET" -c user.name=fence -c user.email=fence@localhost \
  commit-tree "$(git -C "$TARGET" rev-parse 'HEAD^{tree}')" -m 'fenced clean room')"
git -C "$TARGET" reset -q --hard "$NEW_ROOT"
# The origin refs still pin the pre-fence commits; a clean room has no origin at all.
git -C "$TARGET" remote remove origin
git -C "$TARGET" reflog expire --expire=now --all
git -C "$TARGET" gc -q --prune=now --aggressive

# ---- prove it, do not assert it -------------------------------------------------
fail() { echo "FENCE FAILED: $*"; exit 1; }
roots="$(git -C "$TARGET" rev-list --max-parents=0 --all | wc -l | tr -d ' ')"
(( roots == 1 )) || fail "history has $roots root commits, expected exactly 1"
n="$(git -C "$TARGET" rev-list --all --count)"
(( n == 1 )) || fail "history is not a single commit ($n): deleted trees may be reachable"
objects="$(git -C "$TARGET" rev-list --objects --all)"
for p in "${FENCED_PATHS[@]}"; do
  [[ -e "$TARGET/$p" ]] && fail "$p still present in the working tree"
  grep -qE "(^| )${p%/}(/|$)" <<<"$objects" && fail "$p still reachable in the object graph"
done
echo "fence OK: $TARGET is a single-commit clean room with no ${FENCED_PATHS[*]} anywhere in it"
