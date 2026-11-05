#!/usr/bin/env python3
"""A seam grader for the executable workflow contract and the cut/selection refusals. It
drives a candidate harness only through the methods declared in .pi/model/rpc.yaml
(JSON-RPC over stdio) and files one verdict per item it grades, in the legacy verdict format
[{id, verdict: pass|fail, evidence}]. An item it cannot reach is filed as fail with the reason.

usage: grade_contract.py START_SCRIPT > verdicts.json
It never reads candidate source; it starts the candidate with an isolated state root. The
source commit it asks the candidate to cut is GRADE_SOURCE_COMMIT, or else HEAD of the
repository this grader sits in."""
import json, os, subprocess, sys, tempfile
from seam import Harness

ITEMS = ["01-010", "04-005", "04-006", "05-005"]


def verdict(item, passed, evidence):
    return {"id": item, "verdict": "pass" if passed else "fail", "evidence": str(evidence)[:800]}


def source_commit():
    if os.environ.get("GRADE_SOURCE_COMMIT"):
        return os.environ["GRADE_SOURCE_COMMIT"]
    here = os.path.dirname(os.path.abspath(__file__))
    return subprocess.check_output(["git", "-C", here, "rev-parse", "HEAD"]).decode().strip()


def ok(r):
    return bool(r) and r.get("ok") is True


def refused(r, rid):
    """A refusal: the request's own id, ok false, a non-empty error, and no receipt."""
    return bool(r) and r.get("id") == rid and r.get("ok") is False and bool(r.get("error")) \
        and not r.get("result")


def result(r):
    return (r or {}).get("result") if isinstance((r or {}).get("result"), dict) else {}


def item_ids(items):
    return [i.get("id") if isinstance(i, dict) else i for i in (items or [])]


def status_of(sel):
    return str(sel.get("status", "")).replace("_", "-")


def cut_result(outcome):
    """The prepared cut the outcome names: result.outputs.cut-result (rpc.yaml prepare-cut)."""
    inner = outcome.get("result") if isinstance(outcome.get("result"), dict) else {}
    outputs = inner.get("outputs") if isinstance(inner.get("outputs"), dict) else {}
    cr = outputs.get("cut-result")
    return cr if isinstance(cr, dict) else {}


