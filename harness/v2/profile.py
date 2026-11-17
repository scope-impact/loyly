#!/usr/bin/env python3
"""Capture a black-box grading profile without reading candidate source.

Use one unique work directory per checklist case. Capture `before`, optional
`active` samples, and `after` (before the grader cleans up its processes).
The report preserves observations even when a cleanup check fails.
"""

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time


def unix_nano(iso):
    instant = dt.datetime.fromisoformat(iso)
    epoch = dt.datetime(1970, 1, 1, tzinfo=dt.timezone.utc)
    delta = instant - epoch
    return str((delta.days * 86400 + delta.seconds) * 1_000_000_000
               + delta.microseconds * 1000)


def attribute(key, value):
    if isinstance(value, bool):
        encoded = {"boolValue": value}
    elif isinstance(value, int):
        encoded = {"intValue": str(value)}
    elif isinstance(value, float):
        encoded = {"doubleValue": value}
    else:
        encoded = {"stringValue": str(value)}
    return {"key": key, "value": encoded}


def attributes(values):
    return [attribute(key, value) for key, value in values.items() if value is not None]


def span_id(trace_id, name):
    return hashlib.sha256((trace_id + name).encode()).hexdigest()[:16]


def otlp(result, events):
    captures = [e for e in events if e.get("type") == "capture"]
    times = [unix_nano(e["at_utc"]) for e in events if e.get("at_utc")]
    start = min(times, default=unix_nano(dt.datetime.now(dt.timezone.utc).isoformat()))
    end = max(times, default=start)
    parent = os.environ.get("TRACEPARENT", "").split("-")
    if (len(parent) == 4 and len(parent[1]) == 32 and len(parent[2]) == 16
            and re.fullmatch(r"[0-9a-f]{32}", parent[1])
            and re.fullmatch(r"[0-9a-f]{16}", parent[2])):
        trace_id, parent_id = parent[1], parent[2]
    else:
        trace_id = hashlib.sha256((result["events_sha256"]
                                   + result["checklist_sha256"]).encode()).hexdigest()[:32]
        parent_id = None
    root_id = span_id(trace_id, "grading")
    root = dict(traceId=trace_id, spanId=root_id, name="loyly.grading", kind=1,
                startTimeUnixNano=start, endTimeUnixNano=end,
                attributes=attributes({"loyly.profile.status": result["status"],
                                       "loyly.checklist.items": result["checklist_items"],
                                       "loyly.grader.instruction.sha256":
                                       result["grader_instruction_sha256"]}),
                status={"code": 1 if result["status"] == "clean" else 2})
    if parent_id:
        root["parentSpanId"] = parent_id
    spans = [root]
    memory_points, vm_points, thread_points = [], [], []
    for case in result["cases"]:
        case_events = [e for e in events if e.get("case") == case["id"]]
        samples = [e for e in case_events if e.get("type") == "capture"]
        first = next((e for e in samples if e["phase"] == "before"), None)
        last = next((e for e in reversed(samples) if e["phase"] == "after"), None)
        case_times = [unix_nano(e["at_utc"]) for e in case_events if e.get("at_utc")]
        case_start = min(case_times, default=start)
        case_end = max(case_times, default=end)
        case_span = dict(traceId=trace_id,
                         spanId=span_id(trace_id, case["id"]), parentSpanId=root_id,
                         name="loyly.grade.item", kind=1,
                         startTimeUnixNano=case_start, endTimeUnixNano=case_end,
                         attributes=attributes({"loyly.item.id": case["id"],
                                                "loyly.profile.status": case["status"],
                                                "loyly.model.requests": case["model_requests"],
                                                "loyly.grain.profiles": case["grain_profiles"],
                                                "loyly.vm.qemu.peak_observed":
                                                case["peak_observed_qemu"]}),
                         status={"code": 1 if case["status"] == "clean" else 2},
                         events=[])
        for event in case_events:
            event_type = event["type"]
            event_time = unix_nano(event["at_utc"]) if event.get("at_utc") else case_end
            if event_type == "capture":
                processes_now = event["processes"]
                qemu = sum(p["kind"] == "qemu" for p in processes_now)
                case_span["events"].append(dict(timeUnixNano=event_time,
                                                name="loyly.profile.capture",
                                                attributes=attributes({
                                                    "loyly.profile.phase": event["phase"],
                                                    "loyly.vm.qemu.count": qemu,
                                                    "loyly.process.count": len(processes_now)})))
                vm_points.append(dict(timeUnixNano=event_time, asInt=str(qemu),
                                      attributes=attributes({"loyly.item.id": case["id"],
                                                             "loyly.profile.phase": event["phase"]})))
                for process in processes_now:
                    memory_points.append(dict(timeUnixNano=event_time,
                                              asInt=str(process["rss_kib"] * 1024),
                                              attributes=attributes({"loyly.item.id": case["id"],
                                                                     "loyly.profile.phase": event["phase"],
                                                                     "loyly.process.kind": process["kind"],
                                                                     "process.pid": process["pid"],
                                                                     "process.parent_pid": process["ppid"],
                                                                     "process.group_leader.pid": process["pgid"]})))
            elif event_type == "store":
                case_span["events"].append(dict(timeUnixNano=event_time,
                                                name="loyly.profile.grain",
                                                attributes=attributes({
                                                    "loyly.grain.events": event["event_count"],
                                                    "loyly.orc.traces": event["trace_count"],
                                                    "loyly.orc.nodes": event["node_count"],
                                                    "loyly.judge.scores": event["judge_scores"],
                                                    "loyly.grain.snapshot.sha256":
                                                    event["store_sha256"]})))
                for trace in event.get("traces", []):
                    case_span["events"].append(dict(timeUnixNano=event_time,
                                                    name="loyly.orc.trace",
                                                    attributes=attributes({
                                                        "loyly.orc.trace.id": trace["trace-id"],
                                                        "loyly.orc.trace.root_id":
                                                        trace["root-trace-id"],
                                                        "loyly.orc.trace.parent_id":
                                                        trace["parent-trace-id"],
                                                        "loyly.orc.sheet": trace["sheet"],
                                                        "loyly.orc.status": trace["status"],
                                                        "loyly.orc.duration.ms":
                                                        trace["duration-ms"]})))
                for node in event.get("by_node", []):
                    case_span["events"].append(dict(timeUnixNano=event_time,
                                                    name="loyly.orc.node.summary",
                                                    attributes=attributes({
                                                        "loyly.orc.sheet": node["sheet"],
                                                        "loyly.orc.node": node["name"],
                                                        "loyly.orc.executions": node["executions"],
                                                        "loyly.orc.duration.ms": node["duration_ms"],
                                                        "loyly.orc.total_tokens": node["total_tokens"],
                                                        "loyly.orc.instruction.sha256":
                                                        node["instruction_sha256"]})))
            elif event_type == "jvm":
                thread_points.append(dict(timeUnixNano=event_time,
                                          asInt=str(sum(event["thread_states"].values())),
                                          attributes=attributes({"loyly.item.id": case["id"],
                                                                 "process.pid": event["pid"]})))
                case_span["events"].append(dict(timeUnixNano=event_time,
                                                name="loyly.profile.jvm",
                                                attributes=attributes({
                                                    "process.pid": event["pid"],
                                                    "loyly.jvm.threads":
                                                    sum(event["thread_states"].values())})))
            elif event_type == "jfr":
                case_span["events"].append(dict(timeUnixNano=event_time,
                                                name="loyly.profile.jfr",
                                                attributes=attributes({
                                                    "process.pid": event["pid"],
                                                    "loyly.jfr.bytes": event["bytes"],
                                                    "loyly.jfr.sha256":
                                                    event["recording_sha256"]})))
            elif event_type == "mark":
                case_span["events"].append(dict(timeUnixNano=event_time,
                                                name="loyly.profile.mark",
                                                attributes=attributes({
                                                    "loyly.mark.name": event["name"],
                                                    "loyly.mark.value": event["value"]})))
        spans.append(case_span)
    resource = {"attributes": attributes({"service.name": "loyly-grader",
                                             "service.version": "profile-v1"})}
    traces = {"resourceSpans": [{"resource": resource,
                                 "scopeSpans": [{"scope": {"name": "loyly.harness.profile",
                                                             "version": "1"},
                                                 "spans": spans}]}]}
    instruments = []
    if memory_points:
        instruments.append({"name": "process.memory.usage", "unit": "By",
                            "sum": {"aggregationTemporality": 2,
                                    "isMonotonic": False, "dataPoints": memory_points}})
    if vm_points:
        instruments.append({"name": "loyly.vm.running", "unit": "{vm}",
                            "gauge": {"dataPoints": vm_points}})
    if thread_points:
        instruments.append({"name": "process.thread.count", "unit": "{thread}",
                            "sum": {"aggregationTemporality": 2,
                                    "isMonotonic": False, "dataPoints": thread_points}})
    metrics = {"resourceMetrics": [{"resource": resource,
                                     "scopeMetrics": [{"scope": {"name": "loyly.harness.profile",
                                                                  "version": "1"},
                                                       "metrics": instruments}]}]} if instruments else {"resourceMetrics": []}
    return traces, metrics


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def processes(room, runner_pid, known_pids=None):
    output = subprocess.check_output(
        ["ps", "-axo", "pid=,ppid=,pgid=,rss=,stat=,args="], text=True
    )
    rows = []
    for line in output.splitlines():
        fields = line.strip().split(None, 5)
        if len(fields) != 6:
            continue
        try:
            pid, ppid, pgid, rss = map(int, fields[:4])
        except ValueError:
            continue
        rows.append((pid, ppid, pgid, rss, fields[4], fields[5]))
    owned = {runner_pid} if runner_pid else set()
    while True:
        extended = owned | {r[0] for r in rows if r[1] in owned}
        if extended == owned:
            break
        owned = extended
    found = []
    for pid, ppid, pgid, rss, state, command in rows:
        qemu = Path(command.split()[0]).name.startswith("qemu-system-")
        gondolin = "gondolin bash" in command
        by_room = room in command and (qemu or gondolin)
        by_runner = (pid in owned or (known_pids or {}).get(pid) == pgid) and pid != os.getpid()
        if not (by_room or by_runner):
            continue
        kind = "qemu" if qemu else "gondolin" if gondolin else "runner-tree"
        found.append(dict(pid=pid, ppid=ppid, pgid=pgid, rss_kib=rss,
                          state=state, kind=kind,
                          jvm=Path(command.split()[0]).name == "java"))
    return sorted(found, key=lambda p: p["pid"])


