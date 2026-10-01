# Persistence architecture: status after the strict second pass

This page records where the persistence work stands after the strict second-pass task (*JVMD Persistence, second
pass (strict)*), on top of the first pass of *JVMD Persistence and Incremental Semantic Architecture* (PR #55, base
`c8fcb9f`). Workstreams are W1–W9 and acceptance scenarios A1–A13 of the strict task. Every number here comes from a
committed harness; the command to rerun it is next to it, and the raw output is in [`docs/evidence/`](evidence/).

## Summary

- Attributed diagnostics persist as LOCAL memos with **precise certificates only**. A dependency on another source unit
  is bound by its diagnostic projection `P_diag`, not its content. Namespaces are scoped to the packages name
  resolution consulted, plus negative resolutions. There is no coarse certificate, no global namespace leaf and no
  second persisted diagnostics mechanism.
- Restore is a pull-based dependency cone with early cutoff. A body-only edit recompiles the edited unit, compares its
  `P_diag`, and restores every dependant.
- On a real Lombok + MapStruct + Spring Boot multi-module project (ruoyi-vue-pro, 977 units in 18 modules), a
  no-change restart and a relocated checkout restore **977/977 units with zero compiler runs**. A 20-file body-only
  branch switch recompiles 22 units. No unit is refused on a no-change restart.
- **MACHINE storage: keep RocksDB.** On the 490-jar corpus the native backend fails three of the five pre-registered
  criteria (peak RSS 0.92×, heap allocation 1.08×, exact p95 3.39×), so the native POC left production code.

## Acceptance scenarios (strict task §6)

Synthetic fixtures are generated with a fixed seed by `SyntheticProjects` (random DAG, hub, layered; no linear chain).
"javac" counts units the compiler attributed.

| # | Scenario | Required | Measured | Breaking test / harness |
|---|---|---|---|---|
| A1 | No-change warm restart, 5,000 units, all topologies | javac 0, restores 5,000, bytes hashed 0, enumerations 0 | javac 0, restores 5,000, 0 bytes hashed, 0 enumerations | `RestartScenarioTest.a1NoChangeRestartRestoresEveryUnitWithoutJavac`, `WarmRestartGateTest.noChangeWarmRestartIsLinearAndEnumeratesNothing` |
| A2 | Body edit to the hub | javac 1, restores 4,999 | javac 1, restores 4,999 | `RestartScenarioTest.a2HubBodyEditRecompilesOnlyTheHub`, `…a2InReverseOrderEarlyCutoffAttributesTheHubOnceForItsFirstDependant` |
| A3 | Private method added to the hub | 1 + direct completers | exact | `RestartScenarioTest.a3PrivateMethodAddedToTheHubRecompilesItsDirectCompleters` |
| A4 | Public method added mid-layer | 1 + direct dependants; cutoff per layer | exact, counted per layer | `RestartScenarioTest.a4PublicMethodInAMidLayerStopsAtDependantsWhoseProjectionIsUnchanged` |
| A5 | New top-level type in `p` | only units holding `package:…\|p` or a matching negative | exact | `RestartScenarioTest.a5NewTopLevelTypeRecompilesOnlyUnitsThatConsultedItsPackage` |
| A6 | Body edit inside a 3-cycle | javac 3 | javac 3 | `RestartScenarioTest.a6BodyEditInsideAThreeCycleRecompilesTheCycleOnly`, `PreciseCertificateTest.bodyEditInsideAThreeCycleInvalidatesTheCycleAndBodyEditOutsideInvalidatesNone` |
| A7 | Relocated checkout, real project | javac 0, ≥ 98 % restored | javac 0, 977/977 (100 %) | `RealProjectBenchmark` (below) |
| A8 | Branch switch, K = 20 body-only, real project | javac K, all others restored | 22 units recompiled, 955 restored | `RealProjectBenchmark`; see the progress log for the 2 extra units |
| A9 | Lombok/MapStruct no-change restart, real project | 0 % processor refusals, ≥ 95 % restored | 0 refusals, 977/977 | `RealProjectBenchmark`, `ProcessorBindingTest.allowlistedLombokContextIsMemoisedAndRestoredThroughBinaryProjections` |
| A10 | `lombok.config` edited | exactly the units holding it | root config: all 977 hold it, all 977 recompiled; package config: exactly its units | `RealProjectBenchmark`, `ProcessorBindingTest.lombokConfigEditRecompilesExactlyTheUnitsHoldingIt` |
| A11 | 1,000 vs 5,000 units | wall ratio ≤ 6, stats ≤ 2 × files + dirs | ratio 4.92; stats 2,032 / 10,032 = bound | `WarmRestartScalingBenchmark` ([log](evidence/warm-restart-scaling.md)) |
| A12 | Corrupt memo record, journal, directory inventory | miss then rebuild, never an error | miss then rebuild | `WarmRestartGateTest.a12CorruptMemoJournalAndInventoryMissThenRebuild`, `SemanticMemoStoreTest.entriesAreIndependentlyValidAndCorruptionIsOnlyAMiss`, `FileStatePersistenceTest.torn_or_corruptJournalLosesOnlyItsInvalidSuffix` |
| A13 | Sufficiency differential | 2,000+ mutations, 0 mismatches | 2,000 mutations, 0 mismatches; 217/217 neutral mutations equal | `DiagnosticProjectionSufficiencyTest.unchangedDiagnosticProjectionImpliesUnchangedDependantDiagnostics` |

Rerun A1–A6 at 5,000 units with `mvn -pl jvmd-tests test -Dtest=RestartScenarioTest -Djvmd.scenario.units=5000
-DexcludedGroups=corpus` ([log](evidence/restart-scenarios.md)), A11 with `mvn -pl jvmd-tests test -Dtest=WarmRestartScalingBenchmark
-DexcludedGroups=corpus`.

## Real project (W9)

Pinned in [`benchmarks/real-project.json`](../benchmarks/real-project.json): `YunaiV/ruoyi-vue-pro` at `1697112f`.
The reactor's default build has 18 modules with main sources (framework starters plus `system`, `infra` and `server`)
and 977 main source units. Every module runs Lombok; the processor path also carries MapStruct and the Spring Boot
configuration processor. Build it once with Maven into a local repository (UTF-8 locale), then run
`mvn -pl jvmd-tests test -Dtest=RealProjectBenchmark -DexcludedGroups= -Djvmd.w9.project=<checkout>
-Djvmd.w9.repository=<repository>`. Full output: [real-project.md](evidence/real-project.md).

