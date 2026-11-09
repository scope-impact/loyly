#!/usr/bin/env bash
# Build the replay page of one harness run: its Grain and ORC events, from every store of the run,
# replayed in time order. One self-contained HTML file, scrubbed for publication (README.md).
# Usage: tools/spec/replay/build.sh <run-dir> <out.html> [--title <title>] [--spec <spec-root>]
# Needs bb and sqlite3.
set -euo pipefail
V="$(cd "$(dirname "$0")" && pwd)"
bb --config "$V/bb.edn" -m replay.main "$@"