def append_line(path, entry):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a") as file:
        file.write(json.dumps(entry, sort_keys=True, separators=(",", ":")) + "\n")


def capture(args, found=None):
    room = str(Path(args.room).resolve(strict=True))
    known = {}
    phases = []
    if Path(args.events).is_file():
        for line in Path(args.events).read_text().splitlines():
            earlier = json.loads(line)
            if earlier.get("type") == "capture" and earlier.get("case") == args.case:
                phases.append(earlier["phase"])
                known.update({p["pid"]: p["pgid"] for p in earlier["processes"]})
    expected = ("before" if not phases else
                "after" if phases[-1] == "before" else
                "active or after" if phases[-1] == "active" else "closed")
    if (args.phase != "before" and not phases) or (args.phase == "before" and phases) \
            or (phases and phases[-1] == "after"):
        raise ValueError(f"{args.case}: capture {args.phase} refused; expected {expected}")
    entry = dict(type="capture", case=args.case, phase=args.phase, room=room,
                 runner_pid=args.runner_pid,
                 at_utc=dt.datetime.now(dt.timezone.utc).isoformat(),
                 monotonic_ns=time.monotonic_ns(),
                 processes=found if found is not None else processes(room, args.runner_pid, known))
    append_line(args.events, entry)
    counts = {kind: sum(p["kind"] == kind for p in entry["processes"])
              for kind in ("qemu", "gondolin", "runner-tree")}
    print(json.dumps(dict(case=args.case, phase=args.phase, counts=counts,
                          jvm_pids=[p["pid"] for p in entry["processes"] if p["jvm"]])))
    return 0


