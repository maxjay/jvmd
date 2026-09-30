#!/usr/bin/env python3
"""Reduces memory-profiling runs (benchmarks/memory/profile.ts) to small machine-readable summaries.

  analyze.py RUN_DIR [RUN_DIR...] --out SUMMARY_DIR --async-profiler AP_HOME --java-home JDK

Per run it writes SUMMARY_DIR/<run>/: lifecycle.json (every checkpoint), and by mode:
  alloc/live  phases.json: per phase top classes (bytes, samples), top stacks, module, component
              and thread rollups, outside-TLAB (large) allocations
  native      phases.json: per phase native allocation (total and unfreed) by library/component and stack
  retention   histograms.json: top-100 classes per checkpoint (post full GC)
  nmt         nmt.json: committed/reserved per NMT category per checkpoint
  exact       samples.json (per-phase peaks from the 50 ms sampler), stages.json (dev.jvmd.Stage), gc.json
Raw evidence stays in the run directory; nothing here is lossy with respect to what the report quotes.
"""
import argparse, collections, gzip, json, os, re, subprocess, sys
from pathlib import Path

# ------------------------------------------------------------------ attribution rules
MODULES = [  # innermost matching frame wins; order only breaks ties within one frame
    ("jvmd-index-rocks", r"^dev/jvmd/index/rocks/"),
    ("jvmd-index", r"^dev/jvmd/index/"),
    ("jvmd-analyzer", r"^dev/jvmd/analyzer/"),
    ("jvmd-resolver", r"^dev/jvmd/resolver/"),
    ("jvmd-lsp", r"^dev/jvmd/lsp/"),
    ("jvmd-dist", r"^dev/jvmd/dist/"),
    ("jvmd-mcp", r"^dev/jvmd/mcp/"),
    ("jvmd-runtime", r"^dev/jvmd/runtime/"),
    ("jvmd-core", r"^dev/jvmd/core/"),
    ("javac / jdk.compiler", r"^com/sun/tools/javac/|^com/sun/source/|^jdk/internal/javac|^javax/tools/|^javax/lang/model/"),
    ("Jackson", r"^com/fasterxml/jackson/"),
    ("RocksDB JNI wrapper", r"^org/rocksdb/"),
    ("Maven resolver libraries", r"^org/apache/maven/|^org/eclipse/aether/|^org/codehaus/plexus/|^org/eclipse/sisu/|^com/google/inject/"),
    ("JDK classfile API", r"^java/lang/classfile/|^jdk/internal/classfile/"),
    ("JDK ZIP/JAR", r"^java/util/zip/|^java/util/jar/|^jdk/internal/util/zip|^sun/net/www/protocol/jar/|^jdk/nio/zipfs/"),
    ("JDK collections", r"^java/util/(HashMap|LinkedHashMap|TreeMap|ArrayList|HashSet|LinkedHashSet|TreeSet|ArrayDeque|Arrays|ImmutableCollections|AbstractMap|AbstractList|Collections|concurrent/ConcurrentHashMap|concurrent/ConcurrentSkipListMap|concurrent/CopyOnWrite|stream/|Spliterators|List|Map|Set)"),
    ("JDK strings/text", r"^java/lang/(String|AbstractStringBuilder|StringBuilder|StringLatin1|StringUTF16|StringConcatHelper|invoke/StringConcat)|^java/util/(Formatter|regex/)|^java/text/|^sun/nio/cs/|^java/nio/charset/"),
    ("JDK I/O & NIO", r"^java/io/|^java/nio/|^sun/nio/|^sun/net/"),
    ("JDK security/digest", r"^java/security/|^sun/security/|^jdk/internal/util/HexFormat|^java/util/HexFormat"),
    ("JDK JFR/management", r"^jdk/jfr/|^jdk/internal/event/|^sun/management/|^com/sun/management/|^java/lang/management/"),
    ("JDK reflection/invoke", r"^java/lang/invoke/|^java/lang/reflect/|^jdk/internal/reflect/|^sun/invoke/"),
]
MODULES = [(n, re.compile(p)) for n, p in MODULES]
JVMD_OWNERS = {"jvmd-index-rocks", "jvmd-index", "jvmd-analyzer", "jvmd-resolver", "jvmd-lsp", "jvmd-dist", "jvmd-mcp", "jvmd-runtime", "jvmd-core"}
LIBRARY_OWNERS = JVMD_OWNERS | {"javac / jdk.compiler", "Jackson", "RocksDB JNI wrapper", "Maven resolver libraries", "JDK classfile API", "JDK ZIP/JAR"}

