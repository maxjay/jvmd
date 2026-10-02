#!/usr/bin/env python3
"""Aggregate every scenario's reduction into one compact JSON (aggregate.json) and print it to the job log
in fixed-size, numbered chunks between markers, so the evidence can be read where artifacts cannot be fetched.

usage: aggregate.py ROOT   (ROOT contains one directory per downloaded boot-<scenario> artifact)
"""
import glob
import json
import os
import sys

CHUNK = 3000


def compact_boot(b):
    keep = {k: v for k, v in b.items() if k != "counters"}
    keep["counters"] = b.get("counters")
    return keep


def load(path):
    with open(path) as f:
        return json.load(f)


def main(root):
    out = {"scenarios": {}}
    for reduction in sorted(glob.glob(os.path.join(root, "**", "reduction.json"), recursive=True)):
        r = load(reduction)
        name = os.path.basename(os.path.dirname(reduction))
        entry = {"boots": [compact_boot(b) for b in r.get("boots", [])], "pairs": r.get("pairs", []), "aggregate": r.get("aggregate"),
                 "profiles": [{"file": os.path.relpath(p["file"], os.path.dirname(reduction)), "total_samples": p["total_samples"],
                               "top_self": p["top_self"][:25], "top_inclusive": p["top_inclusive_jvmd_and_libraries"][:30],
                               "by_thread": p["by_thread"][:10]} for p in r.get("profiles", [])]}
        for extra in ("controls", "shutdown", "experiments"):
            if extra in r:
                entry[extra] = r[extra]
        out["scenarios"][name] = entry
    # Full per-boot summaries (status timings, repository counters) for the pairs scenario's first pair and for one profile pair.
    details = {}
    for path in sorted(glob.glob(os.path.join(root, "**", "summary.json"), recursive=True)):
        rel = os.path.relpath(path, root)
        if any(m in rel for m in ("/pair-1/", "/pair-2/", "/counters-1/", "/scale-", "/retention-1/", "/native-1/", "/experiments/", "/shutdown/", "c1-no-change")):
            s = load(path)
            details[rel] = {k: s.get(k) for k in ("t_ms", "counters", "in_flight", "index_status", "readiness", "exit_vs_ready", "io_at_domain_ready",
                                                   "io_post_ready_delta", "cpu_post_ready_delta", "rss_post_ready", "pss_post_ready", "allocated_bytes",
                                                   "runtime_dump_ms", "detection_lag_ms", "socket_owner", "identity", "discovery", "classpath_files",
                                                   "gc", "retention", "nmt", "store_files", "status_error", "session_capable", "storage_open_end",
                                                   "cpu_at_domain_ready", "peak_rss_bytes_at_domain_ready", "faults_at_domain_ready", "threads_at_domain_ready", "native_libraries", "aot_cache", "aot_log_head")}
            if "/pair-1/" in rel or "/counters-1/" in rel or "c1-no-change" in rel:
                details[rel]["series"] = s.get("series")
    out["details"] = details
    names = ("semantic-comparison.json", "warm-vs-clean.json", "warm-vs-original.json", "reference-vs-restart.json", "vs-cold.json", "graceful-vs-warm.json")
    for path in sorted(p for p in glob.glob(os.path.join(root, "**", "*.json"), recursive=True) if os.path.basename(p) in names):
        c = load(path)
        rel = os.path.relpath(path, root)
        out.setdefault("comparisons", {})[rel] = {"verdict": c["verdict"], "completeness": c["completeness"],
                                                   "families": {k: {kk: vv for kk, vv in v.items() if kk in ("status", "a_count", "b_count", "changed_count", "missing_in_b_count", "extra_in_b_count", "observed", "missing_in_b", "extra_in_b")} for k, v in c["families"].items()},
                                                   "runtime": {k: (v.get("status") if isinstance(v, dict) else v) for k, v in (c.get("runtime") or {}).items()},
                                                   "runtime_vs_disk": [c.get("runtime_vs_disk_a", {}).get("status"), c.get("runtime_vs_disk_b", {}).get("status")]}
    for path in sorted(glob.glob(os.path.join(root, "**", "input-manifest*.json"), recursive=True)):
        m = load(path)
        out.setdefault("inputs", {})[os.path.relpath(path, root)] = {k: v for k, v in m.items() if k != "files"}
    for path in sorted(glob.glob(os.path.join(root, "**", "*-export.json"), recursive=True))[:200]:
        e = load(path)
        rel = os.path.relpath(path, root)
        fam = e.get("families", {})
        out.setdefault("exports", {})[rel] = {"physical": {"total_bytes": e["physical"]["total_bytes"], "by_category": e["physical"]["by_category"]},
                                              "migration": e.get("migration"), "family_digests": e.get("family_digests"), "other_stores": e.get("other_stores"),
                                              "counts": {k: (len(v) if isinstance(v, (dict, list)) else v) for k, v in fam.items()},
                                              "prefix_counts": fam.get("metadata.key_prefix_counts"), "export_ms": e.get("export_ms")}
    for path in sorted(glob.glob(os.path.join(root, "**", "build.json"), recursive=True))[:1]:
        with open(path) as f:
            out["build"] = f.read()
    for path in sorted(glob.glob(os.path.join(root, "**", "environment.txt"), recursive=True))[:2]:
        with open(path) as f:
            out.setdefault("environment", {})[os.path.relpath(path, root)] = f.read()[:4000]
    text = json.dumps(out, separators=(",", ":"), default=str)
    with open(os.path.join(root, "aggregate.json"), "w") as f:
        f.write(text)
    chunks = [text[i:i + CHUNK] for i in range(0, len(text), CHUNK)]
    print(f"AGGREGATE-BEGIN {len(text)} bytes {len(chunks)} chunks")
    for i, chunk in enumerate(chunks):
        print(f"AGG|{i:05d}|{chunk}")
    print("AGGREGATE-END")


if __name__ == "__main__":
    main(sys.argv[1])
