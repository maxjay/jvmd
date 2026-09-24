#!/usr/bin/env python3
"""One independent source oracle for live and archived prepared-server results."""
import argparse
import json
import re
from pathlib import Path
from urllib.parse import unquote, urlparse

STATE_ALIASES = {"first": "first_use", "warm": "steady"}


def canonical_state(state):
    return STATE_ALIASES.get(state, state)


def selected(text, range_):
    if isinstance(range_, list):
        range_ = {"start": range_[0], "end": range_[1]}
    lines = text.splitlines(keepends=True)

    def offset(p):
        return sum(len(s) for s in lines[: p["line"]]) + p["character"]

    start, end = offset(range_["start"]), offset(range_["end"])
    if not 0 <= start < end <= len(text):
        raise ValueError("invalid source range")
    return text[start:end]


def locations(value):
    rows = [value] if isinstance(value, dict) else value or []
    return [
        {
            "uri": unquote(r.get("uri", r.get("targetUri", ""))),
            "range": r.get("targetSelectionRange", r.get("range")),
        }
        for r in rows
    ]


def classify(operation, row):
    if "result" not in row:
        return row.get("outcome", "error")
    result = row["result"]
    name = operation["operation"]
    symbol = operation["symbol"]
    try:
        if name in ("definition", "dependency_definition"):
            actual = locations(result)
            expected = operation["expected"]
            if len(actual) != 1:
                return "incomplete" if not actual else "wrong"
            loc = actual[0]
            if name == "definition":
                return "correct" if loc == locations([expected])[0] else "wrong"
            if loc["uri"].startswith("jdt:"):
                evidence = next(
                    (v for k, v in row.get("source_evidence", {}).items() if unquote(k) == loc["uri"]), {}
                )
                source = evidence.get("source", "")
                valid = (
                    urlparse(loc["uri"]).path == "/" + expected["binary_name"] + "/" + expected["source_name"]
                    and source.strip() == expected["source"].strip()
                    and selected(source, loc["range"]) == symbol
                )
            else:
                valid = (
                    loc["uri"] == unquote(expected["uri"])
                    and selected(expected["source"], loc["range"]) == symbol
                )
            return "correct" if valid else "wrong"
        if name == "references":
            expected = operation["expected"]
            actual = locations(result)
            # JVMD returns invocation ranges; JDTLS returns identifiers. Both must
            # start at precisely the expected occurrence, with only its call suffix.
            normalized = []
            for loc in actual:
                matches = [
                    e
                    for e in expected
                    if unquote(e["uri"]) == loc["uri"] and e["range"]["start"] == loc["range"]["start"]
                ]
                if not matches:
                    return "wrong"
                e = matches[0]
                if loc["range"] != e["range"]:
                    text = selected(operation["source"], loc["range"])
                    if not re.fullmatch(re.escape(symbol) + r"\([12]\)", text):
                        return "wrong"
                normalized.append(e)
            key = lambda r: json.dumps(r, sort_keys=True)
            return "correct" if sorted(map(key, normalized)) == sorted(map(key, expected)) else "incomplete"
        if name == "hover":
            text = json.dumps(result)
            return (
                "correct"
                if re.search(r"\bint\s+[\w.]*" + re.escape(symbol) + r"\s*\(int\s+input\)", text)
                else "wrong"
            )
        if name == "completion":
            rows = result.get("items", []) if isinstance(result, dict) else result or []
            matches = [r for r in rows if re.match(re.escape(symbol) + r"(?:\b|\()", r.get("label", ""))]
            if not matches:
                return "incomplete"
            if any(re.search(r"\bString\b", json.dumps(r)) for r in matches):
                return "stale"
            signature = re.escape(symbol) + r"\(int(?:\s+input)?\)\s*:\s*int\b"
            return "correct" if any(re.search(signature, json.dumps(r)) for r in matches) else "wrong"

    except (ValueError, KeyError, TypeError, IndexError):
        return "wrong"
    raise ValueError("unknown operation: " + name)


def verify(root):
    provenance = json.loads((root / "provenance.json").read_text())
    workers = []
    expected_workers = provenance["runs"] * (2 if provenance["overhead"] else len(provenance["servers"]))
    identities = set()
    for path in sorted(root.glob("*/report.json")):
        report = json.loads(path.read_text())
        fixture = json.loads((path.parent / "fixture.json").read_text())
        identities.add(fixture["identity"])
        operations = {(o["operation"], o["target"]): o for o in fixture["operations"]}
        errors = []
        seen = set()
        for row in report["actions"]:
            key = (row["operation"], row["target"])
            identity = (*key, row["state"], row["sample"])
            if identity in seen:
                errors.append("duplicate invocation")
            seen.add(identity)
            if key not in operations or classify(operations[key], row) != row["outcome"]:
                errors.append("misclassified invocation " + row["id"])
            if row["outcome"] == "correct" and (row.get("latency_ms") is None or row["latency_ms"] < 0):
                errors.append("missing timing")
        if report.get("schema", 2) >= 3:
            state_counts = [
                ("first_use", 1),
                ("warmup", provenance["warmup"]),
                ("steady", provenance["samples"]),
            ]
        else:
            state_counts = [
                ("first", 1),
                ("warmup", provenance["warmup"]),
                ("warm", provenance["samples"]),
            ]
        required = {
            (op, target, state, sample)
            for op, target in operations
            for state, count in state_counts
            for sample in range(count)
        }
        missing = sorted(required - seen)
        if report.get("schema", 2) >= 3:
            milestones = report.get("preparation", {}).get("milestones_ns", {})
            ordered = [
                "process_spawn",
                "initialize_received",
                "workspace_ready",
                "documents_admitted",
                "first_use_started",
                "first_use_finished",
            ]
            values = [milestones.get(name) for name in ordered]
            if any(value is None for value in values) or values != sorted(values):
                errors.append("benchmark milestones are missing or out of order")
            if report.get("preparation", {}).get("readiness", {}).get("target_queried") is not False:
                errors.append("measured target was queried during readiness")
            for key in operations:
                states = [
                    canonical_state(row["state"])
                    for row in report["actions"]
                    if (row["operation"], row["target"]) == key
                ]
                if not states or states[0] != "first_use":
                    errors.append("operation-specific warmup preceded first use")
            first = next((row for row in report["actions"] if canonical_state(row["state"]) == "first_use"), None)
            diagnostic = report.get("diagnostic_cold_end_to_end_ms")
            if first and first.get("response_ns") and diagnostic is not None:
                expected = (first["response_ns"] - milestones["process_spawn"]) / 1e6
                if abs(expected - diagnostic) > 1e-6:
                    errors.append("cold end-to-end timing does not match milestone arithmetic")
        passed = (
            not errors
            and not missing
            and all(row["outcome"] == "correct" for row in report["actions"])
            and report["outcome"] == "correct"
        )
        workers.append(
            {
                "worker": path.parent.name,
                "verified": passed,
                "classification_valid": not errors,
                "errors": errors,
                "not_executed": missing,
                "outcomes": {
                    outcome: sum(row["outcome"] == outcome for row in report["actions"])
                    for outcome in sorted({r["outcome"] for r in report["actions"]})
                },
            }
        )
    return {
        "schema": 2,
        "workers": workers,
        "fixture_identity_equal": len(identities) == 1,
        "expected_workers": expected_workers,
        "complete": len(workers) == expected_workers
        and len(identities) == 1
        and all(w["verified"] for w in workers),
    }


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("root", type=Path)
    p.add_argument("output", type=Path)
    a = p.parse_args()
    result = verify(a.root)
    a.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result))
    raise SystemExit(0 if result["complete"] else 1)