# Semantic components: the first rule (in list order) matching ANY frame of the stack wins, so
# more specific operations are listed before the broad owners that contain them.
COMPONENTS = [
    ("profiling observer (allocation agent / status)", r"^AllocationAgent\.|Dispatcher\.status|IndexService\.status"),
    ("artifact hashing", r"dev/jvmd/core/Hashing\.|IndexService\.(sha1|hash|verifySha)|FileStateRegistry\.(hash|digest)|java/security/MessageDigest"),
    ("artifact discovery (repository walk)", r"IndexService\.(discover|jars)|java/nio/file/FileTreeWalker|java/nio/file/Files\.walk"),
    ("JAR parsing (ZIP/class bytes)", r"dev/jvmd/index/BinaryReader\.(read|entries|bytes)|dev/jvmd/index/BinaryReader\.lambda"),
    ("binary semantic extraction (classfile -> symbols/edges)", r"dev/jvmd/index/(BinaryReader|CodeReader|Signatures|Descriptors|BinarySymbols)"),
    ("artifact encoding (ArtifactIndexFormat)", r"dev/jvmd/index/ArtifactIndexFormat\."),
    ("Rocks SST construction (sort/grams/SST)", r"RocksArtifactRepository\.(publish|writeSst|lambda\$publish|verifyStagedSst)|SstSorter|GramPostings|org/rocksdb/SstFileWriter"),
    ("Rocks artifact metadata (StoredArtifact/inventory)", r"RocksIndexStore\.publish|RocksArtifactInventory|RocksIndexStore\.(record|write|put)"),
    ("source-JAR documentation indexing", r"IndexService\.indexSources|SourceDocs|DocumentationIndex|SourceIndexer"),
    ("index restart / reopen (manifest, metadata load)", r"RocksMigrationManager|RocksIndexStore\.<init>|RocksIndexStore\.(load|open|restore)|RocksIndexStorage\.<init>|RocksArtifactRepository\.<init>|RocksArtifactInventory\.<init>"),
    ("semantic fact publication (source facts)", r"SourceIndexPublisher|KeyedFacts\.publish|FactCodec\.encode"),
    ("repository lookup (index queries)", r"KeyedFacts\.(query|lookup|lambda)|FactCodec\.decode|SourceOverlay|RocksIndexStore\.(query|lookup|search|find|symbol|selected|classpath|semantic)|RocksArtifactRepository\.(query|read|get|lookup|owner|scan|symbol)|IndexService\.(query|lookup|search)"),
    ("workspace/project resolution (Maven model)", r"dev/jvmd/resolver/|Application\.refresh|org/apache/maven/|org/eclipse/aether/"),
    ("classpath construction", r"Classpath|CompilerInputs"),
    ("workspace/index binding", r"Application\.(bindIndex|prepareIndex)|WorkspaceBindings|BindingFacts"),
    ("proof construction / validation", r"Proof|Witness"),
    ("javac parse", r"com/sun/tools/javac/parser/|CompilerPool\.parse"),
    ("javac enter/attribute/flow", r"com/sun/tools/javac/(comp|code|jvm|tree|util|model|main|api|file)/"),
    ("diagnostics (module actors, stores)", r"ModuleAnalyzerRegistry|WorkspaceAnalysisCoordinator|DiagnosticStore|DiagnosticSnapshots|Analyzer\.diagnos"),
    ("completion", r"Analyzer\.[a-zA-Z$]*[Cc]omplet|Completion|TypeCompletionCache"),
    ("hover / documentation enrichment", r"[Hh]over|Documentation|Javadoc"),
    ("definition", r"[Dd]efinition"),
    ("references", r"[Rr]eferences|CodePass"),
    ("semantic state (resident/document snapshots)", r"ResidentSemanticState|SemanticUnitState|DocumentSemantic|SemanticUpdatePolicy|RocksIndexSemanticState|RocksSemanticInvalidation"),
    ("documents / live source state", r"dev/jvmd/core/Documents|LiveSourceState|WorkspaceOverlay"),
    ("LSP facade / JSON-RPC encoding", r"dev/jvmd/lsp/|Dispatcher\.|UnixServer|com/fasterxml/jackson/"),
    ("index scan (other)", r"dev/jvmd/index/IndexService"),
    ("RocksDB JNI (other)", r"^org/rocksdb/"),
    ("JVMD (other)", r"^dev/jvmd/"),
    ("JDK/JVM internal (no JVMD frame)", r"."),
]
COMPONENTS = [(n, re.compile(p)) for n, p in COMPONENTS]

