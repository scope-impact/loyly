#!/usr/bin/env bash
# The harness oracle: proof that a candidate implementation of the four harness tools is
# behaviourally identical to the one in service.
#
#   ./harness/oracle.sh py       exercise the Python implementation (harness/*.py)
#   ./harness/oracle.sh bb       exercise the Babashka implementation (harness/*.clj)
#   ./harness/oracle.sh record   (re)record this repository's golden files from the
#                                Python implementation, which is the reference
#
# It is written to run unchanged in any repository that carries this harness. Everything
# repository-specific is either read from the repository (the committed cycle 01
# checklist, the committed scores) or recorded into harness/oracle/ by `record`.
#
# WHAT IT IS FOR. The checklist sets the scoring denominator. A denominator that moves
# for a reason other than the spec moving makes every past cycle incomparable, silently,
# and no test suite the candidate ships with can notice that: only a comparison against
# the bytes the instrument produced before can. So this is a byte-for-byte oracle. It
# compares output text, not meaning, and it compares refusal messages too, because those
# messages are the instrument telling an operator why it refused. A reimplementation that
# refuses for the same reason in different words has changed the product.
#
# THE THREE PRE-GUARD BEHAVIOURS. Two of the three repositories carry an older Python that
# lacks three guards the target implementation must have: the stamper's empty-spec
# message, score's empty/non-JSON verdicts handling, and gen_checklist's planned-slice
# filter. Under `py` the oracle detects which of the three the copy in front of it has and
# asserts that copy's actual behaviour, announcing the gap as an UPGRADE rather than a
# failure. Under `bb` all three are required. So `oracle.sh py` is green everywhere today
# and `oracle.sh bb` states the target.
#
# WHEN A GOLDEN MAY BE RE-RECORDED. Almost never. A golden that moves because the tools
# behave differently is the alarm this file exists to raise, and re-recording to quiet it
# destroys the only evidence. The one legitimate case is a change that alters the words on
# purpose and nothing else, and harness/README.md carries an entry for each time that has
# happened. If no entry there explains the diff you are looking at, do not record.
#
# NOTHING COMMITTED IS TOUCHED. Scoring is round-tripped through a throwaway cycle
# directory; the real cycle files are only ever read. The one thing the oracle does write
# inside the repository is harness/cycles/<throwaway>/, which it removes, and (under
# `record`) harness/oracle/.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT" || exit 2

MODE="${1:-}"
case "$MODE" in
  py|bb|record) ;;
  *) echo "usage: harness/oracle.sh py|bb|record" >&2; exit 2 ;;
esac

GOLD="harness/oracle"
TMPCYCLE="oracle-roundtrip"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/harness-oracle.XXXXXX")"
WORK="$(cd "$WORK" && pwd -P)"
cleanup() { rm -rf "$WORK" "$ROOT/harness/cycles/$TMPCYCLE"; }
trap cleanup EXIT

PASSED=0; FAILED=0; SKIPPED=0
ok()   { printf '  ok    %s\n' "$1"; PASSED=$((PASSED + 1)); }
bad()  { printf '  FAIL  %s\n' "$1"; FAILED=$((FAILED + 1)); }
note() { printf '  note  %s\n' "$1"; }
skip() { printf '  SKIP  %s\n' "$1"; SKIPPED=$((SKIPPED + 1)); }
head1(){ printf '\n%s\n' "$1"; }

# ---------------------------------------------------------------------------------
# which implementation
# ---------------------------------------------------------------------------------
REF=(python3)                     # the reference, used by `record` and by fixture prep
if [ "$MODE" = "bb" ]; then
  EXT="clj"
  STAMP=(bb harness/stamp_item_ids.clj)
  GEN=(bb harness/gen_checklist.clj)
  SCORE=(bb harness/score.clj)
  BOARD=(bb harness/scoreboard.clj)
else
  EXT="py"
  STAMP=(python3 harness/stamp_item_ids.py)
  GEN=(python3 harness/gen_checklist.py)
  SCORE=(python3 harness/score.py)
  BOARD=(python3 harness/scoreboard.py)
fi
STAMP_FILE="harness/stamp_item_ids.$EXT"
GEN_CMD="${GEN[*]}"

for f in stamp_item_ids gen_checklist score scoreboard; do
  if [ ! -f "harness/$f.$EXT" ]; then
    echo "oracle: no harness/$f.$EXT: there is nothing to judge in $MODE mode." >&2
    exit 2
  fi
done

