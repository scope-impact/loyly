#!/usr/bin/env bash
# harness/v2/probe-06.sh START_SCRIPT BUDGET_SECONDS: the identity probe of slice 06, the
# cycle-runner context. It proves the thing answering runs cycles, by domain behaviour over the
# context's seam (.pi/spec/cycle-runner/seam.md), not by a name: within the boot budget,
# counted from launching start.sh, the candidate must read a git repository it is handed, start
# the contexts it is handed and hear whether they respond, and halt a run loudly when a context
# stops responding. Everything it acts on is the probe's own fixture, written to a temporary
# directory: no real context, no builder and no gate is needed.
#
#   0. a fixture project repository (git, one commit): a plan of one slice with one stamped
#      scenario, its harness configuration; a stub self-check and a stub builder that each
#      leave a mark when launched (neither may run); LOYLY_CONTEXTS names a file of the
#      probe's, rewritten between the steps (the seam: read at every start-run)
#   1. cycle-runner/start-run of R-probe at a well-formed hex string that is no commit of the
#      repository, the five contexts exiting at once: refused, -32000 not-a-commit (StartRun
#      requires 4 comes before 5)
#   2. the same at the repository's commit: refused, -32000 contexts-not-responding, its
#      data.contexts naming all five, each of which was launched
#   3. the five contexts now reply to every request except specification/stamp-slice, where
#      Specification exits: start-run answers exactly one cycle-runner/run-started, once the
#      run halted (the methods are synchronous)
#   4. cycle-runner/run-progress of R-probe: halted at the stamp effect, reason
#      context-did-not-respond, no effect result; the run list holds R-probe once (the two
#      refusals recorded nothing); neither the self-check nor the builder ran
#
# The candidate gets its Grain store through LOYLY_GRAIN_STORE: GATE_STORE when the gate
# (harness/v2/gate.sh) set one, so the event-store check reads what this probe caused, and a
# file in the probe's own temporary directory otherwise. The probe is the instrument's and
# is fenced from the room: harness/config.sh names it APP_PROBE when SLICE=06.
set -uo pipefail
START="${1:?usage: probe-06.sh START_SCRIPT BUDGET_SECONDS}"
BUDGET="${2:?usage: probe-06.sh START_SCRIPT BUDGET_SECONDS}"
exec python3 - "$START" "$BUDGET" "${GATE_STORE:-}" <<'PY'
import json, os, select, signal, socket, stat, subprocess, sys, tempfile, time
start, budget, gate_store = sys.argv[1], float(sys.argv[2]), sys.argv[3]
work = tempfile.mkdtemp(prefix="probe-06.")
began = time.monotonic()
CONTEXTS = ["specification", "cycle-preparation", "grader-lifecycle", "regeneration-cycle", "measurement"]

