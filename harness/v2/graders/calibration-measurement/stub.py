#!/usr/bin/env python3
"""Calibration fixture for grade_measurement.exs: a minimal measurement context over its seam
(.pi/spec/measurement/seam.md), written from the spec alone. Not the product: it keeps its
facts as JSON in the LOYLY_GRAIN_STORE file, not in Grain, and runs no ORC workflow.

MUTANT=<name> breaks one behaviour of the scenarios (calibrate.sh lists them and the item
each must fail); unset, it is the reference that carries every scenario."""
import hashlib, json, os, sys
from fractions import Fraction

MUTANT = os.environ.get("MUTANT", "")
STORE = os.environ.get("LOYLY_GRAIN_STORE") or os.path.join(os.getcwd(), "stub-store.json")
AT = "2026-11-07T00:00:00Z"


def load():
    try:
        with open(STORE) as f:
            return json.load(f)
    except (OSError, ValueError):
        return {"stamped": {}, "pinned": {}, "gated": {}, "checklists": {}, "filings": {},
                "measurements": {}, "boards": {}, "filed_order": []}


def save(db):
    with open(STORE, "w") as f:
        json.dump(db, f)


def refusal(kind, msg, **data):
    return None, {"code": -32000, "message": msg, "data": dict(refusal=kind, **data)}


def bad_params():
    return None, {"code": -32602, "message": "invalid params"}


def need(p, *keys):
    return isinstance(p, dict) and all(k in p for k in keys)


# ---- the consumed handoffs, appended as given

def handoff(type_, keys, fold):
    def h(p):
        if not need(p, *keys):
            return bad_params()
        db = load()
        fold(db, p)
        save(db)
        return {"events": [dict(type=type_, **p)]}, None
    return h


def fold_stamped(db, p):
    db["stamped"].setdefault(p["item-id"], p)


def fold_pinned(db, p):
    db["pinned"].setdefault(p["cut-id"], p)


def fold_gated(db, p):
    db["gated"].setdefault(p["cycle-id"], {"cut": p["cut-id"], "candidate": p["candidate-digest"]})


# ---- the checklist

def digest(items):
    if MUTANT == "digest-constant":
        return "sha256:0"
    return "sha256:" + hashlib.sha256(json.dumps(items, sort_keys=True).encode()).hexdigest()


def derive(p):
    if not need(p, "cut-id", "selection-id", "items", "unstamped", "skipped-slices"):
        return bad_params()
    db, cut = load(), p["cut-id"]
    items, unstamped = list(p["items"]), p["unstamped"]
    if cut not in db["pinned"]:
        return refusal("cut-not-pinned", "cut %s is not pinned" % cut)
    if unstamped:
        listed = unstamped[:1] if MUTANT == "first-unstamped-only" else unstamped
        return refusal("unstamped-scenarios", "unstamped scenarios: run specification/stamp-slice", unstamped=listed)
    if not items and MUTANT != "allow-empty":
        return refusal("empty-checklist", "the checklist would be empty")
    never = [i["item-id"] for i in items if i["item-id"] not in db["stamped"]]
    if never and MUTANT != "no-identity-check":
        return refusal("item-never-stamped", "never stamped", **{"item-ids": never})
    ids = [i["item-id"] for i in items]
    if len(set(ids)) != len(ids):
        return refusal("item-twice", "an item twice")
    if cut in db["checklists"]:
        return refusal("checklist-already-derived", "already derived")
    if any(i["title"] == "" for i in items) and MUTANT != "no-title-check":
        return refusal("empty-title", "an empty title")
    if MUTANT == "sort-items":
        items.sort(key=lambda i: i["item-id"])
    if MUTANT == "rule-empty":
        items = [dict(i, rule=i["rule"] if i["rule"] is not None else "") for i in items]
    if MUTANT == "rule-null":
        items = [dict(i, rule=None) for i in items]
    skipped = [] if MUTANT == "drop-skipped" else p["skipped-slices"]
    c = {"cut-id": cut, "selection-id": p["selection-id"], "checklist-digest": digest(items),
         "total": len(items), "items": items}
    db["checklists"][cut] = c
    save(db)
    return {"events": [dict(type="measurement/checklist-derived", **c, **{"skipped-slices": skipped, "derived-at": AT})]}, None


