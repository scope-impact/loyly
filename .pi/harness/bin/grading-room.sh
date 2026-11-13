#!/usr/bin/env bash
# grading-room.sh <out-room> <slice NN> <candidate-dir> <spec-commit> [<contexts-dir>]
# Builds a fresh grading room for grader.sh: only what a black-box grader may read (AGENTS.md, "The
# instrument"; ../README.md, "The grader command"). From git at <spec-commit> (never the working
# tree, which a spec round may be editing): the slice's own spec folder, the other contexts'
# seam.md and event-model/, .pi/spec/decisions.md, and the gate's store check as reference
# (harness/v2/event_store_check.clj, event-store-check.sh, deps.edn). Copied, at the repository's
# own layout because every context reads ../../.pi/spec/<context>/event-model from where it sits:
# the gated candidate to harness-impl/NN-<context>/ (so the grader may run its start.sh and
# test.sh) and the promoted sibling contexts of <contexts-dir> (default harness-impl/ of this
# checkout) to harness-impl/<their names>/, without host build caches
# (.cpcache, .m2, .gitlibs, .home, .tmp, .test-state, test-work, target/classpath.txt; the
# candidate's classes/), which name host paths; the grader's VM fetches dependencies again. Plus checklist.txt (one item id per line,
# in file order) and an empty, writable work/. Nothing else of the repository: no builders,
# prompts, prior verdicts, harness/v2/cycles, scores or other candidates.
set -euo pipefail
OUT="${1:?out-room}"; NN="${2:?slice NN}"; CAND="${3:?candidate-dir}"; COMMIT="${4:?spec-commit}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
CTXS="${5:-$REPO/harness-impl}"
[[ "$NN" =~ ^[0-9][0-9]$ ]] || { echo "bad slice: $NN" >&2; exit 2; }
[[ -d "$CAND" ]] || { echo "candidate not found: $CAND" >&2; exit 2; }
[[ -e "$OUT" ]] && { echo "grading room exists (a room is always fresh): $OUT" >&2; exit 2; }
git -C "$REPO" cat-file -e "$COMMIT^{commit}" || { echo "not a commit: $COMMIT" >&2; exit 2; }
plan="$(git -C "$REPO" ls-tree --name-only "$COMMIT" .pi/plan/ | grep "^.pi/plan/$NN-" | head -1)"
[[ -n "$plan" ]] || { echo "no .pi/plan/$NN-* at $COMMIT" >&2; exit 2; }
ctx="$(basename "$(git -C "$REPO" show "$COMMIT:$plan")")"   # the link's target: ../spec/<context>
[[ -n "$ctx" ]] || { echo "cannot resolve $plan at $COMMIT" >&2; exit 2; }
mkdir -p "$OUT/work"
OUT="$(cd "$OUT" && pwd)"
others=()
for d in $(git -C "$REPO" ls-tree -d --name-only "$COMMIT" .pi/spec/); do
  c="$(basename "$d")"; [[ "$c" == "$ctx" ]] && continue
  git -C "$REPO" cat-file -e "$COMMIT:.pi/spec/$c/seam.md" 2>/dev/null && others+=(".pi/spec/$c/seam.md")
  git -C "$REPO" cat-file -e "$COMMIT:.pi/spec/$c/event-model" 2>/dev/null && others+=(".pi/spec/$c/event-model")
done
git -C "$REPO" archive "$COMMIT" ".pi/spec/$ctx" .pi/spec/decisions.md "${others[@]}" \
  harness/v2/event_store_check.clj harness/v2/event-store-check.sh harness/v2/deps.edn | tar -x -C "$OUT"
mkdir -p "$OUT/.pi/plan"; ln -s "../spec/$ctx" "$OUT/$plan"
grep -o '@item-[0-9][0-9]-[0-9][0-9][0-9]' "$OUT/.pi/spec/$ctx/spec.feature" | sed 's/^@item-//' > "$OUT/checklist.txt"
EXCL=(--exclude .cpcache --exclude .m2 --exclude .gitlibs --exclude .home --exclude .tmp
      --exclude .test-state --exclude test-work --exclude target/classpath.txt --exclude .git)
# The candidate's classes/ too: AOT output of its own build.sh, compiled on the host against host
# dependency checkouts; in the VM the freshly fetched sources are newer, Clojure loads them over the
# AOT classes and a protocol no longer matches its AOT implementation. start.sh runs from src.
rsync -a "${EXCL[@]}" --exclude /classes "$CAND/" "$OUT/harness-impl/$NN-$ctx/"
for d in "$CTXS"/[0-9][0-9]-*; do
  [[ -d "$d" ]] || continue
  [[ "$(basename "$d")" == "$NN-"* ]] && continue
  rsync -a "${EXCL[@]}" "$d/" "$OUT/harness-impl/$(basename "$d")/"
done
chmod -R u+w "$OUT"
{ echo "slice=$NN"; echo "context=$ctx"; echo "spec_commit=$(git -C "$REPO" rev-parse "$COMMIT")"
  echo "candidate=$(cd "$CAND" && pwd)"; echo "candidate_in_room=harness-impl/$NN-$ctx"; echo "contexts=$(cd "$CTXS" && pwd)"
  echo "items=$(wc -l < "$OUT/checklist.txt" | tr -d ' ')"; echo "built=$(date -u +%FT%TZ)"; } > "$OUT/.grading-room.txt"
echo "grading room $OUT: $ctx at $(git -C "$REPO" rev-parse --short "$COMMIT"), $(wc -l < "$OUT/checklist.txt" | tr -d ' ') items"
