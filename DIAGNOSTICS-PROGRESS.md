# JVMD Diagnostics Redesign Progress

Implementation branch: `diagnostics/incremental-state`  
Base: `main` at `5cbf5bb13051d76f52765018bb3881025f313d18`  
Architecture basis: `jvmd-incremental-diagnostics-architecture.md`.

Every current-state claim below is `[x]` when a named regression or CI run proves it, `[~]` when a limitation remains, and `[ ]` when deliberately deferred.

## Phases

- [x] **Phase 0 — Instrumentation (§60).** Javac/configure/classpath timings and counters, diagnostic reuse/analysis/index-publication counters, resolver counters, per-phase coordinator timings and invalidation-reason counts are exposed.
- [x] **Phase 1 — Request-level redundant work (§61).** `RequestScope` and `WorkspaceContextManager` reuse request-stable Maven/module preparation; classpath validation and context construction are memoized at the compiler-context level.
- [x] **Phase 2 — Retained module analyzer state (§62).** Compiler pools and focused/outline caches survive A→B→A module traversal; invalidation recycles only contexts that analysed affected files.
- [x] **Phase 3 — DiagnosticStore / zero-javac warm path (§63).** Detached per-file diagnostic state is keyed by source, context and classpath identity. Unchanged requests and pagination reuse it without javac or file reanalysis.
- [x] **Phase 4 — Asynchronous source-index publication (§64).** `SourceIndexPublisher` is bounded and coalescing; diagnostic responses never wait for the writer, duplicate in-flight semantic publications are suppressed, and publication failure does not discard diagnostic state.
- [x] **Phase 5 — API fingerprint invalidation (§65).** Body-only edits retain dependants; API changes conditionally invalidate reverse dependants. Coverage includes transitive changes, constants, annotations, generics, inheritance, permits/records, deletion, newly resolved names and namespace moves.
- [x] **Phase 6 — Cold module batching (§66).** `CompilerPool.batchQuery` performs one javac task for a batch; chunks are bounded to 128 files / 4 MiB. The 100-file regression uses one cold javac query and zero warm queries.
- [x] **Phase 7 — Adaptive cold/warm selection (§67).** The coordinator batches when at least 16 files and 25% of a module are stale; smaller changes take the incremental path. Superseded work is rejected at source-generation boundaries.
- [x] **Phase 8 — Persistent diagnostic cache (§68).** Checksummed immutable snapshot objects and atomic manifests restore unchanged diagnostics across daemon restart. Corrupt or stale state is a cache miss; unsaved dependency state is authoritative.
- [x] **Phase 9 — High-fidelity Lombok diagnostics (§69).** Saved Lombok sources can use isolated external javac diagnostics and are checked against a real build. Generated-member body/source-position navigation remains unavailable and is explicitly warned.
- [~] **Phase 10 — Parallel module actors (§70).** Implementation complete; final CI is pending. Each module actor owns a platform thread, Analyzer and CompilerPool. Cold module groups run with a bounded cap of `min(configured, 4)` (production default `min(CPUs/2, 4)`), while detached API fingerprints preserve cross-module conditional invalidation. The phase-10 benchmark compares 1/2/4 actors and records wall clock, actor CPU, peak heap, GC and correctness.

## Acceptance invariant

Run `35342199973` / checkpoint job `105590432519` on the assembled AOT image passed the complete checkpoint suite.

40-file deterministic fixture:

```json
{"files":40,"cold_ms":284.669889,"repeated_ms":15.968163,"cold_javac_queries":1,
 "repeated_additional_javac_queries":0,"repeated_files_reanalysed":0,"repeated_index_writes":0}
```

- [x] unchanged warm `diag.get`: **0 additional javac queries**
- [x] unchanged warm `diag.get`: **0 files reanalysed**
- [x] unchanged warm `diag.get`: **0 synchronous index writes**
- [x] diagnostics are identical to the cold result
- [x] pagination performs no semantic analysis
- [x] warm latency is comfortably below the 500 ms budget
- [x] the regression now exercises multiple consecutive warm reads, not only one retry

