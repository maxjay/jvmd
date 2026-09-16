# JVMD Diagnostics Redesign Progress

Implementation branch: `diagnostics/incremental-state`  
Base: `main` at `5cbf5bb13051d76f52765018bb3881025f313d18`

## Acceptance invariant

Repeated unchanged `diag.get` must prove:

- `javac_queries = 0`
- `files_reanalysed = 0`
- no unnecessary synchronous source-index writes
- diagnostics remain correct
- pagination over an unchanged cached snapshot does not trigger semantic analysis

## Original benchmark / baseline

The architecture document records the pre-redesign observed benchmark as:

- fresh workspace diagnostics: approximately **26.8 s**
- repeated workspace diagnostics: approximately **8.45 s**

These remain the only directly comparable pre-change numbers available to this implementation branch. The connector environment cannot execute the repository locally, so branch measurements are collected by the repository's GitHub Actions AOT test harness. `IncrementalDiagnosticsStoreTest` now writes `jvmd-tests/target/diagnostics-perf.json` containing cold/warm timings and architectural counters for a deterministic 40-source fixture.

## Current phase

**Verification of phases 1–4 and 6; phase 5 (index publication decoupling) and cold batching remain open.**

The highest-value warm-path architecture is implemented and under CI verification. The work intentionally remains a draft PR until the new regression tests and the existing checkpoint/corpus suites are green.

## Phase status

| Phase | Status | Notes |
|---|---|---|
| 1. Instrument current path | implemented, verification pending | CompilerPool javac/config/classpath timings and counts; Analyzer diagnostic reuse/analysis/index-write counters; Maven resolution counters; existing RPC Metrics provide request duration |
| 2. Prepare request context once | partial | Maven workspace resolution is memoized once per RPC; source enumeration was already once/request; `Application.analyzer(...)` still reconstructs some per-file module data and classpath-stamp work remains reducible |
| 3. Preserve analyzer/compiler state per module context | implemented, verification pending | compiler pools are retained by context generation; A→B→A regression added |
| 4. Detached per-file diagnostic state | implemented, verification pending | dedicated `DiagnosticStore`; unchanged repeated workspace request test asserts zero added javac, zero files analysed, zero index writes; real pagination exercised |
| 5. Decouple index publication | not complete | synchronous `recordSource()` remains on changed/full semantic computations; unchanged cached `diag.get` bypasses it entirely |
| 6. API/declaration fingerprints | implemented, verification pending | detached API fingerprint, conditional reverse-dependency invalidation for authoritative editor changes, body-only/signature regression added |
| 7. Cold module-batched javac | not started | no shared-javac parallelism introduced |
| 8. Persistent diagnostic snapshots/further optimization | not started | intentionally deferred until warm path is proven |

## Checkpoints and commits

### Checkpoint 0 — Branch and implementation log

Commit: `dc22e7e4568d3c8f3aa4984348c8a67827b9d473`

Files/classes changed:

- `DIAGNOSTICS-PROGRESS.md`

Result:

- dedicated branch created;
- historical benchmark and acceptance invariant recorded.

### Checkpoint 1 — Diagnostic/compiler instrumentation

Commits:

- `726f47451224f82be8002b83202a8c9fa95896f1` — CompilerPool timing/counters
- `55ce8d3b0cc3bed1d597b4fa5cf1a0125ac43b25` — lifecycle counter assertions

Files/classes changed:

- `jvmd-analyzer/.../CompilerPool.java`
- `jvmd-tests/.../CompilerPoolLifecycleTest.java`

Instrumentation exposed:

- javac query count and cumulative duration;
- compiler configure count and duration;
- classpath validation count and duration;
- compiler recycle/fault/heap information (existing plus retained);
- later Analyzer checkpoints add files analysed/reused, cache hits/misses and `recordSource` count/duration;
- Maven request-level checkpoint adds actual resolve calls/cache hits.

Tests:

- `CompilerPoolLifecycleTest` asserts query/configuration/validation counters while retaining thread-confinement and recycle behavior.

Known gap:

- no separate `diag.get` sub-timer has been added to `Application`; total RPC duration is already captured by the existing dispatcher `Metrics` layer.

### Checkpoint 2 — Request-scoped Maven/workspace preparation

Commits:

