#!/usr/bin/env python3
"""A seam grader for the selection path, refusals, and terminal task outcomes. It drives a
candidate harness only through the methods declared in .pi/model/rpc.yaml (JSON-RPC over
stdio) and files one verdict per item it grades, in the legacy verdict format
[{id, verdict: pass|fail, evidence}]. An item it cannot reach is filed as fail with the reason.

usage: grade_seam.py START_SCRIPT > verdicts.json
It never reads candidate source; it starts the candidate with an isolated state root. The
source commit it asks the candidate to cut is GRADE_SOURCE_COMMIT, or else HEAD of the
repository this grader sits in."""
import json, os, subprocess, sys, tempfile
from seam import Harness

ITEMS = ["01-005", "01-006", "01-007", "04-008", "06-001"]


def verdict(item, ok, evidence):
    return {"id": item, "verdict": "pass" if ok else "fail", "evidence": str(evidence)[:600]}


def source_commit():
    if os.environ.get("GRADE_SOURCE_COMMIT"):
        return os.environ["GRADE_SOURCE_COMMIT"]
    here = os.path.dirname(os.path.abspath(__file__))
    return subprocess.check_output(["git", "-C", here, "rev-parse", "HEAD"]).decode().strip()


def ok(r):
    return bool(r) and r.get("ok") is True


def refused(r, rid):
    return bool(r) and r.get("id") == rid and r.get("ok") is False and bool(r.get("error"))


def result(r):
    return (r or {}).get("result") if isinstance((r or {}).get("result"), dict) else {}


def cut_ids(value):
    """Every cutId named anywhere in a response, in order (the receipt's, then the outcome's)."""
    found = []
    if isinstance(value, dict):
        for k, v in value.items():
            if k == "cutId" and isinstance(v, str):
                found.append(v)
            found += cut_ids(v)
    elif isinstance(value, list):
        for v in value:
            found += cut_ids(v)
    return found


def item_ids(items):
    return [i.get("id") if isinstance(i, dict) else i for i in (items or [])]


def status_of(sel):
    return str(sel.get("status", "")).replace("_", "-")


