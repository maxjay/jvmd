# Immutable artifact index migration

The implementation is on `indexing/immutable-artifacts`; checkpoint history is in
[INDEXING-PROGRESS.md](../INDEXING-PROGRESS.md). **The replacement is not complete.**
The default `rocksdb-sst` backend now implements the complete `IndexStore` contract
without opening SQLite: binary artifacts, documentation, per-file source facts,
workspace membership, hierarchy, lazy bytecode references and JDK enrichment.
SQLite remains an explicitly selected comparison/rollback backend. Deterministic
integration fixtures pass; the full performance/corpus/platform acceptance gate
is still open. The historical dual-write reports below are not replacement results.

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

These historical dual-write measurements fail the proposed 3× seed, 50% write reduction and query p95
acceptance targets. Dual writing has since been removed from the default store;
changing targets or dropping correctness checks would not solve it. This is a
small generated fixture on Linux/overlayfs with warm OS caches, not WSL or the
861-JAR corporate repository. Retain the SQLite rollback until acceptance passes.

## Run the benchmark

Use the pinned JDK 25 and the repository's Maven build:

```sh
mvn -B -DskipTests install
mvn -B -pl jvmd-tests test -Dtest=IndexRedesignBenchmarkTest -DexcludedGroups=
```

The harness now compares isolated full SQLite and full Rocks stores, asserts that
Rocks opens no SQLite file, and checks that staging is empty after close. It records
compiled implementation hashes as well as the Git revision.

The parent launches a fresh `-Xmx1024m` JVM for every sample, alternates backend
order, records CPU, peak heap pool usage, Linux peak RSS, process-attributed write
bytes, index size, phase timings and first/repeated query latency. Raw files and
median/min/max summaries are written under
`jvmd-tests/target/index-redesign-benchmark`. The fast CI workflow retains JSON evidence.
Use `-Djvmd.benchmark.state_root=/path/on/measured/filesystem` to control the
measurement filesystem; completed JSON/logs are still retained in the target
report directory. The local workspace mount was observed to restore deleted
staging files, while the same worker under `/tmp` passed cleanup. Local follow-up
measurements therefore use `/tmp` for both backends. The container uses overlayfs
with `fsync=volatile`; these results cannot establish the durable WSL acceptance
target. The earlier final-size figures are diagnostic only.

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
(`jvmd-index-v5`), JDK feature/multi-release selection and indexing mode. GAV/path
context determines external SCIP identities separately. Typed symbol records,
binary/SCIP/name/path/substring postings and forward/reverse symbolic references
are sorted in bounded compressed runs and imported as one SST. Secondary postings
use blocks of at most 256 delta-encoded local IDs. The manifest is in the same SST
and contains counts and a checksum over every record and secondary posting.
Queries load individual records; whole-artifact reconstruction is an oracle operation.
Documentation has a separate binary-plus-source-content key and verified member
checksum. Metadata in a separate Rocks database atomically selects each path's
binary, code and documentation generations; source facts are stored per file.

Generations are below `state/index-v2/generations/format-1-jdk25-jvmd-index-v5`.
A candidate is checked against its inventory before `active.manifest` switches;
`previous` retains the earlier format generation. Incomplete SSTs/sort runs never
have a published manifest. Startup removes staging remnants after obtaining the
Rocks database lock. A format change rebuilds disposable index data; the prior
SQLite index is retained for explicit rollback. Readers hold the store metadata
monitor while selecting/querying generations; production reclamation remains
conservative until broader reader-pin/failure-injection acceptance is complete.

## Controls and diagnostics

| JVM property | Default | Meaning |
| --- | --- | --- |
| `jvmd.index.store.backend` | `rocksdb-sst` | Full production store; `sqlite` explicitly selects the legacy comparison backend |
| `jvmd.index.generation.backend` | `auto` | Rocks provider discovery; `none` is valid with the SQLite store only |
| `jvmd.index.read.backend` | `shadow` | Legacy SQLite comparison setting; the Rocks store always serves its own reads |
| `jvmd.index.generation_budget_mb` | heap-derived, 8–128 MiB | Weighted admission estimate held from parsing through publication |
| `jvmd.index.native_budget_mb` | 64 MiB | Shared strict block cache and write-buffer accounting across six Rocks databases |
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
The production Rocks store does not fall back to SQLite. Missing membership is
reported through the existing workspace contract. Source caches have a separate
16 MiB estimated retention budget; loading a single source module is not a hard
heap bound.

Source deltas update one persisted file and its Merkle ancestor lists. Reconciliation
still scans the filesystem for external creates/deletes; timestamps do not prove
that descendants are unchanged. Semantic API changes and unresolved dependants
produce durable invalidation revisions, consumed by each analyzer on its own
executor. Body-only changes stay local. Directory-scanning races with atomic
snapshot files tolerate disappearance while retaining other I/O errors.

## Recovery and packaging

For immediate rollback, restart with
`-Djvmd.index.store.backend=sqlite -Djvmd.index.generation.backend=none
-Djvmd.index.read.backend=sqlite`.
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

- Validate deterministic search ordering, all pages, overrides, source/JDK overlays,
  ambiguous identities and aliases across the complete corpus and concurrent-ingestion matrix.
- Finish generation pinning/rollback integration for all production readers and
  publication-boundary fault injection, including process termination and disk full.
- Run unchanged/restart/replacement, body/API edit and concurrent-query matrices
  against the same baseline, retain the real 861-JAR run, and pass the 0.97 corpus floor.
- Record successful native release-matrix jobs and total heap/native/RSS evidence.

The migration remains a draft until those gates pass.
