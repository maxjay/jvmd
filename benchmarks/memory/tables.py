#!/usr/bin/env python3
"""Renders the report's main tables from results/summary.json (decimal MB/GB throughout).

  tables.py benchmarks/memory/results/summary.json > tables.md

Each table is preceded by an HTML comment naming it, so the report can be regenerated table by table.
"""
import json, sys

d = json.load(open(sys.argv[1]))
MIB = 1.048576  # G1 log values are MiB


def f(v, nd=0):
    if v is None:
        return "—"
    if isinstance(v, (int, float)):
        return f"{v:,.{nd}f}".replace(",", " ")
    return str(v)


def rng(xs, nd=0):
    xs = [x for x in (xs or []) if x is not None]
    if not xs:
        return "—"
    lo, hi = min(xs), max(xs)
    return f(lo, nd) if f(lo, nd) == f(hi, nd) else f"{f(lo, nd)}–{f(hi, nd)}"


def both(xs, nd=0):
    xs = [x for x in (xs or []) if x is not None]
    return " / ".join(f(x, nd) for x in xs) if xs else "—"


out = []
emit = out.append

# ------------------------------------------------------------------ stable memory
emit("<!-- table:stable -->")
emit("| State | Live heap (post-GC) | Heap used / committed | RSS | PSS | RSS anon / file | RocksDB cache / pinned | Direct / mapped |")
emit("|---|---:|---:|---:|---:|---:|---:|---:|")
for r in d["stable"]:
    s, p = r["supplementary"], r["primary"]
    live = f(s["liveHeapAfterFullGcMB"], 1) + " MB" if s["liveHeapAfterFullGcMB"] is not None else "—"
    if p.get("liveHeapAfterFullGcMB") not in (None, s["liveHeapAfterFullGcMB"]):
        live += f" (primary {f(p['liveHeapAfterFullGcMB'], 0)})"
    rss = both(s["rssMB"]) + (f" (primary {both(p['rssMB'])})" if p["rssMB"] else "")
    rocks = f"{rng(s['rocksCacheMB'], 1)} / {rng(s['rocksPinnedMB'], 1)}"
    if p["rocksCacheMB"]:
        rocks += f" (primary {rng(p['rocksCacheMB'], 1)} / {rng(p['rocksPinnedMB'], 1)})"
    dm = f"{rng(s['directMB'])} / {rng(s['mappedMB'])}"
    emit(f"| {r['state']} | {live} | {rng(s['heapUsedMB'])} / {rng(s['heapCommittedMB'])} | {rss} | {both(s['pssMB'])} | "
         f"{rng(s['rssAnonMB'])} / {rng(s['rssFileMB'])} | {rocks} | {dm} |")
emit("")

# ------------------------------------------------------------------ allocation by phase
emit("<!-- table:allocation -->")
emit("| Phase | Primary alloc (MB) | Supp. alloc (MB) | Wall (s, primary / supp.) | Peak heap used (MB) | Peak RSS (MB) |")
emit("|---|---:|---:|---:|---:|---:|")
after_refs = False
for r in d["lifecycle"]:
    p, s = r["primary"], r["supplementary"]
    if after_refs and r["phase"] != "first-use references (M13w..M14)":
        # Every primary lifecycle past references is a degraded (post-OOM or stalled) daemon: not reported.
        p = {"allocatedMB": [], "seconds": [], "exactRun": None}
    after_refs = after_refs or r["phase"].startswith("first-use references")
    pe, se = p.get("exactRun") or {}, s.get("exactRun") or {}
    secs = [x for x in (p["seconds"][:1] + s["seconds"][:1])]
    peakh = [x for x in (pe.get("peakHeapUsedMB"), se.get("peakHeapUsedMB")) if x]
    peakr = [x for x in (pe.get("peakRssMB"), se.get("peakRssMB")) if x]
    if not p["allocatedMB"] and not s["allocatedMB"]:
        continue
    emit(f"| {r['phase']} | {both(p['allocatedMB'])} | {both(s['allocatedMB'])} | {both(secs, 2)} | {rng(peakh)} | {rng(peakr)} |")
emit("")

# ------------------------------------------------------------------ GC
emit("<!-- table:gc -->")
emit("| Phase (supplementary `exact`; references from primary `exact-1`) | GC pauses | Young | Mixed | Full | Pause (ms) | Heap after GC, max (MB) | Humongous regions, max | Concurrent cycles |")
emit("|---|---:|---:|---:|---:|---:|---:|---:|---:|")
for run in ("b256-exact", "exact-1"):
    for g in d["gc"][run] or []:
        if not g["pauses"]:
            continue
        if run == "exact-1" and not (g["full"] or g["pauseMs"] > 2000):
            continue
        emit(f"| i{g['incarnation']} {g['phase']}{' (primary)' if run == 'exact-1' else ''} | {g['pauses']} | {g['young']} | {g['mixed']} | {g['full']} | "
             f"{f(g['pauseMs'])} | {f(g['heapAfterMaxM'] * MIB)} | {g['humongousRegionsMax']} | {g['concurrentCycles']} |")