def grade(start):
    state = tempfile.mkdtemp(prefix="pi-harness-grade-seam-")
    h = Harness(start, env={"PI_HARNESS_STATE_ROOT": state})
    out = {}
    try:
        # Optimization refusals first: nothing else depends on them.
        base = {"purpose": "fresh-generation", "reflectionProvider": "bedrock",
                "reflectionModel": "grade-seam-model"}
        no_train = h.call(101, "optimize-prompt", {**base, "valset": [{"example": "v"}]}, timeout=60)
        not_list = h.call(102, "optimize-prompt", {**base, "trainset": [{"example": "t"}],
                                                   "valset": "not-a-collection"}, timeout=60)
        alive = h.call(103, "cycle-contract", timeout=60)
        out["06-001"] = verdict("06-001", refused(no_train, 101) and refused(not_list, 102) and ok(alive),
                                f"missing trainset -> {no_train!r}; non-collection valset -> {not_list!r}; "
                                f"harness still answers: {ok(alive)}")

        # A source-cut task: its one terminal outcome, read twice.
        commit = source_commit()
        receipt = h.call(110, "prepare-cut", {"sourceCommit": commit, "executionBackend": "host"}, timeout=60)
        task = result(receipt).get("taskId")
        first = h.call(111, "wait", {"taskId": task}, timeout=240) if ok(receipt) and task is not None else None
        again = h.call(112, "wait", {"taskId": task}, timeout=240) if first is not None else None
        o1, o2 = result(first), result(again)
        terminal = ok(first) and o1.get("status") in ("completed", "failed") and "result" in o1 \
            and (o1.get("status") != "failed" or bool(o1.get("error")))
        out["04-008"] = verdict("04-008", terminal and ok(again) and o2 == o1,
                                f"receipt {receipt!r}; first wait status {o1.get('status')!r}; "
                                f"second wait identical: {o2 == o1} (second status {o2.get('status')!r})")

        # The cut identity: the receipt's, else one the cut outcome names; the one the
        # composition accepts is the cut the selection path is driven against.
        cut, composition, tried = None, None, []
        for candidate in dict.fromkeys(cut_ids(receipt) + cut_ids(first)):
            c = h.call(120 + len(tried), "slice-composition", {"cut": candidate}, timeout=60)
            tried.append((candidate, c and c.get("ok"), c and c.get("error")))
            if ok(c):
                cut, composition = candidate, result(c)
                break
        slices = [s for s in (composition or {}).get("slices", []) or []
                  if isinstance(s, dict) and len(item_ids(s.get("items"))) >= 2]
        if cut is None or not slices:
            why = f"no cut whose composition holds a slice of two or more items; tried {tried!r}"
            for item in ("01-005", "01-006", "01-007"):
                out[item] = verdict(item, False, why)
            return [out[i] for i in ITEMS]

        slice_name = slices[0].get("slice")
        subset = item_ids(slices[0].get("items"))[:2]
        params = {"cut": cut, "slice": slice_name, "itemIds": subset}
        a = h.call(130, "preview-selection", params, timeout=60)
        b = h.call(131, "preview-selection", params, timeout=60)
        sa, sb = result(a), result(b)
        same = ok(a) and ok(b) and bool(sa.get("selectionId")) and bool(sa.get("selectionDigest")) \
            and sa.get("selectionId") == sb.get("selectionId") \
            and sa.get("selectionDigest") == sb.get("selectionDigest") \
            and item_ids(sa.get("items")) == item_ids(sb.get("items")) and len(item_ids(sa.get("items"))) == 2 \
            and status_of(sa) == "awaiting-approval" and status_of(sb) == "awaiting-approval"
        out["01-005"] = verdict("01-005", same,
                                f"slice {slice_name!r} items {subset!r}: first "
                                f"{(sa.get('selectionId'), sa.get('selectionDigest'), item_ids(sa.get('items')), sa.get('status'))!r}; "
                                f"second {(sb.get('selectionId'), sb.get('selectionDigest'), item_ids(sb.get('items')), sb.get('status'))!r}; "
                                f"errors {(a or {}).get('error')!r} {(b or {}).get('error')!r}")
        if not ok(a) or not sa.get("selectionId"):
            why = f"the partial selection could not be previewed: {a!r}"
            out["01-006"] = verdict("01-006", False, why)
            out["01-007"] = verdict("01-007", False, why)
            return [out[i] for i in ITEMS]
        sel_id, digest = sa["selectionId"], sa.get("selectionDigest")

        # 01-007 runs while the selection is still unapproved.
        submit = h.call(140, "submit-approved-slice",
                        {"requestId": "grade-seam-unapproved", "selectionId": sel_id,
                         "scope": ["grade-seam-candidate"], "targetStack": "node",
                         "executionBackend": "host"}, timeout=60)
        out["01-007"] = verdict("01-007", refused(submit, 140) and status_of(sa) == "awaiting-approval",
                                f"submitting unapproved partial selection {sel_id!r}: {submit!r}")

        other = "sha256:" + ("0" * 64 if not str(digest).endswith("0" * 64) else "1" * 64)
        wrong = h.call(150, "approve-selection", {"selectionId": sel_id, "selectionDigest": other}, timeout=60)
        still = h.call(151, "preview-selection", params, timeout=60)
        right = h.call(152, "approve-selection", {"selectionId": sel_id, "selectionDigest": digest}, timeout=60)
        out["01-006"] = verdict("01-006", refused(wrong, 150) and ok(still)
                                and result(still).get("selectionId") == sel_id
                                and status_of(result(still)) == "awaiting-approval"
                                and ok(right) and status_of(result(right)) == "approved",
                                f"wrong digest -> {wrong!r}; status after: {result(still).get('status')!r}; "
                                f"reviewed digest -> status {result(right).get('status')!r} {(right or {}).get('error')!r}")
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
