#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
if [ -f target/classpath.txt ] && [ -f target/classes/runner/main.class ]; then
  exec java -Xmx256m -XX:ActiveProcessorCount=2 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED -cp "$(cat target/classpath.txt)" runner.main
fi
exec clojure -J-Xmx256m -J-XX:ActiveProcessorCount=2 -J-XX:+UseSerialGC -J-XX:TieredStopAtLevel=1 -J--add-opens=java.base/java.nio=ALL-UNNAMED -J--add-opens=java.base/sun.nio.ch=ALL-UNNAMED -M -m runner.main
