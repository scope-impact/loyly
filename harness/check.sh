#!/usr/bin/env bash
# The entry point: run everything that judges this repository's harness, and refuse when
# the instrument is red.
#
#   harness/check.sh                 the instrument, then the adoption report
#   harness/check.sh --local         the instrument alone: no sibling repository is read
#   harness/check.sh --strict        as the default, and a fork across siblings also fails
#   harness/check.sh --hook          what the installed pre-commit hook runs
#   harness/check.sh --install-hook  install that hook into this clone's .git/hooks
#
# WHY THIS FILE EXISTS. Until 2026-09-08 nothing ever ran harness/oracle.sh. It made its
# assertions only when a person typed the command, there was no CI in any of the three
# repositories, no Makefile, and the gate and the fence mentioned the adoption check in a
# comment and called neither it nor the oracle. So the harness had a judge and no
# occasion: a check that reports and is ignored measures nothing, and the failure is
# silent by construction, because a judge nobody runs never says anything at all.
#
# WHAT MAKES IT FIRE. A pre-commit hook, and two callers inside the harness itself:
# harness/verify.sh will not ratify a "done" claim, and harness/fence.sh will not build a
# clean room, while the instrument here is red. The hook is the one that fires in the way
# this household actually works: these repositories have no remote that runs anything, so
# a CI workflow would be a file nothing executes, and the commit is the one moment every
# change passes through.
#
# THE HOOK IS NOT VERSIONED, which is why it is not the only caller. .git/hooks lives
# outside the tree, so a fresh clone has none until someone runs --install-hook, and
# --no-verify skips it. The two calls inside verify.sh and fence.sh are committed files
# and travel with the repository, so the strongest claims the harness makes, "this is
# ratified" and "this room is clean", cannot be made over a red instrument even where no
# hook was ever installed.
#
# THE THREE VERDICTS, and why only two of them stop a commit:
#
#   the oracle              HARD. Repository local, deterministic, byte for byte against
#                           the goldens. If it is red, this checkout's tools are not the
#                           tools the goldens recorded, and nothing downstream means what
#                           it says.
#   the adoption check,     HARD. Also repository local: the configuration is complete,
#   this repository alone   no value but APP_MARKER is empty, PLAN_DIR agrees with what the
#                           tools resolve, the per-repository files and the goldens are
#                           where the oracle needs them.
#   the adoption check,     REPORTED, unless --strict or a commit that touches a shared
#   across the siblings     module file. A sibling's working tree is not a property of the
#                           change being committed: another agent editing saunavuoro right
#                           now would otherwise block a commit in kalibra that has nothing
#                           to do with it. The exception is the case where it IS the
#                           change: a commit that edits a shared module file and leaves the
#                           copies forked is exactly the thing the adoption check exists to
#                           catch, so the hook escalates to strict for those commits alone.
#
# EXIT 2 FROM THE ORACLE IS A SKIP, NOT A FAILURE. It means a tool it judges is absent, and
# a fenced clean room is the case that produces it on purpose: saunavuoro fences
# harness/score.clj and harness/scoreboard.clj, so the gate running inside its clean room
# has fewer tools to judge than the repository it was cloned from. The skip is printed
# loudly and is not a pass.
#
# SHARED MODULE FILE. This text is byte identical in every repository that carries this
# harness. Everything a repository knows about itself is in harness/config.sh, and
# harness/modules.sh proves both halves of that.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 2

MODE="${1:-full}"
case "$MODE" in
  full|--local|--strict|--hook|--install-hook) ;;
  *) echo "usage: harness/check.sh [--local|--strict|--hook|--install-hook]" >&2; exit 2 ;;
esac

HOOK_FILE=".git/hooks/pre-commit"

# ---- install the hook -----------------------------------------------------------
# The hook's own text is three lines and is generated here, so every clone that installs
# one installs the same one and there is no fourth file to keep identical by hand. It
# resolves the repository root through git rather than trusting the working directory,
# because a commit can be made from a subdirectory.
if [[ "$MODE" == "--install-hook" ]]; then
  GITDIR="$(git rev-parse --git-dir 2>/dev/null)" || { echo "FATAL: not a git repository, so there is nowhere to install a hook." >&2; exit 1; }
  mkdir -p "$GITDIR/hooks"
  if [[ -e "$GITDIR/hooks/pre-commit" ]] && ! grep -q 'harness/check.sh' "$GITDIR/hooks/pre-commit"; then
    echo "FATAL: $GITDIR/hooks/pre-commit exists and is not ours. Merge by hand rather than"
    echo "       overwriting a hook somebody wrote for another reason."
    exit 1
  fi
  cat >"$GITDIR/hooks/pre-commit" <<'HOOK'
#!/usr/bin/env sh
# Installed by harness/check.sh --install-hook. Refuses a commit while the harness oracle
# is red. To commit anyway: git commit --no-verify, and say in the message why.
exec "$(git rev-parse --show-toplevel)/harness/check.sh" --hook
HOOK
  chmod +x "$GITDIR/hooks/pre-commit"
  echo "installed $GITDIR/hooks/pre-commit: every commit in this clone now runs harness/check.sh --hook."
  echo "It is not a committed file. A fresh clone has no hook until this is run there too."
  exit 0
fi