emit("")

# ------------------------------------------------------------------ NMT
cats = ["Total", "Java Heap", "Class", "Metaspace", "Thread", "Code", "GC", "Internal", "Symbol", "Arena Chunk", "Native Memory Tracking"]
emit("<!-- table:nmt -->")
emit("| Run / state | " + " | ".join(c.replace("Native Memory Tracking", "NMT itself") for c in cats) + " |")
emit("|---|" + "---:|" * len(cats))
for run in ("nmt-2", "b256-nmt"):
    N = d["nmt"][run] or {}
    for k in ("i1:M1", "i1:M3", "i1:M4", "i1:M6", "i1:M9", "i1:M14", "i1:M14x", "i1:M17r", "i1:M19s", "i1:M20bx", "i2:M24i", "i2:M26b"):
        if k in N:
            emit(f"| {run} {k} | " + " | ".join(f((N[k].get(c) or 0) / 1000, 1) for c in cats) + " |")
emit("")

# ------------------------------------------------------------------ malloc_info
emit("<!-- table:malloc -->")
emit("| Run / state | glibc obtained from OS (MB) | Free inside arenas (MB) | In use (MB) |")
emit("|---|---:|---:|---:|")
for run in ("nmt-2", "b256-nmt"):
    M = d["malloc_info"].get(run) or {}
    for k in ("i1:M1", "i1:M3", "i1:M4", "i1:M6", "i1:M9", "i1:M14", "i1:M14x", "i1:M17r", "i1:M19s", "i1:M20bx", "i2:M24i", "i2:M26b"):
        if k in M:
            v = M[k]
            emit(f"| {run} {k} | {f(v['systemMB'], 1)} | {f(v['freeInArenasMB'], 1)} | {f(v['inUseMB'], 1)} |")
emit("")

# ------------------------------------------------------------------ RSS split
order = ["Java heap", "glibc malloc (brk heap + arenas)", "JIT code / executable anonymous", "thread stacks (approx.)",
         "other anonymous (metaspace, GC data, NMT, JNI, ...)", "file-backed"]
emit("<!-- table:rss -->")
emit("| Run / state | RSS (MB) | Java heap | glibc arenas + brk | JIT code | Thread stacks | Other anonymous | File-backed |")
emit("|---|---:|---:|---:|---:|---:|---:|---:|")
for run in ("rss-1", "b256-rss"):
    S = d["smaps_split"].get(run) or {}
    for k in ("i1:M3", "i1:M4", "i1:M9", "i1:M14", "i1:M14x", "i1:M17r", "i1:M19s", "i1:M20bx", "i2:M24i", "i2:M26b"):
        if k in S:
            v = S[k]
            tot = sum(x["rssMB"] for x in v.values())
            emit(f"| {run} {k} | {f(tot)} | " + " | ".join(f((v.get(c) or {}).get("rssMB")) for c in order) + " |")
emit("")

# ------------------------------------------------------------------ warm queries
emit("<!-- table:queries -->")
emit("| Request | State | n | Alloc median (MB) | Alloc p95 (MB) | Latency median / p95 (ms) |")
emit("|---|---|---:|---:|---:|---:|")
for q in d["queries_primary"] + d["queries_supplementary"]:
    pass
seen = {}
for g in ("queries_primary", "queries_supplementary"):
    for q in d[g]:
        seen.setdefault((q["method"], q["state"]), []).append(q)
for (m, st), qs in sorted(seen.items()):
    n = sum(q["n"] for q in qs)
    emit(f"| {m} | {st} | {n} | {both([q['allocMedianMB'] for q in qs], 2)} | {both([q['allocP95MB'] for q in qs], 2)} | "
         f"{both([q['latencyMedianMs'] for q in qs], 1)} / {both([q['latencyP95Ms'] for q in qs], 1)} |")
emit("")

# ------------------------------------------------------------------ scale
emit("<!-- table:scale -->")
emit("| Repository | JARs | Symbols | Edges | Seed alloc (GB) | Live heap (MB) | Persisted DB (MB) | RocksDB cache (MB) | Seed (s) | Peak heap (MB) | Peak RSS (MB) |")
emit("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
for r in d["scale"]:
    emit(f"| {r['run']} | {r['jars']} | {f(r['symbols'])} | {f(r['edges'])} | {f(r['seedAllocated'] / 1e9, 2)} | {f((r['liveHeapAfterFullGc'] or 0) / 1e6, 1)} | "
         f"{f((r['persistedDb'] or 0) / 1e6, 1)} | {f((r['rocksCache'] or 0) / 1e6, 1)} | {f(r['seedSeconds'], 1)} | {f((r['peakHeapUsed'] or 0) / 1e6)} | {f((r['peakRss'] or 0) / 1e6)} |")
emit("")
print("\n".join(out))
