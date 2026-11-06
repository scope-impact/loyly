#!/usr/bin/env bash
# harness/v2/score.sh NN ROOM: the score and the scoreboard of one spec-v2 context's cycle
# (.pi/spec/decisions.md, "First light"), run from THIS checkout against the candidate a
# builder left in ROOM, after harness/v2/gate.sh passed. The instrument: it reports to a
# person and never into the build loop. In order:
#
#   1. the sealed graders the operator approved for spec v2 (harness/v2/graders.approved.json;
#      spec v1's harness/graders.approved.json names v1 items and is never read here) that
#      cover slice NN; none is "score refused: no approved sealed grader". Each must be sealed
#      under STATE/graders/sealed/<hex>/ with its entry's sha256 equal to its approved digest.
#   2. the slice's next cycle number MM, claimed atomically as harness/v2/cycles/NN/MM (as
#      harness/regen.clj claims harness/cycles/MM). Spec-v2 cycles never go under
#      harness/cycles: harness/oracle.sh (a shared module file) reads harness/cycles/01 as spec
#      v1's recorded cycle, judged by harness/oracle/scoreboard-01.golden, so a v2 cycle 01
#      there would turn the oracle red over a golden that records something else. One root
#      per slice also makes the legacy scoreboard's "previous cycle" (MM minus one) the last
#      cycle of the same selection, the slice (decisions.md g). harness/README.md, "Spec v2".
#   3. the slice's checklist, derived by the legacy harness/gen_checklist.clj from slice NN's
#      spec alone (a scratch root holding only .pi/plan/NN-context), into the cycle
#   4. each approved grader, run with elixir from its sealed directory against
#      ROOM/harness-impl/NN-context/start.sh; its stderr is kept in the cycle; one item filed
#      by two graders is refused
#   5. the legacy harness/score.clj files the verdicts (an unreached item fails and stays in
#      the denominator), then the legacy harness/scoreboard.clj reports the cycle. Both resolve
#      harness/cycles from their own location, never from an argument, so they run as
#      unchanged copies in the scratch root, whose harness/cycles is a link to the slice's
#      harness/v2/cycles/NN; "harness/cycles/MM" in their output means that directory.
#
# STATE is PI_HARNESS_STATE_ROOT, or .pi/harness/.state of the repository's main checkout.
set -uo pipefail
SLICE="${1:-}"; ROOM="${2:-}"
die() { echo "score.sh: $*" >&2; exit 1; }
[[ "$SLICE" =~ ^[0-9]+$ && -n "$ROOM" ]] || die "usage: harness/v2/score.sh NN ROOM"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
if [[ -n "${PI_HARNESS_STATE_ROOT:-}" ]]; then STATE="$PI_HARNESS_STATE_ROOT"
else
  common="$(git -C "$ROOT" rev-parse --path-format=absolute --git-common-dir 2>/dev/null)" \
    || die "not a git checkout, and PI_HARNESS_STATE_ROOT is not set"
  STATE="$(dirname "$common")/.pi/harness/.state"
fi

link="$(cd "$ROOT" && ls -d ".pi/plan/$SLICE"-* 2>/dev/null | head -1)"
[[ -n "$link" ]] || die "slice $SLICE names no context: no .pi/plan/$SLICE-* in $ROOT"
CONTEXT="$(basename "$link")"

