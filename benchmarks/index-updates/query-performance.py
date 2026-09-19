#!/usr/bin/env python3
"""Serial cold/reopened/warm comparison of two committed Rocks implementations."""
import argparse
import json
import os
from pathlib import Path
import shutil
import statistics
import subprocess
import time

from run import compile_tree, launch, revision, sha, DEPENDENCIES, HERE


def measure(java_home, cp, repository, state, output, artifacts, classes, fields, operation, log):
    command = [str(java_home / "bin/java"), "-Xmx1024m", "--enable-native-access=ALL-UNNAMED",
               "-Djvmd.benchmark.ready_marker=true", "-Djvmd.benchmark.warm_type_queries=3",
               "-cp", cp, "dev.jvmd.bench.RepositoryUpdateBenchmark", operation, str(repository),
               str(state), str(output), str(artifacts), str(classes), str(fields), str(artifacts > 1).lower()]
    started = time.perf_counter()
    ready = None
    with log.open("w") as out:
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        try:
            for line in process.stdout:
                if line.strip() == "JVMD_BENCHMARK_ALL_TYPES_READY":
                    ready = time.perf_counter()
                out.write(line)
            if process.wait() != 0:
                raise RuntimeError(f"Worker failed: {log}")
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
    if ready is None:
        raise RuntimeError("Missing process readiness marker")
    value = json.loads(output.read_text())
    value["process_start_to_all_types_ms"] = (ready - started) * 1000
    value["process_elapsed_ms"] = (time.perf_counter() - started) * 1000
    return value


def summarize(runs):
    groups = {}
    for run in runs:
        scenario = run["scenarios"][0]
        key = "/".join([run["fixture"], run["mode"], scenario["scenario"]])
        groups.setdefault(key, []).append({**run, **scenario})
    metrics = ["open_and_scan_ms", "scan_ms", "all_types_query_ms", "all_types_queryable_ms",
               "process_start_to_all_types_ms", "warm_broad_type_query_p50_ms", "type_query_p50_ms",
               "type_query_p95_ms", "peak_rss_bytes", "scan_write_bytes", "indexed_delta"]
    return {key: {metric: {"median": statistics.median(x[metric] for x in rows),
                           "min": min(x[metric] for x in rows), "max": max(x[metric] for x in rows)}
                  for metric in metrics} for key, rows in groups.items()}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--current", type=Path, default=HERE.parent.parent)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--dependencies", type=Path, required=True)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--runs", type=int, default=3)
    args = parser.parse_args()
    if args.runs < 1:
        parser.error("--runs must be positive")
    args.root.mkdir(parents=True, exist_ok=False)
    dep_root = args.root / "dependencies"
    dep_root.mkdir()
    dependencies = []
    for name in DEPENDENCIES:
        target = dep_root / name
        shutil.copyfile(next(args.dependencies.rglob(name)), target)
        dependencies.append(target)
    cps, revisions = {}, {}
    for mode, repo in [("baseline", args.baseline), ("optimized", args.current)]:
        revisions[mode] = revision(repo)
        cps[mode] = compile_tree(repo, args.root / mode, args.java_home, dependencies, True)
    report = {"revisions": revisions, "dependencies_sha256": {p.name: sha(p) for p in dependencies},
              "harness_sha256": {p.name: sha(p) for p in [Path(__file__), HERE / "run.py", HERE / "RepositoryUpdateBenchmark.java"]},
              "environment": {"cpu_max": Path("/sys/fs/cgroup/cpu.max").read_text().strip(),
                              "memory_max": Path("/sys/fs/cgroup/memory.max").read_text().strip(),
                              "cpu_model": next(x.split(":", 1)[1].strip() for x in Path("/proc/cpuinfo").read_text().splitlines() if x.startswith("model name"))},
              "scope": f"Both implementations use Rocks. Fresh states, then unchanged reopened states. Serial 1GiB JVMs, alternating backend order, {args.runs} repetitions. Process readiness includes JVM launch, unlike the inner service timer. Three broad warm queries follow 31 exact type and 32 field queries; no OS-cache flush.",
              "fixtures": {}, "runs": []}
    (args.root / "classpaths.json").write_text(json.dumps(cps, indent=2))
    for fixture, artifacts, classes, fields in [("single-380000", 1, 950, 397), ("m2-128", 128, 8, 368)]:
        root = args.root / fixture
        root.mkdir()
        repository = root / "pristine"
        manifest = root / "manifest.json"
        launch(args.java_home, cps["baseline"], "dev.jvmd.bench.RepositoryUpdateBenchmark",
               ["fixture", repository, root / "unused", manifest, artifacts, classes, fields, str(artifacts > 1).lower()], root / "fixture.log")
        report["fixtures"][fixture] = json.loads(manifest.read_text())
        for repetition in range(args.runs):
            for mode in (["baseline", "optimized"] if repetition % 2 == 0 else ["optimized", "baseline"]):
                run_root = root / f"{mode}-{repetition}"
                run_root.mkdir()
                for operation in ["seed", "reopen"]:
                    print(fixture, repetition, mode, operation, flush=True)
                    value = measure(args.java_home, cps[mode], repository, run_root / "state", run_root / f"{operation}.json",
                                    artifacts, classes, fields, operation, run_root / f"{operation}.log")
                    value.update(fixture=fixture, mode=mode, repetition=repetition)
                    scenario = value["scenarios"][0]
                    if scenario["faults_delta"] or (operation == "reopen" and scenario["indexed_delta"]):
                        raise RuntimeError("Fault or unexpected restart rebuild")
                    report["runs"].append(value)
                    report["summary"] = summarize(report["runs"])
                    (args.root / "report.json").write_text(json.dumps(report, indent=2) + "\n")
        identities = {run["scenarios"][0]["all_types_scip_sha256"] for run in report["runs"] if run["fixture"] == fixture}
        if len(identities) != 1:
            raise RuntimeError("Search identities disagree across implementations/restarts")
    print("Complete:", args.root / "report.json", flush=True)


if __name__ == "__main__":
    main()