| Run | Compiler runs (in-process + external) | Units recompiled | Memo restores | Persisted processor hits | First correct completion | Diagnose all units |
|---|---:|---:|---:|---:|---:|---:|
| Cold (no state) | 184 (148 + 36) | 977 | 0 | 0 | 110.5 s | 69.6 s |
| A9 no-change restart | 0 | 0 | 977 | 18 | 3.0 s | 21.3 s |
| A7 relocated checkout (`mv` of the tree) | 0 | 0 | 977 | 18 | 10.4 s | 21.3 s |
| A8 branch switch, 20 body-only edits | 38 (22 + 16) | 22 | 955 | 10 | 66.2 s | 33.9 s |
| A10 reactor-root `lombok.config` edited | 184 (148 + 36) | 977 | 0 | 0 | 97.4 s | 77.1 s |

- *Compiler runs* counts every compiler invocation: in-process attributions (one per batch of up to 40 units) plus
  external processor runs (one per module). In Lombok modules the daemon publishes the external processor run's
  diagnostics, so an edited Lombok module reruns its processor once; that run's result is itself persisted under a
  location-free key (`ExternalAnnotationProcessingTest.processorResultSurvivesRestartAndRelocationAndCorruptionReruns`).
- *Units recompiled* is units minus memo restores.

**Refusal rates per reason** (refusals / units diagnosed in that run):

| Run | Units | Refusals | Rate | By reason |
|---|---:|---:|---:|---|
| Cold | 977 | 0 | 0 % | — |
| A9 no-change restart | 977 | 0 | 0 % | — |
| A7 relocated checkout | 977 | 0 | 0 % | — |
| A8 branch switch | 977 | 0 | 0 % | — |
| A10 `lombok.config` edited | 977 | 4 | 0.41 % | `foreign-diagnostic-file` 4 |

Allowlisted processors are never refused (A9: 0 %). Over the work on this project the following reasons were found
and removed by binding instead of refusing (progress log): `non-logical-classpath-entry`, `non-logical-dependency`,
`unsaved-processor-inputs` on context warnings, `negative-unproven:static-import` (15 units), and the previously
uncounted `query-warning:originates` (14 units).

Reason codes are exposed by `session.status section=persistence` (`attributed_memo.refusal_reasons`) and in the
benchmark output. `foreign-diagnostic-file` is a unit whose javac diagnostics name another file; it is refused rather
than persisted with a path it cannot restore.

