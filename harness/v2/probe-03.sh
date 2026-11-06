#!/usr/bin/env bash
# harness/v2/probe-03.sh START_SCRIPT BUDGET_SECONDS: the identity probe of slice 03, the
# grader-lifecycle context. It proves the thing answering keeps instrument goldens, by domain
# behaviour over the context's seam (.pi/spec/grader-lifecycle/seam.md), not by a name: within
# the boot budget, counted from launching start.sh, the candidate must write a golden from a
# scenario whose digest it is handed, move it for a recorded reason, refuse a move without a
# note, and report the golden with its one move.
#
#   0. LOYLY_SCENARIO_DIGESTS names a file of the probe's: its one scenario has the scenario
#      digest s-probe
#   1. grader-lifecycle/write-instrument-golden of G-probe from that scenario, scenario digest
#      s-probe, digest g-1: exactly one grader-lifecycle/instrument-golden-written with those
#      fields and a written-at
#   2. grader-lifecycle/move-instrument-golden of G-probe to g-2 for spec-grew, additions only,
#      with a note: exactly one grader-lifecycle/instrument-golden-moved from g-1 to g-2
#   3. a move of G-probe to g-3 with an empty note: refused, -32000 with data.refusal empty-note
#   4. grader-lifecycle/instrument-goldens: one golden, G-probe, digest g-2, its one move from
#      g-1 to g-2 for spec-grew with that note
#
# The candidate gets its Grain store through LOYLY_GRAIN_STORE: GATE_STORE when the gate
# (harness/v2/gate.sh) set one, so the event-store check reads what this probe caused, and a
# file in the probe's own temporary directory otherwise. The probe is the instrument's and
# is fenced from the room: harness/config.sh names it APP_PROBE when SLICE=03.
set -uo pipefail
START="${1:?usage: probe-03.sh START_SCRIPT BUDGET_SECONDS}"
BUDGET="${2:?usage: probe-03.sh START_SCRIPT BUDGET_SECONDS}"
exec python3 - "$START" "$BUDGET" "${GATE_STORE:-}" <<'PY'
import json, os, select, signal, subprocess, sys, tempfile, time
start, budget, gate_store = sys.argv[1], float(sys.argv[2]), sys.argv[3]
work = tempfile.mkdtemp(prefix="probe-03.")
scenario = "the identity probe's scenario"
digests = os.path.join(work, "scenario-digests.json")
with open(digests, "w") as f:
    json.dump({scenario: "s-probe"}, f)
env = dict(os.environ, LOYLY_GRAIN_STORE=gate_store or os.path.join(work, "store.sqlite"),
           LOYLY_SCENARIO_DIGESTS=digests)
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
def differs(e, want):
    return {k: e.get(k) for k, v in want.items() if e.get(k) != v}

write = {"golden-id": "G-probe", "scenario": scenario, "scenario-digest": "s-probe",
         "path": "harness/oracle/probe.golden", "digest": "g-1"}
e = one_event("grader-lifecycle/write-instrument-golden",
              call(1, "grader-lifecycle/write-instrument-golden", write),
              "grader-lifecycle/instrument-golden-written")
wrong = differs(e, write)
if wrong:
    fail(f"the written golden differs from the domain answer in {json.dumps(wrong)[:300]}")
if not e.get("written-at"):
    fail("the written golden carries no written-at")

move = {"golden-id": "G-probe", "digest": "g-2", "reason": "spec-grew", "additions-only": True,
        "note": "the probe's checklist grew", "scenario-digest": "s-probe"}
e = one_event("grader-lifecycle/move-instrument-golden",
              call(2, "grader-lifecycle/move-instrument-golden", move),
              "grader-lifecycle/instrument-golden-moved")
want = {"golden-id": "G-probe", "from-digest": "g-1", "to-digest": "g-2", "reason": "spec-grew",
        "additions-only": True, "note": move["note"], "scenario-digest": "s-probe"}
wrong = differs(e, want)
if wrong:
    fail(f"the move differs from the domain answer in {json.dumps(wrong)[:300]}")
if not e.get("moved-at"):
    fail("the move carries no moved-at")

err = call(3, "grader-lifecycle/move-instrument-golden",
           dict(move, **{"digest": "g-3", "reason": "instrument-behavior-changed", "additions-only": False,
                         "note": ""}), refused=True)
if err.get("code") != -32000 or (err.get("data") or {}).get("refusal") != "empty-note":
    fail(f"a move without a note answered {json.dumps(err)[:300]}, not the refusal empty-note")

goldens = (call(4, "grader-lifecycle/instrument-goldens", {}) or {}).get("goldens")
if not isinstance(goldens, list) or len(goldens) != 1:
    fail(f"grader-lifecycle/instrument-goldens answered {json.dumps(goldens)[:300]}, not one golden")
g = goldens[0]
moves = g.get("moves")
want_move = {"from-digest": "g-1", "to-digest": "g-2", "reason": "spec-grew", "note": move["note"]}
if (g.get("golden-id") != "G-probe" or g.get("digest") != "g-2" or g.get("scenario-digest") != "s-probe"
        or not isinstance(moves, list) or len(moves) != 1 or differs(moves[0], want_move)):
    fail(f"grader-lifecycle/instrument-goldens answered {json.dumps(g)[:300]}: want G-probe at g-2 "
         f"with its one move from g-1 for spec-grew")
elapsed = time.monotonic() - began
stop()
print(f"wrote G-probe from a scenario handed in at s-probe, moved it g-1 -> g-2 for spec-grew, refused a move "
      f"without a note and reported it, in {elapsed:.1f}s of the {budget:g}s budget: grader lifecycle over its seam")
PY
