#!/usr/bin/env python3
"""Calibration fixture for grade_regeneration_cycle.exs: a minimal regeneration-cycle context over
its seam (.pi/spec/regeneration-cycle/seam.md), written from the spec alone. Not the product: it
keeps its facts as JSON in the LOYLY_GRAIN_STORE file, not in Grain, runs no ORC workflow and no
stage clock.

MUTANT=<name> breaks one behaviour of the scenarios (calibrate.sh lists them and the item each
must fail); unset, it is the reference that carries every scenario."""
import hashlib, json, os, sys

MUTANT = os.environ.get("MUTANT", "")
STORE = os.environ.get("LOYLY_GRAIN_STORE") or os.path.join(os.getcwd(), "stub-store.json")
try:
    TOOLS = json.loads(os.environ.get("LOYLY_SCORING_TOOL_PATHS") or "[]")
except ValueError:
    TOOLS = []
AT = "2026-11-07T00:00:00Z"
RC = "regeneration-cycle/"
GATE_REASONS = ("contract-missing", "suite-discovery-empty", "suite-red", "boot-budget-exhausted", "identity-probe-failed")
STAGES = ("fence", "regenerate", "gate", "score")
FAIL_REASONS = ("harness-self-check-failed", "fence-refused", "candidate-not-regenerated", "gate-attempts-exhausted",
                "score-stage-failed", "budget", "infrastructure")

db = {"cuts": [], "goldens": {}, "checks": [], "cycles": {}, "order": [], "attempts": {}, "rooms": {}, "due": []}


def save():
    with open(STORE, "w") as f:
        json.dump(db, f)


class Refused(Exception):
    def __init__(self, kind, **data):
        self.kind, self.data = kind, data


class BadParams(Exception):
    pass


def need(p, spec):
    """spec: {key: type or (type, optional)}; raise BadParams on mismatch."""
    if not isinstance(p, dict):
        raise BadParams()
    for k, t in spec.items():
        opt = isinstance(t, tuple)
        t = t[0] if opt else t
        if k not in p:
            if opt:
                continue
            raise BadParams()
        v = p[k]
        if t == "str" and not isinstance(v, str):
            raise BadParams()
        if t == "int" and (not isinstance(v, int) or isinstance(v, bool)):
            raise BadParams()
        if t == "pos" and (not isinstance(v, int) or isinstance(v, bool) or v < 1):
            raise BadParams()
        if t == "bool" and not isinstance(v, bool):
            raise BadParams()
        if t == "strs" and not (isinstance(v, list) and all(isinstance(x, str) for x in v)):
            raise BadParams()
        if t == "strs1" and not (isinstance(v, list) and v and all(isinstance(x, str) for x in v)):
            raise BadParams()
        if isinstance(t, set) and v not in t:
            raise BadParams()


def need_budget(b):
    if not isinstance(b, dict):
        raise BadParams()
    need(b, {"gate-attempt-limit": "int", "max-outer-cycles": "pos", "max-continuations": "int"})
    t = b.get("stage-timeouts-seconds")
    if not isinstance(t, dict):
        raise BadParams()
    need(t, {s: "pos" for s in STAGES})


def goldens_digest():
    lines = [f"{g}\t{d}" for g, d in sorted(db["goldens"].items())]
    text = "\n".join(lines) if MUTANT == "goldens-no-newline" else "".join(l + "\n" for l in lines)
    return "sha256:" + hashlib.sha256(text.encode()).hexdigest()


def cycle(cid):
    return db["cycles"].get(cid)


def check_of(cid, occasion, attempt):
    return next((c for c in db["checks"] if (c["cycle-id"], c["occasion"], c["attempt"]) == (cid, occasion, attempt)), None)


def rows(cid):
    return db["attempts"].setdefault(cid, {})


def ev(kind, **f):
    return dict({"type": RC + kind}, **f)


