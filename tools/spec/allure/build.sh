#!/usr/bin/env bash
# Build the static Allure report from a filed slice-06 cycle's tagged results.
# Usage: tools/spec/allure/build.sh [cycle-number]
set -euo pipefail
root=$(git rev-parse --show-toplevel)
cycle=${1:-26}
[[ $cycle =~ ^[0-9]+$ ]]
results="$root/harness/v2/cycles/06/$cycle/allure-results"
scores="$root/harness/v2/cycles/06/$cycle/scores.json"
out="$root/.pi/harness/.state/pages/allure-cycle-$cycle"
test -d "$results"
test -f "$scores"
if [[ "$cycle" == 26 ]]; then
  # The filed verdicts and profile stay unchanged. Enrich a throwaway copy from the
  # approved spec cut so Allure can group by Feature and Rule and display Gherkin.
  cut=a0c700f7cf969fd660fdf108be09ce881c99f412
  results="$root/.pi/harness/.state/pages/allure-cycle-$cycle-results"
  rm -rf "$results"
  (cd "$root/tools/spec" && clojure -M -m spec.allure "$cycle" "$cut")
fi
npx --yes allure-commandline@2.46.1 generate "$results" --clean --output "$out"
expected=$(jq '.items | length' "$scores")
passed=$(jq '[.items[] | select(.verdict == "pass")] | length' "$scores")
jq -e --argjson expected "$expected" --argjson passed "$passed" \
  '.statistic.total == $expected and .statistic.passed == $passed' \
  "$out/widgets/summary.json" >/dev/null
if rg -q '/Users/|/private/|/home/|/var/folders/|Bearer [A-Za-z0-9]' "$out" \
    -g '*.json' -g '*.txt' -g '*.html'; then
  echo 'Allure report contains a host path or credential-shaped text' >&2
  exit 1
fi
echo "Allure cycle $cycle: $expected results, $passed passed, static report at $out"
