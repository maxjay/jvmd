# JVMD Diagnostics Redesign Progress

Implementation branch: `diagnostics/incremental-state`
Base: `main` at `5cbf5bb13051d76f52765018bb3881025f313d18`
Audited against `jvmd-incremental-diagnostics-architecture.md` at `bde57f7`, 2026-09-17.

Every claim below is `[x]` (a named test or CI run proves it), `[~]` (partly true — the note says
what is missing), or `[ ]` (not started). Phase numbers are the architecture document's, §60–§70.

## Phases

- [x] **Phase 0 — Instrument the existing path** (§60). `CompilerPool` javac/configure/classpath counters and timings, `Analyzer` reuse/analysis/index-write counters, `MavenResolver` resolve/cache counters. Test: `CompilerPoolLifecycleTest`.
- [~] **Phase 1 — Fix request-level redundant work** (§61). Maven `resolveWorkspace` is 1/request and source enumeration is 1/request. Missing: per-file module-context reconstruction in `Application.analyzer(...)` and per-file classpath stamping (gaps 3, 6). Test: `DiagnosticRequestContextTest`.
- [~] **Phase 2 — ModuleAnalyzerRegistry** (§62). `CompilerPool` is retained per context generation and `DiagnosticStore` survives generation changes. Missing: `Analyzer.configure` still clears the focused/outline caches on every generation change (gap 1). Test: `ModuleAnalyzerContextReuseTest` (asserts pool reuse only).
- [x] **Phase 3 — DiagnosticStore / zero javac warm path** (§63). Detached per-file semantic state; repeated unchanged `diag.get` adds no javac queries, no reanalysis, no index writes; pagination adds no semantic work. Test: `IncrementalDiagnosticsStoreTest`. CI: `checkpoints` job of run `35159316456`.
- [ ] **Phase 4 — Remove synchronous index publication** (§64). `index.recordSource(...)` is still inline in `Analyzer.bindings` (`Analyzer.java:157`). No `SourceIndexPublisher`. The warm unchanged path already performs zero writes; changed sources still write synchronously.
- [~] **Phase 5 — API fingerprint invalidation** (§65). Detached `ApiFingerprint`, conditional reverse-dependency invalidation, body-only vs signature behavior proven. Missing: the transitive `C → B → A` checkpoint. Test: `ApiFingerprintInvalidationTest`.
- [ ] **Phase 6 — Cold module batch analysis** (§66). No `CompilerPool.batchQuery`; cold analysis is still one javac task per unknown file.
- [ ] **Phase 7 — Adaptive cold/warm selection** (§67). No strategy selector, no timing histograms.
- [ ] **Phase 8 — Persistent diagnostic cache** (§68). In-memory only; a daemon restart reanalyses everything.
- [ ] **Phase 9 — High-fidelity Lombok backend** (§69). Reduced-fidelity path and its warning remain as-is.
- [ ] **Phase 10 — Parallel module actors** (§70). Not started, and correctly gated behind everything above.

## Acceptance invariant

Repeated unchanged `diag.get`, measured on the 40-source fixture with the assembled AOT image:

- [x] `repeated_additional_javac_queries = 0`
- [x] `repeated_files_reanalysed = 0`
- [x] `repeated_index_writes = 0`
- [x] diagnostics identical between first and second request
- [x] pagination over the cached snapshot triggers no semantic analysis
- [x] warm request under the 500 ms budget — **22.5 ms**
- [ ] the same invariant on a fixture with a real `pom.xml`, multiple modules and a non-empty classpath (gap 5)

```json
{"files":40,"cold_ms":1618.6,"repeated_ms":22.5,"cold_javac_queries":40,
 "repeated_additional_javac_queries":0,"repeated_files_reanalysed":0,"repeated_index_writes":0}
```

## Definition of done (§83)

