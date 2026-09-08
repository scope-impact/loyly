#!/usr/bin/env bash
# The harness oracle: proof that the four harness tools still behave, byte for byte, as
# the instrument whose output the goldens recorded.
#
#   ./harness/oracle.sh bb       exercise the Babashka implementation (harness/*.clj)
#   ./harness/oracle.sh record   (re)record this repository's golden files from the
#                                Babashka implementation
#
# The goldens were first recorded from the Python implementation, and the Babashka one
# was accepted as its replacement on 2026-09-03 only because this oracle found no byte of
# difference between them; the Python was deleted the same day. So the reference is now
# the Babashka, and what `record` captures is whatever it does today, which is why
# recording is almost never the right response to a red run (see below).
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
# THE THREE GUARDS. Until 2026-09-03 two of the three repositories carried an older Python
# that lacked three guards: the stamper's empty-spec message, score's empty/non-JSON
# verdicts handling, and gen_checklist's planned-slice filter. A `py` mode then asserted
# each copy's actual behaviour and announced the gap as an UPGRADE. That mode went with
# the Python; all three guards are required of the implementation in service.
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

# WHO RUNS IT. Until 2026-09-08, a person typing the command, and nothing else: no CI, no
# Makefile, and neither the gate nor the fence called it. It now runs from
# harness/check.sh, which the pre-commit hook runs on every commit, and which
# harness/verify.sh and harness/fence.sh call before they ratify a build or build a clean
# room. Fifty-six assertions that only fire when somebody remembers them are not a check
# on anything.
#
# CHECK 9 JUDGES harness/modules.sh, which nothing judged until 2026-09-08. The argument
# for leaving it out was that what it compares is other repositories and the oracle has
# only this one. The oracle has built fixture trees since it was written, and two of those
# are two repositories.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT" || exit 2

MODE="${1:-}"
case "$MODE" in
  bb|record) ;;
  *) echo "usage: harness/oracle.sh bb|record" >&2; exit 2 ;;
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
PY=(python3)   # the interpreter alone, used to write fixtures inline; no harness tool is Python
EXT="clj"
STAMP=(bb harness/stamp_item_ids.clj)
GEN=(bb harness/gen_checklist.clj)
SCORE=(bb harness/score.clj)
BOARD=(bb harness/scoreboard.clj)
STAMP_FILE="harness/stamp_item_ids.$EXT"
GEN_CMD="${GEN[*]}"

for f in stamp_item_ids gen_checklist score scoreboard; do
  if [ ! -f "harness/$f.$EXT" ]; then
    echo "oracle: no harness/$f.$EXT: there is nothing to judge." >&2
    exit 2
  fi
done

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

