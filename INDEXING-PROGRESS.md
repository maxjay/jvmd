# JVMD Index Rebuild Progress

Implementation branch: `indexing/immutable-artifacts`  
Base: `main` at `e651e9f8db50f3a6ecd4d9fb19c1d4681196708d` (merged diagnostics redesign).  
Source plan: `jvmd indexing redesign — implementation checklist`.

This file is the persistent source of truth for the storage/indexing redesign. A phase is only marked complete when its checkpoint is satisfied; partial implementation is `[~]`.

## Starting evidence

Historical local profiling supplied with the design document (not yet reproduced on this branch):

- Maven repository: 861 JARs / 493 MiB, no source JARs.
- Large generated API JAR: 55.3 MiB / 380,216 symbols.
- Parse: 13.4 s.
- SQLite ingestion: 95.3 s isolated / 213.4 s during full seed.
- Relationships: 979,602 emitted / 550,001 unique.
- Approximate ingestion samples: 53% symbol insert/ID retrieval, 13% edge batches, 9% WAL commit.
- Final corpus linking: ~136 s.
- Full seed was reported as >1 hour; the phase samples do not account for that entire elapsed time.

These numbers are evidence to reproduce, not acceptance measurements.

## Phases

- [~] **01 — Reproducible baseline.** Branch/base captured. A checked-in forked harness now records three fresh runs per backend, restarts, a one-JAR replacement, environment, manifest, CPU/heap/RSS/write traffic and query latency; raw evidence is retained under `docs/performance`. Existing SQLite remains the control. The real 861-JAR corporate repository remains a local benchmark.
- [~] **02 — Operation-level measurements.** Status now records discovery, hashing, parsing, SQL queue/execution, storage and resolution separately; Rocks exposes sort spills, native cache/memtable usage, compaction and stalls. Preparation/dedupe and total RSS attribution still need the large-corpus profile.
- [~] **03 — `IndexStore` backend contract and correctness oracle.** All production reads and writes now cross `IndexStore`; SQLite implements the full semantic contract and the expanded fast gate covers search, workspace isolation, local modules, source docs, inheritance, hierarchy and lazy references. Raw database access remains only as a temporary low-level test seam pending the full-suite gate.
- [~] **04 — Artifact identity and compact binary record format.** Versioned GAV-independent detached facts with artifact-local ids are implemented. Source-JAR documentation/position data is now a separately content-addressed immutable overlay keyed by binary generation + source hash, so docs can change independently of reusable binary facts; final compact-record tuning remains.
- [x] **05 — RocksDB external-SST vs minimal immutable-file prototype comparison.** Measurement branch `benchmark/index-storage` selected RocksDB SST ingestion: comparable median publish time, ~83% lower stored bytes and ~66% lower steady-state write bytes in the 800k-fact CI comparison.
- [~] **06 — Bounded parallel artifact ingestion.** Repository scanning now supports an injected artifact-generation sink. The Rocks sink builds distinct SSTs concurrently using caller scan workers, serializes only ingestion, and enforces an estimated-byte semaphore budget. Runtime wiring, external sort spills and a shared native cache/write-buffer budget are implemented. Estimates do not constitute a hard parsed-model/RSS bound; real-repository tuning remains.
- [~] **07 — Atomic artifact generations.** One content-addressed artifact is built as one external SST containing manifest + indexes and ingested as a single publication. Reopen persistence, concurrent same-key idempotence, orphan staging cleanup, manifest/payload verification, and full-inventory candidate verification before activation, active/previous rollback retention, reader pins, and pruning of older unpinned generations are covered; broader failure injection remains.
- [~] **08 — Workspace-specific symbolic relationship resolution.** Rocks now resolves artifact-local symbolic targets against an ordered workspace identity (classpath order + scope + module + source-overlay fingerprint + compiler fingerprint) and persists that cache by the complete workspace hash. Global cross-version linking is not used on this path; the full store now resolves production hierarchy and references; complete corpus and first-query scale acceptance remain.
- [~] **09 — Required search behavior.** The Rocks artifact layout now has explicit binary, name, name-path and 1–3 gram substring indexes. Workspace search returns context-derived SCIP identities in classpath order; the full store adds opaque durable handles, source overlays and bounded pagination. Deterministic page/alias fixtures pass; scale and corpus measurements remain.
- [~] **10 — Incremental artifact discovery/reuse.** The persistent Rocks inventory is now integrated with production repository scans: unchanged JARs are observed without parse/rewrite, changed/new JARs publish then observe, successful scans reconcile deletions, and failed skeleton scans deliberately skip deletion reconciliation. SNAPSHOT rehash behavior is preserved. Reader-pinned generation reclamation remains.
- [~] **11 — Per-file workspace state and Merkle fingerprints.** Persistent Rocks workspace state is now configured from the production module bind path for separate main/test contexts. Per-file content identities feed deterministic bottom-up directory/module Merkle fingerprints; unsaved editor overlays replace disk leaf hashes; compiler options, processor path/names/Lombok mode, generated-output fingerprints, ordered classpath and JDK/release identity participate. Unchanged binds issue zero state writes. Known source deltas update one leaf and its ancestors; full reconciliation handles external create/delete changes. Rename/symlink/generated-file acceptance remains.
- [~] **12 — Narrow semantic invalidation.** Persistent Rocks semantic state is now fed directly from production analyzer source deltas: content hash, API fingerprint, compiler/context fingerprint, source dependencies, exported declaration identities and unresolved symbolic targets are detached and persisted. Body-only changes stay local; API changes fan through reverse dependencies; cycles terminate; unresolved targets are conservatively matched against changed exports; compiler/context fingerprint changes invalidate the module. Reconciliation publishes deletions, and persistent invalidation revisions are consumed on the analyzer executor. The complete annotation/visibility/generated-declaration matrix remains.
- [~] **13 — Production backend migration.** The default full `RocksIndexStore` now owns artifact/source/docs/workspace/hierarchy/code/JDK reads and writes with no SQLite database. SQLite remains an explicit comparison/rollback backend. Deterministic integration fixtures cover the new path; full corpus agreement, process-interruption migration and all platform gates remain required.
- [~] **14 — Packaging and operational limits.** All four Linux/macOS x64/arm64 package, AOT-training and relocated native-store checks pass on optimized GitHub commit `7b5e800d`; Windows installer validation passes too. Pinned licenses are bundled. WSL-specific filesystem measurements, final-revision CI and broader failure/memory limits remain open.
- [~] **15 — Performance/correctness acceptance gate.** The final full-provider 380,000-symbol three-run comparison reports SQLite/Rocks median seed 53.225/19.349 s (2.75x), writes 696.21/86.68 MB and query p95 60.96/32.64 ms. This generated fixture clears the write-reduction target but not the 3x seed target; real-corpus acceptance remains open. All unchanged restarts perform zero artifact rebuilds/global links. The corporate corpus is unavailable in this environment.
- [~] **16 — Documentation/handover.** `docs/index-redesign.md` documents architecture, settings, rollback, benchmark commands, raw results and unresolved work. Final handover requires the remaining checkpoints.