def watch(args):
    if not 1 <= args.seconds <= 30 or not 50 <= args.interval_ms <= 1000:
        raise ValueError("watch requires 1–30 seconds and 50–1000 ms interval")
    room = str(Path(args.room).resolve(strict=True))
    known = {}
    if Path(args.events).is_file():
        for line in Path(args.events).read_text().splitlines():
            prior = json.loads(line)
            if prior.get("type") == "capture" and prior.get("case") == args.case:
                known.update({p["pid"]: p["pgid"] for p in prior["processes"]})
    deadline = time.monotonic() + args.seconds
    while True:
        found = processes(room, args.runner_pid, known)
        known.update({p["pid"]: p["pgid"] for p in found})
        observed = any(p["kind"] == args.kind for p in found)
        if observed or time.monotonic() >= deadline:
            args.phase = "active"
            capture(args, found)
            print(json.dumps(dict(case=args.case, watched=args.kind,
                                  observed=observed)))
            return 0 if observed else 1
        time.sleep(args.interval_ms / 1000)


def mark(args):
    append_line(args.events, dict(type="mark", case=args.case, name=args.name,
                                  at_utc=dt.datetime.now(dt.timezone.utc).isoformat(),
                                  monotonic_ns=time.monotonic_ns(), value=args.value))