# ---- the conclude-cycle policy: runs before the method that made a cycle due responds

def due(c):
    if c["status"] != "running":
        return None
    if c["self-check-failed"]:
        stage = "fence" if MUTANT == "fence-stage-on-gate-check" else c["stage"]
        return ("fail", stage, "harness-self-check-failed", c["self-check-failure"])
    limit = c["budget"]["gate-attempt-limit"]
    exhausted = c["failed-attempts"] > limit if MUTANT == "exhausted-off-by-one" else c["failed-attempts"] >= limit
    if exhausted:
        return ("fail", "gate", "gate-attempts-exhausted", "gate attempts exhausted")
    if c["consecutive-gate-refusals"] >= 3:
        return ("fail", "gate", "infrastructure", "three consecutive gate refusals")
    if c["score-stage-failed"]:
        return ("fail", "score", "score-stage-failed", "score refused")
    if c["scoreboard-reported"]:
        return ("pass",)
    return None


def policy(cid):
    if MUTANT == "no-policy":
        return
    c = cycle(cid)
    if not c:
        return
    d = due(c)
    if not d:
        return
    try:
        if d[0] == "pass":
            pass_cycle({"cycle-id": cid})
        else:
            fail_cycle({"cycle-id": cid, "stage": d[1], "reason": d[2], "detail": d[3]})
    except Refused:
        pass


# ---- the consumed handoffs

def source_cut_pinned(p):
    need(p, {"cut-id": "str", "commit": "str", "tree-digest": "str", "pinned-at": "str"})
    if p["cut-id"] not in db["cuts"]:
        db["cuts"].append(p["cut-id"])
    return [dict({"type": "cycle-preparation/source-cut-pinned"}, **p)]


def golden_written(p):
    need(p, {"golden-id": "str", "scenario": "str", "scenario-digest": "str", "path": "str", "digest": "str", "written-at": "str"})
    db["goldens"].setdefault(p["golden-id"], p["digest"])
    return [dict({"type": "grader-lifecycle/instrument-golden-written"}, **p)]


def golden_moved(p):
    need(p, {"golden-id": "str", "from-digest": "str", "to-digest": "str",
             "reason": {"instrument-behavior-changed", "wording-replaced", "spec-grew"}, "additions-only": "bool",
             "note": "str", "scenario-digest": "str", "moved-at": "str"})
    if p["golden-id"] in db["goldens"] and MUTANT != "ignore-moves":
        db["goldens"][p["golden-id"]] = p["to-digest"]
    return [dict({"type": "grader-lifecycle/instrument-golden-moved"}, **p)]


def measurement_filed(p):
    need(p, {"cycle-id": "str"})
    c = cycle(p["cycle-id"])
    if c:
        c["measurement-filed"] = True
        c["stage"] = "scoreboard"
    return [dict({"type": "measurement/measurement-filed"}, **p)]


def score_stage_failed(p):
    need(p, {"cycle-id": "str", "refusal": "str", "detail": "str", "failed-at": "str"})
    c = cycle(p["cycle-id"])
    if c:
        c["score-stage-failed"] = True
        policy(p["cycle-id"])
    return [dict({"type": "measurement/score-stage-failed"}, **p)]


def scoreboard_reported(p):
    need(p, {"cycle-id": "str"})
    c = cycle(p["cycle-id"])
    if c:
        c["scoreboard-reported"] = True
        policy(p["cycle-id"])
    return [dict({"type": "measurement/scoreboard-reported"}, **p)]


# ---- the commands

