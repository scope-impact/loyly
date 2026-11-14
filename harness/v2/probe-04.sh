#!/usr/bin/env bash
# SUPERSEDED by spec round 18 (.pi/spec/decisions.md, "Spec round 18", 9): the contexts 01 to
# 05 are built only inside slice 06's one candidate, on one store, with no hand-in method, so no
# slice 04 cycle runs on its own and this probe gates nothing; it hands other contexts' events in
# through methods that round removed. Kept to replay the relay-era builds, as v2/assemble.clj is.
#
# harness/v2/probe-04.sh START_SCRIPT BUDGET_SECONDS: the identity probe of slice 04, the
# regeneration-cycle context. It proves the thing answering records and guards a regeneration
# cycle, by domain behaviour over the context's seam (.pi/spec/regeneration-cycle/seam.md), not
# by a name: within the boot budget, counted from launching start.sh, the candidate must take
# two handed-in handoffs, open a cycle on a self-check at the goldens digest it computes from
# them, refuse a fence whose path matched nothing, prove the clean room, and report the cycle.
#
#   1. cycle-preparation/source-cut-pinned (cut K-probe) and grader-lifecycle/instrument-golden-
#      written (golden checklist, digest g-probe) handed in: each answers exactly that event
#   2. regeneration-cycle/record-harness-self-check of cycle C-probe before the fence, attempt 0,
#      on K-probe, outcome passed, at the goldens digest seam.md's formula gives for that one
#      golden: exactly one regeneration-cycle/harness-self-check-passed with those fields
#   3. regeneration-cycle/prove-clean-room of C-probe with the fenced path harness/scroe.clj
#      unmatched: refused, -32000 with data.refusal fenced-path-matched-nothing
#   4. regeneration-cycle/prove-clean-room of C-probe, every check of the probe holding: exactly
#      one regeneration-cycle/clean-room-proved with its commit and fenced paths
#   5. regeneration-cycle/cycle-progress of C-probe: running at the regenerate stage on K-probe,
#      its clean room's commit, 0 gate attempts, the budget's 3 gate attempts
#
# The budget's stage timeouts are a day, so the stage clock never fires during the probe. The
# candidate gets its Grain store through LOYLY_GRAIN_STORE: GATE_STORE when the gate
# (harness/v2/gate.sh) set one, so the event-store check reads what this probe caused, and a
# file in the probe's own temporary directory otherwise; no scoring tool is declared
# (LOYLY_SCORING_TOOL_PATHS unset). The probe is the instrument's and is fenced from the room:
# harness/config.sh names it APP_PROBE when SLICE=04.
set -uo pipefail
START="${1:?usage: probe-04.sh START_SCRIPT BUDGET_SECONDS}"
BUDGET="${2:?usage: probe-04.sh START_SCRIPT BUDGET_SECONDS}"
exec python3 - "$START" "$BUDGET" "${GATE_STORE:-}" <<'PY'
import hashlib, json, os, select, signal, subprocess, sys, tempfile, time
start, budget, gate_store = sys.argv[1], float(sys.argv[2]), sys.argv[3]
work = tempfile.mkdtemp(prefix="probe-04.")
env = dict(os.environ, LOYLY_GRAIN_STORE=gate_store or os.path.join(work, "store.sqlite"))
env.pop("GATE_STORE", None)
env.pop("LOYLY_SCORING_TOOL_PATHS", None)
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
def handed_in(rid, method, params, stamp):
    e = one_event(method, call(rid, method, params), method)
    wrong = differs(e, {k: v for k, v in params.items() if k != stamp})
    if wrong:
        fail(f"the handed-in {method} came back as {json.dumps(e)[:300]}")

handed_in(1, "cycle-preparation/source-cut-pinned",
          {"cut-id": "K-probe", "commit": "0123456789abcdef0123456789abcdef01234567",
           "tree-digest": "sha256:probe-tree", "pinned-at": "2026-11-07T10:00:00Z"}, "pinned-at")