# ---------------------------------------------------------------------------------
# record
# ---------------------------------------------------------------------------------
if [ "$MODE" = "record" ]; then
  mkdir -p "$GOLD"
  "${GEN[@]}" --out "$GOLD/checklist.golden.json" 2>"$WORK/err" || {
    echo "oracle record: gen_checklist refused:" >&2; cat "$WORK/err" >&2; exit 2; }
  echo "recorded $GOLD/checklist.golden.json ($(grep -c '"id"' "$GOLD/checklist.golden.json") items)"
  if [ -f harness/cycles/01/scores.json ]; then
    "${BOARD[@]}" 01 >"$GOLD/scoreboard-01.golden" || {
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
printf "gen_checklist: skipping 02-b (status: planned)\nchecklist: {'total': 1}\n" >"$WORK/want"
expect_text "a planned slice is skipped, out loud, on stderr" "$WORK/want" "$WORK/err"
expect_empty "with --out, stdout carries nothing" "$WORK/out"
if grep -q '"02-001"' "$FX/c.json"; then bad "a planned slice stays out of the checklist"; else
  ok "a planned slice stays out of the checklist"; fi

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
"${PY[@]}" - "$FX" <<'PYEOF'
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
"${PY[@]}" - "$FX" <<'PYEOF'
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
{ printf 'stamp: no NN-slug/spec.feature under <ROOT>/docs/plan: nothing to stamp yet.\n'
  printf '       (the spec is written first; stamping is idempotent, run it again then)\n'; } >"$WORK/want"
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

# 8.5 zero verdicts
printf 'score: 0 items scored. A run that graded nothing tells you the grading did not happen, not that every item failed. Nothing recorded.\n' >"$WORK/w-zero"
score_refusal "an empty verdicts list" '[]' "$WORK/w-zero"

# 8.6 an empty verdicts file
score_refusal "an empty verdicts file" '' "$WORK/w-zero"

# 8.7 a verdicts file that is not JSON
printf 'score: v.json is not valid JSON (<DETAIL>). A measurement that cannot be read is not a measurement: nothing was written.\n' >"$WORK/w-badjson"
score_refusal "a verdicts file that is not JSON" 'not json{' "$WORK/w-badjson"
printf 'score: v.json should hold a list of verdicts, found dict. Nothing was written.\n' >"$WORK/w-notlist"
score_refusal "a verdicts file holding an object" '{"a": 1}' "$WORK/w-notlist"

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
FX="$(new_fixture allplanned)"
mkdir -p "$FX/docs/plan/01-a"
printf '  @item-01-001\n  Scenario: a one\n' >"$FX/docs/plan/01-a/spec.feature"
printf -- '---\nstatus: backlog\n---\n' >"$FX/docs/plan/01-a/plan.md"
{ printf 'gen_checklist: skipping 01-a (status: backlog)\n'
  printf 'gen_checklist: no NN-slug/spec.feature under <ROOT>/docs/plan. An empty checklist lets a scoring run report a flawless cycle in which nothing was ever checked, so none is written.\n'; } >"$WORK/want"
run "$FX" "${GEN[@]}"
norm "$FX" "$WORK/err"
expect_rc "every slice filtered out: exit 1" 1 "$RC"
expect_text "every slice filtered out: the empty-spec message" "$WORK/want" "$WORK/err"

# =================================================================================
# CHECK 9  modules.sh, the adoption check, judged against fixture repositories
# =================================================================================
# Until 2026-09-08 this section did not exist, and harness/modules.sh was the one tool in
# harness/ that nothing judged. The reason given, in its own header and in the spec, was
# that what it compares is other repositories and the oracle has only this one. That was
# never true. The oracle has built temporary fixture trees since it was written, and two
# of those are two repositories: the tool resolves its root from its own file location, so
# a directory with a harness/ in it is as real to it as this checkout is.
#
# What is pinned here is the verdict lines and the exit codes, line by line and byte for
# byte, not the whole output. The report's shape (the table, the column widths) is a
# report for a person; the FAIL and ok lines are the tool saying what it found, and those
# are the product.
head1 "CHECK 9  the adoption check, against fixture repositories"

# A fixture repository for the adoption check: the whole harness copied in, so the tool
# finds the shared files, the per-repository files and the golden it now demands. Two
# fixtures built this way are identical, which is the point: every failure below has to be
# made on purpose, one difference at a time.
new_mod_fixture() {
  local d="$WORK/mod-$1"
  rm -rf "$d"; mkdir -p "$d/harness/oracle" "$d/docs/plan"
  cp "$ROOT"/harness/*.clj "$ROOT"/harness/*.sh "$ROOT/harness/spec.feature" "$d/harness/" 2>/dev/null
  cp "$ROOT/harness/oracle/checklist.golden.json" "$d/harness/oracle/" 2>/dev/null \
    || printf '{}\n' >"$d/harness/oracle/checklist.golden.json"
  chmod +x "$d/harness"/*.sh
  # A probe that differs in every fixture, because @harness-053 is that the probe's
  # content is never compared.
  printf '#!/usr/bin/env bash\necho "the fixture probe of %s"\n' "$1" >"$d/harness/probe.sh"
  chmod +x "$d/harness/probe.sh"
  ( cd "$d" && pwd -P )
}

mod_run() {  # mod_run DIR [ARG...] -> RC, stdout and stderr together in $WORK/out
  local d="$1"; shift
  ( cd "$d" && ./harness/modules.sh "$@" ) >"$WORK/out" 2>&1
  RC=$?
}

has_line() {  # has_line LABEL EXACT-LINE
  if grep -Fqx "$2" "$WORK/out"; then ok "$1"; else
    bad "$1"
    printf '        wanted this line, byte for byte:\n        %s\n' "$2"
    sed 's/^/        /' "$WORK/out" | head -30
  fi
}

has_line_matching() {  # has_line_matching LABEL EREGEX
  if grep -Eq "$2" "$WORK/out"; then ok "$1"; else
    bad "$1"
    printf '        wanted a line matching: %s\n' "$2"
    sed 's/^/        /' "$WORK/out" | head -30
  fi
}

no_line_matching() {  # no_line_matching LABEL EREGEX
  if grep -Eq "$2" "$WORK/out"; then
    bad "$1"
    printf '        this line should not be here:\n' 
    grep -E "$2" "$WORK/out" | sed 's/^/        /'
  else ok "$1"; fi
}

MA="$(new_mod_fixture a)"

# 9.0  the shared list is the tool's own, and harness/check.sh is on it
mod_run "$MA" --list-shared
expect_rc "--list-shared: exit 0" 0 "$RC"
has_line "the shared list names harness/check.sh, so the entry point cannot fork" "harness/check.sh"
has_line "the shared list names harness/modules.sh, so the check checks itself" "harness/modules.sh"

# 9.1  two identical repositories: green, and the closing line says so
MB="$(new_mod_fixture b)"
mod_run "$MA" --siblings "$MB"
expect_rc "two identical repositories: exit 0" 0 "$RC"
has_line "a shared file identical in both is reported as identical" "  ok    harness/verify.sh identical in 2 repositories"
has_line "the closing line claims no fork" "no fork: the shared module files are one text, and every difference is declared."

# 9.2  @harness-050  a shared module file that differs by one byte
printf '\n# one byte more\n' >>"$MB/harness/verify.sh"
mod_run "$MA" --siblings "$MB"
expect_rc "@harness-050 a forked sibling: exit 1" 1 "$RC"
has_line "@harness-050 the line reads FAIL and names the sibling" \
  "  FAIL  harness/verify.sh has forked in: mod-b (diff it against this one and reconcile to a single text)"

# 9.3  @harness-050  the same file missing in the sibling, which fails with a different word
MB="$(new_mod_fixture b)"
rm -f "$MB/harness/verify.sh"
mod_run "$MA" --siblings "$MB"
expect_rc "@harness-050 a file missing in a sibling: exit 1" 1 "$RC"
has_line "@harness-050 a missing file says missing, not forked" "  FAIL  harness/verify.sh is missing in: mod-b"

# 9.4  @harness-051  a configuration variable one repository declares alone
MB="$(new_mod_fixture b)"
grep -v '^APP_MARKER=' "$MB/harness/config.sh" >"$MB/harness/config.tmp" && mv "$MB/harness/config.tmp" "$MB/harness/config.sh"
mod_run "$MA" --siblings "$MB"
expect_rc "@harness-051 a variable declared alone: exit 1" 1 "$RC"
has_line "@harness-051 the line reads FAIL and names the sibling that lacks it" \
  "  FAIL  APP_MARKER is declared somewhere and absent in: mod-b (add it there, with its comment, today)"

# 9.5  @harness-052  a declared variable left empty, and the one exception
MB="$(new_mod_fixture b)"
sed 's/^APP_DIR=.*/APP_DIR=/' "$MB/harness/config.sh" >"$MB/harness/config.tmp" && mv "$MB/harness/config.tmp" "$MB/harness/config.sh"
mod_run "$MA" --siblings "$MB"
expect_rc "@harness-052 an empty APP_DIR: exit 1" 1 "$RC"
has_line "@harness-052 the line reads FAIL and names that repository" \
  "  FAIL  mod-b: APP_DIR is declared with an empty value, and only APP_MARKER may be empty"

MB="$(new_mod_fixture b)"
sed 's/^APP_MARKER=.*/APP_MARKER=/' "$MB/harness/config.sh" >"$MB/harness/config.tmp" && mv "$MB/harness/config.tmp" "$MB/harness/config.sh"
mod_run "$MA" --siblings "$MB"
expect_rc "@harness-052 an empty APP_MARKER is the exception: exit 0" 0 "$RC"
no_line_matching "@harness-052 nothing is said about APP_MARKER being empty" "APP_MARKER is declared with an empty value"

# 9.6  @harness-053  the probe: present, never compared, and executable
MC="$(new_mod_fixture c)"
MB="$(new_mod_fixture b)"
mod_run "$MA" --siblings "$MB" "$MC"
expect_rc "@harness-053 three different probes: exit 0" 0 "$RC"
has_line "@harness-053 the probe line reads ok" "  ok    harness/probe.sh present in all, content is each repository's own by design"
no_line_matching "@harness-053 no comparison of the probe's content is reported" "probe.sh (has forked|differs)"
chmod -x "$MB/harness/probe.sh"
mod_run "$MA" --siblings "$MB"
expect_rc "@harness-053 a probe that is not executable: exit 1" 1 "$RC"
has_line "@harness-053 the gate cannot run it, and the check says which repository" \
  "  FAIL  mod-b: harness/probe.sh is not executable, so the gate cannot run it"

# 9.7  @harness-054  PLAN_DIR against what the tools resolve
MB="$(new_mod_fixture b)"
sed 's#^PLAN_DIR=.*#PLAN_DIR=docs/specs#' "$MB/harness/config.sh" >"$MB/harness/config.tmp" && mv "$MB/harness/config.tmp" "$MB/harness/config.sh"
if ! command -v bb >/dev/null 2>&1; then
  skip "@harness-054 PLAN_DIR: bb is not on PATH, so the tools cannot be asked"
else
  mod_run "$MA" --siblings "$MB"
  expect_rc "@harness-054 PLAN_DIR disagreeing with the tools: exit 1" 1 "$RC"
  has_line "@harness-054 the line reads FAIL and shows both values" \
    "  FAIL  mod-b: PLAN_DIR is 'docs/specs' and the tools resolve 'docs/plan'. Change both or neither."
fi

# 9.8  @harness-055  values that differ are reported and not judged
MB="$(new_mod_fixture b)"; MC="$(new_mod_fixture c)"
sed 's#^APP_DIR=.*#APP_DIR=coldrun/grounded#' "$MB/harness/config.sh" >"$MB/harness/config.tmp" && mv "$MB/harness/config.tmp" "$MB/harness/config.sh"
sed 's#^APP_DIR=.*#APP_DIR=app#' "$MC/harness/config.sh" >"$MC/harness/config.tmp" && mv "$MC/harness/config.tmp" "$MC/harness/config.sh"
mkdir -p "$MB/coldrun/grounded" "$MC/app"
mod_run "$MA" --siblings "$MB" "$MC"
expect_rc "@harness-055 three different APP_DIR values: exit 0" 0 "$RC"
has_line_matching "@harness-055 the table has one column per repository" '^  variable *mod-a *mod-b *mod-c *$'
has_line_matching "@harness-055 the row whose values differ is marked" '^  APP_DIR .*<- differs$'
no_line_matching "@harness-055 a row whose values are all equal is not marked" '^  PORT .*<- differs$'

# 9.9  the recorded goldens are part of the model
MB="$(new_mod_fixture b)"
rm -f "$MB/harness/oracle/checklist.golden.json"
mod_run "$MA" --siblings "$MB"
expect_rc "a missing checklist golden: exit 1" 1 "$RC"
has_line "a missing checklist golden names the repository and what it blinds" \
  "  FAIL  harness/oracle/checklist.golden.json is missing in: mod-b (harness/oracle.sh cannot judge the checklist without it)"

MB="$(new_mod_fixture b)"
mkdir -p "$MB/harness/cycles/01"
printf '{}\n' >"$MB/harness/cycles/01/scores.json"
mod_run "$MA" --siblings "$MB"
expect_rc "a scored cycle with no scoreboard golden: exit 1" 1 "$RC"
has_line "a scored cycle with no scoreboard golden says what is unjudged" \
  "  FAIL  mod-b: harness/cycles/01/scores.json is here and harness/oracle/scoreboard-01.golden is not, so nothing judges the scoreboard. Record it."
printf 'x\n' >"$MB/harness/oracle/scoreboard-01.golden"
mod_run "$MA" --siblings "$MB"
expect_rc "the scoreboard golden recorded: exit 0" 0 "$RC"
has_line "the golden and the cycle it records are reported together" \
  "  ok    mod-b: harness/oracle/scoreboard-01.golden present, with harness/cycles/01/scores.json here to round-trip it"

# 9.10  the vacuous green: no sibling named
# Until 2026-09-08 this printed "identical in 1 repository" for every shared file, which is
# a pass earned by comparing a file with itself. Nothing else in this harness reports a
# comparison it did not make, and this one did.
mod_run "$MA"
expect_rc "no sibling named: exit 0, because the local checks did run" 0 "$RC"
no_line_matching "no file is reported identical to itself" "identical in 1 repositor"
has_line "the uncompared files are skips" "  SKIP  harness/verify.sh present here, and compared with nothing: name a sibling to compare it"
has_line "the closing line does not claim no fork" "no sibling was named, so the shared module files were compared with nothing."

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
