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
- [~] **14 — Packaging and operational limits.** RocksJNI is pinned and included through `jvmd-index-rocks`; production startup exercises provider loading with a bounded generation-memory budget. Pinned upstream licenses are included in release archives. The existing distribution smoke now publishes and reopens a real Rocks index after relocation to a path with spaces. Linux native/AOT loading passes locally; full AOT training and the platform/WSL CI matrix remain gates.
- [~] **15 — Performance/correctness acceptance gate.** The retained three-run generated-fixture report fails seed/write/query targets for the dual-write migration: median seed 2.212 s versus SQLite 1.424 s, writes 25.24 MB versus 8.85 MB, p95 5.36 ms versus 2.59 ms. All six unchanged restarts perform zero artifact rebuilds/global link passes. This is not the corporate acceptance corpus.
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

That path remains the production control while Rocks is integrated and compared. The prototype selection alone does not justify removing SQLite.

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