handed_in(2, "grader-lifecycle/instrument-golden-written",
          {"golden-id": "checklist", "scenario": "the identity probe's scenario", "scenario-digest": "s-probe",
           "path": "harness/oracle/checklist.golden.json", "digest": "g-probe",
           "written-at": "2026-11-07T10:00:01Z"}, "written-at")

# seam.md, "The goldens digest": one "<golden-id>\t<digest>\n" line per current golden, by golden id
goldens_digest = "sha256:" + hashlib.sha256("checklist\tg-probe\n".encode("utf-8")).hexdigest()
day = 86400
plan = {"gate-attempt-limit": 3, "max-outer-cycles": 1, "max-continuations": 0,
        "stage-timeouts-seconds": {"fence": day, "regenerate": day, "gate": day, "score": day}}
check = {"cycle-id": "C-probe", "cut-id": "K-probe", "occasion": "before-fence", "attempt": 0,
         "harness-digest": "h-probe", "goldens-digest": goldens_digest, "identity-probe-digest": "p-probe",
         "outcome": "passed", "skipped-checks": [], "missing-goldens": [], "reworded-goldens": [],
         "grader-digests": ["r-probe"], "budget": plan}
e = one_event("regeneration-cycle/record-harness-self-check",
              call(3, "regeneration-cycle/record-harness-self-check", check),
              "regeneration-cycle/harness-self-check-passed")
want = {k: v for k, v in check.items() if k not in ("outcome", "missing-goldens", "reworded-goldens")}
wrong = differs(e, want)
if wrong:
    fail(f"the passed self-check differs from the domain answer in {json.dumps(wrong)[:300]}")
if not e.get("checked-at"):
    fail("the passed self-check carries no checked-at")

room = {"cycle-id": "C-probe", "cut-id": "K-probe", "clean-room-commit": "c-probe",
        "fenced-paths": ["harness/scroe.clj", "harness-impl"], "root-commits": 1, "commits": 1,
        "unmatched-paths": ["harness/scroe.clj"], "paths-left-in-tree": [], "paths-reachable-in-object-graph": []}
err = call(4, "regeneration-cycle/prove-clean-room", room, refused=True)
if err.get("code") != -32000 or (err.get("data") or {}).get("refusal") != "fenced-path-matched-nothing":
    fail(f"a clean room whose fenced path matched nothing answered {json.dumps(err)[:300]}, "
         f"not the refusal fenced-path-matched-nothing")

room = dict(room, **{"fenced-paths": ["harness/score.clj", "harness-impl"], "unmatched-paths": []})
e = one_event("regeneration-cycle/prove-clean-room", call(5, "regeneration-cycle/prove-clean-room", room),
              "regeneration-cycle/clean-room-proved")
wrong = differs(e, {k: room[k] for k in ("cycle-id", "cut-id", "clean-room-commit", "fenced-paths")})
if wrong or not e.get("proved-at"):
    fail(f"the proved clean room differs from the domain answer in {json.dumps(wrong)[:300]}, or has no proved-at")

c = (call(6, "regeneration-cycle/cycle-progress", {"cycle-id": "C-probe"}) or {}).get("cycle")
want = {"cycle-id": "C-probe", "cut-id": "K-probe", "status": "running", "stage": "regenerate",
        "clean-room-commit": "c-probe", "gate-attempts": 0}
wrong = differs(c or {}, want)
if not c or wrong or (c.get("budget") or {}).get("gate-attempt-limit") != 3:
    fail(f"regeneration-cycle/cycle-progress of C-probe answered {json.dumps(c)[:300]}: want C-probe running "
         f"at the regenerate stage on K-probe, clean room c-probe, 0 gate attempts, a budget of 3")
elapsed = time.monotonic() - began
stop()
print(f"opened C-probe on a self-check at the goldens digest of two handed-in handoffs, refused a fence whose "
      f"path matched nothing, proved the clean room and reported the cycle at regenerate, in {elapsed:.1f}s of "
      f"the {budget:g}s budget: the regeneration cycle over its seam")
PY
