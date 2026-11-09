#!/bin/sh
# Regression: a Linux builder's bundled executables must never shadow the gate's
# native Java/Git/Bash on another OS. Simulate Darwin without executing a foreign
# binary. All files remain under the candidate's declared scope.
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
root=$PWD
fixture="$root/.test-state/launcher"
mkdir -p "$fixture/bin" "$fixture/jdk/bin"
printf '#!/bin/sh\nprintf "Darwin\\n"\n' > "$fixture/bin/uname"
printf '#!/bin/sh\nexit 0\n' > "$fixture/jdk/bin/java"
chmod +x "$fixture/bin/uname" "$fixture/jdk/bin/java"
env -i PATH="$fixture/bin:/usr/bin:/bin" HOME="$fixture" JAVA_HOME="$fixture/jdk" \
  EXPECTED_JAVA_HOME="$fixture/jdk" /bin/sh -c '
    set -eu
    . ./env.sh
    test "$JAVA_HOME" = "$EXPECTED_JAVA_HOME" || {
      echo "FAIL: env.sh replaced native JAVA_HOME with a foreign runtime" >&2; exit 1;
    }
    case "$PATH" in *"/.runtime/"*)
      echo "FAIL: foreign bundled executables shadow native tools" >&2; exit 1;; esac
    test -z "${LD_LIBRARY_PATH:-}"
    test -z "${GIT_EXEC_PATH:-}"
    test -z "${GIT_SSL_CAINFO:-}"
    case "$JAVA_TOOL_OPTIONS" in *trustStore*) exit 1;; esac
  '
printf 'PASS: native launcher/toolchain regression\n'
