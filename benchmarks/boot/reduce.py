#!/usr/bin/env python3
"""Reduce one scenario directory of benchmarks/boot output into reduction.json and reduction.md.

Reads every */summary.json (one per daemon boot), pair.json (one per cold/warm pair), controls.json,
shutdown.json and every *.collapsed profile. Profile reductions report samples, never elapsed time:
summed samples across threads are not boot duration.

usage: reduce.py SCENARIO_DIR
"""
import collections
import glob
import json
import os
import re
import statistics
import sys

KEY_COUNTERS = ["jar.parsed", "jar.metadata_reuse", "jar.hash_reuse", "jar.hash_calls", "jar.hash_sha256_bytes",
                "checksum.sha1_files", "checksum.sha1_bytes", "jar.class_models", "jar.symbols",
                "sources.parsed", "sources.metadata_reuse", "sources.members",
                "admission.estimate_jar_open_calls", "admission.estimate_jar_open_ns", "admission.estimate_entries",
                "admission.artifact_wait_ns", "inventory.observe_calls", "inventory.observe_ns",
                "docs.verify_calls", "docs.verify_full_passes", "docs.verify_records", "docs.verify_full_ns",
                "restore.artifact_manifests", "restore.metadata_store_ns", "repository.contains",
                "store.metadata_installs", "store.observation_refreshes",
                "open.repository_db_ns", "open.inventory_db_ns", "open.semantic_state_dbs_ns", "open.metadata_store_ns",
                "inventory.complete_scan_ns", "activation.validate_candidate_ns", "activation.activate_and_prune_ns",
                "jar.parse_ns", "jar.hash_ns", "jar.canonical_facts_ns", "jar.publish_binary_ns", "sources.publish_ns"]
TIMES = ["MAIN_ENTERED", "STORAGE_OPEN_END", "SESSION_CAPABLE", "PRODUCT_READY", "SCAN_STARTED", "DISCOVERY_COMPLETE",
         "SKELETONS_COMPLETE", "DOCS_COMPLETE", "INVENTORY_COMPLETED", "SCAN_COMPLETE", "DOMAIN_READY"]


def load(path):
    with open(path) as f:
        return json.load(f)


def fmt(v, unit=""):
    if v is None:
        return "n/a"
    if isinstance(v, float):
        return f"{v:,.0f}{unit}"
    if isinstance(v, int):
        return f"{v:,}{unit}"
    return str(v)


def boots(root):
    rows = []
    for path in sorted(glob.glob(os.path.join(root, "**", "summary.json"), recursive=True)):
        s = load(path)
        s["_path"] = os.path.relpath(path, root)
        rows.append(s)
    return rows


def boot_row(s):
    t = s.get("t_ms", {})
    c = s.get("counters") or {}
    cpu = s.get("cpu_at_domain_ready") or {}
    io = s.get("io_at_domain_ready") or {}
    alloc = (s.get("allocated_bytes") or {}).get("DOMAIN_READY")
    return {
        "label": s.get("label"), "mode": s.get("mode"), "domain_ready": s.get("domain_ready"), "failure": s.get("failure"),
        **{"t_" + k: t.get(k) for k in TIMES},
        "cpu_user_ms": cpu.get("user_ms"), "cpu_system_ms": cpu.get("system_ms"),
        "allocated_bytes": alloc, "vm_hwm_bytes": s.get("peak_rss_bytes_at_domain_ready"),
        "read_bytes": io.get("read_bytes"), "write_bytes": io.get("write_bytes"), "rchar": io.get("rchar"), "wchar": io.get("wchar"),
        "major_faults": (s.get("faults_at_domain_ready") or {}).get("major"),
        "detection_lag_ms": s.get("detection_lag_ms"),
        "counters": {k: c.get(k) for k in KEY_COUNTERS if k in c},
    }


def collapsed(path, limit=30):
    """Top self and inclusive frames of one collapsed-stack file."""
    self_counts, inclusive, total = collections.Counter(), collections.Counter(), 0
    by_thread = collections.Counter()
    with open(path, errors="replace") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line:
                continue
            stack, _, count = line.rpartition(" ")
            try:
                n = int(count)
            except ValueError:
                continue
            frames = stack.split(";")
            total += n
            self_counts[frames[-1]] += n
            for frame in set(frames):
                inclusive[frame] += n
            if frames and frames[0].startswith("[") and frames[0].endswith("]"):
                by_thread[re.sub(r"\d+", "#", frames[0])] += n
    interesting = re.compile(r"dev[./]jvmd|org[./]rocksdb|rocksdb::|java[./]util[./]zip|java[./]util[./]jar|com[./]fasterxml|MessageDigest|sun[./]security|Hashing|java[./]io|java[./]nio|GC|Compile")
    return {
        "file": path, "total_samples": total,
        "top_self": [[k, v, round(100 * v / total, 2)] for k, v in self_counts.most_common(limit)] if total else [],
        "top_inclusive_jvmd_and_libraries": [[k, v, round(100 * v / total, 2)] for k, v in inclusive.most_common(400) if interesting.search(k)][:limit] if total else [],
        "by_thread": [[k, v, round(100 * v / total, 2)] for k, v in by_thread.most_common(15)] if total else [],
    }


