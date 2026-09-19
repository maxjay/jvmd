#!/usr/bin/env python3
"""Serial before/after seed and query comparisons on pinned real dependency JARs."""
import argparse
import json
from pathlib import Path
import shutil
import statistics
import subprocess
import time

from run import compile_tree, DEPENDENCIES, HERE, revision, sha


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--current", type=Path, default=HERE.parent.parent)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--dependencies", type=Path, required=True)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--runs", type=int, default=5)
    args = parser.parse_args()
    if args.runs < 1:
        parser.error("--runs must be positive")
    args.root.mkdir(parents=True, exist_ok=False)
    dep_root = args.root / "dependencies"
    dep_root.mkdir()
    dependencies = []
    repository = args.root / "repository"
    manifest = []
    for name in DEPENDENCIES:
        target = dep_root / name
        shutil.copyfile(next(args.dependencies.rglob(name)), target)
        dependencies.append(target)
        artifact, version = name[:-4].rsplit("-", 1)
        group = "com/fasterxml/jackson/core" if artifact.startswith("jackson") else "org/rocksdb" if artifact == "rocksdbjni" else "org/xerial"
        jar = repository / group / artifact / version / name
        jar.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(target, jar)
        manifest.append({"path": str(jar.relative_to(repository)), "size": jar.stat().st_size, "sha256": sha(jar)})
    cps, revisions = {}, {}
    for mode, repo in [("baseline", args.baseline), ("optimized", args.current)]:
        revisions[mode] = revision(repo)
        root = args.root / mode
        cps[mode] = compile_tree(repo, root, args.java_home, dependencies, True)
        subprocess.run([str(args.java_home / "bin/javac"), "--release", "25", "-encoding", "UTF-8",
                        "-cp", cps[mode], "-d", str(root / "classes"), str(HERE / "DependencyBenchmark.java")], check=True)
    (args.root / "classpaths.json").write_text(json.dumps(cps, indent=2))
    report = {"revisions": revisions, "manifest": manifest, "runs": [],
              "harness_sha256": {p.name: sha(p) for p in [Path(__file__), HERE / "DependencyBenchmark.java", HERE / "run.py"]},
              "environment": {"cpu_max": Path("/sys/fs/cgroup/cpu.max").read_text().strip(),
                              "memory_max": Path("/sys/fs/cgroup/memory.max").read_text().strip(),
                              "cpu_model": next(x.split(":", 1)[1].strip() for x in Path("/proc/cpuinfo").read_text().splitlines() if x.startswith("model name"))},
              "scope": "Pinned Jackson annotations/core/databind, SQLite JDBC and RocksDB JNI JARs. Independent 1GiB JVMs; serial alternating repetitions, fresh states then unchanged reopen. No OS-cache flush. Queries call the index API directly; 11 fixed warm samples per query."}
    for repetition in range(args.runs):
        for mode in (["baseline", "optimized"] if repetition % 2 == 0 else ["optimized", "baseline"]):
            root = args.root / f"{mode}-{repetition}"
            root.mkdir()
            for scenario in ["fresh", "reopen"]:
                print(repetition, mode, scenario, flush=True)
                output = root / (scenario + ".json")
                command = [str(args.java_home / "bin/java"), "-Xmx1024m", "--enable-native-access=ALL-UNNAMED",
                           "-cp", cps[mode], "dev.jvmd.bench.DependencyBenchmark", str(repository), str(root / "state"), str(output)]
                started = time.perf_counter()
                ready = None
                with (root / (scenario + ".log")).open("w") as log:
                    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
                    try:
                        for line in process.stdout:
                            if line.strip() == "JVMD_DEPENDENCIES_READY":
                                ready = time.perf_counter()
                            log.write(line)
                        if process.wait() != 0 or ready is None:
                            raise RuntimeError(f"Worker failed: {root}/{scenario}.log")
                    finally:
                        if process.poll() is None:
                            process.kill()
                            process.wait()
                result = json.loads(output.read_text())
                result.update(mode=mode, scenario=scenario, repetition=repetition,
                              process_start_to_all_types_ms=(ready - started) * 1000)
                if scenario == "reopen" and result["status"]["indexed"] != 0:
                    raise RuntimeError("Unexpected restart rebuild")
                report["runs"].append(result)
                groups = {}
                for row in report["runs"]:
                    groups.setdefault(row["mode"] + "/" + row["scenario"], []).append(row)
                report["summary"] = {key: {metric: {"median": statistics.median(r[metric] for r in rows),
                                                    "min": min(r[metric] for r in rows), "max": max(r[metric] for r in rows)}
                                           for metric in ["open_and_scan_ms", "process_start_to_all_types_ms", "peak_rss_bytes", "scan_cpu_ms"]}
                                     for key, rows in groups.items()}
                (args.root / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    if len({r["types_sha256"] for r in report["runs"]}) != 1:
        raise RuntimeError("Type identities differ")
    for i in range(len(report["runs"][0]["queries"])):
        if len({r["queries"][i]["sha256"] for r in report["runs"]}) != 1:
            raise RuntimeError("Query identities differ")
    print("Complete:", args.root / "report.json", flush=True)


if __name__ == "__main__":
    main()