# ---- 1. the approved sealed graders covering the slice
graders="$(bb -e '
(let [[file slice] *command-line-args*
      gs (:graders (cheshire.core/parse-string (slurp file) true))]
  (doseq [{:keys [digest entry items]} gs
          :when (some #(clojure.string/starts-with? % (str slice "-")) items)]
    (println digest entry)))' "$ROOT/harness/v2/graders.approved.json" "$SLICE")" \
  || die "score refused: harness/v2/graders.approved.json could not be read"
[[ -n "$graders" ]] || die "score refused: no approved sealed grader covers slice $SLICE (harness/v2/graders.approved.json)"
while read -r digest entry; do
  hex="${digest#sha256:}"; dir="$STATE/graders/sealed/$hex"
  [[ "$entry" == *.exs ]] || die "score refused: no runner for grader entry $entry (spec-v2 graders are Elixir)"
  [[ -f "$dir/$entry" ]] || die "score refused: approved grader $digest is not sealed at $dir"
  [[ "$(shasum -a 256 "$dir/$entry" | cut -d' ' -f1)" == "$hex" ]] \
    || die "score refused: the sealed $dir/$entry does not match its approved digest $digest"
done <<<"$graders"

START="$(cd "$ROOM" 2>/dev/null && pwd -P)/harness-impl/$CONTEXT/start.sh"
[[ -d "$ROOM" && -f "$START" ]] || die "score refused: no candidate at $ROOM/harness-impl/$CONTEXT/start.sh"

# ---- 2. the cycle number, in the slice's own root
CYCLES="$ROOT/harness/v2/cycles/$SLICE"
mkdir -p "$CYCLES"
n=$(( $(ls "$CYCLES" | grep -E '^[0-9]+$' | sed 's/^0*//' | sort -n | tail -1) + 1 ))
until mkdir "$CYCLES/$(printf '%02d' "$n")" 2>/dev/null; do n=$((n + 1)); done
CYCLE="$(printf '%02d' "$n")"; CYC="$CYCLES/$CYCLE"
echo "cycle $CYCLE of slice $SLICE ($CONTEXT), filed in $CYC: candidate $START" >&2

# ---- 3. the slice's checklist, from that slice's spec alone
scratch="$(mktemp -d "${TMPDIR:-/tmp}/score-$SLICE.XXXXXX")"; trap 'rm -rf "$scratch"' EXIT
mkdir -p "$scratch/harness" "$scratch/.pi/plan/$CONTEXT"
cp "$ROOT/harness/gen_checklist.clj" "$ROOT/harness/hjson.clj" "$ROOT/harness/config.sh" \
   "$ROOT/harness/score.clj" "$ROOT/harness/scoreboard.clj" "$scratch/harness/"
ln -s "$CYCLES" "$scratch/harness/cycles"
for f in spec.feature plan.md; do [[ -e "$ROOT/$link/$f" ]] && cp -L "$ROOT/$link/$f" "$scratch/.pi/plan/$CONTEXT/"; done
bb "$scratch/harness/gen_checklist.clj" --out "$CYC/checklist.json" 2>"$CYC/checklist.log" \
  || die "score refused: the checklist could not be derived: $(tail -c 400 "$CYC/checklist.log")"

# ---- 4. the graders, each from its sealed directory
commit="$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"
outs=()
while read -r digest entry; do
  hex="${digest#sha256:}"; dir="$STATE/graders/sealed/$hex"
  out="$CYC/verdicts-${hex:0:12}.json"; log="$CYC/grade-${hex:0:12}.log"
  echo "grading with $digest" >&2
  (cd "$dir" && PI_HARNESS_PROJECT_ROOT="$ROOT" GRADE_SOURCE_COMMIT="$commit" elixir "$dir/$entry" "$START") >"$out" 2>"$log" \
    || die "grader $digest did not run: $(tail -c 400 "$log")"
  outs+=("$out")
done <<<"$graders"
bb -e '
(let [[out & files] *command-line-args*
      vs (vec (mapcat #(cheshire.core/parse-string (slurp %)) files))
      twice (sort (keep (fn [[id n]] (when (> n 1) id)) (frequencies (map #(get % "id") vs))))]
  (when (seq twice)
    (binding [*out* *err*] (println "score refused: more than one grader filed" (clojure.string/join ", " twice)))
    (System/exit 1))
  (spit out (cheshire.core/generate-string vs)))' "$CYC/verdicts.json" "${outs[@]}" || exit 1

# ---- 5. file and report, with the legacy tools
bb "$scratch/harness/score.clj" "$CYCLE" --verdicts "$CYC/verdicts.json" || die "score refused by harness/score.clj (above)"
bb "$scratch/harness/scoreboard.clj" "$CYCLE"
