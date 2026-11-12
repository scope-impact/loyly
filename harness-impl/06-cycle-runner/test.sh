#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
rm -rf target/classes
mkdir -p target/classes
clojure -J-Xmx1200m -J-XX:ActiveProcessorCount=2 -J-XX:+UseSerialGC -J-XX:TieredStopAtLevel=1 -M:test -e '(binding [*compile-path* "target/classes"] (compile (quote runner.main)) (compile (quote runner.standin)) (compile (quote runner.mailbox-worker)) (compile (quote runner.store-reader)))' >&2
clojure -Spath > target/classpath.txt
exec clojure -J-Xmx256m -J-XX:ActiveProcessorCount=2 -J-XX:+UseSerialGC -J-XX:TieredStopAtLevel=1 -M:test -m runner.suite
