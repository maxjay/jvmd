#!/usr/bin/env python3
"""Comparator sensitivity controls (assessment §13, §22.5): each mutation must be detected."""
import copy
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import compare  # noqa: E402


KEY = {"binarySha256": "a" * 64, "formatVersion": 1, "indexerVersion": "jvmd-index-v9", "runtimeFeature": 25, "mode": "signatures"}


def export(complete=True):
    key = KEY
    return {
        "migration": {"active": "format-1-jdk25-jvmd-index-v9" if complete else "", "complete": complete},
        "families": {
            "metadata.artifacts": {
                "/r/a.jar": {"input": {"context": {"path": "/r/a.jar", "gav": "g:a:1", "kind": "jar"}, "key": key, "size": 10, "mtime": 5},
                             "docsKey": None, "codeKey": None, "resolutionIdentity": "a" * 64, "symbols": 3, "edges": 1,
                             "classReferences": True, "sourceRevision": 0, "simpleNames": 1},
                "/r/b.jar": {"input": {"context": {"path": "/r/b.jar", "gav": "g:b:1", "kind": "jar"}, "key": dict(key, binarySha256="b" * 64), "size": 11, "mtime": 6},
                             "docsKey": "d" * 64, "codeKey": None, "resolutionIdentity": "b" * 64, "symbols": 4, "edges": 2,
                             "classReferences": True, "sourceRevision": 0, "simpleNames": 2},
            },
            "metadata.unmatched_source_members": {"/r/b-sources.jar": "0"},
            "repository.generations": {compare.cache_key(key): {"sha256": "1" * 64, "records": {"1|symbol": 3}},
                                       compare.cache_key(dict(key, binarySha256="b" * 64)): {"sha256": "2" * 64, "records": {"1|symbol": 4}},
                                       "d" * 64: {"sha256": "4" * 64, "records": {"9|member": 2}}},
            "classpath.slots": ["slot:a", "slot:b", "slot:c"],
            "metadata.key_prefix_counts": {"A": 2, "next-artifact": 1},
        },
    }


class ComparatorSensitivity(unittest.TestCase):
    def test_identical_is_equal(self):
        self.assertEqual(compare.compare_exports(export(), export())["verdict"], "EQUAL")

    def test_changed_fact_with_equal_counts(self):
        b = export()
        k2 = compare.cache_key(dict(KEY, binarySha256="b" * 64))
        b["families"]["repository.generations"][k2]["sha256"] = "3" * 64  # same record counts, different content
        r = compare.compare_exports(export(), b)
        self.assertEqual(r["verdict"], "NOT_EQUIVALENT")
        f = r["families"]["repository.reachable_generations"]
        self.assertEqual(f["changed_count"], 1)
        self.assertEqual(f["a_count"], f["b_count"])

    def test_unreferenced_generation_is_reported_not_compared(self):
        b = export()
        b["families"]["repository.generations"]["e" * 64] = {"sha256": "5" * 64, "records": {"1|symbol": 9}}
        r = compare.compare_exports(export(), b)
        self.assertEqual(r["verdict"], "EQUAL")
        self.assertEqual(r["families"]["repository.unreferenced_generations"], {"status": "NOT_COMPARED", "observed": "DIFFERENT"})

    def test_missing_reachable_generation(self):
        b = export()
        del b["families"]["repository.generations"]["d" * 64]
        r = compare.compare_exports(export(), b)
        self.assertEqual(r["verdict"], "NOT_EQUIVALENT")
        self.assertEqual(r["families"]["repository.referenced_but_missing"]["status"], "DIFFERENT")

    def test_changed_value_inside_artifact(self):
        b = export()
        b["families"]["metadata.artifacts"]["/r/a.jar"]["symbols"] = 4
        self.assertEqual(compare.compare_exports(export(), b)["verdict"], "NOT_EQUIVALENT")

    def test_missing_result(self):
        b = export()
        del b["families"]["metadata.artifacts"]["/r/b.jar"]
        r = compare.compare_exports(export(), b)
        self.assertEqual(r["verdict"], "NOT_EQUIVALENT")
        self.assertEqual(r["families"]["metadata.artifacts"]["missing_in_b"], ["/r/b.jar"])

    def test_missing_family(self):
        b = export()
        del b["families"]["repository.generations"]
        self.assertEqual(compare.compare_exports(export(), b)["verdict"], "NOT_EQUIVALENT")

    def test_reordered_classpath_slots(self):
        b = export()
        b["families"]["classpath.slots"] = ["slot:b", "slot:a", "slot:c"]
        r = compare.compare_exports(export(), b)
        self.assertEqual(r["verdict"], "NOT_EQUIVALENT")
        self.assertEqual(r["families"]["classpath.slots"]["first_difference"], 0)

    def test_partial_versus_complete(self):
        r = compare.compare_exports(export(complete=True), export(complete=False))
        self.assertEqual(r["verdict"], "NOT_EQUIVALENT")
        self.assertFalse(r["completeness"]["equal"])

    def test_two_partials_are_not_reported_complete(self):
        self.assertEqual(compare.compare_exports(export(False), export(False))["verdict"], "EQUAL_BUT_PARTIAL")

    def test_non_semantic_family_is_not_compared(self):
        b = export()
        b["families"]["metadata.key_prefix_counts"]["next-artifact"] = 7
        self.assertEqual(compare.compare_exports(export(), b)["verdict"], "EQUAL")

    def test_runtime_view_matches_disk_rows(self):
        e = export()
        rows = compare.disk_artifact_rows(e)
        self.assertEqual(compare.diff_value(rows, compare.disk_artifact_rows(copy.deepcopy(e)))["status"], "EQUAL")
        changed = copy.deepcopy(rows)
        changed[0][10] = 99
        self.assertEqual(compare.diff_value(changed, rows)["status"], "DIFFERENT")

    def test_runtime_views_missing_is_unobservable(self):
        self.assertEqual(compare.compare_runtime(None, {"store.artifacts": []})["status"], "UNOBSERVABLE")


if __name__ == "__main__":
    unittest.main()
