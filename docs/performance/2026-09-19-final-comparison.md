# Actual main, Maven updates, source Merkle state and JDTLS — 2026-09-19

The subsequent [query-planning and warm-query report](2026-09-19-query-optimization.md)
measures a newer implementation and includes JVM launch on both sides. The
numbers below remain the original measured-revision comparison.

RocksDB improves fresh seeding and Maven updates on both generated fixtures. Source
Merkle updates also avoid almost all reconciliation work when the changed file is
known. These are separate mechanisms: Maven scans do not call source Merkle state.
JDTLS wins single-JAR type readiness; Rocks wins the 128-JAR readiness sample.

## Revisions and method

- Actual main: `e651e9f8db50f3a6ecd4d9fb19c1d4681196708d` (SQLite implementation).
- Rocks: GitHub `cd4e703abe3798a0de30cca3c46786c65ea54bb6`, local
  `a89e0f2375382d43c21e9370fb5d2d686636c5ab`; both have tree
  `ec6c71406d0398d2e770c16179144d7e136bf3ef`. Includes the admission deadlock fix.
- Temurin 25.0.4.1+1, AMD EPYC 9V74, eight allocated CPUs, separate 1 GiB JVMs.
  Runs are serial; backend order alternates. No deliberate OS-cache flush.
- Three fresh repetitions per backend/fixture. The multi-JAR run adds separate
  update and post-update restart JVMs: 24 JVMD workers in total. Another 12 JVMs
  run JDTLS fresh/restart. All fixture JAR SHA-256 values match across systems.
- One JAR: 950 classes / 380,000 symbols. Maven fixture: 128 JARs, 1,024 classes /
  379,904 symbols, cross-JAR references and one SNAPSHOT. This is not the unavailable
  corporate 861-JAR repository.
- Sources from unchanged checkouts compile independently using the same pinned
  dependencies; the classpath harness omits module descriptors. Each production
  provider still performs its normal publication and activation.
- `/tmp` overlayfs uses volatile fsync. These are container throughput measurements,
  not durable WSL/storage acceptance. Earlier Intel-host numbers are historical.

Tables show medians of three repetitions; p95 entries are medians of three
31-query p95 measurements. MB means decimal megabytes. Raw reports retain ranges,
per-run measurements, hashes, counters and status snapshots.

## Fresh seed: actual main versus Rocks

Seed is store open plus repository scan; query readiness is reported separately.


| Fixture | Main seed | Rocks seed | Speedup |
| --- | --- | --- | --- |
| One JAR | 33.481 s | 9.722 s | 3.44x |
| 128 JARs | 25.586 s | 3.236 s | 7.91x |

Both generated samples exceed the proposed 3x seed target. This does not establish
that target on the real repository. The single-JAR seed ranges are main
32.984–34.234 s and Rocks 9.457–10.358 s.


| Fixture | Measurement | Main | Rocks |
| --- | --- | --- | --- |
| One JAR | Scan writes | 693.88 MB | 86.76 MB |
| One JAR | Query writes | 317.72 MB | 0.01 MB |
| One JAR | Peak RSS | 882.92 MB | 947.62 MB |
| One JAR | Final index | 371.17 MB | 37.71 MB |
| 128 JARs | Scan writes | 460.60 MB | 55.06 MB |
| 128 JARs | Query writes | 313.91 MB | 0.00 MB |
| 128 JARs | Peak RSS | 340.38 MB | 777.67 MB |
| 128 JARs | Final index | 377.94 MB | 39.04 MB |

Rocks uses more peak RSS in this experiment, especially with multiple artifacts
building concurrently. A smaller final index is not evidence of a smaller total
process footprint. Scan writes exclude workspace loading and query traffic;
SQLite's query workload spills large temporary files, so total process writes
must not be described as scan amplification. Background Rocks flushes/compactions
can cross phase boundaries. Raw reports also retain shutdown-inclusive writes.

## Updating the Maven repository

Mutations happen outside scan timing. Every scan is followed by complete fixture
type-count checks and repeated field/type queries. Replacement checks require all
new methods and zero old methods. An unchanged SNAPSHOT is intentionally rehashed;
release files with unchanged stamps are reused.


| Scenario | Main scan | Rocks scan | Speedup |
| --- | --- | --- | --- |
| Unchanged, first scan after restart | 860.30 ms | 92.55 ms | 9.3x |
| Unchanged, warm | 787.76 ms | 28.36 ms | 27.8x |
| Metadata-only edit | 773.69 ms | 22.58 ms | 34.3x |
| Touch release, identical bytes | 793.52 ms | 18.14 ms | 43.7x |
| Replace one release JAR | 1324.43 ms | 214.16 ms | 6.2x |
| Add one JAR | 1015.12 ms | 106.98 ms | 9.5x |
| Delete one JAR* | 775.00 ms | 17.52 ms | Different correctness* |
| Replace SNAPSHOT, preserve mtime | 1135.84 ms | 79.49 ms | 14.3x |
| Unchanged after updates | 791.65 ms | 13.33 ms | 59.4x |
| Restart after updates | 884.93 ms | 91.67 ms | 9.7x |

*Main retains the deleted artifact's eight classes in global search and keeps its
path registered; both backends correctly exclude it from the updated workspace
classpath. Rocks removes the global visibility and path registration too. Main's
delete timing therefore does not represent equivalent correct work.

All workers report zero indexing faults. Unchanged scans rebuild zero artifacts;
release/SNAPSHOT replacement and addition publish exactly one Rocks generation.
Rocks reports zero global link passes and zero source-Merkle updates throughout.
The gains come from immutable artifact reuse and avoiding main's global relink.
No claim is made that Maven discovery skips the filesystem walk.

