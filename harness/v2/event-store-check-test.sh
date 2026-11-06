#!/usr/bin/env bash
# harness/v2/event-store-check-test.sh: the event-store check judged on hand-made stores
# (event_store_fixtures.clj) against slice 01's event model: one store passes, and each of
# the others, and a store that is not there, fails for its own reason.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/event-store-check-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
(cd "$HERE" && clojure -J--enable-native-access=ALL-UNNAMED -M event_store_fixtures.clj "$WORK" >/dev/null) || { echo "FATAL: fixtures not written"; exit 1; }
bad=0
expect() {  # expect RC STORE WORDS
  local out rc
  out="$("$HERE/event-store-check.sh" 01 "$2" 2>&1)"; rc=$?
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
(( bad )) && { echo "event-store check test FAILED"; exit 1; }
echo "event-store check test OK: 1 pass, 6 refusals, each for its own reason"
