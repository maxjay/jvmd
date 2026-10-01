# JVMD memory: allocation, retention, native memory and lifecycle

An evidence report. It describes where JVMD allocates and holds memory today. It proposes no
change. Every number comes from the runs listed in [Methodology](#methodology). The small
machine-readable summaries are in `benchmarks/memory/results/`. The raw evidence (JFR, HPROF,
NMT, smaps, histograms) is in the workflow artifacts listed in [Raw evidence](#raw-evidence).

Statements are labelled where the distinction matters:

- **MEASURED**: read directly from a counter, a dump or the kernel.
- **ATTRIBUTED**: assigned to code by sampled stacks or a dominator tree.
- **INFERENCE**: an interpretation of the two above.

Allocation means bytes allocated over an interval. Retention means bytes still reachable. RSS means
pages resident. These are three different quantities and are never mixed in this report.

---

## Executive summary

1. **Machine seed.** Indexing the 462-JAR fixture repository allocates **31.7–35.1 GB** of Java
   heap in 36 s (MEASURED; 7 runs, 6 of them within 33.3–35.1 GB).
   - That is **~40.5 KB per symbol**, linear in symbols (R² 0.998).
   - The persisted SST database it produces is **437 MB**, an allocation amplification of
     **~80×**.
   - Afterwards the Java heap retains **5.6–7.0 MB** after a full GC. That is the same as an
     empty repository (6.0 MB), so the machine index adds **~1 MB** of retained heap.
   - Seed allocation is almost entirely transient: SST gram postings, symbol encoding, classfile
     reading, fact construction, SST sort runs and canonical digests.
2. **First-use workspace references** on apache/maven does not produce an answer at the benchmark's
   1 GiB heap (MEASURED, 0 of 16 attempts).
   - It allocates **31–37 GB** and drives the live heap to the ceiling (763 MB live after a full GC
     at the end; 1.06 GB in the OOM dump).
   - In the 13 default-configuration attempts it ended three ways:
     - 6 in a **RocksDB WriteBufferManager write stall** that hangs the daemon (3 of the 5
       unprofiled lifecycles);
     - 6 in `OutOfMemoryError`;
     - 1 finishing server-side after ~4 minutes, with 78 full GCs and the heap at the ceiling,
       after the client had given up at 60 s.
   - With a 256 MiB RocksDB budget, 3 of 3 attempts ended at the heap ceiling.
   - At OOM, `Analyzer.compilerPools` retains 269 MB (javac outcomes and `Bindings` snapshots).
     The in-flight workspace-bindings capture holds 247 MB. 23 copies of a 5.67 MB `ZipFileSystem`
     hold 130 MB. `SemanticFact`s hold 71 MB.
   - First-use references is **the lifecycle's peak**: heap at 1 GiB, RSS 1.96–2.16 GB.
3. **A persisted daemon restart fails deterministically** after workspace use at the default
   configuration (MEASURED: 9 of 9 lifecycle restarts, plus 4 profiled and 3 manual reopen
   attempts on a preserved state).
   - The error is `RocksDBException: Insert failed due to LRU cache being full`, thrown from
     `RocksIndexStore.<init>`.
   - A restart after a machine-only seed succeeds.
   - The `store` RocksDB database holds per-source-file LOCAL fact values of up to **8.5 MB each**
     (64 MB for 80 files).
4. **RSS is mostly not Java data** (MEASURED).
   - At machine READY: RSS 418–931 MB, live heap 5.6 MB.
     - The Java heap region keeps 218–588 MB of pages resident from the seed peak.
     - glibc holds **284–298 MB, of which 215–225 MB is free inside its arenas**.
   - After a workspace session and mutations: glibc holds 738–993 MB, **555–849 MB of it free**.
   - The process is ~95% anonymous memory. File-backed residency is 37–46 MB: libjvm, the CDS
     archive, the JDK modules image and the RocksDB JNI library. No SST or Maven JAR is mmapped.
     Direct and mapped NIO buffers are 0 at every checkpoint.
5. **RocksDB native memory is bounded by its budget, but its traffic is not.**
   - The cache holds 38 MB at READY (~88 KB per artifact) and reaches the 64 MiB budget during
     workspace admission.
   - During first-use references it drives **~82 GB of malloc traffic**, 76 GB of it block
     fetch and decompression. A later POM edit drives **14 GB**; the same edit with a 256 MiB
     budget drives 0.1 GB.
6. **One Maven workspace** (apache/maven, 2 open files) retains **46 MB** once admitted, 70 MB
   after warm queries and 102 MB after mutations (live heap after full GC, above the 5.6 MB
   machine baseline).
   - Closing the session releases all but **18–24 MB**. What stays: the machine-wide
     `FileStateRegistry` (4.5–5.0 MB), `MavenResolver` caches (2.7 MB), workspace `LocalArtifacts`
     (1.1 MB) and per-session `RocksIndexStore` workspace entries.
7. **Warm editor queries allocate a fixed cost per request.**
   - Completion: 1.22 MB median, 1.26 MB for 1 candidate, 1.81 MB for a capped 50.
   - Definition: 1.15 MB. Hover: 2.96 MB. `completionItem/resolve`: 0.73 MB.
   - None of it survives. The largest share is recomputing compiler-input identity (27–40%) and
     file-state stamps (10–14%), independent of the prefix typed.
8. **Workspace-scale operations each allocate 1–2.7 GB**, dominated by Maven model resolution and
   canonical digests/`BigInteger` accumulators:
   - workspace open, ~2.5 GB;
   - POM edit, ~2.0 GB;
   - reopen after close, ~1.1 GB;
   - restart reopen, ~1.9 GB, plus 0.68 GB to the first correct answer.

---

## Methodology

### Subject and environment

| | |
|---|---|
| JVMD commit | `c8fcb9f9a2430be2d8588839c5d9c8acc808973b` (`main` when the investigation started). Production code is unchanged except one property-gated, read-only status exposure ([Instrumentation](#instrumentation-added)). |
| JDK | Temurin 25.0.4.1+1 (checksum-pinned, the benchmark's JDK) |
| OS / kernel / arch | Ubuntu 24.04.4 LTS, Linux 6.18.44, x86-64, THP `madvise` |
| CPU / memory / FS | 4 × Intel Xeon @ 2.10 GHz, 15.7 GiB, ext4 |
| Heap | `-Xmx1024m` and `heap_ceiling_mb: 1024`, the benchmark's settings. The pressure run uses 512 MiB. |
| RocksDB native budget | 64 MiB, the default (`jvmd.index.native_budget_mb`). Supplementary runs use 256 MiB (see below). |
| Machine repository | The pinned fixture's own repository: 462 JARs, 201 MB, 0 `-sources.jar` |
| Fixture | apache/maven `5cd1b60264101080c712accd605180a4bd9222e0`, as in `jvmd-benchmarks.yml` |
| Profilers | async-profiler 4.1, JFR (JDK 25), HotSpot NMT, Eclipse MAT 1.16.1, glibc `malloc_info` via `jcmd System.native_heap_info`, `/proc/<pid>/{status,smaps_rollup,smaps}` |

### What runs

`benchmarks/memory/profile.ts` drives one daemon through the lifecycle with explicit checkpoints. It
uses the benchmark's own harness, the same oracles and the same daemon command line. Each run uses
exactly one profiling mode:

| Mode | Adds to the daemon | Measures |
|---|---|---|
| `control` (RUN G) | nothing beyond the benchmark's allocation agent | exact allocation per phase, latency, RSS at checkpoints |
| `exact` (RUN A) | 50 ms `/proc` and heap sampler, `-Djvmd.trace=true`, JFR `settings=default` (GC, `dev.jvmd.Stage`), G1 log | peaks per phase, GC, RequestScope stages |
| `alloc` (RUN B) | async-profiler `alloc`, 256 KiB interval, one JFR per phase | allocation sites, classes, threads |
| `live` | async-profiler `alloc --live`, GC forced before each phase ends | survival of each phase's allocation (qualitative, see limitations) |
| `retention` (RUN C) | forced-GC class histogram at every checkpoint, HPROF at H1–H9, heap dump on OOM | live heap, dominators (MAT) |
| `nmt` (RUN D) | `-XX:NativeMemoryTracking=detail`, summary and detail diffs, glibc `malloc_info`, NMT at exit | HotSpot native, glibc arenas |
| `native` (RUN E) | async-profiler `nativemem`, 64 KiB interval, one JFR per phase | malloc/free by library and stack, unfreed view |
| `rss` (RUN F) | 50 ms `smaps_rollup` sampler, full `smaps` at every checkpoint | RSS/PSS by mapping |

Phase boundaries are checkpoints, never sleeps:

- **Allocation per phase** is the JVM-wide `ThreadMXBean.getTotalThreadAllocatedBytes` delta
  between two checkpoints, through the benchmark agent.
- **Observer cost is excluded.** At each checkpoint the snapshot is taken first. The status call
  and the mode's hooks run next. The next phase's baseline is taken only after them.
- **Peaks** come from G1 pool peaks, reset at every checkpoint, and from the 50 ms sampler.
- **"Settled"** means JVM-wide allocation stayed below 1 MiB/s for two consecutive 1 s windows.

### Two configurations, and why

The default configuration cannot complete the lifecycle. First-use references ends in a RocksDB
write stall (the daemon hangs) or in an OOM. Every later phase is then either unmeasurable or
measured in a degraded daemon. So the matrix runs every mode twice:

- **Primary**: the default configuration. Every number for machine seed, workspace open,
  admission, first and warm completion/definition/hover and first-use references comes from it. So
  does the stall, the OOM and the restart failure.
- **Supplementary ("b256")**: `-Djvmd.index.native_budget_mb=256`, an existing runtime property
  (no code change), with first-use references skipped. It exists only to reach the later phases
  in a healthy daemon: mutations, close, reconnect, reopen and restart. Its RocksDB cache numbers
  reflect the 256 MiB budget and are labelled. Its Java allocation for phases both configurations
  reach matches the primary within 0–9% ([Lifecycle](#lifecycle-memory-map)).
- **References to completion** use dedicated 256 MiB runs (`b256-refs-*`) that include
  references, so the OOM itself is profiled and dumped.

### Runs and repetition

| Group | Runs | Repetition |
|---|---|---|
| Unprofiled benchmark baseline | `node benchmarks/run.ts --only lifecycle` | single observation (hung at reconnect) |
| Primary control (lifecycle) | `control-2`, `control-3`, plus `dry-control` (driver validation) | 2 complete observations |
| Primary profiled | `exact-1`, `alloc-1`, `live-1`, `retention-1`, `nmt-1`, `nmt-2`, `native-1`, `rss-1` | 1 per mode |
| Supplementary control | `b256-control`, `b256-control-2`, `b256-control-sizes` (result sizes) | 2–3 observations |
| Supplementary profiled | `b256-{exact,alloc,live,retention,nmt,native,rss}`, `b256-pressure-512` | 1 per mode |
| References to completion | `b256-control-1` (validation), `b256-refs-alloc`, `b256-refs-retention` | 3 observations, all at the heap ceiling (2 logged `OutOfMemoryError`; 1 lost its client connection) |
| Scale / per-JAR | `scale-{empty,n100,n250,n350,full}`, `jar-one-*`, `scale-full-{nmt,native}` | 1 per size |
| Restart failure | `restartfail-{exact,nmt,native,alloc}` on a preserved state, plus 3 manual repetitions; also every default-configuration lifecycle that attempted a restart | 4 + 3 + 9 |
| Humongous allocations | `b256-humongous` (JFR `ObjectAllocationOutsideTLAB` with stacks) | 1 |
| Second environment | GitHub Actions run [36793765544](https://github.com/maxjay/jvmd/actions/runs/36793765544), the whole matrix on `ubuntu-24.04` | 1 per mode |

Warm queries are 2 discarded warmups plus 100 measured requests, or 30 for prefixes and 10 for
references. Each warm loop therefore holds 200–440 measured samples across runs. Machine seed and
first-use references are expensive. They are reported per run with their run count; no
statistical confidence is claimed for them.

### Correctness

Every profiled request goes through the benchmark's oracle. A failed oracle is recorded with the
operation, and nothing is reported as a profile of a correct operation unless it passed. Across
all runs, the only oracle failures other than references are these:

- `documentSymbol` on `MavenProject.java` is answered with `-32005 "Editor result exceeds 64 KiB;
  request partial results"`. This is a response-size contract, consistent across modes.
- After an OOM, the daemon answered one `documentSymbol` with `[]`. That run is labelled degraded.

### Profiler overhead

Phase wall time per mode against the same configuration's control (seconds; the control lists
both runs). Allocation volume is unaffected by the profilers (differences ≤ 3%).

| Phase | control | exact | alloc | live | retention | nmt | native | rss |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| machine seed (primary) | 37.1 / 36.0 | 42.5 | 40.8 | 43.2 | 35.5 | 34.4 / 36.9 | 40.0 | 42.0 |
| workspace open (primary) | 6.2 / 5.7 | 6.3 | 6.2 | 8.0 | 5.7 | 6.4 / 6.1 | 6.3 | 6.1 |
| admission (primary) | 19.2 / 18.9 | 22.1 | 20.1 | 21.7 | 18.6 | 17.3 / 20.1 | 19.5 | 19.5 |
| warm completion ×102 (supp.) | 0.80 / 0.77 | 0.88 | 0.78 | 0.78 | 0.86 | 0.84 | 0.78 | 0.89 |
| warm hover ×102 (supp.) | 1.23 / 1.04 | 1.19 | 1.09 | 1.09 | 1.08 | 1.04 | 1.09 | 1.13 |
| POM edit (supp.) | 5.12 / 5.04 | 5.11 | 6.16 | 5.87 | 5.04 | 5.18 | 5.16 | 5.01 |

Hook time (histograms, dumps, NMT reports, profiler stop/start) is excluded from phase windows by
construction. Ordinary latency figures in this report come only from control runs.

### Instrumentation added

All of it is observational:

- `benchmarks/harness/agent/AllocationAgent.java` answers two extra request bytes.
  - `s` returns a JSON snapshot: total allocated, heap, pools with peaks, buffer pools, GC counts,
    and the probe's own allocation.
  - `p` returns the same snapshot and resets pool peaks.
  - The existing `?` protocol is unchanged. One behaviour change: the agent now answers every
    request byte, as its comment always documented, rather than one line per `read()`.
  - The agent also survives an `OutOfMemoryError` in the measured JVM.
- `Application` gains a read-only exposure of the in-progress first-scan status, gated by
  `-Djvmd.profile.bootstrap_status` (off by default). Without it, seed progress (M2) is
  unobservable: `daemon.status.index` is `{"phase":"starting"}` until READY.

### Limitations of the measurements

- JVM-wide allocation includes background work: diagnostics actors, source publication, and the
  60 s machine rescan. The driver therefore settles before each measured phase.
  - The validation run shows why this matters. Warm definitions measured 38.5 MB per request
    while admission was still running, and 1.15 MB per request once it had settled.
- The async-profiler allocation sampler on JDK 25 cannot separate in-TLAB from outside-TLAB
  allocations. Humongous allocations come from a separate JFR run.
- Virtual threads appear under their `ForkJoinPool-1-worker-N` carrier in async-profiler. Index
  readers, the source publisher and RPC connections are all virtual. RequestScope reports no
  allocation for virtual-thread spans (`threadAllocatedBytes = -1`). These are reported as
  "unavailable", never as 0.
- async-profiler's `live` mode undercounts survivors in this workload. Workspace open raised the
  post-GC live heap by ~55 MB while `live` attributed 1.7 MB to it. It is used only qualitatively.
- The `nativemem` sampler (64 KiB interval) measures malloc/free traffic well. Its "unfreed" view
  understates long-lived RocksDB cache contents (a few MB versus 38–64 MB reported by the cache).
  RocksDB residency is therefore taken from RocksDB's counters and from glibc `malloc_info`.
- The anonymous-RSS split by address is heuristic: the Java heap reservation, 64 MiB-aligned glibc
  arenas, executable mappings and stack-shaped mappings. HotSpot metaspace reservations are also
  64 MiB-aligned and are counted with the arenas (13–39 MB).

---

## Current instrumentation (checkpoint 0)

- **Benchmark allocation probe** (`benchmarks/harness/agent`). It reads
  `ThreadMXBean.getTotalThreadAllocatedBytes()` before and after each request. The value is
  JVM-wide, so it includes background work, and it carries no class, site or retention
  information.
- **`RequestScope.Span`** (`jvmd-core/.../RequestScope.java`).
  - Tracing is enabled with `-Djvmd.trace=true`.
  - Each span is emitted as a `dev.jvmd.Stage` JFR event with duration, platform-thread CPU and
    platform-thread allocation.
  - Spans that are queued, or that run on virtual threads, record −1. That covers
    `index.bootstrap`, `index.scan`, `index.binary`, `index.sources`, `facts.background_publish`,
    `rpc.execute` and `response.encode`.
- **RocksDB memory status** (`RocksMemory`).
  - One `LRUCache(64 MiB, strict_capacity_limit=true)` is shared by five databases: `db`,
    `inventory`, `semantic-state`, `workspace-state` and `store`.
  - They also share a `WriteBufferManager(16 MiB, cache, allow_stall=true)`. Each database has
    `write_buffer_size` 4 MiB and up to 2 write buffers.
  - The status reports `cache_and_memtable_budget_bytes`, `cache_usage_bytes` and
    `cache_pinned_bytes`, but only after READY.
- **Benchmark lifecycle** (`benchmarks/harness/lifecycle.ts`):
  1. Start the daemon to READY.
  2. Open a workspace, then query (hover, definition, references, signature help, document
     symbol, semantic tokens, completion).
  3. Edit `MavenProject` and check the new member is completed; introduce an error and check the
     diagnostic.
  4. Reconnect to the retained session.
  5. Restart the daemon and reopen.

---

## Lifecycle memory map

### Checkpoints

| ID | Boundary | How it is observed |
|---|---|---|
| M0 | process spawned | allocation probe connected (first JVM snapshot) |
| M1 | JVM initialised, index empty | first `daemon.status` answered |
| M2-0…M2-100 | seed progress | `scanned/total` crosses 0/10/25/50/75/100 % (gated status) |
| M3 / M4 | READY / idle | `READY` on stdout / settled |
| M5- / M6 | workspace session created / resolution complete | before LSP `initialize` / `initialize` returned (`session.open` runs Maven resolution synchronously) |
| M7 | index binding | not separately observable: binding happens lazily inside the first analyzer context, within M8→M9a (JFR `analyzer.context.*` stages) |
| M8 / M9a / M9 | documents opened / first diagnostics / admission settled | `didOpen` sent / versioned `publishDiagnostics` for both files / settled |
| M10 / M11 | first correct completion / warm loop done | oracle `getGroupId` |
| M11p-* | prefix narrowing | `project.`, `project.g`, `project.get`, `project.getM` |
| M11r-* | `completionItem/resolve` first / warm | |
| M12 / M13 / M14 | first definition / hover / references (each followed by a warm loop) | exact locations / hover text / both reference sites |
| M15 | body-only edit admitted | versioned diagnostics, no errors |
| M16 / M16u / M16a / M16r | relevant API / unrelated API / source added / source removed | new member completed / unrelated diagnostics / new type completed / type no longer completed |
| M17 / M17r | POM dependency added / reverted | `commons-lang3` member completed / no longer offered |
| M18 / M18b / M20 | documents closed / adapter disconnected (session kept) / reconnect, first correct definition | |
| M19 / M19s | `session.close` returned / settled | |
| M20b / M20bx | reopen after close, first correct definition / closed again, settled | |
| M21 / M22 | shutdown requested / process exited | |
| M23 / M24 / M24i | restart spawned / persisted index READY / idle | |
| M25 / M26 / M26b | workspace reopened / first correct definition / first correct completion | |

### Stable memory

Supplementary control runs (`b256-control`, `b256-control-2`; two values each). Live heap is from
the matching `retention` run: post-full-GC heap used. Primary values are given where the primary
lifecycle reaches the state.

| State | Live heap | Heap used / committed | RSS | PSS | RSS anon / file | RocksDB cache / pinned | Direct / mapped |
|---|---:|---:|---:|---:|---:|---:|---:|
| Machine ready (M4) | **5.6 MB** | 31–116 / 94–216 MB | 418 / 551 MB (primary 614 / 692) | 415 / 548 | 380–513 / 37 | 38.2 / 1.5 MB | 0 / 0 |
| Workspace ready (M9) | **51.7 MB** | 150–187 / 183–488 | 743 / 1024 (primary 790 / 1040) | 740 / 1021 | 697–978 / 46 | 113 / 14.7 (primary **63.8** / 15.2, at the 64 MiB budget) | 0 / 0 |
| Warm editor (M14x) | **75.5 MB** (primary after references: 464 MB) | 111–213 / 267–357 | 877 / 930 (primary 1899) | 874 / 927 | 831–884 / 46 | 114 / 14.7 (primary 15.9 / 14.1) | 0 / 0 |
| After mutations (M17r) | 107.3 MB | 182–319 / 344–357 | 1050 / 999 | 1047 / 996 | 1004–953 / 46 | 112 / 2.5 | 0 / 0 |
| Reconnected (M20) | 102.4 MB | 141–237 / 344–357 | 1050 / 1001 | 1047 / 998 | 1004–955 / 46 | 112 / 2.5 | 0 / 0 |
| Session closed (M19s) | **24.0 MB** | 162–263 / 344–357 | 1037 / 988 | 1034 / 985 | 991–942 / 46 | 112 / 2.5 | 0 / 0 |
| All sessions closed (M20bx) | 28.7 MB | 83–120 / 190–197 | 893 / 827 | 890 / 824 | 847–781 / 46 | 112 / 2.5 | 0 / 0 |
| Restarted, idle (M24i) | **5.4 MB** | 59–60 / 216 | 193 / 194 | 190 / 191 | 155–156 / 38 | 38.5 / 1.4 | 0 / 0 |
| Restarted, workspace + queries (M26b) | 61.4 MB | 89–125 / 194–197 | 527 / 577 | 524 / 574 | 482–531 / 46 | 108 / 1.5 | 0 / 0 |

- PSS ≈ RSS: almost nothing is shared.
- Restarted RSS (193 MB) is far below first-boot RSS at the same logical state (418–931 MB). The
  difference is seed residue: heap pages G1 committed during the seed peak, plus freed glibc arena
  memory.

### Allocation by phase

Exact JVM-wide allocation per phase. "Primary" columns are `control-2` and `control-3`;
"supplementary" columns are `b256-control` and `b256-control-2`. Peak heap and peak RSS come from
the `exact` runs: the 50 ms sampler for RSS, G1 pool peaks for heap.

| Phase | Primary alloc (MB) | Supp. alloc (MB) | Wall (s, primary / supp.) | Peak heap used (MB) | Peak RSS (MB) |
|---|---:|---:|---:|---:|---:|
| JVM start to M1 | 21 / 21 | 22 / 22 | 0.4 | 32 | 107 |
| **Machine seed (M1→M3)** | **31 737 / 33 377** | **33 380 / 33 251** | 37.1 / 35.6 | 521–528 (pool sum 588–673) | **962–1026** |
| Workspace open: `session.open` incl. Maven resolution | 2 707 / 2 548 | 2 387 / 2 551 | 6.2 / 5.5 | 276–291 | 746–790 |
| Admission (didOpen → settled) | 1 807 / 1 934 | 2 092 / 1 942 | 22.2 / 22.0 | 352–392 | 898–966 |
| First completion (incl. admitting the probe edit) | 419 / 417 | 416 / 417 | 0.7 | 218–225 | 736–790 |
| Warm completion ×102 | 123 / 123 | 123 / 123 | 0.8 | 209–213 | 733–776 |
| `completionItem/resolve`, first | 22.5 | 22.1 | 0.13 | | |
| First definition | 16.0 | 16.0 | 0.07 | | |
| Warm definition ×102 | 117 | 117 | 0.7 | | |
| First hover | 32.0 | 31.9 | 0.13 | | |
| Warm hover ×102 | 303 | 303 | 1.0 | | |
| **First-use references** | **36 072** (+ warm attempt 8 667–11 627) | n/a (skipped) | 178 (to OOM / stall) | **1 022 (ceiling)** | **1 959–2 155** |
| Body-only edit admitted | | 128 / 131 | 0.8 | 213 | 777 |
| Completion after body edit | | 21.4 | 0.09 | | |
| Relevant API edit | | 91 / 92 | 0.3 | 210 | 782 |
| Unrelated document opened / unrelated API edit | | 157 / 44 | 0.7 / 0.35 | | |
| Source file added / removed | | 48 / 142 | 1.9 / 2.4 | | |
| **POM dependency added** | | **1 971 / 1 974** | 5.1 | 282 | 929 |
| POM reverted | | 1 932 / 1 933 | 4.7 | | 969 |
| Reconnect to retained session (to first correct answer) | | 51 / 51 | 0.3 | | |
| `session.close` | | 1.2 | 0.06 | | |
| **Reopen after close** (to first correct answer) | 1 052 | 1 091 / 1 091 | 17.6 | 305 | 943 |
| Restart: JVM start to READY | — (fails) | 69 / 69 | 0.9 | 73 | 214 |
| Restart: workspace reopen | — | 1 864 / 1 880 | 4.6 | 148 | 482 |
| Restart: first correct definition | — | 675 / 675 | 17.6 | 169 | 601 |
| Restart: first completion | — | 66 / 67 | 0.4 | 159 | 539 |

Rates and normalisations:

- Machine seed allocates at ~0.93 GB/s.
- Workspace open allocates 0.45 GB/s, admission 0.09 GB/s.
- Seed per artifact: 33.4 GB / 462 = 72 MB per JAR.

### GC behaviour by phase

From the supplementary `exact` run's G1 log; the primary run is used for references.

| Phase | GC events | Young | Mixed | Full | Pause (ms) | Heap after GC, max | Humongous regions, max | Concurrent cycles |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Seed M1→M2-0 | 8 | 8 | 0 | 0 | 113 | 106 MB | 0 | 0 |
| Seed M2-0→M2-10 | 30 | 20 | 4 | 0 | 499 | 277 MB | 0 | 6 |
| Seed M2-10→M2-25 | 51 | 20 | 16 | 0 | 755 | 359 MB | 0 | 15 |
| Seed M2-25→M2-50 | 76 | 19 | 28 | 0 | 688 | **475 MB** | 9 | 29 |
| Seed M2-50→M2-75 | 61 | 15 | 24 | 0 | 462 | 282 MB | 0 | 22 |
| Seed M2-75→M3 | 67 | 19 | 24 | 0 | 500 | 207 MB | 1 | 24 |
| Workspace open | 68 | 18 | 26 | 0 | 395 | 161 MB | 11 | 24 |
| Admission (to M9a) | 21 | 5 | 8 | 0 | 227 | 271 MB | 11 | 8 |
| First completion | 9 | 2 | 3 | 0 | 58 | 99 MB | 28 | 4 |
| Warm loops (each ×30–102) | 1–3 | | | 0 | 2–14 | 81–107 MB | 5–9 | 0–2 |
| POM add / revert | 21 / 17 | | | 0 | 187 / 108 | 225 / 258 MB | 10 / 12 | 8 / 6 |
| **First-use references (primary `exact-1`)** | 544 + 507 (warm) | | | **97 + 160** | **46 096 + 71 760** | 1 024 MB | **205** | |

- During seed, heap occupancy *after* collections reaches 475 MB. The post-full-GC live set at
  the same checkpoints is only 25–90 MB (retention run). Transient seed data therefore survives
  young collections long enough to be promoted and to trigger concurrent cycles: 96 cycles per
  seed.
- During references the JVM spends ~118 s in pauses (257 full GCs) before the OOM.

---

## Machine-index allocation

### Volume and normalisations (MEASURED)

| Quantity | Value |
|---|---|
| Exact allocation, full seed | 31.7–35.0 GB (7 runs: controls 31.7, 33.4, 33.4, 33.3; profiled 34.2–35.1) |
| Per indexed JAR | 72–76 MB (`seedAllocated ~ artifacts` R² 0.981) |
| Per symbol | **40.5 KB** (slope over 5 sizes, R² 0.998) |
| Per edge | 30.2 KB (R² 0.9985) |
| Persisted SST database | 436–437 MB (**503.6 B per symbol**, R² 0.99993); total state 437 MB |
| **Allocation amplification** (allocated / persisted SST bytes) | **~80×** full seed; groovy 97×, commons-lang3 92×, guava 82× |
| Retained heap after seed | 5.6–7.0 MB live (empty repository: 6.0 MB). Slope ~1.9 KB per artifact (R² 0.87). `RocksIndexStore` retains 0.34 MB at READY (artifacts map 240 KB, paths 27 KB) |
| RocksDB cache at READY | 38–40 MB, slope **88.5 KB per artifact** (R² 0.99); pinned 1.5 MB |
| Peak heap used | 521–528 MB (sampler); single JARs: groovy 499 MB, guava 246 MB, commons-lang3 133 MB, javax.inject 36 MB (empty: 30 MB) |
| Peak RSS | 962–1 026 MB |
| Native malloc traffic (nativemem) | 11.6 GB: HotSpot 7.0–10.9 GB (C2 compiler arenas), RocksDB 2.96 GB (2.7 GB SST writing and Snappy buffers), zlib 0.19 GB |

### Allocation sites and classes (ATTRIBUTED; `b256-alloc`, 133 K samples, 35.0 GB sampled vs 34.2 GB exact)

**Mechanism** is the innermost JVMD/library frame matching a rule. **Operation** is the outermost
pipeline frame: for seed it is 100% `IndexService.scan → indexJar`. **Thread**: 100% virtual
index readers (`jvmd-index-reader-N`, carried on `ForkJoinPool-1`).

| Mechanism (component) | Share | Top site |
|---|---:|---|
| Classfile reading (`BinaryReader`, `CodeReader`, `java.lang.classfile`) | 21% | `BinaryReader.read → byte[]` (class bytes) |
| SST gram postings (`GramPostings`) | 16% | `GramPostings$Block.<init> → int[]` |
| Symbol/resolution encoding (`ArtifactIndexFormat.encode*/write*`) | 14% | `ArtifactIndexFormat.writeString → byte[]` |
| Fact construction (`ArtifactIndexFormat.from`, `ResolutionFact`, `SemanticType`) | 14% | `ResolutionFact.<init>` and `JavaTypes.type` |
| SST sort/spill/merge (`SstSorter`) | 11% | `SstSorter$Run.readBytes → byte[]` |
| Canonical digests (`CanonicalDigestWriter`, `Hash256`, SHA-256) | 10% | `CanonicalDigestWriter.write → byte[]` |
| SST keys (`relativeKey`, `hex8`, SCIP suffix) | 6% | `RocksArtifactRepository.relativeKey → byte[]` |
| SST writing (other `RocksArtifactRepository`) | 3% | |
| Jackson / JSON | 2% | `StoredArtifact` metadata |
| Artifact hashing (SHA-1 sidecar + SHA-256 of JAR) | ~1% | 64 KiB read buffers, `MessageDigest` |

Classes by bytes: `byte[]` 47%, `int[]` 13%, `String` 4.8% (1.66 GB), `Object[]` 3%,
`SstSorter$Entry` 2.5%, stream pipeline objects ~5%, `LinkedHashMap$Entry` 1.8%,
classfile `Utf8EntryImpl` 1%.

Module rollup (innermost owner):

| Owner | Share |
|---|---:|
| JDK collections | 35% |
| jvmd-index-rocks | 22% |
| JDK strings/text | 22% |
| JDK I/O | 5% |
| jvmd-index | 3.5% |
| JDK classfile API | 3% |
| JDK security/digest | 3% |
| jvmd-core | 2.4% |
| Jackson | 1.3% |
| JDK ZIP/JAR | 0.6% |
| RocksDB JNI | ~0% |

By library owner (nearest JVMD/library frame): jvmd-index 45%, jvmd-index-rocks 35%, jvmd-core 13%,
JDK classfile API 5%, Jackson 2%, ZIP/JAR 1%.

Top-20 seed allocation sites (innermost JVMD frame → allocated class; 35.0 GB sampled):

| # | Site → class | GB | % |
|---:|---|---:|---:|
| 1 | `rocks.GramPostings$Block.<init>` → `int[]` | 4.34 | 12.4 |
| 2 | `ArtifactIndexFormat.writeString` → `byte[]` | 3.02 | 8.6 |
| 3 | `BinaryReader.read` → `byte[]` | 1.96 | 5.6 |
| 4 | `rocks.SstSorter$Run.readBytes` → `byte[]` | 1.75 | 5.0 |
| 5 | `core.CanonicalDigestWriter.write` → `byte[]` | 1.15 | 3.3 |
| 6 | `ArtifactIndexFormat.encodeSymbol` → `byte[]` | 1.13 | 3.2 |
| 7 | `rocks.GramPostings.flush` → `byte[]` | 0.81 | 2.3 |
| 8 | `rocks.SstSorter$1.accept` → `byte[]` | 0.77 | 2.2 |
| 9 | `rocks.RocksArtifactRepository.writeSst` → `byte[]` | 0.76 | 2.2 |
| 10 | `core.CanonicalDigestWriter.digest` → `byte[]` | 0.70 | 2.0 |
| 11 | `rocks.RocksArtifactRepository.relativeKey` → `byte[]` | 0.63 | 1.8 |
| 12 | `ArtifactIndexFormat.lambda$resolutionIdentity` → `byte[]` | 0.41 | 1.2 |
| 13 | `ArtifactContext.scip` → `byte[]` | 0.40 | 1.2 |
| 14 | `core.JavaTypes.type` → `byte[]` | 0.31 | 0.9 |
| 15 | `ArtifactIndexFormat.writeResolution` → `byte[]` | 0.30 | 0.8 |
| 16 | `rocks.SstSorter.add` → `SstSorter$Entry` | 0.29 | 0.8 |
| 17 | `rocks.RocksArtifactRepository.hex8` → `byte[]` | 0.29 | 0.8 |
| 18 | `core.JavaTypes.type` → `String` | 0.27 | 0.8 |
| 19 | `core.CanonicalDigestWriter.digest` → `SHA2$SHA256` | 0.25 | 0.7 |
| 20 | `rocks.SstSorter$PostingWriter.put` → `SstSorter$Entry` | 0.22 | 0.6 |

The top 20 sites cover 66% of seed allocation. Top-50 classes (by bytes and by sample count) and
top-50 stacks for every phase are in `benchmarks/memory/results/allocation-top50.json`.

JFR `dev.jvmd.Stage` cannot attribute seed allocation exactly: `index.scan` and its children run
on virtual threads and report allocation −1. The sampled profile above is therefore the only
attribution of seed allocation.

### Per-JAR profiles (MEASURED; one JAR per repository, `exact`)

The empty-repository control is subtracted where noted. No fixture JAR has a `-sources.jar`, so
documentation indexing allocates nothing in this repository.

| JAR | Bytes | Classes | Symbols | Edges | Seed alloc | Alloc / symbol | Peak heap (−30 MB baseline) | Retained Δ | Persisted SST | Wall |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| javax.inject-1 | 2.5 KB | 6 | 8 | 28 | 8.3 MB (−2.5 empty) | — | +6 MB | +0.4 MB | 51 KB | 0.16 s |
| commons-lang3-3.20.0 | 714 KB | 422 | 6 460 | 8 503 | 285 MB | 44 KB | +103 MB | +0.4 MB | 3.1 MB | 1.2 s |
| guava-33.4.8-jre | 3.0 MB | 1 968 | 20 317 | 25 867 | 860 MB | 42 KB | +216 MB | +0.3 MB | 10.4 MB | 2.7 s |
| groovy-4.0.15 | 7.6 MB | 4 584 | 51 066 | 70 302 | 2 478 MB | 49 KB | **+469 MB** | +0.4 MB | 25.6 MB | 6.2 s |

Native allocation per JAR was not measured separately; the full-seed nativemem profile is above.

### Machine-index object flows

```text
byte[] (class bytes)          created by BinaryReader.read (readAllBytes of each .class entry)
                              phase machine seed · 1.96 GB · retained after READY ~0
                              purpose classfile parsing input · held while the JAR's Content (all ClassModels) is alive

int[] posting blocks          created by GramPostings$Block.<init> in RocksArtifactRepository.writeSst/addGram
                              phase machine seed · 4.34 GB (+0.81 GB flush buffers) · retained ~0
                              purpose n-gram postings for name search · destination SST keys `8|gram|`

byte[] encoded symbols        created by ArtifactIndexFormat.encodeSymbol/writeString/writeResolution
                              phase machine seed · ~4.8 GB · retained ~0 · destination SST values `1|symbol|`

byte[] sort runs              created by SstSorter (spill buffers 4 MiB, run reads, posting writer)
                              phase machine seed · ~3.8 GB · retained ~0 · destination staged SST → ingested

byte[] digests / SHA-256      created by CanonicalDigestWriter (ResolutionFact identity, resolution identities)
                              phase machine seed · ~3.4 GB · retained ~0 · purpose content identities
```

---

## Workspace and admission allocation

### Workspace open (`session.open`, M5-→M6): 2.4–2.7 GB, 5.5–6.2 s, live heap 5.6 → 61 MB

| Owner | Share |
|---|---:|
| **Operation:** project resolution (Maven model, `MavenResolver`, aether) | 68% |
| **Operation:** local workspace artifacts (`IndexService.registerLocal` → `LocalArtifacts`) | 32% |
| **Mechanism:** Maven model / resolver code | 50% |
| **Mechanism:** canonical digests | 8% |
| **Mechanism:** javac | 7% |
| **Mechanism:** fact construction and classfile reading | 8% |

`LocalArtifacts` builds SSTs for the workspace's own modules, as the machine seed does for JARs.

- Threads: resolver platform thread 42%, virtual 32%, session thread 26%.
- JFR stage `project.resolve`: 6 invocations over the run, 2.8 GB exact platform-thread
  allocation, 16 s.

### Admission (M8→M9): 1.8–2.1 GB, ~22 s

| Operation | Share |
|---|---:|
| Local workspace artifacts | 55% |
| Diagnostics module actors (`jvmd-module-*`) | 26% |
| Annotation-processing preparation | 12% |
| Source-fact publication | 4% |

- **Mechanisms:** canonical digests 24%, javac 14%, SST gram postings 6%, classfile reading 6%,
  `AlgebraicAccumulator`/`BigInteger` 6%.
- **Classes:** `byte[]` 44%, `int[]` 13%, `MutableBigInteger` 2%.
- **JFR stages:**
  - `compiler.enter_attribute`: 26 runs, 454 MB.
  - `compiler.prepare`: 919 MB.
  - `module.execute`: 785 MB.
  - `analyzer.context.create`: 23 runs, 1.44 GB.
  - `annotation_processing.prepare`: 552 calls over the run, 1.40 GB.

M7 (index binding) is inside this window: `analyzer.context.prepare/lookup/create`.

### What one workspace retains (MEASURED, post-GC; dominators ATTRIBUTED by MAT)

| State | Live heap | Above machine baseline |
|---|---:|---:|
| Admitted (M9) | 51.7 MB | **+46 MB** |
| After warm queries | 75.5 MB | +70 MB |
| After mutations | 107.3 MB | +102 MB |

At M9 (H2 dump) the top dominators are:

| Owner | Retained | Classification |
|---|---:|---|
| `ModuleAnalyzerRegistry$Actor` (diagnostics actor and its `Analyzer`) | 15.3 MB | LOCAL / compiler |
| `CompilerPool`s (union retained set) | 10.1 MB | compiler |
| `ZipFileSystem` (one per javac platform; INFERENCE: `lib/ct.sym`) | 5.67 MB | compiler, duplicated per pool |
| `FileStateRegistry` (machine-wide file stamps) | 3.5 MB | shared cache |
| `LiveSourceState` × 77 (each with a watcher thread) | 3.7 MB | LIVE |
| `Session` state map | 2.8 MB | LOCAL |
| `Resolution` (Maven project graph) | 1.6 MB | LOCAL |
| `IndexService` (`LocalArtifacts` 1.1 MB, `RocksIndexStore` 0.54 MB) | 1.8 MB | MACHINE + LOCAL |
| `MavenResolver` | 1.0 MB | shared cache |
| `SemanticFact` / `ResidentSemanticState` | 0.8 / 0.3 MB | LOCAL semantic |

After mutations (H4) the session state map retains 21.7 MB, the actor 33.2 MB, and the
`CompilerPool` retained set 45 MB. There are now **4** `ZipFileSystem`s of 5.67 MB (22.7 MB), one
per compiler generation.

The thread count rises from 35 to 196: 77 `LiveSourceState` watchers plus module actors. Thread
stacks hold 16 MB RSS.

---

## Query allocation

### First use versus warm

Exact JVM-wide allocation per request. Medians come from both configurations' control runs; the
two agree within 1%. Result sizes come from `b256-control-sizes`.

| Request | First use | Warm median | Warm p95 | Warm latency median / p95 | Result | Bytes per result |
|---|---:|---:|---:|---:|---|---:|
| completion `project.getGr` (1 candidate) | **417 MB** (first completion, incl. the probe edit) | 1.26 MB | 1.26 MB | 8.2 / – ms | 1 item, 457 B | 1.26 MB/item |
| completion `project.` (capped 50) | 37.9 MB (after the edit) | 1.86 MB | 1.87 MB | 10.0 ms | 50 items, 24.6 KB | 37 KB/item; 75 B per response byte |
| completion `project.g` / `.get` | 37.2 / 37.4 MB | 1.81 / 1.81 MB | 1.82 / 1.81 | 9.9 / 10.2 ms | 50 items | 36 KB/item |
| completion `project.getM` | 36.2 MB | 1.28 MB | 1.28 MB | 8.5 ms | 6 items | 214 KB/item |
| all warm completions (440 samples) | | 1.22 MB | 1.75 MB | 7.4 / 10.9 ms | | |
| `completionItem/resolve` | 22.4 MB | 0.73 MB | 0.74 MB | 2.2 / 3.1 ms | 437 B | |
| definition | 16.0 MB | 1.15 MB | 1.15 MB | 5.8 / 6.4 ms | 1 location | |
| hover | 32.0 MB | 2.96 MB | 2.97 MB | 8.9 / 10.7 ms | 1 036 B | |
| references (first use) | **31–37 GB, OOM or stall** | — | — | — | — | — |

Prefix narrowing does not change cost per request beyond candidate count:

- a 1-candidate list costs ~1.26 MB;
- 6 candidates cost 1.28 MB;
- a capped 50 costs 1.81–1.86 MB (+~11 KB per extra candidate).

The fixed per-request cost dominates. Nothing a warm query allocates survives: the post-GC live heap
is unchanged across each 102-request loop, and `live` mode shows 0.0 MB survivors.

### Where warm requests allocate (ATTRIBUTED, `b256-alloc`)

| Request (×102) | Exact | Mechanisms |
|---|---:|---|
| warm completion | 128 MB | compiler-input identity (`CompilerInputs.compose/write`) 34%, `Application` 20%, completion materialization 13%, file-state stamps (`FileStateRegistry.stamp`) 10%, Maven model 6% |
| warm definition | 123 MB | compiler-input identity 40%, file-state stamps 12%, completion-context code 11% |
| warm hover | 316 MB | compiler-input identity 27%, file-state stamps 14%, completion-context code 11%, analyzer 9%, source-fact decoding 6% |
| warm `completionItem/resolve` | 79 MB | source-fact decoding (`FactCodec`/`KeyedFacts`/`SourceOverlay`) 57%, `jvmd-index-rocks` 19%, RocksDB JNI 7% |

Prefix-loop allocation is dominated by Jackson/JSON encoding (32–34%) when 50 candidates are
returned.

JFR stages for every RPC show what each request recomputes:

- `analyzer.context.prepare`: 578 calls, 2.20 GB over the run.
- `inputs.validate`: 573 calls, 2.06 GB.
- `annotation_processing.prepare`: 552 calls, 1.40 GB.
- `analyzer.configure`: 318 MB.
- `analyzer.context.identity`: 300 MB.
- `completion.materialize`: 244 calls, 1.41 GB.

The whole-run session thread totals 7.63 GB over 606 `session.execute` spans.

### First use: javac versus JVMD (ATTRIBUTED)

| Request | javac share | JVMD mechanisms |
|---|---:|---|
| First completion (437 MB) | 5% | canonical digests 52%, `AlgebraicAccumulator`/`BigInteger` 23%, jvmd-core 10% |
| First definition (17 MB) | 53% | semantic declaration extraction 12% |
| First hover (34 MB) | 37% | semantic declaration extraction 30% |
| First-use references (36.8 GB) | 27% | canonical digests 22%, source-fact encoding 16%, accumulators 9%, semantic declaration extraction 4% |
| Admission (2.0 GB) | 14% | digests 24%, local-artifact SSTs ~25% |

Across the whole run, JFR's exact platform-thread allocation for javac stages is:

| Stage | Allocation |
|---|---:|
| `compiler.prepare` | 919 MB |
| `compiler.enter_attribute` | 454 MB |
| `compiler.parse` | 17 MB |

That is 1.39 GB of the 7.63 GB the session thread allocated. The rest is JVMD identity, digest and
materialisation work.

### First-use references (ATTRIBUTED, `b256-refs-alloc` 36.8 GB sampled / 37.1 GB exact; `alloc-1` 31.7 GB)

- **Operations:** workspace bindings (`Application.occurrences → WorkspaceBindings.getBatch →
  load/capture`) 78–79%, background source-fact publication (`SourceIndexPublisher`) 20–22%.
- **Threads:** session 78%, virtual (publisher) 22%.
- **Mechanisms:** javac 17–27%, canonical digests 22–25%, source-fact encoding 16–19%,
  accumulators 9–11%, semantic declaration extraction 4–5%.
- **Classes:** `byte[]` 35–40%, `int[]` 7–8%, javac `List`/`ListBuffer` ~14%, `String` 4%.

---

## Mutation allocation

From the supplementary configuration: two control runs plus the alloc run.

| Mutation | Exact allocation | Wall | Dominant operation / mechanism | Retained Δ (live) | Native | RSS peak |
|---|---:|---:|---|---:|---:|---:|
| Body-only edit (`MavenProject.getGroupId` body) admitted | 128–131 MB | 0.8 s | diagnostics actors 76%; javac 38%, semantic declaration extraction 14% | ~0 (75.5 → 75.5 MB) | HotSpot only | 777 MB |
| Completion after body edit | 21 MB | 0.09 s | | | | |
| Relevant API edit (member added, offered at caller) | 91–92 MB | 0.3 s | annotation-processing fingerprint 71%, completion 24% | −2.9 MB | | 782 MB |
| Unrelated API edit (plugin package) | 44 MB, plus 157 MB to open the document | 0.35 s | diagnostics actors 71%; javac 32% | +0.1 MB | | 793 MB |
| Source file added / removed | 48 / 142 MB | 1.9 / 2.4 s | annotation-processing preparation 49%, completion 36%; javac 23% | +1.4 / −0.2 MB | | 808 MB |
| **POM dependency added** | **1.97 GB** | 5.1 s | Maven resolution 77% (Maven model 66%), digests 11% | +12.9 MB | RocksDB 110 MB (256 MiB budget) / **14.0 GB** (64 MiB, after references) | 929 MB |
| POM reverted | 1.93 GB | 4.7 s | same | +14.9 MB | | 969 MB |

Unrelated-edit selectivity:

- The unrelated API edit costs 44 MB, against 92 MB for the relevant one.
- Completion at the caller afterwards needs no recomputation.

---

## Retained heap

### Machine ready (H1, 4.9 MB in the dump; 5.6 MB histogram)

The machine-wide index is a negligible part of the heap:

| Owner | Retained | Kind |
|---|---:|---|
| `IndexService` | 411 KB | MACHINE |
| → `RocksIndexStore` (`artifacts` 240 KB, `paths` 27 KB, workspaces/lookups ~0.3 KB) | 338 KB | MACHINE metadata |
| → `RocksArtifactRepository` | 69 KB | MACHINE |
| `java.lang.Class` objects (2 673) | 1.70 MB | JVM/class metadata |
| `AppClassLoader` | 338 KB | JVM |
| JFR metadata | 294 KB | JVM |
| JDK locale, zone and security caches | ~0.6 MB | JVM |
| `ZipFile$Source` (open JARs, 14) | 238 KB | JVM |

The histogram shows 124 K objects: `byte[]` 1.7 MB, `String` 0.6 MB (25 566 instances), `Class`
0.5 MB.

### Workspace states

See [Workspace and admission](#workspace-and-admission-allocation) for the H2 (admitted) and H4
(after mutations) dominators.

### First-use references (OOM dump, 1.06 GB live)

| Owner | Retained |
|---|---:|
| `Analyzer` (interactive), total | 294 MB |
| → `compilerPools` (`LinkedHashMap<String,CompilerPool>`) | **269 MB** |
| → → 348 `CompilerPool$Outcome` → `Bindings$Snapshot` (up to 18 MB each) | 210 MB |
| → `modules` (`ResidentSemanticState`, outlines, document semantics) | 21.9 MB |
| `jvmd-session-s1` thread stack locals (in-flight `WorkspaceBindings` capture, a `LinkedHashMap`) | **247 MB** |
| `ZipFileSystem` × 23 (5.67 MB each) | 130 MB |
| `SemanticFact` retained set (75 715 facts, 459 K `byte[]` 34 MB, 232 K `SemanticType$Declared`) | 71 MB |
| `ResidentSemanticState` retained set (106 K `BigInteger`, 89 K accumulator values) | 24.6 MB |
| Module actor | 18.8 MB |
| `LiveSourceState` (151 instances) | 13.4 MB |
| `Session` | 6.3 MB |
| `FileStateRegistry` | 4.0 MB |

The `CompilerPool` retained set is 284 MB:

- `byte[]` 64.5 MB;
- javac `List` 20.6 MB (858 K nodes);
- `HashMap$Node` 16 MB;
- `String` 15.5 MB;
- `MethodSymbol` 10.1 MB (140 K);
- `JCIdent` 8.6 MB.

Class histogram during the primary stall (live 1.04 GB):

| Class | Size | Instances |
|---|---:|---:|
| `byte[]` | 414 MB | 3.5 M |
| `LinkedHashMap$Entry` | 130 MB | 3.2 M |
| `String` | 64 MB | 2.7 M |
| `ZipFileSystem$IndexNode` | 23 MB | 566 K |
| `SourceText$Position` | 19 MB | 789 K |
| `Bindings$Occurrence` | 15 MB | 306 K |
| `BigInteger` | 9.3 MB | 233 K |
| `Hash256` | 3.8 MB | 159 K |

### After the session closes

| Checkpoint | Live heap |
|---|---:|
| Before close (M18b) | 100.7 MB |
| Session closed (M19s) | 24.0 MB |
| Second session closed (M20bx) | 28.7 MB |
| Restart, idle (machine baseline) | 5.4 MB |

So 77 MB is released, and **18–23 MB stays above the machine baseline**. H5 and H7 dominators:

| Owner | H5 (closed) | H7 (2 sessions closed) | Classification |
|---|---:|---:|---|
| `FileStateRegistry` (7.5 K `UnixPath`, 16 K `FileTime`, 7 K `Stamp`/`Observation`) | 4.5 MB | 5.0 MB | shared cache, machine-global; grows with files observed |
| `Application` → `MavenResolver` (project model caches, resolver class loader) | 2.7 MB | 2.7 MB | shared cache |
| `IndexService` → `LocalArtifacts` | 1.1 MB | 1.1 MB | workspace-derived, owned by the machine index (ambiguous) |
| `RocksIndexStore.workspaces` | 159 KB | **318 KB** | workspace entries survive `session.close` and grow per session (suspect) |
| `RocksIndexStore.classpathSequences` | 26 KB | 51 KB | same |
| `Dispatcher` (`Metrics`: 10 K samples per method) | 0.63 MB | 0.63 MB | machine-global, bounded |
| `URLClassLoader` (Maven 4 resolver) | 0.54 MB | 0.54 MB | machine-global |

No `Session`, `Analyzer`, `CompilerPool` or `LiveSourceState` remains after close. Nothing here is
labelled a leak. The two "suspect" rows are workspace-scoped data with a machine-scoped owner that
grows per opened session.

### Reconnect versus reopen

| | Retained session reconnect (M18b→M20) | Reopen after close (M19s→M20b) |
|---|---:|---:|
| Allocation to first correct definition | **51 MB** | **1.09 GB** |
| Latency to first correct definition | 0.15–0.2 s | 16.4–17.0 s |
| Live heap change | +1.7 MB | +24 MB |
| Mechanisms | annotation-processing fingerprint 68%, javac 16% | canonical digests 53%, accumulators 17%, Maven resolution 41% (operation) |

Reconnect reuses the `Session`, `Analyzer`s, `CompilerPool`s and `Documents`; nothing is
duplicated. Reopen rebuilds all of them.

### Restart (supplementary configuration)

| | Allocation | Wall | Live heap after |
|---|---:|---:|---:|
| JVM start to READY | 69 MB | 0.9 s | 5.4 MB |
| Workspace reopen | 1.86–1.88 GB | 4.6 s | 22 MB |
| First correct definition | 675 MB | 17.6 s | 44 MB |
| First completion | 66 MB | 0.4 s | 61 MB |

- **To READY:** ATTRIBUTED Jackson 26–57% (`StoredArtifact` JSON for 462 artifacts), ZIP/JAR 9–41%,
  classfile reading 10–20%. RocksDB native malloc is 9 MB, plus 0.9 MB of open/recovery.
- **Workspace reopen:** Maven resolution 96%.
- **First correct definition:** canonical digests 55%, accumulators 16%.

No machine re-index happens: the restart rescan reuses all 462 artifacts in 59–175 ms. What is
reconstructed:

- the `RocksIndexStore` artifact table (heap 0.34 MB);
- RocksDB table readers and index blocks (cache 38.5 MB at READY);
- per workspace: the Maven model, analyzer contexts and javac state, and digest/accumulator
  identities.

### Retention ledger

| Owner | Machine ready (H1) | % live | Workspace ready (H2) | % live | Dominator / root |
|---|---:|---:|---:|---:|---|
| MACHINE metadata (`IndexService`, `RocksIndexStore`) | 0.41 MB | 8% | 1.8 MB (incl. `LocalArtifacts` 1.1) | 3.5% | `Application.index` |
| LOCAL semantic state (`SemanticFact`, `ResidentSemanticState`) | 0 | 0 | 1.1 MB | 2% | `Analyzer.modules` |
| Compiler / analyzer (`CompilerPool`s, actor `Analyzer`, ct.sym `ZipFileSystem`) | 0 | 0 | ~31 MB | 60% | `Session.state`, `ModuleAnalyzerRegistry` actor thread |
| Documents / live state (`LiveSourceState`, `Documents`) | 0 | 0 | 3.7 MB | 7% | watcher threads, `Session.state` |
| Proof state (`QueryProof`, `ClasspathSequence`) | 0 | 0 | < 0.1 MB (0.2 MB at references) | — | `Analyzer` |
| Caches (`FileStateRegistry`, `MavenResolver`, `Metrics`) | ~0.1 MB | 2% | 4.5 MB | 9% | `Application` |
| Other (JDK class/locale/JFR metadata, loaders) | ~4.3 MB | 88% | ~10 MB | 19% | system roots |

---

## Native and off-heap memory

### HotSpot native memory (NMT, committed MB)

| State | Total | Java heap | Class + metaspace | Thread | Code | GC | Internal | Symbol | Arena chunk |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| M1 (bootstrap) | 315 | 216 | 12.5 | 2.0 | 9.9 | 44.8 | 1.3 | 2.7 | 7.7 |
| Seed 25% | 517–655 | 395–477 | 14 | 2.1 | 16 | 50–54 | 10.0 | 2.8 | 11–15 (peak **143**) |
| Machine ready (M4) | **297–360** | 184–237 | 14.8 | 2.3 | 17.5 | 46–49 | 10.0 | 2.8 | 3–10 |
| Workspace ready (M9) | 412–665 | 224–468 | 40.3 | 19.5 | 40–44 | 47–54 | 10.5 | 5.5 | 0–1 |
| After references (primary) | **1 262** | **1 024** | 44.4 | 26.4 | 60 | 64 | 10.7 | 5.7 | 0.3 |
| After mutations (supp.) | 574 | 354 | 42.9 | 19.8 | 55.7 | 51.5 | 10.5 | 5.6 | 7.3 |
| All sessions closed (supp.) | 415 | 224 | 43.0 | 2.7 | 56.0 | 49.0 | 10.4 | 5.6 | 2.7 |
| Restarted, idle | 317 | 216 | 13.1 | 2.2 | 11.2 | 44.8 | 1.3 | 2.7 | 9.7 |

NMT self-overhead is 1.3–5.2 MB. The restart failure's NMT at exit is 322 MB committed: heap 226,
GC 47, code 9, arena 7.4.

### glibc malloc arenas (`malloc_info`, MEASURED)

| State | Obtained from OS | Free inside arenas | In use |
|---|---:|---:|---:|
| Machine ready | 284–290 MB | **215–220 MB** | 69–70 MB |
| Workspace ready | 388–477 MB | 270–304 MB | 118–172 MB |
| After queries (supp.) | 581 MB | 382 MB | 200 MB |
| After references (primary) | 916–929 MB | **827–839 MB** | 89–90 MB |
| After mutations | 738–993 MB | 555–837 MB | 156–183 MB |
| All sessions closed | 748–993 MB | 573–849 MB | 144–175 MB |
| Restarted, idle | 77 MB | 14 MB | 63 MB |
| Restarted, workspace | 295 MB | 110 MB | 185 MB |

ATTRIBUTED: native malloc traffic is dominated by HotSpot C2 compiler arenas (7–11 GB over the
seed; the Arena Chunk peak reaches 143 MB) and by RocksDB block fetch/decompression.

INFERENCE: freed compiler arenas and RocksDB buffers stay inside glibc's per-thread arenas.
`malloc_info` shows 72–77 arena mappings. That freed memory, not live native data, is most of the
non-heap RSS.

### RocksDB (counters and nativemem)

| Phase | Budget | Cache usage | Pinned | RocksDB malloc traffic | RocksDB unfreed (sampled) | RSS change |
|---|---:|---:|---:|---:|---:|---:|
| Machine seed | 64 / 256 MiB | 0 → 40 MB | 1.6 MB | 2.96 GB: SST writing 2.72 GB, block fetch 0.20 GB, table readers 24 MB, memtables 10 MB | 3.4–3.9 MB | +385–532 MB |
| Workspace open | 64 MiB | 40 → **66.9 MB** | 2.1 MB | 57 MB: SST writing 40, block fetch 12 | 0.7 MB | +340 MB |
| Workspace open | 256 MiB | 40 → 104 MB | 2.1 MB | 45 MB | 1.0 MB | +370 MB |
| Admission | 64 / 256 MiB | 67 / 119 MB | **15.4–16.0 MB** | 150–155 MB: SST writing 83–88, memtables 56 | 0.2–1.3 MB | −30 to −157 MB |
| **First-use references** | 64 MiB | 67 → full (strict limit; inserts failing) | 14 MB | **81.8 GB**: block fetch/decompress **76.5 GB**, memtables 4.3 GB, SST writing 0.9 GB | 13 MB | to 1.96–2.16 GB |
| POM add after references | 64 MiB | full | | **14.0 GB** (block fetch) | 0 | |
| POM add (healthy) | 256 MiB | 107 → 118 MB | 2.6 MB | 110 MB | 0.6 MB | +66 MB |
| Restart to READY | 256 MiB | 38.5 MB | 1.4 MB | 9 MB (+0.9 MB open/recovery) | | +130 MB |

Why the columns do not sum to RSS:

- **The cache counts more than blocks.** Usage counts block contents plus the WriteBufferManager's
  dummy entries for memtables. Pinned counts pinned top-level index/filter blocks and memtable
  charges.
- **The sampler sees traffic, not residency.** nativemem samples malloc calls at 64 KiB; most
  RocksDB traffic is per-read decompression buffers freed at once, and freed bytes return to glibc
  arenas rather than the OS.
- **RSS also carries other deltas.** It includes Java heap commits and HotSpot arenas from the same
  phase.

**MEASURED.** In the five-database layout, the cache is shared and capacity-strict. The memtable
budget is one quarter of the cache, and stalls are allowed. Under that layout:

- During first-use references, writers to `inventory` and `semantic-state` park in
  `DBImpl::WriteBufferManagerStallWrites` (gdb), with no CPU progress. This happened in 6 of 13
  default-configuration lifecycles: the unprofiled benchmark baseline, `control-1`, `control-2`,
  `retention-1`, `nmt-1` and `rss-1`.
- At that point the `store` database holds an unflushed WAL/memtable of 14.8–25 MB
  (write buffer 4 MiB; WBM limit 16 MiB).
- The 60 s rescan's 34 artifact re-publications fail with `Insert failed due to LRU cache being
  full` once the cache is full. The same fault appeared during references with a 256 MiB budget
  (`b256-refs-alloc`), while the cache reported ~48 MB of usage.

The persisted `store` database after a workspace session (`control-1`):

| Key family | Rows | Bytes | Largest value |
|---|---:|---:|---:|
| `L/g` (LOCAL per-file fact groups, `JVF` binary) | 80 | 64.3 MB | **8.5 MB** (`ModelMerger.java`; `MavenProject.java` 4.3 MB) |
| `L/p` (postings) | 489 253 | 23.0 MB | 291 B |
| `L/v` | 33 292 | 16.8 MB | 5.8 KB |
| `A` (artifact metadata) | 500 | 0.32 MB | 736 B |

INFERENCE: single values of several MB written to `store` make its memtable exceed the shared
16 MiB write-buffer budget. Any write to a different database then stalls until `store` flushes,
which only a write to `store` triggers. On reopen, replaying that state through the same
strict-capacity cache fails the iterator in `RocksIndexStore.<init>`.

The failed reopen itself (`restartfail-*`):

- exit after ~1.2 s;
- peak RSS 155 MB, peak heap 28 MB, NMT 322 MB committed;
- RocksDB malloc 30 MB: iterator seeks decompressing blocks 10.9 MB, background compaction
  compressing and building tables ~9 MB, recovery buffers 3 MB.

It is not a process memory exhaustion.

### Direct and mapped buffers

`BufferPoolMXBean` `direct`, `mapped` and `mapped - 'non-volatile memory'` report **0 buffers and
0 bytes at every checkpoint of every run** (MEASURED). RocksDB JNI passes `byte[]` and native
memory, not NIO buffers. No JVMD code maps files.

---

## RSS and mappings

### RSS split, anonymous by address (rss runs)

| State | RSS | Java heap | glibc arenas + brk | JIT code | Thread stacks | Other anonymous (metaspace, GC, NMT) | File-backed |
|---|---:|---:|---:|---:|---:|---:|---:|
| Machine ready, primary (`rss-1`) | 541 | 218 | 248 | 12 | 2 | 10 | 50 |
| Machine ready, supp. (`b256-rss`) | 930 | **588** | 262 | 12 | 3 | 15 | 50 |
| Workspace ready (primary / supp.) | 724 / 1 012 | 206 / 460 | **405 / 420** | 26 / 29 | 16 | 13 / 29 | 59 |
| After references (primary) | 1 233 | 567 | 530 | 34 | 22 | 21 | 59 |
| After mutations (supp.) | 978 | 314 | 530 | 32 | 16 | 27 | 59 |
| All sessions closed (supp.) | 820 | 163 | **535** | 33 | 3 | 26 | 59 |
| Restarted, idle | 199 | 63 | 71 | 6 | 2 | 7 | 50 |
| Restarted, workspace | 593 | 214 | 267 | 24 | 16 | 14 | 59 |

File-backed residency (M9) is 50–59 MB in smaps, 37–46 MB `RssFile`:

- `libjvm.so` 18.8 MB;
- CDS `classes.jsa` 14.7 MB;
- JDK `lib/modules` 10.3 MB;
- extracted `librocksdbjni*.so` 9.7 MB;
- libc and libstdc++ 3.4 MB.

**No RocksDB SST, WAL or MANIFEST file and no Maven JAR is mapped.** RocksDB reads SST blocks with
`pread` into malloc'd buffers. JARs are read through `ZipFile`/zipfs. Every private mapping is
private-dirty, and PSS ≈ RSS. Virtual size is 4.8–5.1 GB, dominated by the 1 GiB heap reservation,
the 1 GiB compressed class space and the arena reservations. It is not resident.

### Process memory ledger (overlapping categories marked)

| Category | Machine ready | Workspace ready | After references (primary) | Overlap |
|---|---:|---:|---:|---|
| Java live heap (post-GC) | 5.6 MB | 51.7 MB | 763–1 040 MB | ⊂ committed heap |
| Java committed heap | 94–237 MB | 183–488 MB | 1 024 MB | ⊂ NMT, ⊂ RSS anon (only touched pages) |
| HotSpot NMT committed | 297–360 MB | 412–665 MB | 1 262 MB | includes heap, GC, code, metaspace |
| RocksDB cache usage | 38.2 MB | 63.8 MB (64 MiB budget) / 113 MB (256) | full | ⊂ glibc in-use |
| RocksDB pinned | 1.5 MB | 15 MB | 14 MB | ⊂ cache usage |
| glibc obtained / free in arenas | 284–290 / 215–220 MB | 388–477 / 270–304 MB | 916–929 / 827–839 MB | ⊂ RSS anon (touched pages) |
| Direct / mapped buffers | 0 / 0 | 0 / 0 | 0 / 0 | — |
| RSS anonymous | 380–654 MB | 697–994 MB | 1 854 MB | |
| RSS file-backed | 37 MB | 46 MB | 46 MB | |
| PSS | 415–548 MB | 740–1 021 MB | ≈ RSS | |
| **Total RSS** | **418–692 MB** | **743–1 040 MB** | **1 899–2 155 MB** | |

### Peaks

| Quantity | Value | Phase |
|---|---|---|
| Peak RSS | **2.16 GB**; 1.96–2.06 GB at the primary references OOM; the healthy lifecycle peaks at **1.03 GB** (seed) and 0.97 GB (admission, POM) | first-use references |
| Peak Java heap used | the ceiling, 1 024 MB (OOM); healthy lifecycle 521–528 MB | references; seed |
| Peak committed heap | 1 024 MB (references); 669–693 MB (seed) | |
| Peak RocksDB cache | at the budget: 64 MiB (default) or 148 MB of 256 MiB | admission onwards / references |
| Peak HotSpot native committed | 1 262 MB (heap 1 024); excluding heap, Arena Chunk peaks at 143 MB during seed | references; seed |

---

## Scaling

Machine index only (`exact`, seed-only): 5 repository sizes, 4 single JARs.

| Repository | JARs | Artifacts | Symbols | Edges | Seed alloc | Live heap | Persisted DB | RocksDB cache | Seed time | Peak heap | Peak RSS |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| empty | 0 | 0 | 0 | 0 | 2.5 MB | 6.0 MB | 0 | 0.3 MB | 0.1 s | 30 MB | 162 MB |
| 100 | 100 | 100 | 118 190 | 151 984 | 4.78 GB | 6.6 MB | 59.5 MB | 6.7 MB | 5.8 s | 553 MB | 858 MB |
| 250 | 250 | 250 | 499 990 | 668 605 | 20.7 GB | 6.8 MB | 255 MB | 24.3 MB | 26.5 s | 702 MB | 1 036 MB |
| 350 | 350 | 350 | 641 975 | 862 981 | 26.2 GB | 6.9 MB | 324 MB | 30.4 MB | 32.0 s | 714 MB | 1 118 MB |
| full | 462 | 462 | 867 654 | 1 159 791 | 35.1 GB | 7.0 MB | 436 MB | 40.0 MB | 38.5 s | 692 MB | 1 090 MB |

Linear fits (empirical; five points do not establish asymptotic complexity):

| Quantity | Slope | R² |
|---|---|---:|
| Seed allocation | 40.5 KB per symbol / 30.2 KB per edge / 78 MB per artifact | 0.998 / 0.9985 / 0.981 |
| Persisted SST bytes | 503.6 B per symbol | 0.99993 |
| RocksDB cache at READY | 88.5 KB per artifact | 0.991 |
| Seed time | 46 µs per symbol | 0.98 |
| Retained heap | ~1.9 KB per artifact (~1 B per symbol), over a constant 6.2 MB | 0.84–0.87 |

- Allocation and persisted bytes grow linearly in symbols and edges.
- Retained heap is essentially constant: the per-artifact slope is within run-to-run noise
  (±0.3 MB).
- Peak heap saturates at ~700 MB (4 index readers, the admission budget of 128 MB per JAR estimate,
  and G1 heuristics). It does not scale with repository size.
- A machine-only seed reopens at the default budget for every size, with live heap 6.0–6.8 MB.

---

## Strings, hashes, collections and serialization

### Strings

| | Seed | Workspace open | Admission | POM edit | Reopen |
|---|---:|---:|---:|---:|---:|
| `String` allocation (sampled) | 1.66 GB | 170 MB | 76 MB | 142 MB | 45 MB |

Retained `String` instances (post-GC):

| State | Instances | Size |
|---|---:|---:|
| READY | 25 566 | 0.6 MB |
| Admitted | 162 609 | 3.9 MB |
| After queries | 209 730 | 5.0 MB |
| After mutations | 273 506 | 6.6 MB |
| Session closed | 92 575 | 2.2 MB |
| References peak | 2.7 M | 64 MB |

`byte[]` backing (all `byte[]`, not only strings): 1.7 / 22.2 / 30.3 / 45.4 / 11.0 MB at those
states.

Top duplicate values in the admitted heap (MAT `group_by_value`; the top 500 values account for
31 K instances):

| Value | Copies |
|---|---:|
| absolute path of `MavenProject.java` | 2 837 |
| `"compile"` | 2 790 |
| `"jar"` | 1 010 |
| `"selected by Maven"` | 1 000 |
| **`e3b0c442…b855`** (SHA-256 of empty input, hex) | 897 |
| `"test"` | 810 |
| `"public"` | 547 |
| `"method"` | 427 |
| each `…/target/classes` path | 150–261 |
| each Maven repository JAR path | ~151 |
| `DefaultMavenProjectHelper.java` path | 240 |

No SCIP strings appear among the top 500 duplicates at that state.

### Hash representation

Canonical digests (`CanonicalDigestWriter`, `LiveStateTree`, `Hash256`, SHA-256) allocate:

| Phase | Allocation | Share |
|---|---:|---:|
| Seed | 3.4 GB | 10% |
| Workspace open | 218 MB | 8% |
| Admission | 500 MB | 24% |
| First completion | 231 MB | 52% |
| First-use references | 8 GB | 22–25% |
| Reopen | 572 MB | 53% |
| Restart first query | — | 55% |

Of that: `SHA2$SHA256` objects take 247 MB of the seed, and `Hash256` objects 87 MB.

`AlgebraicAccumulator` (`BigInteger`/`MutableBigInteger` arithmetic over hashes) allocates:

| Phase | `BigInteger` / `MutableBigInteger` | Accumulator share |
|---|---:|---:|
| First completion | 60 MB | 23% |
| Admission | 68 MB | 6% |
| Reopen | 102 MB | 17% |
| References | 3.4 GB | 9–11% |

Retained:

- `ResidentSemanticState` at the references peak holds 106 K `BigInteger` (4.2 MB) and 89 K
  accumulator values. Admitted it holds 13 K `BigInteger`.
- Duplicate hex strings exist; the empty-content SHA-256 alone has 897 copies.

### Collections (with owners)

| Population | Allocation | Retention | Owner |
|---|---|---|---|
| JDK collections | 35% of seed as innermost owner (`LinkedHashMap$Entry` 0.62 GB, `HashMap$Node[]` 0.44 GB, `LinkedHashMap` 0.37 GB) | ~0 | `JavaTypes.referenced`, `ArtifactIndexFormat.from`, `SstSorter` |
| Stream pipeline objects | 2.3 GB of seed | 0 | `ResolutionFact.canonical` |
| `LinkedHashMap$Entry` | — | 3.2 M (130 MB) at the references peak | in-flight `WorkspaceBindings` capture |
| `LinkedHashMap$Entry` | — | 60 K (2.4 MB) admitted | |
| `HashMap$Node` | — | 71 K admitted, 147 K after mutations | `FileStateRegistry` (7 K), `Session` state, analyzers |
| `TreeMap$Entry` | 4–5% of warm completion/definition | 0 | warm queries |

### Serialization and encoding

| Encoding | Allocation | Retained | Output |
|---|---|---|---|
| Machine artifact encoding (`ArtifactIndexFormat` binary) | ~4.8 GB | ~0 | SST values (~437 MB SST in total) |
| SST machinery (postings, sort, keys) | ~13.4 GB | ~0 | same SSTs; **encoding amplification ≈ 42×** |
| Jackson `StoredArtifact` JSON (seed) | 0.66 GB | | 0.32 MB of `A` rows |
| Jackson at restart (reading those rows) | 26–57% of 20–48 MB | | |
| Jackson in completion responses | 32–34% of each 50-candidate request | | |
| Source facts (`FactCodec`/`KeyedFacts`/`SourceOverlay`) during references | 6.1 GB (encoding) | | `store` `L/*` rows: 104 MB for 80 files |
| Source-fact decoding in warm `completionItem/resolve` | 57% of 0.73 MB per request | | |

### RocksDB key construction

| Key construction | Allocation (seed) |
|---|---:|
| `relativeKey` → `byte[]` | 0.63 GB |
| `hex8` | 0.29 GB `byte[]` + `String` |
| SCIP suffix (`ArtifactContext.scip`) | 0.40 GB |
| `String` keys built in `writeSst` | 0.20 GB |
| `String.format` | none among the top 50 sites |
| RocksDB JNI wrapper objects | ~0 in the Java heap |

`WriteBatch` construction is native: 3.2 GB of `WriteBatch::Put` `std::string` growth during
references.

### ZIP/JAR handling

| Allocation | Volume | Lifetime |
|---|---|---|
| Class bytes read during seed (`BinaryReader.read`) | 1.96 GB | transient |
| `ZipFile`/`Inflater`/entry names | ZIP/JAR owner 0.6% of seed | transient; zlib native 0.19 GB malloc |
| `JarFile$JarFileEntry` | 13% of 48 MB to READY at restart | |
| zipfs `IndexNode`s per javac platform `ZipFileSystem` | small | **retained** at 5.67 MB per compiler: 1 → 4 → 23 instances (130 MB at references) |

ZIP/JAR survivors dominate `live`-mode survivors in every workspace phase.

### SourceOverlay and persisted source state

| | Allocation | Retention |
|---|---|---|
| Admission | `SourceIndexPublisher` 4% of 2.0 GB | |
| References | source-fact encoding 6.1 GB | |
| `SourceOverlay` decoded cache | | budget 4 MiB; 108 KB retained at OOM, 8.9 KB after close |
| `store` `L/*` rows (persisted) | | ~104 MB on disk for 80 files |

---

## Restart

Covered in [Retained heap → Restart](#restart-supplementary-configuration) and
[RocksDB](#rocksdb-counters-and-nativemem).

- **Default configuration:** the persisted reopen fails after any lifecycle that included
  workspace use (9 of 9, plus 7 direct attempts). It succeeds after a machine-only seed (5 of 5
  sizes).
- **Supplementary configuration (256 MiB):** the reopen succeeds. It reaches READY with 69 MB
  allocated, 5.4 MB live and 193 MB RSS.

---

## Findings relevant to future architecture

Factual observations only:

- **Seed churn.** 33–35 GB of transient Java allocation produces 437 MB of SST (~80×).
  - Allocation scales at ~40.5 KB per symbol.
  - Classfile reading, gram postings, symbol encoding, fact construction, SST sorting and digests
    account for 86% of it.
- **Seed retention.** The machine index's on-heap footprint after seed is ~0.34 MB
  (`RocksIndexStore`). Total retained heap is constant at ~6–7 MB regardless of repository size.
- **Machine data residency.** Machine index data resides in RocksDB native memory (38–40 MB of cache
  at READY, ~88.5 KB per artifact) and on disk (437 MB of SSTs, ~504 B per symbol). It is read
  through `pread`; there are no file mappings today.
- **Peak per JAR.** Indexing one large JAR peaks at ~470 MB of transient heap (groovy, 51 K symbols).
- **Per-request cost.** Every warm editor request recomputes compiler-input identity, file-state
  stamps and annotation-processing fingerprints.
  - That costs 0.73–2.96 MB per request (fixed, prefix-independent).
  - It costs 1.26 MB even for a 1-item completion.
- **Workspace references.** First-use references holds javac compilation outcomes and binding
  snapshots for the whole workspace on the heap.
  - That is 269 MB in `Analyzer.compilerPools` plus a 247 MB in-flight capture.
  - It exceeds a 1 GiB heap on apache/maven.
- **LOCAL facts in RocksDB.** LOCAL source facts are persisted as per-file values of up to 8.5 MB
  in the `store` database. That layout coincides with every RocksDB stall and every failed reopen
  observed.
- **RocksDB read churn.** At the default 64 MiB budget, references and later POM edits drive
  14–82 GB of RocksDB block fetch/decompression malloc. At 256 MiB the same POM edit drives 0.1 GB.
- **Native residue.** 215–849 MB of process memory is glibc arena memory that is free. NMT-tracked
  HotSpot native excluding the heap is ~110–240 MB.
- **Heap commit residue.** The Java heap's committed and resident pages track the seed peak
  (184–588 MB at READY for a 5.6 MB live set) and the references peak (1 GiB, kept).
- **ct.sym copies.** One javac platform `ZipFileSystem` of 5.67 MB is retained per compiler
  generation (INFERENCE: `ct.sym`): 1 copy admitted, 4 after mutations, 23 at the references peak.
- **After close.** Session-scoped state is released on close except about 18–23 MB, of which
  `FileStateRegistry` is 4.5–5 MB. Per-session `RocksIndexStore.workspaces` entries grow with each
  session.
- **Workspace-scale recomputation.** Each costs 1–2.7 GB of allocation: workspace open (Maven
  resolution), POM edits (re-resolution), reopen after close (identities), and restart reopen (1.9 GB
  + 0.68 GB to the first answer).

## Top findings (ranked)

| Rank | Memory population | Evidence | Allocated | Retained / native | Lifecycle |
|---:|---|---|---:|---:|---|
| 1 | First-use workspace references: javac outcomes, `Bindings` snapshots, in-flight capture | alloc + OOM dump + MAT | 31–37 GB | **763 MB–1.06 GB live (OOM)**; 82 GB RocksDB malloc traffic | per session, first use; released on close |
| 2 | Machine seed transient structures (postings, encoding, classfile reading, sort runs, digests) | exact + alloc + scale | **33–35 GB** (40.5 KB/symbol) | +1 MB heap; 437 MB SST on disk | machine, once per new or changed artifact |
| 3 | glibc arena memory freed but retained (HotSpot arenas, RocksDB buffers) | `malloc_info` + smaps | 7–11 GB HotSpot native traffic during seed | **215–849 MB free inside arenas** | process lifetime; grows with each heavy phase |
| 4 | Java heap committed above live (G1 commit residue) | NMT + smaps + histograms | — | 184–588 MB committed for 5.6 MB live at READY; 1 GiB after references | process lifetime |
| 5 | RocksDB `store` LOCAL fact values (`L/g` up to 8.5 MB each) and the shared WBM/strict cache | `ldb` read + gdb + logs | 6.1 GB `FactCodec` encoding (references) | 64–104 MB on disk; memtable 15–25 MB; **write stall, reopen failure** | persisted per workspace; survives restart |
| 6 | RocksDB block fetch/decompression churn at the 64 MiB budget | nativemem | 76 GB (references), 14 GB (POM edit after) | cache 38–64 MB resident (budget-bound) | per query when the cache is full |
| 7 | Workspace open, POM edit, reopen and restart reopen (Maven model, identities) | exact + alloc | 1.1–2.7 GB each | +13–55 MB live | per session or project change |
| 8 | Canonical digests and `BigInteger` accumulators | alloc + histograms | 3.4 GB seed, 8 GB references, 52–75% of first completion and reopen | 13–106 K `BigInteger` live in `ResidentSemanticState` | everywhere |
| 9 | Per-request recomputation of input identity and file stamps (warm queries) | exact + alloc + JFR stages | 0.73–2.96 MB per request | 0 | every request |
| 10 | Compiler state per generation (`CompilerPool`, actor `Analyzer`, ct.sym `ZipFileSystem` 5.67 MB each) | MAT | admission 1.8–2.1 GB | 26 MB admitted → 78 MB after mutations | per session |
| 11 | `SemanticFact` / `ResidentSemanticState` | MAT | (within references) | 1.1 MB admitted; 95 MB at references | per session |
| 12 | Machine-wide caches surviving close (`FileStateRegistry`, `MavenResolver`, `LocalArtifacts`, `RocksIndexStore.workspaces`) | MAT | small | 18–23 MB, grows per session | machine lifetime |
| 13 | `LiveSourceState` watchers (77 platform threads) | MAT + `/proc` | — | 3.7–13.4 MB heap; 16 MB stack RSS; threads 35 → 196 | per session |
| 14 | Duplicate path and enum strings; hex hash identities | MAT | 1.66 GB `String` (seed) | 2.8 K copies of one path; 897 copies of one empty hash | per session |
| 15 | Machine index on heap (`RocksIndexStore` artifact table) | MAT + scale | 0.66 GB Jackson (seed); 20–48 MB to READY at restart | **0.34 MB** (~0.5–0.7 KB per artifact) | machine |
| 16 | Direct and mapped NIO buffers, file mappings | `BufferPoolMXBean` + smaps | — | **0**; file-backed RSS 37–46 MB (libraries, CDS) | — |

---

## Answers to the required questions

1. **Total heap allocated while seeding.** 31.7–35.1 GB for 462 JARs (6 of 7 runs within 33.3–35.1 GB).
2. **Top 20 seed sites.** See [the table](#allocation-sites-and-classes-attributed-b256-alloc-133-k-samples-350-gb-sampled-vs-342-gb-exact). They cover 66%.
3. **Dominant allocated types.** `byte[]` 47%, `int[]` 13%, `String` 5%, `Object[]` 3%, `SstSorter$Entry` 2.5%.
4. **Dominant retained types after READY.** `byte[]` 1.7 MB, `String` 0.6 MB, `Class` 0.5 MB, out
   of 5.6–5.9 MB total.
5. **Owners after READY.** JDK/class-loading metadata (~88%) and `IndexService` → `RocksIndexStore`
   (0.41 MB).
6. **Heap with no workspace open.** 5.6 MB at READY. After sessions have been opened and closed,
   24–29 MB.
7. **One Maven workspace retains** +46 MB admitted, +70 MB with warm queries, +102 MB after
   mutations. With references it reaches the 1 GiB ceiling.
8. **Released on session close.** About 77 MB (100.7 → 24.0 MB). 18–23 MB remains above baseline.
9. **Warm completion.** 1.22 MB per request median (1.26 MB for 1 item, 1.81 MB for 50; p95 1.75 MB).
10. **First completion.** 417 MB, including admitting the edit that adds the completion probe.
11. **javac versus JVMD in first use.** Completion 5% javac. Definition 53%. Hover 37%. Admission
    14%. References 17–27%. JFR javac stages: 1.39 GB of the session thread's 7.63 GB.
12. **Body-only mutation.** 128–131 MB, plus 21 MB for the next completion.
13. **Relevant API mutation.** 91–92 MB.
14. **POM mutation.** 1.97 GB to add a dependency, 1.93 GB to revert it.
15. **RocksDB native memory.** The cache holds 38 MB at READY and sits at the 64 MiB budget from
    admission on (113–148 MB with a 256 MiB budget). Unflushed memtables add up to 15–25 MB (WAL).
    Malloc traffic is 3 GB during seed and 82 GB during references.
16. **Block cache versus pinned.** Usage 38–67 MB; pinned 1.4–16 MB (index/filter top level and
    memtable charges). Memtables are visible as WBM charges and WAL size.
17. **What NMT accounts for.** HotSpot only: 297–360 MB at READY, of which the heap is 184–237 MB.
    1.26 GB after references.
18. **What remains outside NMT.**
    - RocksDB malloc (cache, memtables, read buffers).
    - glibc free-but-retained arenas (215–849 MB).
    - zlib, libstdc++ and JNI library data.
    - Thread stacks are only partly covered.
19. **Anonymous versus file-backed RSS.** ~95% anonymous. File-backed is 37–46 MB.
20. **Mapped or state files with resident pages.** Only libraries and archives: libjvm 19 MB, CDS
    15 MB, `lib/modules` 10 MB, `librocksdbjni` 10 MB. No SST, WAL or JAR.
21. **High allocation, negligible retention.**
    - machine seed (35 GB, +1 MB);
    - warm queries (0 retained);
    - workspace open, POM edits, reopen and restart reopen (1–2.7 GB each, +13–55 MB);
    - SST and encoding machinery;
    - digests.
22. **Low allocation, high retention.**
    - ct.sym `ZipFileSystem`s, 5.67 MB each;
    - `FileStateRegistry`, 4.5 MB, machine-wide;
    - `LocalArtifacts`;
    - per-session `RocksIndexStore.workspaces`;
    - `LiveSourceState` watchers;
    - and on disk, `store` `L/g` values.
23. **Populations that scale with symbols or artifacts.**
    - Seed allocation (40.5 KB per symbol).
    - Persisted SST (504 B per symbol).
    - RocksDB cache at READY (88.5 KB per artifact).
    - Seed time.
    - Retained heap does not scale measurably (~1–2 KB per artifact over a constant 6 MB).
24. **Reconstructed during restart.**
    - The `RocksIndexStore` artifact table (Jackson).
    - RocksDB table readers and index blocks.
    - A rescan that reuses all artifacts.
    - Per workspace, everything again: Maven model, contexts, javac state, identities.
25. **Restart allocation.** 69 MB to READY, 1.86–1.88 GB to reopen the workspace, 675 MB to the first
    correct definition. At the default configuration the reopen fails after ~1.2 s and 30 MB of
    RocksDB malloc.
26. **Lifecycle peak.** The heap ceiling (1 GiB) with RSS 1.96–2.16 GB. Without references:
    RSS 0.96–1.03 GB during seed, heap 521–528 MB.
27. **Phase.** First-use workspace references. Without it, machine seed.
28. **Humongous allocations.** Yes. G1 shows up to 205 humongous regions during references and
    9–28 elsewhere. Sites: see [Humongous allocations](#humongous-allocations).
29. **Populations duplicated across MACHINE, LOCAL and LIVE.**
    - Workspace modules are indexed into SSTs (`LocalArtifacts`) with the machine pipeline.
    - Their source facts are also persisted as LOCAL `store` rows.
    - They are held again as `SemanticFact`s and `ResidentSemanticState` on the heap.
    - javac holds the same sources' trees and symbols in `CompilerPool` outcomes per generation,
      for both the interactive analyzer and the diagnostics actor.
    - The ct.sym file system is duplicated per compiler.
    - Paths and hashes are duplicated as strings.
30. **Evidence for the persistence investigation.** See
    [Findings relevant to future architecture](#findings-relevant-to-future-architecture) and the
    ranked table, chiefly ranks 1, 2, 5, 6, 3/4 and 15.

## Humongous allocations

<!-- HUMONGOUS -->

## Allocation ledger (major phases)

| Owner | Machine seed | Workspace open | Admission | First completion | Warm completion ×102 | First-use references | POM edit |
|---|---:|---:|---:|---:|---:|---:|---:|
| javac | — | 7% | 14% | 5% | — | 17–27% | — |
| Index parsing (classfile reading, fact construction) | 35% | 8% | 11% | — | — | 2% | 5% |
| Persistence encoding (symbol encoding, SST postings, sort, keys, source facts) | 50% | 5% | 15% | — | — | 16–19% | — |
| Semantic state and identity (digests, accumulators, declarations) | 10% | 10% | 30% | 75% | — | 35–40% | 16% |
| Resolver (Maven model) | — | 50% | — | — | 6% | — | 66% |
| Query projection (input identity, stamps, materialisation, JSON) | — | — | — | — | 57% | — | — |
| Other | 5% | 20% | 30% | 20% | 37% | ~15% | 13% |

Shares are of each phase's sampled bytes. Its exact volume is in the
[lifecycle table](#allocation-by-phase).

## Churn ledger

Churn = allocated − retained. Retained is the post-GC live-heap change across the phase. The bytes
retained were not necessarily allocated in the same phase; this is a churn estimate, not a
conservation.

| Phase / owner | Allocated | Retained contribution | Churn |
|---|---:|---:|---:|
| Machine seed: artifact parsing (classfile reading + fact construction) | ~12 GB | ~0 (+1 MB in total) | ~100% |
| Machine seed: persistence encoding (SST + symbol encoding) | ~17.5 GB | 0 heap; 437 MB written to disk | ~100% of heap |
| Workspace open | 2.4–2.7 GB | +55 MB | 98% |
| Admission | 1.8–2.1 GB | −9 MB (61 → 52) | ~100% |
| javac (first use, all phases) | ≥ 1.39 GB (JFR stages) | 10–45 MB `CompilerPool` sets | ~97% |
| First-use references | 31–37 GB | +690 MB (session-scoped) | 98% |
| Query materialisation (warm, 102 requests) | 0.12–0.32 GB | 0 | 100% |
| POM edits | 3.9 GB (add + revert) | +28 MB | 99% |

---

## Unknowns and limitations

- **Single observations.** These were observed once: per-mode profiled runs; per-JAR runs; the
  scale points; and the primary references to completion.
  - References reached the heap ceiling 3 of 3 times with a 256 MiB budget. At the default budget
    it stalled 6 times, threw OOM 6 times and once finished server-side after ~4 minutes.
  - Seed allocation varies 31.7–35.1 GB across 7 runs.
- **First-use references at 1 GiB never produced a correct answer.** Its steady-state allocation
  and retention are therefore unknown; only the OOM and stall states are measured.
- **Supplementary configuration.** Phases after references were measured only with a 256 MiB
  RocksDB budget and without references. Their RocksDB numbers do not describe the default budget.
- **M7 (index binding)** is not separately observable without new instrumentation. It is inside
  the admission window.
- **ct.sym identification** of the 5.67 MB `ZipFileSystem` is an inference (entry count, the only
  zipfs code path). MAT could not render the `zfpath`.
- **RocksDB memory categories.** Index/filter blocks versus data blocks in the cache, and table
  reader memory, are not separable from the nativemem stacks. `estimate_table_readers_mem` and
  `cur_size_all_mem_tables` are exposed only for the repository database.
- **The live-object profile undercounts.** Survival evidence relies on post-GC heap deltas and dumps.
- **glibc arena free memory** is measured. How much of it is resident (versus untouched in the
  arena) is bounded by smaps (glibc RSS 248–535 MB) but not measured per arena.
- **Environment.** All local numbers come from one 4-CPU, 16 GiB container. The CI run of the same
  matrix (second environment) uploaded raw evidence. Its MAT reduction failed (a path bug since
  fixed). Its numbers are not compared in this report.

---

## Reproduction

Prerequisites, as in `jvmd-benchmarks.yml`:

- pinned Temurin 25.0.4.1+1;
- `mvn -B -DskipTests install && bash jvmd-dist/assemble.sh`;
- the apache/maven fixture built into its own repository;
- async-profiler 4.1 (`ASYNC_PROFILER_HOME`);
- Eclipse MAT 1.16.1 (`MAT_HOME`) for heap dumps.

Every command writes a new directory.

```sh
P="--project $APACHE_MAVEN --project-repository $APACHE_MAVEN_REPOSITORY"
# Machine seed (exact volume, peaks, GC, stages; then allocation sites; then native)
node benchmarks/memory/profile.ts --mode exact  --seed-only --seed-restart --repository $APACHE_MAVEN_REPOSITORY --output out/seed-exact
node benchmarks/memory/profile.ts --mode alloc  --seed-only --repository $APACHE_MAVEN_REPOSITORY --output out/seed-alloc
node benchmarks/memory/profile.ts --mode native --seed-only --seed-restart --repository $APACHE_MAVEN_REPOSITORY --output out/seed-native
# Workspace, queries, mutations, close, reconnect, restart (healthy daemon: supplementary configuration)
node benchmarks/memory/profile.ts --mode alloc     --native-budget-mb 256 --skip-references $P --output out/workspace-alloc
node benchmarks/memory/profile.ts --mode control   --native-budget-mb 256 --skip-references $P --output out/queries-control
# Default configuration (the stall, the OOM and the failed restart are part of the result)
node benchmarks/memory/profile.ts --mode control $P --output out/default-control
# Native
node benchmarks/memory/profile.ts --mode nmt    $P --output out/nmt
node benchmarks/memory/profile.ts --mode native $P --output out/native
# Retention (heap dumps H1-H9, histograms, dump on OOM) and references to completion
node benchmarks/memory/profile.ts --mode retention --native-budget-mb 256 --skip-references $P --output out/retention
node benchmarks/memory/profile.ts --mode retention --native-budget-mb 256 $P --output out/references-retention
# Restart failure, profiled directly on a persisted state left by a default-configuration lifecycle
node benchmarks/memory/profile.ts --mode nmt --seed-only --reuse-state out/default-control/state/jvmd --repository $APACHE_MAVEN_REPOSITORY --output out/restart-nmt
# Scale and per-JAR
python3 benchmarks/memory/subset_repository.py $APACHE_MAVEN_REPOSITORY repos/n250 --count 250
node benchmarks/memory/profile.ts --mode exact --seed-only --seed-restart --repository repos/n250 --output out/scale-n250
# Reduction
python3 benchmarks/memory/analyze.py out/*/ --out summary
for d in out/*/dumps/*.hprof; do bash benchmarks/memory/mat.sh "$d" summary/mat/$(basename "$d" .hprof); bash benchmarks/memory/mat_drill.sh "$d" summary/mat-drill/$(basename "$d" .hprof); done
python3 benchmarks/memory/synthesize.py summary --out benchmarks/memory/results
```

The whole matrix runs in CI with **Actions → JVMD memory profile → Run workflow**
(`.github/workflows/memory-profile.yml`): one job per profile, raw evidence uploaded as artifacts.

## Raw evidence

- **GitHub Actions** run [36793765544](https://github.com/maxjay/jvmd/actions/runs/36793765544).
  - It has 21 jobs and 20 artifacts named `memory-<profile>`, kept for 90 days.
  - They contain JFR recordings, async-profiler JFRs, NMT summaries, detail diffs and
    `malloc_info`, histograms, HPROF dumps (`memory-*retention`, ~0.5–1.2 GB each), smaps
    snapshots, process samples, `daemon.status` snapshots, results, environment metadata and
    driver logs.
- **In this repository:**
  - `benchmarks/memory/results/summary.json`: every table above.
  - `benchmarks/memory/results/allocation-top50.json`: top-50 classes and stacks per phase.
  - the driver, probes and reduction scripts.

## Definition of done

| | |
|---|---|
| Exact subject SHA and environment recorded | ✓ [Methodology](#methodology) |
| Existing allocation measurement limitations documented | ✓ [Checkpoint 0](#current-instrumentation-checkpoint-0) |
| Unprofiled control exists | ✓ benchmark baseline, `control-2`, `control-3`, `b256-control*` |
| Machine seed: exact volume, stack/class attribution, retained-heap evidence, native evidence | ✓ |
| Workspace open/admission: the same | ✓ |
| First-use core queries: the same | ✓ (references: to OOM/stall) |
| Warm core queries: the same | ✓ (references warm: unavailable, see limitations) |
| Body/API/classpath mutations profiled | ✓ (supplementary configuration) |
| Session close profiled for reclaimable/retained state | ✓ |
| Reconnect profiled | ✓ |
| Persisted restart profiled; existing failure captured | ✓ |
| Java allocation separated from retention | ✓ |
| Java memory separated from native; HotSpot native from third-party native | ✓ |
| RSS/PSS/file-backed residency measured | ✓ |
| RocksDB cache/pinned recorded; direct/mapped buffer pools recorded | ✓ |
| Top allocation classes and call stacks for every major phase | ✓ `allocation-top50.json` |
| Dominator/retained-owner evidence | ✓ |
| Per-module and component rollups | ✓ |
| Allocation amplification for machine indexing | ✓ ~80× |
| Allocation per request for warm queries | ✓ |
| Per-artifact/per-symbol scaling evidence | ✓ |
| Profiling overhead measured | ✓ |
| Every profiled operation checked by its oracle | ✓ |
| Raw evidence retained outside git | ✓ workflow artifacts |
| Concise ledgers and ranked findings | ✓ |
| No production optimisation or architecture change | ✓ |
| Temporary heavy profiling machinery cleaned up | ✓ (push trigger removed; the workflow is dispatch-only) |