def record_harness_self_check(p):
    need(p, {"cycle-id": "str", "cut-id": "str", "occasion": {"before-fence", "before-gate"}, "attempt": "int",
             "harness-digest": "str", "goldens-digest": "str", "identity-probe-digest": "str", "outcome": {"passed", "failed"},
             "failing-check": ("str",), "detail": ("str",), "skipped-checks": "strs", "missing-goldens": "strs",
             "reworded-goldens": "strs", "grader-digests": "strs"})
    if not 0 <= p["attempt"] <= 3:
        raise BadParams()
    need_budget(p.get("budget"))
    cid, occ, att, b = p["cycle-id"], p["occasion"], p["attempt"], p["budget"]
    c = cycle(cid)
    passed = p["outcome"] == "passed"
    if p["cut-id"] not in db["cuts"]:
        raise Refused("cut-not-pinned")
    if p["goldens-digest"] != goldens_digest() and MUTANT != "accept-stale-goldens":
        raise Refused("goldens-not-current")
    if check_of(cid, occ, att):
        raise Refused("self-check-already-recorded")
    if not passed and not p.get("failing-check"):
        raise Refused("no-failing-check")
    if occ == "before-fence" and (c or att != 0):
        raise Refused("not-the-cycles-first-fact")
    if occ == "before-gate":
        ok = c and c["status"] == "running" and c["cut-id"] == p["cut-id"]
        ok = ok and (MUTANT == "gate-check-any-attempt" or c["attempts"] == att)
        if not ok:
            if MUTANT == "refuse-but-record-check":
                db["checks"].append({"cycle-id": cid, "occasion": occ, "attempt": att, "outcome": p["outcome"]})
            raise Refused("no-candidate-for-attempt")
    if not (1 <= b["gate-attempt-limit"] <= 3 and 0 <= b["max-continuations"] <= 3):
        raise Refused("budget-out-of-bounds")
    if passed and p["missing-goldens"] and MUTANT != "pass-with-missing-golden":
        raise Refused("missing-golden", goldens=p["missing-goldens"])
    if passed and MUTANT == "golden-needs-cycle" and any(g.startswith("scoreboard-") for g in db["goldens"]):
        missing = [g for g in db["goldens"] if g.startswith("scoreboard-")]
        raise Refused("missing-golden", goldens=missing)
    if passed and p["reworded-goldens"]:
        raise Refused("reworded-golden", goldens=p["reworded-goldens"])
    if any(g not in db["goldens"] for g in p["reworded-goldens"]):
        raise Refused("reworded-golden-not-current", goldens=p["reworded-goldens"])
    if passed and p["skipped-checks"] and MUTANT == "skip-is-failure":
        passed = False
        p = dict(p, **{"failing-check": "tool not found: " + ", ".join(p["skipped-checks"])})
    skipped = [] if MUTANT == "drop-skips" else p["skipped-checks"]
    db["checks"].append({"cycle-id": cid, "occasion": occ, "attempt": att, "outcome": "passed" if passed else "failed",
                         "harness-digest": p["harness-digest"], "goldens-digest": p["goldens-digest"],
                         "identity-probe-digest": p["identity-probe-digest"], "grader-digests": p["grader-digests"]})
    if occ == "before-fence":
        db["cycles"][cid] = {"cycle-id": cid, "cut-id": p["cut-id"], "status": "running", "stage": "fence",
                             "stage-entered-at": AT, "consecutive-gate-refusals": 0, "budget": b,
                             "harness-digest": p["harness-digest"], "gate-attempts": 0, "attempts": 0, "failed-attempts": 0,
                             "evidence": [], "self-check-failed": not passed,
                             "self-check-failure": None if passed else p["failing-check"],
                             "clean-room": False, "measurement-filed": False, "score-stage-failed": False,
                             "scoreboard-reported": False}
        db["order"].append(cid)
    elif not passed:
        c["self-check-failed"] = True
        c["self-check-failure"] = p["failing-check"]
    if passed:
        e = ev("harness-self-check-passed", **{k: p[k] for k in ("cycle-id", "cut-id", "occasion", "attempt", "harness-digest",
                                                                  "goldens-digest", "identity-probe-digest", "grader-digests")},
               **{"skipped-checks": skipped, "checked-at": AT})
        if MUTANT != "event-drops-budget":
            e["budget"] = b
        return [e]
    fc = "self-check failed" if MUTANT == "failing-check-dropped" else p["failing-check"]
    if MUTANT == "failing-check-dropped":
        db["cycles"][cid]["self-check-failure"] = fc
    e = ev("harness-self-check-failed", **{k: p[k] for k in ("cycle-id", "cut-id", "occasion", "attempt", "harness-digest")},
           **{"budget": b, "failing-check": fc, "detail": p.get("detail", fc), "reworded-goldens": p["reworded-goldens"],
              "checked-at": AT})
    return [e], cid


