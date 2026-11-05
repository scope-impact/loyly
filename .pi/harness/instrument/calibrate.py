#!/usr/bin/env python3
"""Calibrate the grader, then seal it. Every item it grades must pass against the carried
fixture (a real harness) and fail against the uncarried fixture that breaks only that item.
Any other outcome stops here: an uncalibrated grader is never sealed.
usage: calibrate.py CARRIED_START_SCRIPT SEALED_ROOT"""
import hashlib, json, os, shutil, stat, sys, tempfile
from grade import grade

here = os.path.dirname(os.path.abspath(__file__))
carried, sealed_root = sys.argv[1], sys.argv[2]

carried_verdicts = {v["id"]: v for v in grade(carried)}
cases, failures = [], []
for item, v in sorted(carried_verdicts.items()):
    cases.append({"item": item, "role": "carried", "verdict": v["verdict"]})
    if v["verdict"] != "pass":
        failures.append(f"{item} carried should pass: {v['evidence']}")
for item in sorted(carried_verdicts):
    wrapper = tempfile.NamedTemporaryFile("w", suffix=".sh", delete=False)
    wrapper.write(f"#!/bin/sh\nexec python3 {here}/uncarried.py {item} {carried}\n")
    wrapper.close()
    os.chmod(wrapper.name, os.stat(wrapper.name).st_mode | stat.S_IEXEC)
    got = {v["id"]: v for v in grade(wrapper.name)}[item]
    cases.append({"item": item, "role": "uncarried", "verdict": got["verdict"]})
    if got["verdict"] != "fail":
        failures.append(f"{item} uncarried should fail: {got['evidence']}")

print(json.dumps(cases, indent=1))
if failures:
    print("CALIBRATION FAILED; nothing sealed:\n  " + "\n  ".join(failures), file=sys.stderr)
    sys.exit(1)

files = ["grade.py", "seam.py"]
digest = hashlib.sha256(b"".join(open(os.path.join(here, f), "rb").read() for f in files)).hexdigest()
target = os.path.join(sealed_root, digest)
os.makedirs(target, exist_ok=True)
for f in files:
    shutil.copy2(os.path.join(here, f), target)
manifest = {"grader_digest": "sha256:" + digest, "items": sorted(carried_verdicts),
            "calibration": cases, "status": "sealed"}
json.dump(manifest, open(os.path.join(target, "manifest.json"), "w"), indent=1)
print(f"sealed grader sha256:{digest} covering {len(carried_verdicts)} items at {target}")