THREAD_GROUPS = [
    ("index workers (jvmd-index-*)", r"jvmd-index"),
    ("session threads (jvmd-session-*)", r"jvmd-session"),
    ("module actors (jvmd-module-*)", r"jvmd-module-[0-9a-f]"),
    ("diagnostic dispatch (jvmd-module-dispatch-*)", r"jvmd-module-dispatch"),
    ("source publisher / snapshots", r"jvmd-source-publisher|jvmd-diagnostic-snapshots"),
    ("watchers (source-state, overlay, project-model)", r"jvmd-source-state|overlay-watch|project-model-watch"),
    ("RPC connections (virtual, unnamed)", r"^\s*tid=|^$|ForkJoinPool-1-worker|^\[?\s*tid"),
    ("ForkJoin/common pools", r"ForkJoinPool|commonPool"),
    ("Rocks/native background", r"rocksdb|rocks:"),
    ("allocation probe (profiling agent)", r"allocation-probe"),
    ("JVM service threads", r"C1 Compiler|C2 Compiler|Signal Dispatcher|Finalizer|Reference Handler|Common-Cleaner|Notification Thread|Attach Listener|JFR|Service Thread|GC"),
    ("main / other platform", r"."),
]
THREAD_GROUPS = [(n, re.compile(p)) for n, p in THREAD_GROUPS]


def frame_name(f):
    return re.sub(r"_\[[a-z0-9]\]$", "", f)


def module_of(frame):
    for name, rx in MODULES:
        if rx.search(frame):
            return name
    return None


def classify_stack(frames):
    """frames: root..leaf method frames (allocated class removed)."""
    names = [frame_name(f) for f in frames]
    inner = next((m for m in (module_of(f) for f in reversed(names)) if m), "other")
    owner = next((m for m in (module_of(f) for f in reversed(names)) if m in LIBRARY_OWNERS), "JDK/other (no library frame)")
    component = "JDK/JVM internal (no JVMD frame)"
    for cname, rx in COMPONENTS:
        if any(rx.search(f) for f in names):
            component = cname
            break
    return inner, owner, component


def thread_group(t):
    for name, rx in THREAD_GROUPS:
        if rx.search(t):
            return name
    return "other"


def read_collapsed(path):
    with open(path) as fh:
        for line in fh:
            stack, _, value = line.rstrip("\n").rpartition(" ")
            if stack:
                yield stack.split(";"), int(value)


def jfrconv(ap, args, src, dst):
    if not dst.exists():
        subprocess.run([str(Path(ap) / "bin/jfrconv"), *args, str(src), str(dst)], check=True, capture_output=True,
                       env={**os.environ, "JAVA_TOOL_OPTIONS": ""})
    return dst


