#!/usr/bin/env python3
"""Builds the report's tables from the per-run summaries written by analyze.py (and mat.sh).

  synthesize.py SUMMARY_DIR --out benchmarks/memory/results

Writes results/summary.json (every number the report quotes, small) and results/tables.md.
Run groups: primary = default configuration; supplementary ("b256") = RocksDB native budget 256 MiB,
first-use references skipped. Each table names the runs it draws from.
"""
import argparse, collections, csv, glob, gzip, json, os, re, statistics
from pathlib import Path

MB = 1_000_000  # decimal megabytes throughout the report
PRIMARY_CONTROL = ["control-2", "control-3"]
SUPP_CONTROL = ["b256-control", "b256-control-2"]


def load(summary, run, name="lifecycle.json"):
    p = Path(summary) / run / name
    return json.load(open(p)) if p.exists() else None


def marks_by_id(lc, inc=None):
    out = {}
    for m in lc["marks"]:
        if inc is None or m["incarnation"] == inc:
            out.setdefault(m["id"], m)
    return out


def mb(v, nd=1):
    return None if v is None else round(v / MB, nd)


def med(xs):
    xs = [x for x in xs if x is not None]
    return statistics.median(xs) if xs else None


def pct(xs, p):
    xs = sorted(x for x in xs if x is not None)
    if not xs:
        return None
    k = (len(xs) - 1) * p / 100
    f = int(k)
    return xs[f] if f + 1 >= len(xs) else xs[f] + (xs[f + 1] - xs[f]) * (k - f)


PHASES = [  # (label, from-mark, to-mark) of the lifecycle allocation table
    ("JVM start to probe (M0..M1)", "M0", "M1"),
    ("machine seed (M1..M3)", "M1", "M3"),
    ("idle after READY (M3..M4)", "M3", "M4"),
    ("workspace open: session.open incl. Maven resolution (M5-..M6)", "M5-", "M6"),
    ("documents opened (M6..M8)", "M6", "M8"),
    ("diagnostic/semantic admission (M8..M9)", "M8", "M9"),
    ("first completion (M9..M10)", "M9", "M10"),
    ("warm completion x102 (M10..M11)", "M10", "M11"),
    ("completionItem/resolve first", "M11r-", "M11r-first"),
    ("first definition (M11r-warm..M12)", "M11r-warm", "M12"),
    ("warm definition x102", "M12", "M12w"),
    ("first hover", "M12w", "M13"),
    ("warm hover x102", "M13", "M13w"),
    ("first-use references (M13w..M14)", "M13w", "M14"),
    ("documentSymbol + semanticTokens + signatureHelp first", "M14w", "M14x"),
    ("body-only edit admitted (M14x..M15)", "M14x", "M15"),
    ("completion after body edit", "M15", "M15q"),
    ("relevant API edit (M15q..M16)", "M15q", "M16"),
    ("open unrelated document", "M16", "M16u-"),
    ("unrelated API edit", "M16u-", "M16u"),
    ("source file added", "M16u", "M16a"),
    ("source file removed", "M16a", "M16r"),
    ("POM dependency added (M16r..M17)", "M16r", "M17"),
    ("POM reverted", "M17", "M17r"),
    ("documents closed", "M17r", "M18"),
    ("reconnect to retained session (M18b..M20)", "M18b", "M20"),
    ("session.close (M20..M19)", "M20", "M19"),
    ("reopen after close (M19s..M20b)", "M19s", "M20b"),
    ("restart: JVM start to READY (M23..M24)", "M23", "M24"),
    ("restart: workspace reopen (M24i..M25)", "M24i", "M25"),
    ("restart: first correct definition (M25..M26)", "M25", "M26"),
    ("restart: first completion (M26..M26b)", "M26", "M26b"),
]


