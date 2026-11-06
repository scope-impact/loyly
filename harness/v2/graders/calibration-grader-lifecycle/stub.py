#!/usr/bin/env python3
"""Calibration fixture for grade_grader_lifecycle.exs: a minimal grader-lifecycle context over
its seam (.pi/spec/grader-lifecycle/seam.md), written from the spec alone. Not the product: it
keeps its facts as JSON in the LOYLY_GRAIN_STORE file, not in Grain, and runs no ORC workflow.

MUTANT=<name> breaks one behaviour of the scenarios (calibrate.sh lists them and the item each
must fail); unset, it is the reference that carries every scenario."""
import json, os, sys

MUTANT = os.environ.get("MUTANT", "")
STORE = os.environ.get("LOYLY_GRAIN_STORE") or os.path.join(os.getcwd(), "stub-store.json")
DIGESTS = os.environ.get("LOYLY_SCENARIO_DIGESTS")
AT = "2026-11-07T00:00:00Z"
REASONS = ("instrument-behavior-changed", "wording-replaced", "spec-grew")
if MUTANT == "accept-fourth-reason":
    REASONS += ("make-the-self-check-green",)


def read_digests():
    try:
        with open(DIGESTS) as f:
            d = json.load(f)
        return d if isinstance(d, dict) else {}
    except (OSError, TypeError, ValueError):
        return {}


AT_BOOT = read_digests()


def digest_of_scenario(s):
    if MUTANT == "ignore-digests":
        return None
    d = AT_BOOT if MUTANT == "digests-at-boot" else read_digests()
    return d.get(s)


def load():
    try:
        with open(STORE) as f:
            return json.load(f)
    except (OSError, ValueError):
        return {"goldens": []}


def save(db):
    with open(STORE, "w") as f:
        json.dump(db, f)


def refusal(kind):
    return None, {"code": -32000, "message": kind, "data": {"refusal": kind}}


def bad_params():
    return None, {"code": -32602, "message": "invalid params"}


def strs(p, *keys):
    return isinstance(p, dict) and all(isinstance(p.get(k), str) for k in keys)


def find(db, gid):
    return next((g for g in db["goldens"] if g["golden-id"] == gid), None)


def write(p):
    if not strs(p, "golden-id", "scenario", "scenario-digest", "path", "digest"):
        return bad_params()
    db = load()
    if find(db, p["golden-id"]):
        return refusal("golden-exists")
    if p["scenario-digest"] != digest_of_scenario(p["scenario"]):
        return refusal("scenario-digest-mismatch")
    db["goldens"].append({"golden-id": p["golden-id"], "scenario": p["scenario"], "path": p["path"],
                          "digest": p["digest"], "scenario-digest": p["scenario-digest"], "moves": []})
    save(db)
    keys = ("golden-id", "scenario", "scenario-digest", "path", "digest")
    return {"events": [dict({k: p[k] for k in keys}, type="grader-lifecycle/instrument-golden-written", **{"written-at": AT})]}, None


def move(p):
    if not strs(p, "golden-id", "digest", "reason", "note", "scenario-digest") or not isinstance(p.get("additions-only"), bool):
        return bad_params()
    if p["reason"] not in REASONS:
        return refusal("bad-reason") if MUTANT == "fourth-reason-refusal" else bad_params()
    if p["note"] == "" and MUTANT == "empty-note-schema":
        return bad_params()
    db = load()
    g = find(db, p["golden-id"])
    if not g:
        return refusal("no-such-golden")
    refused = None
    if p["digest"] == g["digest"]:
        refused = "same-digest"
    elif p["reason"] == "spec-grew" and not p["additions-only"] and MUTANT != "spec-grew-any-diff":
        refused = "spec-grew-not-additions-only"
    elif p["note"] == "" and MUTANT != "accept-empty-note":
        refused = "empty-note"
    elif p["scenario-digest"] != digest_of_scenario(g["scenario"]):
        refused = "stale-scenario-digest"
    if refused:
        if MUTANT == "refuse-but-record":
            g["digest"] = p["digest"]
            save(db)
        return refusal(refused)
    frm = g["digest"]
    if MUTANT == "from-is-to":
        frm = p["digest"]
    if MUTANT != "digest-not-moved":
        g["digest"] = p["digest"]
    g["scenario-digest"] = p["scenario-digest"]
    note = "" if MUTANT == "drop-note" else p["note"]
    if MUTANT != "no-move-history":
        g["moves"].append({"from-digest": frm, "to-digest": p["digest"], "reason": p["reason"], "note": note})
    save(db)
    e = {"type": "grader-lifecycle/instrument-golden-moved", "golden-id": p["golden-id"], "from-digest": frm,
         "to-digest": p["digest"], "reason": p["reason"], "additions-only": p["additions-only"], "note": note,
         "scenario-digest": p["scenario-digest"], "moved-at": AT}
    return {"events": [e]}, None


def goldens(p):
    return {"goldens": [{k: g[k] for k in ("golden-id", "path", "digest", "scenario-digest", "moves")}
                        for g in load()["goldens"]]}, None


HANDLERS = {
    "grader-lifecycle/write-instrument-golden": write,
    "grader-lifecycle/move-instrument-golden": move,
    "grader-lifecycle/instrument-goldens": goldens,
}

for raw in sys.stdin:
    try:
        req = json.loads(raw)
    except ValueError:
        print(json.dumps({"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "parse error"}}), flush=True)
        continue
    handler = HANDLERS.get(req.get("method"))
    if handler:
        result, error = handler(req.get("params") or {})
    else:
        result, error = None, {"code": -32601, "message": "unknown method"}
    resp = {"jsonrpc": "2.0", "id": req.get("id")}
    resp.update({"error": error} if error else {"result": result})
    print(json.dumps(resp), flush=True)
    if MUTANT == "exit-after-one":
        sys.exit(0)
