# File semantic contribution consolidation

Source of truth: this consolidation branch and the current implementation task. Superseded architecture, progress, smoke, index-redesign, and historical performance narrative documents have been removed from this branch.

Base: `03591aeef674b67f131065048a50543d78aca855` (current `main` at branch creation)
Branch: `refactor/file-semantic-contribution`
Validated production head: `c3e23e9ee4c0a4b19e3ce1a58e40600b9e63373d`

## Objective

Consolidate the duplicated per-file persistence/invalidation payload behind one authoritative typed
`FileSemanticContribution`, and delete the parallel semantic representations rather than layering
another model on top.

The canonical contribution owns:
- normalized source file identity;
- source/content identity;
- API identity;
- dependencies;
- exported names;
- unresolved names.

This phase deliberately does not redesign navigation postings or the physical source index. Those are
separate changes with separate benchmarks.

## Result

The production path now constructs one `FileSemanticContribution` in the analyzer and carries that
same value through asynchronous source publication into Rocks persistence/invalidation.

Deleted ownership:
- `RocksSemanticInvalidation.FileInput`;
- private `RocksSemanticInvalidation.Stored`;
- Delta -> FileInput conversion;
- Stored -> FileInput conversion;
- the public full-module semantic mutation surface used only by the old representation/tests;
- the partial/index-only semantic publication mode and blank sentinel state.

The Rocks codec serializes/deserializes the canonical contribution directly. Incremental
`observeFile` also loads the module semantic map once rather than loading it and then immediately
loading it a second time through the old bulk-update path.

One remaining limitation is explicit: invalidation still materializes the module's persisted
contributions once to rebuild reverse dependency/unresolved views. This phase removes duplicate
ownership and the duplicate load; replacing the remaining O(module) derived-view rebuild is a
separate storage/index change, not silently bundled into this consolidation.

## Production size

Exact same counting rule on `src/main/java/**/*.java`:

| revision | files | physical LOC |
| --- | ---: | ---: |
| base `03591aee` | 107 | 12,048 |
| consolidated code head `c3e23e9e` | 108 | 12,044 |

Net production change: **-4 physical lines** despite adding the named canonical type.

The more important structural count for this payload is:

```
before: SourceIndexPublisher.Delta fields
        + RocksSemanticInvalidation.FileInput
        + RocksSemanticInvalidation.Stored

after:  FileSemanticContribution
```

Authoritative semantic payload representations: **3 -> 1**.

## Before benchmark

The branch was opened production-identical to main (only this progress document differed), so the
normal PR Checkpoints suite established the before measurement before any production edits.

Baseline Checkpoints: run `35612641135`, **success**.

128-file `WorkspaceBindingsIncrementalBenchmarkTest`:

| operation | phase | request ms | binding computations | javac queries |
| --- | --- | ---: | ---: | ---: |
| references | cold | 329.643 | 128 | 4 |
| references | warm | 3.735 | 0 | 0 |
| references | body edit | 29.782 | 1 | 1 |
| references | API edit | 70.984 | 17 | 2 |
| rename | cold | 201.330 | 128 | 4 |
| rename | warm | 3.595 | 0 | 0 |
| rename | body edit | 24.151 | 1 | 1 |
| rename | API edit | 56.948 | 17 | 2 |

## After benchmark and correctness

Code-head Checkpoints: run `35614081275`, **success**.
Code-head Distributions: run `35614081317`, **success**.

The same 128-file benchmark on the consolidated code:

| operation | phase | request ms | binding computations | javac queries |
| --- | --- | ---: | ---: | ---: |
| references | cold | 595.759 | 128 | 4 |
| references | warm | 2.335 | 0 | 0 |
| references | body edit | 20.861 | 1 | 1 |
| references | API edit | 39.672 | 17 | 2 |
| rename | cold | 129.660 | 128 | 4 |
| rename | warm | 2.697 | 0 | 0 |
| rename | body edit | 27.861 | 1 | 1 |
| rename | API edit | 31.352 | 17 | 2 |

The deterministic work invariants are unchanged:
- unchanged warm query: **0 binding computations, 0 javac queries**;
- body edit: **1 file, 1 javac query**;
- API edit: **17-file dependency closure, 2 javac queries**;
- both references and rename preserve those counts.

