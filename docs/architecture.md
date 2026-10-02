# jvmd architecture

This describes how jvmd holds what it knows about Java code: the layers, how each is built from its inputs, and where the code for each part lives.

## Terms

| Term | Meaning |
| --- | --- |
| Daemon | The one jvmd process per machine user. Owns MACHINE and serves every project on the machine. |
| Project | One canonical root path opened through `session.open`. Owns one LOCAL. |
| Layer | MACHINE, LOCAL or LIVE. A layer has one root, which covers all of that layer's trees and aggregates. |
| MACHINE | The content-addressed index of every artifact under the Maven repository and every module of the configured JDK. One per daemon. |
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

**MACHINE** holds each distinct artifact content once, whatever number of paths it was found at. A leaf carries its cacheKey, every path with its file stamp, its resolution and documentation identities, and the root of its own semantic tree (declarations ordered by owner, then member, with resolution range sums). The artifact tree orders leaves by cacheKey; the path table maps each location to its leaf.

**LOCAL** holds one project's source files (`LocalFileTree`, ordered by logical path, with membership, content, api, namespace and resolution aggregates), the declarations attributed from them (`LocalSemanticTree`), the module graph, and one route per module scope. It holds no copy of an artifact: a route refers to MACHINE leaves by key, and a sibling module of the same project is reached through LOCAL.

**LIVE** is the session's resident semantic state for the open buffers and in-flight attribution. It is created empty when a session opens and discarded when it closes.

**Read order.** `SemanticReadViews.precedence` composes LIVE, LOCAL and MACHINE:

1. The first layer that owns a symbol wins.
2. If a higher layer owns the owner type with COMPLETE completeness, lower members are hidden.
3. If project source declares a binary name, no lower layer answers for it, including while the file that declares it is not yet built.
4. Overlay identities are digests of (layer, completeness, value) down to the first COMPLETE layer.

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

## Boot

`BootDecision` is the only class that asks whether a layer has prior state. It reads the layer's root and picks the cold boot when the root is absent or unreadable, and the warm boot when it is present.

### MACHINE cold boot

`MachineColdBoot`, at daemon start:

1. **Create.** Create the generation's storage (`RocksIndexStorage.create`). This is the only call site that creates it. A generation that already has a committed root is refused; anything else in it is deleted.
2. **Enumerate.** One walk of the repository (`*.jar` except `*-javadoc.jar`, one stat per file, each `-sources.jar` paired with its binary) and the configured JDK's modules.
3. **Build artifacts.** One `ArtifactJob` per input, at most four at a time, inputs with paired sources first: read the binary into memory once and hash those bytes (checking its `.sha1` when present); claim the content in the boot's `ClaimMap` (a later job with equal content only records its path); take admission and build the leaf with `ArtifactBuilder` from the same bytes: facts, resolution identity, documentation joined from the paired sources, the semantic tree, and one published, once-verified SST. An input that cannot be read or parsed is a fault and contributes no leaf.
4. **Build the tree.** Bulk-build the artifact tree, the aggregates and the path table.
5. **Commit.** `RocksMachineStore` stages leaves, nodes and paths in bounded batches, syncs once, then writes the root.

The daemon prints `READY` after the root is committed.

### LOCAL cold boot

`LocalColdBoot`, after `session.open` for a resolved Maven project, once MACHINE is committed, on a platform worker the session owns:

1. **Create.** Create the project's LOCAL storage (`RocksLocalStore.create`), keyed by canonical root within the generation.
2. **Module graph.** Resolve with `MavenResolver`: modules in reactor order, the edges between them, and one compiler context per module scope, including that scope's annotation processing output. A sibling module's sources are on its dependants' source path.
3. **Routes.** Map each module scope's classpath entries to MACHINE leaf keys or sibling modules. An artifact MACHINE lacks is built by the MACHINE `ArtifactJob`, added to the MACHINE tree, and the MACHINE root is recommitted once.
4. **Files.** Read each source file once; its hash and its attribution use the same bytes.
5. **Declarations.** One job per (unit, compiler context) in `UnitQueue`, batched by context. `UnitCapture` compiles and captures; nothing is admitted into analyzer state, proved, cached or published. A request that needs a file moves its job to the front and waits for it (`require`).
6. **Build the trees.** Bulk-build the file tree and the semantic tree.
7. **Commit.** Stage file leaves, nodes, the module graph and routes in bounded batches, sync once, then write the LOCAL root.

While it runs, analysis reads LOCAL from `LocalLayer`: the files built so far, with the types of unbuilt files owned by project source and unknown. The session's dependency selection comes from its routes.

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
| `jvmd-boot/.../boot/cold/local/` | `LocalColdBoot`, `Context`, `UnitQueue`, `UnitJob` | The LOCAL cold boot, one method per stage; the (unit, context) queue |
| `jvmd-boot/.../boot/warm/` | `MachineWarmBoot`, `LocalWarmBoot` | Reopening a committed MACHINE generation; serving a project whose LOCAL root is committed on demand |

Code that exists only so a reopened generation or a committed LOCAL root keeps working until the warm boot reads the committed layers directly is marked `TEMPORARY(warm-boot)`.
