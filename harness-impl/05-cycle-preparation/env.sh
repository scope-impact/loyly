# Sourced after cd to the candidate root. Never put a foreign toolchain on PATH:
# this directory is copied from a Linux/aarch64 builder to a native host gate.
P="$PWD"
export HOME="$P/.home" CLOJURE_USER_HOME="$P/.home/.clojure" CLJ_CONFIG="$P/.home/.clojure"
export GITLIBS="$P/.gitlibs"
mkdir -p "$P/.tmp" "$CLOJURE_USER_HOME"

bundled_java=false
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_CMD="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA_CMD="$(command -v java)"
elif [ "$(uname -s):$(uname -m)" = 'Linux:aarch64' ] &&
     [ -x "$P/.runtime/usr/lib/jvm/java-21-openjdk/bin/java" ]; then
  export JAVA_HOME="$P/.runtime/usr/lib/jvm/java-21-openjdk"
  JAVA_CMD="$JAVA_HOME/bin/java"
  bundled_java=true
else
  echo 'Java is required (install a native JDK, version 17 or newer).' >&2
  return 1
fi
export JAVA_CMD

# A native git always wins. The fallback and its shared libraries are Linux-only.
if ! command -v git >/dev/null 2>&1 &&
   [ "$(uname -s):$(uname -m)" = 'Linux:aarch64' ]; then
  export PATH="$PATH:$P/.runtime/usr/bin"
  export GIT_EXEC_PATH="$P/.runtime/usr/libexec/git-core"
  export GIT_SSL_CAINFO="$P/.tools/remote-certs.txt"
  export LD_LIBRARY_PATH="$P/.runtime/usr/lib:$P/.runtime/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
fi
# The bundled CLI is a portable Bash script, not a native executable. Native
# Clojure and Bash are preferred; in particular .runtime/bin never enters PATH.
export PATH="$PATH:$P/.tools"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Duser.home=$HOME -Djava.io.tmpdir=$P/.tmp -XX:TieredStopAtLevel=1 -XX:ActiveProcessorCount=2 -Xms128m -Xmx768m"
if [ "$bundled_java" = true ]; then
  export JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -Djavax.net.ssl.trustStore=$P/.tools/cacerts -Djavax.net.ssl.trustStorePassword=changeit"
fi
