#!/usr/bin/env bash
# harness/v2/score.sh NN ROOM: the score and the scoreboard of one spec-v2 context's cycle
# (.pi/spec/decisions.md, "First light"), run from THIS checkout against the candidate a
# builder left in ROOM, after harness/v2/gate.sh passed. The instrument: it reports to a
# person and never into the build loop. In order:
#
#   1. the grader: the pinned open-ended prompt harness/v2/grading/prompt.md and the grader
#      command LOYLY_GRADER_CMD (default below). Scoring is legacy-style
#      (archive/legacy-harness/README.md, step 5: grade however you grade, then file): the
#      prompt's sha256 and the command are recorded in the cycle (graders.txt) and on stderr as
#      provenance, and nobody approves them; no seal, no calibration, no approval list.
#      SCORE_WHICH=1 prints them and stops before anything is claimed or run.
#   2. the slice's next cycle number MM, claimed atomically as harness/v2/cycles/NN/MM (as
#      harness/regen.clj claims harness/cycles/MM). Spec-v2 cycles never go under
#      harness/cycles: harness/oracle.sh (a shared module file) reads harness/cycles/01 as spec
#      v1's recorded cycle, judged by harness/oracle/scoreboard-01.golden, so a v2 cycle 01
#      there would turn the oracle red over a golden that records something else. One root
#      per slice also makes the legacy scoreboard's "previous cycle" (MM minus one) the last
#      cycle of the same selection, the slice (decisions.md g). harness/README.md, "Spec v2".
#   3. the slice's checklist, derived by the legacy harness/gen_checklist.clj from slice NN's
#      spec alone (a scratch root holding only .pi/plan/NN-context), into the cycle
#   4. the grading, an ORC workflow (harness/v2/grading, on the Grain store
#      MM/grading.sqlite): the prompt rendered for the slice's items, the grader command run
#      on it in a fresh scratch directory (LOYLY_GRADER_TIMEOUT seconds at most, default
#      1800), its verdicts.json validated into MM/verdicts.json. An entry not shaped
#      {id, verdict pass|fail, evidence}, an unknown id and an id filed twice are dropped with
#      a note; no file is zero verdicts
#   5. the legacy harness/score.clj files the verdicts (an unreached item fails and stays in
#      the denominator; zero verdicts are refused), then the legacy harness/scoreboard.clj
#      reports the cycle. Both resolve harness/cycles from their own location, never from an
#      argument, so they run as unchanged copies in the scratch root, whose harness/cycles is
#      a link to the slice's harness/v2/cycles/NN; "harness/cycles/MM" in their output means
#      that directory.
#
set -uo pipefail
SLICE="${1:-}"; ROOM="${2:-}"
die() { echo "score.sh: $*" >&2; exit 1; }
[[ "$SLICE" =~ ^[0-9]+$ && -n "$ROOM" ]] || die "usage: harness/v2/score.sh NN ROOM"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

link="$(cd "$ROOT" && ls -d ".pi/plan/$SLICE"-* 2>/dev/null | head -1)"
[[ -n "$link" ]] || die "slice $SLICE names no context: no .pi/plan/$SLICE-* in $ROOT"
CONTEXT="$(basename "$link")"

# ---- 1. the grader: the pinned prompt and the command, their provenance recorded
GRADING="$ROOT/harness/v2/grading"
CMD="${LOYLY_GRADER_CMD:-claude -p --permission-mode bypassPermissions}"
[[ -f "$GRADING/prompt.md" ]] || die "score refused: no grader prompt at $GRADING/prompt.md"
graders="sha256:$(shasum -a 256 "$GRADING/prompt.md" | cut -d' ' -f1) prompt.md
command: $CMD"
if [[ -n "${SCORE_WHICH:-}" ]]; then echo "slice $SLICE ($CONTEXT): $graders"; exit 0; fi

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

# ---- 4. the grading, an ORC workflow
echo "$graders" >"$CYC/graders.txt"
echo "grading with $(head -1 "$CYC/graders.txt")" >&2
(cd "$GRADING" && LOYLY_GRADER_CMD="$CMD" clojure -M:jvm -m loyly.grading \
   "$SLICE" "$CONTEXT" "$(cd "$ROOT/$link" && pwd -P)" "$START" "$(cd "$ROOM" && pwd -P)" "$CYC") \
   >"$CYC/grading.log" 2>&1 || die "the grading workflow did not run: $(tail -c 400 "$CYC/grading.log")"
grep '^grading:' "$CYC/grading.log" >&2

# ---- 5. file and report, with the legacy tools
bb "$scratch/harness/score.clj" "$CYCLE" --verdicts "$CYC/verdicts.json" || die "score refused by harness/score.clj (above)"
bb "$scratch/harness/scoreboard.clj" "$CYCLE"
