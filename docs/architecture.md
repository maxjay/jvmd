# jvmd architecture

This describes how jvmd holds what it knows about Java code: the layers, how each is built from its inputs, and where the code for each part lives.

## Terms

| Term | Meaning |
| --- | --- |
| Daemon | The one jvmd process per machine user. Owns MACHINE and serves every project on the machine. |
| Project | One canonical root path opened through `session.open`. Owns one LOCAL. |
| Layer | MACHINE, LOCAL or LIVE. A layer has one root, which covers all of that layer's trees and aggregates. |
| MACHINE | The content-addressed index of every artifact under the Maven repository. One per daemon. |
| LOCAL | One project's own state: its source files and their declarations, its module graph, and its routes into MACHINE. Persisted. |
| LIVE | What a session's editors and in-flight analysis see that differs from LOCAL. Never persisted. |
| Route | The ordered classpath of one module scope (main or test), as references: MACHINE leaf keys and sibling modules. Never a copy. |
| Leaf | One unit of a layer: an artifact content in MACHINE, a source file with its declarations in LOCAL. |
| Leaf key | MACHINE: the content cacheKey. LOCAL: the logical source path, relative to the project root. |
| Projected identity | A SHA-256 over only the fields a consumer depends on: content, resolution, api, namespace, documentation. |
| Aggregate | The additive set hash (`AlgebraicAccumulator`) over (leaf key, projected identity) for every leaf of a layer, one per projection. |
| Tree | A hash-priority treap with Merkle hashes and subtree range sums (`KeyedTree`). Its shape depends only on its key set. |
| Root | One digest over a layer's top tree hash, its aggregates, its leaf count and its format. Written last. |
| Commit | Writing a layer's leaves and tree nodes in bounded staged batches, syncing once, then writing its root. |
| Committed | A layer whose root is present and readable. This is the only meaning of complete. |
| Cold boot | Building a layer from its inputs because the layer has no committed root. |
| Warm boot | Serving a layer whose root is committed. |
| Generation | The directory `index-v2/generations/format-<F>-jdk<N>-<indexer>`. A different name is a different, empty location, so its first start is a cold boot. |
| UNKNOWN | Missing evidence. It has no identity and never compares equal to anything. |

## Layers

A read goes LIVE, then LOCAL, then MACHINE; the first layer that has the answer wins.

**MACHINE** holds each distinct artifact content once, whatever number of paths it was found at. A leaf carries its cacheKey, every path with its file stamp, its resolution and documentation identities, and the root of its own semantic tree (declarations ordered by owner, then member, with resolution range sums). Its documentation identity is the commutative sum, over the members joined to its sources, of each member's doc comment hash: the same projection LOCAL takes of a declaration, so rendering and source positions do not reach it. The artifact tree orders leaves by cacheKey; the path table maps each location to its leaf.

**LOCAL** holds one project's source files (`LocalFileTree`, ordered by logical path, with membership, content, api, namespace and resolution aggregates), the declarations attributed from them (`LocalSemanticTree`), the module graph, and one route per module scope. It holds no copy of an artifact: a route refers to MACHINE leaves by key, and a sibling module of the same project is reached through LOCAL.

**LIVE** is the session's resident semantic state for the open buffers and in-flight attribution. It is created empty when a session opens and discarded when it closes.

**Read order.** `SemanticReadViews.precedence` composes LIVE, LOCAL and MACHINE:

1. The first layer that owns a symbol wins.
2. If a higher layer owns the owner type with COMPLETE completeness, lower members are hidden.
3. If project source declares a binary name, no lower layer answers for it, including while the file that declares it is not yet built.
4. Overlay identities are digests of (layer, completeness, value) down to the first COMPLETE layer.

Two projects over one MACHINE: both route to the shared leaf, which is stored once.

```
 project P                         project Q
 LIVE  (open buffers that differ)  LIVE
 LOCAL (files, declarations,       LOCAL
        module graph, routes)
   route p:main ──┐          ┌── route q:main
                  ▼          ▼
 MACHINE  [ guava-33 ] [ jackson-2.22 ] [ java.base ] ...   one per daemon
```

**Write direction.** MACHINE is written by its cold boot, by the repository scan of a reopened generation, and by a LOCAL cold boot adding an artifact a route needs. LOCAL is written by its cold boot. LIVE is written by editor events and the attribution of unsaved content. No layer writes into a layer above it.

## Identities and trees

