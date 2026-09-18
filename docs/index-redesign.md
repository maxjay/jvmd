# Immutable artifact index migration

The implementation is on `indexing/immutable-artifacts`; checkpoint history is in
[INDEXING-PROGRESS.md](../INDEXING-PROGRESS.md). **The replacement is not complete.**
SQLite still serves the full `IndexStore` contract and receives artifact writes.
Rocks is a production-integrated generation store, semantic-state store and search
comparison path. Removing SQLite ingestion requires a complete replacement store
and agreement for all consumers, including local sources, JDK enrichment,
hierarchy/overrides, lazy bytecode references and documentation.

## Measured decision and current performance

The separate `benchmark/index-storage` branch selected Rocks external SSTs over
an immutable sorted-file prototype on 800,000 pre-parsed facts: comparable median
publication time, about 83% less final storage and 66% less steady-state write traffic.
Those numbers compare the two prototypes; they are not SQLite-to-production gains.

The [retained production-path report](performance/2026-09-18-index-migration.json)
contains three fresh JVM runs per backend, three unchanged restarts per backend,
and one-JAR replacement. The generated repository has 8 JARs, 64 classes and 2,240
symbols. Both paths include indexing, workspace selection, queries and shutdown;
the migration also includes Rocks sorting, publication and SQLite writes.

| Fresh-run measurement | SQLite control median (range) | Dual-write migration median (range) |
| --- | --- | --- |
| Seed | 1.424 s (1.411–1.469) | 2.212 s (2.116–2.481) |
| Through queries and close | 1.525 s (1.518–1.598) | 2.442 s (2.267–2.735) |
| Process write traffic | 8.85 MB (8.81–8.86) | 25.24 MB (24.76–25.28) |
| Query p95 | 2.59 ms (1.97–3.34) | 5.36 ms (3.98–5.53) |
| Peak process RSS | 140.0 MB (137.5–140.5) | 253.8 MB (252.3–255.6) |

These measurements fail the proposed 3× seed, 50% write reduction and query p95
acceptance targets. They identify dual writing as remaining production work;
changing targets or dropping correctness checks would not solve it. This is a
small generated fixture on Linux/overlayfs with warm OS caches, not WSL or the
861-JAR corporate repository. Retain the SQLite rollback until acceptance passes.

## Run the benchmark

Use the pinned JDK 25 and the repository's Maven build:

```sh
mvn -B -DskipTests install
mvn -B -pl jvmd-tests test -Dtest=IndexRedesignBenchmarkTest -DexcludedGroups=
```

The parent launches a fresh `-Xmx1024m` JVM for every sample, alternates backend
order, records CPU, peak heap pool usage, Linux peak RSS, process-attributed write
bytes, index size, phase timings and first/repeated query latency. Raw files and
median/min/max summaries are written under
`jvmd-tests/target/index-redesign-benchmark`. The fast CI workflow retains JSON evidence.
The assertion gate checks unchanged restarts perform zero artifact rebuilds and
zero global linking; no timing budget is weakened.

A larger public generated fixture can be requested with
`-Djvmd.benchmark.artifacts=1 -Djvmd.benchmark.classes=950 -Djvmd.benchmark.fields=397`
(380,000 symbols). For the real repository:

```sh
mvn -B -pl jvmd-tests test -Dtest=IndexRedesignBenchmarkTest -DexcludedGroups= \
  -Djvmd.benchmark.repository=/home/you/.m2/repository \
  -Djvmd.benchmark.query=String
```

The supplied repository is read only. The one-JAR replacement scenario runs only
on the generated fixture. Measure native WSL storage and `/mnt/c` separately;
record the machine/CPU allocation and whether the OS cache was deliberately cold.
The original baseline revision remains available on `benchmark/index-storage`.

## Data and publication

Artifact identity includes full binary SHA-256, record format, indexer identity
(`jvmd-index-v3`), JDK feature/multi-release selection and indexing mode. GAV/path
context determines external SCIP identities separately. Typed symbol records,
binary/SCIP/name/path/substring postings and forward/reverse symbolic references
are sorted in bounded runs and imported as one SST. The manifest is in the same
SST and contains counts and a checksum over every record and secondary posting.
Queries load individual records. Whole-artifact reconstruction is an oracle operation.
Documentation has a separate binary-plus-source-content key and verified member
checksum. Return-type/SCIP ambiguity and source alias context still need the full
production oracle before authoritative cutover.

