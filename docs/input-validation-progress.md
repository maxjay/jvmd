# Input identity consolidation — current checklist

Baseline: `9ac81c4bdded8de18537569debf02e789c24fe27` (main, 2026-09-21).
Branch: `refactor/input-identity-validation`. Neither predecessor is stacked.
This file is the current implementation checklist for task 4; earlier progress entries
remain historical evidence, not competing instructions.

- [x] Identify merged predecessors and exact baseline before production edits.
- [ ] Verify the three #18 regressions on the baseline.
- [ ] Record baseline validation and end-to-end evidence.
- [ ] Implement the shared observation/snapshot contract.
- [ ] Cut consumers over and delete replaced paths.
- [ ] Cover races, overlays, membership, environment, reconciliation and eviction.
- [ ] Run serial alternating measurements and existing correctness suite.
- [ ] Record additions/deletions, limitations, tested head and open PR.

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