def phase_window(lc, a, b):
    """Exact whole-JVM allocation and wall time between two marks of the same incarnation. Wall time excludes
    checkpoint hooks (histograms, dumps, profiler restarts); allocation is the raw counter difference."""
    ms = lc["marks"]
    ia = next((i for i, m in enumerate(ms) if m["id"] == a), None)
    ib = next((i for i, m in enumerate(ms) if m["id"] == b), None)
    if ia is None or ib is None or ib <= ia or ms[ia]["incarnation"] != ms[ib]["incarnation"]:
        return None
    secs, peak = 0.0, 0
    for m in ms[ia + 1: ib + 1]:
        ph = m.get("phase")
        if not ph:
            return None
        secs += ph["seconds"]; peak = max(peak, ph.get("peakHeapUsed") or 0)
    # Allocation is the raw counter difference between the two checkpoint snapshots. It includes the work
    # other threads did while intermediate checkpoints were observed (during seed a status call can block for
    # up to ~0.8 s while publication continues) and each intermediate observation's own ~0.9 MB.
    if ms[ia].get("allocated") is None or ms[ib].get("allocated") is None:
        return None
    return {"allocated": ms[ib]["allocated"] - ms[ia]["allocated"], "seconds": secs, "peakHeapUsed": peak}


def lifecycle_table(summary):
    rows = []
    groups = {"primary": PRIMARY_CONTROL, "supplementary": SUPP_CONTROL}
    exact = {"primary": "exact-1", "supplementary": "b256-exact"}
    for label, a, b in PHASES:
        row = {"phase": label, "from": a, "to": b}
        for g, runs in groups.items():
            ws = [w for w in (phase_window(load(summary, r), a, b) for r in runs if load(summary, r)) if w]
            row[g] = {"runs": len(ws), "allocatedMB": [mb(w["allocated"]) for w in ws], "seconds": [round(w["seconds"], 2) for w in ws],
                      "peakHeapUsedMB": [mb(w["peakHeapUsed"]) for w in ws]}
            ex = load(summary, exact[g], "samples.json")
            lc = load(summary, exact[g])
            if ex and lc:
                ids = [m["id"] for m in lc["marks"]]
                if a in ids and b in ids:
                    span = set(ids[ids.index(a) + 1: ids.index(b) + 1])
                    win = [s for s in ex if s["to"] in span]
                    if win:
                        row[g]["exactRun"] = {"peakRssMB": mb(max(s["peakRss"] or 0 for s in win)),
                                              "peakHeapUsedMB": mb(max(s["peakHeapUsed"] or 0 for s in win)),
                                              "peakHeapCommittedMB": mb(max(s["peakHeapCommitted"] or 0 for s in win))}
        rows.append(row)
    return rows


STATES = [  # (label, mark id) for stable-state tables
    ("machine READY, idle (M4)", "M4"),
    ("workspace admitted (M9)", "M9"),
    ("warm editor, after queries (M14x)", "M14x"),
    ("after mutations (M17r)", "M17r"),
    ("documents closed (M18)", "M18"),
    ("reconnected (M20)", "M20"),
    ("session closed, settled (M19s)", "M19s"),
    ("reopened after close (M20b)", "M20b"),
    ("all sessions closed, before shutdown (M20bx)", "M20bx"),
    ("restarted, READY idle (M24i)", "M24i"),
    ("restarted, workspace + first queries (M26b)", "M26b"),
]


def stable_table(summary):
    rows = []
    for label, mid in STATES:
        row = {"state": label, "mark": mid}
        for g, runs, ret in (("primary", PRIMARY_CONTROL, "retention-1"), ("supplementary", SUPP_CONTROL, "b256-retention")):
            ms = [marks_by_id(load(summary, r)).get(mid) for r in runs if load(summary, r)]
            ms = [m for m in ms if m and m.get("VmRSS")]
            rl = load(summary, ret)
            rm = marks_by_id(rl).get(mid) if rl else None
            row[g] = {
                "runs": len(ms),
                "liveHeapAfterFullGcMB": mb(rm.get("liveHeapAfterFullGc")) if rm else None,
                "heapUsedMB": [mb(m["heapUsed"]) for m in ms], "heapCommittedMB": [mb(m["heapCommitted"]) for m in ms],
                "rssMB": [mb(m["VmRSS"]) for m in ms], "pssMB": [mb(m["Pss"]) for m in ms],
                "rssAnonMB": [mb(m["RssAnon"]) for m in ms], "rssFileMB": [mb(m["RssFile"]) for m in ms],
                "rocksCacheMB": [mb((m.get("rocks") or {}).get("cache_usage_bytes")) for m in ms],
                "rocksPinnedMB": [mb((m.get("rocks") or {}).get("cache_pinned_bytes")) for m in ms],
                "rocksBudgetMB": [mb((m.get("rocks") or {}).get("cache_and_memtable_budget_bytes")) for m in ms],
                "direct": [(m.get("direct") or {}).get("count") for m in ms], "directMB": [mb((m.get("direct") or {}).get("capacity")) for m in ms],
                "mapped": [(m.get("mapped") or {}).get("count") for m in ms], "mappedMB": [mb((m.get("mapped") or {}).get("capacity")) for m in ms],
                "metaspaceMB": [mb(m.get("metaspaceUsed")) for m in ms], "codeCacheMB": [mb(m.get("codeCacheUsed")) for m in ms],
                "threads": [m.get("Threads") for m in ms], "classes": [m.get("classes") for m in ms],
            }
        rows.append(row)
    return rows


