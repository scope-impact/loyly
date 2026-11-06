#!/usr/bin/env python3
"""Calibration fixture for grade_specification.exs: a minimal stamper over the specification
seam (.pi/spec/specification/seam.md), written from the spec alone. Not the product: it keeps
its ledger as JSON in the LOYLY_GRAIN_STORE file, not in Grain, and runs no ORC workflow.

MUTANT=<name> breaks one behaviour of the scenarios (calibrate.sh lists them and the item
each must fail); unset, it is the reference that carries every scenario."""
import json, os, re, sys

MUTANT = os.environ.get("MUTANT", "")
STORE = os.environ.get("LOYLY_GRAIN_STORE") or os.path.join(os.getcwd(), "stub-store.json")
ITEM = re.compile(r"@item-(\d+)-(\d+)")
HEADING = re.compile(r"^\s*(?:Scenario|Scenario Outline" + ("|Scenario Template" if MUTANT == "template-yes" else "") + r"):(.*)$")


def load():
    try:
        with open(STORE) as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save(db):
    with open(STORE, "w") as f:
        json.dump(db, f)


def title_of(rest):
    t = rest.lstrip() if MUTANT == "no-trailing-trim" else rest.strip()
    if t == "" and MUTANT != "trim-whitespace-title":
        return rest[-1]
    return t


def read(text, ledger=None):
    """[(line, title, tag)], [empty lines]: the contract ScenarioReading."""
    scen, empty, run = [], [], []
    for n, line in enumerate(text.split("\n"), 1):
        line = line.rstrip("\r")
        if line.strip().startswith("@"):
            run.append(line)
            continue
        m = HEADING.match(line)
        if m:
            rest = m.group(1)
            if rest == "" or (MUTANT == "trim-whitespace-title" and rest.strip() == ""):
                empty.append(n)
            else:
                tags = ITEM.findall(" ".join(run))
                tag = "%s-%s" % tags[-1] if tags else None
                if MUTANT == "any-tag-stamps" and run and not tag:
                    tag = "other"
                t = title_of(rest)
                if MUTANT == "wording-identity" and tag and ledger is not None:
                    known = {i["item-id"]: i["title"] for i in ledger.get("items", [])}
                    if known.get(tag) != t:
                        tag = None
                scen.append((n, t, tag))
            run = []
        elif MUTANT == "pending-tag" and line.strip() == "":
            continue
        else:
            run = []
    return scen, empty


def refusal(kind, msg):
    return {"code": -32000, "message": msg, "data": {"refusal": kind}}


def stamp(p):
    slice_, source = p.get("slice"), p.get("source")
    unstamped, empty = p.get("unstamped"), p.get("empty-title-lines")
    slice_ok = isinstance(slice_, str) and (MUTANT == "notes-slice" or re.fullmatch(r"[0-9]+", slice_))
    if not (slice_ok and isinstance(source, str) and isinstance(unstamped, list) and isinstance(empty, list)):
        return None, {"code": -32602, "message": "invalid params"}
    path = source if os.path.isabs(source) else os.path.join(os.getcwd(), source)
    db = load()
    ledger = db.get(slice_)
    if not os.path.isfile(path):
        if MUTANT == "no-spec-fully-stamped" or (MUTANT == "missing-after-ledger" and ledger):
            return {"events": []}, None
        return None, refusal("no-spec-found", "no spec found: " + source)
    d = os.path.basename(os.path.dirname(path))
    if MUTANT == "slice-int":
        of = d.split("-")[0].lstrip("0")
    else:
        of = d.split("-")[0] if (MUTANT == "notes-slice" or d[:1].isdigit()) else None
    if of != slice_:
        return None, refusal("not-this-slice", "%s is slice %s, not %s" % (source, of, slice_))
    with open(path) as f:
        text = f.read()
    scen, empties = read(text, ledger)
    mine = [{"line": n, "title": t} for n, t, tag in scen if tag is None]
    if MUTANT != "no-offer-check" and unstamped != mine:
        return None, refusal("unstamped-mismatch", "the scenarios offered are not the file's unstamped ones")
    if MUTANT != "no-empty-check" and empty != empties:
        return None, refusal("empty-title-mismatch", "the empty-title headings offered are not the file's")
    if MUTANT == "reuse-deleted":
        nums = [int(b) for a, b in ITEM.findall(text) if a == slice_]
        first = max(nums) + 1 if nums else 1
    else:
        first = ledger["next"] if ledger else 1
    numbers = list(range(first, first + len(mine)))
    if MUTANT == "reverse-order":
        numbers.reverse()
    at = "2026-11-07T00:00:00Z"
    events = []
    if empties and MUTANT != "no-report":
        events.append({"type": "specification/empty-title-headings-reported", "slice": slice_,
                       "source": source, "lines": empties, "reported-at": at})
    ledger = ledger or {"next": 1, "items": []}
    lines = text.split("\n")
    for s, num in sorted(zip(mine, numbers), key=lambda x: -x[0]["line"]):
        tag = "@item-%s-%03d" % (slice_, num)
        i = s["line"] - 1
        indent = re.match(r"\s*", lines[i]).group(0)
        if MUTANT == "no-file-write":
            continue
        if MUTANT == "drop-tags" and i > 0 and lines[i - 1].strip().startswith("@"):
            lines[i - 1] = indent + tag
        else:
            lines.insert(i, indent + tag)
    for s, num in zip(mine, numbers):
        iid = "%s-%03d" % (slice_, num)
        ledger["items"].append({"item-id": iid, "source": source, "line": s["line"], "title": s["title"]})
        events.append({"type": "specification/scenario-stamped", "item-id": iid, "slice": slice_,
                       "source": source, "line": s["line"], "title": s["title"], "stamped-at": at})
    ledger["next"] = first + len(mine) + (1 if MUTANT == "idempotence-broken" and not mine else 0)
    if mine:
        with open(path, "w") as f:
            f.write("\n".join(lines))
    db[slice_] = ledger
    save(db)
    return {"events": events}, None


def stamped_items(p):
    db = load()
    want = p.get("slice")
    slices = [{"slice": k, "next-number": v["next"], "items": v["items"]}
              for k, v in sorted(db.items()) if want is None or k == want]
    return {"slices": slices}, None


for raw in sys.stdin:
    try:
        req = json.loads(raw)
    except ValueError:
        print(json.dumps({"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "parse error"}}), flush=True)
        continue
    method, params = req.get("method"), req.get("params") or {}
    handler = {"specification/stamp-slice": stamp, "specification/stamped-items": stamped_items}.get(method)
    if handler:
        result, error = handler(params)
    else:
        result, error = None, {"code": -32601, "message": "unknown method"}
    resp = {"jsonrpc": "2.0", "id": req.get("id")}
    resp.update({"error": error} if error else {"result": result})
    print(json.dumps(resp), flush=True)
    if MUTANT == "exit-after-one":
        sys.exit(0)