def prove_clean_room(p):
    need(p, {"cycle-id": "str", "cut-id": "str", "clean-room-commit": "str",
             "fenced-paths": "strs" if MUTANT in ("accept-empty-fence", "empty-fence-refusal") else "strs1",
             "root-commits": "int", "commits": "int", "unmatched-paths": "strs", "paths-left-in-tree": "strs",
             "paths-reachable-in-object-graph": "strs"})
    if MUTANT == "empty-fence-refusal" and not p["fenced-paths"]:
        raise Refused("no-fenced-paths")
    c = cycle(p["cycle-id"])
    if not c or (c["self-check-failed"] and MUTANT != "prove-after-failed-check"):
        raise Refused("self-check-not-passed")
    if p["unmatched-paths"] and MUTANT != "no-unmatched-check":
        raise Refused("fenced-path-matched-nothing", paths=p["unmatched-paths"])
    if p["root-commits"] != 1 and MUTANT != "roots-not-checked":
        raise Refused("root-commits-not-one")
    if p["commits"] != 1 and MUTANT != "commits-not-checked":
        raise Refused("commits-not-one")
    if p["paths-left-in-tree"] and MUTANT != "tree-not-checked":
        raise Refused("fenced-path-left-in-tree", paths=p["paths-left-in-tree"])
    if p["paths-reachable-in-object-graph"] and MUTANT != "object-graph-not-checked":
        raise Refused("fenced-path-reachable-in-object-graph", paths=p["paths-reachable-in-object-graph"])
    if c["clean-room"]:
        raise Refused("clean-room-already-proved")
    if p["cut-id"] != c["cut-id"]:
        raise Refused("not-the-self-checked-cut")
    unfenced = [t for t in TOOLS if t not in p["fenced-paths"]]
    if unfenced and MUTANT != "ignore-scoring-tools":
        raise Refused("scoring-tool-not-fenced", paths=unfenced)
    if c["status"] != "running":
        raise Refused("cycle-concluded")
    c["clean-room"] = True
    c["clean-room-commit"] = p["clean-room-commit"]
    if MUTANT != "clean-room-stays-fence":
        c["stage"] = "regenerate"
    return [ev("clean-room-proved", **{k: p[k] for k in ("cycle-id", "cut-id", "clean-room-commit", "fenced-paths")}, **{"proved-at": AT})]


