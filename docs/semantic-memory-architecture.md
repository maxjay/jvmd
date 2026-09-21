# Semantic memory architecture

Status: investigation/specification for PR #8. This document is deliberately stricter than
“Corpus must pass”: it defines the state model that any accepted implementation must satisfy.

## Core rule

**Persist canonical facts and content identities. Derive projections.**

A Merkle identity proves equality/change and localises the changed ownership path; it does not
reconstruct information discarded by hashing. Therefore JVMD must retain/persist the minimum
canonical leaves required to answer/rebuild semantics, compose identities from those leaves, and
treat lookup acceleration/results as derived state rather than a second source of truth.

Every state item must be one of:

| Class | Meaning | Persistence rule |
| --- | --- | --- |
| Canonical fact | Information that cannot be recovered from an identity alone | Persist/retain once under one owner |
| Merkle identity | Deterministic identity derived from canonical children and context | Compose/reuse; do not duplicate as an independent authority |
| Derived index | Lookup acceleration reconstructable from canonical facts | Rebuildable/disposable; retain only when measured worthwhile |
| Result cache | Cached output valid for canonical input identities | Disposable; key by canonical identities |

## Initial ownership/multiplicity ledger

This is an audit starting point, not a claim that every row is wrong.

| State | Current owner/representation | Initial classification | Architectural question |
| --- | --- | --- | --- |
| Open document text | `Documents` | Canonical fact | Keep one editor-buffer authority |
| Observed disk content hash | `FileStateRegistry` / Documents source identity | Merkle/input identity | Reuse rather than duplicate hashing |
| Workspace file record | `RocksWorkspaceState F` | Canonical leaf + identity | Is path + content hash the minimal durable file leaf? |
| Directory child mapping | `RocksWorkspaceState C` | Canonical structure or derived index | Can this be the sole durable ownership edge rather than coexist with D? |
| Directory digest | `RocksWorkspaceState D` | Merkle identity | Derived from child mapping; justify persistence as acceleration |
| Module digest | `RocksWorkspaceState M` | Merkle identity | Derived from source roots + directory roots + compiler context |
| Declaration contract | `SemanticApi.declarations` | Canonical semantic fact | Keep detached meaning once |
| Declaration ownership | `SemanticApi.owners` + SemanticNode child structure | Potential duplication | Determine whether both are required after construction |
| Exported names | `SemanticApi.exportedNames` + persisted invalidation state | Canonical projection / derived index | Should export names be canonical API leaf data and indexed derivatively? |
| File API root | `SemanticApi.root` | Merkle identity | Authoritative file API identity |
| Module/workspace API root | `SemanticNode` composition / NavigationIndex apiRoot | Merkle identity | One identity owner; consumers reference it |
| Source content hash for invalidation | `RocksSemanticInvalidation.Stored.contentHash` | Potential duplicate identity | Workspace/source identity already exists |
| API fingerprint for invalidation | `RocksSemanticInvalidation.Stored.apiFingerprint` | Potential duplicate identity | File API root already exists |
| Dependencies | analyzer graph + `RocksSemanticInvalidation` | Canonical relationship fact | Persist once; reverse closure may be derived/indexed |
| Export names in invalidation | `RocksSemanticInvalidation` | Potential duplicate fact | Likely belongs to canonical file API contribution |
| Unresolved targets | `RocksSemanticInvalidation` | Canonical negative-read fact | Persist once; name-to-waiter mapping should be derived |
| Per-file semantic revision | `RocksSemanticInvalidation I` | Derived invalidation token | Can it be replaced by canonical contribution/root identity? |
| Symbol rows | per-file binding snapshots + NavigationIndex + source index | Canonical vs projection unresolved | Decide one canonical detached row owner |
| Symbol ownership postings | `NavigationIndex.owners` | Derived index | Measured lookup value vs multiplicity |
| Selected symbols | `NavigationIndex.symbols` | Derived index/view | Avoid retaining duplicate whole symbol authority |
| Declarations map | `NavigationIndex.declarations` | Derived index | Derivable from canonical symbol/declaration facts |
| Name postings | `NavigationIndex.names` | Derived index | Rebuildable; consider compact IDs |
| Edge ownership | `NavigationIndex.edges` | Canonical contribution aggregation or derived index | Canonical edge fact should exist once per file |
| Outgoing postings | `NavigationIndex.outgoing` | Derived index | Rebuildable from edges |
| Incoming postings | `NavigationIndex.incoming` | Derived index | Rebuildable from edges |
| Reference postings | `NavigationIndex.references` | Derived index | Rebuildable from per-file occurrences/reference facts |
| Per-file occurrences | `NavigationIndex.occurrences` + fragment snapshots | Canonical fact/projection unresolved | Pick one owner and share/derive the other |
| Diagnostics | WorkspaceBindings + DiagnosticSnapshots | Result cache | Key by canonical source/context/dependency identities |
| Diagnostic source/API/dependency identity | DiagnosticSnapshots payload | Potential duplicate identity | Prefer key/manifest derived from canonical identities |
| Rocks local/source index | IndexStore/Rocks | Durable query index | Must not become a second semantic authority |

