#!/usr/bin/env python3
"""One checked report: prepared queries, per-process variation and actual spans."""
import argparse
import collections
import json
import math
import statistics
from pathlib import Path
from verify import verify


def stats(values, state):
    return {
        "samples": len(values),
        "p50_ms": statistics.median(values) if values else None,
        "p95_ms": (
            sorted(values)[math.ceil(0.95 * len(values)) - 1]
            if state == "warm" and len(values) >= 20
            else None
        ),
        "min_ms": min(values) if values else None,
        "max_ms": max(values) if values else None,
    }


def union(intervals):
    total = 0
    end = None
    for start, stop in sorted(intervals):
        total += max(0, stop - max(start, end if end is not None else start))
        end = max(stop, end if end is not None else stop)
    return total


def investigate(run):
    stages = run.get("trace", {}).get("traceEvents", [])
    for action in run["actions"]:
        selected = [s for s in stages if s["args"].get("invocation") == action["id"]]
        action["stages"] = selected
        action["profile_groups"] = [
            p for p in run.get("attribution", {}).get("groups", []) if p["invocation"] == action["id"]
        ]
        action["rpc_union_ms"] = (
            union((s["ts"], s["ts"] + s["dur"]) for s in selected if s["name"] == "rpc.execute") / 1000
            if selected
            else None
        )
        action["queue_union_ms"] = (
            union((s["ts"], s["ts"] + s["dur"]) for s in selected if s["args"]["queued"]) / 1000
            if selected
            else None
        )


def summarize(root):
    provenance = json.loads((root / "provenance.json").read_text())
    verification = verify(root)
    groups = collections.defaultdict(list)
    runs = []
    for path in sorted(root.glob("*/report.json")):
        run = json.loads(path.read_text())
        run["directory"] = path.parent.name
        for name in ("resources", "trace", "attribution"):
            file = path.parent / (name + ".json")
            if file.exists():
                run[name] = json.loads(file.read_text())
        investigate(run)
        runs.append(run)
        for action in run["actions"]:
            if action["state"] == "warmup":
                continue
            key = (action["operation"], action["state"], run["server"], run["mode"])
            groups[key].append((run["directory"], action))
    rows = []
    for (operation, state, server, mode), actions in sorted(groups.items()):
        correct = [(worker, a) for worker, a in actions if a["outcome"] == "correct"]
        processes = []
        for worker in sorted({w for w, a in actions}):
            values = [a["latency_ms"] for w, a in correct if w == worker]
            processes.append(
                {
                    "worker": worker,
                    **stats(values, state),
                    "failures": sum(a["outcome"] != "correct" for w, a in actions if w == worker),
                }
            )
        rows.append(
            {
                "operation": operation,
                "state": state,
                "server": server,
                "mode": mode,
                **stats([a["latency_ms"] for w, a in correct], state),
                "outcomes": dict(collections.Counter(a["outcome"] for w, a in actions)),
                "targets": sorted({a["target"] for w, a in actions}),
                "processes": processes,
                "process_p50_range_ms": (
                    [
                        min(p["p50_ms"] for p in processes if p["p50_ms"] is not None),
                        max(p["p50_ms"] for p in processes if p["p50_ms"] is not None),
                    ]
                    if correct
                    else None
                ),
            }
        )
    return {
        "schema": 2,
        "provenance": provenance,
        "verification": verification,
        "rows": rows,
        "invocations": runs,
        "aggregation": "Successful requests only; failures shown separately. Pooled request median and nearest-rank warm p95 only with >=20 samples; first requests have no p95. Targets share one process, not independent repetitions. Per-process medians/ranges retained. Profiled and stage-only timings never enter ordinary comparison rows.",
        "scope": "Prepared JVMD/JDTLS servers, normal production LSP. RSS/CPU: Java server and bridge separately; process lifetime includes preparation. Allocation: sampled JFR weights only. Heap: diagnostic GCHeapSummary events; unavailable in unprofiled runs. No forced GC.",
    }


def markdown(summary):
    lines = [
        "# Prepared JVMD / JDTLS language services",
        "",
        summary["aggregation"],
        "",
        "| Operation / targets | State | JVMD correct / total | JVMD p50 / p95 ms | JDTLS correct / total | JDTLS p50 / p95 ms |",
        "|---|---|---:|---:|---:|---:|",
    ]
    ordinary = [r for r in summary["rows"] if r["mode"] == "comparison"]

    def cell(row):
        if row is None:
            return ["unmeasured", "unavailable"]
        count = f"{row['samples']} / {sum(row['outcomes'].values())}"
        value = lambda v: "unavailable" if v is None else f"{v:.3f}"
        return [count, value(row["p50_ms"]) + " / " + value(row["p95_ms"])]

    for op, state in sorted({(r["operation"], r["state"]) for r in ordinary}):
        rows = [r for r in ordinary if r["operation"] == op and r["state"] == state]
        cells = [op + " / " + ",".join(rows[0]["targets"]), state]
        for server in ("jvmd", "jdtls"):
            cells += cell(next((r for r in rows if r["server"] == server), None))
        lines.append("| " + " | ".join(cells) + " |")
    lines += [
        "",
        summary["scope"],
        "",
        "[Invocations, stages, profiles, failures and reproduction](dashboard.html)",
    ]
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("root", type=Path)
    p.add_argument("output", type=Path)
    p.add_argument("--markdown", type=Path)
    a = p.parse_args()
    result = summarize(a.root)
    a.output.write_text(json.dumps(result, indent=2) + "\n")
    if a.markdown:
        a.markdown.write_text(markdown(result))
