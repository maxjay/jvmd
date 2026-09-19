# Immutable artifact index migration

The implementation is on `indexing/immutable-artifacts`; checkpoint history is in
[INDEXING-PROGRESS.md](../INDEXING-PROGRESS.md). **Acceptance remains incomplete.**
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

The [isolated production-store report](performance/2026-09-19-rocks-isolated-40000.json)
contains three fresh JVM runs per backend, three unchanged restarts per backend,
and one-JAR replacement. The generated fixture has one JAR, 100 classes and 40,000
symbols. Rocks opens no SQLite database. Sorting, indexes, publication, queries,
workspace selection and shutdown are included. This measures local commit
`ae1ab71` (GitHub `9bb689d9`); class hashes are retained. The subsequent return-type
identity correction does not occur in this fixture, but is not part of that run.

| Fresh-run measurement | SQLite median (range) | RocksDB median (range) |
| --- | --- | --- |
| Seed | 6.404 s (6.263–6.760) | 9.558 s (9.472–9.579) |
| Through queries and close | 6.688 s (6.570–7.030) | 10.199 s (10.163–10.268) |
| Process write traffic | 74.47 MB (74.47–74.48) | 38.98 MB (38.97–39.12) |
| Query p95 | 18.57 ms (16.58–19.47) | 13.22 ms (13.09–17.01) |
| Final index size | 38.18 MB | 4.09 MB |
| Peak process RSS | 270.8 MB (269.0–277.3) | 347.2 MB (335.9–425.1) |

Rocks queries are 28.8% faster here, process writes are 47.7% lower, and the final
index is 89.3% smaller. Seeding is 49.2% slower and RSS is higher. The proposed 3×
seed and 50% write-reduction targets have **not** passed. All six unchanged restarts
perform zero artifact rebuilds/global link passes. A one-JAR replacement rebuilds
one artifact; Rocks runs no global link pass. Body/API editing and queries during
ingestion still require the controlled matrix.

The [old dual-write report](performance/2026-09-18-index-migration.json) and
[first independent small](performance/2026-09-19-rocks-initial-small.json) and
[40,000-symbol](performance/2026-09-19-rocks-initial-40000.json) experiments are retained
as historical evidence. The initial large run wrote 583 MB; compact postings and
compressed sort runs reduced that to 39 MB. Earlier workspace-path final-size
figures were affected by staging files returning after deletion. The controlled
follow-up checks that staging is empty after close. These generated Linux samples
are not the durable WSL or real 861-JAR corporate acceptance run.

## Run the benchmark

Use the pinned JDK 25 and the repository's Maven build:

```sh
mvn -B -DskipTests install
mvn -B -pl jvmd-tests test -Dtest=IndexRedesignBenchmarkTest -DexcludedGroups=
```

The harness now compares isolated SQLite and Rocks stores through the production
ServiceLoader provider, including candidate validation and activation. Earlier
reports used a direct sink and omitted provider activation; their scope is
store-level performance. The harness asserts that
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
(`jvmd-index-v7`), JDK feature/multi-release selection and indexing mode. GAV/path
context determines external SCIP identities separately. Typed symbol records,
binary/SCIP/name/path/substring postings and forward/reverse symbolic references
are sorted in bounded compressed runs and imported as one SST. Secondary postings
use blocks of at most 256 delta-encoded local IDs, including temporary runs.
Substring IDs accumulate into bounded blocks before sorting. Merge passes copy
disjoint ranges intact and expand overlapping ranges while preserving ordering
and duplicate detection. The manifest is in the same SST
and contains counts and a checksum over every record and secondary posting.
Queries load individual records; whole-artifact reconstruction is an oracle operation.
JVM return-only overload collisions carry the same return-type-disambiguated
SCIP identity as the SQLite oracle, including direct lookup and pagination.
Documentation has a separate binary-plus-source-content key and verified member
checksum. Metadata in a separate Rocks database atomically selects each path's
binary, code and documentation generations; source facts are stored per file.

Generations are below `state/index-v2/generations/format-1-jdk25-jvmd-index-v7`.
A candidate is checked against its inventory before `active.manifest` switches.
New immutable publications reuse their verified checksum/schema proof within the
same owner; reopened unvalidated candidates receive a streaming verification.
Validation never reconstructs the whole artifact or traverses every symbol.
The `previous` manifest retains the earlier format generation. Incomplete SSTs/sort runs never
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
| `jvmd.index.sort_buffer_bytes` | 4 MiB | Per-builder sort and gram-accumulator budget; minimum 64 KiB; a single larger record is processed alone |
| `jvmd.index.scan.initial_delay_seconds` | 2 | Delay before the first repository crawl |

Native cache accounting is not total process memory. Heap models, sort buffers,
JNI/table-reader state, compression work, thread stacks and mapped files remain
part of RSS. Oversized parsed artifacts receive exclusive estimated admission;
there is no claim that their actual heap model is bounded by that estimate.

`daemon.status` reports active artifact operations, overlapping worker durations,
scan elapsed time, SQL queue/execution times and actual global `link_passes`.
`generation_sink.repository` adds `record_prepare_ms`, `sort_spill_ms`,
`sort_merge_and_sst_ms`, `file_sync_ms`, `sst_ingest_ms` and
`publication_verify_ms`. These are cumulative worker durations; they can overlap
across parallel artifacts. It also reports logical gram occurrences, compact
posting blocks, sorter input/run records, sort spill/peak bytes, SST bytes, pending/running
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
- Repeat total heap/native/RSS acceptance on the target corpus. The full-store
  implementation passed all four native platform packaging/AOT/relocation jobs
  and the Windows installer check in [run 35406797784](https://github.com/maxjay/jvmd/actions/runs/35406797784).
  WSL-specific filesystem measurements and final-revision CI remain separate gates.

The migration remains a draft until those gates pass.
