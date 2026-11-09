#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
mkdir -p target/classes
# Dependencies are pinned; application classes are rebuilt from source every suite run.
rm -rf target/classes/runner
clojure -M -e '(binding [*compile-path* "target/classes"] (compile (quote runner.main)))' >&2
