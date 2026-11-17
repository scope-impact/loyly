#!/usr/bin/env python3
"""Prepare or audit the explicitly approved cycle-23 base for a slice-06 repair."""

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

EXPECTED = "947d49609d24e2325fcb027a695b03c3b68c2b9cd9337a3401d39cf58df5231a"
SKIP_DIRS = {".cpcache", "target", "classes", "work", "vendor", "node_modules", "tmp"}
SKIP_FILES = {"PROVENANCE.md"}


def files(root):
    result = {}
    for path in root.rglob("*"):
        rel = path.relative_to(root)
        if any(part in SKIP_DIRS for part in rel.parts):
            continue
        if not path.is_file() or path.name in SKIP_FILES or path.name.endswith((".sqlite", ".log")):
            continue
        result[str(rel)] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def digest(rows):
    data = "".join(sorted(f"{value}  {name}\n" for name, value in rows.items()))
    return hashlib.sha256(data.encode()).hexdigest()


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def main():
    require(len(sys.argv) == 3 and sys.argv[1] in {"prepare", "check"},
            "usage: repair-base-06.py prepare|check FENCED_ROOM")
    mode, room = sys.argv[1], Path(sys.argv[2]).resolve()
    repo = Path(__file__).resolve().parents[2]
    source = repo / "harness-impl/06-cycle-runner"
    base = room / "repair-input/accepted-base"
    candidate = room / "harness-impl/06-cycle-runner"
    require(room.is_dir() and (room / ".git").is_dir(), "repair room is not a Git checkout")
    count = subprocess.check_output(["git", "-C", str(room), "rev-list", "--count", "--all"], text=True).strip()
    require(count == "1", f"repair room must have one fenced commit, got {count}")

    if mode == "prepare":
        require(not base.exists() and not candidate.exists(), "repair room already has a base or candidate")
        source_rows = files(source)
        require(len(source_rows) == 39 and digest(source_rows) == EXPECTED,
                "accepted source does not match the approved cycle-23 digest")
        base.parent.mkdir(parents=True)
        shutil.copytree(source, base, ignore=shutil.ignore_patterns(
            ".cpcache", "target", "classes", "work", "vendor", "node_modules", "tmp",
            "PROVENANCE.md", "*.sqlite", "*.log"), symlinks=True)
        require(files(base) == source_rows, "base copy differs from accepted source")
        for path in sorted(base.rglob("*"), reverse=True):
            path.chmod(path.stat().st_mode & ~0o222)
        base.chmod(base.stat().st_mode & ~0o222)
        print(json.dumps({"base": str(base), "files": len(source_rows), "digest": EXPECTED,
                          "selection": ["06-047", "06-066"]}, sort_keys=True))
        return

    require(base.is_dir() and candidate.is_dir(), "repair base or candidate is absent")
    base_rows, candidate_rows = files(base), files(candidate)
    require(len(base_rows) == 39 and digest(base_rows) == EXPECTED,
            "repair base changed after preparation")
    status = subprocess.check_output(["git", "-C", str(room), "status", "--porcelain"], text=True)
    for line in status.splitlines():
        require(line.startswith("?? repair-input/") or line.startswith("?? harness-impl/"),
                f"builder wrote outside its scope: {line}")
    changed = sorted(name for name in base_rows.keys() | candidate_rows.keys()
                     if base_rows.get(name) != candidate_rows.get(name))
    print(json.dumps({"base_digest": EXPECTED, "candidate_digest": digest(candidate_rows),
                      "changed_paths": changed}, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