## Acceptance targets

- Full seed >= 3x faster than the reproduced SQLite baseline.
- >= 50% fewer process-attributed storage bytes written.
- Controlled query p95 within 10% of baseline.
- Stable heap/native/RSS under configured budgets.
- Unchanged scans: zero artifact parses, zero artifact-data rewrites, zero global linking passes.
- Exact semantic agreement on deterministic fixtures and preservation of the existing corpus correctness floor.

## Current architecture audit

The merged `main` path currently:

1. discovers Maven JARs;
2. parses binary artifacts in parallel;
3. serializes all writes through one `IndexDatabase` writer;
4. assigns global SQL symbol IDs during ingestion;
5. writes symbolic relationships into multiple SQL relationship tables;
6. runs a global `linkEdges()` pass after repository ingestion;
7. stores workspace membership separately in SQLite;
8. relies on SQL joins/FTS-style structures for queries.

That path remains the explicit SQLite comparison/rollback backend. The branch now defaults to the full Rocks store; prototype selection alone is not evidence of production performance acceptance.

## Checkpoint history

### Checkpoint 3 — full store boundary and production scan inventory
- All production read consumers (hierarchy, documentation, lazy references, workspace membership, pending-signature discovery) route through `IndexStore`.
- Binary, lazy-code, local-module, per-file source, source-documentation and JDK-enrichment writes route through `IndexStore`; `IndexService` contains no direct database reads/writes.
- Expanded fast gate passes Rocks storage/workspace tests plus SQLite contract, local module, source documentation, inherit-doc, hierarchy, signature closure and lazy-reference integration.
- Production repository scans drive the Rocks inventory lifecycle. A scan only reconciles deletions after every binary skeleton task succeeds.
- Regression coverage proves unchanged scans do not republish immutable generations, deleted artifacts leave the inventory on a complete scan, and a failed scan preserves prior inventory state.
- The remaining `IndexService.database()` escape hatch exists only for a handful of low-level tests and is not used by production code.