def top(counter, n):
    return [{"key": k, "value": v} for k, v in counter.most_common(n)]


def alloc_phase(ap, jfr, cache, live=False):
    kind = "--live" if live else "--alloc"
    bytes_file = jfrconv(ap, [kind, "--total", "-t"], jfr, cache / (jfr.stem + ".bytes.collapsed"))
    count_file = jfrconv(ap, [kind, "-t"], jfr, cache / (jfr.stem + ".samples.collapsed"))
    out = {"bytes": 0, "samples": 0}
    classes_b, classes_n, stacks_b = collections.Counter(), collections.Counter(), collections.Counter()
    inner_b, owner_b, comp_b, thread_b, comp_class = (collections.Counter() for _ in range(5))
    outside_b, outside_classes = collections.Counter(), collections.Counter()
    comp_top_site = collections.defaultdict(collections.Counter)
    for frames, v in read_collapsed(bytes_file):
        thread, methods, cls = frames[0], frames[1:-1], frames[-1]
        tlab = cls.endswith("_[k]")
        cname = frame_name(cls)
        out["bytes"] += v
        classes_b[cname] += v
        key = ";".join(frame_name(f) for f in methods[-12:]) + " => " + cname
        stacks_b[key] += v
        inner, owner, comp = classify_stack(methods)
        inner_b[inner] += v; owner_b[owner] += v; comp_b[comp] += v
        comp_class[comp + " :: " + cname] += v
        site = next((frame_name(f) for f in reversed(methods) if frame_name(f).startswith("dev/jvmd/")), frame_name(methods[-1]) if methods else "?")
        comp_top_site[comp][site + " => " + cname] += v
        thread_b[thread_group(re.sub(r"^\[|\]$", "", thread).split(" tid=")[0])] += v
        if tlab:
            outside_b[key] += v; outside_classes[cname] += v
    for frames, v in read_collapsed(count_file):
        out["samples"] += v
        classes_n[frame_name(frames[-1])] += v
    out.update({
        "top_classes_by_bytes": top(classes_b, 50), "top_classes_by_samples": top(classes_n, 50),
        "top_stacks_by_bytes": top(stacks_b, 50),
        "innermost_owner": top(inner_b, 40), "library_owner": top(owner_b, 40), "component": top(comp_b, 40),
        "component_top_site": {k: top(v, 3) for k, v in comp_top_site.items()},
        "component_class": top(comp_class, 60), "threads": top(thread_b, 20),
        "outside_tlab_stacks": top(outside_b, 30), "outside_tlab_classes": top(outside_classes, 20),
    })
    return out


NATIVE_LIBS = [
    ("RocksDB (librocksdbjni)", r"rocksdb::|Java_org_rocksdb|librocksdbjni|^org/rocksdb/|rocksdb_"),
    ("zlib/zip (libzip)", r"inflate|deflate|ZIP_|libzip|Java_java_util_zip"),
    ("HotSpot (libjvm)", r"^(os::|Arena|ChunkPool|Symbol|ClassLoader|Metaspace|CodeCache|Compile|C2|JVM_|JNIHandle|ThreadLocalAlloc|G1|Thread::|JavaThread|ciEnv|Method|ConstantPool|InstanceKlass|NMT|MallocTracker|AllocateHeap|ResourceArea|Unsafe_|jni_|JfrCheckpoint|Jfr|StringTable|SymbolTable|CompileBroker|Parse|PhaseIdealLoop|Node)|libjvm"),
    ("JDK native (libnio/libjava/libnet)", r"Java_sun_nio|Java_java_io|Java_java_lang|Java_sun_|libnio|libjava|libnet"),
    ("async-profiler itself", r"libasyncProfiler|Profiler::|CallTraceStorage|LinearAllocator"),
    ("libc/other", r"."),
]
NATIVE_LIBS = [(n, re.compile(p)) for n, p in NATIVE_LIBS]


def native_owner(frames):
    names = [frame_name(f) for f in frames]
    for name, rx in NATIVE_LIBS:
        if any(rx.search(f) for f in names):
            return name
    return "unknown"


