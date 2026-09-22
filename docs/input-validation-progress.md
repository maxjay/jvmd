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

Review verification completed: 135 phase 3/4 tests passed. Three alternating
component repetitions retain zero warm enumeration/hash/map-rebuild work; body/API
queries stay 1/2. Updated raw evidence and honest timing/cost scope are in
`docs/performance/input-validation/review` and the report's review appendix.

Bounded finish baseline (before production edits): PR head
0347d7ab58a9c176ac3be027d36c0f2a72209459, tree
b2e3127b4a7102d0f8a79415d753ace3c5d723e5; main 6129dbf.
Compared reviewed c3ccd8cf through current head: four prior inline repairs retained;
#17/#18 ownership/policy/read-lease code remains integrated. Reproducer bundle read
and converted to InputBoundaryRepairTest (phase-4). Baseline toolchain remains
Temurin 25.0.4.1+1, Maven 3.9.16, Node 24.19.0; heap -Xmx1g. Existing
benchmarks/input-validation/{prepare,run,summarize}.py and workspaces/{compile,matrix,verify,summarize}.py
are used unchanged initially. Baseline commands/results are under finish-before-*
in scratch and will be committed with final evidence. Normal validation command:
/tmp/jvmd-mvn -o -B -pl jvmd-tests -am -Dgroups=phase-3,phase-4
-Djvmd.resolvers=/workspace/scratch/7a99f3b47779/jvmd/jvmd-resolver/maven3/target
-DargLine=-Xmx1g test. Focused baseline uses -Dtest=InputBoundaryRepairTest
-Dsurefire.failIfNoSpecifiedTests=false instead of groups.

- [x] Demonstrate the three new regressions on this baseline.
- [x] Repair shared effective environments and per-module fact association.
- [x] Repair role evidence and scoped, bounded-lifetime overlay transitions.
- [x] Measure and remove redundant observations inside logical operations.
- [x] Publish correctness, performance evidence and exact tested trees on PR #21.

Correctness checkpoint: all three original assertions failed on 0347d7a (3 failures,
0 errors). Repaired tests now pass, plus real-javac ordered-classpath/two-module
coverage. Navigation consumes the same per-module compiler snapshots that produce
its facts; changed environments invalidate their owner files rather than an
unordered whole-workspace classpath union. File-manager validation receives that
complete environment instead of constructing a reduced one. Environment replacement
also recreates javac's delegate, whose patch-module options otherwise survived a
pool recycle and caused duplicate-patch errors on the now-required rebuild.
Source and environment evidence have separate roles. Documents keeps weak,
module-owned transition subscriptions, not a closed-file history; only relevant
transitions advance their monotonic version. Content equality still permits reuse.

Operation checkpoint: 140 phase 3/4 assertions pass, including prior semantic policy,
zero-decoded-budget fact lifetime, pinned reads and aborted rebuild coverage.
Analyzer passes its start snapshot into CompilerPool; javac performs the one fresh
end capture before returning accepted output. Diagnostics and batch attribution no
longer wrap that work in repeated whole-module captures. File-manager reuse compares
the supplied complete environment and no longer re-observes it. Per-request boolean
freshness memoization was removed; a newly captured input can never be hidden behind
an earlier same-request valid flag. Warm diagnostic cache hits retain one capture.
Counters continue across actual file-manager replacement. Membership invalidation
remains SemanticUpdatePolicy's responsibility; only environment or non-owned compiler
source changes invalidate a module's full owned fact set.

Final API cutover: removed WorkspaceBindings' generation/classpath-only overloads
and their reduced Configuration constructor. Explicit-inventory clients now supply
CompilerInputs.Configuration; Application supplies compiler-owned module snapshots.
Test fixtures were migrated, and benchmark-only InputNavigation adapts the old/new
API reflectively so the same harness runs against all three revisions. No old
production validator remains. The migrated 140-test phase 3/4 gate passes.

Final verification checkpoint: tested local 67cf8dcef166598ccc40178a5d859cdc60c17d86
and published production 82bf709b3a70b6b35dfde617dea1eef61eeb80cb have identical
tree c28aa74d6dea5765772c5b553ce152e21a7941d3. All three production workflows passed:
Checkpoints 35670736591, Distributions 35670736629, Merge Review 35670736583,
including semantic-state lifetime and hosted JVMD/JDTLS correctness comparisons.
Local tests: 140 phase-3/4, 3 Rocks workspace, 4 harness, 3 trend, 4 dashboard pass.
Three alternating component/editor repetitions against both immediate and original
baselines completed; each editor comparison and separate JFR run verified 504
responses and 3120 ranges. Same dependency hashes, JDK and 1 GiB heap throughout.
Raw evidence, distributions, manifests, failing-baseline and passing-gate logs are
under docs/performance/input-validation/finish. Latest report section supersedes
older conclusions: body diagnostics captures 12→3, but warm diagnostics allocation
remains +74.3% versus original baseline; editor measured work +1.5% versus immediate
and +6.3% versus original. Production net +86 versus immediate (+156 versus original),
not shrinkage. No remaining reproduced correctness failure; metadata cost and some
navigation/tail regressions remain documented. This evidence-only checkpoint changes
no tested production or harness files. PR #21 is updated, not merged.