### Checkpoint 2 — backend selection and first production generation store
- Measurement branch: `benchmark/index-storage`.
- Candidate workload: 100,000 symbols + 200,000 unique relationships = 800,000 sorted facts.
- Immutable segment median publish: ~320 ms; final storage ~30.4 MB; median query p50/p95 ~0.117/0.376 ms.
- RocksDB SST median publish: ~327 ms; final storage ~5.22 MB; steady-state process writes ~10.4 MB; median query p50/p95 ~0.430/0.746 ms.
- Decision: RocksDB external-SST ingestion. Query latency remained sub-millisecond while stored bytes and write amplification were materially lower.
- Added isolated `jvmd-index-rocks` module so JNI/native packaging does not enter the existing jlink runtime before the packaging phase.
- First generation layout is cache-key-first, keeping every immutable artifact in one non-overlapping Rocks key range.
- Manifest and all artifact-local indexes are written to one SST and ingested together.

### Checkpoint 0 — branch and plan
- Branch: `indexing/immutable-artifacts`.
- Base: merged diagnostics `main`.
- Added this progress document before production behavior changes.
- Next: baseline/instrumentation, then backend contract.

## Guardrails

- Do not select RocksDB merely because it is the recommended candidate; compare it against the minimal immutable-file prototype first.
- Do not carry the old SQL table layout directly into key/value records.
- Do not move expensive global linking into first-query latency and call ingestion faster.
- Do not mark the real acceptance gate complete from generated fixtures alone.
- Public SCIP identities and API/query behavior must not depend on backend-local IDs.

### Checkpoint 4 — search correctness and cache backfill
- Fixed pagination within an artifact: cursor and kind filtering happen before the page limit, including substring false positives and name-prefix collisions.
- Added individual binary symbol records and SCIP-suffix postings. Workspace search and relationship materialization no longer decode an entire artifact per returned symbol.
- Fixed source overlays with nullable documentation/location values, and alias-specific cursor selection when identical content has multiple coordinates.
- Incremented the indexer identity to `jvmd-index-v2`; a reused SQLite artifact now backfills a missing immutable generation without replacing its SQLite symbols.
- A partially indexed workspace falls back as a whole rather than returning incomplete Rocks search results.
- Fixed batched alias removal reference counts. Candidate validation is retained for initial activation rather than decoding every artifact on every unchanged scan.
- Validation: all 31 Rocks repository, workspace, inventory, migration and invalidation tests pass on the pinned JDK. Added deterministic pagination/filter/null-overlay and multiple-alias deletion regressions.
- The backend remains a migration with SQLite writes. This checkpoint does not claim the final performance or corpus acceptance gate.

