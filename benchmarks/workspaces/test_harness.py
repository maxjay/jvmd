#!/usr/bin/env python3
"""Fast standard-library tests for benchmark reporting; no language server required."""
import json, subprocess, sys, tempfile, time, unittest
import threading
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from resources import ProcessMonitor, attribute_samples
from run import Client, first_system_value
from verify import classify, verify
from summarize import stats


class HarnessTest(unittest.TestCase):
    def test_completion_rejects_stale_and_missing_signature(self):
        operation = {"operation": "completion", "symbol": "value0"}
        check = lambda rows: classify(operation, {"result": {"items": rows}})
        self.assertEqual("incomplete", check([]))
        self.assertEqual("wrong", check([{"label": "value0()", "detail": "int"}]))
        self.assertEqual("correct", check([{"label": "value0(int input): int"}]))
        self.assertEqual("stale", check([{"label": "value0(int input)", "detail": "String"}]))

    def test_definition_rejects_wrong_location_and_range(self):
        expected = {
            "uri": "file:///library/Library.java",
            "range": {"start": {"line": 0, "character": 4}, "end": {"line": 0, "character": 10}},
        }
        op = {"operation": "definition", "symbol": "value0", "expected": expected}
        self.assertEqual("correct", classify(op, {"result": [expected]}))
        self.assertEqual(
            "wrong", classify(op, {"result": [dict(expected, uri="file:///installed/Library.java")]})
        )
        self.assertEqual("incomplete", classify(op, {"result": []}))

    def test_references_reject_duplicates_and_partial_sets(self):
        expected = {
            "uri": "file:///Caller.java",
            "range": {"start": {"line": 0, "character": 0}, "end": {"line": 0, "character": 6}},
        }
        op = {"operation": "references", "symbol": "value0", "expected": [expected], "source": "value0(1)"}
        self.assertEqual("correct", classify(op, {"result": [expected]}))
        self.assertEqual("incomplete", classify(op, {"result": []}))
        self.assertEqual("incomplete", classify(op, {"result": [expected, expected]}))
        call = {
            "uri": expected["uri"],
            "range": {"start": expected["range"]["start"], "end": {"line": 0, "character": 9}},
        }
        self.assertEqual("correct", classify(op, {"result": [call]}))

    def test_dependency_source_rejects_wrong_version(self):
        source = "int base0(int input) {}"
        expected = {
            "uri": "jar:file:///repo/offset-1-sources.jar!/external/Offset.java",
            "source": source,
            "binary_name": "offset-1.jar",
        }
        op = {"operation": "dependency_definition", "symbol": "base0", "expected": expected}
        location = {
            "uri": expected["uri"],
            "range": {"start": {"line": 0, "character": 4}, "end": {"line": 0, "character": 9}},
        }
        self.assertEqual("correct", classify(op, {"result": [location]}))
        location["uri"] = location["uri"].replace("offset-1-", "offset-2-")
        self.assertEqual("wrong", classify(op, {"result": [location]}))

    def test_missing_required_invocations_cannot_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            worker = root / "worker"
            worker.mkdir()
            (root / "provenance.json").write_text(
                json.dumps({"runs": 1, "servers": ["jvmd"], "overhead": False, "samples": 1, "warmup": 1})
            )
            (worker / "fixture.json").write_text(
                json.dumps({"identity": "fixture", "operations": [{"operation": "hover", "target": "0"}]})
            )
            (worker / "report.json").write_text(json.dumps({"outcome": "correct", "actions": []}))
            self.assertFalse(verify(root)["complete"])
            self.assertEqual(3, len(verify(root)["workers"][0]["not_executed"]))

    def test_percentile_precision_requires_warm_samples(self):
        self.assertIsNone(stats([1] * 19, "warm")["p95_ms"])
        self.assertIsNone(stats([1] * 100, "first")["p95_ms"])
        self.assertEqual(1, stats([1] * 20, "warm")["p95_ms"])

    def test_samples_choose_innermost_matching_thread_and_leave_others_unassigned(self):
        spans = [
            {
                "queued": False,
                "eventThread": {"javaThreadId": 7},
                "startTime": "2026-01-01T00:00:00Z",
                "duration": "PT1S",
                "durationNanos": 1_000_000_000,
                "stage": "parent",
                "span": 1,
            },
            {
                "queued": False,
                "eventThread": {"javaThreadId": 7},
                "startTime": "2026-01-01T00:00:00.1Z",
                "duration": "PT0.3S",
                "durationNanos": 300_000_000,
                "stage": "child",
                "span": 2,
            },
        ]
        events = [
            {
                "type": "jdk.ExecutionSample",
                "values": {"startTime": "2026-01-01T00:00:00.2Z", "sampledThread": {"javaThreadId": thread}},
            }
            for thread in (7, 8)
        ]
        result = attribute_samples(events, spans)
        self.assertEqual(2, result["total_events"]["jdk.ExecutionSample"])
        self.assertEqual(1, result["assigned_events"]["jdk.ExecutionSample"])
        self.assertEqual({2, None}, {row["span"] for row in result["groups"]})

    def test_jfr_wait_attribution_handles_minute_and_hour_durations(self):
        span = {
            "queued": False,
            "eventThread": {"javaThreadId": 7},
            "startTime": "2026-01-01T00:00:00Z",
            "duration": "PT1H1M0.5S",
            "durationNanos": 3660500000000,
            "stage": "project.resolve",
            "span": 1,
        }
        event = {
            "type": "jdk.ThreadPark",
            "values": {
                "eventThread": {"javaThreadId": 7},
                "startTime": "2026-01-01T00:01:00Z",
                "duration": "PT1M0.689558602S",
            },
        }
        result = attribute_samples([event], [span])
        self.assertEqual(1, result["assigned_events"]["jdk.ThreadPark"])
        self.assertAlmostEqual(60689.558602, result["groups"][0]["observed_wait_ms"])

    def test_optional_system_value_supports_cgroup_v1_and_missing_files(self):
        with tempfile.TemporaryDirectory() as directory:
            missing = Path(directory) / "v2"
            fallback = Path(directory) / "v1"
            fallback.write_text("200000\n")
            self.assertEqual("200000", first_system_value((missing, fallback)))
            self.assertIsNone(first_system_value((missing,)))

    def test_client_reassembles_lsp_partial_results(self):
        client = Client.__new__(Client)
        client.next = 0
        client.responses = {}
        client.notifications = []
        client.failure = None
        client.condition = threading.Condition()

        def send(message):
            token = message["params"]["partialResultToken"]
            client.notifications.extend(
                [
                    {"method": "$/progress", "params": {"token": token, "value": [1, 2]}},
                    {"method": "$/progress", "params": {"token": token, "value": [3]}},
                ]
            )
            client.responses[message["id"]] = {"id": message["id"], "result": []}

        client.send = send
        result, _ = client.call("textDocument/references", {"textDocument": {"uri": "file:///Test.java"}})
        self.assertEqual([1, 2, 3], result)

    @unittest.skipUnless(Path("/proc/self/stat").exists(), "Linux /proc required")
    def test_monitor_includes_descendant_memory(self):
        child = subprocess.Popen(
            [sys.executable, "-c", "import time; x=bytearray(8*1024*1024); time.sleep(.25)"]
        )
        monitor = ProcessMonitor(child.pid, 0.005)
        child.wait()
        values = monitor.close()
        self.assertGreater(values["samples"], 1)
        self.assertGreater(values["peak_rss_bytes"], 8 * 1024 * 1024)
        self.assertGreaterEqual(values["peak_processes"], 1)


if __name__ == "__main__":
    unittest.main()
