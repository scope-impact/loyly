#!/usr/bin/env bash
# Publish the event-model page to the gh-pages branch, which GitHub Pages serves at its root.
#
#   tools/spec/publish.sh [remote] [--dry-run]     remote defaults to scope-impact
#
# Rebuilds and checks the page, then commits it as index.html (with .nojekyll), and each
# tools/spec/eval/*.html as eval/<name>.html, and each tools/spec/replay/*.html (a run's replay,
# tools/spec/replay/build.sh) selected by the project's Runs :only list as replay/<name>.html,
# plus the cycle-26 Allure report at allure/cycle-26/. A grader-only report may be
# supplied with LOYLY_GRADER_ONLY_ALLURE_DIR; later publishes retain its Pages copy.
# All are committed on top of the remote's gh-pages, so every
# published version stays in that branch's history. --dry-run makes the commit in a throwaway
# worktree and shows it, without pushing.
set -euo pipefail
# The remote and branch default to the :publish entry of tools/spec/views/project.edn.
root=$(git rev-parse --show-toplevel)
cfg() { bb -e "(println (get-in (clojure.edn/read-string (slurp \"$root/tools/spec/views/project.edn\")) [:publish $1] \"$2\"))"; }
REMOTE=$(cfg :remote scope-impact); BRANCH=$(cfg :branch gh-pages); DRY=0
for a in "$@"; do case "$a" in --dry-run) DRY=1 ;; *) REMOTE="$a" ;; esac; done

cd "$root"
bash tools/spec/views/build.sh >/dev/null
bash tools/spec/views/check.sh
bash tools/spec/allure/build.sh 26
page="$root/tools/spec/views/dist/event-model.html"
src=$(git rev-parse --short HEAD); [ -z "$(git status --porcelain -- .pi tools/spec ':!tools/spec/views/dist')" ] || src="$src+uncommitted"

wt=$(mktemp -d); trap 'git -C "$root" worktree remove --force "$wt" >/dev/null 2>&1 || true' EXIT
if git fetch -q "$REMOTE" "$BRANCH" 2>/dev/null; then
  git worktree add -q --detach "$wt" FETCH_HEAD
else
  git worktree add -q --detach "$wt" && git -C "$wt" checkout -q --orphan gh-pages-new
fi
cd "$wt"
git rm -rqf . 2>/dev/null || true
cp "$page" index.html; : > .nojekyll
git add index.html .nojekyll
for f in "$root"/tools/spec/eval/*.html; do
  [ -e "$f" ] || continue
  mkdir -p eval; cp "$f" "eval/$(basename "$f")"; git add "eval/$(basename "$f")"
done
replays=$(bb -e "(let [c (clojure.edn/read-string (slurp \"$root/tools/spec/views/project.edn\"))] (doseq [p (:site-pages c) :when (= \"replay\" (:href p)) f (:only p)] (println f)))")
[ -n "$replays" ] || { echo 'no published replay selected in project.edn' >&2; exit 1; }
while IFS= read -r name; do
  f="$root/tools/spec/replay/$name"
  [ -f "$f" ] || { echo "missing selected replay: $f" >&2; exit 1; }
  mkdir -p replay; cp "$f" "replay/$name"; git add "replay/$name"
done <<< "$replays"
mkdir -p allure/cycle-26
cp -R "$root/.pi/harness/.state/pages/allure-cycle-26/." allure/cycle-26/
git add allure/cycle-26
grader_only=allure/grader-only-2026-11-18
if [ -n "${LOYLY_GRADER_ONLY_ALLURE_DIR:-}" ]; then
  test -f "$LOYLY_GRADER_ONLY_ALLURE_DIR/index.html"
  mkdir -p "$grader_only"
  cp -R "$LOYLY_GRADER_ONLY_ALLURE_DIR/." "$grader_only/"
  git add "$grader_only"
elif git cat-file -e "HEAD:$grader_only/index.html" 2>/dev/null; then
  git archive HEAD "$grader_only" | tar -x
  git add "$grader_only"
fi
git commit -q -m "Publish the Löyly spec v2 event model from $src"
git log --oneline -1; git show --stat --format= HEAD
if [ "$DRY" = 1 ]; then echo "dry run: not pushed to $REMOTE $BRANCH"; exit 0; fi
git push -q "$REMOTE" "HEAD:$BRANCH"
echo "pushed to $REMOTE $BRANCH"