- `cb4c091c01098a43e436dd5d681bf1f78fdc4b7a` — `RequestScope`
- `d99def5b3278ccc0b1be89454b2014601887cb2b` — scope handlers/in-process queries to one RPC generation
- `64d546e83ab07c146527b3f2f5aa8a52f983fab6` — request-local Maven workspace-resolution memoization
- `778592d058095dc54bc5f4b3c2b0bc263526c661` — Maven-backed diagnostic request test

Files/classes changed:

- `jvmd-core/.../RequestScope.java`
- `jvmd-core/.../Dispatcher.java`
- `jvmd-resolver/.../MavenResolver.java`
- `jvmd-tests/.../DiagnosticRequestContextTest.java`

Required counter behavior in test:

- actual Maven `resolveWorkspace` calls per multi-file `diag.get`: **1**;
- remaining per-file refresh calls are served by the request snapshot.

Known gap:

- `Application.analyzer(...)` still rebuilds some Java collections/module context data for every source. This is lower cost than Maven resolution but phase 2 is therefore intentionally marked partial.

### Checkpoint 3 — Module/context compiler-state retention

Commits:

- `fcacbdcbd2ea92c6968d3720a5767550adb0aa4b` — retain one `CompilerPool` per analyzer context generation
- `0616b1f03e92544936f399e9abb610d132687c7c` — A→B→A regression

Files/classes changed:

- `jvmd-analyzer/.../Analyzer.java`
- `jvmd-tests/.../ModuleAnalyzerContextReuseTest.java`

Test invariant:

- query A, then B, then A again;
- A's compiler pool is retained and reports a reused javac context rather than being destroyed by the B traversal;
- no compiler operations are parallelized.

Correctness choice:

- source invalidations still recycle affected compiler working state conservatively; retaining pools addresses unrelated context traversal, not stale semantic reuse.

### Checkpoint 4 — Detached `DiagnosticStore` / zero-javac warm path

Commits:

- `2209ffdb46ad5090e7b94e7efd24791d23b8a1a8` — initial detached store
- `0e7239635727fba1b046fe0eb6b5d8b0b1590b70` — Analyzer diagnostic reuse/counters
- `189baec5bc22ae94f347efd3afa361c1898e32a2` — initial unchanged-workspace architectural test
- `96cbf0d6d4551035ca05f6f44d09266b104e0709` — store metadata API used by later invalidation work
- `68b79e235286e627cc0cb67c476d5fddf6fd98c8` / `ad2c3fa6f13a1783e6e50c600324417350a62876` — benchmark output and non-vacuous 40-diagnostic pagination test

Files/classes changed:

- `jvmd-analyzer/.../DiagnosticStore.java`
- `jvmd-analyzer/.../Analyzer.java`
- `jvmd-tests/.../IncrementalDiagnosticsStoreTest.java`

Store identity currently includes:

- normalized source path;
- authoritative source-content hash;
- analyzer/module context generation;
- classpath fingerprint.

Test fixture:

- 40 sources (deliberately larger than the existing 32-entry focused LRU);
- 40 real diagnostics so page 2 exists;
- first request populates detached snapshots;
- second unchanged request must return identical diagnostics;
- subsequent pagination must not trigger semantic work.

Required measured deltas:

- repeated additional javac queries: **0**;
- repeated files reanalysed: **0**;
- repeated synchronous `recordSource` calls: **0**;
- pagination additional javac queries: **0**;
- pagination files reanalysed: **0**;
- warm fixture budget: **< 500 ms**.

Actual milliseconds: **pending latest CI artifact/log**.

### Checkpoint 5 — API fingerprint / conditional invalidation

Commits:

- `77ee3de6e0e75dda71ae9ff1c27c7cbde19b51aa` — detached declaration/API fingerprint
- `356824fb482e90af3c7096773beff9e42c20e9dc` — conditional reverse-dependency invalidation
- `25df379d43f4926f8fe15966b2033e380e4c916d` / `190f3a6706d087bf41f77c35b370258ca4e1b226` — acknowledge authoritative editor hashes so the old observer does not rediscover and broaden an explicit edit
- `772430420f2dae41f95e69b07fa6b2b95dd4e5ed` — body-only vs signature-change regression

Files/classes changed:

- `jvmd-analyzer/.../ApiFingerprint.java`
- `jvmd-analyzer/.../Analyzer.java`
- `jvmd-analyzer/.../Dependencies.java`
- `jvmd-tests/.../ApiFingerprintInvalidationTest.java`

Fingerprint intentionally excludes:

- method bodies;
- source positions/ranges;
- docs/comments;
- local variables and parameters as independently exposed declarations.

It retains detached semantic declaration identity/signature/modifiers/type parameters/erased descriptor/declaring type/GAV information.

Body-only test target:

- changed file analysed: yes (exactly one additional query);
- API fingerprint changed: no;
- dependant analysed: no;
- cached dependant diagnostics remain reusable.

Signature test target:

- changed file analysed: yes;
- API fingerprint changed: yes;
- relevant dependant invalidated and analysed when requested;
- resulting type error is observed.

Correctness notes:

- unsaved document content remains authoritative through `Documents.hash`;
- preserved-mtime disk edits continue through the existing content-hash dependency checks;
- externally detected disk changes remain conservative rather than incorrectly applying body-only optimization without first establishing a new API fingerprint;
- unresolved diagnostic snapshots are conservatively reconsidered after a confirmed API change.

## Tests added or extended

- `CompilerPoolLifecycleTest`
- `DiagnosticRequestContextTest`
- `ModuleAnalyzerContextReuseTest`
- `IncrementalDiagnosticsStoreTest`
- `ApiFingerprintInvalidationTest`

Existing tests specifically relied on during implementation:

- `ReverseDependencyInvalidationTest`, including preserved-mtime source changes;
- existing phase/corpus checkpoint suites through GitHub Actions.

## Performance / counters

Historical pre-redesign workspace:

- fresh: ~26.8 s
- repeated: ~8.45 s

New deterministic 40-file AOT fixture records:

- cold request ms;
- repeated unchanged request ms;
- cold javac query count;
- repeated additional javac queries;
- repeated files reanalysed;
- repeated synchronous index writes.

Expected architectural result after the first request:

```text
repeated_additional_javac_queries = 0
repeated_files_reanalysed = 0
repeated_index_writes = 0
```

Latest numeric measurement is pending the CI run containing `ad2c3fa6f13a1783e6e50c600324417350a62876`.

## Cache observability

`Analyzer.status()` now exposes:

- focused binding cache entries/hits;
- detached diagnostic-store entries/hits/misses/puts/invalidations;
- diagnostic files analysed/reused;
- API fingerprint changes/unchanged counts;
- pending API and conditional-file counts;
- synchronous index publication calls/duration;
- per-context compiler-pool status.

`CompilerPool.status()` exposes javac queries/query time, configure calls/time and classpath-validation calls/time.

`MavenResolver.status()` exposes actual resolution calls and request-cache hits.

## Known issues / regressions / remaining work

- CI verification for the latest checkpoint is still required before marking phases complete.
- Phase 2 still reconstructs some module/context Java data in `Application.analyzer(...)` per file and still computes classpath stamps more often than the target architecture ultimately requires.
- Phase 5 has **not** yet moved changed-source `IndexService.recordSource()` work off the diagnostic response critical path. The warm unchanged path already performs zero writes, but changed-source publication is still synchronous.
- Cold diagnostics still use one javac task/query per unknown file; module batching is not implemented.
- Persistent daemon-restart diagnostic snapshots are not implemented.
- API-conditional invalidation is applied to explicit authoritative editor changes. Disk changes discovered lazily remain conservative to preserve invalidation correctness.
- The original ~26.8 s / ~8.45 s workspace fixture is not present as a reproducible checked-in fixture, so the 40-file CI benchmark is an architectural regression fixture rather than an exact apples-to-apples reproduction of those historical timings.

## Next checkpoint

1. run/inspect latest CI and repair any new regression rather than bypassing it;
2. capture `diagnostics-perf.json` / test logs and record the actual warm measurement;
3. finish or safely bound phase 5 source-index publication decoupling;
4. if the warm-path suite is green, proceed to cold module-batched javac analysis rather than parallelizing shared compiler state.

## Repository/design discrepancies and decisions

The repository largely matched the architecture document's diagnosis. The implementation deliberately reused the actual repository abstractions rather than creating a second semantic stack:

- `Dependencies` remains the reverse dependency graph;
- the existing focused 32-entry cache remains for interactive compiler results;
- `DiagnosticStore` is separate and per-file;
- `CompilerPool` thread ownership remains unchanged;
- request-level Maven reuse was implemented below `Application.analyzer(...)` using an RPC scope, allowing existing callers and semantics to remain intact while preventing repeated resolver work;
- `WorkspaceBindings` remains available for operations that need a coherent workspace binding graph.