- [x] Warm unchanged workspace: 0 javac, 0 analyses, 0 synchronous index writes, ≤ 1 workspace resolution validation
- [x] Body-only edit: changed file analysed, API fingerprint unchanged, dependants not analysed
- [~] API-changing edit: direct dependant invalidated and reanalysed; transitive closure untested
- [~] Multi-module: compiler pools and diagnostic states survive module switching; focused/outline caches do not (gap 1)
- [ ] Cold workspace: N files do not require N independent javac tasks
- [ ] Restart: persisted diagnostic states restored without javac
- [x] LSP shares state with `diag.get` — `LspFacade.diagnostics` delegates to `diag.get` (§43)
- [x] Correctness: preserved-mtime edits detected, unsaved documents authoritative, processor state versioned, Lombok fidelity explicit
- [~] Observability: counters exist; per-phase timings (§56), invalidation reasons (§58) and the `session.status` shape (§59) do not

## Baseline

- Pre-redesign, from the architecture document: fresh ~26.8 s, repeated ~8.45 s.
- [ ] Not reproducible as a checked-in fixture, so the 40-file benchmark is an architectural regression fixture, not an apples-to-apples rerun of those numbers.

## Open gaps

1. [ ] **Focused cache cleared on every module switch** (§62, §83). `Analyzer.java:38` runs `outlines.clear(); focused.clear()` whenever the context generation changes, so A→B→A discards A's interactive cache. Warm `diag.get` is unaffected; interactive latency after a module switch is not. `ModuleAnalyzerContextReuseTest` asserts pool reuse only, so nothing catches this.
2. [ ] **Cross-module pool recycling** (§23). `Analyzer.java:114` recycles *every* retained pool for any changed file, so an edit in module A discards module B's and C's warm javac state.
3. [ ] **Classpath identity is still per file** (§14). `CompilerPool.cacheValid()` is memoized per RPC (`6d60bd5`), but `Analyzer.classpathStamp()` (`Analyzer.java:47`) stats every classpath jar on each `bindings()`/`diagnostics()` call — once per file, not once per module generation.
4. [ ] **No FileStateRegistry** (§18, §40). Each warm `diag.get` reads every source and SHA-256s it twice, and `Dependencies.check` re-reads and re-hashes each file's forward-dependency closure. No `size + mtime + ctime + inode` fast path — though `AnnotationProcessing.java:68` already uses that idiom and can be reused. Cost is O(workspace bytes) per warm request.
5. [ ] **Benchmark fixture blind spot** (§72). The 40-source fixture writes no `pom.xml`, so `session.state("resolution")` is null and `Application.analyzer(...)` takes the `"plain"` path with an empty classpath. The 22.5 ms figure therefore exercises neither gap 3 nor gap 4 nor the per-file context rebuild. §72 asks for ~250-source and ~1,000-source multi-module fixtures; only the small one exists.
6. [ ] **No WorkspaceAnalysisCoordinator** (§11, §74, §75). `diag.get` is still an explicit per-file loop (`Application.java:119`) and `Application.analyzer(...)` (`Application.java:432`) rebuilds module lookup, classpath, coordinates and navigation sources for every file. Deliberate (see Decisions), and the warm invariant was met without it, but §12's "build module contexts once per request" is unrealized and gaps 3 and 4 live inside it.
7. [ ] **Store is unbounded** (§16.2, §51). `DiagnosticStore` has no byte budget or eviction, and `put(...)` prunes only entries for the same file *and* context fingerprint, so superseded-context states accumulate for the session's lifetime.
8. [ ] **No explicit state model** (§17, §58). `VALID / STALE / CONDITIONALLY_STALE / UNKNOWN` and the invalidation-reason enum are not modelled; conditional state lives in the ad hoc `pendingApi` and `conditionalByFile` maps, and `session.status` exposes no reason counts.
9. [ ] **No generations, supersession or cancellation** (§52, §53, §54). Keying by `(sourceHash, contextGeneration, classpathStamp)` prevents publishing a result under a newer source identity, but there is no explicit generation check, bounded retry, or dropping of superseded queued analyses.
10. [ ] **Corpus job needed the benchmark excluded.** Fixed at `bde57f7` by tagging `IncrementalDiagnosticsStoreTest` `perf`; the corpus job does not assemble `jvmd-dist/target/image`. CI confirmation pending.