| Structure | Code | Property used |
| --- | --- | --- |
| Projected identity | `ResolutionFact`, `ArtifactIndexFormat.resolutionIdentity`, `ApiFingerprint`, `LiveStateTree.namespace` | A change outside the projection leaves the identity equal |
| Additive set hash | `AlgebraicAccumulator`, `Aggregate` | Insert, remove and replace in O(1), independent of order |
| Keyed tree | `KeyedTree` | Equal key sets give equal roots in any build order; updates in O(log n); equal subtree hashes are skipped |
| Range sums | `KeyedTree.range`, `ResidentSemanticState.FACTS` | The identity of any key range (an owner's members, an overload group) without listing it |
| Ordered sequence | `ClasspathSequence` | A diff returns the changed interval; routes use it |
| Three-valued knowledge | `SemanticKnowledge`, `SemanticCompleteness` | UNKNOWN has no identity |
| Proof with early cutoff | `QueryProof`, `ProofDag` | A consumer is recomputed only when an identity it read changed |
| Committed root | `Root`, `Commit` | One digest covers a layer; its presence is completeness |

Two cold boots over the same inputs produce the same root, whatever the enumeration order or the order jobs complete in. Tests check this by comparing roots.

Rules that follow from these structures:

1. A consumer that needs "has X changed" compares one identity read from a tree.
2. Equal inputs give equal roots, so equality of two states is checked by comparing roots.
3. A layer's root is the only completeness signal.
4. Each projection has one producer.

### Proofs

A result is invalidated only when one of the exact identities it read changed, and the change stops travelling at the first result that comes out equal.

**Leaves** are the identities a result can read (`QueryProof.Domain`):

| Domain | Key | Identity | Changes when |
| --- | --- | --- | --- |
| EXACT_SYMBOL | one declaration id | its resolution identity (kind, name, descriptor, modifiers, type, type parameters, supertypes) | that declaration's signature changes; not on body, Javadoc, parameter name or position changes |
| MEMBER_RANGE | owner + name prefix | range sum over the owner's members whose name starts with the prefix | a member in that range is added, removed or changes signature |
| OVERLOAD_GROUP | owner + exact name | range sum over the overloads with that name | an overload with that name is added, removed or changed |
| HIERARCHY | type id | the type's API composed with its supertypes' | the type or a supertype changes API |
| NAMESPACE | package or type | the top-level names visible there | a type is added, removed or renamed in that package |
| NEGATIVE_RESOLUTION | name @ scope | proven absence of a name | a type with that name appears |
| CLASSPATH_SEARCH | binary name in a classpath | the first entry that defines it | an entry before the winner changes |
| RESOLUTION_PATH | source or type path | where a name resolved to | that source or type moves or changes |

For a project with a LOCAL layer, classpath searches are taken over the module scope's route into MACHINE; project output directories are LOCAL, and project source owns their binary names.

**Proof.** A `QueryProof` is the sorted set of (domain, key, identity) a result read; its certificate identity is one digest of that set; it is valid while every identity in it is equal to the current one.

**Consumers** are the reusable results: a file's source attribution (`registerSourceProof`; its result identity is the file's api fingerprint and exported names, so a body change does not change it), a completion range (`registerCompletionRangeProof`; the member ranges it listed) and a document context (`registerDocumentProof`).

**ProofDag** (`SemanticUpdatePolicy.ProofDag`) maps each leaf key to the consumers that read it (`reverse`), lets results depend on results (`producers`), and orders consumers above every producer they read (`heights`). A propagation wave (`propagateObserved`) takes the consumers of each changed leaf whose captured identity differs, recomputes the lowest first, and stops where a result identity comes out equal. An UNKNOWN leaf retires its consumers without comparing. A completion range is recomputed inside the wave; a source attribution or document context needs javac, so it is retired and recomputed when next read.

**File-level fallback** (`SemanticUpdatePolicy.decide`) applies to files without full proof coverage: changed exported names, or an added or deleted file, are an API and namespace change; otherwise a changed api fingerprint is an API change; otherwise the edit is body-only and nothing downstream is touched. API changes walk reverse dependants, plus files whose unresolved names match an export, skipping dependants with full proof coverage.

The trees are hash-priority treaps, not prolly trees: a tree's shape is fixed by its contents, so equal contents give equal hashes in any build order, and two trees are compared by skipping every subtree whose hash is equal.

### Worked example: editing library A while working in library B

One project, two modules; `lib-b` depends on `lib-a`.

