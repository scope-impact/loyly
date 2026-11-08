#!/usr/bin/env bash
# Starts the grader-lifecycle candidate speaking JSON-RPC 2.0 over stdio.
# LOYLY_GRAIN_STORE names its SQLite Grain store; LOYLY_SCENARIO_DIGESTS its
# current scenario digests. Only the seam writes to stdout.
set -euo pipefail
cd "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec clojure -M:run -m loyly.grader-lifecycle.seam