The latency samples are single CI observations and move strongly in both directions (for example,
references cold is slower while rename cold is much faster). They are recorded, not treated as a
statistically supported speedup/regression claim. This phase is accepted for ownership/complexity
consolidation, not a latency claim.

## Commits

- `5c04273d` — define this consolidation scope from current main.
- `ccde6696` — introduce the canonical contribution and remove Rocks duplicate payload types.
- `9b2af2fb` — move focused invalidation tests onto the canonical contribution.
- `dad30cc8` — remove duplicate semantic mutation/compatibility surfaces.
- `c3e23e9e` — require complete semantic publications and remove partial sentinel state.
- `6411bdf2` — record before/after benchmark and validation evidence.
- `887ad015` — delete superseded progress/design/smoke/performance narrative documents and clean surviving user-facing links.
- `f67e282a` — remove the last stale benchmark-report references and repair the benchmark README prose.

## Acceptance

- [x] Started from exact current main.
- [x] Benchmark captured before production edits.
- [x] One authoritative per-file semantic payload replaces parallel representations.
- [x] Focused invalidation semantics preserved.
- [x] Warm/body/API compiler-work invariants preserved.
- [x] Full Checkpoints pass on the production code head.
- [x] Cross-platform Distributions pass on the production code head.
- [x] Production Java LOC decreased.
- [x] Remaining module-wide derived-index rebuild is explicitly left for a separately benchmarked task.
- [x] Superseded implementation/progress/design/performance narrative documents removed; surviving docs are user/installation/integration/dependency or executable benchmark documentation.
- [x] Final Markdown tree contains 9 files and zero references to the deleted documents.


## 2026-09-21 — Rocks storage cutover

Completed on `refactor/rocks-storage-cutover`, from main `06c47be1c755a8f23f88d81f0fe2980813518122`.

- [x] Remove runtime SQLite/shadow selection, comparison counters, cursor translation, and SQL row decoding from `IndexService`.
- [x] Move SQLite, its schemas, and JDBC dependency into the test-only historical reference; switch production contract/search/workspace fixtures to Rocks.
- [x] Replace the optional generation sink with `IndexStorage` lifecycle ownership and focused inventory, semantic-state, and admission services.
- [x] Make `RocksIndexStore` the sole content-plus-artifact-manifest publisher; remove duplicate binary/code/local/JDK publication and the second documentation pointer.
- [x] Remove the unused production shadow workspace resolver. Retain Rocks format validation, activation, previous-generation recovery, pins, and pruning.
- [x] Build, run 40 Rocks unit tests and 58 selected integration tests, inspect distribution contents, and pass the distribution publication/query/reopen smoke test. No failures remain. An initial navigation failure also occurred on baseline and was resolved by building the missing Maven 3 resolver executable.
- [x] Run the existing `RepositoryUpdateBenchmark` against baseline and cutover: three serial alternating repetitions, eight JARs, 10,400 symbols, isolated 1 GiB JVMs. Semantic hashes agree throughout. A baseline-written store reopens with the cutover without rebuilding artifacts.

Production Java across index and Rocks modules: **5,141 → 3,895 lines (−1,246)**. Of these, 761 SQLite implementation lines moved to tests. `IndexService`: **415 → 325 lines**. The implementation commit is a net repository reduction of 775 lines, including test/doc changes and treating moved files as moves.

Eight new JARs now cause **8 repository publication calls instead of 16** (baseline: 8 new + 8 reused; cutover: 8 new + 0 reused). This removes overlapping orchestration; it does not claim the old implementation wrote each artifact twice.

Median measurements, in milliseconds:

| Measurement | Baseline | Cutover |
| --- | ---: | ---: |
| Fresh open + scan | 865.05 | 854.78 |
| Warm unchanged scan | 7.58 | 6.17 |
| Restart open + scan | 481.09 | 485.43 |
| Replace release scan | 154.78 | 157.86 |
| Add JAR scan | 56.65 | 64.67 |
| Delete JAR scan | 5.44 | 5.45 |
| Warm unchanged type-query p50 | 0.675 | 0.471 |
| Warm unchanged member-query p50 | 1.681 | 1.988 |

Timing is mixed; these small-fixture measurements support no blanket speedup claim. This is an ownership/deletion change. Full scenario samples, environment, semantic hashes, revisions, and publication counts are in [the benchmark evidence](docs/performance/2026-09-21-storage-cutover.json). Compatibility and status changes are documented in [storage.md](docs/storage.md).

