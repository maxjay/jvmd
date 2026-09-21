# Shared semantic state: complete replacement contract

## Scope and extensibility

Capture declaration meaning once from javac elements, independent of protocol display maps.
A declaration node owns its semantic fields and keyed child declarations; a file owns top-level
types; a module owns files; a workspace owns modules. Cross-reference edges carry symbol IDs,
never recursive ownership links. Source text/positions/docs/reference contributions remain
separate inputs and outputs. Existing compiler context and source publication fences remain.

A content identity carries its domain, schema version and algorithm. Collection updates expose
`key, old contribution, new contribution`; absent values mean insertion/deletion. Unordered
keyed sets bind both key and identity, enforce unique keys, and count membership. Ordered inputs
(parameter order, record components, classpath) are encoded in order. A future multiset/accumulator
implementation can maintain an unordered aggregate at this boundary without altering semantic
consumers. No XOR prototype, algorithm switch flag, or generic provider framework is needed now.
Different algorithms/schema identities are incompatible and force recomputation at persistence
boundaries. Incremental hashing does not remove the cost of maintaining the membership index.

The initial engine is deterministic Merkle composition with immutable nodes and structural sharing.
Equal inputs produce equal identities regardless of insertion/deletion history. No global interner.
Reuse is bounded by retained snapshots and existing cache admission. Every API consumer reads the
captured file identity; none reconstructs it from symbol rows. The persisted fingerprint includes
the new scheme version, and old diagnostic snapshot schemas miss safely.

## Named replacements

| Existing responsibility | Replacement |
| --- | --- |
| ApiFingerprint filtering/sorting/JSON projection in Analyzer and WorkspaceBindings | Captured semantic contracts and one file API identity |
| Presentation `api` maps plus a separate reconstructed API model | Authoritative detached contract, with compatibility presentation where required |
| WorkspaceBindings aggregate and integer edge indexes rebuilt globally | Immutable changed-file navigation contributions and shared indexes |
| Whole result JSON serialization for cache admission | Per-contribution structural size estimate, checked separately against live heap |
| Rebuilt reverse graph in navigation / duplicated graph operations | Shared dependency graph operations with complete-vs-partial observations |
| Standalone file API comparisons without composable ownership | File, module and workspace identity paths, reused by actual consumers |

Filesystem observations and durable Rocks invalidation revisions remain distinct lifecycle
boundaries. They must consume the same captured API identity; an in-memory root does not become
a cross-store transaction or a promise of scan-free external-edit detection.

## Measurement contract fixed before production edits

Primary outcome: at least 10% paired-median lower 512-file body-edit navigation latency, with
95% paired-bootstrap interval below zero. Supporting allocation target: at least 10% lower
allocated bytes on that path and a confidence interval below zero. No accepted material latency
regression on other covered paths. Material thresholds are per-request, both relative and absolute:

- Cold JVM/workspace: >10% and >10 ms.
- Warm navigation/rename/validation: >5% and >0.025 ms.
- Edits, diagnostics and lifecycle operations: >10% and >0.25 ms.

Use ten independent alternating JVM pairs initially; one further ten-pair campaign may resolve
uncertainty, with all samples combined. An interval that permits a material regression is unresolved,
not a pass. Report medians, paired changes/intervals and descriptive sample p95; do not claim
production p95 from a handful of JVMs. Allocate/GC/RSS are reported separately. Retained live heap
with 13 snapshots and after releasing old readers is a separate forced-GC campaign, never a latency
sample. Memory growth above 10% needs attribution and keeps acceptance open until resolved.

Retain the existing cold/warm/128/512/rename/real-source/precise-vs-coarse suite. Add two-source-module
lifecycle workloads: body/API/doc-position edits, diagnostics reuse/after API changes, buffers,
addition/deletion, context revision, old-reader isolation and clean-rebuild agreement. Compiler/JDK,
heap, fixture, dependency, source and harness hashes are recorded. Exact compiler context,
classpath precedence, negative resolution and publication races also have deterministic repository
tests; these are correctness gates, not claims of benchmarked production tail latency.

No broad JDTLS comparison is implied. Completion, parser performance and native watcher throughput
are not changed by this scope; existing completion/watcher correctness gates must still pass.

## Readability and complexity acceptance

Count all affected handwritten production sources, including helpers, with the same formatter
on both revisions. Report net lines and removed responsibilities separately. Tests/docs/evidence
are not a line-reduction target. If the code or performance target is unmet, leave it explicitly
open; do not retain the old implementation in parallel or claim completion from tests alone.
