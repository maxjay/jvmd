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

- [~] **01 — Reproducible baseline.** Branch/base captured. Existing SQLite remains the control. Implementing checked-in environment/phase/storage/query measurement harness next. The real 861-JAR corporate repository remains a local benchmark.
- [ ] **02 — Operation-level measurements.** Discovery, hash, parse, preparation/dedupe, queue wait, storage, publication and resolution timings.
- [~] **03 — `IndexStore` backend contract and correctness oracle.** All production reads and writes now cross `IndexStore`; SQLite implements the full semantic contract and the expanded fast gate covers search, workspace isolation, local modules, source docs, inheritance, hierarchy and lazy references. Raw database access remains only as a temporary low-level test seam pending the full-suite gate.
- [~] **04 — Artifact identity and compact binary record format.** Versioned GAV-independent detached facts with artifact-local ids are implemented. Source-JAR documentation/position data is now a separately content-addressed immutable overlay keyed by binary generation + source hash, so docs can change independently of reusable binary facts; final compact-record tuning remains.
- [x] **05 — RocksDB external-SST vs minimal immutable-file prototype comparison.** Measurement branch `benchmark/index-storage` selected RocksDB SST ingestion: comparable median publish time, ~83% lower stored bytes and ~66% lower steady-state write bytes in the 800k-fact CI comparison.
- [~] **06 — Bounded parallel artifact ingestion.** Repository scanning now supports an injected artifact-generation sink. The Rocks sink builds distinct SSTs concurrently using caller scan workers, serializes only ingestion, and enforces an estimated-byte semaphore budget. Production runtime wiring and real-repository tuning remain.
- [~] **07 — Atomic artifact generations.** One content-addressed artifact is built as one external SST containing manifest + indexes and ingested as a single publication. Reopen persistence, concurrent same-key idempotence, orphan staging cleanup, manifest/payload verification, and full-inventory candidate verification before activation, active/previous rollback retention, reader pins, and pruning of older unpinned generations are covered; broader failure injection remains.
- [~] **08 — Workspace-specific symbolic relationship resolution.** Rocks now resolves artifact-local symbolic targets against an ordered workspace identity (classpath order + scope + module + source-overlay fingerprint + compiler fingerprint) and persists that cache by the complete workspace hash. Global cross-version linking is not used on this path; production API integration remains.
- [~] **09 — Required search behavior.** The Rocks artifact layout now has explicit binary, name, name-path and 1–3 gram substring indexes. Workspace search returns context-derived SCIP identities in classpath order; production API/pagination equivalence and scale measurements remain.
- [~] **10 — Incremental artifact discovery/reuse.** The persistent Rocks inventory is now integrated with production repository scans: unchanged JARs are observed without parse/rewrite, changed/new JARs publish then observe, successful scans reconcile deletions, and failed skeleton scans deliberately skip deletion reconciliation. SNAPSHOT rehash behavior is preserved. Reader-pinned generation reclamation remains.
- [~] **11 — Per-file workspace state and Merkle fingerprints.** Persistent Rocks workspace state is now configured from the production module bind path for separate main/test contexts. Per-file content identities feed deterministic bottom-up directory/module Merkle fingerprints; unsaved editor overlays replace disk leaf hashes; compiler options, processor path/names/Lombok mode, generated-output fingerprints, ordered classpath and JDK/release identity participate. Unchanged binds issue zero state writes. Incremental create/delete notification outside module rebind remains.
- [~] **12 — Narrow semantic invalidation.** Persistent Rocks semantic state is now fed directly from production analyzer source deltas: content hash, API fingerprint, compiler/context fingerprint, source dependencies, exported declaration identities and unresolved symbolic targets are detached and persisted. Body-only changes stay local; API changes fan through reverse dependencies; cycles terminate; unresolved targets are conservatively matched against changed exports; compiler/context fingerprint changes invalidate the module. Deletion publication and feeding the resulting reanalysis set back into scheduling remain.
- [~] **13 — Production backend migration.** Rocks generation is wired into the production composition root through ServiceLoader with an immediate `jvmd.index.generation.backend=none` rollback. Candidate/active/previous manifests are atomic and candidates are inventory-verified before activation. Candidate activation now occurs only after both binary and source-documentation scan phases succeed. First-page dependency searches now run Rocks in shadow mode against the same ordered workspace and compare stable SCIP identities with SQLite; SQLite remains authoritative until the shadow mismatch gate is clean. Rocks now has a stable pagination cursor composed from ordered classpath position + artifact-local symbol id, and SQLite page boundaries are translated by SCIP during shadow comparison so page 2+ is validated as well. Runtime read selection is now explicit: `jvmd.index.read.backend=shadow` (default), `sqlite` (instant rollback), or `rocksdb-sst` (authoritative Rocks workspace search with SQLite fallback when unavailable). Hierarchy relationships now shadow both outgoing and reverse/incoming resolution, including lazy code-generation overlays.
- [~] **14 — Packaging and operational limits.** RocksJNI is pinned and included through `jvmd-index-rocks`; production startup exercises provider loading with a bounded generation-memory budget. Distribution/AOT/native-loading CI and license/operational documentation still need the final gate.
- [ ] **15 — Performance/correctness acceptance gate.**
- [ ] **16 — Documentation/handover.**

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

That path remains the control backend until the storage prototype checkpoint selects a replacement.

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
