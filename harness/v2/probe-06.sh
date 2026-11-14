#!/usr/bin/env bash
# harness/v2/probe-06.sh START_SCRIPT BUDGET_SECONDS: the identity probe of slice 06, the
# cycle-runner context: since spec round 18 the one candidate, cycle-runner/cycle.v1 with the
# five contexts 01 to 05 in it, on one store. It proves the thing answering runs cycles, by
# domain behaviour over the context's seam (.pi/spec/cycle-runner/seam.md), not by a name:
# within the boot budget, counted from launching start.sh, the candidate must read a git
# repository it is handed, record a run's first effects into the contexts it holds, and halt
# the run before the cycle when its sealed grader is not approved. Everything it acts on is the
# probe's own fixture, written to a temporary directory: no builder, gate or model is needed.
#
#   0. a fixture project repository (git, one commit): a plan of one slice with one stamped
#      scenario, its harness configuration; a stub self-check and a stub builder host that
#      each leave a mark when launched (neither may run); a grader rubric; the configuration
#      file the probe hands in, rewritten between the steps (the seam: read at every start-run)
#   1. cycle-runner/start-run of R-probe at a well-formed hex string that is no commit of the
#      repository: refused, -32000 not-a-commit (StartRun requires 4)
#   2. the same at the repository's commit, the configuration naming a builder image that
#      LOYLY_BUILDER_IMAGES does not hold: refused, -32000 builder-image-missing (requires 5)
#   3. the same, no builder image named, the run's approved grader digests not naming the
#      rubric's: start-run answers exactly one cycle-runner/run-started, once the run halted
#   4. cycle-runner/run-progress of R-probe: halted at the grader effect, reason
#      refused-before-the-cycle, its detail naming the rubric's grader digest ("sha256:" and the
#      SHA-256 of the line cycle-runner/grade.v1 LF followed by the rubric's bytes); its effect
#      results the stamp, the pin and the checklist, each recorded into its context; the run
#      list holds R-probe once (the two refusals recorded nothing); neither the self-check nor
#      the builder ran
#
# The candidate gets its Grain store through LOYLY_GRAIN_STORE: GATE_STORE when the gate
# (harness/v2/gate.sh) set one, so the event-store check reads what this probe caused, and a
# file in the probe's own temporary directory otherwise. The probe is the instrument's and
# is fenced from the room: harness/config.sh names it APP_PROBE when SLICE=06.
set -uo pipefail
START="${1:?usage: probe-06.sh START_SCRIPT BUDGET_SECONDS}"
BUDGET="${2:?usage: probe-06.sh START_SCRIPT BUDGET_SECONDS}"
exec python3 - "$START" "$BUDGET" "${GATE_STORE:-}" <<'PY'
import hashlib, json, os, select, signal, socket, stat, subprocess, sys, tempfile, time
start, budget, gate_store = sys.argv[1], float(sys.argv[2]), sys.argv[3]
work = tempfile.mkdtemp(prefix="probe-06.")
began = time.monotonic()

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
rubric = write(os.path.join(work, "rubric.md"), "Pass when the item's behaviour is observed over the seam; else fail.\n")
with open(rubric, "rb") as f:
    grader_digest = "sha256:" + hashlib.sha256(b"cycle-runner/grade.v1\n" + f.read()).hexdigest()
images = os.path.join(work, "images")
os.makedirs(images)
config_file = write(os.path.join(work, "config.sh"), config + "BUILDER_IMAGE=probe-image\n")

env = dict(os.environ,
           LOYLY_GRAIN_STORE=gate_store or os.path.join(work, "store.sqlite"),
           LOYLY_RUNS_DIR=os.path.join(work, "runs"),
           LOYLY_PROJECT_REPO=repo,
           LOYLY_HARNESS_CONFIG=config_file,
           LOYLY_SELF_CHECK_CMD=self_check,
           LOYLY_BUILDER_HOST=builder,
           LOYLY_BUILDER_IMAGES=images,
           LOYLY_BUILDER_INSTRUCTION=instruction,
           LOYLY_GRADER_RUBRIC=rubric,
           LOYLY_MODEL_API_BASE="http://127.0.0.1:9",
           LOYLY_RENDER_MODEL="probe-render",
           LOYLY_GRADER_MODEL="probe-grader",
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

# 1. not a commit
err = call(1, "cycle-runner/start-run", dict(run, commit=not_a_commit), refused=True)
if refusal(err) != "not-a-commit":
    fail(f"a start at {not_a_commit}, no commit of the repository, answered {json.dumps(err)[:300]}, "
         f"not the refusal not-a-commit")

# 2. a builder image the images directory does not hold
err = call(2, "cycle-runner/start-run", dict(run, commit=commit), refused=True)
if refusal(err) != "builder-image-missing":
    fail(f"a start whose configuration names a builder image {images} does not hold answered "
         f"{json.dumps(err)[:300]}, not the refusal builder-image-missing")

# 3. no image named; the rubric's grader digest not approved
write(config_file, config)
events = (call(3, "cycle-runner/start-run", dict(run, commit=commit)) or {}).get("events")
if (not isinstance(events, list) or len(events) != 1 or events[0].get("type") != "cycle-runner/run-started"
        or events[0].get("run-id") != "R-probe" or events[0].get("commit") != commit):
    fail(f"cycle-runner/start-run answered {json.dumps(events)[:300]}, not exactly one "
         f"cycle-runner/run-started of R-probe at {commit}")

# 4. the run halted at the grader, before the cycle, after the stamp, the pin and the checklist
r = (call(4, "cycle-runner/run-progress", {"run-id": "R-probe"}) or {}).get("run") or {}
halt = r.get("halt") or {}
results = r.get("effect-results")
if (r.get("status") != "halted" or halt.get("effect") != "grader"
        or halt.get("reason") != "refused-before-the-cycle" or grader_digest not in str(halt.get("detail"))):
    fail(f"cycle-runner/run-progress of R-probe answered {json.dumps(r)[:400]}: want halted at the grader "
         f"effect, reason refused-before-the-cycle, its detail naming {grader_digest}")
want = [("stamp", "specification"), ("pin", "cycle-preparation"), ("checklist", "measurement")]
if (not isinstance(results, list)
        or [(x.get("effect"), x.get("context")) for x in results] != want
        or any(x.get("outcome") != "recorded" for x in results)):
    fail(f"the effect results of R-probe are {json.dumps(results)[:400]}: want the stamp, the pin and the "
         f"checklist, each recorded into its context")
runs = (call(5, "cycle-runner/run-progress", {}) or {}).get("runs")
if not isinstance(runs, list) or [x.get("run-id") for x in runs] != ["R-probe"]:
    fail(f"cycle-runner/run-progress answered {json.dumps(runs)[:300]}: want R-probe once, the refused "
         f"starts having recorded nothing")
ran = [m for m in ("self-check", "builder") if os.path.exists(os.path.join(marks, m))]
if ran:
    fail(f"the {' and the '.join(ran)} ran for a run halted at the grader")
elapsed = time.monotonic() - began
stop()
print(f"refused a start at no commit and one whose builder image is missing, stamped, pinned and derived "
      f"the checklist of R-probe and halted it at the unapproved grader {grader_digest}, ran no self-check "
      f"and no builder, in {elapsed:.1f}s of the {budget:g}s budget: a cycle runner over its seam")
PY
