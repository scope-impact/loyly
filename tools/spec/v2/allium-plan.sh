#!/usr/bin/env bash
# allium-plan.sh <context>: the test obligations `allium plan` derives from each file of
# .pi/spec/<context>/allium, counted. Must-cover = each rule's success, each `requires`
# (rule_failure, numbered in requires order) and each expression invariant: the obligations
# a scenario or a grader must exercise. Prints one line per file and a context total.
set -euo pipefail
ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
D="$ROOT/.pi/spec/$1/allium"; cd "$D"
tot_s=0; tot_f=0; tot_i=0; tot_all=0
for f in *.allium; do
  j="$(allium plan "$f")"
  read -r s fl i all < <(jq -r '[ ([.obligations[]|select(.category=="rule_success")]|length),
                                  ([.obligations[]|select(.category=="rule_failure")]|length),
                                  ([.obligations[]|select(.category=="invariant")]|length),
                                  (.obligations|length) ] | @tsv' <<<"$j")
  printf '  %-28s must-cover %3d (rule_success %2d, rule_failure %3d, invariant %2d); all obligations %3d\n' \
    "$f" $((s+fl+i)) "$s" "$fl" "$i" "$all"
  tot_s=$((tot_s+s)); tot_f=$((tot_f+fl)); tot_i=$((tot_i+i)); tot_all=$((tot_all+all))
done
printf 'plan %-20s must-cover %3d (rule_success %2d, rule_failure %3d, invariant %2d); all obligations %3d\n' \
  "$1" $((tot_s+tot_f+tot_i)) "$tot_s" "$tot_f" "$tot_i" "$tot_all"