### Checkpoint 5 — bounded generation construction and incremental scans
- Replaced the all-postings in-memory SST sort with external sorted runs, a bounded merge fan-in and cleanup on failure. Symbols remain individually readable; the production SST no longer embeds a second whole-artifact payload.
- The `jvmd-index-v3` layout stores a checksum over every sorted key/value and verifies symbol, relationship and class-reference counts. Documentation overlays verify their member checksum/count as well.
- All five production Rocks databases share one strict block-cache/write-buffer budget (default 64 MiB). Status exposes cache use, pinned cache, native table-reader estimates, memtables, compactions and write stalls. Other native/JVM overhead still requires process RSS measurement.
- A weighted permit is acquired before JAR parsing and held through publication. Oversized artifacts run alone under the estimate; the estimate is not a hard process-memory cap. Sorted runs default to 4 MiB per active builder.
- Selected-class member resolution follows inheritance and does not obtain missing members from later duplicate classes.
- SQLite's migration control now records a transactional dirty-link marker. Unchanged scans and reopened unchanged indexes run zero global link passes; complete scans reconcile removed paths while preserving shared aliases.
- Shutdown waits for index/source workers before closing native handles; failed shutdown leaves handles open rather than freeing them under an active worker.
- Validation: 23 SQLite/Rocks index integration tests pass, including unchanged restart and JAR deletion. Rocks tests cover bounded spilling, corruption of a secondary posting, and failure before manifest publication. Full native platform and corporate-repository acceptance remain open.

### Checkpoint 6 — production semantic and Merkle update paths
- Analyzer source publication now selects the configured module by its source root, keeping main/test and distinct workspace directories separate.
- Known file changes update one Merkle leaf and its ancestor child lists without a filesystem walk. Full reconciliation uses linear child aggregation and still discovers external additions/deletions.
- Source deletion removes persisted semantic facts. API changes, unresolved lookup sites and their transitive dependants receive persistent invalidation revisions; body-only changes do not invalidate dependent files.
- Analyzer cache checks consume those revisions on the owning compiler executor. The asynchronous index writer never touches compiler instances.
- Unchanged semantic records are no longer rewritten when a different file is observed.
- Validation: 36 Rocks tests and 32 integration tests pass, including persistent revision replay, unresolved transitive invalidation, known-file/reconciliation fingerprint agreement, reverse-dependency invalidation and compiler isolation.

### Checkpoint 7 — reproducible production measurements and packaging checks
- Added a forked benchmark comparing complete SQLite and dual-write production paths, with three fresh seeds, three unchanged restarts per backend and a one-JAR replacement. Checked-in raw results and architecture/recovery documentation explicitly retain the failed performance targets.
- Added a relocated-runtime native smoke covering ServiceLoader, SST publication, query and reopen; bundled the pinned RocksDB Apache and LevelDB notices and reused the existing release matrix.
- Fixed lookup of opaque Rocks pagination IDs, including documentation overlays, so an authoritative search result can be materialized again by ID.
- CI exposed a race when diagnostics snapshots atomically renamed temporary files under a source root. Source/output inventory now tolerates concurrent disappearance while propagating other I/O errors; a concurrent-rename regression passes locally. Existing timing and semantic assertions are unchanged.
- Local validation: full reactor build; 36 Rocks tests; 14 forked benchmark workers; real native index publication/query/reopen with strict AOT. The daemon regression is blocked locally by Unix sockets returning `Operation not permitted`, so its original CI gate remains required. AOT archive creation succeeds, but local full training currently fails in the Maven 4 injector with duplicate `userProperties`; CI on the preceding revision trained successfully.
- Remaining implementation is explicit: full production Rocks `IndexStore`, removal of binary SQL ingestion, complete query/overlay/hierarchy agreement, production reader pins/fault injection, the real 861-JAR acceptance run and all platform/corpus gates. This checkpoint does not mark the redesign complete.

