#!/usr/bin/env bash
# The adoption check: prove that the harness modules are one text here and in the sibling
# repositories, and that the configuration which replaces the differences is complete.
#
#   harness/modules.sh                                  check this repository alone
#   harness/modules.sh --siblings ../saunavuoro ../kalibra
#   harness/modules.sh --list-shared                    the shared file list, one per line
#
# WHY. A module is shareable when its configuration is documented and a repository unlike
# the others has adopted it without forking it. Three hand-kept copies satisfy neither
# half by themselves: a copy drifts quietly, and a difference that lives in the code
# instead of in the configuration is a fork nobody declared. This script is the half of
# that sentence a machine can check, and it is the reason the copies may stay copies
# rather than becoming a shared repository nobody wants to own.
#
# WHAT IT REFUSES:
#   - a shared module file that differs between two repositories, or is missing in one
#   - a declared per-repository file that is missing, or the probe not being executable
#   - a file in harness/ that is in none of the lists, so nothing has an opinion about it
#   - a recorded golden that is missing where the oracle needs it
#   - a configuration variable declared in one repository and absent in another
#   - a variable other than APP_MARKER left with an empty value
#   - PLAN_DIR disagreeing with the path the Babashka tools resolve for themselves
#
# WHAT IT ONLY REPORTS: configuration values that differ. That is what configuration is
# for, and the report is there so a difference nobody intended is at least read out loud.
#
# WITH NO SIBLING NAMED it compares nothing, and now says so. Until 2026-09-08 it printed
# "identical in 1 repository" for every shared file, which is a green earned by comparing
# a file with itself. Those lines are SKIP, the closing line no longer claims "no fork",
# and the exit code is still 0, because the checks that are about this repository alone
# did run and did pass.
#
# WHO RUNS IT. harness/check.sh, on every commit, through the pre-commit hook it installs.
# WHO JUDGES IT. harness/oracle.sh, CHECK 9, which builds fixture repositories and runs
# this file against them. Until 2026-09-08 this was the one tool nothing judged, on the
# argument that what it compares is other repositories and the oracle has only this one.
# The oracle builds its own repositories out of temporary directories, so that argument
# was never true: it was only untested.
#
# SHARED MODULE FILE. This text is byte identical in every repository that carries this
# harness, and it checks itself along with the rest.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

usage() { echo "usage: harness/modules.sh [--siblings PATH...] | --list-shared" >&2; exit 2; }

