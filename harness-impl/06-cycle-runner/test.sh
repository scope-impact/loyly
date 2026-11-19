#!/bin/sh
set -eu
cd "$(dirname "$0")"
origin=$(pwd -P)
mkdir -p target
suite_dir=$(mktemp -d "$origin/target/standalone.XXXXXXXX")
trap 'rm -r -- "$suite_dir"' EXIT
mkdir "$suite_dir/candidate"
cp -R deps.edn start.sh src event-model test "$suite_dir/candidate/"
export LOYLY_TEST_CANDIDATE="$suite_dir/candidate"
cd "$LOYLY_TEST_CANDIDATE"
# The worktree itself can sit beneath a .pi directory. Give the copied build
# a filesystem view in which no ancestor exposes a .pi, plan or specification.
# Gondolin and the dependency caches keep their usual host paths.
cat > "$suite_dir/isolate.sb" <<'PROFILE'
(version 1)
(allow default)
(deny file-read* (regex #".*/[.]pi$") (regex #".*/[.]pi/(spec|plan)(/.*)?$") (with errno ENOENT))
(deny file-read* (subpath (param "ORIGINAL_SRC")) (subpath (param "ORIGINAL_MODEL")))
(allow process-exec (require-not (regex #".*/java$")) (with no-sandbox))
PROFILE
classpath=$(clojure -Spath -M:test)
sandbox-exec -D "ORIGINAL_SRC=$origin/src" -D "ORIGINAL_MODEL=$origin/event-model" \
  -f "$suite_dir/isolate.sb" java -cp "$classpath" clojure.main -m loyly.suite