Scan-only write traffic is not uniformly lower: the warm unchanged scan writes
8.19 KB on main versus 57.34 KB on Rocks; the first unchanged restart writes
1.16 MB versus 15.87 MB. For a release replacement it falls from 12.28 MB to
0.365 MB, and a SNAPSHOT replacement from 6.75 MB to 0.365 MB. All post-update
restart queries pass.

## Source Merkle experiment

Within the Rocks branch, apply the same known source edit to two persisted trees:
update one file plus ancestors, or perform full source reconciliation. Known-file
timing includes its SHA-256 calculation. Twenty paired edits per repetition,
three repetitions per size; order alternates. Values below are medians of the
three per-repetition medians. This is a source-state microbenchmark, not a main,
Maven, watcher-delivery, compilation or end-to-end diagnostics comparison.


| Source files | Known-file update | Full reconciliation | Speedup |
| --- | --- | --- | --- |
| 1,000 | 0.557 ms | 18.546 ms | 33.3x |
| 10,000 | 0.543 ms | 108.744 ms | 200.3x |

All 120 pairs produce identical roots. Each known-file edit writes one file leaf,
six directory/child records and one module record. Classpath-only changes write
one module record and zero file/directory records, and change the fingerprint.
Unchanged reconciliation writes no tree records, but still walks/hashes files.
Full reconciliation remains necessary to discover unreported external changes.

## JDTLS dependency-type readiness

Pinned Eclipse JDTLS 1.61.0, distribution SHA-256
`338e7e73d61836651ba2453919a0d34fa763eb4e7c03342092309bffb8934c64`.
Each fresh Eclipse Java project explicitly references all identical fixture JARs;
Maven/Gradle import and automatic builds are disabled. The client waits until LSP
`workspace/symbol` returns all 950 or 1,024 fixture classes, then runs 31 `Type0`
queries returning one class per artifact. Restart keeps the JDTLS workspace.

JVMD calls its index-service API; JDTLS uses LSP and includes Eclipse/project/JDK
startup. JDT also indexes binary fields, methods and references; the two systems
retain different representations and query capabilities. The original JVMD timer
starts inside its JVM, while JDTLS includes process launch. The follow-up query
comparison records parent-process readiness for both. This is a comparison of
the observed dependency-type task under those startup
scopes, not equivalent total work or a general language-server ranking. JDTLS
Maven-folder updates, binary field queries and whole-repository crawling are not
measured here.


| Fixture | Process | Main: all types queryable | Rocks: all types queryable | JDTLS: all types queryable |
| --- | --- | --- | --- | --- |
| One JAR | Fresh | 38.891 s | 11.932 s | 8.324 s |
| One JAR | Restart | Not measured | Not measured | 3.829 s |
| 128 JARs | Fresh | 30.700 s | 5.467 s | 7.992 s |
| 128 JARs | Restart | 6.172 s | 2.780 s | 3.493 s |

| Fresh-run repeated type query p95 | Main API | Rocks API | JDTLS LSP |
| --- | --- | --- | --- |
| One JAR | 0.703 ms | 0.443 ms | 31.768 ms |
| 128 JARs | 4.377 ms | 16.410 ms | 49.939 ms |

JDTLS is faster for single-JAR initial type readiness; Rocks is faster for the
128-JAR readiness and restart samples. Main has the lowest repeated type-query
p95 for 128 matches, while Rocks wins that metric for one match. Transport and
query semantics limit attribution: do not convert these into a universal ranking.

JDTLS fresh peak RSS is 967.9 MB (one JAR) and 964.4 MB (128 JARs). Its final
workspace plus configuration is about 58.3/58.7 MB and includes JDK/project state;
these are not equivalent index-content sizes to JVMD's figures.

## Correctness fix, CI and remaining acceptance

The complete-repository gate exposed a fair-semaphore deadlock: an owner already
holding the artifact budget called `acquire(0)` behind a queued worker waiting
for that owner's budget. Skip acquisition when the existing permit covers the
publication. A deterministic regression times out on the old logic and passes
with the fix; all 43 Rocks storage tests pass.

The fixed production commit passed the complete checkpoint job in
[run 35442230037](https://github.com/maxjay/jvmd/actions/runs/35442230037), and all
four Linux/macOS native packaging/AOT/relocation jobs plus the Windows installer
in [run 35442229949](https://github.com/maxjay/jvmd/actions/runs/35442229949).
The optimized predecessor passed the corpus job in
[run 35410771797](https://github.com/maxjay/jvmd/actions/runs/35410771797).
The fixed commit's serialized corpus rerun remains pending; a previous revision's
success is not a claim that this rerun completed. No timing/corpus gate was relaxed.

The PR is ready for review at the user's request. Remaining acceptance includes
the corporate repository, durable WSL filesystems, full body/API and concurrent
query performance, total memory bounds, and broader reclamation/fault injection.

## Reproduction and evidence

[Harness instructions](../../benchmarks/index-updates/README.md) include both
actual-main builds, fixture generation, source Merkle and JDTLS commands.

- [Main/Rocks and source-Merkle raw report](2026-09-19-main-repository-updates.json)
- [JDTLS single-JAR raw report](2026-09-19-jdtls-single-380000.json)
- [JDTLS 128-JAR raw report](2026-09-19-jdtls-m2-128.json)
- [Revision mapping and CI snapshots](2026-09-19-update-ci-evidence.json)
- [Deadlock regression evidence](2026-09-19-admission-regression.json)