Reproduction uses `benchmarks/index-updates/RepositoryUpdateBenchmark.java` compiled against each revision's core/index/Rocks classes and runtime dependencies (SQLite JDBC only for baseline). Invoke `fixture`, then `seed`, `updates`, and `restart`, each with arguments `<operation> <repository> <state> <output.json> 8 100 10 true`; use `java -Xmx1024m --enable-native-access=ALL-UNNAMED`. Restore the same pristine fixture path and create independent state for each repetition, alternating revision order. For compatibility, run baseline `seed`, then cutover `reopen` against that same repository/state. OS caches were not flushed.

## 2026-09-21 — Shared semantic update policy

Implemented from main `a0fa429` in `ba95dd7` on `refactor/semantic-update-policy`.

- [x] Preserve `FileSemanticContribution`; centralize API/body/namespace classification, unresolved matching, dependency closure, complete replacement, deletion, and pending resolution in `SemanticUpdatePolicy`.
- [x] Reuse PR #11's semantic-postings implementation from `0fad49d`, adapting it to the shared policy. The unrelated local-source lookup optimization in #11 is not included. The PR remains open.
- [x] Remove `WorkspaceBindings.reverseClosure()` and its API comparison. Add/delete updates use shared decisions; compiler-environment identity checks still require a full cache rebuild.
- [x] Remove analyzer-owned API fingerprint, pending API, and conditional-by-file maps and `DiagnosticStore`'s unresolved-file sweep. `Dependencies.recordFocused()` explicitly supplements dependency facts; full contributions replace all prior edges and unresolved facts.
- [x] Module actors exchange detached complete contributions. Compiler objects remain on their existing owner threads. Initial attribution seeds facts without invalidating callers already analysed against those sources.
- [x] Reject incomplete source-publication deltas; focused and failed results cannot replace the authoritative contribution. Publication identity includes dependency/export/unresolved facts so unchanged source/API hashes cannot suppress their replacement.
- [x] Persist complete diagnostic contributions in diagnostic snapshot schema 3. Older diagnostic snapshots are cache misses and reanalyse; Rocks canonical contributions and postings retain their existing encodings. Contributions, postings, and invalidation revisions now commit in one Rocks write batch.
- [x] Pass 48 targeted tests with a 1 GiB test JVM, including live/verified diagnostics, multi-module API changes, body-only reuse, focused/full edge replacement, unresolved errors, navigation, cycles, deletion, and restart. A store seeded by main also passed migration, affected-file, and revision checks.
- [x] Record three serial alternating repetitions of the same 1,000-file storage microbenchmark against main and the implementation. Median-of-run p50: body update **2.894 → 0.041 ms**, API update **2.911 → 0.053 ms**. Median-of-run p95: body **3.631 → 0.183 ms**, API **3.509 → 0.148 ms**. These gains chiefly come from #11's postings; no editor latency or whole-corpus allocation claim is made.

Production Java is **+170 net lines** (410 added, 240 deleted), including the imported postings and explicit update contract. This consolidates policy ownership but does not yield a net production line reduction in this step. Live unresolved matching still scans actor-local canonical contributions; persisted matching uses #11's postings. Errors without a stable javac target use the explicit `*` unresolved marker, consistently across consumers.

The baseline runtime tree was verified identical to main. JDK: Temurin 25.0.4.1; 1 GiB maximum heap. Initial local checks needed a fresh JDK, the Maven 3 resolver executable, and Maven on PATH; after repairing the toolchain, all selected checks passed without weakening assertions. The initial-attribution cache regression found by `BatchDiagnosticsTest` was fixed in the shared policy.

[Raw samples and scope](docs/performance/2026-09-21-semantic-policy.json). [Reproduction](benchmarks/semantic-policy/README.md).

### Checkpoints follow-up — completion discovery race

Checkpoints run 35653852144 failed only `CompletionPrefixCacheTest.changedReleaseAndNewSourceNamesCannotReuseOldCandidates` in Phase 4. The retry after an unresolved receiver flushed javac but retained the source inventory until a WatchService create event arrived. Added a deterministic regression that suppresses event delivery: it fails before the fix and passes after it. Empty completion results now invalidate the source inventory for the next attempt; successful prefix-cache hits keep their existing fast path. The complete Phase 4 group passes locally: **79 tests, 0 failures/errors**, without changing the checkpoint or weakening assertions.

