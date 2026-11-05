#!/usr/bin/env python3
"""The uncarried calibration fixture for grade_contract.py: a proxy that runs a real harness
and breaks exactly one behavior, named by the item it violates, passing everything else through.
usage: uncarried_contract.py ITEM REAL_START_SCRIPT   (started by the grader as a harness)"""
import json, re, signal, sys
from seam import Harness

# The grader stops this proxy with SIGTERM; exit through finally so the real harness it
# started in its own session is stopped too, never orphaned.
signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))

item, real = sys.argv[1], sys.argv[2]
h = Harness(real)
try:
    for line in sys.stdin:
        if not line.strip():
            continue
        req = json.loads(line)
        rid, method, params = req.get("id"), req.get("method"), req.get("params") or {}

        if item == "04-005" and method == "prepare-cut" and (
                not re.fullmatch(r"[0-9a-f]{40}", str(params.get("sourceCommit", "")))
                or set(str(params.get("sourceCommit"))) == {"0"}
                or params.get("executionBackend") not in ("host", "gondolin")):
            # Accept an invalid identity with a receipt instead of refusing it.
            resp = {"id": rid, "ok": True, "result": {"cutId": params.get("sourceCommit"), "taskId": 999999,
                                                      "executionBackend": params.get("executionBackend")}}
        else:
            fwd = params
            if item == "04-006" and method == "preview-selection" and isinstance(params.get("itemIds"), list):
                # Record a selection for an ambiguous denominator: drop repeats and unknown items.
                comp = h.call(f"{rid}-comp", "slice-composition", {"cut": params.get("cut")}, timeout=300)
                known = set()
                for s in ((comp or {}).get("result") or {}).get("slices") or []:
                    if isinstance(s, dict) and s.get("slice") == params.get("slice"):
                        known = {i.get("id") if isinstance(i, dict) else i for i in s.get("items") or []}
                fwd = {**params, "itemIds": [i for i in dict.fromkeys(params["itemIds"]) if i in known]}
            resp = h.call(rid, method, fwd, timeout=300)
            if resp is None:
                continue
            if method == "workflow-contract" and resp.get("ok") and isinstance(resp.get("result"), dict):
                r = resp["result"]
                if item == "01-010":
                    r["builderResultFields"] = [f for f in r.get("builderResultFields") or []
                                                if f != "builderSessionId"]
                if item == "05-005":
                    r["observationFields"] = [f for f in r.get("observationFields") or [] if f != "artifacts"]
        print(json.dumps(resp), flush=True)
finally:
    h.stop()