ROCKS_CATEGORIES = [
    ("block cache inserts (BlockBasedTable/Block)", r"BlockBasedTable::(RetrieveBlock|MaybeReadBlockAndLoadToCache|GetDataBlockFromCache|PutDataBlockToCache)|BlockFetcher|UncompressBlock|Block::Block|ParsedFullFilterBlock|BlockContents"),
    ("table readers / index & filter (open)", r"BlockBasedTable::Open|TableCache::(FindTable|GetTableReader)|PartitionIndexReader|PartitionedFilterBlockReader|BlockBasedTable::PrefetchIndexAndFilterBlocks|TableReader"),
    ("memtables / write buffers", r"MemTable|Arena::AllocateNewBlock|ConcurrentArena|InlineSkipList|WriteBufferManager|WriteBatch"),
    ("SST writing / ingestion", r"SstFileWriter|BlockBasedTableBuilder|IngestExternalFile|ExternalSstFileIngestionJob|BlockBuilder|FilterBlockBuilder|IndexBuilder|CompressBlock"),
    ("iterators / reads", r"Iterator|NewIterator|DBIter|MergingIterator|Get\b|DBImpl::Get|MultiGet|PinnableSlice"),
    ("compaction / flush", r"Compaction|FlushJob|BuildTable"),
    ("DB open / recovery / manifest / WAL", r"DBImpl::Open|DB::Open|Recover|VersionSet|VersionEdit|log::Reader|WalManager|ManifestWriter|ColumnFamilySet|DBImpl::DBImpl"),
    ("JNI wrappers / options", r"Java_org_rocksdb|rocksdb::Options|ColumnFamilyOptions|DBOptions|JniUtil|portal"),
    ("other RocksDB", r"rocksdb::"),
]
ROCKS_CATEGORIES = [(n, re.compile(p)) for n, p in ROCKS_CATEGORIES]


def rocks_category(frames):
    names = [frame_name(f) for f in frames]
    for name, rx in ROCKS_CATEGORIES:
        if any(rx.search(f) for f in reversed(names)):
            return name
    return None


def native_phase(ap, jfr, cache):
    res = {}
    for label, extra in (("total", []), ("unfreed", ["--leak"])):
        f = jfrconv(ap, ["--nativemem", "--total", "-t", *extra], jfr, cache / (jfr.stem + f".{label}.collapsed"))
        owners, stacks, rocks, threads = (collections.Counter() for _ in range(4))
        total = 0
        for frames, v in read_collapsed(f):
            thread, body = frames[0], frames[1:]
            total += v
            owners[native_owner(body)] += v
            rc = rocks_category(body)
            if rc:
                rocks[rc] += v
            stacks[";".join(frame_name(x) for x in body[-10:])] += v
            threads[thread_group(re.sub(r"^\[|\]$", "", thread).split(" tid=")[0])] += v
        res[label] = {"bytes": total, "owners": top(owners, 20), "rocks_categories": top(rocks, 20), "top_stacks": top(stacks, 30), "threads": top(threads, 15)}
    return res


def parse_histogram(text, n=100):
    rows = []
    for line in text.splitlines():
        m = re.match(r"\s*\d+:\s+(\d+)\s+(\d+)\s+(\S+)", line)
        if m:
            rows.append({"instances": int(m.group(1)), "bytes": int(m.group(2)), "class": m.group(3)})
    total = re.search(r"Total\s+(\d+)\s+(\d+)", text)
    return {"total_instances": int(total.group(1)) if total else None, "total_bytes": int(total.group(2)) if total else None, "top": rows[:n]}


