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


if __name__ == "__main__":
    unittest.main()