Real Maven multi-module fixture (128 files, two source-bearing modules, non-empty classpath):

```json
{"files":128,"source_modules":2,"cold_ms":392.060647,"warm_ms":31.972242,
 "cold_javac_queries":1,"warm_additional_javac_queries":0}
```

This benchmark also verifies module batching, zero warm semantic work, and request-scoped Maven validation.

## Definition of done (§83)

- [x] Warm unchanged workspace: 0 javac, 0 analyses, 0 synchronous index writes.
- [x] Body-only edit: only the changed file is analysed when its API fingerprint is unchanged.
- [x] API-changing edit: reverse-dependency invalidation, including transitive/API-shape cases, is covered.
- [x] Multi-module state: retained compiler/context caches survive module switching.
- [x] Cold workspace: N files do not require N independent javac tasks.
- [x] Restart: validated persisted diagnostic states restore without javac.
- [x] LSP diagnostics share the same `diag.get` state.
- [x] Correctness: unsaved documents, preserved-mtime edits, source deletion/addition, classpath/JAR/release changes, processor output and Lombok fidelity have regressions.
- [x] Observability: coordinator phase timings, javac counters, reuse counters, snapshot/publication counters and invalidation reasons are exposed.
- [~] Scale calibration: a 128-file multi-module fixture is checked in; the architecture's larger ~250/~1,000-source long-run fixtures remain useful follow-up work rather than a correctness blocker.

## Baseline and benchmark interpretation

The architecture document recorded an external historical workload at roughly **26.8 s fresh / 8.45 s repeated**. That exact workload is not checked into this repository, so those figures must not be directly compared with the deterministic CI fixtures above.

The checked-in benchmarks are architectural regression evidence: they prove the warm-work invariant and current batching behavior on reproducible workloads.

## Remaining non-blocking work

1. Add ~250- and ~1,000-source multi-module fixtures for long-run latency, memory and cache-budget calibration.
2. Use the checked-in 1/2/4-actor measurements to decide whether production should keep the automatic actor count or pin a lower value on memory-constrained machines.
3. Extend Lombok fidelity beyond diagnostics if generated-member body/source-position navigation becomes a requirement.
4. Re-run the unavailable historical workload separately if an apples-to-apples 26.8 s / 8.45 s comparison is needed.

## Current test matrix

- [x] repeated unchanged workspace/file diagnostics and pagination
- [x] body-only implementation edit
- [x] return/signature/API-shape changes
- [x] transitive dependency/API invalidation
- [x] superclass/interface/overload/static-import changes
- [x] source deletion and package/namespace move
- [x] newly introduced source resolving a previously unresolved name
- [x] dependency JAR and loose-classpath replacement
- [x] compiler `--release` change
- [x] generated-source / processor-output change
- [x] unchanged processor output reuses identity
- [x] workspace/source mutation during analysis is superseded
- [x] persistent restart reuse and corrupt-cache rebuild
- [x] batch-vs-individual diagnostic agreement
- [x] multi-module cold/warm workload
- [x] high-fidelity Lombok diagnostics against a real build
- [x] A→B→A compiler/focused-cache retention
- [x] asynchronous source-index publication and in-flight dedupe

## Next actions

1. Get the final checkpoint + corpus CI run green on the hardening/documentation commit.
2. Review the 1/2/4-actor evidence after CI and keep the bounded production default only if heap/GC remain acceptable.
3. Once CI is green, this branch is ready for review; do not merge it implicitly.

## Checkpoint history

### Checkpoint 0 — Branch and log
- Commit: `dc22e7e` — `DIAGNOSTICS-PROGRESS.md`.
- Recorded the historical benchmark and the acceptance invariant.