def stats(values):
    values = [v for v in values if v is not None]
    if not values:
        return None
    return {"n": len(values), "median": statistics.median(values), "min": min(values), "max": max(values), "values": values}


def main(root):
    out = {"root": root, "boots": [], "pairs": [], "profiles": []}
    for s in boots(root):
        out["boots"].append(boot_row(s))
    for path in sorted(glob.glob(os.path.join(root, "**", "pair.json"), recursive=True)):
        p = load(path)
        out["pairs"].append({"id": p["id"], "mode": p["mode"], "equivalence": p["equivalence"], "verdict": p["verdict"],
                             "runtime": p.get("runtime"), "runtime_vs_disk": p.get("runtime_vs_disk"),
                             "noninterference": (p.get("exports") or {}).get("cold", {}).get("noninterference"),
                             "export_ms": {k: (v or {}).get("export_ms") for k, v in (p.get("exports") or {}).items()}})
    for path in sorted(glob.glob(os.path.join(root, "**", "*.collapsed"), recursive=True)):
        out["profiles"].append(collapsed(path))
    for extra in ("controls.json", "shutdown/shutdown.json"):
        if os.path.exists(os.path.join(root, extra)):
            out[extra.split("/")[-1].replace(".json", "")] = load(os.path.join(root, extra))
    # Aggregates by scenario (cold/warm) across pairs.
    agg = {}
    for side in ("cold", "warm"):
        rows = [b for b in out["boots"] if (b["label"] or "").endswith("/" + side)]
        if rows:
            agg[side] = {k: stats([r[k] for r in rows]) for k in ("t_PRODUCT_READY", "t_DOMAIN_READY", "t_SESSION_CAPABLE", "cpu_user_ms",
                                                                    "cpu_system_ms", "allocated_bytes", "vm_hwm_bytes", "read_bytes", "write_bytes", "rchar", "wchar")}
            agg[side]["failures"] = [r["label"] for r in rows if not r["domain_ready"]]
    out["aggregate"] = agg
    with open(os.path.join(root, "reduction.json"), "w") as f:
        json.dump(out, f, indent=1)
    md = [f"## Boot reduction: {os.path.basename(root)}", ""]
    md.append("| boot | DOMAIN_READY ms | PRODUCT_READY ms | SESSION_CAPABLE ms | scan start ms | CPU user/sys ms | alloc MB | VmHWM MB | read/write MB | parsed/reused jars |")
    md.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    mb = lambda v: "n/a" if v is None else f"{v / 1e6:,.1f}"
    for b in out["boots"]:
        c = b["counters"]
        md.append(f"| {b['label']} | {fmt(b['t_DOMAIN_READY'])} | {fmt(b['t_PRODUCT_READY'])} | {fmt(b['t_SESSION_CAPABLE'])} | {fmt(b['t_SCAN_STARTED'])} | "
                  f"{fmt(b['cpu_user_ms'])}/{fmt(b['cpu_system_ms'])} | {mb(b['allocated_bytes'])} | {mb(b['vm_hwm_bytes'])} | "
                  f"{mb(b['read_bytes'])}/{mb(b['write_bytes'])} | {fmt(c.get('jar.parsed', 0))}/{fmt(c.get('jar.metadata_reuse', 0))} |")
    if out["pairs"]:
        md += ["", "| pair | outcome | exports | runtime | runtime vs disk |", "|---|---|---|---|---|"]
        for p in out["pairs"]:
            md.append(f"| {p['id']} | {p['equivalence']['outcome']} | {p['verdict']} | {p['runtime']} | {p['runtime_vs_disk']} |")
    for prof in out["profiles"]:
        md += ["", f"### {os.path.relpath(prof['file'], root)} ({prof['total_samples']:,} samples)", "", "| self frame | samples | % |", "|---|---:|---:|"]
        for k, v, pct in prof["top_self"][:12]:
            md.append(f"| `{k[:110]}` | {v:,} | {pct} |")
    with open(os.path.join(root, "reduction.md"), "w") as f:
        f.write("\n".join(md) + "\n")
    print("\n".join(md[:60]))


if __name__ == "__main__":
    main(sys.argv[1])
