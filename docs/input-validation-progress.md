# Input identity consolidation — current checklist

Baseline: `9ac81c4bdded8de18537569debf02e789c24fe27` (main, 2026-09-21).
Branch: `refactor/input-identity-validation`. Neither predecessor is stacked.
This file is the current implementation checklist for task 4; earlier progress entries
remain historical evidence, not competing instructions.

- [x] Identify merged predecessors and exact baseline before production edits.
- [x] Verify the three #18 regressions on the baseline.
- [x] Record baseline validation and end-to-end evidence.
- [x] Implement the shared observation/snapshot contract.
- [x] Cut consumers over and delete replaced paths.
- [x] Cover races, overlays, membership, environment, reconciliation and eviction.
- [x] Run serial alternating measurements and existing correctness suite.
- [x] Record additions/deletions, limitations and tested head.
- [x] Open PR and record remote CI state.

## Ownership inventory from production callers

| Area | Current responsibility | Duplication / replacement |
|---|---|---|
| FileStateRegistry | Content hash with ctime/inode validation, bounded observations | Retain as shared disk observation; add tracked membership and counters |
| Documents | Session overlays and their hashes, source hash fallback | Retain session isolation; supply identified text and tracked snapshots |
| WorkspaceBindings | Source enumeration callback, private stamp/hash LRU, classpath scans, copied maps, ValidationToken | Delete private hash cache, identity recipe and persisted-root fast-token authority |
| Analyzer | Classpath/environment string recipe, namespace enumeration, sourceIdentities maps before/after javac, completion identity recipe | Consume typed source/membership/environment snapshots; semantic policy still decides dependency invalidation |
| CompilerPool | Compiler-thread ownership and lifecycle, request-scoped validation memo | Retain lifecycle; use shared freshness observations, compare full effective configuration |
| IndexedFileManager | Separate binary stamps, class/source watchers, source discovery catalog | Replace freshness/discovery with shared observations; keep javac package catalogs and byte retention |
| RocksWorkspaceState | Re-enumerates sources/hashes overlays, composes environment in module fingerprint | Shared input collection/environment composition; retain persisted Merkle encoding and incremental ancestor updates |
| LocalArtifacts | Private stamp/hash LRU, walks source/output trees, artifact recipe | Shared disk observations and inventory; artifact output identity remains a distinct role |
| Application/context construction | Finds source roots, compiler options, processor/generated inputs; async source publication | Preserve actual compiler configuration; remove persisted publication from live validation |

Distinct checks: source/output timestamp ordering in LocalArtifacts protects stale build
outputs; binary-byte checks protect an already loaded JAR; diagnostic dependency checks
protect previously accepted semantics. None is interchangeable with a whole-workspace hash.

## Checkpoints

2026-09-21: inspected current main and #17/#18 status. #18 includes declarations-only
ownership, commit-before-owner replacement and independent read leases. Baseline regression
execution started before any production changes. Initial harness deliberately separates
identity validation from javac and detached navigation-result construction.

Shared contract checkpoint: CompilerInputs now separates source hashes, membership,
compiler environment and an in-flight observation fence. FileStateRegistry retains
validated content and directory observations; unsupported metadata forces reconciliation.
Session Documents retain overlay ownership. The preliminary focused run passed 42 tests
(39 integration tests plus 3 Rocks workspace tests). Full phase 3/4 exposed two consumer
regressions and an obsolete hash-count assertion; fixes and the full rerun are in progress.

Consumer cutover checkpoint: removed WorkspaceBindings' private hash LRU/ValidationToken,
Analyzer's independently constructed source maps/environment recipes, IndexedFileManager's
two WatchServices and stamp policy, and LocalArtifacts' hash LRU. CompilerPool now owns the
module's input snapshot and validates all javac results before returning them. Navigation
validates before committing its fact batch and retains committed owner metadata on failure.
Rocks keeps its existing directory Merkle algorithm, using shared input/environment evidence.
Publication carries the analyzed hash; persisted roots no longer authorize live cache reuse.

The full phase 3/4 run passed before the final metadata-call reduction. Preliminary paired
measurements found zero warm directory enumerations, content hashes and identity-map rebuilds
in the candidate; baseline had 120 source and 80 classpath enumerations and 120 identity-map
rebuilds across 40 paired observations. The stronger initial JDK content check costs ~150 ms
cold in this fixture. Final evidence must be rerun at the committed consumer head.

Portability follow-up: providers without Unix change-time evidence must rehash, but an
unchanged fallback hash must not manufacture a new in-flight observation on every read.
Implicit javac source reads now verify their actual text against the captured input snapshot.
This avoids accepting bytes from an intervening version even when timestamp evidence is
unavailable. The last run is superseded by the final validation below.


Final measured checkpoint (supersedes preliminary guidance above): production head
`5fae72760a4486e9b914c821aa6cdd25aa7b5212`, published tree-equivalent head
`9f14f41d2cd1bf0672927059a10d3fbea2e0c4b8`. Baseline 123 tests and candidate 132
phase 3/4 tests passed; final 9 input and 3 Rocks tests passed. The existing LSP
matrix verified 504 responses / 3,120 ranges. Three alternating paired repetitions
and raw source/dependency fingerprints are committed with the delivery report.
Warm input validation p50: 2.423 → 2.109 ms; 40 warm observations perform zero
source/classpath enumerations, hashes and map rebuilds. Real diagnostics p50 and
allocation regress; end-to-end measured work +7.1%, restart +8.3%. Production diff
is +442/-417 (net +25), not shrinkage. See
[the current report](performance/2026-09-21-input-validation.md) for full evidence,
reproduction and limitations. Production is frozen after this measured checkpoint.

Delivery: [PR #21](https://github.com/maxjay/jvmd/pull/21) is open, unmerged.
GitHub Checkpoints, Distributions and Merge Review started; results are pending.
Main advanced during measurement with reporting-only #20 (`6129dbf`); it was
merged into this branch and reporting tests rerun. Measured production is unchanged.

Review repairs checkpoint: addressed all four PR #21 findings. Compiler environment
inventories follow directory links (including package links and target recreation),
with cycle rejection; ordinary source discovery stays no-follow. Added source,
module-source, legacy bootstrap/extension/endorsed path options, including inline
-Xbootclasspath and module path patterns. The shared directory cache now evicts
least-recently-used root/suffix/mode inventories above 128; revisiting an evicted
root reconciles without clearing accepted input/semantic state. Baseline benchmark
compilation now uses the base checkout's own runtime dependencies and manifests
record their hashes. Regression and benchmark verification follows this checkpoint.
Prior head c3ccd8cf passed all three GitHub workflows (Checkpoints, Distributions,
and Merge Review), including the hosted JDTLS comparison.