### Checkpoint 8 — replace production SQL with the full Rocks store
- Added `RocksIndexStore` and selected it by default. The normal path opens no SQLite database; the legacy store remains explicitly selectable for comparisons and rollback.
- Added durable path/context handles, per-file source replacement, independent documentation overlays, stable public numeric handles across lazy code enrichment, JDK signature closure, workspace-specific inheritance/overrides, alias-aware pagination and source precedence.
- Reused existing semantic fixture assertions against the new default and added focused full-store/reopen/no-SQL and canonical SQLite comparison tests. Legacy SQL-specific migration tests explicitly select their control store.
- The first independent 2,240-symbol comparison and the subsequent 40,000-symbol run are retained under `docs/performance/2026-09-19-rocks-initial-*.json`. They do not meet the acceptance targets: the latter's median seed is 15.498 s versus 6.536 s for SQLite, with 583.14 MB versus 74.47 MB process writes. Query p95 is 15.69 ms versus 19.23 ms. These experiments used uncommitted replacement code and are historical diagnostics, not final-revision acceptance evidence.
- The larger fixture exposed strict native-cache pressure. Partitioned metadata indexes and non-caching checksum scans fix that failure. Added compressed sort runs and bounded delta-encoded posting blocks to address measured sort write amplification; the indexer identity is now `jvmd-index-v5`.
- All 37 Rocks storage tests pass, including packed posting boundaries, corruption detection, reopen and spill cleanup. The expanded 42-test integration gate and full reactor build pass. Fresh-process performance validation is recorded in the next evidence checkpoint.
- Distribution smoke now requests strict AOT explicitly so fallback diagnostic text cannot corrupt its JSON output. Production shutdown preserves shared native handles when store shutdown fails.
- Acceptance remains open for the corporate 861-JAR run, complete body/API edit matrix, hard parsed-model memory bounds, production reclamation/fault injection, corpus floor and platform release results.

### Checkpoint 9 — compact sort namespaces and controlled benchmark location
- Removed the repeated 65-byte artifact namespace from each temporary sort row, restoring it only when writing the final SST. Posting semantics and the on-disk format are unchanged.
- Added a benchmark state-root option and retained worker output outside the measured directory. Cleanup assertions found deleted staging files being restored under this session's workspace path; the same worker under `/tmp` passed. No production cleanup assertion was weakened. The local overlay mount uses `fsync=volatile`, so these measurements are preliminary rather than the durable WSL acceptance run.
- The 37 Rocks storage tests pass after namespace compaction. The full 42-test integration gate passed on the preceding full-store checkpoint.

### Checkpoint 10 — preserve return-only overload identities and retain evidence
- The broader existing phase-3 fixture exposed return-only JVM overloads being collapsed under one SCIP identity. Detached records now mark collisions and `ArtifactContext` emits the existing return-type-disambiguated identity. Added direct lookup, pagination and SQLite-agreement assertions without changing existing expectations. Cache identity advances to `jvmd-index-v6` for predictable rebuilds.
- Retained the three-run isolated 40,000-symbol report with revision and compiled-class hashes. Rocks writes 47.7% less and query p95 is 28.8% lower, but seeding is 49.2% slower and peak RSS is higher. No redesign performance target is silently revised.
- All six restarts rebuild zero artifacts and run zero global linking. A replacement rebuilds exactly one artifact; Rocks performs no global linking. Staging is empty after each Rocks worker closes.
- All four native package/AOT/relocation jobs and the Windows installer check passed for GitHub `9bb689d9`: https://github.com/maxjay/jvmd/actions/runs/35406797784 . The general checkpoints workflow for that revision is queued behind an older corpus run; no final corpus-floor claim is made.
- Validation after the identity fix: all 37 Rocks storage tests and all 32 non-corpus/non-performance phase-3 tests pass. The fast integration list now includes binary skeleton, multi-release, schema and invalidation fixtures so return-only overloads are exercised there too.

