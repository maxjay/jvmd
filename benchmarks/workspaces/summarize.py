#!/usr/bin/env python3
"""Compact checked measurements; no responses, recordings or generated dashboard."""
import argparse
import collections
import hashlib
import json
import math
import statistics
from pathlib import Path
from verify import verify


def stats(values, state):
    return {
        "samples": len(values),
        "p50_ms": statistics.median(values) if values else None,
        "p95_ms": sorted(values)[math.ceil(.95 * len(values)) - 1]
        if state == "warm" and len(values) >= 20 else None,
        "min_ms": min(values) if values else None,
        "max_ms": max(values) if values else None,
    }


def summarize(root):
    provenance = json.loads((root / "provenance.json").read_text())
    verification = verify(root)
    groups = collections.defaultdict(list)
    fixtures, preparation, resources = set(), [], []
    for path in sorted(root.glob("*/report.json")):
        run = json.loads(path.read_text())
        fixtures.add(run["fixture_identity"])
        preparation.append({"worker": path.parent.name, "server": run["server"],
                            "milliseconds": run.get("preparation", {}).get("process_start_to_ready_ms")})
        resource = path.parent / "resources.json"
        if resource.exists():
            resources.append({"worker": path.parent.name, "groups": json.loads(resource.read_text())["groups"]})
        for action in run["actions"]:
            if action["state"] != "warmup":
                groups[action["operation"], action["state"], run["server"], run["mode"]].append((path.parent.name, action))
    rows = []
    for (operation, state, server, mode), actions in sorted(groups.items()):
        correct = [(worker, a) for worker, a in actions if a["outcome"] == "correct"]
        rows.append({"operation": operation, "state": state, "server": server, "mode": mode,
                     **stats([a["latency_ms"] for _, a in correct], state),
                     "outcomes": dict(collections.Counter(a["outcome"] for _, a in actions)),
                     "targets": sorted({a["target"] for _, a in actions}),
                     "processes": [{"worker": worker, **stats([a["latency_ms"] for w, a in correct if w == worker], state)}
                                   for worker in sorted({w for w, _ in actions})]})
    config = {key: provenance.get(key) for key in
              ("jdk", "node", "runs", "samples", "warmup", "targets", "sources", "cache",
               "cpu_quota", "cpu_period", "memory_limit", "runner")}
    config["fixtures"] = sorted(fixtures)
    config["harness"] = {key: provenance["harness"][key] for key in
                         ("run.py", "fixture.py", "verify.py", "resources.py", "bridge.ts", "StdioApplication.java", "summarize.py", "compile.py")}
    config["dependencies"] = {Path(k).name: v for k, v in provenance["build"]["dependencies"].items()
                              if not Path(k).name.startswith("jvmd-")}
    return {"schema": 2, "revision": provenance["build"]["revision"], "tree": provenance["build"]["tree"],
            "complete": verification["complete"], "rows": rows, "preparation": preparation,
            "resources": resources, "configuration": config,
            "compatibility": hashlib.sha256(json.dumps(config, sort_keys=True).encode()).hexdigest(),
            "verification": {"complete": verification["complete"],
                             "missing": sum(len(w["not_executed"]) for w in verification["workers"]),
                             "classification_valid": all(w["classification_valid"] for w in verification["workers"])}}


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("root", type=Path)
    p.add_argument("output", type=Path)
    a = p.parse_args()
    result = summarize(a.root)
    a.output.write_text(json.dumps(result, separators=(",", ":")) + "\n")
    raise SystemExit(0 if result["complete"] else 1)
