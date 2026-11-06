#!/usr/bin/env bash
# harness/v2/probe-01.sh START_SCRIPT BUDGET_SECONDS: the identity probe of slice 01, the
# specification context. It proves the thing answering is a stamper, by domain behaviour
# over the context's seam (.pi/spec/specification/seam.md), not by a name: within the boot
# budget, counted from launching start.sh, the candidate must stamp one unstamped scenario of
# a fresh spec file and then report it through its query.
#
#   1. specification/stamp-slice for slice 07 of a temporary plan/07-probe/spec.feature
#      holding one unstamped scenario on line 2: the result's events are exactly one
#      specification/scenario-stamped, item-id 07-001, with the slice, source, line and title
#   2. the file now carries the tag @item-07-001 on its own line directly above the heading
#   3. specification/stamped-items for slice 07: one slice, next-number 2, item 07-001
#
# The candidate gets its Grain store through LOYLY_GRAIN_STORE: GATE_STORE when the gate
# (harness/v2/gate.sh) set one, so the event-store check reads what this probe caused, and a
# file in the probe's own temporary directory otherwise. The probe is the instrument's and
# is fenced from the room: harness/config.sh names it APP_PROBE when SLICE=01.
set -uo pipefail
START="${1:?usage: probe-01.sh START_SCRIPT BUDGET_SECONDS}"
BUDGET="${2:?usage: probe-01.sh START_SCRIPT BUDGET_SECONDS}"
exec python3 - "$START" "$BUDGET" "${GATE_STORE:-}" <<'PY'
import json, os, select, signal, subprocess, sys, tempfile, time
start, budget, gate_store = sys.argv[1], float(sys.argv[2]), sys.argv[3]
work = tempfile.mkdtemp(prefix="probe-01.")
spec_dir = os.path.join(work, "plan", "07-probe")
os.makedirs(spec_dir)
source = os.path.join(spec_dir, "spec.feature")
title = "the identity probe's scenario"
with open(source, "w") as f:
    f.write("Feature: identity probe\n"
            f"  Scenario: {title}\n"
            "    Given a spec file with one unstamped scenario\n"
            "    When the operator stamps slice 07\n"
            "    Then it is stamped\n")
env = dict(os.environ, LOYLY_GRAIN_STORE=gate_store or os.path.join(work, "store.sqlite"))
env.pop("GATE_STORE", None)
began = time.monotonic()
proc = subprocess.Popen([start], stdin=subprocess.PIPE, stdout=subprocess.PIPE, cwd=work, env=env,
                        stderr=subprocess.DEVNULL, start_new_session=True, text=True)
def stop():
    try: os.killpg(proc.pid, signal.SIGTERM)
    except ProcessLookupError: pass
    try: proc.wait(timeout=5)
    except subprocess.TimeoutExpired: os.killpg(proc.pid, signal.SIGKILL)
def fail(why):
    print(why); stop(); sys.exit(1)
def call(rid, method, params):
    proc.stdin.write(json.dumps({"jsonrpc": "2.0", "id": rid, "method": method, "params": params}) + "\n")
    proc.stdin.flush()
    while True:
        left = budget - (time.monotonic() - began)
        if left <= 0:
            fail(f"no answer to {method} within the {budget:g}s boot budget")
        ready, _, _ = select.select([proc.stdout], [], [], left)
        if not ready: continue
        line = proc.stdout.readline()
        if not line:
            fail(f"start.sh exited before answering {method}")
        try: msg = json.loads(line)
        except ValueError:
            fail(f"stdout carried a line that is not JSON: {line.strip()[:120]}")
        if msg.get("id") != rid:
            fail(f"a response carried id {msg.get('id')!r}, not the request's id {rid}")
        if "error" in msg or "result" not in msg:
            fail(f"{method} was refused: {json.dumps(msg.get('error'))[:300]}")
        return msg["result"]

result = call(1, "specification/stamp-slice",
              {"slice": "07", "source": source, "unstamped": [{"line": 2, "title": title}],
               "empty-title-lines": []})
events = (result or {}).get("events")
if not isinstance(events, list) or len(events) != 1:
    fail(f"stamp-slice produced {json.dumps(events)[:300]}, not exactly one event")
e = events[0]
want = {"type": "specification/scenario-stamped", "item-id": "07-001", "slice": "07",
        "source": source, "line": 2, "title": title}
wrong = {k: e.get(k) for k, v in want.items() if e.get(k) != v}
if wrong:
    fail(f"the stamped event differs from the domain answer in {json.dumps(wrong)[:300]}")
if not e.get("stamped-at"):
    fail("the stamped event carries no stamped-at")
with open(source) as f:
    lines = f.read().split("\n")
heading = next((i for i, l in enumerate(lines) if l.strip() == f"Scenario: {title}"), None)
if heading is None or heading == 0 or lines[heading - 1].strip() != "@item-07-001":
    fail("the spec file does not carry @item-07-001 on the line directly above the scenario's heading")
result = call(2, "specification/stamped-items", {"slice": "07"})
slices = (result or {}).get("slices")
if not isinstance(slices, list) or len(slices) != 1:
    fail(f"stamped-items for slice 07 answered {json.dumps(slices)[:300]}, not one slice")
s = slices[0]
ids = [i.get("item-id") for i in s.get("items") or []]
if s.get("slice") != "07" or s.get("next-number") != 2 or ids != ["07-001"]:
    fail(f"stamped-items for slice 07 answered {json.dumps(s)[:300]}: want next-number 2 and item 07-001")
elapsed = time.monotonic() - began
stop()
print(f"stamped one scenario as 07-001 and reported it, next number 2, in {elapsed:.1f}s of the "
      f"{budget:g}s budget: a stamper over the specification seam")
PY
