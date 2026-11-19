#!/usr/bin/env bash
# Build a report for a fresh black-box grader-only room, leaving filed cycle evidence untouched.
set -euo pipefail
root=$(git rev-parse --show-toplevel)
room=${1:?usage: build-grader-only.sh GRADING_ROOM OUTPUT_DIR}
out=${2:?usage: build-grader-only.sh GRADING_ROOM OUTPUT_DIR}
room=$(cd "$room" && pwd)
case "$out" in
  /*) ;;
  *) out="$PWD/$out" ;;
esac
cut=$(sed -n 's/^spec_commit=//p' "$room/.grading-room.txt")
[[ $cut =~ ^[0-9a-f]{40}$ ]] || { echo 'grading room has no pinned spec commit' >&2; exit 1; }
results="$out-results"
test ! -e "$results"
(cd "$root/tools/spec" && clojure -M -m spec.allure-run "$room" "$cut" "$results")
npx --yes allure-commandline@2.46.1 generate "$results" --clean --output "$out"
expected=$(wc -l < "$room/checklist.txt" | tr -d ' ')
passed=$(jq '[.[] | select(.verdict == "pass")] | length' "$room/work/verdicts.json")
jq -e --argjson expected "$expected" --argjson passed "$passed" \
  '.statistic.total == $expected and .statistic.passed == $passed' \
  "$out/widgets/summary.json" >/dev/null
if rg -q '/Users/|/private/|/home/|/var/folders/|Bearer [A-Za-z0-9]' "$out" \
    -g '*.json' -g '*.txt' -g '*.html'; then
  echo 'Allure report contains a host path or credential-shaped text' >&2
  exit 1
fi
echo "Grader-only Allure: $expected results, $passed passed, $out"