def store(args):
    store_path = Path(args.store).resolve(strict=True)
    result = subprocess.run(
        ["clojure", "-M", "-m", "profile-store", str(store_path)],
        cwd=Path(__file__).parent, capture_output=True, text=True, timeout=120,
    )
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or "Grain profile reader failed")
    summary = json.loads(result.stdout)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(summary, indent=2) + "\n")
    by_node = {}
    for node in summary["nodes"]:
        key = (node["sheet"], node["name"], node["instruction-sha256"])
        row = by_node.setdefault(key, dict(sheet=node["sheet"], name=node["name"],
                                           instruction_sha256=node["instruction-sha256"],
                                           executions=0, duration_ms=0,
                                           status_counts={}, total_tokens=0))
        row["executions"] += 1
        row["duration_ms"] += node["duration-ms"] or 0
        status = node["status"]
        row["status_counts"][status] = row["status_counts"].get(status, 0) + 1
        row["total_tokens"] += (node["usage"] or {}).get("total-tokens", 0)
    append_line(args.events, dict(type="store", case=args.case,
                                  at_utc=dt.datetime.now(dt.timezone.utc).isoformat(),
                                  profile_sha256=sha256(output),
                                  store_sha256=summary["store-sha256"],
                                  event_count=summary["event-count"],
                                  trace_count=len(summary["traces"]),
                                  node_count=len(summary["nodes"]),
                                  judge_scores=summary["judge-scores"],
                                  event_counts=summary["event-counts"],
                                  traces=[{k: trace.get(k) for k in
                                           ("trace-id", "root-trace-id", "parent-trace-id",
                                            "sheet", "status", "duration-ms", "node-count")}
                                          for trace in summary["traces"]],
                                  by_node=sorted(by_node.values(),
                                                 key=lambda n: (n["sheet"] or "",
                                                                n["name"] or "")),
                                  prompt_digests=sorted({n["instruction-sha256"]
                                                         for n in summary["nodes"]
                                                         if n["instruction-sha256"]})))
    print(f"Grain profile: {summary['event-count']} events, "
          f"{len(summary['traces'])} traces, {len(summary['nodes'])} nodes")