### Checkpoint 1 — Instrumentation (architecture phase 0)
- Commits: `726f474` (CompilerPool timing/counters), `55ce8d3` (lifecycle counter assertions).
- Files: `CompilerPool.java`, `CompilerPoolLifecycleTest.java`.
- Exposed: javac query count/duration, configure count/duration, classpath validation count/duration, recycle/fault/heap; later checkpoints added files analysed/reused, cache hits/misses, `recordSource` count/duration, Maven resolve calls/cache hits.
- Gap: no separate `diag.get` sub-timer in `Application`; total RPC duration comes from the dispatcher `Metrics` layer.

### Checkpoint 2 — Request-scoped preparation (architecture phase 1)
- Commits: `cb4c091` (`RequestScope`), `d99def5` (scope handlers to one RPC generation), `64d546e` (Maven workspace memoization), `778592d` (Maven-backed diagnostic request test), `6d60bd5` (classpath validation once per RPC and module).
- Files: `RequestScope.java`, `Dispatcher.java`, `MavenResolver.java`, `CompilerPool.java`, `DiagnosticRequestContextTest.java`.
- Proven: actual Maven `resolveWorkspace` calls per multi-file `diag.get` = **1**.
- Gaps: 3 and 6.

### Checkpoint 3 — Compiler-state retention (architecture phase 2)
- Commits: `fcacbdc` (one `CompilerPool` per context generation), `0616b1f` (A→B→A regression).
- Files: `Analyzer.java`, `ModuleAnalyzerContextReuseTest.java`.
- Proven: A's pool is retained and reports a reused javac context after a B traversal; no compiler operation is parallelized.
- Gaps: 1 and 2.

### Checkpoint 4 — DiagnosticStore (architecture phase 3)
- Commits: `2209ffd` (detached store), `0e72396` (Analyzer reuse counters), `189baec` (unchanged-workspace test), `96cbf0d` (store metadata API), `68b79e2` / `ad2c3fa` (benchmark output, 40-diagnostic pagination), `bde57f7` (`perf` tag for the corpus job).
- Files: `DiagnosticStore.java`, `Analyzer.java`, `IncrementalDiagnosticsStoreTest.java`.
- Store identity: normalized path, authoritative source hash, analyzer/module context generation, classpath fingerprint.
- Fixture: 40 sources (larger than the 32-entry focused LRU), 40 real diagnostics so page 2 exists.
- Measured: see the acceptance invariant above.
- Gaps: 5 and 7.

### Checkpoint 5 — API fingerprints (architecture phase 5)
- Commits: `77ee3de` (detached fingerprint), `356824f` (conditional reverse-dependency invalidation), `25df379` / `190f3a6` (acknowledge authoritative editor hashes), `7724304` (body-only vs signature regression), `4b03c6f` (keep dependants conditional for editor changes).
- Files: `ApiFingerprint.java`, `Analyzer.java`, `Dependencies.java`, `ApiFingerprintInvalidationTest.java`.
- Fingerprint excludes method bodies, positions, docs, locals and parameters as independent declarations; retains declaration identity, signature, modifiers, type parameters, erased descriptor, declaring type, GAV.
- Proven: body-only edit analyses one file and no dependant, fingerprint unchanged; signature edit changes the fingerprint, invalidates the dependant, and the type error is observed.
- Correctness notes: unsaved content stays authoritative through `Documents.hash`; preserved-mtime disk edits continue through content-hash checks; lazily discovered disk changes stay conservative; unresolved snapshots are reconsidered after a confirmed API change.
- Gap: transitive propagation untested.

## Environment

- Builds and runs in a session container with pinned Temurin 25.0.4.1+1, `jvmd-dist/assemble.sh` and `jvmd-dist/train-aot.sh`; CI uses the same toolchain.
- Architectural counters are environment-independent; millisecond budgets are not. `FocusedAttributionBudgetTest` (p95 < 50 ms) measured 51.7 ms on container hardware while passing on CI runners.

## Decisions

- `Dependencies` remains the reverse dependency graph rather than a new `SemanticDependencyGraph`.
- The existing 32-entry focused cache stays for interactive compiler results; `DiagnosticStore` is separate and per-file.
- `CompilerPool` thread ownership is unchanged; nothing is parallelized (§29).
- Request-level Maven reuse was implemented below `Application.analyzer(...)` through an RPC scope, so existing callers and semantics stay intact — this is why gap 6 is open by choice rather than oversight.
- `WorkspaceBindings` remains for operations needing a coherent workspace binding graph (§77).