def query_table(summary, runs):
    by = collections.defaultdict(list)
    for r in runs:
        lc = load(summary, r)
        if not lc:
            continue
        for op in lc["operations"] or []:
            if op.get("outcome") == "pass" and op.get("allocatedBytes") is not None:
                by[(op["method"], op["state"])].append(op)
    out = []
    for (method, state), ops in sorted(by.items()):
        a = [o["allocatedBytes"] for o in ops]
        l = [o["latencyMs"] for o in ops if o.get("latencyMs") is not None]
        out.append({"method": method, "state": state, "n": len(ops), "allocMedianMB": mb(med(a), 2), "allocP95MB": mb(pct(a, 95), 2),
                    "allocMinMB": mb(min(a), 2), "allocMaxMB": mb(max(a), 2), "latencyMedianMs": round(med(l), 1) if l else None,
                    "latencyP95Ms": round(pct(l, 95), 1) if l else None})
    return out


def completion_sizes(summary, runs):
    """Candidates returned per warm completion (from the raw journal is not kept; the steady loop answers the same probe)."""
    return None


def overhead_table(summary):
    """Wall time of representative phases per profiling mode against the unprofiled control (same configuration)."""
    reps = [("machine seed", "M1", "M3"), ("workspace open", "M5-", "M6"), ("admission", "M8", "M9a"),
            ("warm completion x102", "M10", "M11"), ("warm hover x102", "M13", "M13w"), ("POM edit", "M16r", "M17")]
    out = []
    for cfg, control, modes in (("primary", PRIMARY_CONTROL, ["exact-1", "alloc-1", "live-1", "retention-1", "nmt-1", "nmt-2", "native-1", "rss-1"]),
                                ("supplementary", SUPP_CONTROL, ["b256-exact", "b256-alloc", "b256-live", "b256-retention", "b256-nmt", "b256-native", "b256-rss"])):
        for label, a, b in reps:
            base = [w["seconds"] for w in (phase_window(load(summary, r), a, b) for r in control if load(summary, r)) if w]
            row = {"config": cfg, "phase": label, "control_s": [round(x, 2) for x in base]}
            for m in modes:
                lc = load(summary, m)
                w = phase_window(lc, a, b) if lc else None
                row[m] = round(w["seconds"], 2) if w else None
                row[m + "_allocMB"] = mb(w["allocated"]) if w else None
            out.append(row)
    return out


