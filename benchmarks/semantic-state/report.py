#!/usr/bin/env python3
"""Combine unchanged paired campaigns and render all latency/allocation measurements."""
import argparse
import copy
import json
import statistics
from pathlib import Path
from suite import summarize, write


def combine(paths):
    combined = None
    for path in paths:
        campaign = json.loads(path.read_text())
        if campaign.get("profiled") or campaign.get("retained_heap"):
            raise ValueError("Profiled/forced-GC measurements cannot enter a latency report")
        if set(campaign["builds"]) != {"before", "after"}:
            raise ValueError("Both variants are required")
        pairs = {}
        for run in campaign["runs"]:
            variants = pairs.setdefault(run["pair"], set())
            if run["variant"] in variants:
                raise ValueError("Duplicate worker")
            variants.add(run["variant"])
        if len(pairs) != campaign["pairs"] or any(v != {"before", "after"} for v in pairs.values()):
            raise ValueError("Incomplete comparison: retain the failure separately")
        if combined is None:
            combined = copy.deepcopy(campaign)
            combined.update(runs=[], pairs=0, source_campaigns=[])
        for field in ("builds", "fixture", "harness_sha256", "main", "platform", "cpu_count"):
            if combined.get(field) != campaign.get(field):
                raise ValueError(f"Campaigns differ in {field}; do not pool them")
        for run in campaign["runs"]:
            combined["runs"].append(dict(run, pair=run["pair"] + combined["pairs"]))
        combined["pairs"] += campaign["pairs"]
        combined["source_campaigns"].append(str(path.resolve()))
    return combined


def change(row):
    low, high = row["paired_change_ci95"]
    return f'{row["paired_change_percent"]:+.1f}% [{low:+.1f}, {high:+.1f}]'


def render(campaign, summary):
    lines = ["# Paired benchmark measurements", "",
             f'{campaign["pairs"]} independent alternating JVM pairs; every output assertion passed.',
             "Paired changes are medians of worker ratios, not ratios of the displayed medians.",
             "Sample p95 is the p95 of worker medians; it is not production request-tail latency.",
             "95% intervals use the predeclared paired bootstrap. Lower is better.", "",
             "## Latency", "",
             "| Scenario | Before median ms | After median ms | Before sample p95 | After sample p95 | Paired change [95% interval] |",
             "| --- | ---: | ---: | ---: | ---: | ---: |"]
    for scenario, row in summary["ms"].items():
        before, after = row["before"], row["after"]
        lines.append(f'| {scenario} | {before["median"]:.3f} | {after["median"]:.3f} | '
                     f'{before["sample_p95"]:.3f} | {after["sample_p95"]:.3f} | {change(row)} |')
    lines += ["", "## Allocated bytes", "",
              "Decimal MB per request, reduced to a median within each JVM first.", "",
              "| Scenario | Before MB | After MB | Paired change [95% interval] |",
              "| --- | ---: | ---: | ---: |"]
    for scenario, row in summary["allocated_bytes"].items():
        lines.append(f'| {scenario} | {row["before"]["median"]/1e6:.3f} | '
                     f'{row["after"]["median"]/1e6:.3f} | {change(row)} |')
    if all("peak_rss_kib" in run and "gc" in run for run in campaign["runs"]):
        lines += ["", "## Whole-worker resources", "",
                  "Includes warmup and untimed correctness checks. RSS includes native memory.", "",
                  "| Variant | Peak RSS MiB | GC milliseconds | GC collections |",
                  "| --- | ---: | ---: | ---: |"]
        for variant in ("before", "after"):
            runs = [run for run in campaign["runs"] if run["variant"] == variant]
            rss = statistics.median(run["peak_rss_kib"] / 1024 for run in runs)
            gc_ms = statistics.median(sum(gc["ms"] for gc in run["gc"]) for run in runs)
            collections = statistics.median(sum(gc["collections"] for gc in run["gc"]) for run in runs)
            lines.append(f"| {variant} | {rss:.2f} | {gc_ms:.1f} | {collections:.1f} |")
    lines += ["", "Raw inputs, every request, min/max and summaries are retained with this report.",
              "These tables do not waive correctness, latency-regression or code-reduction gates.", ""]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("campaigns", nargs="+", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    combined = combine(args.campaigns)
    summary = summarize(combined["runs"])
    args.output.mkdir(parents=True, exist_ok=False)
    write(args.output / "campaign.json", combined)
    write(args.output / "summary.json", summary)
    (args.output / "measurements.md").write_text(render(combined, summary))
    print(args.output)


if __name__ == "__main__":
    main()
