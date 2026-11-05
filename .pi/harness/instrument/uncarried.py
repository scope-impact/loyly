#!/usr/bin/env python3
"""The uncarried calibration fixture: a proxy that runs a real harness and breaks exactly one
behavior, named by the item it violates. The carried fixture is the real harness itself.
usage: uncarried.py ITEM REAL_START_SCRIPT   (started by the grader as a harness)"""
import json, sys
from seam import Harness

item, real = sys.argv[1], sys.argv[2]
h = Harness(real)
submits = 0
try:
    for line in sys.stdin:
        if not line.strip():
            continue
        req = json.loads(line)
        resp = h.call(req.get("id"), req.get("method"), req.get("params"), timeout=60)
        if resp is None:
            continue
        method = req.get("method")
        if item == "02-002" and method == "cycle-contract" and resp.get("ok"):
            resp["result"]["stages"] = list(reversed(resp["result"]["stages"]))
        if item == "02-010" and method == "cycle-contract" and resp.get("ok"):
            resp["result"]["workflowName"] = "some-other-product"
        if item == "09-002" and method not in ("cycle-contract", "submit-cycle", "wait", "execution-status"):
            resp = {"id": req.get("id"), "ok": True, "result": {}}
        if item == "02-001" and method == "wait" and resp.get("ok"):
            resp = {"id": req.get("id"), "ok": True, "result": {"status": "completed"}}
        if item == "04-009" and method == "wait":
            resp = {"id": req.get("id"), "ok": True, "result": {"status": "completed"}}
        if item == "09-003" and method == "submit-cycle" and resp.get("ok"):
            submits += 1
            if submits > 1:
                resp["result"] = {**resp["result"], "taskId": resp["result"].get("taskId", 0) + 1000}
        print(json.dumps(resp), flush=True)
        if item == "09-001" and req.get("id") == 7:
            print(json.dumps(resp), flush=True)
finally:
    h.stop()
