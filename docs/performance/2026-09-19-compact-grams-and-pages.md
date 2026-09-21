# Compact grams, merge allocation and bounded queries — 2026-09-19

This follow-up starts from merged main `dce594130a0ec0f2f213293c8429fa26051dd453`
(PR #5). Investigation found two different problems: avoidable allocation during
index construction, and full symbol decoding for records that cannot enter a
bounded result page. The page problem is the larger latency win.

## Findings and implementation

A fresh main JFR attributed roughly 780 MB of sampled allocation to gram creation,
363 MB to posting decoding, 419 MB to full SST keys, and 381 MB to temporary-run
byte reads. The total sampled allocation was 4.979 GB. CPU samples also highlighted
hash-map lookups, gram insertion and posting-buffer growth. These samples include
seed plus the standard query batch; they are not an elapsed-time partition.

A separate paginated-query JFR found most allocation in symbol/string decoding.
Main read and decoded all 380,000 candidate symbols to return a 20-row page, even
when the local ID alone proved a candidate could not enter that page. The profiled
page timings were about 1.3–1.5 s. Final unprofiled comparisons are below.

Implemented changes:

- `GramSet` stores a gram's length and up to three UTF-16 code units in a primitive
  value. Reusable set arrays replace per-occurrence substrings and hash-set nodes.
  Length is encoded explicitly, preserving embedded NULs and distinct one/two/three
  unit grams. Text conversion occurs when emitting a posting block.
- `GramPostings` probes its bounded LRU with a reusable lookup object. Stored keys
  remain immutable; the mutable probe is never inserted. Eviction and 256-ID block
  bounds are retained. Fixed encoding buffers avoid stream growth and per-byte
  stream overhead; emitted values still receive an independent exact-sized copy.
- Merge runs use a reusable validated posting cursor instead of allocating an ID
  array for every block. Every block is fully checked before it can pass through
  intact; interleaved blocks are still expanded and duplicate/order checks remain.
- Adjacent SST keys reuse a buffer when their lengths agree. The final byte-array
  `put` consumes it synchronously. Independent full-row and checksum tests cover
  spill and non-spill paths.
- Plain signature generations rank by artifact/local ID before reading symbol
  facts. Candidates below the cursor or outside an already filled top-k page are
  skipped. Only the natural-ID `1|symbol|` namespace seeks directly to the cursor
  and stops after filling the page. Gram/name/type prefixes still visit remaining
  ranges because their key order does not imply global ID order.
- Code-enriched generations retain the original symbol-dependent rank mapping.
  Kind filters, source precedence, aliases and final predicates remain in force.
  New `query_posting_candidates` and `query_symbol_reads` status counters show the
  actual query work without a per-record atomic increment.

No persisted encoding, index version, configured memory/admission budget or native
dependency changes. Gram sets retain only the current symbol and owner prefix;
there is no growing per-artifact symbol-result cache.

## Revisions and method

- Baseline: main `dce594130a0ec0f2f213293c8429fa26051dd453`, tree
  `a550c895695aad43e06f06b209f42c0023a52c76`.
- Optimized production: GitHub `c0739d14d44e3966125741c275290632c2315a99`,
  local `edb807edf3324f54b7f96a38706dacf452945e9e`, identical tree
  `f13ee5ea072cc91f113676f2fa3e73397bda2fc2`.
- Pinned Temurin 25.0.4.1+1; serial 1 GiB JVMs on eight allocated AMD EPYC CPUs,
  volatile overlayfs, no AOT archive and no OS-cache flush. Source trees are
  separately compiled with identical flags. Revisions, fixture/dependency hashes,
  harness hashes and implementation class hashes are retained in raw reports.
- Five alternating repetitions for generated seed/restart (40 processes), real
  dependency seed/restart (20), and bounded pages (10). The last experiment opens
  private copies of **the same main-produced index**, so stable IDs and complete
  page hashes can be compared directly, including early and middle pages.
- All latency claims use unprofiled runs. JFRs run separately. Warm pages use five
  timed samples after a reference call; reported values are the median of five
  worker medians. This fixed warm-up is not a long-running throughput experiment.
- These are direct index-service APIs. MCP/LSP transport, response serialization,
  compiler diagnostics and broader editor operations are outside the timed scope.

## Bounded pages: the major latency gain

Each request returns 20 complete rows. “Middle” starts after local ID 190,000;
it is not a third consecutive page. All 60 page hashes match across implementations
and workers. Substring searches still inspect the candidate posting IDs, but
decode only the 20 symbols returned. Blank enumeration seeks directly to its
starting ID. Every optimized case records exactly 120 fact reads for its reference
call plus five timed calls: **20 facts per request**.

| Query | Page | Merged main | Optimized | Ratio |
| --- | --- | --- | --- | --- |
| Substring `Type` | First | 1,290.259 ms | 7.696 ms | 168x |
| Substring `Type` | Second | 1,283.518 ms | 10.448 ms | 123x |
| Substring `Type` | Middle | 1,271.692 ms | 3.810 ms | 334x |
| Blank index enumeration | First | 1,448.397 ms | 0.639 ms | 2,267x |
| Blank index enumeration | Second | 1,464.247 ms | 0.690 ms | 2,122x |
| Blank index enumeration | Middle | 1,453.931 ms | 0.649 ms | 2,239x |

The substring case is a mixed-kind search, not the type-only shortcut added in
PR #5. Blank enumeration is an index API operation. These large ratios apply to
bounded pages on this 380,000-symbol fixture; requests returning all results and
code-enriched generations do not receive the same shortcut. Complete row checks
include metadata and stable IDs, not just response counts.

## Seeding, restart and ordinary queries

The generated workloads contain 380,000 symbols in one JAR and 379,904 symbols
across 128 JARs. The real corpus is five pinned public dependency JARs: Jackson
annotations/core/databind, SQLite JDBC and RocksDB JNI, containing 18,997 indexed
symbols. It is a small real corpus, not the user's 861-JAR repository.

| Measurement | Merged main | Optimized |
| --- | --- | --- |
| Generated one JAR, fresh seed | 9.048 s | 8.530 s |
| Generated one JAR, restart to types | 0.588 s | 0.637 s |
| Generated 128 JARs, fresh seed | 3.032 s | 2.915 s |
| Generated 128 JARs, restart to types | 0.785 s | 0.781 s |
| Five real dependencies, fresh seed | 1.448 s | 1.425 s |
| Five real dependencies, restart to types | 0.627 s | 0.637 s |

The one-JAR seed median is **5.7% lower**, and all five alternating pairs are
faster. Observed ranges overlap: 8.682–9.339 s main versus 8.163–8.991 s optimized.
The 128-JAR median is 3.9% lower with overlapping ranges and mixed paired results;
this is weaker evidence. The real-dependency seed and restart results are
effectively unchanged. These are observed small-sample results, not confidence
intervals or a universal seed-speed claim. Fresh seed times cover service open
plus scan; restart-to-types includes process launch and the first full type query.

Ordinary full-result query results remain mixed:

| Measurement | Merged main | Optimized |
| --- | --- | --- |
| One JAR, broad warm types, fresh | 8.838 ms | 9.710 ms |
| One JAR, exact type p95, fresh | 0.363 ms | 0.352 ms |
| 128 JARs, broad warm types, fresh | 18.555 ms | 18.814 ms |
| 128 JARs, exact type p95, fresh | 14.721 ms | 18.812 ms |

The 128-result exact-query p95 increases from 14.721 to 18.812 ms in the fixed
31-query fresh-process batch; after restart it is 18.911/18.759 ms. This experiment
does not isolate whether short-run compilation, GC scheduling or other variation
caused that change, so no general query-speed improvement is claimed. The
full-result broad type query remains roughly unchanged in the multi-JAR fixture
and slightly slower in the single-JAR fixture. The new fast path targets bounded
pages rather than all query shapes.

The real dependency queries return 1 ObjectMapper, 222 Serializer types, 55
readValue methods, 877 Builder matches and 2,399 get methods. Five-worker medians
of the eleven-sample warm batches after fresh seed are:

| Query | Results | Merged main | Optimized |
| --- | --- | --- | --- |
| ObjectMapper | 1 | 0.379 ms | 0.434 ms |
| Serializer | 222 | 8.962 ms | 8.559 ms |
| readValue | 55 | 1.426 ms | 1.664 ms |
| Builder | 877 | 12.349 ms | 12.448 ms |
| get | 2399 | 23.430 ms | 22.741 ms |

## Allocation, memory and remaining costs

Separate matched JFRs estimate **5.042 → 3.541 GB allocated**,
about **29.8% less transient allocation**. GC counts are
33 → 31. These are samples, not retained memory, and profiled elapsed time is
not used in latency claims. Gram strings/hash-set nodes and merge ID-array
allocation disappear from those hot paths; temporary-run reads remain substantial.

| Fresh peak RSS | Merged main | Optimized |
| --- | --- | --- |
| One JAR | 846.5 MB | 857.4 MB |
| 128 JARs | 637.3 MB | 598.6 MB |
| Five real dependencies | 544.7 MB | 553.5 MB |

Peak RSS is mixed; lower allocation churn does not establish uniformly lower
process memory. MB uses decimal bytes. Configured budgets are unchanged, and the
existing sort/gram counter is an accounting estimate rather than a whole-process
memory bound.

Large-JAR phase medians:

| Phase | Merged main | Optimized |
| --- | --- | --- |
| Record preparation | 2.315 s | 2.038 s |
| Initial sort/spill | 1.484 s | 1.317 s |
| Merge/SST | 3.011 s | 2.966 s |

Individual medians do not sum to a median wall time. Multi-JAR phase counters
also overlap across workers and must not be summed as elapsed time. Large-JAR
input/run record counts are unchanged at 2,765,194 / 4,085,898 and spill volume
remains about 33 MB. This PR reduces allocation and pointless decoding; it does
not remove the external sort.

The next likely seed targets are temporary-run key/value copies (about 480 MB in
the optimized sampled profile), fact normalization and signature/path construction.
Concurrent read and full body/API workloads still need their own profiles.
Intra-artifact parallelism remains unproven: adding more artifact workers does
not split a single JAR, and raising concurrency before measuring memory pressure
would not establish a speed benefit.

## Correctness and validation

All **80 selected tests pass: 52 storage and 28 integration**. Added checks cover
primitive grams versus Java substring sets over randomized UTF-16 input, LRU
eviction and full posting blocks, reusable cursor widths/corruption/exhaustion,
page-boundary decoding counts, natural-ID seeking, arbitrary custom ranks and
pagination after bytecode enrichment. Existing SQLite agreement, aliases/source
precedence, corrupt posting, spill/duplicate/checksum and publication tests pass.

All 40 generated workers and 20 real-dependency workers have zero faults and
matching type identities; all query identities in the real dependency workload
agree. The 20 generated and 10 real restart workers rebuild zero artifacts. All
10 page workers reuse the same persisted fixture contents and have matching full
pages, with zero rebuilds or faults.

Four additional cross-version opens verify both directions on both generated
fixtures without rebuilding. One complete 128-JAR update sequence per revision
also passes unchanged/metadata/touch checks, release replacement, addition,
deletion, same-mtime SNAPSHOT replacement and persisted restart. Those update
runs are correctness evidence, not a repeated update-speed comparison.

Measured production code passed the complete checkpoint job in
[run 35455511165](https://github.com/maxjay/jvmd/actions/runs/35455511165) and all four
native packaging/AOT/relocation jobs plus Windows installer in
[run 35455511150](https://github.com/maxjay/jvmd/actions/runs/35455511150).
The corpus agreement job is still running. The final evidence-only commit starts
separate CI. No test, correctness floor or timing gate was weakened.

## Reproduction and evidence

Run from the optimized checkout with the pinned JDK and dependency versions listed
in the [harness README](../../benchmarks/index-updates/README.md):

```sh
git worktree add --detach /tmp/jvmd-perf-main dce594130a0ec0f2f213293c8429fa26051dd453
python benchmarks/index-updates/query-performance.py \
  --baseline /tmp/jvmd-perf-main --current "$PWD" \
  --java-home /path/to/jdk-25.0.4.1+1 \
  --dependencies /path/to/release/lib/jvmd \
  --root /tmp/jvmd-performance --runs 5
python benchmarks/index-updates/dependencies.py \
  --baseline /tmp/jvmd-perf-main --current "$PWD" \
  --java-home /path/to/jdk-25.0.4.1+1 \
  --dependencies /path/to/release/lib/jvmd \
  --root /tmp/jvmd-real-dependencies --runs 5
python benchmarks/index-updates/pages.py \
  --benchmark-root /tmp/jvmd-performance \
  --java-home /path/to/jdk-25.0.4.1+1 \
  --root /tmp/jvmd-pages --runs 5 --samples 5
python benchmarks/index-updates/profile-seed.py \
  --benchmark-root /tmp/jvmd-performance \
  --java-home /path/to/jdk-25.0.4.1+1 \
  --root /tmp/jvmd-allocation-profile
```

Run these sequentially. The existing update worker also supports `seed`, `updates`
and `restart`; compatibility checks use `reopen` with the other implementation's
compiled classpath and persisted state. Do not mutate the shared pristine fixture
used by the timed seed/page comparisons when running update checks.

- [Generated seed/restart/query matrix](2026-09-19-post-merge-seeding.json)
- [Pinned real dependencies and query mix](2026-09-19-post-merge-dependencies.json)
- [Bounded full-page comparison](2026-09-19-post-merge-pages.json)
- [Matched diagnostic allocation profiles](2026-09-19-post-merge-allocation.json)
- [Initial investigation profiles](2026-09-19-post-merge-investigation.json)
- [Bidirectional persisted-index compatibility](2026-09-19-post-merge-compatibility.json)
- [Maven-update correctness sequences](2026-09-19-post-merge-updates.json)
- [Test and CI snapshot](2026-09-19-post-merge-validation.json)

These results compare against the merged Rocks implementation, not the historical
SQLite main. Previous SQLite, Merkle and JDTLS experiments remain separate dated
snapshots; no new cross-server ranking is inferred from this run. Corporate
861-JAR, durable WSL and broad concurrent/body/diagnostic workloads remain outside
this benchmark's acceptance scope.