## Concrete current multiplicity

### RocksWorkspaceState

The durable workspace hierarchy currently stores:

```text
F: file path -> relative path + content hash
C: directory identity -> child name + child hash
D: directory -> directory hash
M: module -> module hash
```

The relationships are deterministic:

```text
D = H(directory name, sorted C)
M = H(module/context inputs, root D values)
```

Therefore D and M are derived identities. Persisting them may be a valid acceleration, but they
must not be treated as independent facts. The audit must measure whether retaining F+C+D+M reduces
read/compute cost enough to justify write amplification and Rocks cache pressure.

### RocksSemanticInvalidation

One stored file currently repeats:

```text
contentHash
apiFingerprint
dependencies
exportedNames
unresolvedTargets
```

A one-file `observeFile` currently loads and decodes all semantic records for the module,
materialises a complete map, replaces one file, reconstructs the reverse dependency relation and
then writes changed records.

That is a specific architecture smell to measure: update work is currently capable of being
O(module state) for one changed file even though existing Merkle/source/API identities already
localise the changed leaf.

Target shape:

```text
canonical file semantic contribution
    source identity
    API identity
    dependency facts
    unresolved facts
    reference facts
        |
        +--> compositional module/workspace identities
        |
        +--> disposable reverse/name/navigation indexes
```

A file update should replace one canonical contribution and update only affected ownership paths
and indexes.

### NavigationIndex

The current derived navigation representation maintains persistent maps for:

```text
owners
symbols
declarations
names
edges
outgoing
incoming
references
occurrences
diagnostics
warnings
degraded
```

Structural sharing prevents copying entire maps, but it does not eliminate multiplicity: one edge
can participate in ownership, outgoing and incoming structures; one symbol can participate in
owner, selected-symbol, declaration and name structures.

The investigation must distinguish:

1. canonical fact payload bytes;
2. references to the same payload;
3. persistent-map node overhead;
4. path-copy allocation per update;
5. old-generation retention;
6. temporary construction maps used during full builds.

The accepted architecture may keep immutable Merkle ownership while using denser mutable/packed
derived indexes if that is measurably better.

## Identity algebra to formalise

Do not collapse all validity into one workspace hash. Consumers need narrow identities.

Candidate conceptual identities:

```text
SourceId(file)
ApiId(file)
ReferenceContributionId(file)
DependencyContributionId(file)
CompilerContextId(module)

ModuleSourceRoot = Merkle(SourceId(file)...)
ModuleApiRoot    = Merkle(ApiId(file)...)
WorkspaceApiRoot = Merkle(ModuleApiRoot...)
```

Expected change behaviour:

| Change | Source | API | refs/deps | context |
| --- | --- | --- | --- | --- |
| body-only edit | changes | unchanged | may change | unchanged |
| public API edit | changes | changes | may change | unchanged |
| position/doc-only edit | changes | unchanged | semantic refs may remain equal | unchanged |
| add/delete source | changes module source root | depends on exported API | changes inventory/deps | unchanged |
| JDK/options/classpath | source may be equal | prior API cannot validate result | prior refs cannot validate result | changes |
| newly exported name | source/API changes | changes | negative-read dependants become candidates | unchanged |

This table must become executable tests/counters rather than documentation alone.

## Memory model to formalise

The accepted implementation must support statements that are both asymptotic and measured:

