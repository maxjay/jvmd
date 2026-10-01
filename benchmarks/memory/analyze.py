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

# Two attribution dimensions over the same stacks (async-profiler reports virtual threads under their
# ForkJoinPool carrier, so "which work" is read from frames, not thread names):
#   mechanism: the innermost frame matching a rule (what code allocated: SST postings, digests, javac...)
#   operation: the outermost frame matching a rule (which pipeline it served: seed, completion, diagnostics...)
MECHANISMS = [
    ("RocksDB JNI wrapper", r"^org/rocksdb/"),
    ("SST gram postings (GramPostings)", r"GramPostings|RocksArtifactRepository\.addGram"),
    ("SST sort/spill/merge (SstSorter)", r"SstSorter"),
    ("SST keys (relativeKey/hex8/scip suffix)", r"RocksArtifactRepository\.(relativeKey|hex8|scipSuffix|key)|ArtifactContext\.scip"),
    ("SST writing (other RocksArtifactRepository)", r"RocksArtifactRepository\."),
    ("symbol/resolution encoding (ArtifactIndexFormat.encode/write)", r"ArtifactIndexFormat\.(encode|write)"),
    ("source-fact decoding (FactCodec read)", r"FactCodec\.(read|decode|lambda\$read)"),
    ("source-fact encoding/overlay (FactCodec/KeyedFacts/SourceOverlay)", r"FactCodec|KeyedFacts|SourceOverlay"),
    ("canonical digests (CanonicalDigestWriter/LiveStateTree/Hash256/SHA)", r"CanonicalDigestWriter|LiveStateTree|dev/jvmd/core/Hash256|dev/jvmd/core/Hashing|java/security/MessageDigest|sun/security/provider"),
    ("algebraic accumulators (AlgebraicAccumulator, BigInteger)", r"AlgebraicAccumulator"),
    ("fact construction (ArtifactIndexFormat.from/ResolutionFact/SemanticType)", r"ArtifactIndexFormat|ResolutionFact|SemanticType|dev/jvmd/index/SemanticFact"),
    ("ZIP/JAR entry I/O", r"^java/util/zip/|^java/util/jar/|^jdk/nio/zipfs/|^jdk/internal/util/zip"),
    ("classfile reading (BinaryReader/CodeReader/classfile API)", r"BinaryReader|CodeReader|dev/jvmd/index/Signatures|dev/jvmd/core/JavaTypes|^java/lang/classfile/|^jdk/internal/classfile/"),
    ("local source join (LocalArtifacts/SourceJoin)", r"LocalArtifacts|SourceJoin"),
    ("reference bindings scan (analyzer.Bindings)", r"dev/jvmd/analyzer/Bindings"),
    ("semantic declaration extraction (SemanticDeclaration/SymbolIdentity)", r"SemanticDeclaration|SymbolIdentity"),
    ("resident semantic state / proofs", r"ResidentSemanticState|SemanticUnitState|DocumentSemantic|Proof|Witness|SemanticUpdatePolicy"),
    ("completion materialization", r"^dev/jvmd/.*[Cc]omplet"),
    ("javac (jdk.compiler)", r"^com/sun/tools/javac/|^com/sun/source/|^jdk/internal/javac"),
    ("Jackson / JSON", r"^com/fasterxml/jackson/|dev/jvmd/core/Json"),
    ("Maven model / resolver", r"^org/apache/maven/|^org/eclipse/aether/|^org/codehaus/plexus/|^dev/jvmd/resolver/|^org/eclipse/sisu/|^com/google/inject/"),
    ("diagnostic stores/snapshots", r"Diagnostic"),
    ("documents / live source state", r"dev/jvmd/core/Documents|LiveSourceState|WorkspaceOverlay|SourceText"),
    ("workspace bindings (WorkspaceBindings/BindingFacts)", r"WorkspaceBindings|BindingFacts"),
    ("profiling observer (allocation agent)", r"^AllocationAgent"),
    ("annotation-processing fingerprint (AnnotationProcessing)", r"dev/jvmd/core/AnnotationProcessing"),
    ("compiler-input identity (CompilerInputs.compose/write)", r"dev/jvmd/core/CompilerInputs"),
    ("file-state stamps (FileStateRegistry stat/hash)", r"dev/jvmd/core/FileStateRegistry"),
    ("jvmd-analyzer (other)", r"^dev/jvmd/analyzer/"),
    ("jvmd-index-rocks (other)", r"^dev/jvmd/index/rocks/"),
    ("jvmd-index (other)", r"^dev/jvmd/index/"),
    ("jvmd-dist (other)", r"^dev/jvmd/dist/"),
    ("jvmd-core (other)", r"^dev/jvmd/core/"),
    ("jvmd-lsp / mcp / runtime (other)", r"^dev/jvmd/"),
]
MECHANISMS = [(n, re.compile(p)) for n, p in MECHANISMS]
OPERATIONS = [
    ("profiling observer (allocation agent / status)", r"^AllocationAgent|Dispatcher\.status|IndexService\.status"),
    ("machine index scan (seed / rescan)", r"IndexService\.(scan|lambda\$scan|indexJar|indexSources|lambda\$start)"),
    ("persisted index open (RocksIndexStorage/RocksIndexStore init)", r"RocksIndexStorage\.<init>|RocksIndexStore\.<init>|RocksMigrationManager|IndexStorage\.open"),
    ("local workspace artifacts (IndexService.registerLocal)", r"IndexService\.(registerLocal|lambda\$registerLocal)|LocalArtifacts"),
    ("background source-fact publication (SourceIndexPublisher)", r"SourceIndexPublisher\.run"),
    ("diagnostics: module actors", r"ModuleAnalyzerRegistry\$Actor|WorkspaceAnalysisCoordinator"),
    ("diagnostic snapshot writer", r"DiagnosticSnapshots"),
    ("project resolution (Maven)", r"Application\.refresh|MavenResolver|dev/jvmd/resolver/|Application\.maintainedResolution|^org/apache/maven/|^org/eclipse/aether/"),
    ("annotation processing preparation", r"AnnotationProcessing|Application\.prepareProcessing"),
    ("references: workspace bindings (Application.occurrences/WorkspaceBindings)", r"Application\.occurrences|WorkspaceBindings|[Rr]eferences|CodePass|dev/jvmd/analyzer/Bindings"),
    ("completion", r"^dev/jvmd/.*[Cc]omplet"),
    ("hover / documentation", r"^dev/jvmd/.*([Hh]over|Documentation)"),
    ("definition", r"^dev/jvmd/.*[Dd]efinition"),
    ("session work (other)", r"dev/jvmd/core/Session"),
    ("RPC / LSP transport", r"Dispatcher|UnixServer|dev/jvmd/lsp/"),
    ("JVMD (other)", r"^dev/jvmd/"),
]
OPERATIONS = [(n, re.compile(p)) for n, p in OPERATIONS]