def checklist(p):
    if not need(p, "cut-id"):
        return bad_params()
    return {"checklist": load()["checklists"].get(p["cut-id"])}, None


# ---- the measurement

def status(db, cycle):
    m = db["measurements"].get(cycle)
    return m["status"] if m else "awaiting"


def file_verdicts(p):
    if not need(p, "cycle-id", "candidate-digest", "grader-digest", "approved-grader-digests", "verdicts"):
        return bad_params()
    db, cycle, vs = load(), p["cycle-id"], p["verdicts"]
    g = db["gated"].get(cycle)
    if not g or (p["candidate-digest"] != g["candidate"] and MUTANT != "any-candidate"):
        return refusal("not-the-gated-candidate", "not the gated candidate")
    c = db["checklists"].get(g["cut"])
    if not c and MUTANT != "no-checklist-check":
        return refusal("no-checklist", "no checklist for " + g["cut"])
    if not vs and MUTANT != "accept-zero":
        return refusal("zero-verdicts", "zero verdicts")
    known = {i["item-id"] for i in c["items"]} if c else set()
    bad = [v for v in vs if v.get("verdict") not in ("pass", "fail")]
    unknown = [v["item-id"] for v in vs if c and v["item-id"] not in known]
    if MUTANT == "unknown-first" and unknown:
        return refusal("unknown-item", "unknown items", **{"item-ids": unknown})
    if bad:
        return refusal("bad-verdict-value", "a verdict neither pass nor fail")
    if unknown and MUTANT != "no-unknown-check":
        return refusal("unknown-item", "unknown items", **{"item-ids": unknown})
    st = status(db, cycle)
    if st == "filed":
        return refusal("measurement-already-filed", "already filed")
    if st == "score-stage-failed":
        return refusal("score-stage-failed", "score stage failed")
    if p["grader-digest"] not in p["approved-grader-digests"] and MUTANT != "any-grader":
        return refusal("grader-not-approved", "grader not approved")
    for v in vs:
        if MUTANT == "no-reason-check":
            break
        r = v.get("reason")
        if r is not None and not (v["verdict"] == "fail" and r in ("inconclusive", "outage")):
            return refusal("bad-verdict-reason", "bad reason")
    returned = []
    for v in vs:
        e = {"item-id": v["item-id"], "verdict": v["verdict"]}
        if "reason" in v and MUTANT != "drop-reason":
            e["reason"] = v["reason"]
        if MUTANT == "filing-not-as-returned":
            e["evidence"] = ""
        elif MUTANT != "evidence-absent":
            e["evidence"] = v.get("evidence", "")
        elif "evidence" in v:
            e["evidence"] = v["evidence"]
        returned.append(e)
    filing = {"grader-digest": p["grader-digest"], "candidate-digest": p["candidate-digest"], "verdicts": returned}
    db["filings"].setdefault(cycle, []).append(filing)
    save(db)
    return {"events": [{"type": "measurement/item-verdicts-filed", "cycle-id": cycle,
                        "candidate-digest": p["candidate-digest"], "grader-digest": p["grader-digest"],
                        "verdicts": returned, "filed-at": AT}]}, None