# Pre-guard detection, py only. See the header.
HAS_STAMP_EMPTY=1; HAS_SCORE_JSON_GUARD=1; HAS_STATUS_FILTER=1
if [ "$MODE" = "py" ]; then
  grep -q "nothing to stamp yet" harness/stamp_item_ids.py || HAS_STAMP_EMPTY=0
  grep -q "is not valid JSON"    harness/score.py          || HAS_SCORE_JSON_GUARD=0
  grep -q "slice_status"         harness/gen_checklist.py  || HAS_STATUS_FILTER=0
fi

# ---------------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------------
# run DIR CMD...  -> RC, $WORK/out, $WORK/err
run() {
  local dir="$1"; shift
  ( cd "$dir" && "$@" ) >"$WORK/out" 2>"$WORK/err"
  RC=$?
}

# expect_text LABEL EXPECTED-FILE ACTUAL-FILE
expect_text() {
  if diff -u "$2" "$3" >"$WORK/diff" 2>&1; then
    ok "$1"
  else
    bad "$1"
    sed 's/^/        /' "$WORK/diff"
  fi
}

expect_rc() {  # expect_rc LABEL WANT GOT
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 (wanted exit $2, got $3)"; fi
}

expect_empty() {  # expect_empty LABEL FILE
  if [ ! -s "$2" ]; then ok "$1"; else bad "$1"; sed 's/^/        /' "$2"; fi
}

