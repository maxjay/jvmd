#!/usr/bin/env python3
"""Build unchanged index sources from two checkouts and run serial, isolated JVM comparisons."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import statistics
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
DEPENDENCIES = ["jackson-annotations-2.22.jar", "jackson-core-2.22.2.jar",
                "jackson-databind-2.22.2.jar", "sqlite-jdbc-3.53.4.0.jar", "rocksdbjni-10.10.1.1.jar"]


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def revision(repo):
    return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()


def compile_tree(repo, root, java_home, dependencies, rocks):
    modules = ["jvmd-core", "jvmd-index"] + (["jvmd-index-rocks"] if rocks else [])
    changed = subprocess.check_output(["git", "diff", "--name-only", "HEAD", "--", *modules], cwd=repo, text=True)
    if changed.strip():
        raise RuntimeError(f"Production sources must be committed: {changed}")
    classes = root / "classes"
    classes.mkdir(parents=True)
    sources = [p for module in modules for p in sorted((repo / module / "src/main/java").rglob("*.java")) if p.name != "module-info.java"]
    sources += [HERE / "RepositoryUpdateBenchmark.java"]
    if rocks:
        sources += [HERE / "MerkleUpdateBenchmark.java"]
    cp = os.pathsep.join(map(str, dependencies))
    with (root / "compile.log").open("w") as log:
        subprocess.run([str(java_home / "bin/javac"), "-encoding", "UTF-8", "-g:lines,vars,source", "-parameters",
                        "--release", "25", "-cp", cp, "-d", str(classes), *map(str, sources)],
                       stdout=log, stderr=subprocess.STDOUT, check=True)
    for module in modules:
        resource = repo / module / "src/main/resources"
        if resource.exists():
            shutil.copytree(resource, classes, dirs_exist_ok=True)
    return str(classes) + os.pathsep + cp


def launch(java_home, cp, class_name, args, log):
    with log.open("w") as out:
        subprocess.run([str(java_home / "bin/java"), "-Xmx1024m", "--enable-native-access=ALL-UNNAMED",
                        "-cp", cp, class_name, *map(str, args)], stdout=out, stderr=subprocess.STDOUT, check=True)


def summarize(runs):
    groups = {}
    for run in runs:
        if "scenarios" not in run:
            continue
        for row in run["scenarios"]:
            key = run["fixture"] + "/" + run["mode"] + "/" + row["scenario"]
            groups.setdefault(key, []).append(row)
    summary = {}
    for key, rows in groups.items():
        metrics = {}
        for field in ["scan_ms", "open_and_scan_ms", "workspace_ready_ms", "elapsed_through_close_ms",
                      "write_bytes", "write_bytes_through_close", "query_p95_ms", "first_query_ms", "all_types_queryable_ms", "type_query_p95_ms",
                      "indexed_delta", "hashes_delta", "reused_delta", "cpu_ms"]:
            values = [r[field] for r in rows if field in r]
            if values:
                metrics[field] = {"median": statistics.median(values), "min": min(values), "max": max(values)}
        summary[key] = metrics
    return summary


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--main", type=Path, required=True)
    parser.add_argument("--rocks", type=Path, default=HERE.parent.parent)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--dependencies", type=Path, required=True)
    parser.add_argument("--root", type=Path, required=True, help="New measurement directory on the target filesystem")
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--smoke", action="store_true")
    args = parser.parse_args()
    if args.runs < 3 and not args.smoke:
        parser.error("At least three runs are required")
    args.root.mkdir(parents=True, exist_ok=False)
    dependencies = []
    dep_root = args.root / "dependencies"
    dep_root.mkdir()
    for name in DEPENDENCIES:
        choices = sorted(args.dependencies.rglob(name))
        if not choices:
            raise RuntimeError(f"Missing dependency {name}")
        target = dep_root / name
        shutil.copyfile(choices[0], target)
        dependencies.append(target)
    cps = {}
    revisions = {}
    for mode, repo in [("main-sqlite", args.main), ("rocks", args.rocks)]:
        repo = repo.resolve()
        revisions[mode] = revision(repo)
        print("Compiling", mode, revisions[mode], flush=True)
        cps[mode] = compile_tree(repo, args.root / mode, args.java_home, dependencies, mode == "rocks")
    (args.root / "classpaths.json").write_text(json.dumps(cps, indent=2))
    report = {"revisions": revisions, "dependencies_sha256": {p.name: sha(p) for p in dependencies},
              "harness_sha256": {p.name: sha(p) for p in [Path(__file__), HERE / "RepositoryUpdateBenchmark.java", HERE / "MerkleUpdateBenchmark.java"]},
              "environment": {"cpu_max": Path("/sys/fs/cgroup/cpu.max").read_text().strip(),
                              "memory_max": Path("/sys/fs/cgroup/memory.max").read_text().strip(),
                              "cpu_model": next(x.split(":", 1)[1].strip() for x in Path("/proc/cpuinfo").read_text().splitlines() if x.startswith("model name"))},
              "scope": "Unmodified main versus default Rocks production provider. Serial fresh 1 GiB JVMs; identical fixture bytes; OS cache not flushed. Source Merkle experiment is separate from Maven inventory.",
              "fixtures": {}, "runs": []}
    configs = [("single-380000", 1, 950, 397, False), ("m2-128", 128, 8, 368, True)]
    if args.smoke:
        configs = [("single-smoke", 1, 3, 7, False), ("m2-smoke", 4, 3, 7, True)]
    for name, artifacts, classes, fields, snapshot in configs:
        fixture_root = args.root / name
        fixture_root.mkdir()
        pristine = fixture_root / "pristine"
        manifest = fixture_root / "manifest.json"
        launch(args.java_home, cps["main-sqlite"], "dev.jvmd.bench.RepositoryUpdateBenchmark",
               ["fixture", pristine, fixture_root / "unused", manifest, artifacts, classes, fields, str(snapshot).lower()], fixture_root / "fixture.log")
        report["fixtures"][name] = json.loads(manifest.read_text())
        for repetition in range(1 if args.smoke else args.runs):
            modes = ["main-sqlite", "rocks"] if repetition % 2 == 0 else ["rocks", "main-sqlite"]
            for mode in modes:
                run_root = fixture_root / f"{mode}-{repetition}"
                run_root.mkdir()
                repository = run_root / "repository"
                shutil.copytree(pristine, repository, copy_function=shutil.copy2)
                for operation in (["seed", "updates", "restart"] if snapshot else ["seed"]):
                    output = run_root / f"{operation}.json"
                    print(name, repetition, mode, operation, flush=True)
                    launch(args.java_home, cps[mode], "dev.jvmd.bench.RepositoryUpdateBenchmark",
                           [operation, repository, run_root / "state", output, artifacts, classes, fields, str(snapshot).lower()], run_root / f"{operation}.log")
                    value = json.loads(output.read_text())
                    value.update({"fixture": name, "mode": mode, "repetition": repetition})
                    report["runs"].append(value)
                    report["summary"] = summarize(report["runs"])
                    (args.root / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    report["merkle_runs"] = []
    for count in ([100] if args.smoke else [1000, 10000]):
        for repetition in range(1 if args.smoke else args.runs):
            root = args.root / f"merkle-{count}-{repetition}"
            root.mkdir()
            output = root / "result.json"
            launch(args.java_home, cps["rocks"], "dev.jvmd.bench.MerkleUpdateBenchmark", [root, output, count, 4 if args.smoke else 20], root / "worker.log")
            report["merkle_runs"].append(json.loads(output.read_text()))
    (args.root / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Complete:", args.root / "report.json", flush=True)


if __name__ == "__main__":
    main()