def file_score(p):
    if not need(p, "cycle-id", "approved-grader-digests"):
        return bad_params()
    db, cycle = load(), p["cycle-id"]
    g = db["gated"].get(cycle)
    if not g:
        return refusal("not-the-gated-candidate", "not gated")
    filings = db["filings"].get(cycle, [])
    if not filings and MUTANT != "score-zero":
        return refusal("zero-verdicts", "zero verdicts filed")
    st = status(db, cycle)
    if st == "filed" and MUTANT != "refile-score":
        return refusal("measurement-already-filed", "already filed")
    if st == "score-stage-failed":
        return refusal("score-stage-failed", "score stage failed")
    if any(f["grader-digest"] not in p["approved-grader-digests"] for f in filings) and MUTANT != "score-any-grader":
        return refusal("grader-not-approved", "grader not approved")
    c = db["checklists"][g["cut"]]
    flat = [(f["grader-digest"], v) for f in filings for v in f["verdicts"]]
    last, superseded = {}, []
    for gd, v in flat:
        if v["item-id"] in last:
            if MUTANT == "first-wins":
                superseded.append({"item-id": v["item-id"], "grader-digest": gd, "verdict": v["verdict"]})
                continue
            pg, pv = last[v["item-id"]]
            superseded.append({"item-id": pv["item-id"], "grader-digest": pg, "verdict": pv["verdict"]})
        last[v["item-id"]] = (gd, v)
    if MUTANT == "no-superseded":
        superseded = []
    order = [v["item-id"] for _, v in flat] if MUTANT == "verdict-order" else [i["item-id"] for i in c["items"]]
    items = []
    for iid in dict.fromkeys(order):
        if iid in last:
            items.append(last[iid][1])
        elif MUTANT != "drop-unreached":
            items.append({"item-id": iid, "verdict": "fail", "reason": "unreached", "evidence": ""})
    passes = sum(1 for i in items if i["verdict"] == "pass")
    unreached = sum(1 for i in items if i.get("reason") == "unreached")
    total = len(items) if MUTANT == "drop-unreached" else c["total"]
    graders = list(dict.fromkeys(f["grader-digest"] for f in filings))
    m = {"cycle-id": cycle, "status": "filed", "cut-id": g["cut"], "candidate-digest": g["candidate"],
         "total": total, "passes": passes, "unreached": unreached, "items": items}
    db["measurements"][cycle] = dict(m, **{"selection-id": c["selection-id"]})
    db["filed_order"].append(cycle)
    event = {"type": "measurement/measurement-filed", "cycle-id": cycle, "cut-id": g["cut"],
             "selection-id": c["selection-id"], "candidate-digest": g["candidate"],
             "checklist-digest": c["checklist-digest"], "grader-digests": graders, "total": total,
             "passes": passes, "unreached": unreached, "items": items, "superseded": superseded, "filed-at": AT}
    if MUTANT != "no-policy":
        report(db, cycle)
    save(db)
    return {"events": [event]}, None


def fail_stage(p):
    if not need(p, "cycle-id", "refusal", "detail"):
        return bad_params()
    db, cycle = load(), p["cycle-id"]
    if cycle not in db["gated"]:
        return refusal("not-the-gated-candidate", "not gated")
    st = status(db, cycle)
    if st == "filed":
        return refusal("measurement-already-filed", "already filed")
    if st == "score-stage-failed":
        return refusal("score-stage-failed", "already failed")
    db["measurements"][cycle] = {"cycle-id": cycle, "status": "score-stage-failed"}
    if MUTANT != "stage-refusal-dropped":
        db["measurements"][cycle]["refusal"] = p["refusal"]
    save(db)
    return {"events": [{"type": "measurement/score-stage-failed", "cycle-id": cycle, "refusal": p["refusal"],
                        "detail": p["detail"], "failed-at": AT}]}, None


def cycle_measurement(p):
    if not need(p, "cycle-id"):
        return bad_params()
    m = load()["measurements"].get(p["cycle-id"])
    if m:
        m = {k: v for k, v in m.items() if k != "selection-id"}
    return {"measurement": m}, None


# ---- the scoreboard