# A fixture repository: an empty tree with the implementation under test copied in, so
# the tools resolve their root to the fixture and never to this repository.
new_fixture() {
  local d="$WORK/fx-$1"
  rm -rf "$d"; mkdir -p "$d/harness" "$d/docs/plan"
  cp "$ROOT"/harness/*."$EXT" "$d/harness/" 2>/dev/null
  ( cd "$d" && pwd -P )
}

# Replace a fixture's absolute path with <ROOT> so an expectation can be written down.
norm() { sed "s#$1#<ROOT>#g" "$2" >"$2.n" && mv "$2.n" "$2"; }

# Mask the JSON parser's own words, which are the one part of one message that is not
# contract (harness/spec.feature @harness-034).
mask_json_detail() {
  sed 's#is not valid JSON (.*)\. A measurement#is not valid JSON (<DETAIL>). A measurement#' "$1" >"$1.n" && mv "$1.n" "$1"
}

echo "harness oracle: $MODE, in $(basename "$ROOT")"
[ "$MODE" = "py" ] && {
  [ "$HAS_STAMP_EMPTY" = 0 ]      && note "this copy's stamper has no empty-spec message: UPGRADE, required under bb"
  [ "$HAS_SCORE_JSON_GUARD" = 0 ] && note "this copy's score has no empty/non-JSON verdicts guard: UPGRADE, required under bb"
  [ "$HAS_STATUS_FILTER" = 0 ]    && note "this copy's gen_checklist has no planned-slice filter: UPGRADE, required under bb"
  true
}

# ---------------------------------------------------------------------------------
# record
# ---------------------------------------------------------------------------------
if [ "$MODE" = "record" ]; then
  mkdir -p "$GOLD"
  "${REF[@]}" harness/gen_checklist.py --out "$GOLD/checklist.golden.json" 2>"$WORK/err" || {
    echo "oracle record: gen_checklist refused:" >&2; cat "$WORK/err" >&2; exit 2; }
  echo "recorded $GOLD/checklist.golden.json ($(grep -c '"id"' "$GOLD/checklist.golden.json") items)"
  if [ -f harness/cycles/01/scores.json ]; then
    "${REF[@]}" harness/scoreboard.py 01 >"$GOLD/scoreboard-01.golden" || {
      echo "oracle record: scoreboard refused" >&2; exit 2; }
    echo "recorded $GOLD/scoreboard-01.golden"
  else
    echo "no harness/cycles/01/scores.json here: no scoreboard golden recorded"
  fi
  exit 0
fi

# =================================================================================
# CHECK 1  the checklist this repository's spec produces is byte identical
# =================================================================================
head1 "CHECK 1  checklist, byte for byte"
if [ ! -f "$GOLD/checklist.golden.json" ]; then
  bad "no $GOLD/checklist.golden.json: run 'harness/oracle.sh record' first"
else
  run "$ROOT" "${GEN[@]}" --out "$WORK/checklist.json"
  expect_rc "gen_checklist exits 0" 0 "$RC"
  cp "$GOLD/checklist.golden.json" "$WORK/gold.json"
  if cmp -s "$WORK/checklist.json" "$WORK/gold.json"; then
    ok "identical to $GOLD/checklist.golden.json"
    if [ -f harness/cycles/01/checklist.json ] && cmp -s "$WORK/checklist.json" harness/cycles/01/checklist.json; then
      ok "identical to the committed harness/cycles/01/checklist.json"
    else
      note "THE ITEM SET MOVED SINCE THE CUT: the spec now yields $(grep -c '"id"' "$WORK/checklist.json") items,"
      note "the committed harness/cycles/01/checklist.json carries $(grep -c '"id"' harness/cycles/01/checklist.json 2>/dev/null || echo 0)."
      note "That is the spec having grown, not an implementation defect. The golden is the judge here."
    fi
  elif [ "$MODE" = "py" ]; then
    bad "the reference implementation no longer reproduces $GOLD/checklist.golden.json"
    note "the spec has changed since the golden was recorded: re-record with 'harness/oracle.sh record', then re-run"
    diff -u "$WORK/gold.json" "$WORK/checklist.json" | head -40 | sed 's/^/        /'
  else
    bad "checklist differs from $GOLD/checklist.golden.json"
    diff -u "$WORK/gold.json" "$WORK/checklist.json" | head -60 | sed 's/^/        /'
  fi
  # The counts line is the tool's own report and is contract (@harness-013).
  printf 'checklist: {%s%s%s: %s}\n' "'" total "'" "$(grep -c '"id"' "$WORK/checklist.json")" >"$WORK/want"
  grep '^checklist: ' "$WORK/err" >"$WORK/got"
  expect_text "the counts line on stderr" "$WORK/want" "$WORK/got"
fi

# =================================================================================
# CHECK 2  the checklist derived from a fixture: ordering, null rule, the warts
# =================================================================================
head1 "CHECK 2  checklist from a fixture (ordering, null rule, the two warts)"
FX="$(new_fixture checklist)"
mkdir -p "$FX/docs/plan/01-a" "$FX/docs/plan/02-b"
cat >"$FX/docs/plan/01-a/spec.feature" <<'EOF'
Feature: F

  Rule: R one

    @item-01-002
    Scenario: the second one, first in the file
      Given a

    @item-01-001 @wip
    Scenario Outline:   a padded title
      Given <a>

  Rule: R two

@item-01-004

    # a comment between the tag and its scenario
    Scenario: reached at last
      Given a

    @item-01-005
    Scenario:
      Given a nameless one
EOF
cat >"$FX/docs/plan/02-b/spec.feature" <<'EOF'
Feature: G
    @item-02-001
    Scenario: under no rule at all
      Given a
EOF
cat >"$WORK/want" <<'EOF'
{
 "items": [
  {
   "id": "01-002",
   "name": "the second one, first in the file",
   "rule": "R one",
   "source": "docs/plan/01-a/spec.feature:6"
  },
  {
   "id": "01-001",
   "name": "a padded title",
   "rule": "R one",
   "source": "docs/plan/01-a/spec.feature:10"
  },
  {
   "id": "01-004",
   "name": "reached at last",
   "rule": "R two",
   "source": "docs/plan/01-a/spec.feature:18"
  },
  {
   "id": "02-001",
   "name": "under no rule at all",
   "rule": null,
   "source": "docs/plan/02-b/spec.feature:3"
  }
 ],
 "counts": {
  "total": 4
 }
}
EOF
run "$FX" "${GEN[@]}"
expect_rc "exits 0" 0 "$RC"
expect_text "items in file order, rule null under no Rule, pending tag survives, nameless scenario dropped" "$WORK/want" "$WORK/out"
printf "checklist: {'total': 4}\n" >"$WORK/want2"
expect_text "counts on stderr" "$WORK/want2" "$WORK/err"

# the whitespace-only title, which is an item whose name is one space (@harness-049)
FX="$(new_fixture wsname)"
mkdir -p "$FX/docs/plan/01-a"
printf '  @item-01-001\n  Scenario:   \n    Given a\n' >"$FX/docs/plan/01-a/spec.feature"
run "$FX" "${GEN[@]}"
if grep -q '"name": " "' "$WORK/out"; then ok 'a whitespace-only title is an item named " "'; else
  bad 'a whitespace-only title is an item named " "'; sed 's/^/        /' "$WORK/out"; fi

# a spec file with no scenarios is a zero-item checklist, not a refusal (@harness-022)
FX="$(new_fixture noscen)"
mkdir -p "$FX/docs/plan/01-a"
printf 'Feature: only a feature line\n' >"$FX/docs/plan/01-a/spec.feature"
cat >"$WORK/want" <<'EOF'
{
 "items": [],
 "counts": {
  "total": 0
 }
}
EOF
run "$FX" "${GEN[@]}"
expect_rc "a spec with no scenarios exits 0" 0 "$RC"
expect_text "a spec with no scenarios yields a zero-item checklist" "$WORK/want" "$WORK/out"

# --out writes the same bytes plus one newline, and creates parent directories
FX="$(new_fixture outfile)"
mkdir -p "$FX/docs/plan/01-a"
printf '  @item-01-001\n  Scenario: one\n' >"$FX/docs/plan/01-a/spec.feature"
run "$FX" "${GEN[@]}" --out "deep/er/still/checklist.json"
expect_rc "--out exits 0" 0 "$RC"
expect_empty "--out prints nothing to stdout" "$WORK/out"
if [ -f "$FX/deep/er/still/checklist.json" ]; then
  ok "--out creates missing parent directories"
  if [ "$(tail -c 1 "$FX/deep/er/still/checklist.json" | od -An -c | tr -d ' ')" = "\n" ]; then
    ok "--out ends the file with exactly one newline"
  else
    bad "--out ends the file with exactly one newline"
  fi
else
  bad "--out creates missing parent directories"
fi

# the planned-slice filter
FX="$(new_fixture status)"
mkdir -p "$FX/docs/plan/01-a" "$FX/docs/plan/02-b"
printf '  @item-01-001\n  Scenario: a one\n' >"$FX/docs/plan/01-a/spec.feature"
printf '  @item-02-001\n  Scenario: b one\n' >"$FX/docs/plan/02-b/spec.feature"
printf -- '---\nstatus: planned\n---\n' >"$FX/docs/plan/02-b/plan.md"
run "$FX" "${GEN[@]}" --out "c.json"
if [ "$HAS_STATUS_FILTER" = 1 ]; then
  printf 'gen_checklist: skipping 02-b (status: planned)\n' >"$WORK/want"
  expect_text "a planned slice is skipped, out loud, on stdout" "$WORK/want" "$WORK/out"
  if grep -q '"02-001"' "$FX/c.json"; then bad "a planned slice stays out of the checklist"; else
    ok "a planned slice stays out of the checklist"; fi
else
  note "pre-guard py: no planned-slice filter here, asserting the unfiltered behaviour"
  expect_empty "no skip line is printed" "$WORK/out"
  if grep -q '"02-001"' "$FX/c.json"; then ok "unfiltered: the planned slice is scored"; else
    bad "unfiltered: the planned slice is scored"; fi
fi

# =================================================================================
# CHECK 3  the stamper is a no-op on this repository's already-stamped spec
# =================================================================================
head1 "CHECK 3  the stamper is a no-op on an already-stamped spec"
if [ ! -d docs/plan ]; then
  skip "no docs/plan in this repository"
elif ! git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; then
  skip "not a git repository, cannot judge a no-op"
elif ! git -C "$ROOT" diff --quiet -- docs/plan/; then
  skip "docs/plan/ is already dirty in the working tree: cannot tell a stamper's writes from yours"
else
  run "$ROOT" "${STAMP[@]}"
  expect_rc "stamp exits 0" 0 "$RC"
  printf 'total stamped: 0\n' >"$WORK/want"
  expect_text "stamp reports nothing stamped" "$WORK/want" "$WORK/out"
  if git -C "$ROOT" diff --quiet -- docs/plan/; then
    ok "docs/plan/ is byte identical after stamping"
  else
    bad "THE STAMPER REWROTE THE SPEC. Restoring docs/plan/ from git."
    git -C "$ROOT" diff --stat -- docs/plan/ | sed 's/^/        /'
    git -C "$ROOT" checkout -- docs/plan/
  fi
fi

# =================================================================================
# CHECK 4  the stamper's stamping: numbering, indentation, idempotence
# =================================================================================
head1 "CHECK 4  the stamper stamps (numbering, indentation, idempotence)"
FX="$(new_fixture stamp)"
mkdir -p "$FX/docs/plan/03-slug" "$FX/docs/plan/07-other"
cat >"$FX/docs/plan/03-slug/spec.feature" <<'EOF'
Feature: F

  Rule: R one

    @item-03-002
    Scenario: already stamped
      Given a

    @wip @slow
    Scenario: has tags but no item tag
      Given a

  Rule: R two

    Scenario Outline: an outline
      Given <a>

    @item-03-009 @extra
    Scenario: high number already
      Given a
EOF
printf 'Feature: G\n    Scenario: in another slice\n      Given a\n' >"$FX/docs/plan/07-other/spec.feature"
cat >"$WORK/want-03" <<'EOF'
Feature: F

  Rule: R one

    @item-03-002
    Scenario: already stamped
      Given a

    @wip @slow
    @item-03-010
    Scenario: has tags but no item tag
      Given a

  Rule: R two

    @item-03-011
    Scenario Outline: an outline
      Given <a>

    @item-03-009 @extra
    Scenario: high number already
      Given a
EOF
cat >"$WORK/want-07" <<'EOF'
Feature: G
    @item-07-001
    Scenario: in another slice
      Given a
EOF
cat >"$WORK/want" <<'EOF'
stamped   2  docs/plan/03-slug/spec.feature
stamped   1  docs/plan/07-other/spec.feature
total stamped: 3
EOF
run "$FX" "${STAMP[@]}"
expect_rc "exits 0" 0 "$RC"
expect_text "the report names each changed file and the total" "$WORK/want" "$WORK/out"
expect_text "numbering runs past the highest tag in the file, indentation is copied" "$WORK/want-03" "$FX/docs/plan/03-slug/spec.feature"
expect_text "a second slice numbers from 001 on its own" "$WORK/want-07" "$FX/docs/plan/07-other/spec.feature"
cp "$FX/docs/plan/03-slug/spec.feature" "$WORK/after-03"
cp "$FX/docs/plan/07-other/spec.feature" "$WORK/after-07"
run "$FX" "${STAMP[@]}"
printf 'total stamped: 0\n' >"$WORK/want2"
expect_text "a second run reports nothing" "$WORK/want2" "$WORK/out"
expect_text "a second run changes nothing (03)" "$WORK/after-03" "$FX/docs/plan/03-slug/spec.feature"
expect_text "a second run changes nothing (07)" "$WORK/after-07" "$FX/docs/plan/07-other/spec.feature"

# =================================================================================
# CHECK 5  scoring round-trips: verdicts derived from a real record rebuild it exactly
# =================================================================================
head1 "CHECK 5  scoring round trip against the committed record"
if [ ! -f harness/cycles/01/scores.json ] || [ ! -f harness/cycles/01/checklist.json ]; then
  skip "no committed harness/cycles/01/scores.json in this repository: THE SCORER IS NOT EXERCISED HERE."
  note "run this check in a repository that has a scored cycle before trusting a candidate scorer."
else
  rm -rf "harness/cycles/$TMPCYCLE"
  mkdir -p "harness/cycles/$TMPCYCLE"
  cp harness/cycles/01/checklist.json "harness/cycles/$TMPCYCLE/checklist.json"
  bb -e '
    (require (quote [cheshire.core :as j]))
    (let [d (j/parse-string (slurp (first *command-line-args*)) true)]
      (spit (second *command-line-args*)
            (j/generate-string (map #(select-keys % [:id :verdict :evidence]) (:items d)))))
  ' harness/cycles/01/scores.json "$WORK/verdicts.json" 2>"$WORK/err"
  if [ ! -s "$WORK/verdicts.json" ]; then
    bad "could not derive a verdicts file from harness/cycles/01/scores.json"
    sed 's/^/        /' "$WORK/err"
  else
    TOTAL=$(grep -c '"id"' harness/cycles/01/scores.json)
    PASSN=$(grep -c '"verdict": "pass"' harness/cycles/01/scores.json)
    run "$ROOT" "${SCORE[@]}" "$TMPCYCLE" --verdicts "$WORK/verdicts.json"
    expect_rc "score exits 0" 0 "$RC"
    { printf 'scored cycle %s: %s/%s pass\n' "$TMPCYCLE" "$PASSN" "$TOTAL"
      printf 'filed: harness/cycles/%s/scores.json\n' "$TMPCYCLE"; } >"$WORK/want"
    expect_text "the scoring report" "$WORK/want" "$WORK/out"
    if cmp -s "harness/cycles/$TMPCYCLE/scores.json" harness/cycles/01/scores.json; then
      ok "the regenerated scores.json is byte identical to the committed one ($TOTAL items)"
    else
      bad "the regenerated scores.json differs from the committed one"
      diff -u harness/cycles/01/scores.json "harness/cycles/$TMPCYCLE/scores.json" | head -40 | sed 's/^/        /'
    fi
  fi
  rm -rf "harness/cycles/$TMPCYCLE"
  if [ -f harness/cycles/01/scores.json ]; then ok "the committed scores.json was not overwritten"; else
    bad "the committed scores.json is gone"; fi
fi

# unreached items are filed as failures, on a fixture (@harness-036, @harness-037)
FX="$(new_fixture unreached)"
mkdir -p "$FX/harness/cycles/01"
"${REF[@]}" - "$FX" <<'PYEOF'
import json, sys
root = sys.argv[1]
items = [{"id": f"01-{i:03d}", "name": f"n{i}", "rule": "R", "source": f"docs/plan/01-a/spec.feature:{i}"} for i in range(1, 11)]
open(f"{root}/harness/cycles/01/checklist.json", "w").write(
    json.dumps({"items": items, "counts": {"total": 10}}, ensure_ascii=False, indent=1) + "\n")
v = [{"id": f"01-{i:03d}", "verdict": ("pass" if i <= 4 else "fail"), "evidence": "e"} for i in range(1, 8)]
open(f"{root}/v.json", "w").write(json.dumps(v))
PYEOF
cat >"$WORK/want" <<'EOF'
scored cycle 01: 4/10 pass (3 unreached, recorded as fail)
filed: harness/cycles/01/scores.json
EOF
run "$FX" "${SCORE[@]}" 01 --verdicts v.json
expect_rc "exits 0" 0 "$RC"
expect_text "unreached items are counted out loud in the report" "$WORK/want" "$WORK/out"
if [ "$(grep -c '"id"' "$FX/harness/cycles/01/scores.json")" = "10" ]; then
  ok "the record carries all 10 items, so the denominator did not shrink"
else
  bad "the record carries all 10 items, so the denominator did not shrink"
fi
if grep -q '"evidence": "not scored: the grader never reached this item"' "$FX/harness/cycles/01/scores.json"; then
  ok "an unreached item is filed as a fail with the reason"
else
  bad "an unreached item is filed as a fail with the reason"
fi

# =================================================================================
# CHECK 6  the scoreboard on this repository's scored cycle, against a golden
# =================================================================================
head1 "CHECK 6  scoreboard, against the recorded golden"
if [ ! -f harness/cycles/01/scores.json ]; then
  skip "no committed harness/cycles/01/scores.json in this repository: NO GOLDEN TO COMPARE."
elif [ ! -f "$GOLD/scoreboard-01.golden" ]; then
  bad "no $GOLD/scoreboard-01.golden: run 'harness/oracle.sh record' first"
else
  run "$ROOT" "${BOARD[@]}" 01
  expect_rc "exits 0" 0 "$RC"
  expect_empty "stderr is empty" "$WORK/err"
  expect_text "stdout is identical to $GOLD/scoreboard-01.golden" "$GOLD/scoreboard-01.golden" "$WORK/out"
fi

# =================================================================================
# CHECK 7  the scoreboard's comparison branch, on synthetic cycles
# =================================================================================
head1 "CHECK 7  scoreboard comparison, denominators, and the rounding of a tie"
FX="$(new_fixture board)"
mkdir -p "$FX/harness/cycles/00" "$FX/harness/cycles/01" "$FX/harness/cycles/03" "$FX/harness/cycles/04"
"${REF[@]}" - "$FX" <<'PYEOF'
import json, sys
root = sys.argv[1]
def w(p, items): open(p, "w").write(json.dumps({"items": items}, ensure_ascii=False, indent=1) + "\n")
def it(i, v, pre="01"): return {"id": f"{pre}-{i:03d}", "name": f"n{i}", "rule": "R", "source": "s", "verdict": v, "evidence": ""}
# 00 -> 01: 16 shared (1 pass then 5 pass, one regression), 2 added. 1/16 and 5/16 are
# exact halves at one decimal, which is the whole point of this fixture.
w(f"{root}/harness/cycles/00/scores.json", [it(i, "pass" if i == 1 else "fail") for i in range(1, 17)])
w(f"{root}/harness/cycles/01/scores.json",
  [it(i, "pass" if 2 <= i <= 6 else "fail") for i in range(1, 17)] + [it(i, "fail") for i in (17, 18)])
# 03 -> 04: no overlap at all, which is the only route to the n/a rate
w(f"{root}/harness/cycles/03/scores.json", [it(i, "pass") for i in range(1, 3)])
w(f"{root}/harness/cycles/04/scores.json", [it(i, "fail", pre="09") for i in range(1, 4)])
PYEOF
cat >"$WORK/want" <<'EOF'
cycle 01: 5/18 checklist items pass

  against cycle 00, counting only the 16 items both cycles carried:
    cycle 00     1/16 =   6.2%
    cycle 01     5/16 =  31.2%   <- these two are the fair comparison
    now passing     5   <- repaired since then
    now failing     1   <- broken since then

  THE ITEM SET MOVED: 16 items -> 18 (2 added, 0 dropped), so the two headline
  rates count different things and must not be read against each other.
  The added items cover work last cycle's spec never asked for.
EOF
run "$FX" "${BOARD[@]}" 01
expect_rc "exits 0" 0 "$RC"
expect_text "both directions, the restated rates, and the moved denominator" "$WORK/want" "$WORK/out"
if grep -q '6\.3%' "$WORK/out"; then
  note "6.25 rounded AWAY from zero. Ties must round to EVEN here (@harness-046)."
fi
cat >"$WORK/want" <<'EOF'
cycle 04: 0/3 checklist items pass

  against cycle 03, counting only the 0 items both cycles carried:
    cycle 03      0/0 =   n/a
    cycle 04      0/0 =   n/a   <- these two are the fair comparison
    now passing     0   <- repaired since then
    now failing     0   <- broken since then

  THE ITEM SET MOVED: 2 items -> 3 (3 added, 2 dropped), so the two headline
  rates count different things and must not be read against each other.
  The added items cover work last cycle's spec never asked for.
EOF
run "$FX" "${BOARD[@]}" 04
expect_text "a zero denominator renders as n/a, and the block still prints" "$WORK/want" "$WORK/out"
cat >"$WORK/want" <<'EOF'
cycle 00: 1/16 checklist items pass

  no previous cycle to diff against: baseline cycle.
EOF
run "$FX" "${BOARD[@]}" 00
expect_text "a cycle with no predecessor takes the baseline branch" "$WORK/want" "$WORK/out"

# =================================================================================
# CHECK 8  every refusal: the same exit code AND the same words
# =================================================================================
head1 "CHECK 8  refusals, exit code and message"

# 8.1 an unstamped scenario
FX="$(new_fixture unstamped)"
mkdir -p "$FX/docs/plan/01-x"
printf 'Feature: F\n\n  Rule: R one\n\n    Scenario: unstamped one\n      Given a\n' >"$FX/docs/plan/01-x/spec.feature"
{ printf 'gen_checklist: some scenarios carry no item id. A checklist built now would quietly score fewer items than the spec contains, so stamp them first with %s:\n' "$STAMP_FILE"
  printf '  docs/plan/01-x/spec.feature:5: UNSTAMPED: unstamped one\n'; } >"$WORK/want"
run "$FX" "${GEN[@]}" --out c.json
expect_rc "unstamped scenario: exit 1" 1 "$RC"
expect_text "unstamped scenario: message" "$WORK/want" "$WORK/err"
expect_empty "unstamped scenario: nothing on stdout" "$WORK/out"
if [ -f "$FX/c.json" ]; then bad "unstamped scenario: no checklist is written"; else
  ok "unstamped scenario: no checklist is written"; fi

# 8.2 a spec directory with no feature files
FX="$(new_fixture nofeatures)"
mkdir -p "$FX/docs/plan/01-x"
printf 'gen_checklist: no NN-slug/spec.feature under <ROOT>/docs/plan. An empty checklist lets a scoring run report a flawless cycle in which nothing was ever checked, so none is written.\n' >"$WORK/want"
run "$FX" "${GEN[@]}"
norm "$FX" "$WORK/err"
expect_rc "no feature files: exit 1" 1 "$RC"
expect_text "no feature files: message" "$WORK/want" "$WORK/err"

# 8.3 the stamper on the same empty tree
if [ "$HAS_STAMP_EMPTY" = 1 ]; then
  { printf 'stamp: no NN-slug/spec.feature under <ROOT>/docs/plan: nothing to stamp yet.\n'
    printf '       (the spec is written first; stamping is idempotent, run it again then)\n'; } >"$WORK/want"
else
  note "pre-guard py: asserting the older stamper's silent zero"
  printf 'total stamped: 0\n' >"$WORK/want"
fi
run "$FX" "${STAMP[@]}"
norm "$FX" "$WORK/out"
expect_rc "no spec to stamp: exit 0" 0 "$RC"
expect_text "no spec to stamp: message" "$WORK/want" "$WORK/out"

# 8.4 scoring with no checklist present
FX="$(new_fixture nochecklist)"
{ printf 'score: no harness/cycles/01/checklist.json: generate it first:\n'
  printf '  %s --out harness/cycles/01/checklist.json\n' "$GEN_CMD"; } >"$WORK/want"
printf '[]' >"$FX/v.json"
run "$FX" "${SCORE[@]}" 01 --verdicts v.json
expect_rc "no checklist: exit 1" 1 "$RC"
expect_text "no checklist: message" "$WORK/want" "$WORK/err"

# a fixture with a checklist, for the verdicts-side refusals
FX="$(new_fixture verdicts)"
mkdir -p "$FX/harness/cycles/01"
cat >"$FX/harness/cycles/01/checklist.json" <<'EOF'
{
 "items": [
  {
   "id": "01-001",
   "name": "n",
   "rule": "r",
   "source": "docs/plan/01-a/spec.feature:1"
  }
 ],
 "counts": {
  "total": 1
 }
}
EOF
score_refusal() {  # score_refusal LABEL VERDICTS-CONTENT EXPECTED-FILE
  printf '%s' "$2" >"$FX/v.json"
  rm -f "$FX/harness/cycles/01/scores.json"
  run "$FX" "${SCORE[@]}" 01 --verdicts v.json
  mask_json_detail "$WORK/err"
  expect_rc "$1: exit 1" 1 "$RC"
  expect_text "$1: message" "$3" "$WORK/err"
  if [ -f "$FX/harness/cycles/01/scores.json" ]; then bad "$1: nothing is written"; else
    ok "$1: nothing is written"; fi
}
score_refusal_rc_only() {  # for the pre-guard py, where the words are a traceback
  printf '%s' "$2" >"$FX/v.json"
  rm -f "$FX/harness/cycles/01/scores.json"
  run "$FX" "${SCORE[@]}" 01 --verdicts v.json
  expect_rc "$1: exit 1 (pre-guard py, message is a traceback)" 1 "$RC"
  if [ -f "$FX/harness/cycles/01/scores.json" ]; then bad "$1: nothing is written"; else
    ok "$1: nothing is written"; fi
}

# 8.5 zero verdicts
printf 'score: 0 items scored. A run that graded nothing tells you the grading did not happen, not that every item failed. Nothing recorded.\n' >"$WORK/w-zero"
score_refusal "an empty verdicts list" '[]' "$WORK/w-zero"

# 8.6 an empty verdicts file
if [ "$HAS_SCORE_JSON_GUARD" = 1 ]; then
  score_refusal "an empty verdicts file" '' "$WORK/w-zero"
else
  note "pre-guard py: an empty verdicts file dies on the JSON parser, not on the guard"
  score_refusal_rc_only "an empty verdicts file" ''
fi

# 8.7 a verdicts file that is not JSON
if [ "$HAS_SCORE_JSON_GUARD" = 1 ]; then
  printf 'score: v.json is not valid JSON (<DETAIL>). A measurement that cannot be read is not a measurement: nothing was written.\n' >"$WORK/w-badjson"
  score_refusal "a verdicts file that is not JSON" 'not json{' "$WORK/w-badjson"
  printf 'score: v.json should hold a list of verdicts, found dict. Nothing was written.\n' >"$WORK/w-notlist"
  score_refusal "a verdicts file holding an object" '{"a": 1}' "$WORK/w-notlist"
else
  note "pre-guard py: a non-JSON verdicts file dies on the parser, an object on a type error"
  score_refusal_rc_only "a verdicts file that is not JSON" 'not json{'
  score_refusal_rc_only "a verdicts file holding an object" '{"a": 1}'
fi

# 8.8 a verdict for an id that is not on the checklist
printf 'score: verdicts for items not on the checklist (the spec is the only author of the item list): 09-999\n' >"$WORK/w-unknown"
score_refusal "a verdict for an unknown id" '[{"id": "09-999", "verdict": "pass", "evidence": "e"}]' "$WORK/w-unknown"

# 8.9 a verdict that is neither pass nor fail
printf "score: verdict must be 'pass' or 'fail' for: 01-001\n" >"$WORK/w-badverdict"
score_refusal "a verdict that is neither pass nor fail" '[{"id": "01-001", "verdict": "maybe", "evidence": "e"}]' "$WORK/w-badverdict"
score_refusal "a verdict with no verdict key" '[{"id": "01-001", "evidence": "e"}]' "$WORK/w-badverdict"

# 8.10 the scoreboard with no scores
FX="$(new_fixture noscores)"
printf 'scoreboard: nothing recorded at harness/cycles/07/scores.json\n' >"$WORK/want"
run "$FX" "${BOARD[@]}" 07
expect_rc "no scores: exit 1" 1 "$RC"
expect_text "no scores: message" "$WORK/want" "$WORK/err"

# 8.11 every slice filtered out is still an empty spec
if [ "$HAS_STATUS_FILTER" = 1 ]; then
  FX="$(new_fixture allplanned)"
  mkdir -p "$FX/docs/plan/01-a"
  printf '  @item-01-001\n  Scenario: a one\n' >"$FX/docs/plan/01-a/spec.feature"
  printf -- '---\nstatus: backlog\n---\n' >"$FX/docs/plan/01-a/plan.md"
  printf 'gen_checklist: no NN-slug/spec.feature under <ROOT>/docs/plan. An empty checklist lets a scoring run report a flawless cycle in which nothing was ever checked, so none is written.\n' >"$WORK/want"
  run "$FX" "${GEN[@]}"
  norm "$FX" "$WORK/err"
  expect_rc "every slice filtered out: exit 1" 1 "$RC"
  expect_text "every slice filtered out: the empty-spec message" "$WORK/want" "$WORK/err"
fi

# =================================================================================
head1 "$PASSED ok, $FAILED failed, $SKIPPED skipped"
if [ "$FAILED" -gt 0 ]; then
  echo "the candidate is NOT identical to the instrument in service."
  exit 1
fi
if [ "$SKIPPED" -gt 0 ]; then
  echo "green, with $SKIPPED check(s) this repository cannot make. Read the SKIP lines: they are"
  echo "not passes. A scorer is only proven where a scored cycle exists to round-trip."
fi
echo "green."
exit 0