QUIET=""; STRICT=""
[[ "$MODE" == "--strict" ]] && STRICT=yes
[[ "$MODE" == "--hook" ]] && QUIET=yes

LOG="$(mktemp "${TMPDIR:-/tmp}/harness-check.XXXXXX")"
trap 'rm -f "$LOG"' EXIT

fails=0
say()  { [[ -n "$QUIET" ]] || echo "$*"; }
loud() { echo "$*"; }

# step LABEL COMMAND... -> RC, output in $LOG (and on screen unless quiet)
step() {
  local label="$1"; shift
  say ""
  say "=== $label ==="
  "$@" >"$LOG" 2>&1
  RC=$?
  [[ -n "$QUIET" ]] || cat "$LOG"
}

dump() { sed 's/^/    /' "$LOG"; }

# ---- the shared module files this commit touches ---------------------------------
# Only in hook mode, and only to decide whether the cross-repository comparison is
# advisory or binding. The list comes from harness/modules.sh itself rather than from a
# second copy here: two lists of the same files is the fork this harness exists to refuse.
if [[ "$MODE" == "--hook" ]]; then
  staged="$(git diff --cached --name-only --diff-filter=ACMR 2>/dev/null)"
  while IFS= read -r f; do
    [[ -n "$f" ]] || continue
    if grep -Fqx "$f" <<<"$staged"; then STRICT=yes; break; fi
  done < <("$ROOT/harness/modules.sh" --list-shared 2>/dev/null)
fi

# ---- 1. the oracle ---------------------------------------------------------------
step "the oracle: harness/oracle.sh bb" "$ROOT/harness/oracle.sh" bb
case "$RC" in
  0) say "the oracle is green." ;;
  2) loud "SKIP  the oracle cannot judge this tree: a tool it grades is absent."
     loud "      A fenced clean room is the case that does this on purpose. It is not a pass."
     dump ;;
  *) loud ""
     loud "FAILED  the harness oracle is red in $(basename "$ROOT")."
     [[ -n "$QUIET" ]] && dump
     fails=$((fails + 1)) ;;
esac

# ---- 2. the adoption check, this repository alone --------------------------------
step "the adoption check, this repository alone: harness/modules.sh" "$ROOT/harness/modules.sh"
if [[ "$RC" != 0 ]]; then
  loud ""
  loud "FAILED  this repository's own harness configuration is incomplete."
  [[ -n "$QUIET" ]] && dump
  fails=$((fails + 1))
fi

# ---- 3. the adoption check, across the siblings ----------------------------------
# The siblings are declared in harness/config.sh, so this file never knows which
# repositories exist. One that is not on this machine is a loud skip and not a failure: a
# clone of one repository alone is a legitimate thing to have, and a check nobody can make
# should say so rather than fail or, worse, pass.
if [[ "$MODE" != "--local" ]]; then
  ( set +u; . "$ROOT/harness/config.sh" >/dev/null 2>&1; printf '%s' "${SIBLING_REPOS-}" ) >"$LOG.sib"
  read -r -a DECLARED <<<"$(cat "$LOG.sib")"; rm -f "$LOG.sib"
  present=()
  for s in "${DECLARED[@]:-}"; do
    [[ -n "$s" ]] || continue
    if [[ -d "$ROOT/$s/harness" ]]; then present+=("$ROOT/$s"); else
      loud "SKIP  $s is declared in SIBLING_REPOS and is not on this machine, so nothing was compared with it."
    fi
  done
  if (( ${#present[@]} == 0 )); then
    loud "SKIP  no declared sibling is present, so the shared module files were compared with nothing."
  else
    step "the adoption check, across the siblings: harness/modules.sh --siblings" \
         "$ROOT/harness/modules.sh" --siblings "${present[@]}"
    if [[ "$RC" != 0 ]]; then
      if [[ -n "$STRICT" ]]; then
        loud ""
        loud "FAILED  the shared module files have forked across the repositories."
        [[ -n "$QUIET" ]] && dump
        fails=$((fails + 1))
      else
        loud ""
        loud "REPORTED, not refused: the shared module files differ across the repositories."
        loud "  This is advisory here because a sibling's working tree is not a property of this"
        loud "  change. It becomes binding the moment a commit touches a shared module file, and"
        loud "  harness/check.sh --strict makes it binding now."
        [[ -n "$QUIET" ]] && dump
      fi
    fi
  fi
fi

# ---- the verdict -----------------------------------------------------------------
if (( fails )); then
  loud ""
  if [[ "$MODE" == "--hook" ]]; then
    loud "COMMIT REFUSED: $fails check(s) red. Nothing has been committed."
  else
    loud "$fails check(s) red."
  fi
  loud ""
  loud "A red oracle means the tools in this checkout are not the tools the goldens recorded,"
  loud "so every number downstream of them is a measurement by an instrument nobody has"
  loud "calibrated. Fix the tool. If the words changed on purpose and nothing else did,"
  loud "re-record with 'harness/oracle.sh record' and write the reason into harness/README.md,"
  loud "under \"The goldens, and the two reasons one can change\". Re-recording to get a green"
  loud "destroys the only evidence that anything moved."
  if [[ "$MODE" == "--hook" ]]; then
    loud ""
    loud "To commit anyway: git commit --no-verify, and say in the message why."
  fi
  exit 1
fi
say ""
loud "harness OK: the oracle is green and this repository's configuration is complete."
exit 0
