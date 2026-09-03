#!/usr/bin/env bash
# The fence: make a clean-room clone of this repo in which the prior implementations
# and all measurements are deleted from the working tree AND from history, then PROVE
# it. A generation that can read last week's answer is not regenerating anything.
#
# The fence is physical, and proved rather than asserted. An earlier and weaker probe
# once reported success while the previous implementation was still reachable from an
# older commit, so the probe now inspects the object graph rather than the working
# tree.
#
# Usage: fence.sh /path/to/clean-room
set -euo pipefail
TARGET="${1:?usage: fence.sh /path/to/clean-room}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[[ -e "$TARGET" ]] && { echo "FATAL: $TARGET exists; the clean room must be fresh."; exit 1; }

# What the regeneration must not see: the current implementation and every measurement.
# The spec, the domain wiki, the sources and the way of working stay: they ARE the input.
FENCED_PATHS=(impl harness/cycles)

# --depth 1: the clone carries ONE commit of history, so a deleted path cannot be dug
# back out of the object store. The file:// prefix is essential rather than decorative:
# handed a plain path, git makes a LOCAL clone that shares the whole object store and
# quietly disregards --depth, leaving the deleted trees intact in the clone's own
# history. With file:// the depth is genuine, and the probe below turns any slip back
# into a hard failure.
git clone -q --depth 1 "file://$ROOT" "$TARGET"

# A fenced path that matches nothing is not a clean pass. It is a typo, or the thing it
# named has moved, and what it was meant to hide may now sit under another name. Until
# 2026-09-03 such an entry was removed silently and the probe below then passed, because
# the probe can only confirm an absence.
for p in "${FENCED_PATHS[@]}"; do
  [[ -e "$TARGET/$p" ]] || { echo "FENCE FAILED: $p matches nothing in the clone: fix the entry, or find where it went, before trusting this room"; exit 1; }
  rm -rf "${TARGET:?}/$p"
done
git -C "$TARGET" add -A
git -C "$TARGET" -c user.name=fence -c user.email=fence@localhost \
  commit -q -m "the fence: prior implementations and measurements removed"

# Squash to a single root commit so no earlier commit survives to be read.
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
(( roots == 1 )) || fail "the clone has $roots root commits; a fenced clean room must have exactly one"
n="$(git -C "$TARGET" rev-list --all --count)"
(( n == 1 )) || fail "history is not a single commit ($n): deleted trees may be reachable"
objects="$(git -C "$TARGET" rev-list --objects --all)"
for p in "${FENCED_PATHS[@]}"; do
  [[ -e "$TARGET/$p" ]] && fail "$p was not removed from the working tree"
  grep -qE "(^| )${p%/}(/|$)" <<<"$objects" && fail "$p still reachable in the object graph"
done
echo "fence OK: $TARGET is a single-commit clean room with no ${FENCED_PATHS[*]} anywhere in it"
