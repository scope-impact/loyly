#!/usr/bin/env bash
# gates.sh [context ...]: every spec-v2 gate, in order, failing closed. All contexts when
# none is named.
#   1. allium check and analyse, each file with its siblings: no CHECK and no ANALYSE line
#      (0 errors, 0 warnings, 0 findings); INFO lines are listed, not counted
#   2. Grain: every context's event-model fragments merged into one model, structural and
#      composition (every command links an Allium rule, every screen a surface, and every
#      link resolves): 0 errors, 0 warnings
#   3. allium plan: the must-cover obligations per context (rule success, each requires,
#      each invariant), reported
set -uo pipefail
T="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(git -C "$T" rev-parse --show-toplevel)"
SPEC="$ROOT/.pi/spec"
ctxs=("$@"); [[ ${#ctxs[@]} -gt 0 ]] || ctxs=($(cd "$SPEC" && for d in */allium; do echo "${d%/allium}"; done))
bad=0
for c in "${ctxs[@]}"; do
  out="$("$T/allium-check.sh" "$c")" || bad=1
  n=$(grep -c '^CHECK' <<<"$out" || true); a=$(grep -c '^ANALYSE' <<<"$out" || true); i=$(grep -c '^INFO' <<<"$out" || true)
  printf 'allium %-20s %s CHECK, %s ANALYSE, %s INFO\n' "$c" "$n" "$a" "$i"
  grep -E '^(CHECK|ANALYSE|INFO)' <<<"$out" | sed 's/^/    /'
  [[ $n = 0 && $a = 0 ]] || bad=1
done
g="$(cd "$T" && clojure -M -i grain-validate.clj 2>&1)"; gs=$?
grep -E '^(structural|composition)' <<<"$g"
[[ $gs = 0 ]] || { bad=1; grep -E '^\{' <<<"$g"; }
for c in "${ctxs[@]}"; do "$T/allium-plan.sh" "$c" | grep '^plan' || bad=1; done
[[ $bad = 0 ]] && echo "ALL GATES PASS" || { echo "GATES FAIL"; exit 1; }