### Checkpoint 11 — profile and remove seed-side posting expansion
- JFR identified temporary sort stream reads/writes as about one third of sampled CPU, while native SST puts were about 3%. Retained the sample summary and implementation hashes in `docs/performance/2026-09-19-seed-profile.json`.
- Added operation timers for record preparation, initial sort/spill, merge/SST construction, file sync, native ingestion and publication verification. A diagnostic 40,000-symbol run spent only 2.9 ms in native SST ingestion; preparing and sorting millions of expanded postings dominated.
- Temporary runs now retain bounded posting blocks; merge passes copy disjoint blocks intact and fall back to ordered ID expansion when runs interleave. Added an overlapping-run regression and shared posting validation.
- Substring grams now accumulate directly into bounded 256-ID blocks before sorting. The accumulator shares the existing 4 MiB builder budget with the sorter; tiny configured buffers use the original bounded fallback. Symbols are normalized by local ID before accumulation, with duplicates rejected.
- One diagnostic trial reduces sorter inputs from 2,910,854 to 290,993 records, represents 2,630,753 gram occurrences in 10,892 blocks, and cuts seed time to 4.96 s. This is a single optimization trial, not the three-run acceptance result. Raw comparable reruns follow.

### Checkpoint 12 — include production activation and remove oracle materialization
- The completed three-run store comparison after compact accumulation is retained as `2026-09-19-rocks-posting-40000.json`: Rocks/SQLite seed 4.556/6.631 s and writes 21.45/74.47 MB. These store-level harness runs did not exercise the provider's candidate activation; that omission is now explicit and corrected in the harness.
- Production candidate validation was reconstructing the whole artifact and opening an outgoing iterator for every symbol just to check the binary hash. Replaced that path with streaming typed-record validation and manifest identity checks. Activation reuses the checksum/schema proof for immutable SSTs published by the same owner; a reopened unvalidated candidate is scanned again. Checksum and schema validation are retained.
- Added counters and a regression proving owned-publication reuse, reopen validation and zero whole-artifact oracle materializations during activation. The benchmark now loads the actual ServiceLoader provider, includes activation, and retains hashes for the new helper classes.
- Validation before this checkpoint: 38 Rocks storage tests and 47 integration tests pass. Full-provider comparisons are recorded separately from prior store-only measurements.
- Added a compact type-definition posting list so workspace duplicate-class checks read classes directly instead of decoding every field and method. The on-disk identity advances to `jvmd-index-v7` so existing caches rebuild predictably.
- After production activation/type-posting changes: all 39 Rocks tests and the 47-test integration gate pass.

### Checkpoint 13 — large-fixture attribution and publication verification
- Retained full-provider three-run reports for 40,000 and 380,000 symbols at local commit `53a693c` (GitHub `36b08133`). The large fixture is one generated JAR with 950 classes, not the unavailable corporate repository: SQLite/Rocks median seed 55.077/26.113 s, writes 696.20/86.73 MB, peak RSS 985.8/861.1 MB and query p95 64.41/39.56 ms. All unchanged restarts perform zero rebuilds/global links. First-query ranges overlap; no first-query improvement is claimed.
- Large-run Rocks attribution: parsing 2.62 s, record preparation 5.35 s, initial sort/spill 2.75 s, merge/SST build 8.85 s, publication verification 4.44 s, native ingestion 0.011 s. This is preparation and verification overhead rather than slow native ingestion. The 25.9 million logical gram occurrences enter sorting as 103,293 blocks.
- Replaced synchronized per-field stream calls with fixed 64 KiB temporary-run buffers; compression, bounded merge fan-in, ordering and duplicate checks remain. Typed-record verification checks encoded fields without creating decoded strings/models. A diagnostic trial reduced large seed to 22.667 s; this is not the final three-run result.
- Fresh publication now checks each encoded symbol during construction, retains the SHA-256 over every sorted row in the manifest, then verifies all native SST block checksums and the final entry count before atomic ingestion. This removes the post-ingest JNI round trip over all rows and prevents a corrupt staged file from becoming visible. Native block checksums are distinct from the application SHA-256; reopened unvalidated candidates still receive the full application checksum/schema scan.
- Reuse only the current class-prefix gram set, preserving boundary-crossing grams and Unicode case normalization. This avoids rebuilding identical prefix substrings for every field/method without retaining an unbounded artifact cache.
- Added wrong-count, corrupted-block, invalid-schema/edge and substring-boundary regressions. Validation: all 42 Rocks storage tests, all 48 fast integration tests and the full reactor build pass. No timing or corpus gate was relaxed. Final measurements of this implementation follow separately.

