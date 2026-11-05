#!/usr/bin/env python3
"""The harness grader: drives a candidate harness only through its public seam (JSON-RPC
over stdio, .pi/model/rpc.yaml) and files one verdict per checklist item it can reach, in
the legacy verdict format [{id, verdict: pass|fail, evidence}]. Items it cannot reach are
left out, so harness/score.clj records them as failed and the denominator holds.

usage: grade.py START_SCRIPT > verdicts.json
It never reads candidate source; it starts the candidate with an isolated state root."""
import json, sys, tempfile
from seam import Harness

STAGES = ["fence", "regenerate", "gate", "score", "scoreboard"]


def verdict(item, ok, evidence):
    return {"id": item, "verdict": "pass" if ok else "fail", "evidence": evidence}


def grade(start):
    state = tempfile.mkdtemp(prefix="pi-harness-grade-")
    h = Harness(start, env={"PI_HARNESS_STATE_ROOT": state})
    out = []
    try:
        r = h.call(1, "cycle-contract")
        result = (r or {}).get("result") or {}
        out.append(verdict("02-002", bool(r and r.get("ok") and result.get("stages") == STAGES),
                           f"cycle-contract stages: {result.get('stages')}"))
        out.append(verdict("02-010", bool(r and r.get("ok") and "pi-harness" in str(result.get("workflowName", "")).lower()
                                          and result.get("stages") == STAGES),
                           f"cycle-contract names {result.get('workflowName')!r}"))

        h.send({"id": 7, "method": "cycle-contract", "params": {}})
        first, extra = h.read(30), h.read(2)
        shape_ok = bool(first) and first.get("id") == 7 and (
            (first.get("ok") is True and "result" in first) or (first.get("ok") is False and "error" in first))
        out.append(verdict("09-001", shape_ok and extra is None,
                           f"answer {first!r}, extra line {extra!r}"))

        bad = h.call(8, "no-such-method-for-grading")
        after = h.call(9, "cycle-contract")
        refused = bool(bad) and bad.get("id") == 8 and bad.get("ok") is False and \
            "no-such-method-for-grading" in json.dumps(bad.get("error"))
        out.append(verdict("09-002", refused and bool(after) and after.get("ok") is True,
                           f"refusal {bad!r}; next contract ok={bool(after and after.get('ok'))}"))

        w = h.call(10, "wait", {"taskId": 987654321}, timeout=30)
        out.append(verdict("04-009", bool(w) and w.get("id") == 10 and w.get("ok") is False,
                           f"wait on an unknown task: {w!r}"))

        a = h.call(11, "submit-cycle", {"requestId": "grade-idempotence"})
        b = h.call(12, "submit-cycle", {"requestId": "grade-idempotence"})
        same = bool(a and b and a.get("ok") and b.get("ok")) and a.get("result") == b.get("result") \
            and (a.get("result") or {}).get("cycleId") == "grade-idempotence"
        out.append(verdict("09-003", same, f"first {a and a.get('result')!r}, second {b and b.get('result')!r}"))
    finally:
        h.stop()
    return out


if __name__ == "__main__":
    print(json.dumps(grade(sys.argv[1]), indent=1))