## Test matrix (§71)

- [x] unchanged repeated workspace query — `IncrementalDiagnosticsStoreTest`
- [x] unchanged repeated file query — `IncrementalDiagnosticsStoreTest` (per-file store; `lsp.diagnostics` is a single-path `diag.get`)
- [x] body-only edit — `ApiFingerprintInvalidationTest`
- [x] return type change → dependants invalid — `ApiFingerprintInvalidationTest`
- [x] unsaved source edit / unsaved API edit — `UnsavedDocumentsTest`, `ApiFingerprintInvalidationTest` (edits arrive through `Documents`)
- [x] preserved-mtime content edit detected — `ReverseDependencyInvalidationTest.statPreservingChangesInvalidateCachedBindings`, `.compiledOutputCannotHideAContentChangeWithPreservedMtime`
- [x] dependency change observed only when touched — `ReverseDependencyInvalidationTest.aDependencyChangeIsObservedOnlyWhenTouched`
- [x] POM dependency changed → module invalid — `PomChangeClasspathDiffTest`
- [x] paginated diagnostics → no extra analysis — `IncrementalDiagnosticsStoreTest`
- [x] multi-module A→B→A — `ModuleAnalyzerContextReuseTest` (pool retention only; see gap 1)
- [x] analyzer fault → graceful tier degradation — `AnalyzerFaultDegradeTest`
- [x] Lombok reduced fidelity reported — `LombokPolicyTest`
- [x] new source participates without being written — `UnsavedDocumentsTest.newSourceFilesParticipateInLookupWithoutBeingWritten`
- [ ] private implementation change → only changed file
- [ ] superclass change → dependants invalid
- [ ] interface method change → implementations/users invalid
- [ ] overload added → relevant dependants invalid
- [ ] static import target changed → dependant invalid
- [ ] source deleted → dependants invalid
- [ ] new source introduces previously unresolved symbol → unresolved dependants reconsidered (implemented via `DiagnosticStore.unresolvedFiles()`, untested)
- [ ] package rename → old/new namespace invalidation (`Analyzer.namespaceChanged()`, `Analyzer.java:141`, has no test)
- [ ] dependency JAR replaced → consuming module invalid
- [ ] compiler `--release` changed → module invalid
- [ ] generated source changed → affected module invalid
- [ ] processor output unchanged after rerun → no downstream work
- [ ] workspace changes during analysis → stale result not published
- [ ] cache persistence restart → result reused
- [ ] corrupted persistent cache → safely ignored/rebuilt
- [ ] every batch-analysis row (blocked on phase 6)

## Next actions

1. [ ] Confirm the corpus fix is green on CI (`bde57f7`).
2. [ ] Close gap 4 (FileStateRegistry) **before** phase 4 — it is cheap, the idiom exists at `AnnotationProcessing.java:68`, and without it a medium fixture will make the warm path look bad for reasons phase 4 cannot fix.
3. [ ] Add the medium fixture from gap 5 so gaps 3, 4 and 6 become measurable.
4. [ ] Phase 4: `SourceIndexPublisher`, with the §64 test (index publication blocked, diagnostic response still completes).
5. [ ] Close gaps 1 and 2, and extend `ModuleAnalyzerContextReuseTest` to assert focused-cache survival rather than pool reuse alone.
6. [ ] Add the transitive `C → B → A` case to `ApiFingerprintInvalidationTest`.
7. [ ] Phase 6 (module-batched javac), then re-evaluate phases 7–10.

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
