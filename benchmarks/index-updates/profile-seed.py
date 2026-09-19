#!/usr/bin/env python3
"""Diagnostic JFR allocation/CPU samples for compiled before/after seed workers."""
import argparse
from collections import Counter
import json
from pathlib import Path
import subprocess

from run import sha


def summarize(path):
    events = json.loads(path.read_text())["recording"]["events"]
    allocations, jvmd, cpu = Counter(), Counter(), Counter()
    total = gc_count = 0
    for event in events:
        value = event["values"]
        frames = (value.get("stackTrace") or {}).get("frames", [])
        names = [f["method"]["type"]["name"] + "." + f["method"]["name"] for f in frames]
        if event["type"] == "jdk.ObjectAllocationSample":
            weight = value["weight"]
            total += weight
            allocations[names[0] if names else "unknown"] += weight
            jvmd[next((n for n in names if n.startswith("dev/jvmd/")), "outside_or_truncated")] += weight
        elif event["type"] == "jdk.ExecutionSample":
            cpu[names[0] if names else "unknown"] += 1
        elif event["type"] == "jdk.GarbageCollection":
            gc_count += 1
    return {"estimated_allocated_bytes": total, "garbage_collections": gc_count,
            "allocation_leaf_bytes": dict(allocations.most_common()),
            "allocation_first_jvmd_frame_bytes": dict(jvmd.most_common()),
            "execution_leaf_samples": dict(cpu.most_common())}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--benchmark-root", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    args.root.mkdir(parents=True, exist_ok=False)
    matrix = json.loads((args.benchmark_root / "report.json").read_text())
    classpaths = json.loads((args.benchmark_root / "classpaths.json").read_text())
    report = {"scope": "One diagnostic JFR per implementation, fresh one-JAR 380000-symbol seed plus default query batch. Sampled allocation estimates are not retained memory; CPU samples are not a wall-clock partition. Use the unprofiled matrix for latency.",
              "revisions": matrix["revisions"], "environment": matrix["environment"],
              "fixture": matrix["fixtures"]["single-380000"], "script_sha256": sha(Path(__file__)), "runs": {}}
    for mode in ["baseline", "optimized"]:
        print("Profiling", mode, flush=True)
        root = args.root / mode
        root.mkdir()
        recording = root / "seed.jfr"
        command = [str(args.java_home / "bin/java"), "-Xmx1024m", "--enable-native-access=ALL-UNNAMED",
                   f"-XX:StartFlightRecording=filename={recording},settings=profile,dumponexit=true",
                   "-cp", classpaths[mode], "dev.jvmd.bench.RepositoryUpdateBenchmark", "seed",
                   str(args.benchmark_root / "single-380000/pristine"), str(root / "state"),
                   str(root / "result.json"), "1", "950", "397", "false"]
        with (root / "worker.log").open("w") as log:
            subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
        events = root / "events.json"
        with events.open("w") as out:
            subprocess.run([str(args.java_home / "bin/jfr"), "print", "--json", "--events",
                            "jdk.ObjectAllocationSample,jdk.ExecutionSample,jdk.GarbageCollection",
                            str(recording)], stdout=out, check=True)
        result = json.loads((root / "result.json").read_text())
        if result["scenarios"][0]["faults_delta"]:
            raise RuntimeError("Profiled seed reported a fault")
        report["runs"][mode] = {**summarize(events), "result": result,
                                "recording_sha256": sha(recording), "events_sha256": sha(events),
                                "command": command}
        (args.root / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Complete:", args.root / "report.json", flush=True)


if __name__ == "__main__":
    main()