## 2026-09-17 — Checkpoint 6: continuation, identity and publication

Checkpoint commit: the commit containing this entry (its SHA is recorded in the next entry).
Continues `2f805e6` from the audited continuation branch, including its corpus benchmark tag fix.

Changes:
- `FileStateRegistry`, `Documents`, `Dependencies`: reuse source hashes only with matching
  size, mtime, ctime and inode; providers without change time rehash conservatively.
  Warm diagnostics validate identities before loading source text.
- `RequestScope`, `WorkspaceContextManager`, `Application`: construct each main/test
  context once per RPC. Classpath stamping is request-memoized per compiler context.
- `Analyzer`: preserve focused and outline caches across module switches; recycle only
  compiler contexts that analysed affected files rather than every retained context.
- `DiagnosticStore`: enforce a byte budget and record invalidation reasons.
- `ApiFingerprint`, `Bindings`: include constants, annotation values, generic bounds,
  inheritance, permits, record components, annotation defaults and parameter annotations.
- `SourceIndexPublisher`, `IndexService`: bounded coalescing publication outside the
  diagnostic response; publication failure does not discard diagnostic state.

Validation: 10 focused tests passed locally with Temurin 25.0.4.1+1 / Maven 3.8.3.
Command: `mvn -Dtest=DiagnosticIdentityTest,ApiFingerprintInvalidationTest,ModuleAnalyzerContextReuseTest,ReverseDependencyInvalidationTest,CompilerPoolLifecycleTest,SourceIndexPublisherTest -Dsurefire.failIfNoSpecifiedTests=false test`.
New tests cover preserved-mtime diagnostics, warm stamp reuse, transitive constant
invalidation, an unrelated cached file, diagnostic budget eviction and blocked/coalesced
index publication. A→B→A now proves one query for A plus a focused-cache hit.

Prior CI evidence checked: run `35159316456` passed the checkpoint job. The corpus
job's only error was the AOT-only benchmark missing its image; identifier correctness
was 0.998821 and live/verified agreement passed. The inherited `bde57f7` test tag fixes
that harness mismatch. No workflow edits or weakened correctness floors are included.

Remaining: coordinator and generation checks, cold batching, restart persistence,
Lombok fidelity, full invalidation matrix, workload benchmarks and full CI validation.
The original private 26.8 s / 8.45 s workload remains unavailable here.


## 2026-09-17 — Checkpoint 7: cold batching, persistence and scheduling

Previous checkpoint SHA: `a4086813f83d83eb71ab10a324f9ef57d6a44bd9`.
This checkpoint: commit containing this entry; SHA follows in the next checkpoint.

Changes:
- `WorkspaceAnalysisCoordinator` owns live diagnostic refresh, deterministic merge and
  pagination. Changed prerequisites are refreshed before dependants, including the
  alphabetically-earlier dependant case. `Application` delegates to it.
- `CompilerPool.batchQuery` attributes a set of sources with one javac task. The coordinator
  chooses batches at 16 stale files / 25% of a module, bounded to 128 files or 4 MiB source
  text per chunk. Detailed binding graphs remain independent of diagnostic summaries.
- `DiagnosticSnapshots` stores checksummed immutable objects with atomic manifest entries.
  Writes are asynchronous and bounded. Restore validates source, JDK/schema/context,
  classpath, namespace and dependency content, including authoritative unsaved buffers.
  Corrupt objects are cache misses. Restored dependencies rehydrate reverse invalidation.
- Source identities are captured around attribution; superseded results are rejected.
  Classpath identities include content and output directories, not jar size/mtime alone.
- `Session`, `RequestScope`, `Dispatcher` allow interactive requests at compiler-safe batch
  boundaries, on the same owner platform thread, with isolated request memoization.
  A document generation change yields an explicit superseded-response warning.

