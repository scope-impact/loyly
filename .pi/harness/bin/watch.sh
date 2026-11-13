#!/usr/bin/env bash
# watch.sh <evidence-dir> [--since <cursor>] [--tools N] [--stall-minutes N] [--at <iso-time>]
# A read-only digest of a builder's or grader's pi-durable session for the supervising Claude
# session (../supervisor/README.md): elapsed time, entries since the cursor, the last tool calls,
# the latest suite run, minutes since the last entry, and the new cursor. --stall-minutes N blocks
# until no entry for N minutes (or the session ended): run it in the background to be woken.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec node --no-warnings "$HERE/../supervisor/watch.ts" "$@"
