# Query planning, persisted startup and warm queries — 2026-09-19

This records the query-planning checkpoint. The subsequent
[seed allocation follow-up](2026-09-19-seed-optimization.md) contains the latest
seed and JDTLS measurements; the experiments below remain their original snapshot.

Upfront indexing is intended to make repeated queries and updates cheap. The old
Rocks type search still decoded 380,000 records to return 950 classes because
member paths shared the queried type-name gram. That work repeated after warm-up.
The optimized planner uses the existing type postings when they contain fewer
candidates, and retains selective gram postings for rare queries.

Posting decoding also allocated a 256-integer temporary array for singleton
blocks. It now bounds that array by the encoded byte count and avoids copying a
fully used array. Both changes preserve the persisted format and existing indexes.

## Corrected JDTLS interpretation

JDTLS also does upfront indexing. The exact distribution manifests identify
JDT Core `6725c16c24d94c83302346dc384bb915a0f2fe1a` and JDTLS
`08eafe6ff60c7159ef88571d47b6a9ef82fef94e`. The pinned
[BinaryIndexer](https://github.com/eclipse-jdt/eclipse.jdt.core/blob/6725c16c24d94c83302346dc384bb915a0f2fe1a/org.eclipse.jdt.core/search/org/eclipse/jdt/internal/core/search/indexing/BinaryIndexer.java)
indexes method/field declarations and constant-pool references. The pinned
[WorkspaceSymbolHandler](https://github.com/eclipse-jdtls/eclipse.jdt.ls/blob/08eafe6ff60c7159ef88571d47b6a9ef82fef94e/org.eclipse.jdt.ls.core/src/org/eclipse/jdt/ls/core/internal/handlers/WorkspaceSymbolHandler.java)
uses type searches that wait for indexing readiness. A type-only LSP result does
not mean JDT built a type-only index. The earlier suggestion that only JVMD did
field/method/reference work was too strong; JVMD also defers some bytecode work.

Both systems benefit from persisted indexes. This test distinguishes fresh index
construction, reopening persisted data, and repeated queries within a process.
It does not establish that JVMD must win every operation after warm-up.

## Method and revisions

- Baseline Rocks: GitHub `deefc256`, local `9dda2e5`, tree `16be1142`.
- Optimized Rocks: GitHub `3871bc83`, local `ebbe326`, tree `cc17acb0`.
- Three alternating repetitions, two fixtures, fresh and unchanged-reopen
  processes: 24 JVMD workers. Twelve separate JDTLS 1.61.0 processes use the
  identical JAR bytes. Every JVM has a 1 GiB heap limit; work runs serially.
- Pinned Temurin 25.0.4.1+1, eight allocated AMD EPYC CPUs, volatile overlayfs,
  no OS-cache flush or application AOT archive. These are generated fixtures,
  not corporate-repository or durable WSL acceptance.
- JVMD now records **parent-process launch to an all-types-ready marker**, including
  JVM startup. Its earlier reports only timed work inside the JVM; those old
  readiness values must not be treated as equivalent process-start measurements.
- Each worker measures 31 exact type queries and then three broad warm queries
  after the field-query batch. Broad queries return all 950/1,024 fixture classes.
  Warm results use the median of three per-worker medians. This fixed short
  warm-up is not proof of stabilized long-running JIT throughput or tail latency.
- JVMD uses its index-service API. JDTLS includes Eclipse/project/JDK startup and
  LSP serialization/transport. Parent launch boundaries now agree, but the server
  duties, matching algorithms and response representations still differ.

All timings below are three-run medians. Main/SQLite was not rerun for this
follow-up; the baseline here is the previous Rocks implementation.

## Process start until all fixture types are searchable


| Fixture | Scenario | Previous Rocks | Optimized Rocks | JDTLS |
| --- | --- | --- | --- | --- |
| One JAR | Fresh index | 12.004 s | 9.911 s | 7.731 s |
| One JAR | Persisted restart | 2.452 s | 0.561 s | 3.716 s |
| 128 JARs | Fresh index | 5.318 s | 3.488 s | 7.809 s |
| 128 JARs | Persisted restart | 2.778 s | 0.775 s | 3.587 s |

The type-query fix removes roughly two seconds of avoidable work from readiness.
JDTLS still wins the single-JAR fresh-start sample. Optimized JVMD has a clearer
advantage when reopening its persisted index and in the multi-JAR sample.
All twelve JVMD reopen workers rebuild zero artifacts.

## Broad warm type search


| Fixture | Warm process | Previous Rocks API | Optimized Rocks API | JDTLS LSP |
| --- | --- | --- | --- | --- |
| One JAR, 950 results | After fresh build | 1704.84 ms | 9.19 ms | 219.85 ms |
| One JAR, 950 results | After restart | 1600.55 ms | 9.29 ms | 215.21 ms |
| 128 JARs, 1,024 results | After fresh build | 1770.91 ms | 18.29 ms | 131.78 ms |
| 128 JARs, 1,024 results | After restart | 1730.83 ms | 24.22 ms | 107.65 ms |

The strongest causal comparison is previous versus optimized JVMD using the same
API and result checks. Cross-server ratios also include LSP and response costs.
The planner applies to searches restricted to type kinds; mixed-kind/unfiltered
searches retain their broader candidate space and are not promised this speedup.
Source precedence, aliases, pagination and final predicates remain in force.

## Exact queries and seed costs

This optimization does not make every query faster. The fixed 31-query batch gives:


| Exact type query p95 | Process | Previous Rocks API | Optimized Rocks API | JDTLS LSP |
| --- | --- | --- | --- | --- |
| One JAR, one result | After fresh build | 0.376 ms | 0.320 ms | 31.909 ms |
| One JAR, one result | After restart | 0.561 ms | 0.741 ms | 31.673 ms |
| 128 JARs, 128 results | After fresh build | 13.152 ms | 19.310 ms | 51.144 ms |
| 128 JARs, 128 results | After restart | 20.931 ms | 18.438 ms | 53.853 ms |

The 128-result fresh-process exact-query p95 is worse in this batch. Removing the
large preceding broad-query workload also changes incidental JVM warm-up; this
experiment does not isolate that contribution. No universal exact-query latency
improvement is claimed.


| Fresh repository scan | Previous Rocks | Optimized Rocks |
| --- | --- | --- |
| One JAR | 9.670 s | 9.429 s |
| 128 JARs | 2.930 s | 3.097 s |

Seed ranges overlap: 9.620–10.133 / 9.285–9.782 s for one JAR, and
2.926–3.295 / 3.078–3.444 s for 128 JARs (previous/optimized). These samples do
not establish a material seed-speed improvement. Preparing postings and sorting/
merging them remain the main seed costs; the type-query fix affects query work.

## Allocations and memory

One diagnostic JFR per implementation estimates merge-side posting-decoder
allocations at **1.619 GB before versus 0.306 GB after**, about 81% less. Total
sampled allocation across seed plus queries falls from 9.075 GB to 6.057 GB; both
changes contribute to that total. These are sampling estimates of transient
allocation, not peak/retained memory, and profiled timings are not acceptance data.


| Fixture | Process | Previous peak RSS | Optimized peak RSS |
| --- | --- | --- | --- |
| One JAR | Fresh | 867.0 MB | 857.2 MB |
| One JAR | Restart | 375.2 MB | 264.1 MB |
| 128 JARs | Fresh | 735.6 MB | 678.1 MB |
| 128 JARs | Restart | 376.7 MB | 258.1 MB |

## Validation and reproduction

All 73 selected tests pass: 45 storage and 28 integration tests, including kind
filtering/SQLite agreement, pagination, source/alias behavior, persisted state,
packed posting boundaries, and malformed/overflow/truncation rejection. All 24
measurement workers report zero faults; result identity hashes agree across both
implementations and every restart. Warm broad queries also compare entire result
rows with the first response.

The optimized production commit passed the full checkpoint job in
[run 35445446148](https://github.com/maxjay/jvmd/actions/runs/35445446148), and all
four native Linux/macOS packaging/AOT/relocation jobs plus the Windows installer
in [run 35445446248](https://github.com/maxjay/jvmd/actions/runs/35445446248).
Its serialized corpus job remains pending. No gate was weakened. The final
report-only commit triggers its own CI; these pass claims name the measured code.

[Reproduction instructions](../../benchmarks/index-updates/README.md) describe the
new `query-performance.py` harness and `jdtls.py --warm-type-queries 3`.

- [JVMD before/after raw report](2026-09-19-query-optimization.json)
- [JDTLS single-JAR fresh/restart/warm report](2026-09-19-jdtls-warm-single-380000.json)
- [JDTLS 128-JAR fresh/restart/warm report](2026-09-19-jdtls-warm-m2-128.json)
- [Allocation profile summary](2026-09-19-query-allocation-profile.json)
- [Pinned JDT/JDTLS source audit](2026-09-19-jdtls-source-audit.json)
- [Revision mappings, tests and CI snapshots](2026-09-19-query-validation.json)

