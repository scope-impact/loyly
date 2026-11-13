#!/usr/bin/env bash
# triage-steers.sh <NN-cMM> [<NN-cMM>...]
# After one or more cycles: groups the supervisor's steers and stops (builders/<cycle>/steers.jsonl,
# stops.jsonl, and the same under launch-*/) and each gate attempt's FAIL/ERROR/FATAL lines
# (logs/gate-cMM[-aN]-sliceNN.log), classifies each group as a spec gap, a harness/tool issue or a
# one-off by deterministic rules, and writes candidate spec inbox items with their evidence to
# $PI_HARNESS_STATE_ROOT/triage/<cycles>/triage.md, for a person to take into a spec round.
# Never reads grading verdicts, grading evidence or scores; never writes an instruction or a spec
# file (the builder instruction is frozen and generic). Program: ../scripts/triage-steers.mjs.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[[ $# -ge 1 ]] || { echo "usage: triage-steers.sh <NN-cMM> [<NN-cMM>...]" >&2; exit 2; }
STATE="${PI_HARNESS_STATE_ROOT:-$HERE/../.state}"; STATE="$(cd "$STATE" && pwd)"
OUT="$STATE/triage/$(IFS=+; echo "$*")"
exec node --no-warnings "$HERE/../scripts/triage-steers.mjs" "$STATE" "$OUT" "$@"
