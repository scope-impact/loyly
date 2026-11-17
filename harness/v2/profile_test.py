#!/usr/bin/env python3
"""Focused checks for the grading profile's ownership and cleanup verdict."""

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace


SPEC = importlib.util.spec_from_file_location("profile", Path(__file__).with_name("profile.py"))
profile = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(profile)


class ProfileTest(unittest.TestCase):
    def test_watch_records_live_qemu_during_case(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            events = root / "events.jsonl"
            with patch.object(profile.subprocess, "check_output", return_value=""), \
                    patch("builtins.print"):
                profile.capture(SimpleNamespace(events=events, case="06-066",
                                                phase="before", room=root, runner_pid=None))
            vm = dict(pid=202, ppid=201, pgid=201, rss_kib=500,
                      state="S", kind="qemu", jvm=False)
            args = SimpleNamespace(events=events, case="06-066", room=root,
                                   runner_pid=201, kind="qemu", seconds=1,
                                   interval_ms=50)
            with patch.object(profile, "processes", side_effect=[[], [vm]]), \
                    patch("builtins.print"):
                self.assertEqual(profile.watch(args), 0)
            captures = [json.loads(line) for line in events.read_text().splitlines()]
            self.assertEqual([e["phase"] for e in captures], ["before", "active"])
            self.assertEqual(captures[-1]["processes"], [vm])

    def test_grade_trace_without_live_vm_fails_profile(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            (root / "checklist.txt").write_text("06-066\n")
            (root / "verdicts.json").write_text('[{"id":"06-066","verdict":"pass"}]')
            (root / "instruction.md").write_text("grader")
            events = root / "events.jsonl"
            base = dict(type="capture", case="06-066", room=str(root),
                        at_utc="2026-11-15T00:00:00+00:00", processes=[])
            profile.append_line(events, dict(base, phase="before", monotonic_ns=1))
            profile.append_line(events, dict(base, phase="after", monotonic_ns=2))
            profile.append_line(events, dict(type="mark", case="06-066",
                                             name="model-requests", value="1"))
            profile.append_line(events, dict(type="store", case="06-066",
                                             at_utc="2026-11-15T00:00:00+00:00",
                                             event_count=1, trace_count=1, node_count=0,
                                             judge_scores=0, store_sha256="abc",
                                             traces=[{"trace-id": "t", "root-trace-id": "t",
                                                      "parent-trace-id": None,
                                                      "sheet": "cycle-runner/grade.v1",
                                                      "status": "success", "duration-ms": 1}]))
            args = SimpleNamespace(events=events, checklist=root / "checklist.txt",
                                   verdicts=root / "verdicts.json",
                                   instruction=root / "instruction.md",
                                   output=root / "profile.json")
            self.assertEqual(profile.report(args), 1)
            result = json.loads((root / "profile.json").read_text())
            self.assertIn("no live Gondolin VM", result["problems"][0])

    def test_capture_refuses_active_after_after_without_changing_evidence(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            events = root / "events.jsonl"
            with patch.object(profile.subprocess, "check_output", return_value=""):
                for phase in ("before", "active", "after"):
                    profile.capture(SimpleNamespace(events=events, case="06-073",
                                                    phase=phase, room=root, runner_pid=None))
                original = events.read_bytes()
                with self.assertRaisesRegex(ValueError, "expected closed"):
                    profile.capture(SimpleNamespace(events=events, case="06-073",
                                                    phase="active", room=root, runner_pid=None))
            self.assertEqual(events.read_bytes(), original)

    def test_capture_exposes_owned_java_pid_for_jvm_probe(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            ps = """201 1 201 100 S /bin/sh ./start.sh
202 201 201 200 S /usr/bin/java -cp app.jar Main
203 1 203 300 S /usr/bin/java -cp other.jar Main
"""
            with patch.object(profile.subprocess, "check_output", return_value=ps), \
                    patch("builtins.print") as emitted:
                profile.capture(SimpleNamespace(events=root / "events.jsonl", case="06-003",
                                                phase="before", room=root, runner_pid=201))
            self.assertEqual(json.loads(emitted.call_args.args[0])["jvm_pids"], [202])

    def test_orphaned_qemu_stays_owned_by_mount(self):
        ps = """101 1 101 900 S qemu-system-aarch64 -append sandboxfs.bind=/room/case,/etc/gondolin
102 1 102 900 S qemu-system-aarch64 -append sandboxfs.bind=/other/case
103 1 103 300 S node /usr/bin/gondolin bash --mount-hostfs /room/case:/room/case
"""
        with patch.object(profile.subprocess, "check_output", return_value=ps):
            found = profile.processes("/room/case", None)
        self.assertEqual([(p["pid"], p["kind"]) for p in found],
                         [(101, "qemu"), (103, "gondolin")])

    def test_reparented_runner_child_stays_owned_by_pid_and_group(self):
        ps = """201 1 200 123 S java -cp app.jar Main
202 1 202 123 S java -cp other.jar Main
"""
        with patch.object(profile.subprocess, "check_output", return_value=ps):
            found = profile.processes("/room/case", None, {201: 200, 202: 200})
        self.assertEqual([p["pid"] for p in found], [201])

    def test_report_preserves_leak_without_changing_verdict(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            (root / "checklist.txt").write_text("06-001\n")
            (root / "verdicts.json").write_text('[{"id":"06-001","verdict":"pass"}]')
            (root / "instruction.md").write_text("grader")
            events = root / "events.jsonl"
            base = dict(type="capture", case="06-001", room=str(root),
                        at_utc="2026-11-15T00:00:00+00:00")
            profile.append_line(events, dict(base, phase="before", monotonic_ns=1,
                                             processes=[]))
            profile.append_line(events, dict(base, phase="after", monotonic_ns=2,
                                             processes=[dict(pid=101, ppid=1, pgid=100,
                                                             rss_kib=900, state="S", kind="qemu")]))
            profile.append_line(events, dict(type="mark", case="06-001", name="no-store",
                                             value="stopped before start"))
            profile.append_line(events, dict(type="mark", case="06-001", name="model-requests",
                                             value="0"))
            args = type("Args", (), dict(events=events, checklist=root / "checklist.txt",
                                         verdicts=root / "verdicts.json",
                                         instruction=root / "instruction.md",
                                         output=root / "profile.json"))()
            self.assertEqual(profile.report(args), 1)
            result = json.loads((root / "profile.json").read_text())
            self.assertEqual(result["status"], "failed")
            self.assertEqual(result["verdicts"]["pass_count"], 1)
            self.assertIn("after has 1 owned processes", result["problems"][0])

    def test_clean_report(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            (root / "checklist.txt").write_text("06-001\n")
            (root / "verdicts.json").write_text('[{"id":"06-001","verdict":"fail"}]')
            (root / "instruction.md").write_text("grader")
            events = root / "events.jsonl"
            base = dict(type="capture", case="06-001", room=str(root),
                        at_utc="2026-11-15T00:00:00+00:00",
                        processes=[])
            profile.append_line(events, dict(base, phase="before", monotonic_ns=1))
            profile.append_line(events, dict(base, phase="after", monotonic_ns=2000001))
            profile.append_line(events, dict(type="mark", case="06-001", name="no-store",
                                             value="refused before store"))
            profile.append_line(events, dict(type="mark", case="06-001", name="model-requests",
                                             value="0"))
            args = type("Args", (), dict(events=events, checklist=root / "checklist.txt",
                                         verdicts=root / "verdicts.json",
                                         instruction=root / "instruction.md",
                                         output=root / "profile.json"))()
            self.assertEqual(profile.report(args), 0)
            result = json.loads((root / "profile.json").read_text())
            self.assertEqual(result["status"], "clean")
            self.assertEqual(result["cases"][0]["elapsed_ms"], 2)
            traces = json.loads((root / "profile.traces.otlp.json").read_text())
            spans = traces["resourceSpans"][0]["scopeSpans"][0]["spans"]
            self.assertEqual([span["name"] for span in spans],
                             ["loyly.grading", "loyly.grade.item"])
            self.assertEqual(spans[1]["parentSpanId"], spans[0]["spanId"])
            self.assertEqual(len(spans[0]["traceId"]), 32)
            self.assertEqual(len(spans[0]["spanId"]), 16)
            metrics = json.loads((root / "profile.metrics.otlp.json").read_text())
            names = [metric["name"] for metric in
                     metrics["resourceMetrics"][0]["scopeMetrics"][0]["metrics"]]
            self.assertIn("loyly.vm.running", names)

    def test_live_java_requires_matching_jvm_diagnostic(self):
        for sampled_pid, expected in ((None, 1), (999, 1), (202, 0)):
            with self.subTest(sampled_pid=sampled_pid), tempfile.TemporaryDirectory() as root:
                root = Path(root)
                (root / "checklist.txt").write_text("06-001\n")
                (root / "verdicts.json").write_text('[{"id":"06-001","verdict":"pass"}]')
                (root / "instruction.md").write_text("grader")
                events = root / "events.jsonl"
                base = dict(type="capture", case="06-001", room=str(root),
                            at_utc="2026-11-15T00:00:00+00:00")
                profile.append_line(events, dict(base, phase="before", monotonic_ns=1,
                                                 processes=[]))
                profile.append_line(events, dict(base, phase="active", monotonic_ns=2,
                                                 processes=[dict(pid=202, ppid=201, pgid=201,
                                                                 rss_kib=200, state="S",
                                                                 kind="runner-tree", jvm=True)]))
                profile.append_line(events, dict(base, phase="after", monotonic_ns=3,
                                                 processes=[]))
                profile.append_line(events, dict(type="mark", case="06-001", name="no-store",
                                                 value="fixture stopped before store"))
                profile.append_line(events, dict(type="mark", case="06-001",
                                                 name="model-requests", value="0"))
                if sampled_pid:
                    profile.append_line(events, dict(type="jvm", case="06-001",
                                                     pid=sampled_pid, thread_states={}))
                args = SimpleNamespace(events=events, checklist=root / "checklist.txt",
                                       verdicts=root / "verdicts.json",
                                       instruction=root / "instruction.md",
                                       output=root / "profile.json")
                self.assertEqual(profile.report(args), expected)
                result = json.loads((root / "profile.json").read_text())
                self.assertEqual(any("no matching JVM diagnostic" in p
                                     for p in result["problems"]), expected == 1)


if __name__ == "__main__":
    unittest.main()