def rate(passes, total):
    if total == 0:
        return "0.0%" if MUTANT == "zero-percent" else "n/a"
    x = Fraction(passes * 1000, total)  # tenths of a percent
    n, r = divmod(x.numerator, x.denominator)
    half = Fraction(r, x.denominator)
    if half > Fraction(1, 2) or (half == Fraction(1, 2) and (n % 2 == 1 or MUTANT == "half-up")):
        n += 1
    return "%d.%d%%" % (n // 10, n % 10)


def report(db, cycle):
    m = db["measurements"][cycle]
    before = db["filed_order"][:db["filed_order"].index(cycle)]
    prev = None
    if MUTANT == "previous-minus-one":
        cand = "C-%d" % (int(cycle.split("-")[1]) - 1)
        prev = db["measurements"].get(cand) or {"cycle-id": cand, "selection-id": m["selection-id"], "items": [], "total": 1}
    else:
        for c in reversed(before):
            pm = db["measurements"][c]
            if pm["selection-id"] == m["selection-id"]:
                prev = pm
                break
    board = {"cycle-id": cycle, "selection-id": m["selection-id"], "passes": m["passes"], "total": m["total"],
             "rate": rate(m["passes"], m["total"]),
             "previous-cycle-id": prev and prev["cycle-id"], "previous-selection-id": prev and prev["selection-id"],
             "new-items": [], "dropped-items": [], "reported-at": AT, "restatement": None}
    if prev:
        cur = {i["item-id"]: i["verdict"] for i in m["items"]}
        old = {i["item-id"]: i["verdict"] for i in prev["items"]}
        shared = [i for i in cur if i in old]
        pp = sum(1 for i in shared if old[i] == "pass")
        cp = sum(1 for i in shared if cur[i] == "pass")
        board["shared"] = {"items": len(shared), "previous-passes": pp, "passes": cp,
                           "previous-rate": rate(pp, len(shared)), "rate": rate(cp, len(shared)),
                           "newly-passing": [i for i in shared if old[i] == "fail" and cur[i] == "pass"],
                           "newly-failing": [i for i in shared if old[i] == "pass" and cur[i] == "fail"]}
        if MUTANT == "net-key":
            board["shared"]["net"] = cp - pp
        board["new-items"] = [i for i in cur if i not in old]
        board["dropped-items"] = [i for i in old if i not in cur]
        moved = bool(board["new-items"] or board["dropped-items"])
        if (moved and MUTANT != "no-restatement") or MUTANT == "always-restate":
            board["restatement"] = {"cycle-id": cycle, "previous-cycle-id": prev["cycle-id"], "previous-total": prev["total"],
                                    "total": m["total"], "added": len(board["new-items"]),
                                    "dropped": len(board["dropped-items"]), "restated-at": AT}
    db["boards"][cycle] = board


def report_scoreboard(p):
    if not need(p, "cycle-id"):
        return bad_params()
    db, cycle = load(), p["cycle-id"]
    if status(db, cycle) != "filed" and MUTANT != "report-unfiled":
        return refusal("no-measurement-filed", "no measurement filed")
    if cycle in db["boards"]:
        return refusal("scoreboard-already-reported", "already reported")
    if status(db, cycle) != "filed":
        db["boards"][cycle] = {"cycle-id": cycle, "passes": 0, "total": 1, "rate": "0.0%", "restatement": None}
    else:
        report(db, cycle)
    save(db)
    b = dict(db["boards"][cycle])
    r = b.pop("restatement")
    events = [dict(type="measurement/scoreboard-reported", **b)]
    if r:
        events.append(dict(type="measurement/moved-item-set-restated", **r))
    return {"events": events}, None


def scoreboard(p):
    if not need(p, "cycle-id"):
        return bad_params()
    return {"scoreboard": load()["boards"].get(p["cycle-id"])}, None


def to_report(p):
    db = load()
    return {"cycle-ids": [c for c in db["filed_order"] if c not in db["boards"]]}, None


HANDLERS = {
    "specification/scenario-stamped": handoff("specification/scenario-stamped",
                                              ("item-id", "slice", "source", "line", "title", "stamped-at"), fold_stamped),
    "cycle-preparation/source-cut-pinned": handoff("cycle-preparation/source-cut-pinned",
                                                   ("cut-id", "commit", "tree-digest", "pinned-at"), fold_pinned),
    "regeneration-cycle/gate-attempt-passed": handoff("regeneration-cycle/gate-attempt-passed",
                                                      ("cycle-id", "cut-id", "attempt", "candidate-digest", "boot-budget-seconds",
                                                       "boot-polls", "answered-on-poll", "identity-probe-result",
                                                       "evidence-root", "passed-at"), fold_gated),
    "measurement/derive-checklist": derive,
    "measurement/checklist": checklist,
    "measurement/file-item-verdicts": file_verdicts,
    "measurement/file-score": file_score,
    "measurement/fail-score-stage": fail_stage,
    "measurement/cycle-measurement": cycle_measurement,
    "measurement/report-scoreboard": report_scoreboard,
    "measurement/scoreboard": scoreboard,
    "measurement/measurements-to-report": to_report,
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