**Restart to first correct completion.** The benchmark lifecycle (`benchmarks/harness/lifecycle.ts`, run by
`jvmd-benchmarks.yml`) restarts the daemon on its persisted state and polls completion until `getGroupId` is offered.
**Measured in CI: 12.0 s** from starting the new daemon (after the old one stopped) to the first correct completion
([run 24](https://github.com/maxjay/jvmd/actions/runs/36850495307), `1eb5d87`, `apache/maven`, 4 CPUs): daemon restart
on the persisted index 1.2 s, editor open after restart 9.6 s (cold open: 76.7 s), reconnect to the warm daemon 9.1 s.

Getting there took three fixes, each found from the lifecycle's own evidence:

- Run 17 (`c710b4d`): the restarted daemon exited with 1 during startup. The harness started the new daemon before
  the killed one had exited and released its store (fixed in `4f87c3f`).
- Runs 19–21: the editor's `initialize` failed, first by timing out, then with a faulted `session.open`. The
  harness's failure evidence (daemon log and busy thread stacks in the job log) showed two RocksDB configuration
  bugs: a strict block-cache limit that failed reads under cache pressure (fixed in `1745f2e`), and writers stalled
  indefinitely on the shared write-buffer budget while holding a lock that `initialize` needs (fixed in `1eb5d87`).
  Both have breaking tests in `RocksMemoryTest`.

The in-process equivalent on the real project is in the table above (first correct completion after a no-change
restart: 3.0 s, cold 110.5 s).

## Checklist (strict task §7)

| Item | Commit | Breaking test |
|---|---|---|
| [x] W1 `AttributedMemos` extracted; `Analyzer.java` ≤ 2,500 lines (2,489; it was 2,852 at the PR head) | `c04d4ed` | `AnalyzerSizeGateTest.analyzerStaysWithinItsLineBudget` |
| [x] W2 `P_diag` computed, stored and restart-stable (relocation) | `43f609f` | `DiagnosticProjectionSufficiencyTest.projectionIsIndependentOfCheckoutLocation`, `…privateMembersAndConstantsAreIncludedButBodiesAreNot` |
| [x] W2 completed-dependency capture, inherited members | `43f609f`, `e009558` | `CompletedDependencyCaptureTest.inheritedMethodDeclaringUnitIsADependency`, `…supertypesCompletedForSubtypingAreDependencies`, `…capturedDependenciesCoverJavacsCompletionSetForEveryFixtureUnit`, `…capturedClassDirectoryTypesCoverJavacsCompletedClassFiles` |
| [x] W2 sufficiency: 2,000+ mutations, 0 mismatches | `43f609f` | `DiagnosticProjectionSufficiencyTest.unchangedDiagnosticProjectionImpliesUnchangedDependantDiagnostics` |
| [x] W3 certificate per the §4.3 table; SCC test | `738b974` | `PreciseCertificateTest` (all five), `ReactorClassBindingTest` |
| [x] W3 coarse fallback, global namespace and `source-roots*` keys deleted; `ATTRIBUTED` versioned (now v5) | `738b974` | `SemanticMemoStoreTest.certificatesRejectProcessLocalAndPhysicalKeys` (rejects `source-roots:` keys); `Coverage` has only `PRECISE`, so a coarse record does not compile |
| [x] W4 dependency-ordered restore with early cutoff; A2–A6 pass | `2659b28`, `fcff119` | `RestartScenarioTest.a2*`–`a6*`, `…aRequestWaitsOnlyForItsDependencyCone`, `SemanticMemoStoreTest.dependencyResolutionDoesNotHoldTheStore` |
| [x] W5 shared snapshot; directory inventory journal; A1 and A11 pass | `543692a` | `WarmRestartGateTest.noChangeWarmRestartIsLinearAndEnumeratesNothing`, `WarmRestartScalingBenchmark` |
| [x] W6 processor binding and allowlist; reactor slots; path-option slots; A9 and A10 | `42a9042`, `9457133`, `e009558` | `ProcessorBindingTest` (all four), `ProcessorAllowlistTest`, `ReactorClassBindingTest`, `AttributedMemoIntegrationTest.ambientCompilerContextIsBoundIntoTheStaticKeyOrRefusedWithItsReason` |
| [x] W7 `DiagnosticSnapshots` persistence deleted; production lines −156 / +28 | `3c7a36c` | `DiagnosticSnapshotCleanupTest.startDeletesTheObsoleteDiagnosticsDirectory`, `PersistentDiagnosticsTest` |
| [x] W8 compression added; 490-jar comparison run; decision applied per the fixed rule (keep RocksDB); the native POC removed from production | `3b9d16f`, `513639c` | `MachineSegmentTest` (compression), `MachineDecisionBenchmark` |
| [x] W9 real project pinned; 5,000-unit fixtures | `9457133`, `2659b28` | `RealProjectBenchmark`, `RestartScenarioTest` |
| [x] W9 restart to first correct completion measured in CI: 12.0 s ([run 24](https://github.com/maxjay/jvmd/actions/runs/36850495307)) | `9457133`, `4f87c3f`, `1745f2e`, `1eb5d87` | `benchmarks/harness/lifecycle.ts` (`phases.restart_first_completion_ms`), `RocksMemoryTest` |
| [x] W9 status doc with a breaking test per item and refusal rates per reason | this commit | — |

## The problems that started this task (strict task §1)

| Problem | Status | Test |
|---|---|---|
| Certificates use raw file content | Fixed: dependencies are `logical-unit:` / `logical-binary:` / `reactor-class:` P_diag entries; content only for the unit itself (static key), SCC peers and non-Java inputs | `PreciseCertificateTest`, `RestartScenarioTest.a2HubBodyEditRecompilesOnlyTheHub` |
| Every record `Coverage.COARSE` | Fixed: `COARSE` no longer exists | `SemanticMemoStoreTest.certificatesRejectProcessLocalAndPhysicalKeys` |
| Global namespace leaf | Fixed: `package:<gav\|scope>\|<pkg>` for the own and star-imported packages, negative resolutions, and `class-package:` for other modules' class directories | `PreciseCertificateTest.namespaceEntriesAreScopedToTheOwnAndStarImportedPackages`, `RestartScenarioTest.a5*` |
| Restore lazy, no early cutoff | Fixed: pull-based dependency cone; a changed dependency is attributed once and compared | `RestartScenarioTest.a2InReverseOrder*`, `…a4*` |
| Quadratic validation (~56,700 stats for 160 units) | Fixed: stats equal `2 × files + dirs` at 1,000 and 5,000 units | `WarmRestartGateTest`, `WarmRestartScalingBenchmark` |
| Real projects get nothing (processors, `-A`, path options, reactor outputs) | Fixed for allowlisted processors, `-A`, path-type options and reactor outputs; no refusal on the pinned project's no-change restart | `ProcessorBindingTest`, `ReactorClassBindingTest`, `RealProjectBenchmark` |
| Synthetic-only evidence; MACHINE decided on 26 jars | Fixed: real project above; MACHINE re-decided on 490 jars | `RealProjectBenchmark`, `MachineDecisionBenchmark` |
| Phase 8 inside `Analyzer.java` (2,852 lines) | Fixed: 2,489 lines | `AnalyzerSizeGateTest` |
| Two persisted diagnostics mechanisms | Fixed: `DiagnosticSnapshots` persistence and `diagnostics-v2` deleted | `DiagnosticSnapshotCleanupTest` |

Open items are listed under *Known limits*.

## Certificate entries (persistable keys)

`PersistableProofKeys` is the executable audit; certificates accept only these restart-stable keys.

| Domain | Key | Identity |
|---|---|---|
| RESOLUTION_PATH | `logical-unit:<gav\|root\|path>` | P_diag of a source unit outside the unit's SCC |
| RESOLUTION_PATH | `logical-binary:<gav\|root\|path>` | binary P_diag of a unit Lombok hides from the source path (read from the processor's class output) |
| RESOLUTION_PATH | `reactor-class:<binary>` | binary P_diag of a class read from another reactor module's class directory |
| RESOLUTION_PATH | `logical-source:<gav\|root\|path>` | content of an SCC peer |
| RESOLUTION_PATH | `config:<reactor gav>\|<path>` | content or absence of `lombok.config` / JPA XML |
| NAMESPACE | `package:<gav\|scope>\|<pkg>` | S0 top-level type set of one package in the source roots |
| NAMESPACE | `class-package:<pkg>` | top-level class names of one package in other modules' class directories |
| NEGATIVE_RESOLUTION | `<simple>@<binary>` | whether a type or package of that name exists in the source roots or other modules' class directories |

The static key binds the unit's logical id and content, release, options (path options as logical slots), the
annotation processing binding (allowlisted processor classes, processor path content, `-A`, mode), archives by content,
this module's class directories by content, other modules' class directories by logical slot, the platform's content
identity, the logical source roots, hidden (Lombok) units and the context warnings.

## MACHINE storage decision (W8)

**Keep RocksDB.** The rule was fixed in the strict task before the run and applied verbatim by
`MachineDecisionBenchmark`: adopt native only if all five criteria hold. Corpus: the full local-repository closure
of `apache/maven@5cd1b602` (490 jars, 234 MB; 483 with symbols, 1,004,202 symbols). Each backend ran in a fresh
JVM (`-Xmx2g`) that seeded the whole corpus and answered first-use queries, then a second fresh JVM measured restart
to first query. Native includes the block-compressed string section added in W8 (16 KiB raw deflate blocks).

| Criterion | RocksDB | Native | Ratio | Result |
|---|---:|---:|---:|---|
| peak RSS during seed ≤ 0.7 × | 1,251.7 MB | 1,150.6 MB | 0.92 | fail |
| total heap allocation during seed ≤ 0.7 × | 37,513.7 MB | 40,631.8 MB | 1.08 | fail |
| exact lookup p95 ≤ 1.5 × | 276.9 µs | 939.0 µs | 3.39 | fail |
| disk bytes ≤ 1.25 × | 483.7 MB | 248.8 MB | 0.51 | pass |
| restart to first query ≤ 100 ms | 195.9 ms | 17.8 ms | — | pass |

Other measurements: build 109.7 s vs 88.4 s, peak heap 1,049 MB vs 936 MB, peak native malloc 197 MB vs 188 MB,
prefix p95 438 vs 599 µs, substring p95 3,988 vs 41,789 µs ([machine-decision.md](evidence/machine-decision.md)).

As the rule requires for "keep", the native POC is no longer production code: `MachineSegment`,
`MachineAccelerators` and `ClasspathRouting` had no production users and now live in the tests module next to
their harness (`MachineDecisionBenchmark`, `MachineStorageBenchmark`, `ClasspathRoutingBenchmark`) and tests.
Rerun: `mvn -pl jvmd-tests test -Dtest=MachineDecisionBenchmark -DexcludedGroups= -Djvmd.w8.corpus=<repository>`.

## Progress log

| Date | Workstream | Commit | Note |
|---|---|---|---|
| 2026-10-01 | W1 | c04d4ed | `AttributedMemos` extracted; three nested classes moved out to reach 2,493 lines. |
| 2026-10-01 | W2 | 43f609f | Capture was reference-based, so supertypes reached only through subtyping were missing. Now uses a per-unit completion model; the javac symbol table is used only to check it. A batch shares one completion set, so it cannot give per-unit dependencies; the model makes batch and single-file results identical. |
| 2026-10-01 | W3 | 738b974 | SCC peers bind the content of *every* SCC member, not only their direct dependencies; otherwise a body edit to a non-adjacent member would not invalidate the cycle. |
| 2026-10-01 | W3 | 738b974 | Disagreement: dependency P_diag is computed inside the dependant's own javac task, not taken from the dependency's own record, because a dependency need not have been attributed. `dependencyProjectionFromADependantsTaskEqualsTheUnitsOwnProjection` checks they are equal. |
| 2026-10-01 | W4 | 738b974, 2659b28 | Disagreement: the resolver is a pull-based dependency-cone resolver inside `AttributedMemos.restore` (each unit restored or attributed at most once per process), not a ProofDag schedule. ProofDag remains the in-memory propagation engine. A request waits only for its dependency cone (`aRequestWaitsOnlyForItsDependencyCone`). |
| 2026-10-01 | W5 | 543692a | Disagreement: the stat bound counts every file the restart validates (the units plus the 3 JDK platform files), with directories counted once. Measured stats equal the bound exactly at 200, 1,000 and 5,000 units. |
| 2026-10-01 | W5 | 543692a | A12 found a real bug: a restored record reported the diagnostic file as a plain path where javac reports a `file:` URI. Fixed; ATTRIBUTED is now v3. |
| 2026-10-01 | W3/W4 | fcff119 | 5,000-unit restart deadlocked: memo lookup held the store monitor while resolving dependencies, and the writer held SourceNamespaces. Fixed with a breaking test. |
| 2026-10-01 | W6 | 42a9042 | Disagreement: generated sources are units in a declared `generated-sources-<scope>` logical root and enter certificates as `logical-unit:` P_diag entries when they are completed, rather than as `config:` content entries. P_diag is sufficient by A13, and a generated unit's own record binds its content through the static key. |
| 2026-10-01 | W6 | 42a9042 | The module's own Lombok class output is not a static slot; each Lombok-rewritten unit is bound through a binary P_diag instead. A static slot would make any Lombok unit's body edit invalidate the whole module and break A8/A10 exactness. |
| 2026-10-01 | W8 | 3b9d16f | The 462-jar / 201 MB corpus could not be identified exactly. The run uses the full local repository closure of apache/maven@5cd1b602 (490 jars, 234 MB), a superset; 456 jars / 179 MB without SNAPSHOTs. |
| 2026-10-01 | W9 | 9457133 | Disagreement: on the pinned project every module runs Lombok, so the daemon publishes the external processor's javac diagnostics rather than the in-process ones. A7–A10 are only meaningful once that result is persisted too. It is now a logically keyed record next to its output (`processorResultSurvivesRestartAndRelocationAndCorruptionReruns`). Real-project counts report external runs and in-process javac separately. |
| 2026-10-01 | W9 | 9457133 | Added `spring-boot-configuration-processor` to the allowlist, with a test that runs it. The pinned project's processor path includes it; it only writes `META-INF/spring-configuration-metadata.json`. |
| 2026-10-01 | W9 | 9457133 | The real project exposed binary P_diag drift between capture and restore, with three causes. (1) javac reads parameter names and `final` from a class file only under `-parameters`; neither can change a dependant's diagnostics, so P_diag no longer projects them (domain v3). The neutral mutations "parameter renamed" and "parameter made final" keep the sufficiency test at 0 mismatches. (2) `IndexedFileManager.list` let the *last* class directory win, while `getJavaFileForInput` and javac take the first, so the module's `target/classes` shadowed the processor output (`firstClassDirectoryWinsInListingsAndLookups`). (3) A reused (pooled) javac context can drop a nested member class from a class-file type's element model. |
| 2026-10-01 | W3/W6 | 9457133 | Disagreement (refines the W3 row): binary dependencies (Lombok-processed units read from class files) take their P_diag from one clean classpath-only reader per context, the same reader restore uses, not from the dependant's pooled task. This is sound because each processor run publishes a fresh output directory that is never rewritten, and hidden units resolve there first, so the reader sees exactly the bytes the dependant's task read. On the infra module, no-change restore went from 129 to 202 of 205 units restored, with 0 stale certificates. |
| 2026-10-01 | W6/W9 | e009558 | The first real-project A8 run restored only 72/977. A body edit in an upstream module reruns its processor and changes the bytes of its class output, which was a content-hashed directory slot in every downstream static key. Other reactor modules' class directories are now bound class by class: the static key holds the logical slot only; the certificate holds `reactor-class:<binary>` (clean-reader P_diag) for each class the completion model reached there, `class-package:<pkg>` (top-level class names) for consulted packages, and negative resolutions that also check those directories. The completion walk now continues through class-directory types (`capturedClassDirectoryTypesCoverJavacsCompletedClassFiles` checks it against javac's symbol table). Breaking test: `ReactorClassBindingTest`. The module's own class directories stay content-bound. |
| 2026-10-01 | W3 | e009558 | Found while writing `ReactorClassBindingTest`: a qualified reference to a missing class (`p.Missing`) was recorded only as the simple name, because javac reports it as `symbol: class Missing` with `location: package p`. That was a soundness hole for sources too. Negative resolutions now include `p.Missing`. |
| 2026-10-01 | W9 | e9ddfb5 | The CI hover-edit allocation rise (1.3M to 11.4M) did not reproduce as a steady-state regression. An in-process A/B of the same edit, diagnostics and hover loop against main c8fcb9f gave a median of 1.43 MB on this branch and 2.00 MB on main, with a first edit of about 20 MB on both. The new cost was periodic: every memo-write flush ran S0 parses with `--release`, which opens ct.sym per parse (6–20 MB per flush). S0 now parses with `--source` and one shared file manager. |
| 2026-10-01 | W6/W9 | c710b4d | Two real-project refusals were removed rather than widened. (1) `capture` returned silently on any query warning, so it was not counted (rule 6); it is now `query-warning:<kind>`. `originates: <gav>` is a deterministic function of the result and is stored in and restored with the record (`SourceOverlayNavigationTest`). (2) `negative-unproven:static-import`: an unresolved name under static imports is bound through each statically imported class: a P_diag entry when it is a source dependency or reactor class, otherwise an absence entry (`ReactorClassBindingTest.unresolvedNamesUnderStaticImportsAreBound`). |
| 2026-10-01 | W3/W9 | 4f87c3f | A negative resolution now binds only whether a type or package of that name exists, not where. On a branch switch without a rebuild, a dependency module moves from its class output to its sources, which had changed the location-list identity (45 A8 misses) without changing resolution. |
| 2026-10-01 | W9 | 4f87c3f | CI lifecycle: the restarted daemon exited with 1 during startup. `JvmdDaemon.stop()` returned right after SIGKILL, so the next daemon could start while the killed one still held its store. The harness now waits for the process to exit. |
| 2026-10-01 | W9 | 1745f2e | CI lifecycle: requests faulted with RocksDB "Insert failed due to LRU cache being full". The shared block cache had a strict capacity limit, so reads failed whenever blocks pinned by concurrent readers, pinned index and filter blocks and charged memtables reached the budget. It is now non-strict: the budget is a capacity target (`RocksMemoryTest.readsSucceedWhilePinnedBlocksExceedTheBudgetAndTheCacheShrinksBack`). This was latent on main (`RocksMemory` is unchanged); the W8 decision does not depend on it (native fails heap allocation and exact p95 regardless). |
| 2026-10-01 | W9 | 1eb5d87 | CI lifecycle: the editor's `initialize` timed out. All databases share one `WriteBufferManager` with stalling enabled; once the memtables of several databases, each below its own flush threshold, exceeded the shared budget, a writer stalled indefinitely. The source-index publisher stalled while holding the `RocksSemanticInvalidation` monitor, which every semantic-revision read needs. Writers no longer stall (`RocksMemoryTest.aWriteDoesNotStallOnMemtablesHeldByOtherIdleDatabases`, which fails after 60 s without the fix). |
| 2026-10-01 | W9 | this commit | Disagreement on A8 "javac K": 22 units recompile for K = 20. The extra units sit in strongly connected components with an edited unit, where rule 2 and A6 require content binding. Separately, dependants of an edited module that was not rebuilt compile against its sources (a different context), and an edited Lombok module reruns its external processor once. All three follow from rules this document sets, so they are reported rather than worked around. |
| 2026-10-01 | W9 | this commit | A10 on the pinned project edits the reactor-root `lombok.config`. Every unit holds it, so all 977 recompile, which is the required result. Per-unit exactness is shown by `ProcessorBindingTest.lombokConfigEditRecompilesExactlyTheUnitsHoldingIt` (a package-level config). |
| 2026-10-01 | §95 | this commit | READY was reported over an empty index. Phase 9 made READY independent of the repository scan, which is right only when a complete index is already persisted. On a first start (and after an interrupted first scan, or a new index format) the daemon printed READY after ~1.5 s with the index still `discovering`: dependency answers were incomplete and the ~7 s, ~1.75 GB first ingest competed with the first requests (it was the CI hover-after-edit allocation rise; [edit-path-investigation.md](evidence/edit-path-investigation.md)). READY now waits for the first scan unless the store's active generation is this format's generation, i.e. a completed, validated, activated scan is on disk (`IndexStorage.scanCompleted`, `readiness.persisted_index_complete`). Breaking tests: `ReadinessGatingTest` (first start, warm restart, interrupted first scan). |

## Corrective pass (PR #55 review)

The review of B1 found a persistence soundness bug and repeated identity work. This section is the one
progress record for the corrective pass. Each checkpoint appends: start/end SHA, what changed and which owner
holds the information, the correctness argument and breaking tests, before/after work, allocation and latency,
failed approaches, and what still fails.

**Mandate.** Work out semantic information when it is needed. Keep useful results and enough evidence to
trust them. Maintain only what changes. Restore and query without working the same information out again.
Complete the supported lifecycle within bounded resources. Speed or hit-rate results count only once the
certificate fixes (checkpoint 1) pass. Certificates, oracles, tests, lifecycle operations and memory limits are
not weakened to get there.

**Subjects.**

| Label | SHA | What |
|---|---|---|
| B0 | `c8fcb9f9a2430be2d8588839c5d9c8acc808973b` | PR base (`main`) |
| B1 | `a66843185c407409b1ab9d1deffe108042ff00b0` | Reviewed head; start of this pass |
| F | (final) | End of this pass |

B1 → F attributes this pass; B0 → F shows the PR's total effect. An incorrect answer or timeout at any
subject is reported as incorrect or timeout, never as a latency.

**Effective configuration at B1.**

- Benchmark daemon: `-Xmx1024m`, `heap_ceiling_mb` 1024.
- Shipping launcher: no `-Xmx` (JVM default), `heap_ceiling_mb` 1024.
- RocksDB native budget: `jvmd.index.native_budget_mb`, default 64 MiB. It is held in one `RocksMemory` per
  `RocksIndexStorage`, and every database and generation of that storage shares it.
- RocksDB cache:
  - B0: `LRUCache(budget, -1, strict=true)` and `WriteBufferManager(budget/4, cache, allowStall=true)`.
  - B1: strict capacity and stalls are both off. The budget is a soft target there; nothing enforces it as a
    hard limit yet (gate M3).
- Index generation budget: `jvmd.index.generation_budget_mb`, default min(128 MiB, heap/8).

**Evidence register at B1.** Brief §1; status as found at B1.

| # | Status at B1 | Where |
|---|---|---|
| E1 | Still present | `PackageDeclarationCertificateTest`: 2 tests pin the defect (a comment's `com.old` bound as the package; stale clean restore). `aTypeAddedToTheDeclaredPackageInvalidatesTheRecord` is `@Disabled`. Run at B1: 3 tests, 0 failures, 1 skipped. |
| E2 | Still present | `AttributedMemos.consultedPackages` and `staticImportBindings` run regexes over raw text. `absenceIdentity` hashes `unreadable` (`AttributedMemos.java:318-335`). |
| E3 | Still present (not reprofiled) | `reactorRoot()` is called from `staticInputs`, `processorResources` and the `config:` resolve callback, with no shared value ([edit-path-investigation.md](evidence/edit-path-investigation.md)). |
| E4 | Still present (not reprofiled) | `drain()` runs Tarjan over the whole reachable graph every 256 captures or 2 s. Its edges call `foreignModule` (`relativize`) and build the `unknownSample` string for every node. |
| E5 | Still present | `memoize` sets `epoch=null`; `members(root)` builds a root-wide `TreeSet` per call. |
| E6 | Still present | `ATTRIBUTED = ("attributed-diagnostics", 5)`. The payload holds diagnostics, warnings, contribution and `p_diag`. A restore fills diagnostics, projections and dependencies; it restores no occurrence graph or query state. |
| E7 | Still present | `Analyzer.java:406` returns `deferred("requires-attribution")` for every consumer except `completion-range:`. |
| E8 | Still present | `RocksMemory.java:17,21`: `strictCapacityLimit=false`, `allowStall=false`. |
| E9 | Not yet re-run | Control: memory lifecycle on B0 and B1 (below). |
| E10 | Not yet re-run | Same control: restart after workspace use (M23–M26). |
| E11 | Unchanged | [machine-decision.md](evidence/machine-decision.md) has RocksDB and native-plus-accelerators columns. The minimal-native column is missing (gate E2). |

**Frozen harness (v1, this commit).**

- Restart, real-project and synthetic suites: `benchmarks/persistence.ts`, `benchmarks/compare.ts` and the
  `jvmd-before-after.yml` matrix as at B1.
- References/restart control: the original memory report's driver, `benchmarks/memory/profile.ts` and
  `probes.ts`, ported unchanged from `ccr-d4329637-lywgzk` (report `8f778fd`), with three additions:
  - `--heap 0` launches without `-Xmx`, for shipping defaults;
  - a summary printed to the job log;
  - the agent's `s`/`p` snapshot commands, merged into the current agent.
- Fixture: apache/maven `5cd1b60264101080c712accd605180a4bd9222e0`, built into its own repository.
- Run with: dispatch `jvmd-benchmarks.yml` with `revision=<subject>` and `memory_control=equivalent` or
  `shipping`.
  - `equivalent` is the original budget: 1 GiB heap, 64 MiB native.
  - `shipping` is the shipping defaults.
- M1 deadline, set before any F result: first-use references on `MavenProject.addAttachedArtifact` must return
  the oracle's two reference sites within **60 s**. That is the client deadline under which B0 answered 0 of 17
  attempts (E9). The driver measures the request to completion (up to 30 min), so a late answer is reported as
  late, not as missing.
- M2: after workspace use, the restarted daemon reaches READY and gives the first correct definition (M26) at
  both configurations.

**Controls.**

- Soundness (C1): `PackageDeclarationCertificateTest` at B1 reproduces the stale restore (E1).
- References/restart (M1/M2): the lifecycle above on B0 and B1, at both configurations. Runs are listed in the
  checkpoint 0 entry below once they finish.

**Gate checklist** (brief §11). Each gate is ticked only with the evidence the brief names.

- [ ] C1 Package certificate
- [ ] C2 Old memo rejection
- [ ] C3 UNKNOWN
- [ ] C4 Capture frontier
- [ ] C5 Projection sufficiency
- [ ] P1 Configuration work
- [ ] P2 Namespace work
- [ ] P3 Graph work
- [ ] P4 Result cutoff
- [ ] P5 Warm reads
- [ ] R1 Constructive restore
- [ ] R2 Readiness
- [ ] M1 References
- [ ] M2 Reopen
- [ ] M3 Bounded resources
- [ ] M4 Lifetime
- [ ] E1 Regression visibility
- [ ] E2 Storage honesty
- [ ] E3 Final integration

### Checkpoint 0: starting state and controls

- Start: `a668431` (B1). End: this commit.
- Changes:
  - The status record, the frozen control harness and the `memory_control` dispatch mode.
  - No production code changed.
- Correctness: the soundness control reproduces at B1 (above).
- Evidence: the B0/B1 references/restart control runs were dispatched after this commit; their results are added
  here when they finish.
- Still failing: every gate above.
- Control results:

  | Run | Subject | Budget | First-use references (60 s deadline) | Restart after workspace use |
  |---|---|---|---|---|
  | [36895099318](https://github.com/maxjay/jvmd/actions/runs/36895099318) | B0 | equivalent (1 GiB / 64 MiB) | `protocol_error` after 360 s: not timely | Fails at store open: `RocksDBException: Insert failed due to LRU cache being full` |

  | [36895104366](https://github.com/maxjay/jvmd/actions/runs/36895104366) | B1 | equivalent | `protocol_error` after 174.2 s: not timely | Reached: READY, then a correct first definition (2.2 s) |

  | [36895107922](https://github.com/maxjay/jvmd/actions/runs/36895107922) | B0 | shipping (no `-Xmx`) | Runner lost 5.5 min into references (shutdown signal, exit 143), probably host memory exhaustion | Not reached |

  | [36895111803](https://github.com/maxjay/jvmd/actions/runs/36895111803) | B1 | shipping | Daemon request timeout after 360 s; 62.6 GB allocated; heap 2.3–3.4 GB, RSS 4.0–5.2 GB; later queries and the edit's diagnostics time out, and the runner is lost after ~23 min | Not reached |

  B0's and B1's first hover also fail, at both budgets, with "Editor result exceeds 64 KiB" (present at B0, so not a PR regression; a hover correctness gap to fix under E3).

  B0 reproduces E9 and E10. At B1 the restart succeeds (strict capacity off) but references still fail
  (M1 open). The shipping-default runs are recorded when they finish.

### Checkpoint 1: certificate safety (stream A)

- Start: `22b904f`. End: this commit.

**What changed, and who now owns the information**

- **Package and imports (A1, C1).** `NamespaceResolutionProofs.Header` is read from javac's syntax tree:
  - the package;
  - single-type, on-demand and static imports;
  - `complete=false` for an erroneous name or a module import.

  The attributed task records the header of the unit it attributed (`Bindings.Snapshot.header`). The memo
  certificate's package entries, its static-import bindings and its negative-resolution plans all come from that
  header, and the three raw-text regexes are deleted.

  The live proof paths had the same defect since before this PR: the completion plans and the
  `document-namespace-proof` identity. They now use `NamespaceResolutionProofs.header(text)`, a javac parse
  cached by content (256 entries).

  A unit whose header is incomplete gets no record (`header-unsupported`).

  Names that failed to resolve now come from javac's structured diagnostic arguments
  (`CompilerPool.Problem.names`, from `JCDiagnostic.getArgs()` and the nested `compiler.misc.location`), not
  from the rendered message. A `doesnt.exist` without its package argument refuses the record
  (`negative-unproven:unstructured-diagnostic`). `names` is evidence only: it is not serialised and not part of
  `Problem` equality.
- **Function version (C2).** `attributed-diagnostics` is now v6. The version is part of the static key's
  identity, so a v5 record can never be looked up. Other functions are unaffected: S0 namespace records are
  still reused.
- **UNKNOWN (A2, C3).** `ObservationFaults` separates an established absence (`NoSuchFile`, or a path through
  a regular file) from a failed observation, which throws `Unavailable`. Changes:
  - `absenceIdentity` no longer hashes `unreadable` (`source-absence-v5`).
  - Class-directory presence checks no longer treat an I/O error as "absent" (`Files.isRegularFile` and
    `isDirectory` did).
  - A member whose hash cannot be read no longer drops silently out of a package.
  - Live membership is used only while the live state is trusted; otherwise the directories are read
    directly.

  At capture a failed observation refuses the record (`observation-unavailable`); at restore it is a miss
  with that reason.
- **Capture frontier (A3, C4).** Before, a capture used `epoch=null` and read disk and editor state after
  attribution. Now it reads through the epoch of its own compiler transaction, the live snapshot that javac's
  reads were validated against. Dependency content comes from that snapshot too, not from a later disk hash.
  The capture ends with the strict transaction fence (`CompilerInputs.Snapshot.transactionCurrent`, which
  checks the input epoch and so also catches A→B→A). If the fence fails, there is no record
  (`inputs-superseded`).

  The epoch is keyed on both the observed snapshot and the configured context, because a reconfiguration can
  reuse the snapshot object.

  Processor resources (`lombok.config`, JPA XML) are read inside the transaction but are not in its live state,
  so the fence cannot see them. Their file stamps (size, mtime, ctime, file key, or absence) are recorded on the
  owner thread just before the compiler transaction (`AttributedMemos.beforeTransaction`). A capture binds a
  resource only if its stamp is unchanged; otherwise there is no record (`processor-resource-superseded`, or
  `processor-resource-unproven` when no stamp was recorded).
- **Projection sufficiency (A4, C5).** `DiagnosticProjectionSufficiencyTest` gains these mutation kinds:
  - overload added (`long`, boxed, fixed arity next to varargs);
  - inherited members (overridden method made final, abstract member added, inherited method removed, field
    hiding);
  - nestmate access (nested constructor or field made private);
  - default methods (removed, made abstract);
  - a static method made instance.

**Breaking tests**

- `PackageDeclarationCertificateTest`:
  - comment, string, text-block and Unicode-escape headers;
  - explicit, on-demand and static imports;
  - nested and multiple top-level types;
  - incomplete source and module imports;
  - the comment-shadowing regression, now enabled: after restart it returns `cant.resolve`;
  - v5 records miss while S0 records are reused.

  The two tests that pinned the defect at B1 were replaced; their history is in the checkpoint 0 register (E1).
- `UnknownObservationCertificateTest`, using injected failures:
  - an unreadable root at capture gives no record, two failures never hit, and the unit is reused once readable;
  - an unreadable root at restart misses, then hits once readable;
  - an unreadable dependency misses;
  - a corrupt record misses and is rewritten;
  - only an established absence counts as negative.
- `DiagnosticProjectionSufficiencyTest`, 2,000 seeded mutations: 0 mismatches. Every new kind changed P_diag in
  every case, and body or neutral edits never did.
- `CaptureFrontierRaceTest`: the capture is held at a barrier while another thread edits a dependency (A→B),
  edits and reverts it (A→B→A), or opens a new buffer in the unit's package (a membership change). Each must
  refuse, and a run with no edit must write. With the fence removed, the three race tests fail and the no-edit
  run still passes.
- `ProcessorBindingTest.aLombokConfigEditedDuringCaptureIsNotBoundToTheEarlierResult`: `lombok.config` is written
  at the capture barrier, so the unit has no record and the other units' records hold.

**Still open in stream A**

- Processor-dependent sufficiency cases are not in the differential corpus.
- Relocation with processors that observe physical paths is not re-audited.

### Checkpoint 2: ownership table (stream B; written before the code changes)

Each row says who owns an expensive quantity at B1 and who will own it. "Frontier" is the input state the value
is valid for.

| Quantity | Owner at B1 | Key | Producer | Invalidated by | Completeness | Frontier | Persisted | Consumers | Change |
|---|---|---|---|---|---|---|---|---|---|
| Reactor root, logical source mapper, normalised roots, root roles, per-class directories | None: `reactorRoot()` walks every coordinate on each call; `LogicalSources.of(context)` is rebuilt on each capture and restore | Configured `Analyzer.Context` (immutable) | Derivation from coordinates and source roots | `configure()` replacing the context | Total for a context | Context identity | No (logical slots are in the static key) | Static key, `processorResources`, `config:`, `logical-*:` and `reactor-class:` resolution, `perClassDirectories` | Derive once per context instance; keep a derivation counter |
| Processor binding, configuration resource identities | `StaticInputs` once per epoch (`static-inputs`); `configIdentity` through `FileStateRegistry.hash` (stat-validated leaf) | Context + epoch; file stamp | `StaticInputs`, `FileStateRegistry` | Context change; resource file stamp | Absent or unreadable distinguished (C3) | Epoch, file stamp | Stamps in the inventory journal | Static key, `config:` entries | Binding per context; resources stay file-stamp leaves |
| Source membership | `LiveSourceState` (live tree). `AttributedMemos.members()` built a root-wide `TreeSet` per call (capture) or per epoch (restore) | Root + live snapshot | Live tree, or a directory inventory when untrusted | Live transitions | Trusted flag | Live input epoch | Inventory journal | Package lists, absence identities | Read the live owner's package index; no root-wide set per memo |
| Per-file S0 namespace | `SourceNamespaces` (LOCAL memo on content and mode) plus `AttributedMemos.namespaceCache` (file → hash, value), filled on the writer thread | Content hash + language mode | javac parse (`--source`) | Content or mode change | PARTIAL on syntax errors | Content | Yes (`s0-namespace` v1) | Package aggregate | One cache keyed by content and mode; parse at most once per pair |
| Package namespace | None: `packageFiles()` lists the members and `packageIdentity()` hashes every member's S0 on each write and each restore | Scope + package | Per-call listing and hash | Any member's addition, removal or S0 change | Unknown when a member is unreadable (C3) | Epoch | No | `package:` entries of every record | Maintained direct-package aggregate with an S0 identity per member, updated per changed file |
| Per-unit `P_diag` | `AttributedMemos.projections` (path → content hash, P_diag) from attribution or restore. Each dependant also recomputes every dependency's P_diag in its own task | Content consumed + projection version | `DiagnosticProjection.of` | Content change | Total for attributed units | Transaction | Inside the record | `logical-unit:` entries, early cutoff | Reuse the established leaf for the same content |
| Source SCCs / condensation | `drain()`: Tarjan over the whole reachable graph per batch (256 captures or 2 s); `knownDependencies` | Pending set + dependency sets | Tarjan | New captures; dependency changes | A component is final when nothing it reaches is unknown | Process | No | Record writes (SCC peers bind content) | Settled components are kept and not revisited; visit counters |
| Ordered classpath and search identities | `StaticInputs.slots` per epoch; `ClasspathSequence` in the analyzer | Context classpath + content identities | `StaticInputs` | Classpath or artifact change | Total | Environment epoch | Logical slots in the static key | Static key | Per context and environment |

**P1, configuration work (done).**

- Change: `AttributedMemos.configured()` derives the reactor root, logical source mapper, normalised roots and
  per-class directories once per `Analyzer.Context` instance. `configure()` replaces the context on any
  configuration change, which is the one invalidator. Status: `attributed_memo.config_derivations`.
- Breaking test: `ConfigurationWorkCountTest`. 60 chained captures in one context give 1 derivation; 60 restores
  with their dependency entries give 1; a reconfiguration adds exactly 1.
- Before, the coordinate table was walked for every memo, entry and `config:` lookup.

**P2, namespace work (done for the memo path).**

- Membership:
  - A root's `.java` members are enumerated once per live owner (`liveMembers`).
  - A later input snapshot applies only the paths the live journal reports changed since the previous
    snapshot (`LiveSourceState.changedPathsSince`); the root is rebuilt only when the journal cannot answer.
  - Unsaved buffers outside the live state are overlaid on a copy, never on the shared set.
  - An untrusted live state is still read directly (C3).
- Package identity:
  - A package's S0 identity is built at most once per package, input snapshot and language mode
    (`currentPackageIdentity`). Capture and restore share it, and it is built on the owner thread from the
    transaction's snapshot.
  - The writer thread no longer reads documents or parses S0; it only encodes and stores (also A3).
- S0 cache: `namespaceCache` is keyed by content hash and language mode (before, by content alone).
- Status: `attributed_memo.membership_enumerations`, `membership_updates`, `package_identity_builds`,
  `source_namespaces.parses`.
- Breaking test: `NamespaceWorkCountTest`.
  - 41 captures in one snapshot: 1 enumeration, at most 2 identity builds (p, q), at most 41 S0 parses.
  - After one body edit: no new enumeration, at most 2 membership updates, identities rebuilt once for the new
    snapshot (not per memo), and exactly 1 new S0 parse.
- Not done: package queries from the editor paths (`LiveSourceState.sources`, which keeps one source per binary
  name and so loses duplicates across roots) are unchanged. The memo path still lists each root separately, so
  duplicates across roots stay visible.

**P3, graph work (done).**

- Change: `drain()` keeps settled components and does not traverse them again.
  - A component is settled only when it is final and every successor outside it is settled or a fixed sink
    (outside the compiler roots, or another module). Everything a settled node reaches is therefore settled or
    a sink.
  - Any change in a node's relevant dependency set (in-root, non-self) unsettles that node and, through reverse
    edges, every settled node that reaches it. The change can come from `Analyzer.resolveContribution` (the one
    point that sets contributions), a capture, a write or a restore. Merges, splits and edges to formerly unknown
    nodes are therefore re-examined. A context change clears the settled state.
  - Components and finality are kept per node; before, a `HashMap` keyed by the component sets hashed each
    set on every lookup.
  - The per-node debug string is gone (`scc_unknown_sample` keeps only the path), and `foreignModule` is cached
    per unit and context.
- Status: `attributed_memo.scc` (drains, vertex_visits, edge_visits, settled_reuses, invalidations, settled).
- Breaking tests: `SccWorkCountTest`.
  - Chain, hub, layered and random-DAG topologies, drained after every capture: cumulative vertex visits are at
    most V and edge visits at most E (B1's drain revisited O(V²) on the chain).
  - A two-unit cycle is written as one component.
  - A settled pair that later becomes a cycle is unsettled: the new record binds its peer by content, and a
    body-only edit of the peer recompiles it.

### Checkpoint 3: owner propagation (stream C)

**P4, result cutoff on the production owner path.** Production code is unchanged here; this is evidence of
how the existing path behaves, with tests.

- Live path: an edit resolves the new contribution (`Analyzer.resolveContribution`). `SemanticUpdatePolicy`
  compares the API result and the names consumers actually use, and only an API change that a consumer uses
  reaches it. That consumer's diagnostics and compiler caches are invalidated. On its next read it is
  re-attributed, its own API is compared again, and propagation stops at the first equal result.
- Restored path: a restored record's `logical-unit:` entry compares the dependency's current P_diag
  (`currentProjection`), restoring or attributing the dependency first. An unchanged P_diag restores the
  consumer without javac.
- Deferred consumers:
  - Source-attribution consumers and document contexts (except completion ranges, which are re-evaluated from
    their leaves) are deferred: they need javac.
  - The continuation is owner-side. A deferred source consumer's file joins `affected` (diagnostic store and
    compiler caches invalidated); a deferred document consumer's cached document semantics are removed. The
    next read for that file therefore recomputes, and nothing serves the pre-change answer.
- Breaking tests: `OwnerCutoffTest`. Each answer is compared with a fresh analyzer's diagnostics for the same
  sources.
  - An unrelated member change: B and C are not recompiled.
  - An overload change that B uses: B is recompiled and now reports `cant.apply`; C is not recompiled.
  - An unequal-depth diamond: evaluated once.
  - Restored consumers after a relevant change: B is reconsidered; C is cut off at B's equal P_diag.
  - Restored consumers after an unrelated change: C stays restored.
  - A deferred completion context: the next request offers the new member.
- Limits:
  - The restored path is coarser than the live one. P_diag is a dependency's whole diagnostic-relevant API, so
    a restored consumer that names A is reconsidered on any change to A's API, while the live path matches
    the names used.
  - Document contexts other than completion ranges are not re-evaluated during propagation; they wait for the
    next read.

## Known limits

- A unit whose javac diagnostics name another file (`foreign-diagnostic-file`) is refused.
- When a dependency module's sources are newer than its class output (a branch switch without a rebuild), dependants
  compile against its sources. That changes their context and static key, so those units are recompiled once.
- An edited Lombok module reruns its external processor (one compiler run over the module).
- The 160-unit linear chain in [restart-baseline.md](evidence/restart-baseline.md) is the first pass's evidence and is
  not headline evidence (strict task, "do not").

## First-pass record

The first pass's phase-by-phase notes, materialisation audit (§112–113) and definition-of-done list were replaced by
this page; they are in the git history of this file at `fd72563`. Their evidence files remain in
[`docs/evidence/`](evidence/): [machine-storage.md](evidence/machine-storage.md),
[classpath-routing.md](evidence/classpath-routing.md), [stratification.md](evidence/stratification.md) and
[restart-baseline.md](evidence/restart-baseline.md).
