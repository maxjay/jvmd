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

The full-provider reports for [40,000 symbols](performance/2026-09-19-rocks-provider-40000.json)
and [380,000 symbols](performance/2026-09-19-rocks-provider-380000.json) include three
fresh JVM runs, three unchanged restarts per backend and one-JAR replacement. The
large generated fixture has one JAR and 950 classes. Measurements include production
candidate validation/activation, workspace loading, queries and shutdown. Rocks opens
no SQLite database. These reports measure local `53a693c` (GitHub `36b08133`), before
the latest fixed-buffer/native-verification optimization; compiled hashes are retained.

| 380,000-symbol fresh run | SQLite median (range) | RocksDB median (range) |
| --- | --- | --- |
| Seed | 55.077 s (54.713–55.202) | 26.113 s (25.539–26.709) |
| Through queries and close | 56.262 s | 26.944 s |
| Process write traffic | 696.20 MB | 86.73 MB |
| Query p95 | 64.41 ms (51.36–98.25) | 39.56 ms (28.88–42.51) |
| Final index size | 371.93 MB | 37.71 MB |
| Peak process RSS | 985.8 MB | 861.1 MB |

This is **2.11x faster seeding and 87.5% fewer writes**, still short of the proposed
3x seed target. First-query ranges overlap (SQLite 115–134 ms, Rocks 123–147 ms);
no first-query improvement is claimed. All six unchanged restarts perform zero
artifact rebuilds/global links. One-JAR replacement takes SQLite/Rocks 78.61/26.89 s,
with exactly one artifact rebuilt; Rocks performs no global link pass. The smaller
fixture reports SQLite/Rocks seed 6.215/4.504 s and writes 74.49/21.56 MB.

The [JFR sample summary](performance/2026-09-19-seed-profile.json) identified temporary
sort I/O as a dominant CPU cost, rather than native ingestion. In the large run,
Rocks spent 5.35 s preparing records, 2.75 s spilling sorted runs, 8.85 s merging and
writing the SST, and 4.44 s re-reading it for verification; native ingestion took
11 ms. Bounded early gram accumulation reduced 25.9 million logical gram postings
to 103,293 blocks. The latest changes use fixed run buffers and verify finished SSTs
natively before publication; final three-run evidence is recorded separately.

The [old dual-write report](performance/2026-09-18-index-migration.json),
[first independent small](performance/2026-09-19-rocks-initial-small.json),
[initial 40,000-symbol](performance/2026-09-19-rocks-initial-40000.json),
[compressed-run](performance/2026-09-19-rocks-isolated-40000.json), and
[early-posting](performance/2026-09-19-rocks-posting-40000.json) reports remain historical
evidence. Those direct-store experiments omitted provider activation. Earlier
workspace-path final-size figures were affected by staging files returning after
deletion; follow-up runs use `/tmp` for both backends and assert empty staging after
close. These generated Linux samples are not the durable WSL or real 861-JAR run.

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
The builder validates symbol schemas/IDs and relationship sources and computes the
manifest SHA-256 while writing sorted records. Before ingestion, `SstFileReader`
verifies every SST block checksum and checks the file entry count, including the
manifest. These native block checksums are distinct from the application SHA-256.
New publications reuse that proof within the same owner; reopened unvalidated
candidates receive a full streaming application SHA-256/schema verification.
Validation never reconstructs a whole-artifact model or performs per-symbol edge queries.
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
posting blocks, sorter input/run records, sort spill/peak bytes, native publication
checks, full verification passes, activation-proof reuse, SST bytes, pending/running
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