def scale_table(summary):
    rows = []
    for r in ["scale-empty", "scale-n100", "scale-n250", "scale-n350", "scale-full", "jar-one-javax.inject-1", "jar-one-commons-lang3-3.20.0",
              "jar-one-guava-33.4.8-jre", "jar-one-groovy-4.0.15"]:
        lc = load(summary, r)
        if not lc:
            continue
        ms = marks_by_id(lc, 1)
        m1, m3, s1 = ms["M1"], ms["M3"], ms.get("S1")
        env = lc["environment"]
        w = phase_window(lc, "M1", "M3")
        samples = load(summary, r, "samples.json") or []
        rows.append({"run": r, "jars": env["repository"]["jars"], "jarBytes": env["repository"]["bytes"],
                     "artifacts": m3["index"]["artifacts"], "symbols": m3["index"]["symbols"], "edges": m3["index"]["edges"],
                     "seedAllocated": w["allocated"], "seedSeconds": round(w["seconds"], 2),
                     "liveHeapAfterFullGc": s1.get("liveHeapAfterFullGc") if s1 else None,
                     "restartLiveHeapAfterFullGc": (marks_by_id(lc).get("S2") or {}).get("liveHeapAfterFullGc"),
                     "persistedBytes": (m3.get("persisted") or {}).get("total"), "persistedDb": (m3.get("persisted") or {}).get("index/db"),
                     "persisted": m3.get("persisted"), "rocksCache": (m3.get("rocks") or {}).get("cache_usage_bytes"),
                     "rocksPinned": (m3.get("rocks") or {}).get("cache_pinned_bytes"),
                     "peakHeapUsed": max((s["peakHeapUsed"] or 0) for s in samples) if samples else None,
                     "peakRss": max((s["peakRss"] or 0) for s in samples) if samples else None,
                     "rssAtReady": m3.get("VmRSS"), "seedGcCount": None})
    for row in rows:
        s = row["symbols"] or 0
        row["allocPerSymbol"] = row["seedAllocated"] / s if s else None
        row["allocPerArtifact"] = row["seedAllocated"] / row["artifacts"] if row["artifacts"] else None
        row["allocAmplification"] = row["seedAllocated"] / row["persistedDb"] if row["persistedDb"] else None
        row["persistedPerSymbol"] = row["persistedDb"] / s if s else None
    return rows


def fit(xs, ys):
    n = len(xs)
    mx, my = sum(xs) / n, sum(ys) / n
    sxx = sum((x - mx) ** 2 for x in xs)
    slope = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sxx
    icpt = my - slope * mx
    ss_res = sum((y - (icpt + slope * x)) ** 2 for x, y in zip(xs, ys))
    ss_tot = sum((y - my) ** 2 for y in ys)
    return {"slope": slope, "intercept": icpt, "r2": 1 - ss_res / ss_tot if ss_tot else None}


def alloc_phase_rollups(summary, run, groups, top50=False):
    ph = load(summary, run, "phases.json")
    if not ph:
        return None
    out = {}
    for label, ids in groups.items():
        sel = [p for p in ph if p["to"] in ids and "error" not in p]
        if not sel:
            continue
        tot = sum(p["bytes"] for p in sel)
        exact = sum((p.get("exactAllocatedBytes") or 0) for p in sel)
        agg = {}
        keys = ("top_classes_by_bytes", "top_classes_by_samples", "top_stacks_by_bytes") if top50 else (
            "mechanism", "operation", "library_owner", "innermost_owner", "threads", "top_classes_by_bytes", "top_classes_by_samples",
            "top_stacks_by_bytes", "outside_tlab_classes", "outside_tlab_stacks", "mechanism_class")
        for key in keys:
            c = collections.Counter()
            for p in sel:
                for r in p.get(key, []):
                    c[r["key"]] += r["value"]
            n = 50 if top50 else 12
            agg[key] = [{"key": k if len(k) < 360 else "…" + k[-359:], "value": v, "share": round(v / tot, 4) if tot and "samples" not in key else None}
                        for k, v in c.most_common(n)]
        site = collections.defaultdict(collections.Counter)
        for p in sel:
            for k, rows in (p.get("mechanism_top_site") or {}).items():
                for r in rows:
                    site[k][r["key"]] += r["value"]
        agg["mechanism_top_site"] = {k: v.most_common(1)[0] for k, v in site.items()}
        out[label] = {"sampledBytes": tot, "exactAllocatedBytes": exact, "samples": sum(p["samples"] for p in sel), **agg}
    return out