def grade(start):
    state = tempfile.mkdtemp(prefix="pi-harness-grade-contract-")
    h = Harness(start, env={"PI_HARNESS_STATE_ROOT": state})
    out = {}
    try:
        # The executable workflow contract (01-010, 05-005).
        wc = h.call(1, "workflow-contract", {}, timeout=180)
        wr = result(wc)
        brf, obf = wr.get("builderResultFields"), wr.get("observationFields")
        out["01-010"] = verdict("01-010", ok(wc) and isinstance(brf, list) and "builderSessionId" in brf,
                                f"workflow-contract ok={ok(wc)}; builderResultFields={brf!r}; "
                                f"error={(wc or {}).get('error')!r}")
        out["05-005"] = verdict("05-005", ok(wc) and isinstance(obf, list) and "artifacts" in obf,
                                f"workflow-contract ok={ok(wc)}; observationFields={obf!r}; "
                                f"error={(wc or {}).get('error')!r}")

        # 04-005: invalid cut requests are refused before any cut task starts. They run first,
        # against fresh state, so any task that exists before the first accepted cut came from them.
        commit = source_commit()
        bad = [
            (10, {"sourceCommit": commit[:12], "executionBackend": "host"}, "malformed sourceCommit"),
            (11, {"sourceCommit": "0" * 40, "executionBackend": "host"}, "40-hex string that is not a commit"),
            (12, {"sourceCommit": commit, "executionBackend": "no-such-backend"}, "unsupported backend"),
        ]
        refusals, bad_ok = [], True
        for rid, params, what in bad:
            r = h.call(rid, "prepare-cut", params, timeout=60)
            good = refused(r, rid)
            bad_ok = bad_ok and good
            refusals.append(f"{what} -> {r!r}")

        receipt = h.call(20, "prepare-cut", {"sourceCommit": commit, "executionBackend": "host"}, timeout=60)
        task = result(receipt).get("taskId")
        first = h.call(21, "wait", {"taskId": task}, timeout=300) if ok(receipt) and task is not None else None
        o1 = result(first)
        cr1 = cut_result(o1)
        cut = cr1.get("cutId")

        # No task exists below the first accepted one: the refusals started none.
        stray = []
        if isinstance(task, int) and not isinstance(task, bool):
            for n, t in enumerate(range(max(0, task - 25), task)):
                w = h.call(30 + n, "wait", {"taskId": t}, timeout=20)
                if w is None or not refused(w, 30 + n):
                    stray.append((t, w))
        no_task = isinstance(task, int) and not stray

        # A second request for the same identity never replaces the prepared cut's content.
        again = h.call(60, "prepare-cut", {"sourceCommit": commit, "executionBackend": "host"}, timeout=60)
        if ok(again):
            t2 = result(again).get("taskId")
            second = h.call(61, "wait", {"taskId": t2}, timeout=300) if t2 is not None else None
            cr2 = cut_result(result(second))
            kept = bool(cut) and cr2.get("cutId") == cut and cr2.get("treeId") == cr1.get("treeId")
            collide = f"re-prepare -> cut {cr2.get('cutId')!r} tree {cr2.get('treeId')!r}"
        else:
            kept = refused(again, 60)
            collide = f"re-prepare refused: {again!r}"
        prepared = ok(first) and o1.get("status") == "completed" and bool(cut)
        out["04-005"] = verdict("04-005", bad_ok and prepared and no_task and kept,
                                f"refusals ok={bad_ok}: {refusals}; valid receipt {receipt!r}; "
                                f"prepared cut {cut!r}; tasks below first accepted: {stray!r}; "
                                f"{collide} (first {cut!r}/{cr1.get('treeId')!r})")

        # 04-006: the selection path, then an unknown and a repeated item.
        if not prepared:
            out["04-006"] = verdict("04-006", False, f"no prepared cut to select from: {first!r}")
            return [out[i] for i in ITEMS]
        comp = h.call(70, "slice-composition", {"cut": cut}, timeout=60)
        slices = [s for s in (result(comp).get("slices") or [])
                  if isinstance(s, dict) and len(item_ids(s.get("items"))) >= 2]
        if not ok(comp) or not slices:
            out["04-006"] = verdict("04-006", False, f"no slice of two or more items in cut {cut!r}: {comp!r}"[:800])
            return [out[i] for i in ITEMS]
        sl = slices[0].get("slice")
        ids = item_ids(slices[0].get("items"))
        known = set()
        for s in result(comp).get("slices") or []:
            if isinstance(s, dict):
                known.update(item_ids(s.get("items")))
        prefix = ids[0][: ids[0].rfind("-") + 1] if "-" in ids[0] else "@item-00-"
        unknown = next(f"{prefix}{n:03d}" for n in range(999, 0, -1) if f"{prefix}{n:03d}" not in known)

        u = h.call(71, "preview-selection", {"cut": cut, "slice": sl, "itemIds": [ids[0], unknown]}, timeout=60)
        d = h.call(72, "preview-selection", {"cut": cut, "slice": sl, "itemIds": [ids[0], ids[1], ids[0]]},
                   timeout=60)
        u_ok = refused(u, 71) and unknown in str(u.get("error"))
        d_ok = refused(d, 72) and ids[0] in str(d.get("error"))

        # Nothing was recorded: a valid preview of the same slice still works, is awaiting
        # approval, and carries exactly the requested denominator.
        v = h.call(73, "preview-selection", {"cut": cut, "slice": sl, "itemIds": [ids[0], ids[1]]}, timeout=60)
        rv = result(v)
        v_ok = ok(v) and bool(rv.get("selectionId")) and status_of(rv) == "awaiting-approval" \
            and item_ids(rv.get("items")) == [ids[0], ids[1]]
        out["04-006"] = verdict("04-006", u_ok and d_ok and v_ok,
                                f"slice {sl!r}; unknown {unknown!r} -> {u!r}; repeated {ids[0]!r} -> {d!r}; "
                                f"valid preview -> ok={ok(v)} id={rv.get('selectionId')!r} "
                                f"status={rv.get('status')!r} items={item_ids(rv.get('items'))!r} "
                                f"error={(v or {}).get('error')!r}")
    except Exception as e:  # an unreachable item fails, never passes
        for item in ITEMS:
            out.setdefault(item, verdict(item, False, f"not reached: {type(e).__name__}: {e}"))
    finally:
        h.stop()
    for item in ITEMS:
        out.setdefault(item, verdict(item, False, "not reached"))
    return [out[i] for i in ITEMS]


if __name__ == "__main__":
    print(json.dumps(grade(sys.argv[1]), indent=1))