Validation: 15 focused tests passed locally. Added `BatchDiagnosticsTest`,
`PersistentDiagnosticsTest`, `DiagnosticSchedulingTest`; retained all checkpoint-6 tests.
A 100-file cold batch exactly matched 100 individual compiler results and used **1** javac
query. Repeated request: **0** queries. The latest run measured **214.7 ms cold / 5.6 ms
warm** (prior runs 227.2/3.6 and 259.7/3.1 ms). These are local synthetic figures, not a
rerun of the private corporate workspace. The 40-file AOT test now requires one cold query.
Restart tests prove zero javac on unchanged restore, corruption rebuild, and correct
invalidation after an unsaved dependency API edit.

Remaining: high-fidelity external Lombok mode, larger multi-module benchmark, full
correctness matrix, stable processor-output reuse, API/docs and existing CI gates.
Optional parallel module actors remain deliberately gated: batching already removes
most repeated compiler setup, and no shared compiler is accessed concurrently.


## 2026-09-18 — Checkpoint 8: fidelity, invalidation matrix and workload validation

Continuation after checkpoint 7 completed the remaining correctness and workload work:

- stable processor-output reuse and identity preservation;
- isolated external Lombok diagnostics, source mapping and real-build agreement;
- watched loose-classpath invalidation without rescanning every class on every request;
- the API-shape/conditional invalidation matrix, including deletion, newly resolved symbols,
  package moves, dependency-JAR replacement and compiler-release changes;
- adaptive/superseded diagnostic scheduling regressions;
- a real Maven multi-module diagnostics benchmark wired into CI.

Run `35342199973` checkpoint evidence:
- 40 files: **284.7 ms cold / 16.0 ms warm**, one cold javac query, zero warm javac,
  zero warm reanalysis, zero synchronous index writes.
- 100-file direct batch: **51.1 ms cold / 3.8 ms warm**, one cold javac query.
- 128-file two-source-module Maven reactor: **392.1 ms cold / 32.0 ms warm**,
  one cold javac query and zero additional warm javac queries.

The checkpoint job is green. Corpus validation was still running when this source-of-truth
entry was prepared; the final hardening commit intentionally triggers both jobs again.


## 2026-09-18 — Checkpoint 9: isolated parallel module actors

Implementation commit: this checkpoint.

- Added a `DiagnosticEngine` isolation boundary and persistent `ModuleAnalyzerRegistry`.
- Every module actor owns one dedicated **platform thread**, its `Analyzer`, and all
  `CompilerPool` instances created by that analyzer. Javac is never invoked from the
  virtual orchestration threads and one Analyzer is never accessed concurrently.
- The coordinator parallelizes only distinct engines that explicitly report actor isolation.
  Direct/shared analyzers remain serial, preserving the existing compiler ownership invariant.
- Production actor concurrency defaults to `min(availableProcessors / 2, 4)` and can be
  overridden with `-Djvmd.diagnostics.moduleActors=N` or `JVMD_DIAGNOSTIC_MODULE_ACTORS`.
- Session heap budget is divided across the configured actor cap.
- Cross-module source changes broadcast only detached source/API identities. A prior API
  fingerprint is injected before conditional invalidation and the resolved fingerprint is
  published after analysis, preserving body-only versus API-changing invalidation across actors.
- `RequestScope` now carries a concurrent request memo so module actors share request-stable
  preparation without sharing compiler objects.
- Coordinator metrics now include actor parallelism used, actor CPU time, peak heap and GC
  collections for the request.
- `ParallelModuleDiagnosticsTest` compares 1, 2 and 4 actors on the same four-module,
  256-source workload and asserts identical diagnostics, four cold module-batch queries,
  zero warm javac work and zero compiler faults while recording wall/CPU/heap/GC evidence.
- Corpus CI timeout is raised from 30 to 90 minutes. Run `35342914541` proved the prior
  cancellation was the 30-minute job timeout: the final log line was
  `The operation was canceled.` at approximately 30 minutes, with no test failure.