ALLOC_GROUPS = {
    "machine seed": ["M2-0", "M2-10", "M2-25", "M2-50", "M2-75", "M2-100", "M3"],
    "workspace open (session.open)": ["M6"],
    "admission (documents opened -> settled)": ["M8", "M9a", "M9"],
    "first completion": ["M10"],
    "warm completion x102": ["M11"],
    "prefix project. warm x32": ["M11p-dot-warm"], "prefix project.g warm x32": ["M11p-g-warm"],
    "prefix project.get warm x32": ["M11p-get-warm"], "prefix project.getM warm x32": ["M11p-getM-warm"],
    "completionItem/resolve warm x102": ["M11r-warm"],
    "first definition": ["M12"], "warm definition x102": ["M12w"], "first hover": ["M13"], "warm hover x102": ["M13w"],
    "first-use references": ["M14"],
    "body-only edit": ["M15", "M15q"], "relevant API edit": ["M16"], "unrelated API edit": ["M16u-", "M16u"],
    "source add/remove": ["M16a", "M16r"], "POM dependency add": ["M17"], "POM revert": ["M17r"],
    "reconnect (retained session)": ["M20-init", "M20"], "reopen after close": ["M20b"],
    "restart to READY": ["M23", "M23b", "M24"], "restart workspace reopen": ["M25"], "restart first queries": ["M26", "M26b"],
}


def native_rollups(summary, run, groups):
    ph = load(summary, run, "phases.json")
    if not ph:
        return None
    out = {}
    for label, ids in groups.items():
        sel = [p for p in ph if p["to"] in ids and "error" not in p and p.get("total")]
        if not sel:
            continue
        res = {}
        for kind in ("total", "unfreed"):
            agg = {"bytes": sum(p[kind]["bytes"] for p in sel)}
            for key in ("owners", "rocks_categories", "top_stacks", "threads"):
                c = collections.Counter()
                for p in sel:
                    for r in p[kind].get(key, []):
                        c[r["key"]] += r["value"]
                agg[key] = [{"key": k, "value": v} for k, v in c.most_common(8)]
            res[kind] = agg
        out[label] = res
    return out


def nmt_table(summary, run):
    d = load(summary, run, "nmt.json")
    if not d:
        return None
    out = {}
    for name, cats in d.items():
        if "-summary.txt" not in name or ".diff" in name:
            continue
        mid = re.sub(r"^i\d+-\d+-", "", name.replace("-summary.txt", ""))
        inc = int(re.match(r"i(\d+)", name).group(1))
        # NMT reports KiB; converted to decimal kB so every report table uses decimal units.
        out[f"i{inc}:{mid}"] = {k: v.get("committed_kb") * 1.024 for k, v in cats.items() if isinstance(v, dict) and "committed_kb" in v}
        if "_malloc_mmap" in cats:
            out[f"i{inc}:{mid}"]["_malloc_kb"] = cats["_malloc_mmap"]["malloc_kb"]
            out[f"i{inc}:{mid}"]["_mmap_committed_kb"] = cats["_malloc_mmap"]["mmap_committed_kb"]
    return out


def malloc_info(run_dir):
    out = {}
    for f in sorted(glob.glob(str(Path(run_dir) / "nmt" / "*-malloc_info.xml"))):
        t = open(f).read()
        tail = t[t.rfind("</heap>"):]
        g = lambda rx: re.search(rx, tail)
        fast, rest, cur, mm = (g(r'<total type="fast" count="\d+" size="(\d+)"'), g(r'<total type="rest" count="\d+" size="(\d+)"'),
                               g(r'<system type="current" size="(\d+)"'), g(r'<total type="mmap" count="\d+" size="(\d+)"'))
        if cur:
            mid = re.sub(r"^i\d+-\d+-", "", Path(f).name.replace("-malloc_info.xml", ""))
            inc = re.match(r"i(\d+)", Path(f).name).group(1)
            free = int(fast[1]) + int(rest[1])
            out[f"i{inc}:{mid}"] = {"systemMB": mb(int(cur[1])), "freeInArenasMB": mb(free), "inUseMB": mb(int(cur[1]) - free), "mmapChunksMB": mb(int(mm[1]))}
    return out


def histograms(summary, run, ids, n=25):
    d = load(summary, run, "histograms.json")
    if not d:
        return None
    out = {}
    for k, v in d.items():
        mid = re.sub(r"^i\d+-\d+-", "", k)
        inc = re.match(r"i(\d+)", k).group(1)
        if mid in ids:
            out[f"i{inc}:{mid}"] = {"total_instances": v["total_instances"], "total_bytes": v["total_bytes"], "top": v["top"][:n]}
    return out