| File | Module | What it reads from A | Leaves in its proof |
| --- | --- | --- | --- |
| `Money.java` | lib-a | (declares `add(Money)`, `currency()`) | |
| `Invoice.java` | lib-b | calls `total.add(line)` | EXACT_SYMBOL `Money#add(Money)`, OVERLOAD_GROUP `Money#add` |
| `Report.java` | lib-b | calls `money.currency()` | EXACT_SYMBOL `Money#currency()`, OVERLOAD_GROUP `Money#currency` |
| `Ledger.java` | lib-b | uses `Money` as a field type | RESOLUTION_PATH and NAMESPACE for `Money` |
| completion at `total.` in `Invoice.java` | lib-b | lists Money's members | MEMBER_RANGE `Money`, prefix "" |

- **Change the body of `add`.** The content leaf of `Money.java` changes; the resolution identity of `add` does not, so the semantic delta has no changed facts, no leaf changed and the api fingerprint is equal. No B file is re-attributed, and the completion list is kept.
- **Change `add(Money)` to `add(Money, Rounding)`.** The delta removes and adds one fact; its tree paths and range sums update in O(log n). The changed leaves are EXACT_SYMBOL `Money#add(Money)`, OVERLOAD_GROUP `Money#add`, the MEMBER_RANGE ranges matching `add`, and HIERARCHY of `Money`'s subtypes. The ProofDag returns `Invoice.java` and the completion at `total.`: the completion is recomputed from range sums without javac, and `Invoice.java` is retired and re-attributed on its next read. `Report.java` and `Ledger.java` are not touched.
- **Add `subtract(Money)`.** The delta adds one fact. The completion is recomputed and lists `subtract`; B files whose recorded unresolved names match the new export are rechecked.

Where B's compiler reads A's declarations from is unchanged: A's classes when A is built and clean, A's sources otherwise. B's semantic reads take A's declarations from the project's LOCAL layer.

### Cost per edit

n is the number of leaves in a tree, k the number of facts an edit changes.

| Step | Cost |
| --- | --- |
| Record a file's new content | O(1) per aggregate, plus one Merkle path |
| Apply a file's semantic delta | O(k log n), including range sums |
| Identity of an owner's members, a prefix or an overload group | O(log n) from range sums |
| Find who read a changed leaf | one lookup in `reverse` per changed key |
| Decide whether a consumer must recompute | compare one captured identity |
| Classpath change | interval diff over `ClasspathSequence`, O(log n); only proofs whose winner is after the interval start are affected |

Where the code does not yet reach these costs:

1. `Analyzer.affectedLeafKeys` scans every key registered in the ProofDag on each mutation.
2. `registerSourceProof` records leaves only for dependencies inside the analyzer's live source set, so a module reading a built, clean sibling from its classes holds no proof on it; the sibling's changes reach it through `ModuleAnalyzerRegistry` and the file-level rule.
3. Every analyzer has its own in-memory ProofDag.
4. HIERARCHY identity includes `ResidentSemanticState.uncertaintyGeneration`, so it cannot be compared across a restart.
5. MACHINE member ranges read through the index store are computed by listing.

## Boot

`BootDecision` is the only class that asks whether a layer has prior state. It reads the layer's root and picks the cold boot when the root is absent or unreadable, and the warm boot when it is present.

### MACHINE cold boot

`MachineColdBoot`, at daemon start:

1. **Create.** Create the generation's storage (`RocksIndexStorage.create`). This is the only call site that creates it. A generation that already has a committed root is refused; anything else in it is deleted.
2. **Enumerate.** One walk of the repository (`*.jar` except `*-javadoc.jar`, one stat per file, each `-sources.jar` paired with its binary).
3. **Build artifacts.** One `ArtifactJob` per input, at most four at a time, inputs with paired sources first: read the binary and its sources into memory once and hash those raw bytes (checking each `.sha1` when present), recording the hashes in the process's `FileStateRegistry`; claim the content in the boot's `ClaimMap` (a later job with equal content only records its path, and decompresses nothing); take admission, estimated from the archives' central directories, then decompress and build the leaf with `ArtifactBuilder` from the same bytes: facts, resolution identity, documentation joined from the paired sources, the semantic tree, and the published SSTs. Nothing hashes the records of a published SST. An input that cannot be read or parsed is a fault and contributes no leaf.
4. **Build the tree.** Bulk-build the artifact tree, the aggregates and the path table.
5. **Commit.** `RocksMachineStore` stages leaves, nodes and paths in bounded batches, syncs once, then writes the root. A recommit writes only the difference from the committed tree: changed leaves and paths, deletions, and nodes not already stored.

MACHINE boots at daemon start. The daemon prints `READY` after its root is committed or found, and sessions open only after that.

### LOCAL cold boot

`LocalColdBoot`, after `session.open` for a resolved Maven project, once MACHINE is committed, on a platform worker the session owns:

