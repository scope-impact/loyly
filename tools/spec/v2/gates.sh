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
#   4. coverage (coverage.py): every must-cover obligation (rule success, each requires less
#      the unit-level ones, invariants, contract invariants, guarantees not verified by a
#      gate) tagged on a scenario or a draft of its context; 0 uncovered, no unknown tags
#   5. gwt (gwt-lint.py): every scenario and draft has a Given, a When and a Then
#   6. glossary (glossary-lint.py): no avoided term of .pi/model/GLOSSARY.md in .pi/spec
#      (the R0 board and the legacy trace reported, not counted)
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
cov="$(python3 "$T/coverage.py" "${ctxs[@]}" 2>&1)" || bad=1
sed 's/^/coverage /' <<<"$cov"
gw="$(python3 "$T/gwt-lint.py" 2>&1)" || bad=1
grep INCOMPLETE <<<"$gw"; tail -1 <<<"$gw"
gl="$(python3 "$T/glossary-lint.py" --quiet 2>&1)" || bad=1
grep '^AVOIDED' <<<"$gl" | sed 's/^/    /'; tail -1 <<<"$gl"
[[ $bad = 0 ]] && echo "ALL GATES PASS" || { echo "GATES FAIL"; exit 1; }
