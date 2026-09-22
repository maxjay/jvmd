"""Check contract: correctness gates, advisory deltas and immutable run publication."""
import copy
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
    def test_success_requires_complete_ci_coverage(self):
        from check import OPERATIONS
        good = result()
        good["configuration"] = dict(runs=5, targets=3, samples=20, warmup=2)
        good["rows"] = [dict(operation=op, state=state, server="jvmd", mode="comparison",
                             p50_ms=1, p95_ms=2 if state == "warm" else None,
                             samples=5*count, outcomes={"correct": 5*count}, targets=["0", "1", "2"],
                             processes=[dict(worker=str(i), samples=count) for i in range(5)])
                        for op in OPERATIONS for state, count in [("first", 3), ("warm", 60)]]
        self.assertTrue(validate(copy.deepcopy(good), good["revision"])["complete"])
        for defect in ("empty", "missing", "duplicate", "wrong", "count", "targets", "processes", "config"):
            bad = copy.deepcopy(good)
            if defect == "empty": bad["rows"] = []
            elif defect == "missing": bad["rows"].pop()
            elif defect == "duplicate": bad["rows"][-1] = bad["rows"][0]
            elif defect == "wrong": bad["rows"][0]["outcomes"] = {"wrong": 15}
            elif defect == "count": bad["rows"][0]["samples"] = 0
            elif defect == "targets": bad["rows"][0]["targets"] = ["0"]
            elif defect == "processes": bad["rows"][0]["processes"] = []
            else: bad["configuration"]["runs"] = 0
            with self.subTest(defect=defect):
                self.assertFalse(validate(bad, good["revision"])["complete"])

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
        latest = [run]
        def api(path, data=None, method=None):
            calls.append((path, data, method))
            if path == "check-runs": return check
            if path == "actions/runs/7": return run
            if path.endswith("/artifacts"): return {"artifacts": []}
            if path.startswith("commits/"): return [pr]
            if path == "pulls/22": return pr
            if path.startswith("actions/workflows/"): return {"workflow_runs": latest}
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
            calls.clear(); latest.append({"id": 8}); publish(run)
            self.assertFalse(any(method == "PATCH" for _, _, method in calls))
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