def parse_nmt(text):
    out = {}
    m = re.search(r"Total: reserved=(\d+)KB(?: [+-]\d+KB)?, committed=(\d+)KB", text)
    if m:
        out["Total"] = {"reserved_kb": int(m.group(1)), "committed_kb": int(m.group(2))}
    for m in re.finditer(r"^-\s+([A-Za-z][A-Za-z ]+?) \(reserved=(\d+)KB(?: ([+-]\d+)KB)?, committed=(\d+)KB(?: ([+-]\d+)KB)?\)", text, re.M):
        out[m.group(1).strip()] = {"reserved_kb": int(m.group(2)), "committed_kb": int(m.group(4)),
                                  "reserved_delta_kb": int(m.group(3) or 0), "committed_delta_kb": int(m.group(5) or 0)}
    m = re.search(r"\(malloc=(\d+)KB(?: [+-]\d+KB)? #\d+(?: [+-]\d+)?\)\s*\n\s*\(mmap: reserved=(\d+)KB(?: [+-]\d+KB)?, committed=(\d+)KB", text)
    if m:
        out["_malloc_mmap"] = {"malloc_kb": int(m.group(1)), "mmap_reserved_kb": int(m.group(2)), "mmap_committed_kb": int(m.group(3))}
    return out


def lifecycle(run):
    rows = []
    for line in open(run / "marks.jsonl"):
        r = json.loads(line)
        s = r.get("snapshot") or {}
        proc = r.get("proc") or {}
        st = r.get("status") or {}
        buffers = (s.get("buffers") or {})
        rows.append({
            "seq": r["seq"], "id": r["id"], "label": r["label"], "incarnation": r["incarnation"], "t": r["t"],
            "sinceSpawnMs": r.get("sinceSpawnMs"),
            "allocated": s.get("allocated"), "heapUsed": (s.get("heap") or {}).get("used"), "heapCommitted": (s.get("heap") or {}).get("committed"),
            "nonHeapUsed": (s.get("non_heap") or {}).get("used"), "nonHeapCommitted": (s.get("non_heap") or {}).get("committed"),
            "metaspaceUsed": ((s.get("pools") or {}).get("Metaspace") or {}).get("usage", {}).get("used"),
            "codeCacheUsed": sum(v["usage"]["used"] for k, v in (s.get("pools") or {}).items() if k.startswith("CodeHeap")) if s.get("pools") else None,
            "oldAfterGc": ((s.get("pools") or {}).get("G1 Old Gen") or {}).get("after_gc", {}).get("used"),
            "direct": buffers.get("direct"), "mapped": buffers.get("mapped"), "mappedNvm": buffers.get("mapped - 'non-volatile memory'"),
            "threads": s.get("threads"), "classes": s.get("classes"), "gc": s.get("gc"),
            "VmRSS": proc.get("VmRSS"), "VmSize": proc.get("VmSize"), "VmHWM": proc.get("VmHWM"), "RssAnon": proc.get("RssAnon"), "RssFile": proc.get("RssFile"),
            "RssShmem": proc.get("RssShmem"), "Pss": proc.get("rollup_Pss"), "Pss_Anon": proc.get("rollup_Pss_Anon"), "Pss_File": proc.get("rollup_Pss_File"),
            "Private_Dirty": proc.get("rollup_Private_Dirty"), "Private_Clean": proc.get("rollup_Private_Clean"),
            "Shared_Clean": proc.get("rollup_Shared_Clean"), "Shared_Dirty": proc.get("rollup_Shared_Dirty"), "Swap": proc.get("rollup_Swap"),
            "Threads": proc.get("Threads"),
            "index": {k: st.get(k) for k in ("phase", "bootstrap", "total", "scanned", "indexed", "artifacts", "symbols", "edges", "simple_names")},
            "rocks": st.get("native_memory"), "repository": st.get("repository"), "store": st.get("store"), "admission": st.get("admission"),
            "phase": r.get("phase"), "observerBytes": r.get("observerBytes"), "hookBytes": r.get("hookBytes"),
            "liveHeapAfterFullGc": r.get("liveHeapAfterFullGc"), "hooks": r.get("hooks"), "persisted": r.get("persisted"),
            "smaps": r.get("smaps"),
        })
    return rows


