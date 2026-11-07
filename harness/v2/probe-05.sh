#!/usr/bin/env bash
# harness/v2/probe-05.sh START_SCRIPT BUDGET_SECONDS: the identity probe of slice 05, the
# cycle-preparation context. It proves the thing answering pins source cuts, by domain
# behaviour over the context's seam (.pi/spec/cycle-preparation/seam.md), not by a name:
# within the boot budget, counted from launching start.sh, the candidate must pin a cut at a
# commit of the repository it is handed, refuse to pin that cut again, and report the cut.
#
#   0. LOYLY_PROJECT_COMMITS names a file of the probe's: two commits, with the tree digests
#      t-probe-1 and t-probe-2
#   1. cycle-preparation/pin-source-cut of K-probe at the first commit, tree digest t-probe-1:
#      exactly one cycle-preparation/source-cut-pinned with those fields and a pinned-at
#   2. a pin of K-probe at the second commit, tree digest t-probe-2: refused, -32000 with
#      data.refusal cut-already-pinned
#   3. cycle-preparation/source-cuts: one cut, K-probe, at the first commit with t-probe-1
#
# The candidate gets its Grain store through LOYLY_GRAIN_STORE: GATE_STORE when the gate
# (harness/v2/gate.sh) set one, so the event-store check reads what this probe caused, and a
# file in the probe's own temporary directory otherwise. The probe is the instrument's and
# is fenced from the room: harness/config.sh names it APP_PROBE when SLICE=05.
set -uo pipefail
START="${1:?usage: probe-05.sh START_SCRIPT BUDGET_SECONDS}"
BUDGET="${2:?usage: probe-05.sh START_SCRIPT BUDGET_SECONDS}"
exec python3 - "$START" "$BUDGET" "${GATE_STORE:-}" <<'PY'
import json, os, select, signal, subprocess, sys, tempfile, time
start, budget, gate_store = sys.argv[1], float(sys.argv[2]), sys.argv[3]
work = tempfile.mkdtemp(prefix="probe-05.")
c1, c2 = "4f2a9c1e0b7d3a5f9c2e8b1d4a6f0c3e7b9d2a51", "9b07e2d4c8a1f6035e9d2b7c4a0f8e1d6b3c5a92"
commits = os.path.join(work, "project-commits.json")
with open(commits, "w") as f:
    json.dump({c1: "t-probe-1", c2: "t-probe-2"}, f)
env = dict(os.environ, LOYLY_GRAIN_STORE=gate_store or os.path.join(work, "store.sqlite"),
           LOYLY_PROJECT_COMMITS=commits)
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

pin = {"cut-id": "K-probe", "commit": c1, "tree-digest": "t-probe-1"}
e = one_event("cycle-preparation/pin-source-cut",
              call(1, "cycle-preparation/pin-source-cut", pin),
              "cycle-preparation/source-cut-pinned")
wrong = differs(e, pin)
if wrong:
    fail(f"the pinned cut differs from the domain answer in {json.dumps(wrong)[:300]}")
if not e.get("pinned-at"):
    fail("the pinned cut carries no pinned-at")

err = call(2, "cycle-preparation/pin-source-cut",
           {"cut-id": "K-probe", "commit": c2, "tree-digest": "t-probe-2"}, refused=True)
if err.get("code") != -32000 or (err.get("data") or {}).get("refusal") != "cut-already-pinned":
    fail(f"a second pin of K-probe answered {json.dumps(err)[:300]}, not the refusal cut-already-pinned")

cuts = (call(3, "cycle-preparation/source-cuts", {}) or {}).get("cuts")
if not isinstance(cuts, list) or len(cuts) != 1 or differs(cuts[0], pin) or not cuts[0].get("pinned-at"):
    fail(f"cycle-preparation/source-cuts answered {json.dumps(cuts)[:300]}: want one cut, K-probe "
         f"at {c1} with t-probe-1")
elapsed = time.monotonic() - began
stop()
print(f"pinned K-probe at a commit handed in with t-probe-1, refused to pin it again and reported it, "
      f"in {elapsed:.1f}s of the {budget:g}s budget: cycle preparation over its seam")
PY
