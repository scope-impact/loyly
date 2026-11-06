#!/usr/bin/env bash
# allium check and allium analyse of .pi/model, all files together (the imports resolve only
# within the check set). Prints one CHECK line per error or warning and one ANALYSE line per
# analyse finding; info diagnostics are not tracked (.pi/model/COVERAGE.md, baseline).
# Usage: tools/spec/allium-check.sh
set -euo pipefail
ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"; cd "$ROOT"
for cmd in check analyse; do
  allium "$cmd" .pi/model | bb -e '
    (doseq [d (cheshire.core/parsed-seq (java.io.BufferedReader. *in*) true)]
      (doseq [x (:diagnostics d) :when (#{"error" "warning"} (:severity x))]
        (println "CHECK" (:command d) (get-in x [:location :file]) (str (get-in x [:location :line])) (:severity x) (:code x) (:message x)))
      (doseq [x (:findings d)] (println "ANALYSE" (:spec_file d) (:type x) (:summary x))))' || true
done
echo "allium-check done"