def mat_reports(mat_dir):
    out = {}
    for d in sorted(Path(mat_dir).glob("*")):
        rep = {}
        for f in sorted(d.glob("*.csv")):
            rows = list(csv.reader(open(f, encoding="utf-8", errors="replace")))
            rep[f.stem] = rows[:25]
        out[d.name] = rep
    return out


def smaps_states(summary, run, ids):
    d = load(summary, run, "smaps_split.json")
    if not d:
        return None
    out = {}
    for k, v in d.items():
        mid = re.sub(r"^i\d+-\d+-", "", k)
        inc = re.match(r"i(\d+)", k).group(1)
        if mid in ids:
            out[f"i{inc}:{mid}"] = {c: {"rssMB": mb(x["Rss"]), "pssMB": mb(x["Pss"]), "sizeMB": mb(x["Size"]), "privateDirtyMB": mb(x["Private_Dirty"])} for c, x in v.items()}
    return out


def smaps_categories(summary, run, ids):
    lc = load(summary, run)
    out = {}
    for m in lc["marks"] if lc else []:
        if m["id"] in ids and m.get("smaps"):
            out[f"i{m['incarnation']}:{m['id']}"] = {
                "categories": {c: {"rssMB": mb(v["Rss"]), "pssMB": mb(v["Pss"]), "sizeMB": mb(v["Size"]), "privateDirtyMB": mb(v["Private_Dirty"]),
                                   "privateCleanMB": mb(v["Private_Clean"]), "sharedCleanMB": mb(v["Shared_Clean"])} for c, v in m["smaps"]["categories"].items()},
                "topFiles": [{"name": f["name"], "rssMB": mb(f["Rss"])} for f in m["smaps"]["topFiles"][:12]]}
    return out