def record_regenerated_candidate(p):
    need(p, {"cycle-id": "str", "attempt": "int", "purpose": {"fresh-generation", "verification-repair"},
             "candidate-digest": "str", "parent-candidate-digest": ("str",), "mounted-evidence": "strs"})
    if not 1 <= p["attempt"] <= 3:
        raise BadParams()
    c = cycle(p["cycle-id"])
    a = p["attempt"]
    if not c:
        raise Refused("no-cycle")
    if not c["clean-room"] and MUTANT != "regen-before-room":
        raise Refused("no-clean-room")
    if a != c["attempts"] + 1:
        raise Refused("not-the-next-attempt")
    if a > c["budget"]["gate-attempt-limit"]:
        raise Refused("over-gate-attempt-limit")
    if (a == 1) != (p["purpose"] == "fresh-generation"):
        raise Refused("wrong-purpose")
    if c["status"] != "running":
        raise Refused("cycle-concluded")
    if not ((c["stage"] == "regenerate" or MUTANT == "regen-before-room") and c["failed-attempts"] == a - 1):
        raise Refused("not-after-a-failed-attempt")
    if p["purpose"] == "verification-repair" and p.get("parent-candidate-digest") != c.get("candidate-digest"):
        raise Refused("not-the-previous-candidate")
    if (p["purpose"] == "fresh-generation" and p["mounted-evidence"]) or \
            (MUTANT != "mount-any-evidence" and any(e not in c["evidence"] for e in p["mounted-evidence"])):
        raise Refused("evidence-not-mountable")
    c["attempts"] = a
    c["gate-attempts"] = a
    c["candidate-digest"] = p["candidate-digest"]
    c["stage"] = "gate"
    rows(c["cycle-id"])[str(a)] = {"cycle-id": c["cycle-id"], "attempt": a, "candidate-digest": p["candidate-digest"], "installed?": False}
    keys = ("cycle-id", "attempt", "purpose", "candidate-digest", "parent-candidate-digest")
    return [ev("candidate-regenerated", **{k: p[k] for k in keys if k in p}, **{"regenerated-at": AT})]


def install_candidate_in_gate_room(p):
    need(p, {"cycle-id": "str", "attempt": "int", "candidate-digest": "str",
             "purpose": {"fresh-generation", "verification-repair"}, "gate-room": "str"})
    c = cycle(p["cycle-id"])
    if not c or c["attempts"] != p["attempt"]:
        raise Refused("no-candidate-for-attempt")
    if p["candidate-digest"] != c.get("candidate-digest"):
        raise Refused("not-the-regenerated-candidate")
    r = rows(c["cycle-id"])[str(p["attempt"])]
    if r["installed?"]:
        raise Refused("already-installed")
    if c["status"] != "running":
        raise Refused("cycle-concluded")
    r["installed?"] = True
    return [ev("candidate-installed-in-gate-room", **p, **{"installed-at": AT})]


def record_gate_attempt(p):
    need(p, {"cycle-id": "str", "attempt": "int", "candidate-digest": "str", "harness-digest": "str",
             "outcome": {"passed", "failed"}, "reason": (set(GATE_REASONS),), "identity-probe-present": "bool",
             "identity-probe-digest": "str", "seam-answered-before-boot": "bool", "boot-budget-seconds": "pos",
             "boot-polls": "pos", "answered-on-poll": ("pos",), "identity-probe-result": ("str",), "evidence-root": "str",
             "detail": "str", "grader-digests": "strs"})
    cid, a = p["cycle-id"], p["attempt"]
    c = cycle(cid)
    r = rows(cid).get(str(a)) if c else None
    passed = p["outcome"] == "passed"
    if not r or not r["installed?"]:
        raise Refused("not-installed")
    if "verdict" in r:
        raise Refused("verdict-already-recorded")
    if (not passed and "reason" not in p) or (passed and "reason" in p):
        raise Refused("reason-does-not-fit-outcome")
    if (passed or MUTANT == "failed-needs-poll") and ("answered-on-poll" not in p or "identity-probe-result" not in p):
        raise Refused("pass-without-poll-or-probe-result")
    if "answered-on-poll" in p and p["answered-on-poll"] > p["boot-polls"]:
        raise Refused("answered-after-last-poll")
    if p["candidate-digest"] != r["candidate-digest"]:
        raise Refused("not-the-installed-candidate")
    chk = check_of(cid, "before-gate", a)
    refusal = None
    if not (chk and chk["outcome"] == "passed" and (chk["harness-digest"] == p["harness-digest"] or MUTANT == "any-harness-digest")
            and chk["goldens-digest"] == goldens_digest()):
        refusal = "no-passing-self-check"
    elif not p["identity-probe-present"] and MUTANT != "no-probe-check":
        refusal = "identity-probe-missing"
    elif p["identity-probe-digest"] != chk["identity-probe-digest"] and MUTANT != "any-probe-digest":
        refusal = "identity-probe-not-the-instruments"
    elif p["seam-answered-before-boot"]:
        refusal = "seam-answered-before-boot"
    if refusal:
        if MUTANT == "refuse-but-spend":
            r["verdict"] = "failed"
        raise Refused(refusal)
    if c["status"] != "running":
        raise Refused("cycle-concluded")
    if chk["grader-digests"] != p["grader-digests"]:
        raise Refused("other-grader-digests")
    common = {k: p[k] for k in ("cycle-id", "attempt", "candidate-digest", "boot-budget-seconds", "boot-polls", "evidence-root")}
    c["consecutive-gate-refusals"] = 0
    r["evidence-root"] = p["evidence-root"]
    if passed:
        r["verdict"] = "passed"
        r["boot-margin-polls"] = (p["boot-budget-seconds"] if MUTANT == "margin-from-seconds" else p["boot-polls"]) - p["answered-on-poll"]
        if MUTANT != "passed-stays-gate":
            c["stage"] = "score"
        return [ev("gate-attempt-passed", **common, **{"cut-id": c["cut-id"], "answered-on-poll": p["answered-on-poll"],
                                                       "identity-probe-result": p["identity-probe-result"], "passed-at": AT})]
    reason = "suite-red" if MUTANT == "ignore-offered-reason" else p["reason"]
    r["verdict"] = "failed"
    r["reason"] = reason
    c["failed-attempts"] += 1
    c["evidence"].append(p["evidence-root"])
    if c["failed-attempts"] < c["budget"]["gate-attempt-limit"] and MUTANT != "failed-stays-gate":
        c["stage"] = "regenerate"
    return [ev("gate-attempt-failed", **common, **{"reason": reason, "detail": p["detail"], "failed-at": AT})], cid


