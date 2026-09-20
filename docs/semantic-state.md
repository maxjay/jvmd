# Semantic state: identity and publication boundaries

Implementation branch: `architecture/semantic-state`, based on `ae23fd1f`.
Acceptance lives in [the checklist](../SEMANTIC-STATE-CHECKLIST.md); changes and results
are recorded in [the append-only progress log](../SEMANTIC-STATE-PROGRESS.md).

## What is implemented

Javac remains the semantic authority. Detached declaration contracts now have a typed,
versioned identity separate from source positions and documentation. Navigation snapshots
use persistent maps with per-file contributions. Source identity reads share the Documents
owner, and complete dependency capture replaces obsolete edges.

This is a foundation for semantic incrementality. It is not a content-addressed compiler,
a persistent syntax tree, or a durable workspace root spanning every store. The existing
filesystem Merkle hierarchy remains useful for persisted source/module identity; it does
not prove that an unsaved buffer or an unobserved external write has not changed semantics.

## Ownership and authority

| State | Owner | What its identity proves | What it does not prove |
| --- | --- | --- | --- |
| Open document text | `Documents` | Content hash of the current editor buffer | Disk contents or workspace consistency |
| Disk content observation | `FileStateRegistry` | Hash from a stamp-bracketed read; Unix ctime/inode permit reuse | A transaction across multiple files |
| Source inventory and hashes | `SourceSnapshots` owned by Documents | An immutable observation of the requested source view | A durable global epoch or filesystem journal |
| Compiler context | Analyzer / CompilerPool | Existing release, options, resolver generation, classpath and processor context | Source semantic equality |
| Declaration contract | `DeclarationContract` / `ApiFingerprint` | Equality of the captured accessible declaration meaning for a file | Equal references, positions, docs, or arbitrary annotation-processor observations |
| Navigation result | `WorkspaceBindings` / `NavigationIndex` | Immutable postings built from validated fragments | A content-addressed restart snapshot |
| Artifact/source index | Existing `IndexStore`, default Rocks | Its existing published artifact generation and source facts | Atomic agreement with every live diagnostic/navigation cache |

`SourceSnapshots.capture(files)` returns a sequence number, immutable hashes and changes
relative to the preceding capture by this owner. A module view followed by a workspace view
can change that number even without a file edit. Consumers compare the returned hash maps;
they must not use the sequence alone as a workspace cache token. Its history is bounded to
one prior view. Fast workspace validation continues to use existing compiler source epochs.

## Declaration contracts

Contracts contain symbol ownership, element kind/type, modifiers, declaration annotations,
constant values, generic bounds, superclass/interfaces, permitted subclasses, record
components, thrown types, parameter types/annotations, receiver, varargs and annotation defaults.
Capture deliberately excludes locals, parameters as declarations, anonymous/local types,
private declarations and descendants of private ownership. Package and protected declarations
remain because they affect Java consumers.

`package-info.java` and `module-info.java` are context inputs. Their content hashes participate
in diagnostic context identity, and changing either forces a full navigation rebuild. Explicit
change notifications clear semantic context caches. Empty member contracts must never suppress
package-annotation or module-visibility invalidation.

Documentation, offsets and ordinary parameter display names are absent. The existing `api`
presentation row remains for compatibility; it is no longer the hash input. The hash encodes
`declaration-contract-v1` and a symbol-sorted map of typed contracts. Generic parameter naming
and javac's detached type spellings can still cause conservative invalidation; this is not a
proof of equivalence under every Java refactoring.

Only a fragment's own source declarations construct contracts; referenced symbols retain their
ordinary presentation rows. The process-wide interner retains at most 8,192 immutable contracts. Full structural equality,
including symbol ownership, decides reuse; Java hash collisions cannot merge unequal records.
Eviction only loses sharing. Pointer equality is sufficient evidence for two retained nodes,
but pointer inequality is not evidence of a semantic change. Context validity remains outside
the contract: a contract must not be used to substitute a compilation from a different context.

## Publication protocol

1. Obtain a live validation token when precise source-root coverage is available.
2. If it is compatible with a cached result's token, reuse that result under the existing
   observed-watcher consistency contract. A newly available Merkle root may be additive
   evidence only when all existing roots and live epochs still agree.
3. Otherwise enumerate and hash the current source view and ordered classpath. When checking
   an existing result, sample the token again before adopting it. A changed token cannot be
   attached to the previously read inputs.
4. Attribute dirty fragments. Compare their contracts; traverse reverse dependencies only
   when those contracts changed. Files with prior errors remain conservative invalidation roots.
5. Before publication, bracket final inventory/hash validation and navigation maintenance with
   live tokens. Cache only a consistent, complete result that fits the admission budget.
6. If the sources changed during this work, return a degraded result with a retry warning and
   retain no reusable workspace snapshot from that attempt.