def sampler_peaks(run, marks):
    """Per phase (between consecutive marks of one incarnation): peak RSS / heap from the 50 ms samples."""
    out = []
    for inc in sorted({m["incarnation"] for m in marks}):
        f = run / f"samples-i{inc}.jsonl"
        if not f.exists():
            continue
        samples = [json.loads(l) for l in open(f)]
        ms = [m for m in marks if m["incarnation"] == inc and m.get("t")]
        for prev, cur in zip(ms, ms[1:]):
            window = [s for s in samples if prev["t"] <= s["t"] <= cur["t"]]
            if not window:
                continue
            def peak(fn):
                vals = [v for v in (fn(s) for s in window) if v is not None]
                return max(vals) if vals else None
            out.append({"from": prev["id"], "to": cur["id"], "samples": len(window),
                        "peakRss": peak(lambda s: s["proc"].get("VmRSS")), "peakRssAnon": peak(lambda s: s["proc"].get("RssAnon")),
                        "peakRssFile": peak(lambda s: s["proc"].get("RssFile")), "peakPss": peak(lambda s: s["proc"].get("rollup_Pss")),
                        "peakHeapUsed": peak(lambda s: (s.get("jvm") or {}).get("heapUsed")),
                        "peakHeapCommitted": peak(lambda s: (s.get("jvm") or {}).get("heapCommitted")),
                        "peakNonHeap": peak(lambda s: (s.get("jvm") or {}).get("nonHeapCommitted"))})
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("runs", nargs="+"); ap.add_argument("--out", required=True)
    ap.add_argument("--async-profiler", default=os.environ.get("ASYNC_PROFILER_HOME", ""))
    a = ap.parse_args()
    for run in map(Path, a.runs):
        env = json.load(open(run / "environment.json"))
        dst = Path(a.out) / run.name
        dst.mkdir(parents=True, exist_ok=True)
        cache = run / "converted"; cache.mkdir(exist_ok=True)
        marks = lifecycle(run)
        results = json.load(open(run / "results.json")) if (run / "results.json").exists() else {}
        json.dump({"environment": env, "failures": results.get("failures"), "marks": marks,
                   "operations": results.get("operations")}, open(dst / "lifecycle.json", "w"), indent=1)
        mode = env["mode"]
        if mode == "exact" or mode == "rss":
            json.dump(sampler_peaks(run, marks), open(dst / "samples.json", "w"), indent=1)
        if mode in ("alloc", "live", "native"):
            phases = []
            raw = [json.loads(l) for l in open(run / "marks.jsonl")]
            for r in raw:
                pf = (r.get("hooks") or {}).get("profiler", {}).get("phaseFile")
                if not pf or not (run / "phases" / pf).exists():
                    continue
                jfr = run / "phases" / pf
                try:
                    data = native_phase(a.async_profiler, jfr, cache) if mode == "native" else alloc_phase(a.async_profiler, jfr, cache, live=(mode == "live"))
                except subprocess.CalledProcessError as e:
                    data = {"error": e.stderr.decode()[-500:]}
                phases.append({"to": r["id"], "label": r["label"], "incarnation": r["incarnation"], "file": pf,
                               "exactAllocatedBytes": (r.get("phase") or {}).get("allocatedBytes"), **data})
                print(run.name, r["id"], data.get("bytes"), file=sys.stderr)
            json.dump(phases, open(dst / "phases.json", "w"), indent=1)
        if (run / "histograms").exists() and any((run / "histograms").iterdir()):
            hs = {p.stem: parse_histogram(p.read_text()) for p in sorted((run / "histograms").glob("*.txt"))}
            json.dump(hs, open(dst / "histograms.json", "w"), indent=1)
        if mode == "nmt":
            nmt = {}
            for p in sorted((run / "nmt").glob("*-summary*.txt")):
                nmt[p.name] = parse_nmt(p.read_text())
            json.dump(nmt, open(dst / "nmt.json", "w"), indent=1)


if __name__ == "__main__":
    main()
