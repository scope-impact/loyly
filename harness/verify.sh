#!/usr/bin/env bash
# The ratifying gate: a "done" claim is ratified by a script the claimant does not
# control. Runs the implementation's own test suite, boots the app fresh, and proves
# the surface answers over HTTP. The builder's assertion is not evidence; this
# script's exit 0 is.
#
# The contract with impl/ is two entry points, so the gate never cares what stack
# the implementation chose (the spec does not name one either):
#
#   impl/test.sh          runs the whole suite; exits non-zero on any failure
#   impl/start.sh PORT    starts the app serving HTTP on PORT, in the foreground
#
# An implementation that cannot provide these two is not done, whatever it claims.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMPL="$ROOT/impl"
PORT=8901
URL="http://127.0.0.1:$PORT"

[[ -x "$IMPL/test.sh" && -x "$IMPL/start.sh" ]] || {
  echo "FATAL: impl/test.sh and impl/start.sh are the gate's contract and at least one"
  echo "       is missing or not executable. Nothing to ratify: build first."
  exit 1
}

# ---- [0] nothing else may be on the port we are about to grade ------------------
# An HTTP probe cannot tell its own backend from anyone else's. This lesson was
# learned from an orphaned server that answered a health probe long after
# its working directory had been deleted from under it: the probe passed instantly,
# nothing was booted, and the suite graded someone else's process. Refuse rather
# than kill: the port may be someone's dev server, and this script does not get to
# decide that.
if held="$(lsof -ti:$PORT 2>/dev/null)" && [[ -n "$held" ]]; then
  echo "FATAL: $URL is already served by pid(s): $held"
  echo "       This run would grade THAT process, not this checkout. Free the port first."
  exit 1
fi

# ---- [1] the suite, before anything is booted -----------------------------------
echo "=== impl/test.sh (the whole suite; the gate trusts its exit code, not its prose) ==="
"$IMPL/test.sh" || { echo "FATAL: suite red."; exit 1; }

# ---- [2] boot what we are grading, prove the surface answers --------------------
echo "=== impl/start.sh $PORT, then probe the real surface ==="
"$IMPL/start.sh" "$PORT" >/dev/null 2>&1 &
APP_PID=$!
cleanup() {
  kill "$APP_PID" 2>/dev/null
  lsof -ti:$PORT 2>/dev/null | xargs -r kill 2>/dev/null
}
trap cleanup EXIT

ok=""
for _ in $(seq 1 20); do
  if curl -sf -o /dev/null "$URL"; then ok=yes; break; fi
  sleep 0.5
done
[[ -n "$ok" ]] || { echo "FATAL: app never answered on $URL: a boot log is not a served page."; exit 1; }

# The page must actually be the app, not a default error page that returns 200.
curl -s "$URL" | grep -qi "sauna" \
  || { echo "FATAL: $URL answers but does not serve the app."; exit 1; }

echo "=== verify OK: suite green, surface answers on $URL ==="