def jvm(args):
    home = Path(os.environ.get("JAVA_HOME", "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"))
    jcmd = home / "bin" / "jcmd"
    jstat = home / "bin" / "jstat"
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    results = {}
    for name, command in (("version", [str(jcmd), str(args.pid), "VM.version"]),
                          ("heap", [str(jcmd), str(args.pid), "GC.heap_info"]),
                          ("threads", [str(jcmd), str(args.pid), "Thread.print", "-l"]),
                          ("gc", [str(jstat), "-gcutil", str(args.pid), "1", "1"])):
        run = subprocess.run(command, capture_output=True, text=True, timeout=15)
        if run.returncode:
            raise RuntimeError(f"{name} probe failed: {run.stderr.strip()}")
        path = output / f"{name}.txt"
        path.write_text(run.stdout)
        results[name] = sha256(path)
    dump = (output / "threads.txt").read_text()
    states = dict(sorted((state, len(re.findall(r"java\.lang\.Thread\.State: " + state + r"\b", dump)))
                         for state in ("NEW", "RUNNABLE", "BLOCKED", "WAITING",
                                       "TIMED_WAITING", "TERMINATED")))
    entry = dict(type="jvm", case=args.case, pid=args.pid, files_sha256=results,
                 thread_states=states,
                 at_utc=dt.datetime.now(dt.timezone.utc).isoformat())
    append_line(args.events, entry)
    print(json.dumps(dict(case=args.case, pid=args.pid, thread_states=states)))


def jfr(args):
    if not 1 <= args.seconds <= 30:
        raise ValueError("JFR duration must be 1 to 30 seconds")
    home = Path(os.environ.get("JAVA_HOME", "/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"))
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    name = f"loyly_profile_{args.pid}_{time.time_ns()}"
    recording = output / f"{name}.jfr"
    command = [str(home / "bin" / "jcmd"), str(args.pid), "JFR.start",
               f"name={name}", "settings=profile", f"duration={args.seconds}s",
               f"filename={recording}"]
    started = subprocess.run(command, capture_output=True, text=True, timeout=20)
    if started.returncode:
        raise RuntimeError(started.stderr.strip() or started.stdout.strip())
    deadline = time.monotonic() + args.seconds + 10
    while time.monotonic() < deadline and (not recording.is_file() or not recording.stat().st_size):
        time.sleep(.25)
    if not recording.is_file() or not recording.stat().st_size:
        raise RuntimeError("JFR recording did not appear after its bounded duration")
    time.sleep(.5)
    summary = subprocess.run([str(home / "bin" / "jfr"), "summary", str(recording)],
                             capture_output=True, text=True, timeout=20)
    if summary.returncode:
        raise RuntimeError(summary.stderr.strip() or "JFR summary failed")
    (output / "jfr-summary.txt").write_text(summary.stdout)
    append_line(args.events, dict(type="jfr", case=args.case, pid=args.pid,
                                  at_utc=dt.datetime.now(dt.timezone.utc).isoformat(),
                                  seconds=args.seconds, bytes=recording.stat().st_size,
                                  recording_sha256=sha256(recording),
                                  summary_sha256=sha256(output / "jfr-summary.txt")))
    print(f"JFR: {recording.stat().st_size} bytes in {args.seconds}s")


