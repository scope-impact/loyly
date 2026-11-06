#!/usr/bin/env bash
# harness/v2/event-store-check-test.sh: the event-store check judged on hand-made stores
# (event_store_fixtures.clj) against slice 01's event model and slice 02's (measurement, with
# the handoffs it consumes): one store of each passes, and each of the others, and a store
# that is not there, fails for its own reason, among them a model event with no ORC run on
# the store, one in a run that never completed with success, a consumed handoff off its
# contract's schema, and a foreign event the slice does not consume.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/event-store-check-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
(cd "$HERE" && clojure -J--enable-native-access=ALL-UNNAMED -M event_store_fixtures.clj "$WORK" >/dev/null) || { echo "FATAL: fixtures not written"; exit 1; }
bad=0
expect() {  # expect RC STORE WORDS: judged against slice 02 when the store's name starts "02-", else 01
  local out rc slice=01
  [[ "$(basename "$2")" == 02-* ]] && slice=02
  out="$("$HERE/event-store-check.sh" "$slice" "$2" 2>&1)"; rc=$?
  if [[ $rc == "$1" ]] && grep -q -- "$3" <<<"$out"; then echo "  ok    $(basename "$2"): exit $rc, says \"$3\""
  else echo "  FAIL  $(basename "$2"): exit $rc (want $1), wanted \"$3\" in:"; sed 's/^/        /' <<<"$out"; bad=1; fi
}
expect 0 "$WORK/pass-two-events.sqlite"      "event-store check OK"
expect 1 "$WORK/fail-empty.sqlite"           "holds no event of the model"
expect 1 "$WORK/fail-unknown-type.sqlite"    "specification/slice-renamed is not an event of"
expect 1 "$WORK/fail-extra-field.sqlite"     ":digest"
expect 1 "$WORK/fail-missing-field.sqlite"   ":title"
expect 1 "$WORK/fail-ill-typed-field.sqlite" ":line"
expect 1 "$WORK/absent.sqlite"               "no event store at"
expect 1 "$WORK/fail-no-orc-run.sqlite"      "the store holds no ORC event"
expect 1 "$WORK/fail-run-never-completed.sqlite" "never completed with success (tick .*: no tree-tick-completed)"
expect 1 "$WORK/fail-run-failed.sqlite"      "never completed with success (tick .*: failure)"
expect 1 "$WORK/fail-outside-run.sqlite"     "was not written inside a run of the ORC workflow specification/stamping"
expect 1 "$WORK/fail-other-workflow.sqlite"  "was not written inside a run of the ORC workflow specification/stamping"
expect 1 "$WORK/fail-unconsumed-handoff.sqlite" "cycle-preparation/source-cut-pinned is not an event of"
expect 0 "$WORK/02-pass-handoffs-and-runs.sqlite" "event-store check OK"
expect 1 "$WORK/02-fail-only-handoffs.sqlite" "holds no event of the model"
expect 1 "$WORK/02-fail-handoff-schema.sqlite" ":branch"
expect 1 "$WORK/02-fail-foreign-event.sqlite" "regeneration-cycle/gate-attempt-failed is not an event of"
expect 1 "$WORK/02-fail-outside-run.sqlite"  "was not written inside a run of the ORC workflow measurement/stamping-to-checklist"
expect 1 "$WORK/02-fail-wrong-workflow.sqlite" "was not written inside a run of the ORC workflow measurement/scoring"
(( bad )) && { echo "event-store check test FAILED"; exit 1; }
echo "event-store check test OK: 2 passes, 17 refusals, each for its own reason"