def record_gate_refusal(p):
    need(p, {"cycle-id": "str", "attempt": "int",
             "refusal": {"seam-answered-before-boot", "identity-probe-missing", "identity-probe-not-the-instruments"},
             "detail": "str"})
    c = cycle(p["cycle-id"])
    r = rows(p["cycle-id"]).get(str(p["attempt"])) if c else None
    if not r or not r["installed?"]:
        raise Refused("not-installed")
    if "verdict" in r:
        raise Refused("verdict-already-recorded")
    if c["status"] != "running":
        raise Refused("cycle-concluded")
    r["last-refusal"] = p["refusal"]
    c["consecutive-gate-refusals"] += 1
    return [ev("gate-attempt-refused", **p, **{"refused-at": AT})], p["cycle-id"]


def pass_cycle(p):
    need(p, {"cycle-id": "str"})
    c = cycle(p["cycle-id"])
    if not c:
        raise Refused("no-cycle")
    if not c["scoreboard-reported"]:
        raise Refused("scoreboard-not-reported")
    if c["status"] != "running":
        raise Refused("cycle-concluded")
    c["status"], c["stage"] = "passed", "result"
    return [ev("cycle-passed", **{"cycle-id": c["cycle-id"], "candidate-digest": c.get("candidate-digest", ""), "concluded-at": AT})]


def fail_cycle(p):
    need(p, {"cycle-id": "str", "stage": set(STAGES), "reason": set(FAIL_REASONS), "detail": "str"})
    c = cycle(p["cycle-id"])
    if not c:
        raise Refused("no-cycle")
    if c["status"] != "running":
        raise Refused("cycle-concluded")
    if p["stage"] != c["stage"]:
        raise Refused("not-the-stage-reached")
    own = {"fence-refused": "fence", "candidate-not-regenerated": "regenerate", "gate-attempts-exhausted": "gate",
           "score-stage-failed": "score"}
    if own.get(p["reason"], p["stage"]) != p["stage"]:
        raise Refused("reason-not-of-the-stage")
    if p["reason"] == "infrastructure" and p["stage"] != "gate":
        raise Refused("infrastructure-not-at-the-gate")
    detail = "" if MUTANT == "fail-cycle-wrong-detail" else p["detail"]
    if MUTANT != "fail-cycle-no-record":
        c["status"] = "failed"
        c["failure"] = {"stage": p["stage"], "reason": p["reason"], "detail": detail}
    e = ev("cycle-failed", **{"cycle-id": c["cycle-id"], "stage": p["stage"], "reason": p["reason"], "detail": detail, "concluded-at": AT})
    if c.get("candidate-digest"):
        e["candidate-digest"] = c["candidate-digest"]
    return [e]