### Checkpoint 14 — final controlled seed measurements and CI evidence
- Final measured code: local `cf1728e`, GitHub `7b5e800d`, identical tree `91d2dac82dd6b62b09804a22fb621601f5e01e3d`. All 28 fresh/restart/replacement worker results across both sizes record that local revision and the same implementation hashes. Raw reports: `docs/performance/2026-09-19-rocks-final-{40000,380000}.json`.
- Three-run 380,000-symbol medians (SQLite / Rocks): seed 53.225 / 19.349 s (2.75x), elapsed through queries/close 54.547 / 20.205 s (2.70x), process writes 696.21 / 86.68 MB (87.5% less), query p95 60.96 / 32.64 ms, final index 371.92 / 37.71 MB, peak RSS 960.9 / 899.2 MB. First-query ranges overlap. One-JAR replacement 78.65 / 19.41 s, each rebuilding one artifact.
- Three-run 40,000-symbol medians: seed 6.474 / 4.154 s (1.56x), writes 74.47 / 21.75 MB (70.8% less), p95 19.49 / 16.78 ms. Rocks peak RSS is higher on this smaller fixture (369.1 versus 266.6 MB); the large result is not a universal memory improvement.
- All 12 unchanged restart workers rebuild zero artifacts and perform zero global link passes. All workers report zero faults; the Rocks workers open no SQLite database and leave no staged sort files after close.
- Large Rocks median phase times: prepare 4.648 s, initial sort/spill 2.345 s, merge/SST 7.311 s, native file verification 34.8 ms, fsync 1.4 ms and ingestion 2.8 ms. Record preparation and merge work remain dominant. The generated fixture remains below the 3x seed target; do not mark phase 15 complete.
- Measurements use pinned JDK 25, separate 1 GiB JVMs, eight available CPUs and /tmp overlayfs with volatile fsync. The reports retain machine/cgroup details. Native WSL durability, the unavailable real 861-JAR repository, body/API performance and queries during ingestion still require acceptance measurements.
- Optimized code passed all four native Linux/macOS package/AOT/relocation jobs and the Windows installer check in run `35410294089`. Earlier revision `aafafc53` passed the complete checkpoint/corpus run `35403345660`, including identifier sweep 106,407/106,515 (99.8986%). The optimized code's own checkpoint/corpus run remains pending. Links and exact revision scopes are retained in `docs/performance/2026-09-19-ci-evidence.json`.

### Checkpoint 15 — actual-main comparison and admission deadlock regression
- Added standalone benchmarks that compile unmodified core/index sources from actual main `e651e9f8` and the Rocks branch, rather than treating the new branch's SQLite mode as old main. Separate three-run fixtures exercise a large single JAR and 128 JARs with cross-artifact references and SNAPSHOT policy.
- Maven scans do not call the workspace Merkle state; the benchmark records zero workspace-state updates. Source Merkle updates are measured separately against full reconciliation, checking identical roots and bounded ancestor writes. Do not attribute Maven scan gains to source Merkle roots.
- Added a checksum-pinned JDTLS 1.61.0 comparison for dependency-type readiness, repeated queries and restart, with explicit API/LSP and JDK/project-startup scope differences. Smoke tests verify complete fixture type counts.
- CI audit found the complete-repository checkpoint stalled while other corpus/native jobs passed. Reproduced a deadlock in admission: an owner holding the artifact budget called `acquire(0)` on a fair semaphore and queued behind a worker needing that owner's capacity. Skip acquisition when the artifact already owns its permit; retain weighted admission for other callers and expose queue length in status.
- The deterministic queued-owner regression fails with `TimeoutException` when the guard is removed, and all 43 Rocks storage tests pass with it. The fixed code and scan-specific write attribution are being measured in a fresh final run; earlier diagnostic runs are not substituted for that result.