1. **Create.** Create the project's LOCAL storage (`RocksLocalStore.create`), keyed by canonical root within the generation.
2. **Module graph.** Resolve with `MavenResolver`: modules in reactor order, the edges between them, and one compiler context per module scope, including that scope's annotation processing output.
3. **Routes.** Map each module scope's classpath entries to MACHINE leaf keys or sibling modules. An artifact MACHINE lacks is built by the MACHINE `ArtifactJob`, added to the MACHINE tree, and the MACHINE root is recommitted once.
4. **Files.** Read each source file once; its hash and its attribution use the same bytes.
5. **Declarations.** One job per (unit, compiler context), in reactor order. Each unit's compiler context is the one analysis builds for its module scope, so javac's classpath and options are those of analysis. Its source path is the files the boot read under that context's source roots, with the text it read (`CompilerPool.bootSources`): javac reads no source from disk, nothing lists the roots or watches them, and no transaction is checked (`CompilerPool.bootQuery`). A context's units are batched in path order, up to 8 Mi characters of source per batch, so a context is usually one javac task and its declarations are entered once. Each unit's identity is the hash taken when the boot read it (`UnitCapture.boot`); that hash, and the hash of every jar MACHINE read, is recorded in the process's `FileStateRegistry`, so the compiler that later reads the unchanged file does not hash it again. `UnitCapture` compiles and captures; nothing is admitted into analyzer state, proved, cached or published. A request that needs a file moves its batch to the front and waits for it (`require`).
6. **Build the trees.** Bulk-build the file tree and the semantic tree.
7. **Commit.** Stage file leaves, nodes, the module graph and routes in bounded batches, sync once, then write the LOCAL root.

While it runs, analysis reads LOCAL from `LocalLayer`: the files built so far, with the types of unbuilt files owned by project source and unknown. The session's dependency selection comes from its routes.

### LIVE

LIVE has no boot: it is created empty when a session opens and discarded when it closes.

### Warm boot

A committed MACHINE root reopens the generation's storage, serves the committed MACHINE tree, and starts a background repository scan that indexes added or changed artifacts. A project whose LOCAL root is committed is served on demand: its modules are published to the index store when first needed and attributed as they are used.

A stopped cold boot writes no root, so the next start (MACHINE) or open (LOCAL) is a cold boot again.

## Code structure

The folder a class sits in says whether it is a cold boot, a warm boot, or a layer both of them build and read. Cold and warm boots never import each other; layer folders never import `boot`; a cold boot calls only create and write methods on the storage it builds. `BootStructureTest` checks these rules.

| Folder | Contains | Responsibility |
| --- | --- | --- |
| `jvmd-core/.../core/tree/` | `KeyedTree`, `Aggregate`, `Root`, `Commit` | The one keyed treap with Merkle hashes, range sums, a bulk builder and a node codec; set hashes; layer roots; staged commits |
| `jvmd-index/.../index/layer/machine/` | `MachineLayer`, `MachineLeaf`, `MachinePath`, `MachineTree`, `ArtifactBuilder`, `ArtifactPublisher` | MACHINE reads; the leaf; the artifact tree, aggregates and path table; building one artifact from bytes |
| `jvmd-index/.../index/layer/local/` | `LocalLayer`, `LocalFile`, `LocalFileTree`, `LocalSemanticTree`, `LocalTree`, `Route` | LOCAL reads; the file and semantic trees; routes into MACHINE |
| `jvmd-index-rocks/.../rocks/layer/` | `RocksMachineStore`, `RocksLocalStore` | Writing MACHINE and LOCAL leaves, nodes, tables, routes and roots |
| `jvmd-analyzer/.../analyzer/capture/` | `UnitCapture` | The javac-and-capture part of batch attribution, used by the LOCAL cold boot and by `Analyzer.bindingsBatch` |
| `jvmd-boot/.../boot/` | `BootDecision` | Reads roots and picks cold or warm |
| `jvmd-boot/.../boot/cold/machine/` | `MachineColdBoot`, `MachineInput`, `ArtifactJob`, `ClaimMap` | The MACHINE cold boot, one method per stage |
| `jvmd-boot/.../boot/cold/local/` | `LocalColdBoot`, `Context`, `UnitQueue`, `UnitJob` | The LOCAL cold boot, one method per stage; the (unit, context) batch queue |
| `jvmd-boot/.../boot/warm/` | `MachineWarmBoot`, `LocalWarmBoot` | Reopening a committed MACHINE generation; serving a project whose LOCAL root is committed on demand |

Code that exists only so a reopened generation or a committed LOCAL root keeps working until the warm boot reads the committed layers directly is marked `TEMPORARY(warm-boot)`.
