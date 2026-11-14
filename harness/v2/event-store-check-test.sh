#!/usr/bin/env bash
# harness/v2/event-store-check-test.sh: the event-store check judged on hand-made stores
# (event_store_fixtures.clj) against the event model of slices 01 to 06, each slice's own
# beside the rest of the one event model (spec round 18: every context's event-model/*.edn, no
# handoff contract): one store of each passes (three of slice 06's, the one tree
# cycle-runner/cycle.v1: a halted run, a concluded one with a context's event in the leaf that
# issued it, and a gate attempt in a child tick the run's tick delegated; and a one-node wrapper
# of slice 05, which declares no leaf), and a one-node wrapper, an effect in another effect's
# leaf (in the tick or in a delegated child tick) and an attempt's tick no cycle.v1 tick
# delegated fail for slice 06, and each of the others, and a store that is not there, fails for
# its own reason, among them a model event with no ORC run on the store, one in a run that
# never completed with success, another context's event off its schema or outside any ORC run,
# a tick completed twice, a trace written twice, and ORC processor output with no Grain
# checkpoint (a re-ticked run, :running then success, passes).
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/event-store-check-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
(cd "$HERE" && clojure -J--enable-native-access=ALL-UNNAMED -M event_store_fixtures.clj "$WORK" >/dev/null) || { echo "FATAL: fixtures not written"; exit 1; }
bad=0
expect() {  # expect RC STORE WORDS: judged against slice NN when the store's name starts "NN-", else 01
  local out rc slice=01
  [[ "$(basename "$2")" =~ ^(0[2-9])- ]] && slice="${BASH_REMATCH[1]}"
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
expect 1 "$WORK/fail-other-outside-run.sqlite" "(cycle-preparation/source-cut-pinned) was not written inside a run of the ORC workflow cycle-preparation/cut-to-fence or specification/stamping"
expect 0 "$WORK/pass-re-ticked-run.sqlite"   "event-store check OK"
expect 1 "$WORK/fail-doubled-completion.sqlite" 'rule "one terminal completion per tick" failed: tick .* has 2 terminal sheet/tree-tick-completed.*start each processor with its :processor-name'
expect 1 "$WORK/fail-doubled-trace.sqlite"   'rule "one terminal completion per tick" failed: trace .* has 2 sheet/execution-traced.*start each processor with its :processor-name'
expect 1 "$WORK/fail-no-checkpoint.sqlite"   'rule "processors are checkpointed" failed: tenant .*no grain/todo-processor-checkpoint.*start each processor with its :processor-name'
expect 0 "$WORK/02-pass-others-and-runs.sqlite" "event-store check OK"
expect 1 "$WORK/02-fail-only-others.sqlite" "holds no event of the model"
expect 1 "$WORK/02-fail-other-schema.sqlite" ":branch"
expect 1 "$WORK/02-fail-other-outside-run.sqlite" "(regeneration-cycle/gate-attempt-passed) was not written inside a run of the ORC workflow"
expect 1 "$WORK/02-fail-outside-run.sqlite"  "was not written inside a run of the ORC workflow measurement/stamping-to-checklist"
expect 1 "$WORK/02-fail-wrong-workflow.sqlite" "was not written inside a run of the ORC workflow measurement/scoring"
expect 0 "$WORK/03-pass-written-then-moved.sqlite" "event-store check OK"
expect 1 "$WORK/03-fail-reason-outside-three.sqlite" ":reason"
expect 1 "$WORK/03-fail-outside-run.sqlite"  "was not written inside a run of the ORC workflow grader-lifecycle/golden-written-then-moved"
expect 1 "$WORK/03-fail-other-outside-run.sqlite" "(specification/scenario-stamped) was not written inside a run of the ORC workflow grader-lifecycle/golden-written-then-moved or specification/stamping"
expect 0 "$WORK/04-pass-others-and-runs.sqlite" "event-store check OK"
expect 1 "$WORK/04-fail-other-schema.sqlite" ":refusal"
expect 1 "$WORK/04-fail-other-outside-run.sqlite" "(measurement/checklist-derived) was not written inside a run of the ORC workflow"
expect 1 "$WORK/04-fail-outside-run.sqlite"  "was not written inside a run of the ORC workflow regeneration-cycle/fence-to-scoreboard"
expect 1 "$WORK/04-fail-wrong-workflow.sqlite" "was not written inside a run of the ORC workflow regeneration-cycle/gate-refusals-fail-cycle"
expect 0 "$WORK/05-pass-pinned.sqlite"       "event-store check OK"
expect 0 "$WORK/05-pass-one-node-wrapper.sqlite" "event-store check OK"
expect 1 "$WORK/05-fail-short-commit.sqlite" ":commit"
expect 1 "$WORK/05-fail-outside-run.sqlite"  "was not written inside a run of the ORC workflow cycle-preparation/cut-to-fence"
expect 1 "$WORK/05-fail-other-outside-run.sqlite" "(specification/scenario-stamped) was not written inside a run of the ORC workflow"
expect 0 "$WORK/06-pass-halted.sqlite"       "event-store check OK"
expect 0 "$WORK/06-pass-concluded.sqlite"    "event-store check OK"
expect 1 "$WORK/06-fail-halt-reason.sqlite"  ":reason"
expect 1 "$WORK/06-fail-outside-run.sqlite"  "was not written inside a run of the ORC workflow cycle-runner/cycle.v1"
expect 1 "$WORK/06-fail-other-outside-run.sqlite" "(specification/scenario-stamped) was not written inside a run of the ORC workflow cycle-runner/cycle.v1 or specification/stamping"
expect 1 "$WORK/06-fail-one-node-wrapper.sqlite" 'rule "each effect in its own leaf" failed: event .* (cycle-runner/run-started) was not written inside a leaf named start of the ORC workflow cycle-runner/cycle.v1; it lies inside the leaf perform-command of cycle-runner/cycle.v1'
expect 1 "$WORK/06-fail-one-node-wrapper.sqlite" 'cycle-runner/effect-recorded, effect fence) was not written inside a leaf named fence of the ORC workflow cycle-runner/cycle.v1'
expect 0 "$WORK/06-pass-delegated-attempt.sqlite" "event-store check OK"
expect 1 "$WORK/06-fail-delegated-wrong-leaf.sqlite" 'effect gate) was not written inside a leaf named gate of the ORC workflow cycle-runner/cycle.v1; it lies inside the leaf install of cycle-runner/gate-attempt.v1'
expect 1 "$WORK/06-fail-orphan-attempt.sqlite" "was not written inside a run of the ORC workflow cycle-runner/cycle.v1"
expect 1 "$WORK/06-fail-wrong-leaf.sqlite"   'effect fence) was not written inside a leaf named fence of the ORC workflow cycle-runner/cycle.v1; it lies inside the leaf pin of cycle-runner/cycle.v1'
(( bad )) && { echo "event-store check test FAILED"; exit 1; }
echo "event-store check test OK: 10 passes and 38 refusals checked, each for its own reason"
