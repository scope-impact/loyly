#!/usr/bin/env bash
# The ratifying gate: a "done" claim is ratified by a script the claimant does not
# control. Runs the implementation's own test suite, boots the app fresh, and proves
# the surface answers over HTTP and is this application. The builder's assertion is not
# evidence; this script's exit 0 is.
#
# The contract with the application under test is two entry points, so the gate never
# cares what stack the implementation chose (the spec does not name one either, and must
# not: a spec that names a stack has stopped being regenerable):
#
#   <APP_DIR>/test.sh          runs the whole suite; exits non-zero on any failure
#   <APP_DIR>/start.sh PORT    starts the app serving HTTP on PORT, in the foreground
#
# An implementation that cannot provide these two is not done, whatever it claims.
# Everything stack-specific lives BEHIND those two files: whether the suite is a compiled
# build or an interpreter, and whether start.sh serves a built bundle or runs a dev
# server, is the implementation's business and never this script's.
#
# SHARED MODULE FILE. This text is byte identical in every repository that carries this
# harness. Everything a repository knows about itself is in harness/config.sh, and
# harness/modules.sh proves both halves of that.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$ROOT/harness/config.sh"
APP="$ROOT/$APP_DIR"
URL="http://127.0.0.1:$PORT"

[[ -x "$APP/test.sh" && -x "$APP/start.sh" ]] || {
  echo "FATAL: $APP_DIR/test.sh and $APP_DIR/start.sh are the gate's contract and at least one"
  echo "       is missing or not executable. Nothing to ratify: build first."
  exit 1
}

# The identity probe is checked here, before a suite runs, because a gate that boots an
# application it cannot identify has already lost the thing it exists to establish.
[[ -n "$APP_PROBE" && -x "$ROOT/$APP_PROBE" ]] || {
  echo "FATAL: harness/config.sh names '${APP_PROBE:-}' as this repository's identity probe,"
  echo "       and it is missing or not executable. A gate with no identity proof ratifies"
  echo "       whatever happens to answer on the port."
  exit 1
}

# ---- [0] the port must be free before anything is graded ------------------------
# An HTTP probe cannot tell this run's server apart from any other process that
# happens to answer on the same port. An orphaned process from a dead run once
# answered a health probe long after its working directory had been deleted from
# under it: the probe passed at once, nothing had been booted, and the suite graded
# the wrong process. The gate refuses instead of killing whatever holds the port,
# because it might be a dev server someone is using, and that is not this script's
# call to make.
if held="$(lsof -ti:$PORT 2>/dev/null)" && [[ -n "$held" ]]; then
  echo "FATAL: $URL is already taken by pid(s): $held"
  echo "       This run would measure that process instead of this checkout. Free the port first."
  exit 1
fi

# ---- [1] the suite, before anything is booted -----------------------------------
echo "=== $APP_DIR/test.sh (the whole suite; the gate trusts its exit code, not its prose) ==="
"$APP/test.sh" || { echo "FATAL: suite red."; exit 1; }

# ---- [2] boot what we are grading, prove the surface answers --------------------
echo "=== $APP_DIR/start.sh $PORT, then probe the real surface ==="
"$APP/start.sh" "$PORT" >/dev/null 2>&1 &
APP_PID=$!
cleanup() {
  kill "$APP_PID" 2>/dev/null
  # Reap it, so job control does not print "Terminated: 15" AFTER the verify OK line.
  # A noisy line under a green result reads like a failure to whoever finds this in a log.
  wait "$APP_PID" 2>/dev/null
  lsof -ti:$PORT 2>/dev/null | xargs -r kill 2>/dev/null
}
trap cleanup EXIT

ok=""
for _ in $(seq 1 20); do
  if curl -sf -o /dev/null "$URL"; then ok=yes; break; fi
  sleep 0.5
done
[[ -n "$ok" ]] || { echo "FATAL: app never answered on $URL: a boot log is not a served page."; exit 1; }

# ---- [3] the surface must be THIS app, not a page that returns 200 ---------------
# Two checks, cheap then real. The cheap one is the name: a served page that does not
# even carry APP_MARKER is not worth probing further. It proves nothing on its own, and
# a repository that declares APP_MARKER empty is saying so plainly.
if [[ -n "$APP_MARKER" ]]; then
  curl -s "$URL" | grep -qi "$APP_MARKER" \
    || { echo "FATAL: $URL answers but does not serve the app."; exit 1; }
else
  echo "    harness/config.sh declares no APP_MARKER: the name check is skipped, and the"
  echo "    probe below is the whole identity proof."
fi

# The real one is APP_PROBE, and it is a per-repository file because a name is not
# evidence: names are carried by templates, error pages and placeholders, and a
# framework's own starter page with the product's name in its title passes a name check.
# A probe has to demand something only the real product could produce, which is domain
# behaviour, which is code and not a value. The gate therefore asks a question it cannot
# itself formulate, and fails with the answer it is given.
if ! why="$("$ROOT/$APP_PROBE" "$URL")"; then
  echo "FATAL: $URL answers, but it is not the application under test:"
  echo "       ${why:-the probe itself failed to run}"
  exit 1
fi

echo "=== verify OK: suite green, $URL serves this application ==="
echo "    $why"