def write(path, text, executable=False):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)
    if executable:
        os.chmod(path, os.stat(path).st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    return path

# 0. the fixtures
with socket.socket() as s:
    s.bind(("127.0.0.1", 0)); port = s.getsockname()[1]
config = (f"APP_DIR=app\nPORT={port}\nPLAN_DIR=plan\nAPP_MARKER=probe\nAPP_PROBE=probe.sh\n"
          f"FENCED_PATHS=\"secret\"\n")
repo = os.path.join(work, "repo")
write(os.path.join(repo, "plan/01-probe/spec.feature"),
      "Feature: Probe\n\n  Rule: One rule\n\n    @item-01-001\n    Scenario: The probe's scenario\n"
      "      Given a probe\n      When it runs\n      Then it is heard\n")
write(os.path.join(repo, "config.sh"), config)
write(os.path.join(repo, "probe.sh"), "#!/bin/sh\nexit 0\n", executable=True)
write(os.path.join(repo, "secret/README.md"), "fenced\n")
genv = dict(os.environ, GIT_AUTHOR_NAME="probe", GIT_AUTHOR_EMAIL="probe@example.invalid",
            GIT_COMMITTER_NAME="probe", GIT_COMMITTER_EMAIL="probe@example.invalid")
for cmd in (["init", "-q"], ["add", "-A"], ["commit", "-q", "-m", "probe"]):
    subprocess.run(["git", "-C", repo] + cmd, check=True, env=genv, stdout=subprocess.DEVNULL)
commit = subprocess.run(["git", "-C", repo, "rev-parse", "HEAD"], check=True, capture_output=True,
                        text=True).stdout.strip()
not_a_commit = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef"
marks = os.path.join(work, "marks")
os.makedirs(marks)
self_check = write(os.path.join(work, "stubs/self-check.sh"),
                   f"#!/bin/sh\ntouch '{marks}/self-check'\nexit 0\n", executable=True)
builder = write(os.path.join(work, "stubs/builder.sh"),
                f"#!/bin/sh\ntouch '{marks}/builder'\nexit 0\n", executable=True)
instruction = write(os.path.join(work, "instruction.md"), "Build the probe's product.\n")
config_file = write(os.path.join(work, "config.sh"), config)
contexts_file = os.path.join(work, "contexts.json")

def contexts(kind):
    """Five entrypoints of KIND: 'exit' exits at once; 'reply' answers every request with an
    empty result, except Specification, which exits when asked to stamp. Each marks its launch."""
    entry = {}
    for c in CONTEXTS:
        launched = os.path.join(marks, f"launched-{kind}-{c}")
        if kind == "exit":
            body = f"#!/bin/sh\ntouch '{launched}'\nexit 0\n"
        else:
            body = ("#!/usr/bin/env python3\nimport json, sys\n"
                    f"open({launched!r}, 'a').close()\n"
                    "for line in sys.stdin:\n"
                    "    try: req = json.loads(line)\n"
                    "    except ValueError: continue\n"
                    f"    if {c!r} == 'specification' and req.get('method') == 'specification/stamp-slice': sys.exit(0)\n"
                    "    sys.stdout.write(json.dumps({'jsonrpc': '2.0', 'id': req.get('id'), 'result': {}}) + '\\n')\n"
                    "    sys.stdout.flush()\n")
        entry[c] = write(os.path.join(work, f"stubs/{kind}/{c}"), body, executable=True)
    with open(contexts_file, "w") as f:
        json.dump(entry, f)

contexts("exit")
env = dict(os.environ,
           LOYLY_GRAIN_STORE=gate_store or os.path.join(work, "store.sqlite"),
           LOYLY_RUNS_DIR=os.path.join(work, "runs"),
           LOYLY_PROJECT_REPO=repo,
           LOYLY_HARNESS_CONFIG=config_file,
           LOYLY_CONTEXTS=contexts_file,
           LOYLY_SELF_CHECK_CMD=self_check,
           LOYLY_BUILDER_CMD=builder,
           LOYLY_BUILDER_INSTRUCTION=instruction,
           LOYLY_SCORING_TOOL_PATHS="[]")
env.pop("GATE_STORE", None)
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
def refusal(err):
    return err.get("code") == -32000 and (err.get("data") or {}).get("refusal")

run = {"run-id": "R-probe", "cycle-id": "C-probe", "cut-id": "K-probe", "selection-id": "whole-plan",
       "budget": {"gate-attempt-limit": 1, "max-outer-cycles": 1, "max-continuations": 0,
                  "stage-timeouts-seconds": {"fence": 60, "regenerate": 60, "gate": 60, "score": 60}},
       "grader-digests": ["r-probe"]}

# 1. not a commit, before the contexts are asked
err = call(1, "cycle-runner/start-run", dict(run, commit=not_a_commit), refused=True)
if refusal(err) != "not-a-commit":
    fail(f"a start at {not_a_commit}, no commit of the repository, answered {json.dumps(err)[:300]}, "
         f"not the refusal not-a-commit")

# 2. the five contexts exit at once
err = call(2, "cycle-runner/start-run", dict(run, commit=commit), refused=True)
named = (err.get("data") or {}).get("contexts")
if refusal(err) != "contexts-not-responding" or not isinstance(named, list) or sorted(named) != sorted(CONTEXTS):
    fail(f"a start whose five contexts exit at once answered {json.dumps(err)[:300]}, not the refusal "
         f"contexts-not-responding naming all five in data.contexts")
unlaunched = [c for c in CONTEXTS if not os.path.exists(os.path.join(marks, f"launched-exit-{c}"))]
if unlaunched:
    fail(f"contexts-not-responding was answered without launching {', '.join(unlaunched)}")

# 3. the contexts reply, but Specification exits when asked to stamp
contexts("reply")
events = (call(3, "cycle-runner/start-run", dict(run, commit=commit)) or {}).get("events")
if (not isinstance(events, list) or len(events) != 1 or events[0].get("type") != "cycle-runner/run-started"
        or events[0].get("run-id") != "R-probe" or events[0].get("commit") != commit):
    fail(f"cycle-runner/start-run answered {json.dumps(events)[:300]}, not exactly one "
         f"cycle-runner/run-started of R-probe at {commit}")

# 4. the run halted at the stamp, loudly, and nothing else ran
r = (call(4, "cycle-runner/run-progress", {"run-id": "R-probe"}) or {}).get("run") or {}
halt = r.get("halt") or {}
if (r.get("status") != "halted" or halt.get("effect") != "stamp"
        or halt.get("reason") != "context-did-not-respond" or r.get("effect-results") != []):
    fail(f"cycle-runner/run-progress of R-probe answered {json.dumps(r)[:400]}: want halted at the stamp "
         f"effect, reason context-did-not-respond, with no effect result")
runs = (call(5, "cycle-runner/run-progress", {}) or {}).get("runs")
if not isinstance(runs, list) or [x.get("run-id") for x in runs] != ["R-probe"]:
    fail(f"cycle-runner/run-progress answered {json.dumps(runs)[:300]}: want R-probe once, the refused "
         f"starts having recorded nothing")
ran = [m for m in ("self-check", "builder") if os.path.exists(os.path.join(marks, m))]
if ran:
    fail(f"the {' and the '.join(ran)} ran for a run halted at the stamp")
elapsed = time.monotonic() - began
stop()
print(f"refused a start at no commit and one whose contexts exit, halted R-probe at the stamp when "
      f"Specification stopped responding, ran no self-check and no builder, in {elapsed:.1f}s of the "
      f"{budget:g}s budget: a cycle runner over its seam")
PY