Coarse roots have no token shortcut and retain full inventory/hash validation. A watcher
reports observed changes; an OS event can still be pending. Neither this protocol nor the
existing Merkle roots promise a linearizable filesystem snapshot. Source text and hashes are
read separately, with before/after validation; arbitrary concurrent edit-and-revert sequences
are not a filesystem transaction. Editor requests run on the existing session executor.

## Dependency and navigation updates

`DependencyGraph.record(owner, reads, complete)` has two distinct contracts: complete capture
replaces the prior read set and reverse edges; partial/focused capture conservatively merges.
Analyzer's lazy dependency wrapper and WorkspaceBindings use this implementation. The Rocks
semantic invalidation store retains its existing persisted algorithm and broad unresolved-name
fallback; it is not yet a shared live query engine. Negative/member lookup read sets are not
precise enough to remove the conservative fallback.

`NavigationIndex` maintains persistent AVL maps for symbol ownership, preferred declarations,
name lookup, edges in both directions and reference occurrences. Each file contributes its own
postings. Replacement removes that file's old contributions and adds its new ones, selecting
the symbol's own declaration over a reference copy. Other fragments' postings and map subtrees
remain shared. Old reader snapshots remain unchanged. Lists of complete edges/occurrences are
lazy immutable views; requesting all of them still costs proportionally to the result size.

The update cost follows changed postings and the owners of affected symbols, plus tree paths.
It is not constant time per edit. Source validation, fragment metadata copies and conservative
namespace/context rebuilds remain workspace-sized work. Adding/removing a source changes the
namespace and currently forces a full rebuild. Cold construction currently uses persistent
insertion; efficient bulk construction is a possible follow-up, subject to measurement.

These AVL roots are structural-sharing roots, not deterministic Merkle identities. Their
shape depends on update history and they do not recursively hash cyclic symbol relationships.

## Memory and status contracts

Admission remains bounded by the lower of the caller's budget and 128 MiB. Each changed fragment
is serialized once for a size proxy; its retained estimate is four times that size plus fixed
fragment/symbol/edge/occurrence allowances. Updates subtract prior fragment weights and add new
ones. The estimate is intentionally conservative but is not a measured upper bound on JVM heap.
It does not account for all old snapshots retained by active readers, compiler memory, or the
separately bounded interner. The JVM and session budgets remain necessary.

| Status field | Meaning |
| --- | --- |
| `serialized_bytes` | Sum of admitted fragment JSON sizes; formerly size of the aggregate JSON |
| `estimated_retained_bytes` | Current admitted fragment weight estimate |
| `fragment_bytes_serialized` | Cumulative serialization work for loaded fragments |
| `navigation_file_updates` | Cumulative number of file contributions replaced |
| `last_reanalysed_files` | Attribution work for the last query |
| `source_enumeration_ms` | Cumulative workspace source enumeration time |
| `input_validation_ms` | Cumulative source/classpath identity validation time |
| `attribution_ms` | Cumulative fragment loader time, including its compiler validation |
| `fragment_serialization_ms` / `api_fingerprint_ms` | Cumulative detached fragment accounting / contract hashing time |
| `navigation_maintenance_ms` | Cumulative persistent posting update time |

Timing counters do not sum to end-to-end RPC latency: dispatch, context setup, source text reads,
selection and response serialization are outside these regions. They are operational evidence,
not a wire-format promise that every layer performs only changed-file work.

## Persistence and migration

Diagnostic cache context identity advances from `diagnostics-v2` to `diagnostics-v3`;
`DiagnosticSnapshots` schema advances from 2 to 3. Old diagnostic snapshots miss and are
recomputed. Persisted semantic API values become different through the versioned contract hash,
causing conservative invalidation as files are observed. Rocks record encoding and artifact
generation format are unchanged. No in-place rewrite of a user's artifact database is needed.

Navigation roots and interned contracts are process-local. There is no serialized javac object,
syntax tree replacement, cross-store workspace commit, or global restart root. A future durable
semantic DAG requires explicit root publication, context identities, reader pinning and storage
reclamation before it can claim that property.

## Validation and remaining acceptance

Run `mvn -B -DskipTests install`, then `bash benchmarks/semantic-state/run.sh` with the pinned
JDK 25. The checkpoint workflow runs that runner and the existing compiler, index, protocol and
editor gates. The runner covers typed identity changes, deterministic publication interleavings,
persistent map oracle agreement, old snapshots, dependency replacement, budget eviction, ordered
classpath, buffers, preserved timestamps, hierarchy, rename and negative-lookup cold agreement.

The synthetic performance probe retains all samples for 128 and 512 files. It verifies correct
incoming references after body/API edits. See [performance evidence](performance/semantic-state/README.md).
Cold, warm and API-edit regressions are acceptance concerns; smaller posting-update counts alone
do not establish a faster product. Real-project, allocation and retained-heap acceptance remains
open. Green parsing, custom authenticated storage, accumulators and content-defined chunking
remain separate research decisions, not prerequisites for making these contracts reliable.
