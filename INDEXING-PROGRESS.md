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
- [~] **03 — `IndexStore` backend contract and correctness oracle.** Read-side contract plus binary/code publication are behind the store boundary; SQLite remains the correctness oracle while remaining write/read paths migrate.
- [~] **04 — Artifact identity and compact binary record format.** Versioned GAV-independent detached facts with artifact-local ids are implemented; source-doc and production compact-record refinements remain.
- [x] **05 — RocksDB external-SST vs minimal immutable-file prototype comparison.** Measurement branch `benchmark/index-storage` selected RocksDB SST ingestion: comparable median publish time, ~83% lower stored bytes and ~66% lower steady-state write bytes in the 800k-fact CI comparison.
- [~] **06 — Bounded parallel artifact ingestion.** Repository scanning now supports an injected artifact-generation sink. The Rocks sink builds distinct SSTs concurrently using caller scan workers, serializes only ingestion, and enforces an estimated-byte semaphore budget. Production runtime wiring and real-repository tuning remain.
- [~] **07 — Atomic artifact generations.** One content-addressed artifact is built as one external SST containing manifest + indexes and ingested as a single publication. Reopen persistence, concurrent same-key idempotence, orphan staging cleanup, and manifest/payload verification are covered; generation reclamation and broader failure injection remain.
- [ ] **08 — Workspace-specific symbolic relationship resolution.**
- [ ] **09 — Required search behavior.**
- [ ] **10 — Incremental artifact discovery/reuse.**
- [ ] **11 — Per-file workspace state and Merkle fingerprints.**
- [ ] **12 — Narrow semantic invalidation.** The merged diagnostics redesign contains reusable `FileStateRegistry` / API-fingerprint ideas, but this indexing checkpoint is not complete until index/workspace state uses them correctly.
- [ ] **13 — Production backend migration.**
- [ ] **14 — Packaging and operational limits.**
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
