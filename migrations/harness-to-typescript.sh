#!/usr/bin/env bash
# Move a sibling repository's harness from Babashka to the TypeScript/Effect tools löyly
# adopted on 2026-11-17, so that the ring is one text again.
#
#   migrations/harness-to-typescript.sh ../saunavuoro
#   migrations/harness-to-typescript.sh ../kalibra
#   migrations/harness-to-typescript.sh ../chartroom
#
# Run it from löyly, with the sibling cloned beside it and its working tree clean. It
# changes the sibling's working tree and stages the result. It does NOT commit: the oracle
# verdict it prints is the evidence a person reads before committing, exactly as the
# Babashka-for-Python swap was accepted.
#
# What it does, in order, and why each step is there:
#   1. Refuses unless every shared file in the sibling is the text löyly had before the
#      move (commit BASE below). Copying over a shared file someone has changed since would
#      silently throw their change away; that is a fork to settle by hand first.
#   2. Records the Babashka oracle's counts, if bb is installed, as the baseline.
#   3. Copies löyly's shared module files (the five tools, package.json and its lockfile,
#      the spec, the oracle, fence, gate, check and modules.sh) and deletes the .clj tools.
#   4. Renames the tools wherever the sibling's own files name them: harness/config.sh
#      (including FENCED_PATHS, where saunavuoro fences score and scoreboard), its harness
#      README, its root README and its docs. raw/ is never touched: sources are immutable.
#   5. Installs the dependency (npm ci --prefix harness) and ignores harness/node_modules.
#   6. Runs the oracle and the local check, and compares the counts with the baseline.

set -uo pipefail

LOYLY="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
BASE=40d008a~1     # löyly's last commit with the Babashka harness
SIB="${1:-}"
[[ -n "$SIB" && -d "$SIB/harness" ]] || { echo "usage: $0 <path to a sibling repository>" >&2; exit 2; }
SIB="$(cd "$SIB" && pwd -P)"
NAME="$(basename "$SIB")"
say() { printf '%s\n' "$*"; }
die() { printf 'migrate %s: %s\n' "$NAME" "$*" >&2; exit 1; }

TOOLS=(stamp_item_ids gen_checklist score scoreboard hjson)
OLD_SHARED=(harness/stamp_item_ids.clj harness/gen_checklist.clj harness/score.clj harness/scoreboard.clj
  harness/hjson.clj harness/spec.feature harness/oracle.sh harness/fence.sh harness/verify.sh harness/check.sh harness/modules.sh)
NEW_SHARED=(harness/stamp_item_ids.ts harness/gen_checklist.ts harness/score.ts harness/scoreboard.ts harness/hjson.ts
  harness/package.json harness/package-lock.json harness/spec.feature harness/oracle.sh harness/fence.sh
  harness/verify.sh harness/check.sh harness/modules.sh)

# ---- 1. preconditions -----------------------------------------------------------------
[[ -f "$SIB/harness/hjson.ts" && ! -f "$SIB/harness/hjson.clj" ]] && die "already migrated."
[[ -z "$(git -C "$SIB" status --porcelain)" ]] || die "the working tree is not clean; commit or stash first."
command -v node >/dev/null && command -v npm >/dev/null || die "node and npm are required."
forked=()
for f in "${OLD_SHARED[@]}"; do
  [[ -f "$SIB/$f" ]] || continue   # fenced or absent: nothing to overwrite
  git -C "$LOYLY" show "$BASE:$f" | cmp -s - "$SIB/$f" || forked+=("$f")
done
if (( ${#forked[@]} )); then
  printf 'migrate %s: these shared files differ from the text löyly had before the move:\n' "$NAME" >&2
  printf '  %s\n' "${forked[@]}" >&2
  die "settle that fork first; copying over it would throw the difference away."
fi

# ---- 2. baseline ------------------------------------------------------------------------
baseline=""
if command -v bb >/dev/null; then
  baseline="$(cd "$SIB" && ./harness/oracle.sh bb 2>&1 | grep -E '^[0-9]+ ok, ' | tail -1)"
  say "baseline, Babashka oracle: ${baseline:-no count line}"
else
  say "baseline: bb is not installed, so the Babashka oracle's counts cannot be recorded first."
fi

# ---- 3. the shared files ----------------------------------------------------------------
for f in "${NEW_SHARED[@]}"; do cp "$LOYLY/$f" "$SIB/$f"; done
for t in "${TOOLS[@]}"; do
  git -C "$SIB" rm -q --ignore-unmatch "harness/$t.clj"
done
chmod +x "$SIB"/harness/*.sh "$SIB"/harness/*.ts

# ---- 4. the names, in the sibling's own files ------------------------------------------
rename() {  # rename FILE: every way the old tools are named, to the new names (perl: macOS sed has no \b)
  perl -pi -e '
    s#bb harness/([a-z_]+)\.clj#node harness/$1.ts#g;
    s#\b(stamp_item_ids|gen_checklist|score|scoreboard|hjson)\.clj\b#$1.ts#g;
    s#five Babashka tools#five TypeScript tools#g;
    s#a Babashka tool can#a TypeScript tool can#g;
    s#([Tt])he Babashka tools resolve#$1he tools resolve#g;
    s#hjson\.ts, plan-dir#hjson.ts, planDir#g;
  ' "$1"
}
while IFS= read -r f; do
  case "$f" in raw/*|harness/oracle/*|*.json|*.png|*.jpg|*.pdf) continue ;; esac
  for s in "${NEW_SHARED[@]}"; do [[ "$f" == "$s" ]] && continue 2; done
  grep -Iq . "$SIB/$f" 2>/dev/null || continue           # binary
  grep -Eq '\.clj\b|Babashka|\bbb harness' "$SIB/$f" && rename "$SIB/$f"
done < <(git -C "$SIB" ls-files)
grep -qx 'harness/node_modules/' "$SIB/.gitignore" 2>/dev/null || printf 'harness/node_modules/\n' >>"$SIB/.gitignore"

# ---- 5. the dependency ------------------------------------------------------------------
(cd "$SIB" && npm ci --prefix harness --no-audit --no-fund >/dev/null) || die "npm ci --prefix harness failed."

# ---- 6. the verdict ---------------------------------------------------------------------
say ""
result="$(cd "$SIB" && ./harness/oracle.sh ts 2>&1)"; rc=$?
printf '%s\n' "$result" | grep -E '^  (FAIL|SKIP)'
counts="$(printf '%s\n' "$result" | grep -E '^[0-9]+ ok, ' | tail -1)"
say "TypeScript oracle: ${counts:-no count line} (exit $rc)"
[[ -n "$baseline" ]] && say "Babashka baseline: $baseline"
(cd "$SIB" && ./harness/check.sh --local >/dev/null 2>&1) && say "check.sh --local: green" || say "check.sh --local: RED, read it before anything else"
git -C "$SIB" add -A
say ""
say "staged in $SIB, not committed. Read the diff and the verdict above, then commit."
say "Things a script cannot write for you:"
say "  - an entry in harness/README.md's log of golden changes (löyly's is the model)"
say "  - if the counts differ from the baseline, the reason, before anything is committed"
(( rc == 0 )) || exit 1