```text
retained canonical semantic state = O(files + declarations + relationships + occurrences)
unchanged query allocation        = O(result)
body edit                         = O(changed facts + affected postings + ownership ancestors)
API edit                          = body edit + O(actual dependency closure)
```

And must reject these behaviours:

```text
unchanged queries increasing retained memory indefinitely
one-file changes materialising the entire module by default
cache pressure causing full semantic reconstruction on each query
old readers retaining unrelated full-workspace generations
derived indexes acting as independent semantic authorities
```

## Memory-pressure policy

A global policy must eventually account for:

1. canonical semantic contributions/roots;
2. compiler/javac working state;
3. required query working memory;
4. high-value derived indexes;
5. result caches;
6. Rocks block cache/memtables.

Under pressure, disposable caches and derived indexes are reduced before canonical incremental
state is destroyed. Repeated full reconstruction is not an acceptable steady-state degradation
mode.

## Acceptance decision

The current branch is not entitled to merge merely because its 512-file latency is strong.
It remains the selected implementation only if the measurements show bounded scaling and the
audit can reduce/justify multiplicity without surrendering those gains. If not, preserve this
evidence and implement the resulting canonical-state model from the original baseline.


## Provisional target model from the persistence audit

This is the first concrete replacement model. It is provisional until the focused scaling probe
closes the remaining attribution questions, but it is intentionally implementable rather than a
generic aspiration.

### 1. Workspace source identity: content-addressed Merkle nodes, one mutable root pointer

Current Rocks workspace state stores overlapping F/C/D/M records. The target durable shape is:

```text
workspace-node/<digest> -> {
    domain,
    sorted child name/type/digest entries
}

module-root/<module-id> -> {
    source-root digests,
    compiler-context digest
}
```

A file leaf contains the source-content identity. A directory node contains only its sorted child
identities. Its Rocks key is its digest, so there is no separate authoritative D record. The module
record is a small root pointer/context descriptor, not another independent copy of the tree.

For a known file change:

```text
relative path
    -> read only ancestors
    -> replace file leaf
    -> create O(path depth) new immutable nodes
    -> atomically move module root pointer
```

Unchanged nodes are reused by digest. A full filesystem walk remains a conservative cold/unknown-
history reconciliation path, not the normal update algorithm.

This replaces the conceptual duplication of F + C + D + M with canonical leaf/node payloads plus
one root pointer.

### 2. File semantic contribution: separate roots by semantic concern

Do not make one workspace hash validate every output. A file's durable semantic descriptor should
refer to independent concern roots:

```text
FileSemanticContribution
    source_id
    api_id
    dependency_id
    unresolved_id
    reference_id
```

Where:

- `source_id` is the canonical observed source identity;
- `api_id` is the detached declaration/API Merkle root;
- `dependency_id` identifies the complete dependency-read set;
- `unresolved_id` identifies negative/name reads;
- `reference_id` identifies body/reference/occurrence facts.

A module maps file path -> contribution identity through a Merkle ownership map. Consumers select
the narrowest root relevant to their result. A body-only edit can therefore change source/reference
state while preserving the API identity exactly.

### 3. Semantic invalidation becomes an algorithm over canonical facts

The current `RocksSemanticInvalidation` record repeats source hash, API fingerprint, dependencies,
exports and unresolved targets and reconstructs the complete module map for a one-file observation.

Target:

- API exports are canonical data under the file API contribution;
- dependencies and unresolved reads are canonical per-file contribution data;
- reverse dependency and unresolved-name-to-waiter maps are **derived postings**;
- API/body classification is simply old/new identity comparison;
- affected files are obtained by walking derived reverse postings from changed API contributions.

There is no need for an independently authoritative per-file “semantic revision” if consumers can
record the canonical contribution identity they observed. A monotonic sequence may remain only as
an implementation/wakeup aid, never as the source of semantic truth.

### 4. Local source index converges on the existing compact artifact query model

This is the strongest concrete reuse found in the audit.

Binary/artifact queries already use compact direct records and postings:

```text
symbol id/key -> one compact record
binary key    -> symbol id
name/path     -> bounded postings
source id     -> outgoing edge postings
target        -> incoming edge postings
```

Normal artifact queries do not materialise an entire artifact; whole-artifact materialisation is
reserved for the correctness oracle.

Local source facts currently use a different model:

```text
artifact -> many JSON SourceFile blobs
query -> sources(artifact)
      -> read every S|... value
      -> copy every byte[]
      -> deserialize every SourceFile
      -> scan for the wanted symbol/edge
```

and a 16 MiB estimated cache-admission threshold means a large local artifact can be permanently
uncacheable, causing the same full materialisation repeatedly.

Target: local source facts use the same direct-record/posting principles as immutable artifact
facts. The exact schema can remain source-aware, but it must support point/range access for:

- SCIP -> source symbol;
- binary key -> source symbol(s);
- file -> its source symbols/relationships;
- source symbol -> outgoing edges;
- target -> incoming edges.

A query for one symbol must not deserialize unrelated files. The 16 MiB whole-artifact source cache
then disappears as a correctness/performance dependency; any remaining cache is merely block/record
acceleration.

### 5. Diagnostics become identity-keyed result caches

A persisted diagnostic object is a result cache, not another semantic authority.

Target key conceptually:

```text
DiagnosticKey =
    H(source_id,
      compiler_context_id,
      relevant_dependency_state_id)
```

The dependency state must preserve current correctness conservatively; where compiler/processor
behaviour can observe more than public API, use the appropriately stronger recorded dependency
identity rather than assuming API equality is sufficient.

The diagnostic payload stores diagnostics/warnings, not another independently authoritative copy
of source/API/dependency identity. Those identities belong in the key/manifest and canonical
semantic stores.

### 6. In-memory workspace state: canonical contributions + derived indexes

The retained in-memory authority is the set/map of immutable per-file contributions and their
ownership roots. Navigation structures are derived acceleration.

The current `NavigationIndex` multiplicity must therefore be justified field-by-field. An accepted
implementation may use immutable/persistent state where old-reader isolation requires it, while
using denser mutable or packed indexes for disposable secondary projections.

Do not preserve a persistent tree merely because the canonical ownership DAG is Merkle-based.

### 7. No admission cliff for canonical state

Both the baseline and candidate currently have a whole-workspace admission decision: if an
estimated aggregate exceeds a fixed threshold, the reusable workspace state can disappear.

The target policy is different:

```text
canonical per-file/root state       keep or durably address
required compiler working set       bounded explicitly
derived indexes                     shrink/evict/rebuild
result caches                       evict first
whole-result materialisations       never required for warm validity
```

Crossing a memory threshold must not transform an unchanged O(query) operation into repeated
O(workspace) reconstruction.

### 8. Proposed physical-state decision table

| Current state | Target decision | Reason |
| --- | --- | --- |
| Workspace F records | **REPLACE** with content-addressed file leaves | source identity should exist once |
| Workspace C records | **REPLACE/ABSORB** into Merkle node payload | child membership is canonical tree structure |
| Workspace D records | **DERIVE** from node key | digest is already the node identity |
| Workspace M fingerprint | **RETAIN AS ROOT POINTER/descriptor** | cheap durable entry point, not duplicate authority |
| Semantic invalidation content hash | **REMOVE DUPLICATE** once source leaf is canonical | already owned by source identity |
| Semantic invalidation API fingerprint | **REMOVE DUPLICATE** once API contribution is durable | use canonical API root identity |
| Semantic dependencies | **RETAIN ONCE** as canonical file contribution | cannot be reconstructed from a hash alone |
| Semantic exported names | **RETAIN UNDER API contribution** | semantic API fact; reverse lookup is derived |
| Semantic unresolved targets | **RETAIN ONCE** as canonical negative-read contribution | required for correctness |
| Semantic per-file revision | **DEMOTE** to optional sequence/wakeup metadata | canonical contribution identity validates state |
| Local SourceFile JSON blobs | **REPLACE** with direct compact facts/postings | avoid whole-artifact materialisation |
| Local source 16 MiB whole-artifact cache | **REMOVE AS ARCHITECTURAL DEPENDENCY** | queries must be point-addressable |
| Navigation name/in/out/ref postings | **DERIVED** | rebuild/update from canonical contributions |
| Diagnostic identity payload copies | **REMOVE DUPLICATE** | cache keyed by canonical identities |
| Diagnostic result payload | **RETAIN AS EVICTABLE CACHE** | useful expensive result, not semantic authority |

No row is deleted merely for line-count or storage-size reasons. Each replacement must retain the
same conservative correctness boundaries and be benchmarked before/after.