def stages(summary, run):
    d = load(summary, run, "jfr.json")
    if not d:
        return None
    by = collections.defaultdict(lambda: collections.Counter())
    for r in d["stages"]:
        k = r["stage"]
        for f in ("count", "durationMs", "allocatedBytes", "allocationKnown", "virtual", "queued", "cpuMs"):
            by[k][f] += r[f]
    jfr = d.get("jfr_allocation_samples") or {}
    return {"by_stage": {k: dict(v) for k, v in sorted(by.items(), key=lambda kv: -kv[1]["allocatedBytes"])},
            "jfr_allocation_samples_top10": {ph: v[:10] for ph, v in jfr.items()}}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("summary"); ap.add_argument("--out", required=True); ap.add_argument("--runs", default="/home/user/work/runs")
    a = ap.parse_args()
    S = a.summary
    res = {}
    res["environment"] = {r: load(S, r)["environment"] for r in ["control-2", "b256-control"] if load(S, r)}
    res["lifecycle"] = lifecycle_table(S)
    res["stable"] = stable_table(S)
    res["queries_primary"] = query_table(S, PRIMARY_CONTROL)
    res["queries_supplementary"] = query_table(S, SUPP_CONTROL)
    res["queries_by_mode"] = {r: query_table(S, [r]) for r in ["exact-1", "alloc-1", "nmt-1", "native-1", "b256-exact", "b256-alloc", "b256-nmt", "b256-native", "b256-pressure-512"]}
    res["overhead"] = overhead_table(S)
    sc = scale_table(S)
    res["scale"] = sc
    full = [r for r in sc if r["run"].startswith("scale-")]
    res["scale_fits"] = {
        "seedAllocated~symbols": fit([r["symbols"] for r in full], [r["seedAllocated"] for r in full]),
        "seedAllocated~artifacts": fit([r["artifacts"] for r in full], [r["seedAllocated"] for r in full]),
        "seedAllocated~edges": fit([r["edges"] for r in full], [r["seedAllocated"] for r in full]),
        "persistedDb~symbols": fit([r["symbols"] for r in full], [r["persistedDb"] for r in full]),
        "liveHeap~symbols": fit([r["symbols"] for r in full], [r["liveHeapAfterFullGc"] for r in full]),
        "liveHeap~artifacts": fit([r["artifacts"] for r in full], [r["liveHeapAfterFullGc"] for r in full]),
        "rocksCache~artifacts": fit([r["artifacts"] for r in full], [r["rocksCache"] for r in full]),
        "seedSeconds~symbols": fit([r["symbols"] for r in full], [r["seedSeconds"] for r in full]),
    }
    res["alloc"] = {r: alloc_phase_rollups(S, r, ALLOC_GROUPS) for r in ["b256-alloc", "b256-refs-alloc"]}
    live = {r: alloc_phase_rollups(S, r, ALLOC_GROUPS) for r in ["live-1", "b256-live"]}
    res["live"] = {r: {g: {"sampledBytes": v["sampledBytes"], "exactAllocatedBytes": v["exactAllocatedBytes"], "mechanism": v["mechanism"][:6],
                           "top_classes_by_bytes": v["top_classes_by_bytes"][:6]} for g, v in (x or {}).items()} for r, x in live.items()}
    top50 = {r: alloc_phase_rollups(S, r, ALLOC_GROUPS, top50=True) for r in ["b256-alloc", "b256-refs-alloc"]}
    top50["b256-refs-alloc"] = {k: v for k, v in top50["b256-refs-alloc"].items() if k == "first-use references"}
    res["native"] = {r: native_rollups(S, r, {**ALLOC_GROUPS, "seed-only restart": ["S2"], "restart attempt (failed)": ["M3-failed", "M24-failed"]})
                     for r in ["native-1", "b256-native", "scale-full-native", "restartfail-native"]}
    res["nmt"] = {r: nmt_table(S, r) for r in ["nmt-1", "nmt-2", "b256-nmt", "scale-full-nmt", "restartfail-nmt"]}
    res["malloc_info"] = {r: malloc_info(Path(a.runs) / r) for r in ["nmt-2", "b256-nmt", "scale-full-nmt"]}
    hist_ids = {"M4", "M9", "M14x", "M14", "M17r", "M19s", "M20", "M20bx", "M24i", "M26b", "S1"}
    res["histograms"] = {r: histograms(S, r, hist_ids) for r in ["b256-retention", "retention-1", "b256-refs-retention", "scale-full"]}
    smap_ids = {"M1", "M2-25", "M3", "M4", "M6", "M9", "M14", "M14x", "M17r", "M19s", "M20bx", "M24i", "M26b"}
    res["smaps_split"] = {r: smaps_states(S, r, smap_ids) for r in ["rss-1", "b256-rss"]}
    res["smaps_categories"] = {r: smaps_categories(S, r, smap_ids) for r in ["rss-1", "b256-rss"]}
    res["stages"] = {r: stages(S, r) for r in ["b256-exact", "exact-1"]}
    res["humongous"] = (load(S, "b256-humongous", "humongous.json") or [])[:40]
    res["gc"] = {r: load(S, r, "gc.json") for r in ["b256-exact", "exact-1", "b256-pressure-512", "scale-full"]}
    res["mat"] = mat_reports(Path(S) / "mat")
    drill = {}
    for dd in sorted((Path(S) / "mat-drill").glob("*")):
        entry = {}
        for f in sorted(dd.glob("*.csv")):
            rows = list(csv.reader(open(f, encoding="utf-8", errors="replace")))
            if f.stem.startswith("retained_"):
                entry[f.stem] = {"retainedBytes": sum(int(r[2]) for r in rows[1:] if len(r) > 2 and r[2].isdigit()),
                                 "top": rows[1:8]}
            else:
                entry[f.stem] = rows[:15]
        drill[dd.name] = entry
    res["mat_drill"] = drill
    for extra in ("prefix_sizes.json", "seed_sites.json", "scale_rows.json"):
        p = Path(a.runs).parent / extra
        if p.exists():
            res[extra.replace(".json", "")] = json.load(open(p))
    res["failures"] = {r: (load(S, r) or {}).get("failures") for r in sorted(os.listdir(S)) if r != "mat"}
    Path(a.out).mkdir(parents=True, exist_ok=True)
    json.dump(res, open(Path(a.out) / "summary.json", "w"), separators=(",", ":"), default=str)
    json.dump(top50, open(Path(a.out) / "allocation-top50.json", "w"), separators=(",", ":"))
    print("wrote", Path(a.out) / "summary.json", os.path.getsize(Path(a.out) / "summary.json") // 1024, "KiB")


if __name__ == "__main__":
    main()