LIST_SHARED=""
REPOS=("$ROOT")
case "${1:-}" in
  "") ;;
  --list-shared) LIST_SHARED=yes; shift; (( $# == 0 )) || usage ;;
  --siblings)
    shift
    (( $# > 0 )) || usage
    for s in "$@"; do
      [[ -d "$s/harness" ]] || { echo "FATAL: $s has no harness/ directory: not a sibling that carries this harness." >&2; exit 2; }
      REPOS+=("$(cd "$s" && pwd)")
    done ;;
  *) usage ;;
esac

# module 1, measure: the tools, their spec and the oracle that judges them
# module 2, cleanroom: the fence
# module 3, gate: the ratifying gate
# and the entry point that runs them, and this file, which judges all three
SHARED=(
  harness/stamp_item_ids.clj harness/gen_checklist.clj harness/score.clj
  harness/scoreboard.clj harness/hjson.clj harness/spec.feature harness/oracle.sh
  harness/fence.sh
  harness/verify.sh
  harness/check.sh
  harness/modules.sh
)
# Declared per-repository files. Their CONTENT is never compared: each is this
# repository's own by design, and the design reason is written in the file itself.
# Required in every repository: a harness with no README is a module nobody can adopt.
PER_REPO=(harness/config.sh harness/probe.sh harness/README.md)

# Declared local files: they may exist in one repository and not the others, and they
# belong to that repository alone. Naming one here is a decision somebody made, which is
# the whole difference between a local file and an undeclared one.
#   harness/oracle.py   kalibra's domain oracle: a third implementation of one hand-worked
#                       calculation, used to check the recomputation against a table worked
#                       by hand. It judges kalibra's arithmetic and not this instrument,
#                       and it is nothing to do with harness/oracle.sh despite the name.
#                       The name is the real problem and a rename is the better fix; see
#                       harness/README.md.
LOCAL=(harness/oracle.py)

# Recorded goldens. Content is never compared either, for a different reason: each is
# recorded from this repository's own spec and its own scored cycle, so there is no single
# text for them to have. They are declared here because the oracle cannot judge anything
# without them. A repository that has quietly lost one gets an oracle that reports the
# missing golden on one line and passes everything else, which reads like a small gap and
# is a blind instrument.
GOLDEN=(harness/oracle/checklist.golden.json)
# A golden that is required exactly where the thing it records exists, written as
# GOLDEN:SOURCE. scoreboard-01 is recorded from cycle 01's scores, so a repository holding
# that cycle must hold the golden too. The other direction is reported and not refused:
# harness/cycles is what harness/fence.sh deletes, so a clean room legitimately holds the
# golden with no cycle behind it.
GOLDEN_IF=(harness/oracle/scoreboard-01.golden:harness/cycles/01/scores.json)

if [[ -n "$LIST_SHARED" ]]; then
  printf '%s\n' "${SHARED[@]}"
  exit 0
fi

fails=0
skips=0
bad()  { echo "  FAIL  $*"; fails=$((fails + 1)); }
ok()   { echo "  ok    $*"; }
skip() { echo "  SKIP  $*"; skips=$((skips + 1)); }
note() { echo "  note  $*"; }
name() { basename "$1"; }

echo "repositories: $(for r in "${REPOS[@]}"; do printf '%s ' "$(name "$r")"; done)"

# A shared file that a repository FENCES is absent from a clean room on purpose, and a
# clean room is where harness/verify.sh runs. saunavuoro fences harness/score.clj and
# harness/scoreboard.clj, so without this the gate would refuse inside the very room it
# exists to grade, reporting a missing module file as a fork. Being fenced is read from
# that repository's own FENCED_PATHS, so the exemption is exactly as wide as the list the
# repository wrote down, and it says which room it thinks it is in rather than passing
# quietly.
fenced_in() {  # fenced_in REPO PATH
  local paths p
  paths="$( set +u; . "$1/harness/config.sh" >/dev/null 2>&1; printf '%s' "${FENCED_PATHS-}" )"
  for p in $paths; do
    [[ "$2" == "$p" || "$2" == "$p"/* ]] && return 0
  done
  return 1
}

echo
echo "== the shared module files: one text, or a fork"
for f in "${SHARED[@]}"; do
  if [[ ! -f "$ROOT/$f" ]]; then
    if fenced_in "$ROOT" "$f"; then
      note "$f is absent here and is in this repository's FENCED_PATHS: a clean room, not a fork"
    else
      bad "$f is missing here"
    fi
    continue
  fi
  if (( ${#REPOS[@]} == 1 )); then
    skip "$f present here, and compared with nothing: name a sibling to compare it"
    continue
  fi
  missing=() forked=() fenced=()
  for r in "${REPOS[@]:1}"; do
    if [[ ! -f "$r/$f" ]]; then
      if fenced_in "$r" "$f"; then fenced+=("$(name "$r")"); else missing+=("$(name "$r")"); fi
    elif ! cmp -s "$ROOT/$f" "$r/$f"; then forked+=("$(name "$r")"); fi
  done
  if (( ${#missing[@]} )); then
    bad "$f is missing in: ${missing[*]}"
  elif (( ${#forked[@]} )); then
    bad "$f has forked in: ${forked[*]} (diff it against this one and reconcile to a single text)"
  elif (( ${#fenced[@]} )); then
    note "$f is fenced and absent in: ${fenced[*]}, and identical everywhere it is present"
  else
    ok "$f identical in ${#REPOS[@]} repositories"
  fi
done

echo
echo "== the per-repository files: present, and never compared"
for f in "${PER_REPO[@]}"; do
  missing=()
  for r in "${REPOS[@]}"; do
    [[ -f "$r/$f" ]] || missing+=("$(name "$r")")
  done
  if (( ${#missing[@]} )); then
    bad "$f is missing in: ${missing[*]}"
  else
    ok "$f present in all, content is each repository's own by design"
  fi
done
for r in "${REPOS[@]}"; do
  [[ -f "$r/harness/probe.sh" && ! -x "$r/harness/probe.sh" ]] && bad "$(name "$r"): harness/probe.sh is not executable, so the gate cannot run it"
done

echo
echo "== the recorded goldens: present where the oracle needs them"
for f in "${GOLDEN[@]}"; do
  missing=()
  for r in "${REPOS[@]}"; do
    [[ -f "$r/$f" ]] || missing+=("$(name "$r")")
  done
  if (( ${#missing[@]} )); then
    bad "$f is missing in: ${missing[*]} (harness/oracle.sh cannot judge the checklist without it)"
  else
    ok "$f present in all, content is each repository's own recording"
  fi
done
for pair in "${GOLDEN_IF[@]}"; do
  g="${pair%%:*}"; src="${pair#*:}"
  for r in "${REPOS[@]}"; do
    if [[ -f "$r/$src" && ! -f "$r/$g" ]]; then
      bad "$(name "$r"): $src is here and $g is not, so nothing judges the scoreboard. Record it."
    elif [[ ! -f "$r/$src" && -f "$r/$g" ]]; then
      note "$(name "$r"): $g records a cycle absent from this tree at $src. Expected in a clean room, a stale recording anywhere else."
    elif [[ -f "$r/$g" ]]; then
      ok "$(name "$r"): $g present, with $src here to round-trip it"
    else
      ok "$(name "$r"): no $src, so no $g is expected"
    fi
  done
done

echo
echo "== every file in harness/ is in one of the lists above"
# An undeclared file is one this check has no opinion about: it is neither held identical
# nor declared different, so a copy of it could drift for a year and nothing would say so.
# harness/oracle.py sat in kalibra like that until 2026-09-08. Directories are not walked,
# because harness/cycles and harness/oracle hold recordings rather than module files, and
# those have their own sections above.
for r in "${REPOS[@]}"; do
  undeclared=0
  while IFS= read -r f; do
    rel="harness/$(basename "$f")"
    declared=""
    for a in "${SHARED[@]}" "${PER_REPO[@]}" "${LOCAL[@]}"; do
      [[ "$a" == "$rel" ]] && { declared=yes; break; }
    done
    if [[ -z "$declared" ]]; then
      bad "$(name "$r"): $rel is in no list, so nothing here has an opinion about it. Add it to SHARED, PER_REPO or LOCAL, with the reason."
      undeclared=$((undeclared + 1))
    fi
  done < <(find "$r/harness" -maxdepth 1 -type f 2>/dev/null | sort)
  (( undeclared )) || ok "$(name "$r"): every file directly in harness/ is declared"
done

# ---- the configuration ----------------------------------------------------------
keys_of() { grep -Eo '^[A-Za-z_][A-Za-z0-9_]*=' "$1/harness/config.sh" 2>/dev/null | tr -d '=' | sort -u; }
value_of() ( set +u; . "$1/harness/config.sh" >/dev/null 2>&1; printf '%s' "${!2-}" )

echo
echo "== the configuration: complete, or a variable one repository has alone"
ALL_KEYS="$(for r in "${REPOS[@]}"; do keys_of "$r"; done | sort -u)"
[[ -n "$ALL_KEYS" ]] || bad "no configuration variables found at all: harness/config.sh is unreadable"
for k in $ALL_KEYS; do
  missing=()
  for r in "${REPOS[@]}"; do
    keys_of "$r" | grep -qx "$k" || missing+=("$(name "$r")")
  done
  if (( ${#missing[@]} )); then
    bad "$k is declared somewhere and absent in: ${missing[*]} (add it there, with its comment, today)"
  else
    ok "$k declared in every repository"
  fi
done
for k in $ALL_KEYS; do
  [[ "$k" == "APP_MARKER" ]] && continue
  for r in "${REPOS[@]}"; do
    [[ -n "$(value_of "$r" "$k")" ]] || bad "$(name "$r"): $k is declared with an empty value, and only APP_MARKER may be empty"
  done
done

echo
echo "== PLAN_DIR against the path the tools resolve for themselves"
if ! command -v bb >/dev/null 2>&1; then
  echo "  SKIP  bb is not on PATH here, so the tools cannot be asked. This is not a pass."
else
  for r in "${REPOS[@]}"; do
    declared="$(value_of "$r" PLAN_DIR)"
    resolved="$(cd "$r" && bb -e '(require (quote [babashka.classpath :as cp])) (cp/add-classpath "harness") (require (quote hjson)) (print (hjson/plan-dir "."))' 2>/dev/null)"
    resolved="${resolved#./}"
    if [[ -z "$resolved" ]]; then
      bad "$(name "$r"): could not ask harness/hjson.clj where the plan tree is"
    elif [[ "$declared" != "$resolved" ]]; then
      bad "$(name "$r"): PLAN_DIR is '$declared' and the tools resolve '$resolved'. Change both or neither."
    else
      ok "$(name "$r"): PLAN_DIR '$declared' agrees with the tools"
    fi
  done
fi

echo
echo "== the configuration values, reported and not judged"
printf '  %-14s' "variable"
for r in "${REPOS[@]}"; do printf '%-24s' "$(name "$r")"; done
echo
for k in $ALL_KEYS; do
  first="$(value_of "${REPOS[0]}" "$k")"
  differs=""
  widest=0
  for r in "${REPOS[@]}"; do
    v="$(value_of "$r" "$k")"
    (( ${#v} > widest )) && widest=${#v}
    [[ "$v" == "$first" ]] || differs="  <- differs"
  done
  # A value too wide for its column is listed under its own name rather than printed into
  # the next column: a report that runs together is a report nobody checks.
  if (( widest > 22 )); then
    echo "  $k$differs"
    for r in "${REPOS[@]}"; do printf '      %-18s%s\n' "$(name "$r")" "$(value_of "$r" "$k")"; done
  else
    printf '  %-14s' "$k"
    for r in "${REPOS[@]}"; do printf '%-24s' "$(value_of "$r" "$k")"; done
    echo "$differs"
  fi
done

echo
if (( fails )); then
  echo "$fails failure(s). A forked module file is not a difference of opinion: it is the"
  echo "instrument reporting a difference that was never in the thing measured."
  exit 1
fi
if (( skips )); then
  echo "no sibling was named, so the shared module files were compared with nothing."
  echo "$skips check(s) not made, and a skip is not a pass. Name the siblings to make them."
  exit 0
fi
echo "no fork: the shared module files are one text, and every difference is declared."