def classify_stack(frames):
    """frames: root..leaf method frames (allocated class removed). Returns (innermost owner, library owner, mechanism, operation)."""
    names = [frame_name(f) for f in frames]
    inner = next((m for m in (module_of(f) for f in reversed(names)) if m), "other")
    owner = next((m for m in (module_of(f) for f in reversed(names)) if m in LIBRARY_OWNERS), "JDK/other (no library frame)")
    mechanism = "JDK/JVM only (no attributable frame)"
    for f in reversed(names):
        hit = next((n for n, rx in MECHANISMS if rx.search(f)), None)
        if hit:
            mechanism = hit
            break
    # Operations: the outermost frame that names a pipeline; session-queued work is refined by inner frames.
    operation = "JDK/JVM only (no attributable frame)"
    hits = [next((n for n, rx in OPERATIONS if rx.search(f)), None) for f in names]
    hits = [h for h in hits if h]
    if hits:
        operation = hits[0]
        if operation in ("session work (other)", "RPC / LSP transport", "JVMD (other)"):
            specific = [h for h in hits if h not in ("session work (other)", "RPC / LSP transport", "JVMD (other)")]
            if specific:
                operation = specific[0]
    return inner, owner, mechanism, operation


THREAD_GROUPS = [
    ("index workers (jvmd-index-*)", r"jvmd-index"),
    ("session threads (jvmd-session-*)", r"jvmd-session"),
    ("module actors (jvmd-module-*)", r"jvmd-module-[0-9a-f]"),
    ("diagnostic dispatch (jvmd-module-dispatch-*)", r"jvmd-module-dispatch"),
    ("source publisher / snapshots", r"jvmd-source-publisher|jvmd-diagnostic-snapshots"),
    ("watchers (source-state, overlay, project-model)", r"jvmd-source-state|overlay-watch|project-model-watch"),
    ("virtual threads (ForkJoinPool-1 carriers: index readers, publisher, RPC)", r"ForkJoinPool-1-worker"),
    ("other ForkJoin/common pools", r"ForkJoinPool|commonPool"),
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
    inner_b, owner_b, comp_b, op_b, thread_b, comp_class = (collections.Counter() for _ in range(6))
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
        inner, owner, comp, op = classify_stack(methods)
        inner_b[inner] += v; owner_b[owner] += v; comp_b[comp] += v; op_b[op] += v
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
        "innermost_owner": top(inner_b, 40), "library_owner": top(owner_b, 40), "mechanism": top(comp_b, 45), "operation": top(op_b, 30),
        "mechanism_top_site": {k: top(v, 3) for k, v in comp_top_site.items()},
        "mechanism_class": top(comp_class, 60), "threads": top(thread_b, 20),
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
    ("iterators / reads", r"rocksdb::.*(Iterator|DBIter|DBImpl::Get|MultiGet|PinnableSlice)"),
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
            owner = native_owner(body)
            owners[owner] += v
            rc = rocks_category(body) if owner.startswith("RocksDB") else None
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


def jfr_events(java_home, jfr, events, cache):
    """Streams `jfr print --json` for the given events (cached as JSON lines)."""
    out = cache / (jfr.stem + "." + events.replace(",", "+") + ".jsonl")
    if not out.exists():
        r = subprocess.run([str(Path(java_home) / "bin/jfr"), "print", "--json", "--events", events, str(jfr)],
                           capture_output=True, text=True, env={**os.environ, "JAVA_TOOL_OPTIONS": ""})
        data = json.loads(r.stdout or '{"recording":{"events":[]}}')
        with open(out, "w") as fh:
            for e in data["recording"]["events"]:
                fh.write(json.dumps(e) + "\n")
    return [json.loads(l) for l in open(out)]


def iso_ms(t):
    from datetime import datetime
    return datetime.fromisoformat(t.replace("Z", "+00:00")).timestamp() * 1000


def phase_of(marks, t):
    prev = None
    for m in marks:
        if m.get("t") and m["t"] >= t:
            return (prev["id"] if prev else "start") + ".." + m["id"]
        prev = m
    return (prev["id"] if prev else "start") + "..end"


def exact_jfr(run, marks, java_home, cache):
    stages, samples, gcs = [], collections.defaultdict(collections.Counter), []
    agg = collections.defaultdict(lambda: {"count": 0, "durationMs": 0.0, "allocatedBytes": 0, "allocationKnown": 0, "virtual": 0, "queued": 0, "cpuMs": 0.0})
    for jfr in sorted(run.glob("exact-i*.jfr")):
        inc = int(re.search(r"i(\d+)", jfr.stem).group(1))
        ms = [m for m in marks if m["incarnation"] == inc]
        for e in jfr_events(java_home, jfr, "dev.jvmd.Stage", cache):
            v = e["values"]
            ph = phase_of(ms, iso_ms(v["startTime"]))
            a = agg[(ph, v.get("stage"))]
            a["count"] += 1; a["durationMs"] += (v.get("durationNanos") or 0) / 1e6
            if v.get("threadAllocatedBytes", -1) >= 0:
                a["allocatedBytes"] += v["threadAllocatedBytes"]; a["allocationKnown"] += 1
            if v.get("threadCpuNanos", -1) >= 0:
                a["cpuMs"] += v["threadCpuNanos"] / 1e6
            a["virtual"] += bool(v.get("virtualThread")); a["queued"] += bool(v.get("queued"))
        for e in jfr_events(java_home, jfr, "jdk.ObjectAllocationSample", cache):
            v = e["values"]
            ph = phase_of(ms, iso_ms(v["startTime"]))
            cls = (v.get("objectClass") or {}).get("name", "?")
            samples[ph][cls] += v.get("weight", 0)
        for e in jfr_events(java_home, jfr, "jdk.GarbageCollection", cache):
            v = e["values"]
            gcs.append({"incarnation": inc, "phase": phase_of(ms, iso_ms(v["startTime"])), "name": v.get("name"), "cause": v.get("cause"),
                        "sumOfPausesMs": _dur_ms(v.get("sumOfPauses")), "longestPauseMs": _dur_ms(v.get("longestPause"))})
    rows = [{"phase": k[0], "stage": k[1], **v} for k, v in agg.items()]
    return {"stages": rows, "jfr_allocation_samples": {ph: top(c, 25) for ph, c in samples.items()}, "gc_events": gcs}


def _dur_ms(d):
    if d is None:
        return None
    if isinstance(d, (int, float)):
        return d / 1e6
    m = re.match(r"PT(?:(\d+)M)?([\d.]+)S", str(d))
    return (int(m.group(1) or 0) * 60 + float(m.group(2))) * 1000 if m else None


GC_LINE = re.compile(r"\[(\d+)ms\].*?GC\(\d+\) (Pause .+?) (\d+)M->(\d+)M\((\d+)M\) ([\d.]+)ms\s*$")
HUMONGOUS = re.compile(r"\[(\d+)ms\].*?GC\(\d+\) Humongous regions: (\d+)->(\d+)")


def gc_log(run, marks):
    out = collections.defaultdict(lambda: {"pauses": 0, "pauseMs": 0.0, "young": 0, "mixed": 0, "full": 0, "remark_cleanup": 0,
                                           "heapBeforeMaxM": 0, "heapAfterMaxM": 0, "humongousRegionsMax": 0, "concurrentCycles": 0})
    for f in sorted(run.glob("gc-i*.log")):
        inc = int(re.search(r"i(\d+)", f.name).group(1))
        ms = [m for m in marks if m["incarnation"] == inc and m.get("sinceSpawnMs") is not None]
        def ph(uptime):
            prev = None
            for m in ms:
                if m["sinceSpawnMs"] >= uptime:
                    return (prev["id"] if prev else "start") + ".." + m["id"]
                prev = m
            return (prev["id"] if prev else "start") + "..end"
        for line in open(f, errors="replace"):
            m = GC_LINE.search(line)
            if m:
                o = out[(inc, ph(int(m.group(1))))]
                kind = m.group(2)
                o["pauses"] += 1; o["pauseMs"] += float(m.group(6))
                o["young"] += "Young" in kind and "Mixed" not in kind; o["mixed"] += "Mixed" in kind; o["full"] += "Full" in kind
                o["remark_cleanup"] += ("Remark" in kind or "Cleanup" in kind)
                o["heapBeforeMaxM"] = max(o["heapBeforeMaxM"], int(m.group(3))); o["heapAfterMaxM"] = max(o["heapAfterMaxM"], int(m.group(4)))
                continue
            m = HUMONGOUS.search(line)
            if m:
                o = out[(inc, ph(int(m.group(1))))]
                o["humongousRegionsMax"] = max(o["humongousRegionsMax"], int(m.group(2)), int(m.group(3)))
            elif "Concurrent Mark Cycle" in line and "ms" in line and "[gc " in line.replace("[gc,", "[gc "):
                mm = re.search(r"\[(\d+)ms\]", line)
                if mm:
                    out[(inc, ph(int(mm.group(1))))]["concurrentCycles"] += 1
    return [{"incarnation": k[0], "phase": k[1], **v} for k, v in out.items()]


def smaps_split(text, xmx_bytes):
    """Anonymous RSS split: Java heap (zero-based compressed-oops reservation just below 4 GiB), glibc malloc
    arenas (64 MiB-aligned anonymous reservations) and brk heap, JIT code (rwx), thread stacks (rw mapping right
    after a small guard mapping), other anonymous; plus file-backed. Heuristic by address/shape, stated as such."""
    heap_lo, heap_hi = (1 << 32) - xmx_bytes, 1 << 32
    maps = []
    cur = None
    for line in text.split("\n"):
        m = re.match(r"^([0-9a-f]+)-([0-9a-f]+) (\S+) \S+ \S+ (\d+)\s*(.*)$", line)
        if m:
            cur = {"s": int(m[1], 16), "e": int(m[2], 16), "perm": m[3], "inode": int(m[4]), "name": m[5].strip(), "Rss": 0, "Pss": 0, "Private_Dirty": 0}
            maps.append(cur); continue
        m = re.match(r"^(Rss|Pss|Private_Dirty):\s+(\d+) kB", line)
        if m and cur:
            cur[m[1]] += int(m[2]) * 1024
    out = collections.defaultdict(lambda: {"Rss": 0, "Pss": 0, "Private_Dirty": 0, "Size": 0, "mappings": 0})
    arena_bases = set()
    for i, mp in enumerate(maps):
        if mp["name"] == "" and mp["s"] % (64 << 20) == 0 and mp["s"] > (1 << 40):
            arena_bases.add(mp["s"])
    for i, mp in enumerate(maps):
        name = mp["name"]
        if name and not name.startswith("["):
            cat = "file-backed"
        elif name == "[heap]":
            cat = "glibc malloc (brk heap + arenas)"
        elif name.startswith("[stack"):
            cat = "thread stacks (approx.)"
        elif name.startswith("["):
            cat = "kernel/vdso"
        elif heap_lo <= mp["s"] < heap_hi:
            cat = "Java heap"
        elif "x" in mp["perm"]:
            cat = "JIT code / executable anonymous"
        elif any(b <= mp["s"] < b + (64 << 20) for b in arena_bases):
            cat = "glibc malloc (brk heap + arenas)"
        elif i > 0 and maps[i - 1]["perm"].startswith("---") and maps[i - 1]["e"] == mp["s"] and (maps[i - 1]["e"] - maps[i - 1]["s"]) <= (64 << 10) and (mp["e"] - mp["s"]) <= (16 << 20):
            cat = "thread stacks (approx.)"
        else:
            cat = "other anonymous (metaspace, GC data, NMT, JNI, ...)"
        o = out[cat]
        o["mappings"] += 1; o["Size"] += mp["e"] - mp["s"]
        for k in ("Rss", "Pss", "Private_Dirty"):
            o[k] += mp[k]
    return dict(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("runs", nargs="+"); ap.add_argument("--out", required=True)
    ap.add_argument("--async-profiler", default=os.environ.get("ASYNC_PROFILER_HOME", ""))
    ap.add_argument("--java-home", default=os.environ.get("JAVA_HOME", ""))
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
        splits = {}
        for f in sorted((run / "smaps").glob("*.smaps.gz")):
            splits[f.name.replace(".smaps.gz", "")] = smaps_split(gzip.open(f, "rt").read(), env["xmxMb"] << 20)
        if splits:
            json.dump(splits, open(dst / "smaps_split.json", "w"), indent=1)
        if mode == "exact" or mode == "rss":
            json.dump(sampler_peaks(run, marks), open(dst / "samples.json", "w"), indent=1)
        if mode == "exact":
            json.dump(gc_log(run, marks), open(dst / "gc.json", "w"), indent=1)
            if a.java_home:
                json.dump(exact_jfr(run, marks, a.java_home, cache), open(dst / "jfr.json", "w"), indent=1)
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
