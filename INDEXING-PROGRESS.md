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
- [~] **04 — Artifact identity and compact binary record format.** Versioned GAV-independent detached facts with artifact-local ids are implemented; source-doc and production compact-record refinements remain.
- [x] **05 — RocksDB external-SST vs minimal immutable-file prototype comparison.** Measurement branch `benchmark/index-storage` selected RocksDB SST ingestion: comparable median publish time, ~83% lower stored bytes and ~66% lower steady-state write bytes in the 800k-fact CI comparison.
- [~] **06 — Bounded parallel artifact ingestion.** Repository scanning now supports an injected artifact-generation sink. The Rocks sink builds distinct SSTs concurrently using caller scan workers, serializes only ingestion, and enforces an estimated-byte semaphore budget. Production runtime wiring and real-repository tuning remain.
- [~] **07 — Atomic artifact generations.** One content-addressed artifact is built as one external SST containing manifest + indexes and ingested as a single publication. Reopen persistence, concurrent same-key idempotence, orphan staging cleanup, manifest/payload verification, and full-inventory candidate verification before activation are covered; reader-pinned generation reclamation and broader failure injection remain.
- [~] **08 — Workspace-specific symbolic relationship resolution.** Rocks now resolves artifact-local symbolic targets against an ordered workspace identity (classpath order + scope + module + source-overlay fingerprint + compiler fingerprint) and persists that cache by the complete workspace hash. Global cross-version linking is not used on this path; production API integration remains.
- [~] **09 — Required search behavior.** The Rocks artifact layout now has explicit binary, name, name-path and 1–3 gram substring indexes. Workspace search returns context-derived SCIP identities in classpath order; production API/pagination equivalence and scale measurements remain.
- [~] **10 — Incremental artifact discovery/reuse.** The persistent Rocks inventory is now integrated with production repository scans: unchanged JARs are observed without parse/rewrite, changed/new JARs publish then observe, successful scans reconcile deletions, and failed skeleton scans deliberately skip deletion reconciliation. SNAPSHOT rehash behavior is preserved. Reader-pinned generation reclamation remains.
- [~] **11 — Per-file workspace state and Merkle fingerprints.** Persistent Rocks workspace state stores per-file content identities plus deterministic bottom-up directory/module Merkle fingerprints. Unsaved overlays replace disk content for the leaf hash; compiler options, processors, generated-output fingerprints, ordered classpath and JDK fingerprint participate in module identity. Unchanged updates issue zero state writes; production source-index integration remains.
- [~] **12 — Narrow semantic invalidation.** Persistent Rocks semantic state consumes the analyzer's detached content/API fingerprints and dependency edges: body-only changes stay local; API changes fan through reverse dependencies; cycles terminate; unresolved targets are conservatively matched against changed exports; compiler/context fingerprint changes invalidate the module. Wiring analyzer-produced fingerprints into the production Rocks workspace remains.
- [~] **13 — Production backend migration.** Rocks generation is wired into the production composition root through ServiceLoader with an immediate `jvmd.index.generation.backend=none` rollback. Candidate/active/previous manifests are atomic and candidates are inventory-verified before activation. First-page dependency searches now run Rocks in shadow mode against the same ordered workspace and compare stable SCIP identities with SQLite; SQLite remains authoritative until the shadow mismatch gate is clean and pagination parity is complete. Hierarchy relationships now shadow both outgoing and reverse/incoming resolution, including lazy code-generation overlays.
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
