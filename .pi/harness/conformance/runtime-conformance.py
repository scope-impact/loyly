#!/usr/bin/env python3
import argparse
import json
from pathlib import Path


def validate(contract, evidence_root):
    path = evidence_root / contract["relativePath"]
    try:
        records = [json.loads(line) for line in path.read_text().splitlines() if line.strip()]
    except Exception as error:
        return False, f"runtime evidence unavailable: {error}"
    discriminator = contract["discriminator"]["field"]
    failures = []
    by_kind = {kind: [record for record in records if record.get(discriminator) == kind]
               for kind in contract["discriminator"]["values"]}
    for kind, rule in contract["records"].items():
        selected = by_kind.get(kind, [])
        exact = rule.get("count", {}).get("exactly")
        if exact is not None and len(selected) != exact:
            failures.append(f"{kind} count {len(selected)} != {exact}")
        for field in rule.get("required", []):
            if any(field not in record for record in selected):
                failures.append(f"{kind} missing {field}")
        for field, expected in rule.get("equals", {}).items():
            if any(record.get(field) != expected for record in selected):
                failures.append(f"{kind} {field} differs")
        for field, expected in rule.get("fieldValues", {}).items():
            if sorted(record.get(field) for record in selected) != sorted(expected):
                failures.append(f"{kind} {field} values differ")
    for field in contract.get("forbidden", []):
        if any(field in record for record in records):
            failures.append(f"forbidden field {field}")
    correlation = contract.get("correlation")
    if correlation:
        anchors = by_kind.get(correlation["anchorKind"], [])
        if len(anchors) == 1:
            for kind in correlation["memberKinds"]:
                for record in by_kind.get(kind, []):
                    if any(record.get(field) != anchors[0].get(field) for field in correlation["fields"]):
                        failures.append(f"{kind} correlation differs")
    return not failures, contract.get("successEvidence") if not failures else "; ".join(failures)


parser = argparse.ArgumentParser()
parser.add_argument("--evidence-root", required=True)
parser.add_argument("--item", action="append", default=[])
parser.add_argument("contract")
args = parser.parse_args()
document = json.loads(Path(args.contract).read_text())
contracts = {value["itemId"]: value for value in document.get("contracts", [])}
results = []
passed = True
for item in args.item:
    contract = contracts.get(item)
    if contract is None:
        ok, evidence = False, "no runtime evidence contract applies to this item"
    else:
        ok, evidence = validate(contract, Path(args.evidence_root))
    passed = passed and ok
    results.append({"itemId": item.removeprefix("@item-"), "verdict": "pass" if ok else "fail", "evidence": evidence})
print(json.dumps(results))
raise SystemExit(0 if passed else 1)
