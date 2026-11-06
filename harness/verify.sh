#!/usr/bin/env bash
# The ratifying gate: a "done" claim is ratified by a script the claimant does not
# control. Runs the implementation's own test suite, boots it fresh, and proves the thing
# answering is this application through its domain behavior. The builder's assertion is
# not evidence; this script's exit 0 is.
#
# Same rules as the legacy gate (archive/legacy-harness/verify.sh); the transport is the
# product's real seam instead of HTTP. The harness is not a web product: its public
# interface is JSON-RPC over stdio (.pi/model/affordances.yaml), so the gate drives that.
#
#   <APP_DIR>/test.sh    runs the whole suite; exits non-zero on any failure
#   <APP_DIR>/start.sh   starts the product reading JSON-RPC on stdin, writing stdout
#
# Everything stack-specific lives behind those two files.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$ROOT/harness/config.sh"
APP="$ROOT/$APP_DIR"

[[ -x "$APP/test.sh" && -x "$APP/start.sh" ]] || {
  echo "FATAL: $APP_DIR/test.sh and $APP_DIR/start.sh are the gate's contract and at least one"
  echo "       is missing or not executable. Nothing to ratify: build first."
  exit 1
}

# The identity probe is checked before a suite runs: a gate that boots something it cannot
# identify has already lost the thing it exists to establish.
[[ -n "$APP_PROBE" && -x "$ROOT/$APP_PROBE" ]] || {
  echo "FATAL: harness/config.sh names '${APP_PROBE:-}' as this repository's identity probe,"
  echo "       and it is missing or not executable."
  exit 1
}

# ---- [0] the instrument, before anything is graded ------------------------------
echo "=== harness/check.sh --local (the instrument, before it is used to grade) ==="
"$ROOT/harness/check.sh" --local || {
  echo "FATAL: the harness itself did not pass. Nothing was booted and nothing was graded."
  exit 1
}

# ---- [1] the whole suite, untimed -------------------------------------------------
echo "=== $APP_DIR/test.sh (the whole suite; the gate trusts its exit code, not its prose) ==="
"$APP/test.sh" || { echo "FATAL: suite red."; exit 1; }

# ---- [2] a fresh boot inside a named budget, proved by domain behavior -----------
# THE BOOT BUDGET: thirty seconds from launching start.sh to the first correct answer. A
# build inside start.sh is inside the budget; a build inside test.sh is not. Legacy's ten
# seconds fit web products; the harness starts a JVM and ORC's durable system, which took
# 11.0s cold on 2026-11-05 with nothing left to move into test.sh, so the budget changed
# here and in @item-02-011 together, as the legacy rule requires.
BOOT_BUDGET_SECONDS=30
echo "=== $APP_DIR/start.sh, then the identity probe over stdio within ${BOOT_BUDGET_SECONDS}s ==="
if ! why="$("$ROOT/$APP_PROBE" "$APP/start.sh" "$BOOT_BUDGET_SECONDS")"; then
  echo "FATAL: the started product is not the application under test, or did not answer in time:"
  echo "       ${why:-the probe itself failed to run}"
  exit 1
fi
echo "=== verify OK: suite green, $APP_DIR/start.sh answers as this application ==="
echo "    $why"