### Checkpoint 16 — actual-main, Maven-update, Merkle and final JDTLS evidence
- Completed 24 isolated JVMD workers against actual main `e651e9f8` and fixed Rocks code `cd4e703a` (local `a89e0f2`, identical tree `ec6c7140`). Three-run open-and-scan medians: one JAR 33.481/9.722 s; 128 JARs 25.586/3.236 s (main/Rocks). Both generated samples exceed 3x, without establishing the corporate-repository target. This AMD machine differs from the prior Intel measurements.
- For 128 JARs, warm unchanged scans take 787.76/28.36 ms, release replacement 1,324.43/214.16 ms, addition 1,015.12/106.98 ms and SNAPSHOT replacement 1,135.84/79.49 ms. All replacement/restart query checks pass. Main retains a deleted artifact in global search/path registration; Rocks removes it. Delete timing is not presented as equivalent correctness.
- Maven work records zero source-Merkle updates. Separately, 120 paired source edits produce identical incremental/reconciled roots. For 10,000 files, known-file updates including hashing take 0.543 ms versus 108.744 ms for reconciliation, about 200x; each writes one leaf, six ancestor/child records and one module record. Classpath-only updates write only the module record.
- Completed 12 JDTLS 1.61.0 JVMs on byte-identical fixtures. One-JAR dependency-type readiness is main/Rocks/JDTLS 38.891/11.932/8.324 s; 128-JAR readiness is 30.700/5.467/7.992 s, and restart 6.172/2.780/3.493 s. JDTLS wins single-JAR readiness; main wins the 128-result repeated type-query p95. The report explicitly distinguishes direct JVMD APIs from JDTLS LSP/project/JDK startup and additional JVMD field/method/relationship indexing.
- Retained scan/query write attribution and memory regressions: Rocks fresh peak RSS is 947.6 MB versus main 882.9 MB for one JAR, and 777.7 MB versus 340.4 MB for 128 JARs. Lower scan writes/final index sizes do not establish lower process memory or uniformly lower unchanged-scan writes.
- Fixed production code passes the complete checkpoint job in run `35442230037` and all four native package/AOT/relocation jobs plus Windows installer in `35442229949`. Optimized predecessor `5325806e` passed corpus in `35410771797`; the admission fix's serialized corpus rerun remains pending. No correctness/timing gate was weakened.
- Results, reproducible commands, raw measurements, revision/class/fixture hashes and CI scope are in `docs/performance/2026-09-19-final-comparison.md`. The PR is ready for review at the user's request. Corporate 861-JAR, WSL durability, end-to-end body/API and concurrent-query performance, memory bounds and broader reclamation/fault injection remain acceptance work.

### Checkpoint 17 — type-query planning and posting allocation
- A first type search decoded 380,000 records to return 950 classes. The production planner now compares the gram posting count against the artifact's type count without loading symbol records, then chooses the smaller candidate set. Blank type searches use the type index directly; rare searches retain selective gram postings. Kind rejection precedes SCIP construction and alias checks. No persisted format changes.
- Posting decoding now sizes temporary ID arrays to the encoded byte upper bound and returns a filled array without copying. The old decoder allocated 256 integers even for singleton blocks; one diagnostic JFR attributed about 1.62 GB of transient merge allocations to it. This is sampled allocation churn, not retained heap or a measured speedup.
- All 73 selected storage and integration tests pass with the repository's compiler export flags, including expanded SQLite agreement for kind filters, common/rare/empty substring queries and every result page. New posting tests retain 256-ID boundaries and malformed/overflow/truncation rejection. The first direct launcher omitted the Maven compiler exports; its four access errors disappear with the normal flags.
- Verified the exact JDTLS 1.61.0/JDT Core source revisions from distribution manifests: JDT also indexes binary methods, fields and constant-pool references, and its type-search handler waits for indexing readiness. The earlier contrast suggesting only JVMD indexes those facts was too strong; startup/query scopes differ, but both benefit from upfront indexing.
- Added a serial before/after harness separating fresh process readiness, persisted-state restart and warm queries. Parent-process readiness includes JVM launch on both sides of the JDTLS comparison; original JVMD service timings excluded that launch. Final controlled measurements of the committed implementation follow.