## 2026-09-21: Separate fact lifetime from navigation results

Base: main `563ecc4`, including the Rocks cutover and shared semantic policy.
Branch: `refactor/indexed-semantic-read-view`.

- [x] Reproduce aggregate rejection losing reusable file state.
- [x] Store detached file facts independently of bounded decoded caches.
- [x] Replace aggregate admission/JSON sizing with pinned indexed read views.
- [x] Use per-owner binary records and postings for persistent local-source facts.
- [x] Migrate legacy source JSON atomically and preserve SCIP/numeric identities.
- [x] Move source precedence, deduplication, expansion and pagination into a common workspace read view.
- [x] Check zero-budget reuse, old-view isolation, replacement/deletion, stale binary-edge masking, migration, and query/edit behavior.
- [x] Run before/after measurements and preserve raw samples and reproduction.

Production implementation: `20ad41cd25c55a96aaa2e3733012e2290f2bc63b`.
Its tree matches the locally tested tree exactly. Production Java is **+350 lines** overall;
`Application` loses 55 lines. This removes lifetime/query ownership duplication but is not a net
line-count reduction: the indexed fact engine, codec and read-view contract replace in-memory maps
and JSON rather than simply deleting functionality.

On the isolated 128-file zero-budget workload, 31 requests load **3,968 → 128 files**, with warm
request medians **5.407 → 0.870 ms**. Cold construction slows **258.19 → 413.65 ms**. Below the old
admission threshold, warm times are **0.746 → 0.816 ms**. For 3,072 local symbols, warm prefix search
is **3.478 → 0.185 ms** and exact-name search **3.603 → 0.320 ms**. Source publication and disk
space increase; see the report for costs, limitations, tests and reproduction.

Evidence: `docs/performance/2026-09-21-semantic-state.md` and `docs/performance/semantic-state/`.
Harness: `benchmarks/semantic-state/`. Full PR CI is separate from the local targeted checks.

### Review follow-up — declaration ownership, transactional owners, and read leases

Combined search now masks dependency results only when the live view owns a declaration, using
`declaration/<scip>` postings. A real JAR/application regression warms bindings through a call to
a dependency class and verifies combined search still returns that class exactly once.

Full rebuilds retain the committed owner inventory until the fact batch commits. Replacement
metadata is published immediately after commit, before view construction or validation can fail.
The regression changes context, removes a file, throws from the loader, and successfully retries;
removed-file facts disappear from the new view while the old pinned view remains readable.

The cache retains revision metadata; each `get`/`peek` returns an independent caller-owned lease.
Application closes its leases and uses a separate revision identity for index reuse. Tests cover
close/reacquire through both validation paths and both peek APIs, overlapping leases, idempotent
close, and reuse without another loader call at zero decoded budget. The benchmark harness also
closes leases when the tested revision supports them.

Validation: **45 tests, zero failures/errors/skips** across fact lifetime, source overlays, index
contracts, pagination, navigation, rename, and semantic protocol coverage. Temporarily restoring
the two original correctness defects made both new regressions fail. Existing performance samples
remain attributed to their recorded revision; they are not measurements of this follow-up.

### Main integration and PR review responses

Merged main `4d90be5` (previous-PR benchmark comparison) into the branch. The Merge Review
job passed both benchmark stages and failed in the previous-PR comparison stage; the merged
checkout now contains the `trend.py` script used by that workflow step.

Updated the exact module dependency contract for `dev.jvmd.dist` and added explicit descriptor
coverage for `dev.jvmd.index.rocks`, including the existing javac-internals boundary checks.
The other review findings (declaration masking, aborted rebuild ownership, and synchronized
lease registration) were already fixed in `667d435`.

Validation: ModuleArchitectureTest, SemanticFactLifetimeTest, and DependencyFindPaginationTest
pass (10 tests); benchmark trend, harness, and dashboard tests pass (10 tests). A broader local
Phase 1 attempt ran 17 tests, with five environment/prerequisite failures: the runtime image and
AOT training were not assembled, and this executor rejects Unix sockets. These are not reported
as passing; CI remains the complete runtime gate.
