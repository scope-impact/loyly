#!/usr/bin/env bash
# probe.sh: prove the thing answering is THIS application, by domain behavior, not a name.
# Launch start.sh, send cycle-contract over stdio (.pi/model/rpc.yaml), and require, inside
# the boot budget, a response with the request's id that names the harness and lists the
# cycle stages fence, regenerate, gate, score, scoreboard in order (@item-02-010).
set -uo pipefail
START="${1:?usage: probe.sh START_SCRIPT BUDGET_SECONDS}"
BUDGET="${2:?usage: probe.sh START_SCRIPT BUDGET_SECONDS}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$ROOT/harness/config.sh"
exec python3 - "$START" "$BUDGET" "${APP_MARKER:?probe.sh needs APP_MARKER}" <<'PY'
import json, os, select, signal, subprocess, sys, time
start, budget, marker = sys.argv[1], float(sys.argv[2]), sys.argv[3]
expected = ["fence", "regenerate", "gate", "score", "scoreboard"]
began = time.monotonic()
proc = subprocess.Popen([start], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                        stderr=subprocess.DEVNULL, start_new_session=True, text=True)
def stop():
    try: os.killpg(proc.pid, signal.SIGTERM)
    except ProcessLookupError: pass
    try: proc.wait(timeout=5)
    except subprocess.TimeoutExpired: os.killpg(proc.pid, signal.SIGKILL)
try:
    proc.stdin.write(json.dumps({"id": 1, "method": "cycle-contract", "params": {}}) + "\n")
    proc.stdin.flush()
    while True:
        left = budget - (time.monotonic() - began)
        if left <= 0:
            print(f"no answer to cycle-contract within the {budget:g}s boot budget"); sys.exit(1)
        ready, _, _ = select.select([proc.stdout], [], [], left)
        if not ready: continue
        line = proc.stdout.readline()
        if not line:
            print("start.sh exited before answering cycle-contract"); sys.exit(1)
        try: msg = json.loads(line)
        except ValueError:
            print(f"stdout carried a line that is not JSON: {line.strip()[:120]}"); sys.exit(1)
        if msg.get("id") != 1:
            print(f"a response carried id {msg.get('id')!r}, not the request's id 1"); sys.exit(1)
        break
    elapsed = time.monotonic() - began
    result = msg.get("result") or {}
    if not msg.get("ok"):
        print(f"cycle-contract was refused: {msg.get('error')}"); sys.exit(1)
    if marker.lower() not in str(result.get("workflowName", "")).lower():
        print(f"the contract does not name '{marker}'"); sys.exit(1)
    if result.get("stages") != expected:
        print(f"the contract's stages are {result.get('stages')}, not {expected}"); sys.exit(1)
    print(f"answered cycle-contract in {elapsed:.1f}s of the {budget:g}s budget, naming '{marker}' "
          f"with stages {' → '.join(expected)}: this harness")
finally:
    stop()
PY
