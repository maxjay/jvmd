# First replacement: source content observation

Recorded after the ten-run baseline and separate allocation profile, before
production edits. Baseline production is `ae23fd1f`.

| Fact / work | Baseline owner and consumers | First replacement |
| --- | --- | --- |
| Unsaved text/version/hash | Documents; analyzer, navigation, editing | Keep Documents authoritative |
| Observed disk content identity | Documents' FileStateRegistry; analyzer dependencies, completion, diagnostics | Navigation reads the same Documents.sourceHash |
| Navigation's duplicate disk stamp/hash cache | WorkspaceBindings.Stamp, hashes, hash(Path); used by inputs() | Delete all three, including its eviction loop and separate hashing |
| Classpath content | Shared FileStateRegistry supplied to Analyzer/WorkspaceBindings | Keep separate from source ownership; same hash implementation |
| File kind and Unix change stamp | FileStateRegistry.hash calls isRegularFile, then stamp | Read kind with the existing Unix stamp; retain conservative provider fallback |
| Freshness of a navigation snapshot | WorkspaceBindings Inputs and ValidationToken | Keep comparison and publication boundaries; content ownership alone does not replace them |
| Filesystem Merkle roots | RocksWorkspaceState owns persisted directory/module identities | Keep existing guarantees; do not treat a persisted root as proof of an unobserved edit |
| API identity and propagation | ApiFingerprint, Analyzer, WorkspaceBindings, persisted semantic invalidation | Unchanged in this first replacement; semantic DAG integration remains open |

The profile attributes about 106 MB of sampled allocation weight to
`Hashing.sha256 -> WorkspaceBindings.hash -> inputs`, across the complete profiled
workload. That is sampled weight, not exact allocated bytes or a per-request figure.
Code inspection confirms this path allocates a 64 KiB buffer even for a small Java
file. Analyzer already observes those files through the Documents-owned registry,
which sizes its buffers and checks stamps on both sides of a read.

The ten unprofiled baseline runs allocate a median 182.22 MB on the 512-file cold
navigation request. **Primary outcome:** reduce that request's allocation by at
least 10%, with the paired bootstrap interval below zero. Expect removal of the
second hash-cache representation and redundant byte reads. Do not claim latency
improvement unless the separately declared latency criteria pass. Other latency,
allocation and RSS regressions remain subject to the suite contract.

Implementation must preserve missing-file handling, editor-buffer precedence,
timestamp-preserving changes, symlink following, and conservative hashing on file
systems without Unix change stamps. Keep before/after read stamps; a shared cache
must not turn a concurrent read into false freshness. Test reuse via hash counters,
old-reader behaviour, API invalidation and current navigation ranges.

This step removes a duplicated observation path. It does not implement the semantic
Merkle DAG, remove coarse source enumeration or fix the baseline's separate API
projection/publication-fence issues. Those remain visible work, not hidden claims.
