"""Check contract: correctness gates, advisory deltas and immutable run publication."""
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from check import baseline, compare, publish, render, validate


def result(value=10, complete=True):
    return {"schema": 2, "revision": "a" * 40, "complete": complete, "compatibility": "same",
            "rows": [{"operation": "hover", "state": "warm", "server": "jvmd", "mode": "comparison",
                      "p50_ms": value, "p95_ms": value, "outcomes": {"correct": 20} if complete else {"wrong": 20}}]}


class CheckTest(unittest.TestCase):
    def test_advisory_colours_and_failed_results(self):
        baseline = result()
        for value, colour in [(5, "🟢"), (15, "🔴"), (10, "⚪")]:
            current = compare(result(value), baseline)
            self.assertTrue(current["complete"])
            self.assertIn(colour, render(current, "Passed", "https://github.com"))
        failed = compare(result(1, False), baseline)
        self.assertIsNone(failed["rows"][0]["change_percent"]["p50_ms"])
        self.assertIn("Failed", render(failed, "Failed", "https://github.com"))

    def test_incompatible_missing_baseline_and_bad_measurements(self):
        baseline = result(); baseline["compatibility"] = "different"
        for other in (None, baseline):
            self.assertIsNone(compare(result(), other)["baseline_revision"])
        for value in (float("nan"), -1, "<script>"):
            with self.assertRaises(ValueError):
                validate(result(value), "a" * 40)
        with self.assertRaises(ValueError):
            validate(result(), "b" * 40)

    def test_rerun_updates_one_comment_and_preserves_completed_record(self):
        calls = []
        run = {"id": 7, "run_attempt": 2, "head_sha": "a" * 40, "status": "completed",
               "conclusion": "success", "event": "pull_request", "head_branch": "feature", "html_url": "https://github.com/run/7"}
        record = result(); record.update(check_url="https://api.github.com/check/9", run_id=7, attempt=2)
        check = {"id": 9, "url": record["check_url"], "status": "completed", "external_id": "jvmd-benchmark:7:2",
                 "output": {"text": "```json\n" + json.dumps(record) + "\n```"}}
        pr = {"number": 22, "state": "open", "head": {"sha": run["head_sha"]}, "base": {"sha": "b" * 40}}
        def api(path, data=None, method=None):
            calls.append((path, data, method))
            if path == "check-runs": return check
            if path == "actions/runs/7": return run
            if path.endswith("/artifacts"): return {"artifacts": []}
            if path.startswith("commits/"): return [pr]
            if path == "pulls/22": return pr
            if path.startswith("actions/workflows/"): return {"workflow_runs": [run]}
            if path.startswith("issues/22/comments"): return [{"id": 3, "user": {"login": "github-actions[bot]"}, "body": "<!-- jvmd-benchmark -->old"}]
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {"GITHUB_STEP_SUMMARY": directory + "/summary"}), patch("check.api", api), patch("check.records", return_value=[check]):
            publish(run); publish(run)
            self.assertEqual(2, sum(path == "issues/comments/3" and method == "PATCH" for path, _, method in calls))
            self.assertFalse(any(path.startswith("check-runs/") for path, _, _ in calls))
            calls.clear(); publish(dict(run, run_attempt=1))
            self.assertEqual(1, len(calls))
            calls.clear()
            with patch("check.records", return_value=[]): publish(run)
            self.assertTrue(any(data and data.get("conclusion") == "failure" for _, data, _ in calls))
            self.assertTrue(any(data and "Failed" in data.get("body", "") for _, data, _ in calls))
            calls.clear(); pr["head"]["sha"] = "c" * 40; publish(run)
            self.assertFalse(any(method == "PATCH" for _, _, method in calls))

    def test_baseline_is_compatible_main_history_without_running_code(self):
        saved = result(); saved["baseline_eligible"] = True
        def api(path):
            if path.startswith("commits?"): return [{"sha": "base"}, {"sha": "ancestor"}]
            return {"workflow_runs": [{"head_sha": "unrelated"}, {"head_sha": "ancestor"}]}
        record = {"id": 1, "conclusion": "success", "output": {"text": "```json\n" + json.dumps(saved) + "\n```"}}
        with patch("check.api", api), patch("check.records", return_value=[record]) as read:
            self.assertEqual(saved, baseline("base", "same"))
            self.assertIsNone(baseline("base", "different"))
            self.assertTrue(all(call.args == ("ancestor",) for call in read.call_args_list))
