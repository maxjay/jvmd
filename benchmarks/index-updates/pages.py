#!/usr/bin/env python3
"""Compare complete bounded pages over copies of the same persisted fixture state."""
import argparse
import json
from pathlib import Path
import shutil
import statistics
import subprocess

from run import HERE, sha


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--benchmark-root", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--runs", type=int, default=5)
    parser.add_argument("--samples", type=int, default=5)
    args = parser.parse_args()
    if args.runs < 1 or args.samples < 1:
        parser.error("Run and sample counts must be positive")
    args.root.mkdir(parents=True, exist_ok=False)
    matrix = json.loads((args.benchmark_root / "report.json").read_text())
    cps = json.loads((args.benchmark_root / "classpaths.json").read_text())
    fixture = args.benchmark_root / "single-380000"
    for mode in ["baseline", "optimized"]:
        subprocess.run([str(args.java_home / "bin/javac"), "--release", "25", "-encoding", "UTF-8",
                        "-cp", cps[mode], "-d", str(args.benchmark_root / mode / "classes"),
                        str(HERE / "PagedQueryBenchmark.java")], check=True)
    report = {"revisions": matrix["revisions"], "environment": matrix["environment"],
              "fixture": matrix["fixtures"]["single-380000"], "runs": [],
              "scope": "Each isolated 1GiB JVM reopens a private copy of baseline-0's persisted index. Serial alternating runs. Mixed-kind substring and blank searches return 20 rows at the first, second and middle positions. Complete page hashes must match, including metadata and stable IDs. This is the direct index API, not LSP transport.",
              "harness_sha256": {p.name: sha(p) for p in [Path(__file__), HERE / "PagedQueryBenchmark.java"]}}
    for repetition in range(args.runs):
        for mode in (["baseline", "optimized"] if repetition % 2 == 0 else ["optimized", "baseline"]):
            print(repetition, mode, flush=True)
            root = args.root / f"{mode}-{repetition}"
            root.mkdir()
            shutil.copytree(fixture / "baseline-0/state", root / "state")
            output = root / "result.json"
            command = [str(args.java_home / "bin/java"), "-Xmx1024m", "--enable-native-access=ALL-UNNAMED",
                       "-cp", cps[mode], "dev.jvmd.bench.PagedQueryBenchmark", str(fixture / "pristine"),
                       str(root / "state"), str(output), str(args.samples)]
            with (root / "worker.log").open("w") as log:
                subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
            result = json.loads(output.read_text())
            result.update(mode=mode, repetition=repetition)
            report["runs"].append(result)
            report["summary"] = {}
            for mode_name in ["baseline", "optimized"]:
                rows = [r for r in report["runs"] if r["mode"] == mode_name]
                if rows:
                    report["summary"][mode_name] = [{"query": q["query"], "page": q["page"],
                        "worker_p50_ms": {"median": statistics.median(r["queries"][i]["p50_ms"] for r in rows),
                                          "min": min(r["queries"][i]["p50_ms"] for r in rows),
                                          "max": max(r["queries"][i]["p50_ms"] for r in rows)}}
                        for i, q in enumerate(rows[0]["queries"])]
            (args.root / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    for i in range(len(report["runs"][0]["queries"])):
        if len({r["queries"][i]["rows_sha256"] for r in report["runs"]}) != 1:
            raise RuntimeError("Complete page contents differ")
    print("Complete:", args.root / "report.json", flush=True)


if __name__ == "__main__":
    main()