# ---- the queries

VIEW = ("cycle-id", "cut-id", "status", "stage", "stage-entered-at", "consecutive-gate-refusals", "budget", "harness-digest",
        "clean-room-commit", "candidate-digest", "gate-attempts", "failure")


def view(c):
    v = {k: c[k] for k in VIEW if c.get(k) is not None}
    v["attempts"] = [rows(c["cycle-id"])[k] for k in sorted(rows(c["cycle-id"]), key=int)]
    if MUTANT == "score-in-view" and c["status"] == "failed":
        v.update({"passes": 0, "total": 27})
    return v


def cycle_progress(p):
    if not isinstance(p, dict):
        raise BadParams()
    if "cycle-id" in p:
        c = cycle(p["cycle-id"])
        return {"cycle": view(c) if c else None}
    return {"cycles": [view(cycle(cid)) for cid in db["order"]]}


def cycles_to_conclude(p):
    return {"cycle-ids": [cid for cid in db["order"] if due(cycle(cid))]}


COMMANDS = {
    "cycle-preparation/source-cut-pinned": source_cut_pinned,
    "grader-lifecycle/instrument-golden-written": golden_written,
    "grader-lifecycle/instrument-golden-moved": golden_moved,
    "measurement/measurement-filed": measurement_filed,
    "measurement/score-stage-failed": score_stage_failed,
    "measurement/scoreboard-reported": scoreboard_reported,
    RC + "record-harness-self-check": record_harness_self_check,
    RC + "prove-clean-room": prove_clean_room,
    RC + "record-regenerated-candidate": record_regenerated_candidate,
    RC + "install-candidate-in-gate-room": install_candidate_in_gate_room,
    RC + "record-gate-attempt": record_gate_attempt,
    RC + "record-gate-refusal": record_gate_refusal,
    RC + "pass-cycle": pass_cycle,
    RC + "fail-cycle": fail_cycle,
}
QUERIES = {RC + "cycle-progress": cycle_progress, RC + "cycles-to-conclude": cycles_to_conclude}


def handle(method, p):
    if method in QUERIES:
        return QUERIES[method](p), None
    if method not in COMMANDS:
        return None, {"code": -32601, "message": "unknown method"}
    try:
        out = COMMANDS[method](p)
    except BadParams:
        return None, {"code": -32602, "message": "invalid params"}
    except Refused as r:
        names = r.data.get("paths") or r.data.get("goldens") or []
        msg = r.kind + (": " + ", ".join(names) if names else "")
        return None, {"code": -32000, "message": msg, "data": dict({"refusal": r.kind}, **r.data)}
    evs, cid = out if isinstance(out, tuple) else (out, None)
    if cid:
        policy(cid)
    save()
    return {"events": evs}, None


for raw in sys.stdin:
    try:
        req = json.loads(raw)
    except ValueError:
        print(json.dumps({"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "parse error"}}), flush=True)
        continue
    result, error = handle(req.get("method"), req.get("params") if req.get("params") is not None else {})
    resp = {"jsonrpc": "2.0", "id": req.get("id")}
    resp.update({"error": error} if error else {"result": result})
    print(json.dumps(resp), flush=True)
    if MUTANT == "exit-after-one":
        sys.exit(0)
