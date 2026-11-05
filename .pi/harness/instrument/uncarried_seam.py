#!/usr/bin/env python3
"""The uncarried calibration fixture for grade_seam.py: a proxy that runs a real harness and
breaks exactly one behavior, named by the item it violates, passing everything else through.
usage: uncarried_seam.py ITEM REAL_START_SCRIPT   (started by the grader as a harness)"""
import json, signal, sys
from seam import Harness

# The grader stops this proxy with SIGTERM; exit through finally so the real harness it
# started in its own session is stopped too, never orphaned.
signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))

item, real = sys.argv[1], sys.argv[2]
h = Harness(real)
previews = 0
waited = set()
try:
    for line in sys.stdin:
        if not line.strip():
            continue
        req = json.loads(line)
        rid, method, params = req.get("id"), req.get("method"), req.get("params") or {}

        # Accepting what must be refused: answer without forwarding, so nothing starts.
        if item == "01-007" and method == "submit-approved-slice":
            resp = {"id": rid, "ok": True, "result": {
                "operationId": "op-uncarried", "selectionId": params.get("selectionId"),
                "tickId": "00000000-0000-0000-0000-000000000000", "correlationId": "c-uncarried",
                "taskId": 999999, "targetStack": params.get("targetStack"),
                "executionBackend": params.get("executionBackend")}}
        elif item == "06-001" and method == "optimize-prompt":
            resp = {"id": rid, "ok": True, "result": {"instruction": "uncarried", "score": 0}}
        elif item == "01-006" and method == "approve-selection":
            resp = {"id": rid, "ok": True, "result": {"selectionId": params.get("selectionId"),
                                                      "selectionDigest": params.get("selectionDigest"),
                                                      "status": "approved"}}
        else:
            resp = h.call(rid, method, params, timeout=300)
            if resp is None:
                continue
            if item == "01-005" and method == "preview-selection" and resp.get("ok"):
                previews += 1
                if previews > 1:
                    r = resp["result"]
                    r["selectionDigest"] = f"{r.get('selectionDigest')}-{previews}"
            if item == "04-008" and method == "wait" and resp.get("ok"):
                task = params.get("taskId")
                if task in waited:
                    resp = {"id": rid, "ok": True, "result": {"status": "failed", "error": "a second, different outcome",
                                                              "result": None}}
                waited.add(task)
        print(json.dumps(resp), flush=True)
finally:
    h.stop()