Generations are below `state/index-v2/generations/format-1-jdk25-jvmd-index-v3`.
A candidate is checked against its inventory before `active.manifest` switches;
`previous` retains the earlier format generation. Incomplete SSTs/sort runs never
have a published manifest. Startup removes staging remnants after obtaining the
Rocks database lock. New formats backfill from existing SQLite entries without
replacing their SQLite symbol IDs.

## Controls and diagnostics

| JVM property | Default | Meaning |
| --- | --- | --- |
| `jvmd.index.generation.backend` | `auto` | `rocksdb-sst`, auto provider discovery, or `none` to stop generating Rocks data |
| `jvmd.index.read.backend` | `shadow` | SQLite answers plus comparison; `sqlite` disables search comparison; `rocksdb-sst` is an experimental complete-workspace search path |
| `jvmd.index.generation_budget_mb` | heap-derived, 8–128 MiB | Weighted admission estimate held from parsing through publication |
| `jvmd.index.native_budget_mb` | 64 MiB | Shared strict block cache and write-buffer accounting across five Rocks databases |
| `jvmd.index.sort_buffer_bytes` | 4 MiB | Per-builder sorted-run buffer; minimum 64 KiB; a single larger record is processed alone |
| `jvmd.index.scan.initial_delay_seconds` | 2 | Delay before the first repository crawl |

Native cache accounting is not total process memory. Heap models, sort buffers,
JNI/table-reader state, compression work, thread stacks and mapped files remain
part of RSS. Oversized parsed artifacts receive exclusive estimated admission;
there is no claim that their actual heap model is bounded by that estimate.

`daemon.status` reports active artifact operations, overlapping worker durations,
scan elapsed time, SQL queue/execution times and actual global `link_passes`.
`generation_sink.repository` adds sort spill/peak bytes, SST bytes, pending/running
compactions, memtable/table-reader memory and write stalls. `native_memory` reports
shared cache budget/use/pins. `shadow_validation` records comparisons/mismatches.
A partial Rocks workspace falls back to the complete SQLite view.

Source deltas update one persisted file and its Merkle ancestor lists. Reconciliation
still scans the filesystem for external creates/deletes; timestamps do not prove
that descendants are unchanged. Semantic API changes and unresolved dependants
produce durable invalidation revisions, consumed by each analyzer on its own
executor. Body-only changes stay local. Directory-scanning races with atomic
snapshot files tolerate disappearance while retaining other I/O errors.

## Recovery and packaging

For immediate rollback, restart with
`-Djvmd.index.generation.backend=none -Djvmd.index.read.backend=sqlite`.
Keep `index.db` and the previous generation while validating. After an interrupted
build, restart normally: absent manifests rebuild and staged files are discarded.
On low disk space publication fails without making the unfinished artifact visible;
free space and retry. Corrupt generation verification prevents initial activation.
Workers finish before native handles close; a worker that cannot stop causes a
controlled close failure rather than freeing handles it still uses.

RocksJNI is pinned at 10.10.1.1. The release archive contains the Apache/LevelDB
notices. The existing Linux x64/arm64 and macOS x64/arm64 release matrix now runs
`dev.jvmd.dist.IndexSmoke` from a relocated image and state directory with spaces,
checking ServiceLoader/native loading, publication, query and reopen. The WSL
installer continues to use the Linux distribution and its existing checks.

## Remaining acceptance work

- Implement a full Rocks `IndexStore`, remove production JAR SQL ingestion and
  replace all hierarchy/documentation/local-source consumers with complete equivalents.
- Validate deterministic search ordering, all pages, overrides, source/JDK overlays,
  ambiguous identities and aliases before relying on authoritative Rocks reads.
- Finish generation pinning/rollback integration for all production readers and
  publication-boundary fault injection, including process termination and disk full.
- Run unchanged/restart/replacement, body/API edit and concurrent-query matrices
  against the same baseline, retain the real 861-JAR run, and pass the 0.97 corpus floor.
- Record successful native release-matrix jobs and total heap/native/RSS evidence.

The migration remains a draft until those gates pass.
