#!/usr/bin/env python3
"""Build once, establish a baseline, then compare serial alternating independent JVMs."""
import argparse
import hashlib
import json
import os
import platform
import random
import statistics
import subprocess
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
EXPORTS = [f"--add-exports=jdk.compiler/com.sun.tools.javac.{name}=ALL-UNNAMED"
           for name in ("api", "util", "code", "main", "platform")]


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def write(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


def build(args):
    # Exclude packaged JVMD classes: missing production classes must fail compilation.
    dependencies = args.output.with_name(args.output.name + "-dependencies")
    dependencies.mkdir(parents=True, exist_ok=False)
    for jar in sorted(args.dependencies.resolve().glob("*.jar")):
        if not jar.name.startswith("jvmd-"):
            (dependencies / jar.name).symlink_to(jar)
    subprocess.run(["python3", str(HERE.parent / "workspaces/compile.py"),
                    "--repo", str(args.repo), "--java-home", str(args.java_home),
                    "--dependencies", str(dependencies), "--output", str(args.output)], check=True)
    manifest = args.output / "build.json"
    info = json.loads(manifest.read_text())
    sources = sorted(HERE.glob("*.java"))
    command = [str(args.java_home.resolve() / "bin/javac"), *EXPORTS, "-cp", info["classpath"],
               "-d", str(args.output.resolve() / "classes"), *map(str, sources)]
    subprocess.run(command, check=True)
    info["benchmark_sources"] = {p.name: digest(p) for p in sources}
    info["benchmark_command"] = command
    info["java_version"] = subprocess.check_output([info["java"], "-version"], stderr=subprocess.STDOUT, text=True)
    info["classes"] = {str(p.relative_to(args.output / "classes")): digest(p)
                       for p in sorted((args.output / "classes").rglob("*.class"))}
    write(manifest, info)


def verify_build(info):
    classes = Path(info["classpath"].split(os.pathsep)[0])
    for relative, expected in info["classes"].items():
        if digest(classes / relative) != expected:
            raise ValueError(f"Class changed after recorded build: {relative}")
    for path, expected in info["dependencies"].items():
        if digest(Path(path)) != expected:
            raise ValueError(f"Dependency changed: {path}")
    if {p.name: digest(p) for p in sorted(HERE.glob("*.java"))} != info["benchmark_sources"]:
        raise ValueError("Benchmark harness changed since build")


def worker(build_info, output, fixture, profile=False):
    output.mkdir()
    command = [build_info["java"], "-Xms256m", "-Xmx1g", "-XX:+UseG1GC",
               "--enable-native-access=ALL-UNNAMED", *EXPORTS]
    if profile:
        command += [f"-XX:StartFlightRecording=filename={output / 'profile.jfr'},settings=profile,dumponexit=true"]
    command += ["-cp", build_info["classpath"], "NavigationBenchmark", str(output / "workspace"),
                str(output / "samples.json"), str(fixture), os.pathsep.join(build_info["dependencies"])]
    write(output / "command.json", command)
    started = time.monotonic()
    with (output / "run.log").open("w") as log:
        subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=240)
    result = json.loads((output / "samples.json").read_text())
    result["wall_seconds"] = time.monotonic() - started
    result["revision"] = build_info["revision"]
    return result


def medians(run, metric):
    values = {}
    for sample in run["samples"]:
        key = sample["workload"] + "/" + sample["phase"]
        values.setdefault(key, []).append(sample[metric])
    return {key: statistics.median(items) for key, items in values.items()}


def percentile(values, fraction):
    values = sorted(values)
    return values[max(0, min(len(values) - 1, int(fraction * len(values) + 0.999999) - 1))]


def summarize(runs):
    result = {}
    for metric in ("ms", "allocated_bytes"):
        by_variant = {}
        for run in runs:
            by_variant.setdefault(run["variant"], []).append(medians(run, metric))
        rows = {}
        for scenario in next(iter(by_variant.values()))[0]:
            row = {}
            for variant, summaries in by_variant.items():
                values = [summary[scenario] for summary in summaries]
                row[variant] = {"median": statistics.median(values), "min": min(values),
                                "max": max(values), "sample_p95": percentile(values, .95), "n": len(values)}
            if "after" in by_variant:
                before = [summary[scenario] for summary in by_variant["before"]]
                after = [summary[scenario] for summary in by_variant["after"]]
                ratios = [a / b for a, b in zip(after, before)]
                rng = random.Random(1729)
                bootstrap = sorted(statistics.median(rng.choices(ratios, k=len(ratios))) for _ in range(5000))
                row["paired_change_percent"] = (statistics.median(ratios) - 1) * 100
                row["paired_change_ci95"] = [(percentile(bootstrap, q) - 1) * 100 for q in (.025, .975)]
            rows[scenario] = row
        result[metric] = rows
    return result


def campaign(args):
    args.output.mkdir(parents=True, exist_ok=False)
    builds = {"before": json.loads(args.before.read_text())}
    if args.after:
        builds["after"] = json.loads(args.after.read_text())
        for field in ("dependencies", "java_version", "benchmark_sources"):
            left = builds["before"][field]
            right = builds["after"][field]
            if field == "dependencies":
                left = {Path(k).name: v for k, v in left.items()}
                right = {Path(k).name: v for k, v in right.items()}
            if left != right:
                raise ValueError(f"Builds differ in {field}")
    for info in builds.values():
        verify_build(info)
    fixture = args.fixture.resolve()
    metadata = {"builds": builds, "fixture": {str(p.relative_to(fixture)): digest(p)
                for p in sorted(fixture.rglob("*.java"))}, "platform": platform.platform(),
                "cpu_count": os.cpu_count(), "pairs": args.pairs, "profiled": args.profile,
                "harness_sha256": digest(Path(__file__)), "runs": []}
    write(args.output / "campaign.json", metadata)
    for pair in range(args.pairs):
        order = list(builds) if pair % 2 == 0 else list(reversed(builds))
        for variant in order:
            print(f"pair {pair + 1}/{args.pairs}: {variant}", flush=True)
            result = worker(builds[variant], args.output / f"{pair:02d}-{variant}", fixture, args.profile)
            result.update(pair=pair, variant=variant)
            metadata["runs"].append(result)
            write(args.output / "campaign.json", metadata)
    if not args.profile:
        write(args.output / "summary.json", summarize(metadata["runs"]))
    print(args.output / "campaign.json", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    builder = commands.add_parser("build")
    for name in ("repo", "java-home", "dependencies", "output"):
        builder.add_argument("--" + name, type=Path, required=True)
    runner = commands.add_parser("run")
    for name in ("before", "fixture", "output"):
        runner.add_argument("--" + name, type=Path, required=True)
    runner.add_argument("--after", type=Path)
    runner.add_argument("--pairs", type=int, default=10)
    runner.add_argument("--profile", action="store_true")
    args = parser.parse_args()
    if args.command == "build":
        build(args)
    else:
        if args.pairs < 1:
            parser.error("--pairs must be positive")
        campaign(args)


if __name__ == "__main__":
    main()
