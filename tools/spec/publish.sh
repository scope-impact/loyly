#!/usr/bin/env bash
# Publish the event-model page to the gh-pages branch, which GitHub Pages serves at its root.
#
#   tools/spec/publish.sh [remote] [--dry-run]     remote defaults to scope-impact
#
# Rebuilds and checks the page, then commits it as index.html (with .nojekyll) on top of the
# remote's gh-pages, so every published version stays in that branch's history. --dry-run
# makes the commit in a throwaway worktree and shows it, without pushing.
set -euo pipefail
REMOTE=scope-impact; DRY=0
for a in "$@"; do case "$a" in --dry-run) DRY=1 ;; *) REMOTE="$a" ;; esac; done

root=$(git rev-parse --show-toplevel); cd "$root"
bash tools/spec/views/build.sh >/dev/null
bash tools/spec/views/check.sh
page="$root/tools/spec/views/dist/event-model.html"
src=$(git rev-parse --short HEAD); [ -z "$(git status --porcelain -- .pi tools/spec)" ] || src="$src+uncommitted"

wt=$(mktemp -d); trap 'git -C "$root" worktree remove --force "$wt" >/dev/null 2>&1 || true' EXIT
if git fetch -q "$REMOTE" gh-pages 2>/dev/null; then
  git worktree add -q --detach "$wt" FETCH_HEAD
else
  git worktree add -q --detach "$wt" && git -C "$wt" checkout -q --orphan gh-pages-new
fi
cd "$wt"
git rm -rqf . 2>/dev/null || true
cp "$page" index.html; : > .nojekyll
git add index.html .nojekyll
git commit -q -m "Publish the Pi harness event model from $src"
git log --oneline -1; git show --stat --format= HEAD | tail -3
if [ "$DRY" = 1 ]; then echo "dry run: not pushed to $REMOTE gh-pages"; exit 0; fi
git push -q "$REMOTE" HEAD:gh-pages
echo "pushed to $REMOTE gh-pages"
