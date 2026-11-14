#!/usr/bin/env bash
# SUPERSEDED by spec round 18 (.pi/spec/decisions.md, "Spec round 18", 9): the contexts 01 to
# 05 are built only inside slice 06's one candidate, on one store, with no hand-in method, so no
# slice 02 cycle runs on its own and this probe gates nothing; it hands other contexts' events in
# through methods that round removed. Kept to replay the relay-era builds, as v2/assemble.clj is.
#
# harness/v2/probe-02.sh START_SCRIPT BUDGET_SECONDS: the identity probe of slice 02, the
# measurement context. It proves the thing answering derives checklists, by domain behaviour
# over the context's seam (.pi/spec/measurement/seam.md), not by a name: within the boot
# budget, counted from launching start.sh, the candidate must take two handed-in handoffs,
# derive a checklist from them, refuse one it cannot derive, and report the one it derived.
#
#   1. specification/scenario-stamped (item 07-001) and cycle-preparation/source-cut-pinned
#      (cut K-probe) handed in: each answers exactly the event it was given
#   2. measurement/derive-checklist of K-probe, whole-plan, offering 07-001: the result's events
#      are exactly one measurement/checklist-derived with that cut, selection, item and total 1,
#      and a checklist digest
#   3. measurement/derive-checklist of the cut K-unpinned, never pinned: refused, -32000 with
#      data.refusal cut-not-pinned
#   4. measurement/checklist of K-probe: total 1, item 07-001, the same digest
#
# The candidate gets its Grain store through LOYLY_GRAIN_STORE: GATE_STORE when the gate
# (harness/v2/gate.sh) set one, so the event-store check reads what this probe caused, and a
# file in the probe's own temporary directory otherwise. The probe is the instrument's and
# is fenced from the room: harness/config.sh names it APP_PROBE when SLICE=02.
set -uo pipefail
START="${1:?usage: probe-02.sh START_SCRIPT BUDGET_SECONDS}"
BUDGET="${2:?usage: probe-02.sh START_SCRIPT BUDGET_SECONDS}"
exec python3 - "$START" "$BUDGET" "${GATE_STORE:-}" <<'PY'
import json, os, select, signal, subprocess, sys, tempfile, time
start, budget, gate_store = sys.argv[1], float(sys.argv[2]), sys.argv[3]
work = tempfile.mkdtemp(prefix="probe-02.")
source = os.path.join(work, "plan", "07-probe", "spec.feature")
title = "the identity probe's scenario"
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
def call(rid, method, params, refused=False):
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
        if refused:
            if "error" not in msg:
                fail(f"{method} was not refused: {json.dumps(msg.get('result'))[:300]}")
            return msg["error"]
        if "error" in msg or "result" not in msg:
            fail(f"{method} was refused: {json.dumps(msg.get('error'))[:300]}")
        return msg["result"]
def one_event(method, result, etype):
    events = (result or {}).get("events")
    if not isinstance(events, list) or len(events) != 1 or events[0].get("type") != etype:
        fail(f"{method} answered {json.dumps(events)[:300]}, not exactly one {etype}")
    return events[0]

stamped = {"item-id": "07-001", "slice": "07", "source": source, "line": 2, "title": title,
           "stamped-at": "2026-11-07T10:00:00Z"}
e = one_event("specification/scenario-stamped", call(1, "specification/scenario-stamped", stamped),
              "specification/scenario-stamped")
if any(e.get(k) != v for k, v in stamped.items() if k != "stamped-at"):
    fail(f"the handed-in scenario-stamped came back as {json.dumps(e)[:300]}")
pinned = {"cut-id": "K-probe", "commit": "0123456789abcdef0123456789abcdef01234567",
          "tree-digest": "sha256:probe-tree", "pinned-at": "2026-11-07T10:00:01Z"}
e = one_event("cycle-preparation/source-cut-pinned", call(2, "cycle-preparation/source-cut-pinned", pinned),
              "cycle-preparation/source-cut-pinned")
if any(e.get(k) != v for k, v in pinned.items() if k != "pinned-at"):
    fail(f"the handed-in source-cut-pinned came back as {json.dumps(e)[:300]}")

item = {"item-id": "07-001", "scenario-digest": "sha256:probe-scenario", "rule": None,
        "source": f"{source}:2", "title": title}
derive = {"cut-id": "K-probe", "selection-id": "whole-plan", "items": [item], "unstamped": [],
          "skipped-slices": []}
e = one_event("measurement/derive-checklist", call(3, "measurement/derive-checklist", derive),
              "measurement/checklist-derived")
want = {"cut-id": "K-probe", "selection-id": "whole-plan", "total": 1, "items": [item], "skipped-slices": []}
wrong = {k: e.get(k) for k, v in want.items() if e.get(k) != v}
if wrong:
    fail(f"the derived checklist differs from the domain answer in {json.dumps(wrong)[:300]}")
digest = e.get("checklist-digest")
if not digest or not e.get("derived-at"):
    fail("the derived checklist carries no checklist-digest or no derived-at")

err = call(4, "measurement/derive-checklist", dict(derive, **{"cut-id": "K-unpinned"}), refused=True)
if err.get("code") != -32000 or (err.get("data") or {}).get("refusal") != "cut-not-pinned":
    fail(f"deriving the checklist of a cut never pinned answered {json.dumps(err)[:300]}, not the refusal cut-not-pinned")

c = (call(5, "measurement/checklist", {"cut-id": "K-probe"}) or {}).get("checklist")
ids = [i.get("item-id") for i in (c or {}).get("items") or []]
if not c or c.get("total") != 1 or ids != ["07-001"] or c.get("checklist-digest") != digest:
    fail(f"measurement/checklist of K-probe answered {json.dumps(c)[:300]}: want total 1, item 07-001 and the same digest")
elapsed = time.monotonic() - began
stop()
print(f"derived the checklist of K-probe from two handed-in handoffs (07-001, total 1), refused an unpinned cut "
      f"and reported the checklist, in {elapsed:.1f}s of the {budget:g}s budget: measurement over its seam")
PY