def report(args):
    events_path = Path(args.events)
    events = [json.loads(line) for line in events_path.read_text().splitlines()]
    checklist = [line.strip() for line in Path(args.checklist).read_text().splitlines()
                 if line.strip()]
    verdicts = json.loads(Path(args.verdicts).read_text())
    problems = []
    if [v.get("id") for v in verdicts] != checklist:
        problems.append("verdict order differs from checklist")
    cases = []
    for item in checklist:
        samples = [e for e in events if e.get("type") == "capture"
                   and e.get("case") == item]
        before = [e for e in samples if e["phase"] == "before"]
        after = [e for e in samples if e["phase"] == "after"]
        case_problems = []
        store_entries = [e for e in events if e.get("type") == "store"
                         and e.get("case") == item]
        no_store = [e for e in events if e.get("type") == "mark"
                    and e.get("case") == item and e.get("name") == "no-store"]
        requests = [e for e in events if e.get("type") == "mark"
                    and e.get("case") == item and e.get("name") == "model-requests"]
        if not store_entries and not no_store:
            case_problems.append("missing Grain profile or no-store reason")
        if store_entries and no_store:
            case_problems.append("Grain profile conflicts with no-store reason")
        graded = any(trace.get("sheet") == "cycle-runner/grade.v1"
                     for entry in store_entries for trace in entry.get("traces", []))
        if len(no_store) > 1 or (no_store and not no_store[0]["value"].strip()):
            case_problems.append("expected one nonempty no-store reason")
        if len(requests) != 1 or not requests[0]["value"].isdigit():
            case_problems.append("expected one numeric model-requests mark")
        if len(before) != 1 or len(after) != 1:
            case_problems.append("expected one before and one after capture")
        if before and after and before[0]["monotonic_ns"] >= after[0]["monotonic_ns"]:
            case_problems.append("captures are out of order")
        if before and after and before[0]["room"] != after[0]["room"]:
            case_problems.append("before and after observe different rooms")
        if before and after and any(not (before[0]["monotonic_ns"] < e["monotonic_ns"]
                                         < after[0]["monotonic_ns"])
                                    for e in samples if e["phase"] == "active"):
            case_problems.append("active capture is outside the case boundaries")
        for phase, entries in (("before", before), ("after", after)):
            for sample in entries:
                live = [p for p in sample["processes"]
                        if p["kind"] in (("qemu", "gondolin") if phase == "before"
                                       else ("qemu", "gondolin", "runner-tree"))]
                if live:
                    case_problems.append(f"{phase} has {len(live)} owned processes")
        peak_qemu = max((sum(p["kind"] == "qemu" for p in e["processes"])
                         for e in samples), default=0)
        if graded and peak_qemu == 0:
            case_problems.append("grade.v1 ran but no live Gondolin VM was sampled")
        peak_rss = max((sum(p["rss_kib"] for p in e["processes"])
                        for e in samples), default=0)
        elapsed_ms = (round((after[0]["monotonic_ns"] - before[0]["monotonic_ns"]) / 1e6)
                      if before and after else None)
        cases.append(dict(id=item, status="clean" if not case_problems else "failed",
                          problems=case_problems, samples=len(samples),
                          grain_profiles=len(store_entries),
                          model_requests=int(requests[0]["value"]) if len(requests) == 1
                          and requests[0]["value"].isdigit() else None,
                          peak_observed_qemu=peak_qemu,
                          peak_observed_rss_kib=peak_rss,
                          elapsed_ms=elapsed_ms))
        problems.extend(f"{item}: {p}" for p in case_problems)
    unknown = sorted({e.get("case") for e in events if e.get("type") == "capture"}
                     - set(checklist))
    if unknown:
        problems.append(f"captures for unknown cases: {unknown}")
    live_java = {(e.get("case"), p["pid"])
                 for e in events if e.get("type") == "capture" and e.get("phase") == "active"
                 for p in e["processes"] if p.get("jvm")}
    sampled_java = {(e.get("case"), e.get("pid"))
                    for e in events if e.get("type") == "jvm"}
    if live_java and not (live_java & sampled_java):
        problems.append("live candidate JVM observed but no matching JVM diagnostic sampled")
    result = dict(version=1, status="clean" if not problems else "failed",
                  checklist_sha256=sha256(args.checklist),
                  verdicts_sha256=sha256(args.verdicts),
                  grader_instruction_sha256=sha256(args.instruction),
                  events_sha256=sha256(events_path),
                  checklist_items=len(checklist),
                  verdicts=dict(pass_count=sum(v.get("verdict") == "pass" for v in verdicts),
                                fail_count=sum(v.get("verdict") == "fail" for v in verdicts)),
                  cases=cases, problems=problems,
                  totals=dict(model_requests=sum(c["model_requests"] or 0 for c in cases),
                              grain_events=sum(e["event_count"] for e in events
                                               if e.get("type") == "store"),
                              orc_traces=sum(e["trace_count"] for e in events
                                             if e.get("type") == "store"),
                              orc_nodes=sum(e["node_count"] for e in events
                                            if e.get("type") == "store"),
                              judge_scores=sum(e["judge_scores"] for e in events
                                               if e.get("type") == "store")),
                  stores=[e for e in events if e.get("type") == "store"],
                  jvm=[e for e in events if e.get("type") == "jvm"],
                  jfr=[e for e in events if e.get("type") == "jfr"],
                  marks=[e for e in events if e.get("type") == "mark"])
    trace_payload, metric_payload = otlp(result, events)
    output = Path(args.output)
    traces_path = output.with_name("profile.traces.otlp.json")
    metrics_path = output.with_name("profile.metrics.otlp.json")
    traces_path.write_text(json.dumps(trace_payload, indent=2) + "\n")
    metrics_path.write_text(json.dumps(metric_payload, indent=2) + "\n")
    result["otlp_traces_sha256"] = sha256(traces_path)
    result["otlp_metrics_sha256"] = sha256(metrics_path)
    result["otel_trace_id"] = trace_payload["resourceSpans"][0]["scopeSpans"][0]["spans"][0]["traceId"]
    output.write_text(json.dumps(result, indent=2) + "\n")
    print(f"profile {result['status']}: {len(cases)} cases, {len(problems)} problems")
    return 0 if not problems else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    cap = sub.add_parser("capture")
    cap.add_argument("--events", required=True)
    cap.add_argument("--case", required=True)
    cap.add_argument("--phase", choices=("before", "active", "after"), required=True)
    cap.add_argument("--room", required=True)
    cap.add_argument("--runner-pid", type=int)
    cap.set_defaults(func=capture)
    watcher = sub.add_parser("watch")
    watcher.add_argument("--events", required=True)
    watcher.add_argument("--case", required=True)
    watcher.add_argument("--room", required=True)
    watcher.add_argument("--runner-pid", type=int, required=True)
    watcher.add_argument("--kind", choices=("qemu", "gondolin"), default="qemu")
    watcher.add_argument("--seconds", type=int, default=10)
    watcher.add_argument("--interval-ms", type=int, default=100)
    watcher.set_defaults(func=watch)
    marker = sub.add_parser("mark")
    marker.add_argument("--events", required=True)
    marker.add_argument("--case", required=True)
    marker.add_argument("--name", choices=("run-id", "trace-id", "model-requests",
                                            "grain-events", "stage", "no-store"), required=True)
    marker.add_argument("--value", default="")
    marker.set_defaults(func=mark)
    grain = sub.add_parser("store")
    grain.add_argument("--events", required=True)
    grain.add_argument("--case", required=True)
    grain.add_argument("--store", required=True)
    grain.add_argument("--output", required=True)
    grain.set_defaults(func=store)
    java = sub.add_parser("jvm")
    java.add_argument("--events", required=True)
    java.add_argument("--case", required=True)
    java.add_argument("--pid", type=int, required=True)
    java.add_argument("--output", required=True)
    java.set_defaults(func=jvm)
    flight = sub.add_parser("jfr")
    flight.add_argument("--events", required=True)
    flight.add_argument("--case", required=True)
    flight.add_argument("--pid", type=int, required=True)
    flight.add_argument("--seconds", type=int, default=10)
    flight.add_argument("--output", required=True)
    flight.set_defaults(func=jfr)
    rep = sub.add_parser("report")
    rep.add_argument("--events", required=True)
    rep.add_argument("--checklist", required=True)
    rep.add_argument("--verdicts", required=True)
    rep.add_argument("--instruction", required=True)
    rep.add_argument("--output", required=True)
    rep.set_defaults(func=report)
    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
