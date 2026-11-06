#!/usr/bin/env bash
# Every spec gate over .pi/model, in order, failing closed. Each gate runs and prints its line
# even when an earlier one fails. Usage: tools/spec/gates.sh
#   1. allium: check and analyse, 0 errors and 0 warnings (allium-check.sh)
#   2. grain: the Event Model as Grain registers it, structural ({:structural-only true
#      :strict true}) and composition (every :grain/allium link resolves), 0 errors, 0 warnings
#   3. coverage: every must-cover Allium obligation is tagged on a scenario or a draft, no
#      unknown tags (coverage.py)
#   4. gwt: every scenario has a Given, a When and a Then (gwt-lint.py)
#   5. checklist: harness/gen_checklist.clj derives the checklist (every scenario stamped)
# Specs only: nothing here builds or ratifies an implementation.
set -uo pipefail
T="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(git -C "$T" rev-parse --show-toplevel)"; cd "$ROOT"
bad=0
out="$("$T/allium-check.sh" 2>&1)" || bad=1
n=$(grep -c '^CHECK' <<<"$out" || true); a=$(grep -c '^ANALYSE' <<<"$out" || true)
echo "allium:    $n errors and warnings | $a analyse findings"
grep -E '^(CHECK|ANALYSE)' <<<"$out" | sed 's/^/    /'
[[ $n == 0 ]] && grep -q 'allium-check done' <<<"$out" || bad=1

g="$(clojure -Sdeps '{:paths [".pi/model/event-model"] :deps {g/cat {:local/root "grain/projects/grain-code-agent-tools"}}}' -M "$T/grain-validate.clj" 2>&1)"
grep -E '^(structural|composition)' <<<"$g" | sed 's/^/grain:     /'
if grep -q 'structural .*:errors 0, :warnings 0.*valid? true' <<<"$g" && grep -q 'composition .*:errors 0, :warnings 0.*valid? true' <<<"$g"; then :; else
  bad=1; grep '^{' <<<"$g" | sed 's/^/    /'; grep -qE '^(structural|composition)' <<<"$g" || tail -5 <<<"$g" | sed 's/^/    /'; fi

cov="$(python3 "$T/coverage.py" 2>&1)"; rc=$?
echo "coverage:  $(head -1 <<<"$cov")"
if [[ $rc != 0 ]]; then bad=1
  u=$(grep -c 'UNCOVERED' <<<"$cov" || true)
  grep -E 'UNCOVERED' <<<"$cov" | head -10 | sed 's/^ */    /'; [[ $u -gt 10 ]] && echo "    ... and $((u - 10)) more (python3 tools/spec/coverage.py)"
  grep -E 'UNKNOWN TAG' <<<"$cov" | sed 's/^ */    /'
fi

gl="$(python3 "$T/gwt-lint.py" 2>&1)" || bad=1
echo "gwt:       $(tail -1 <<<"$gl")"; grep INCOMPLETE <<<"$gl" | sed 's/^/    /'

ck="$(bb harness/gen_checklist.clj --out "$(mktemp -d)/checklist.json" 2>&1)" || bad=1
echo "checklist: $(tail -1 <<<"$ck")"; grep -q "^checklist: {'total': [1-9]" <<<"$ck" || { bad=1; head -5 <<<"$ck" | sed 's/^/    /'; }

[[ $bad = 0 ]] && echo "ALL GATES PASS" || { echo "GATES FAIL"; exit 1; }
