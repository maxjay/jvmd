import json
from pathlib import Path
import threading
import unittest
from contracts import CONTRACT, diagnostic_decision
from run import Client


class ContractTest(unittest.TestCase):
    def test_shared_vectors(self):
        for v in CONTRACT["diagnosticVectors"]:
            with self.subTest(v["name"]):
                kind, _ = diagnostic_decision(v["mode"], v["params"], v["uri"], v["version"],
                                              v.get("incarnation", 1), v.get("eventIncarnation"))
                self.assertEqual(v["expected"], kind)

    def client(self, params, modes=None):
        c = Client.__new__(Client)
        c.condition = threading.Condition()
        c.failure = None
        c.notifications = [{"method": "textDocument/publishDiagnostics", "params": params}]
        c.diagnostic_modes = modes or {}
        return c

    def test_versionless_is_explicitly_unavailable(self):
        c = self.client({"uri": "file:///A.java", "diagnostics": []})
        result = c.diagnostics("file:///A.java", 2, False, 0, timeout=0)
        self.assertEqual("unavailable", result["_admission"]["status"])
        self.assertNotIn("version", result)

    def test_late_versionless_cannot_admit_new_version(self):
        c = self.client({"uri": "file:///A.java", "diagnostics": []}, {"file:///A.java": "versioned"})
        with self.assertRaises(TimeoutError):
            c.diagnostics("file:///A.java", 2, False, 0, timeout=0)

    def test_missing_process_tree_is_unavailable_not_zero(self):
        from unittest.mock import patch
        from resources import _sample
        with patch("resources._process_tree", return_value=set()):
            result = _sample(123)
        self.assertEqual("unavailable", result["availability"])
        self.assertIsNone(result["rss_bytes"])
        self.assertIsNone(result["cpu_ticks"])

    def test_overlapping_peer_trees_are_counted_once_and_missing_roots_fail(self):
        from unittest.mock import patch
        from resources import _sample
        stat = ['0'] * 22
        stat[11], stat[12], stat[19], stat[21] = '2', '3', '100', '4'
        class File:
            def __init__(self, name): self.name = str(name)
            def read_text(self):
                if self.name.endswith('/stat'): return '1 (java) ' + ' '.join(stat)
                if self.name.endswith('/status'): return 'Threads: 1'
                if self.name.endswith('/io'): return 'read_bytes: 10\nwrite_bytes: 20'
                raise AssertionError(self.name)
            def read_bytes(self): return b'/jdk/bin/java\0'
            @property
            def name(self): return self._name
            @name.setter
            def name(self, value): self._name = value
        # Use actual Path for role classification, mocked files only for /proc.
        real_path = Path
        with patch('resources.Path', side_effect=lambda name: File(name) if str(name).startswith('/proc/') else real_path(name)):
            with patch('resources._process_tree', side_effect=lambda root: {1, 2} if root == 1 else {2}):
                sample = _sample(1, [2])
            self.assertEqual('measured', sample['availability'])
            self.assertEqual(2, sample['processes'])
            self.assertEqual(10, sample['cpu_ticks'])
            self.assertEqual(20, sample['read_bytes'])
            with patch('resources._process_tree', side_effect=lambda root: {1, 2} if root == 1 else set()):
                sample = _sample(1, [3])
            self.assertEqual('unavailable', sample['availability'])
            self.assertIsNone(sample['rss_bytes'])


if __name__ == "__main__":
    unittest.main()
