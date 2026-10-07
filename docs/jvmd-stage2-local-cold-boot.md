# jvmd stage 2: LOCAL cold boot

Oct 4, 2026 · @Max. Reconciled 2026-10-06 with LAYOUT 4, the PR #60 audit, exact header proofs, current path-addressed reverse records, persistent definer state and lossless Java String values in parser 5.

This revision replaces the earlier Stage 2 specification. Stage 3 Appendix A and this document describe the same foundation. Stage 3 Appendix F extends header compilation with processors; its processor proofs, generated outputs and resource records are additional work, not implied by completion of this document.

## 1. Vision

Stage 1 builds MACHINE from jars and JDK modules. Stage 2 adds the project: ordered routes, source declarations, files, exact header proofs and their reverse index. It commits one LOCAL root after all module scopes finish. A module's source leaf has the same resolution representation as a jar leaf.

Hashes identify exact content and structure. Algebraic sums answer projected semantic questions. `ContentTree` supplies sharing, `Diff`, range sums and incremental `apply`; a reverse key takes a changed range to its actual consumers. None of these roles requires a new route-wide annotation sum, an `nSum`, or a containing-module dependency.

Stage 2 parses sources and resolves declarations. It never calls `analyze` or `generate`, attributes executable bodies, or emits executable class files. Header faults are retained per file/declaration. Body diagnostics, body proofs, executable outputs and overlays belong to Stage 3. Restoring and updating a committed root belongs to warm boot; `jvmd-boot/warm` remains empty except for its package declaration.

## 2. Definitions

### 2.1 Input

The project model gives the canonical root, JDK home, modules, coordinates, source roots, release and javac options, and the resolved, ordered classpath for each scope. Dependency mediation is the build tool's work. Stage 2 neither invents nor sorts a classpath. Appendix A defines the model.

Each module has `main` and `test`. A main route starts with JDK modules, then its declared dependencies. A test route starts with JDK modules, its own main leaf, main dependencies, then additional test dependencies. A dependency names either a jar location or a sibling module.

### 2.2 Routes and bindings

| Name | Meaning |
| --- | --- |
| Entry | A coordinate with a jar default `(k,a)`, a sibling module reference, or a JDK module `(k,a)` |
| Provider | Session, then this project's built siblings, then the entry's default; substitution is by exact coordinate |
| Binding | Entry, `k`, `a`, and origin. Cold boot has no session provider |
| `routeHash` | Merkle root hash of the `ContentList` of bound `k` in classpath order |
| `R` | Sum of `r` over that sequence, including repeated bindings; compared, never a storage key |
| `leafSetExt` | Merkle root of the external leaf-set ContentTree |
| `leafSetSib` | Merkle root of the sibling leaf-set ContentTree |
| `aSequence` | Annotation identities parallel to bindings; absent from resolution identities |

`ContentList` preserves order. The two leaf sets do not. A duplicate leaf is one definer and cannot conflict with itself. `R` alone is not a proof that the effective classpath is unchanged: a permutation preserves `R` but may change the winner of a conflict.

### 2.3 Source and binary leaves

| Projection | Content and identity |
| --- | --- |
| `m` | Canonical declaration key, including owner, kind, name and descriptor as applicable |
| `res` | Resolution facts, including the minimal warning projection javac reads |
| `T` | `m → res`, with `h = Digest(m \|\| res)`; `k = T.hash`, `r = T.sum` |
| `N` | One rekeyed entry per T fact with the same `h`; `N.sum = r` |
| `O` | `zstr typeKey → empty`, with `h = oSum`, the sum of that type's T facts |
| `E` | Resolution edges only, derived from `res` |
| `tail` | Full retained declaration/type annotations and parameter metadata |
| `A` | `m → tail`, with `h = Digest(m \|\| tail)`; empty tails have no entry |
| `EA` | Annotation-type edges derived from the encoded tail |
| `a` | `Digest(A.hash \|\| EA.hash)`; an exact content address |
| `L\|k` | Resolution leaf roots/counts, a function of `k`; never contains `a` or EA |
| `AL\|a` | Full roots of A and EA, sufficient to reopen either tree |

N keys are `zstr simpleName || u8 kind || [TYPE: zstr directOuter] || m`. `directOuter` comes from the class/source model, not punctuation in a binary name. The exact `innerName` is retained in the type's resolution facts. `N(owner, name)` selects member types directly enclosed by that owner, including names containing `$`.

The minimal warning projection records the class-file Deprecated attribute, Deprecated annotation state including `forRemoval`, and SafeVarargs presence. Full retained annotations also remain in A. `Deprecated.since` therefore changes A/a without changing T/k/r or stub bytes. Projections may overlap; dropping the full annotation would lose metadata. SOURCE-retention annotations are absent from A; Stage 3's source processor projection must retain them independently.

`κ_file = Digest(sourceBytes)`. `sum(file)` is the sum of its emitted fact identities. `Σ sum(file) = r` for a scope. Body edits may change κ while preserving every declaration fact.

### 2.4 Files, proofs and reverse consumers

F rows contain κ, size, modification time, fact sum, declared type keys, faults, exact T observations, own-leaf `r`, and expected-zero D/N observations.

| Header observation | Meaning |
| --- | --- |
| `T(type, TYPE, "")` | The single type-header fact |
| `T(type, FIELD, name)` | The named field range, including zero when absent |
| `T(type, METHOD, name)` | A named method group; an empty name selects the whole method contract |
| `D(type) = absent` | No effective declaration of that exact binary type under own-first binding |
| `N(owner, TYPE, name) = 0` | No direct member type in that exact N range |

The actual positive dependency is a T range sum, never the containing type's entire `oSum`. `ownR` and definer sums may support validation shortcuts; they do not replace the observations. Current `HeaderProof.valid` compares the observations directly. Its caller must already establish unchanged source bytes/options and use the current own leaf and bound route.

Each distinct dependency contributes one empty reverse key containing the dependency, project key and actual relative file path. Identical bytes in two paths remain two consumers. Stage 2 fills these keys during cold boot. It writes no body consumer or result records.

### 2.5 LOCAL

`projectKey = Digest(canonical project root path)`. Module descriptors, routes, `(k,a)` source bindings, F rows, header reverse keys and used definer records form the LOCAL ContentTree. A tree entry has the record key, an empty value, and `h = Digest(recordBytes)`. The tree's Merkle hash binds keys and record fingerprints; its sum is not used as a narrower semantic proof.

`LROOT|projectKey` holds FORMAT, the full LOCAL root, the MACHINE root commitment and model-byte hash, followed by a digest of the preceding bytes. It is published last after flush and sync. LOCAL shares MACHINE's store and node space, not a separate database per project.

### 2.6 Boot-local state

| State | Purpose |
| --- | --- |
| `Order` | A validated dependency DAG; cycles abort |
| `Built` | Completed source leaves and annotation identities by module/scope and coordinate |
| `Written` | Boot-local node deduplication and one leaf builder per exact k |
| `IndexMemo` | One future per `(external/sibling, leafSet)`; completed route states by route key |
| Route parents | Test → own main; main → first declared sibling's main; roots → shared JDK external base |
| `DefinerCounts` | Lazy persistent all-definer and multiple-definer trees; a balanced map assembles an uncached initial state |

There is no file-fact memo in the current cold path: each source is read and projected in its own compilation context. Content alone never authorizes reusing facts from a different binding. IndexMemo deduplicates exact leaf sets even when different route parents lead to them.

## 3. Reasoning

### 3.1 Resolved declarations

Parsing `List<Foo>` cannot produce the resolved descriptor and signature of a class file. Enter/member completion supplies those values. SourceFacts and ClassFacts encode the same structured facts. For equal APIs their T roots are equal, not merely their sums.

### 3.2 Executable bodies

Bodies, blocks, lambdas and declarations local to bodies are excluded. Constant initializers that contribute declaration values are resolved as part of headers. A broken body must not turn a valid declaration into a fault. Source type annotations use javac's already-computed declaration positions, without body attribution.

### 3.3 Coordinate substitution

A route stores the build's coordinate and order. Substituting a source provider changes its binding, not every stored route referring to that coordinate. Open-project and unsaved providers are session state; cold boot uses siblings and defaults. API-identical providers share k under this representation; annotation-only differences travel through a.

### 3.4 Ordered and semantic identities

Route hashes answer exact order/content questions. Leaf-set Merkle hashes key commutative definer parts. T/N range sums prove precisely the declarations observed. A/EA hashes and Diff answer metadata questions. A sum is never a content address.

### 3.5 Proof grain

For `K field`, adding an unrelated method to K does not change the type-header range. For `static final int X = K.VALUE`, an unrelated K method does not change the VALUE field range. Both proofs remain valid. Changes to the actual header or field invalidate them. The containing-type `oSum` remains useful in D and as a shortcut, not as the read identity.

### 3.6 Effective definers

DD contains types with one external definer; DS those with one sibling definer. DC contains conflicts within or across those parts and selects the first provider in the bound sequence. Values retain provider k; entry identities are `Digest(zstr typeKey || oSum)` and exclude provider storage identity.

A lookup consults DC first, then DS and DD. A cross-part conflict can also appear in both disjoint trees, so DC must take precedence. Header validation adds the scope's own leaf first. The common case ends at a single disjoint entry. Resolution equality of DD/DS/DC sums can support cutoff; route R alone cannot establish equal conflict winners.

### 3.7 Delta → candidates → validation

Candidate discovery has four separate inputs:
- T key/h deltas query exact named and whole-kind T prefixes.
- N key insertion/removal queries the direct outer/name N prefix; a same-key h replacement does not change its zero predicate.
- Effective type-presence toggles query D(type); an O value replacement does not change presence. Do not substitute raw DD/DS/DC membership changes for effective presence.
- Exact provider deltas query all T and N prefixes for the type. Read DC with `Diff.content`, since a different first k can select different N ranges even when the owner's oSum and DC h are equal. A same-key DC replacement changing only loser order has no reverse work.

The contract is: if an indexed observation changes, its consumer is among the candidates. Prefix-seeking current X returns project/path candidates; only then are F rows opened and exact proofs validated. A pure route permutation with conflicting constants and member-type visibility must reach T/N consumers with all leaf deltas empty.

N must be considered independently of the enclosing owner's oSum: adding `Base.Inner` changes N(Base,Inner) while leaving oSum(Base) unchanged. Similarly, an absent T field may become present. Reverse publication includes both positive and expected-zero observations.

This is a candidate-discovery primitive, not a warm driver. The caller supplies relevant semantic deltas, including effective-provider changes, and validates candidates. Discovery never scans F rows. Shared dependency prefixes may reach consumers in several projects; validation decides which current bindings changed.

### 3.8 Body records

Legacy ConsumerRecord/ResultRecord codecs exist but cold boot writes neither. They do not define Stage 3's current proof algebra. Stage 3 replaces those placeholders with its specified proofs, result identities and content-addressed outputs. Header reverse records already use actual paths independently of those placeholders.

### 3.9 Negative observations

An exact absent type is a D miss, not a simple-name search. An inherited member-type miss is an N range on an owner. Their zeros are not interchangeable. The collector records own-package and qualified package-prefix candidates, qualified/inherited lookup paths, imports and missing declaration targets; after the own leaf is sealed, only actual absences survive.

### 3.10–3.12 Model, order and faults

The model is build-tool data. Its module DAG controls scheduling; a dependent starts after required modules finish, and own main precedes test. Unknown modules, cycles, invalid options and unreadable inputs abort without publishing a new root. Parse and declaration-resolution faults are F-row data, allowing the rest of the project to commit.

### 3.13 Sharing

Semantic leaf, definer and stub records do not include checkout paths. Their content and tree nodes share across checkouts. LOCAL record keys include project and path, so LOCAL index nodes need not share across different project roots. No per-machine salt enters semantic content identities.

### 3.14 Stubs

Sibling modules compile against cached class-file stubs synthesized from T, never by re-entering siblings' source. Stubs contain resolution attributes and minimal warning annotations; they do not open A. The cache has an S list per leaf and an ST blob per type. The outer stub's key includes exactly the direct member-type data it emits, not each child's oSum. Appendix B.10 defines the key.

### 3.15 Defaults absent from MACHINE

A jar/JDK location missing from P is indexed through Stage 1's ArtifactJob into the shared node/leaf spaces. Its `(k,a)` becomes the route default. Stage 2 does not modify P or MACHINE's root. Unreadable locations bind to nothing and are reported; readable new jars are indexed rather than treated as missing declarations.

### 3.16 Session boundary

Session providers precede built siblings and defaults, but are absent in cold boot. Cross-project substitution depends on what is open now and is not persisted into another project's route.

### 3.17 Persistent derivation with explicit ancestry

The route plan selects a parent before workers run. Tests use own main; a main with siblings uses its first declared sibling's main; root modules use the common JDK external state and empty sibling state. The dependency DAG guarantees completion before the child needs the parent. Selecting a parent is a point lookup, not a scan of all completed states.

Each leaf set is a persistent ContentTree: key = k, value = empty, h = Digest(k). Its exact root hash is the set identity; the sum is not a proof input. Binding still visits model entries and builds canonical sets. Derivation compares parent and target roots with Diff, skipping equal chunks rather than merging flat lists.

Each distinct (kind, leafSet) is claimed once per boot. Read DF|leafSet before folding. A hit restores its all-definer, multiple-definer and disjoint roots lazily, with zero O opens and no counts reconstruction. A miss diffs leaf sets, opens only removed/added O leaves, and applies changed type entries to the parent's persisted multimap and its multiple-definer projection. A first uncached base assembles and persists these trees once.

The same touched keys update DD/DS with ContentTree.apply. DF retains enough mathematics to continue the fold on another project or cold boot; DD/DS alone are not the fold state.

DC|routeHash is checked before construction. Exact hits emit zero conflict nodes. For an uncached route with a parent, Diff of their ordered ContentLists identifies added/removed/moved leaves; only those leaves' O keys can change conflict membership or winner. Recompute those entries and apply them to the parent DC. An initial route without a parent builds its conflicts once, using external multiples and sibling types. A future per exact route returns a readable root to every waiter. Publish route ancestry only after all roots are flushed; shared pending node writes must also be visible if another worker claimed them.

The selected parent need not be the globally nearest leaf set. Costs below are measured relative to that explicit parent. Unrelated root routes with partially overlapping non-JDK jars may revisit those jar O trees; exact identical sets still share one fold. This implementation removes quadratic nearest-state search without claiming a globally optimal derivation tree.

### 3.18 Exact header collection and validation

After SourceFacts completes declarations, one ProofCollector gathers reads and absences. Type syntax consumes type headers. Constant expressions consume named fields and the type headers used while searching inheritance. Annotation checking consumes the annotation type and method contract, including defaults. Intermediate hierarchy headers and missing competing members are required proof inputs.

For `Sub → Mid → Base`, resolving inherited `Sub.Inner` records traversed headers and N zeros before the winner. Adding Sub.Inner or changing Mid's superclass invalidates the proof. The collector treats lookup branches independently, caches hierarchy work per unit, and includes static on-demand competitors and unresolved constant fields. It does not widen an uncertain read to the whole route.

E/EA targets seed implicit declaration dependencies but are not a complete lookup proof on their own. Own-file declarations are covered by κ; reads of other files in the own scope still validate against the own leaf. The resulting T sums and true D/N zeros are persisted after the leaf is sealed.

## 4. Process and records

1. Validate model and dependency order; read the committed MACHINE root; resolve JDK/jar defaults; construct ordered routes and their parent plan.
2. For each ready module, main then test: bind route, obtain sibling stubs, enumerate/hash sources while reading, parse/enter/complete declarations, extract facts and proof observations, sort facts, build T/N/E/O and A/EA, publish L and AL, register Built, derive definer states and seal F proofs.
3. After all jobs finish, write MOD, RT, SL, F and used DF/DD/DS/DC records. Add one empty logical X key per header dependency/path to the sorted LOCAL ContentTree.
4. Flush records and nodes, sync, then atomically publish LROOT with inserts/deletes for the current raw X secondary index. A failed job leaves no new commitment or published reverse change.

| Record | Meaning |
| --- | --- |
| `L\|k`, `N\|hash` | Shared resolution leaves and all content-tree/list nodes |
| `AL\|a` | Full roots of the shared annotation projection |
| `S\|k`, `ST\|stKey` | Derivable stub list and per-type class bytes |
| `MOD\|project\|module` | Module descriptor |
| `RT\|project\|module\|scope` | Entries and bound route/leaf-set identities |
| `SL\|project\|module\|scope` | Own source `(k,a)` binding |
| `F\|project\|path` | Source row with header proof and absences |
| `DD\|leafSetExt`, `DS\|leafSetSib`, `DC\|routeHash` | Definer roots used by this project, including the shared JDK base when derived |
| `DF\|leafSet` | Persisted all-definer, multiple-definer and disjoint roots |
| `X\|H22\|...` | Current empty dependency/project/path reverse records |
| `LROOT\|project` | Current commitment; prior commitments retained under numbered keys |
| `C\|...`, `RS\|...` | Legacy reserved codecs; no records written by Stage 2 |

## 5. Pseudocode

### 5.1–5.2 Trees, lists and Diff

ContentTree sorts keys; ContentList retains input order. Both use content-defined chunks, Merkle hashes, algebraic sums and the configured B/CAP. Equal hashes skip subtrees. Tree Diff compares keys and entry h. It is a semantic delta: when h projects away part of the value, a value-only change at equal h is omitted. Changed provider k can therefore change a definer tree's exact hash without appearing in its semantic Diff. For manifests whose h binds the entire key/value pair, Diff is also the exact content delta. Use Diff.content for exact key/value/h deltas, including definer routing values omitted by semantic Diff. List Diff preserves positions. Apply consumes removed keys and added entries, sharing unaffected nodes. Local edits resynchronize content boundaries; they need not change exactly one chunk.

### 5.2a Stubs

```text
stubs(leaf):
    try S|k and each referenced ST blob
    otherwise read the leaf's T and O
    for each type: compute stKey from oSum and emitted direct-member-type data
                  read ST|stKey or synthesize/write it from res
    write S|k as the ordered list of type names and stKeys
```

### 5.3 Bind

```text
for entry in route order:
    (k,a) = session[coordinate] ?? Built[coordinate] ?? default(entry)
    if absent: omit binding
    append k to ContentList with h = L(k).r
    append a to the parallel annotation sequence
    collect k in external or sibling set according to binding origin
build canonical ContentTrees for ext and sib: k -> empty, h = Digest(k)
return routeHash, R, extRoot.hash, sibRoot.hash, bindings
```

### 5.4 ModuleJob

```text
bound = bind(route)
units = headerCompile(scope sources, jars and sibling stubs, options)
for each unit:
    declarations = SourceFacts(unit)
    observations = ProofCollector(unit)
    keep κ, stamp, facts, declared types and faults
build leaf from sorted facts; build AL separately; register Built(k,a)
states = definerIndex(route, bound)
for each pending file:
    resolve exact observations under own-first binding
    persist T range sums and true D/N zeros in F
```

### 5.5 DefinerIndex

```text
parent = planned route parent's completed states
         or (shared JDK external state, empty sibling base)
for kind in [EXTERNAL, SIBLING]:
    state = IndexMemo.once(kind, leafSet.hash, () ->
        if DF|leafSet.hash exists: restore roots lazily and return
        deltaLeaves = Diff(parent.leafSet, leafSet), or all leaves for an initial base
        changed = collect types from O of removed/added leaves
        counts = apply(parent.allDefiners, changed)
        multiples = apply(parent.multipleDefiners, changed)
        disjoint = apply(parent.disjoint, touchedTypes), or cold build
        flush nodes; persist DF and DD/DS; return state)
dc = IndexMemo.conflicts(routeHash, () ->
    if DC|routeHash exists: return stored root
    if parent exists:
        changedLeaves = Diff.lists(parent.route, route)
        touched = O keys of added/removed/moved leaves
        root = apply(parent.DC, recomputed conflicts at touched)
    else: root = initial conflict build
    flush nodes; persist DC; return root)
publish route states, ordered route root and dc
```

### 5.6 Reverse publication and lookup

```text
for row in files:
    write F|project|path
    for dependency in distinct(row.T reads + row.D/N absences):
        add logical X|H22|dependency|project|path = empty to LOCAL entries
build LOCAL over all used records; flush; sync
atomically: keep previous LROOT in history
            delete removed X keys and insert added X keys from Diff(oldLOCAL,newLOCAL)
            replace LROOT

candidates(deltaT, deltaN, effectivePresence, exactDefiners):
    prefixes = T named/whole-kind + N key-presence + D presence-toggle
               + type-wide T/N for changed effective providers
    discard duplicate/covered prefixes; seek current X keys
    decode project/path; return distinct candidates
    // no LROOT, LOCAL membership or F reads
```

Current raw X keys are a secondary index maintained only by LROOT publication; ordinary put rejects them. Historical LOCAL nodes already preserve their empty values, so historical raw keys are unnecessary. X|H22| isolates earlier raw history. Lookup work is current prefix fan-out, independent of the number of obsolete consumer paths. The publication diff may read the previous root; candidate queries never do. No scan of unrelated files or reverse prefixes is permitted.

### 5.7 Boot decision

Open the MACHINE generation for current FORMAT. Compare the stored project's LOCAL format with LocalFormat.of(current machine format). A matching commitment logs one skip line because warm boot is not implemented. Missing or incompatible LOCAL format causes a cold rebuild in the shared store; old roots remain history. A changed MACHINE FORMAT selects a different generation directory. Never decode old fact codecs as current or migrate records in place.

## 6. Code structure

`jvmd-core` owns ContentTree, ContentList, Diff and the digest/sum algebra. `jvmd-index/layer/machine` owns ClassFacts, Fact, Ann, Res, LeafBuilder, AnnotationLeaf and Stubs. `layer/local` owns the project model, Bind/Bound/Route, SourceFacts, ProofCollector, HeaderProof, F/SL/root codecs, DefinerIndex/DefinerCounts and ReverseIndex. MACHINE code does not import LOCAL.

`jvmd-index-rocks/layer` implements the shared store, generation isolation and exact prefix seeks. `jvmd-boot/cold/stage2` owns Stage2, Boot, Defaults, Order, ModuleJob, HeaderCompiler, Built and IndexMemo. Stage 2 uses Stage 1's artifact enumeration/indexing and Written; Stage 1 never imports Stage 2. Header javac work lives behind the existing compiler exports. Legacy analyzer/runtime code is not an implementation dependency of these layers.

Build-tool integration supplies the model. The Maven integration helper and measurement oracle live in tests. Warm remains unimplemented. Stage 3 processor and body implementations must build on this foundation rather than import the legacy attribution pipeline.

## 7. Properties, costs and invariants

### 7.1 Guarantees

Equal source/class APIs give equal k and all resolution roots. Equal retained annotation data and parameter options also give equal a. κ covers source bytes independently. Header reuse compares the precise stored reads; reverse discovery includes zeros and retains distinct paths. Independent job ordering does not change LOCAL content records/root, though reordering raw model JSON changes modelHash in LROOT.

### 7.2 Costs and limits

Header work includes parsing source text, resolving declarations/constants, extracting metadata and collecting lookup proofs; it is not just a count of declarations, since executable text must still be parsed. Facts must be sorted for each scope. Default preparation is proportional to model route entries plus any newly indexed artifacts.

Parent selection costs route construction plus one point lookup per route. Canonical binding still visits model entries. Leaf-set Diff, DF and DD/DS apply depend on changed chunks/paths and changed leaves' O entries. Exact DF hits reopen no O universe. Exact DC hits perform no build/apply/node emission. A new DC with ancestry opens O only for changed/moved leaves and applies touched entries; an initial conflict build still visits external multiples and sibling types. Costs are relative to the explicitly selected parent, not a globally optimal route graph.

Reverse lookup costs prefix seeks plus current matching keys; LOCAL membership reads are zero. Historical LOCAL trees consume storage without adding prefix hits. Count actual raw prefix hits and returned consumers under churn. Also measure DF cache hits/O opens, DC cache hits/full builds/applies/touched types/node emissions, and leaf-set entries decoded during Diff. A fresh repeated cold boot and another checkout with identical routes must report zero O opens for folding, zero leaf-set comparisons and zero DC reconstruction.

Memory includes active javac tasks/scope facts, persistent state versions, completed leaves and file rows, route maps and dedup sets. It is not independent of project size. No full type-universe copy occurs for each derived state. Measurements report wall time, accumulated header/fact/definer work, fold counts, node writes, faults, source/class parity and sampled heap with GC limitations disclosed.

### 7.3 Required invariants

1. Source/class T/k/r, O, N and E agree; A/EA/a also agree with matching metadata options. Cover generics, records, type annotations, receivers, arrays, parameters, nesting and dollar names.
2. Route R is the sum over the bound sequence; set identities are canonical ContentTree roots over distinct k; routeHash matches a fresh ContentList.
3. Independent module order and worker count preserve LOCAL content; modelHash separately identifies model bytes.
4. Route permutations preserve R/leaf sets and update order-sensitive conflicts when winners change. Annotation-only edits preserve resolution identities.
5. Local route changes retain unaffected chunks; verify sharing without assuming exactly one rewritten chunk.
6. Diff reports key/h changes and preserves semantic equality when provider values differ at equal h. Apply with the full entry delta reconstructs the expected root; Diff alone supplies that full delta only when h binds the complete value.
7. Cold and derived definer folds yield identical disjoint/conflict roots. Duplicate leaves are not conflicts.
8. File fact sums partition the scope r.
9. Parse/declaration faults preserve other facts and permit commitment; model/cycle faults do not.
10. API-identical source/binary provider swaps preserve k and semantic proofs; metadata may differ independently.
11. Before LROOT publication, reads are MACHINE/shared derivable records, not prior project F/RT/MOD/X/C/RS state. DF/DD/DS/DC and S/ST cache reads are allowed. LROOT publication alone reads the prior commitment to reconcile the current secondary index.
12. Semantic leaf/definer/stub data share across checkout paths; project-index nodes may differ.
13. All algebraic tests run with SHA-256 and a second Digest implementation.
14. Stubs reproduce the resolution facts and compiler warnings needed by dependents; no A reads. Changing only a nested member API does not change the outer stub unless its emitted member-type entry changes.
15. Identical source bytes under different bindings produce independently resolved facts; no κ-only reuse.
16. Missing P entries for readable artifacts cause indexing without changing P/ROOT.
17. Sibling edits preserve external DD bytes; only routes whose bindings change receive changed sibling/conflict identities.
18. Exact header proof minimality: unrelated methods preserve type-only and constant-field reads; actual header/constant/default/hierarchy changes invalidate.
19. Lookup completeness: package-prefix reclassification, inherited qualified types, intermediate supertypes, branch-specific misses, static on-demand competitors and unresolved constants are covered.
20. Reverse conservation: every F dependency has one X key per project/path, including zeros; duplicate bytes retain both paths.
21. Pure route permutations reach T and N consumers through exact DC deltas even with empty T/N/O leaf deltas. N/O same-key h replacements do not fan out zero/absence proofs. Loser-only DC order changes do not fan out T/N. Validate candidates exactly.
22. Persisted DF reopens lazily across boots/checkouts, preserves historical snapshots and opens only changed O leaves for a derived state; large/random histories equal fresh folds.
23. Planned ancestry, persistent leaf-set Diff and conflict apply preserve fresh-build roots across workers and add/remove/move/repeat histories. A small route edit never reopens an unrelated large O universe. Shared nodes are readable after any root-publishing flush.
24. `Deprecated.since` changes A/a only; full Deprecated/SafeVarargs values remain recoverable; source and binary A match.
25. Incompatible MACHINE parser/LOCAL versions cold boot; old generations remain opaque/untouched. A second start of the current format takes the skip branch.
26. Repeated reverse churn leaves prefix work proportional to current consumers in memory and Rocks, including reopen. Old LOCAL roots still verify after current X deletion. Failed publication leaves root and reverse keys unchanged.
27. Fresh repeated cold boots and another checkout reuse exact DF/DC: zero O-universe traversal and zero conflict build/apply/node emission. Count work, not just equal output bytes.

## 8. Boundaries

Full body attribution, processor execution/reuse, generated-output reconciliation, executable outputs, navigation/body diagnostics and overlays are Stage 3 work. The Stage 2 baseline runs with `-proc:none`; generated sources already listed in model roots are ordinary inputs. Appendix F will replace that baseline with explicitly proved processor reads. Its PD projection and generated ContentTree must follow the user's amendments.

Current header compilation is classpath mode. Module descriptors are still emitted as facts from parsed module directives. JPMS readability/module-path execution and release-specific ct.sym leaf views remain explicit limitations; do not claim full module-mode or historical-JDK equivalence. The model's release controls supported source language level, with preview/minimum-source normalization recorded in MOD. In classpath mode accepted `--add-exports` targets are mapped to ALL-UNNAMED.

Kotlin/Scala/Groovy contribute through compiled class files, not SourceFacts. Build-model acquisition, watching/debouncing, session substitution, warm updates, garbage collection and remote cache transport have separate owners. Stage 2 exposes enough persisted structure for those consumers without implementing their drivers.

## 9. Persistence

One RocksDB per MACHINE generation contains shared nodes/leaves and project-prefixed LOCAL records. One atomic write retains the previous LROOT, advances its LSEQ counter, updates current bindings/X inserts/deletes, and swaps LROOT. Rooted values are prepared as immutable BV|Digest(value) records before this publication; retained roots resolve by entry hash. CF already has its own content address and X has an empty value. Historical empty X values live in immutable LOCAL nodes. Garbage collection of other unreachable records and the history retention policy are deferred.

Current combined representation is `layout=4;parser=6;local=22`, plus digest/JDK/javac fields. Parser 3 isolated the changed warning/N/innerName representation from PR #59; parser 4 retains full warning annotations in A/EA; parser 5 preserves exact Java String code units in constants and annotation values/defaults. LOCAL 2 added recoverable bindings/absences, LOCAL 3 exact T header observations, LOCAL 4 path-addressed reverse records, LOCAL 5 current reverse publication and persistent leaf-set identities/definer state. These changes require cold rebuilding, never migration. A shared derivable cache may be read directly; node/AL dedup must not add one storage-existence read per content-addressed write.

## 10. Implementation and completion rules

Implement the specified projections and exact reads; do not substitute containing-type/module/route identities for unsupported observations. Preserve key/value binding in every semantic entry. Keep sum checks in meaningful tests, with format and ordinary input validation in production. Scope writes by record type, flush before sharing roots between workers, and publish the commitment last.

Completion requires the invariants above under both digests, the real Rocks reopen/format tests, and a frozen-source multi-module Maven oracle reporting source/class equality for every scope with faults and any metadata differences named. Measurement success is a statement about this Stage 2 foundation, not completion of Stage 3 or the legacy runtime's separate contract.

## Appendix A. Project model

```json
{
  "root": "/abs/project",
  "jdkHome": "/abs/jdk",
  "modules": [{
    "name": "server",
    "coordinate": "org.example:server:1.0",
    "release": 25,
    "moduleInfo": false,
    "javacOptions": ["-parameters"],
    "scopes": {
      "main": {
        "sourceRoots": ["server/src/main/java"],
        "dependencies": [{"coordinate": "org.example:lib:1.0", "location": "org/example/lib/1.0/lib-1.0.jar"}]
      },
      "test": {"sourceRoots": ["server/src/test/java"], "dependencies": []}
    }
  }]
}
```

Dependencies are already mediated, ordered and transitive. Each has exactly one of location or module. Test dependencies are additions to main. JDK entries are supplied from jdkHome and not listed. A missing source root is skipped; an empty scope has an empty leaf. Reject duplicate module names, unknown sibling modules, malformed coordinates, invalid JDK homes and cycles. Store normalized MOD/RT/SL records and the hash of original model bytes, not a second serialized model.

## Appendix B. Codecs

Primitives use the existing Codec: big-endian integers, fixed-width `id`, length-prefixed `str`/`lenBytes`, NUL-terminated `zstr`, u32-counted lists and explicit optional markers. Literal ASCII tags include their displayed `|`; variable fields below use explicit encodings. Human-readable key separators in MOD/RT/F/SL follow LocalStore's key functions; X's suffix uses the self-delimiting encoding in B.9.

Java String values use `utf16 = u32 codeUnitCount || u16 codeUnit[codeUnitCount]`, without a byte-order mark or normalization. This applies to ConstantValue tag 8 and annotation value tag `s`, recursively through nested annotations, arrays and annotation defaults. It preserves NUL, supplementary pairs and unpaired surrogates. `str` remains standard UTF-8 for identifiers/descriptors and the other existing structural fields. Check lengths before allocating. A source/binary equality check alone is insufficient: test decoded values and native client class bytes against real dependencies and their stubs, since both fact producers could otherwise agree on the same lossy encoding.

### B.1 MachineLeaf and annotation roots

L keeps the existing resolution-root/count codec, with no annotation identity. `AL|a = Root(A) || Root(EA)`, each root encoded as hash, sum, count and level. Binding records hold `(k,a)`. This prevents two equal T leaves with different metadata from assigning different values to L|k.

### B.2 Tree/list markers

```text
tree0 = u8 0 || u8 0 || u32 count || entry[count]
list0 = u8 0 || u8 1 || u32 count || (id element || id h)[count]
```

Interior nodes use the existing content-tree child metadata. The explicit kind marker distinguishes ordered lists and key-sorted trees.

### B.3 Model, binding and file records

```text
MOD value = str coordinate || u8 effectiveRelease || u8 moduleInfo
          || list<str> javacOptions || list<str> mainRoots || list<str> testRoots
RT value  = list<entry> || id routeHash || id R || id leafSetExt || id leafSetSib
entry     = str coordinate || u8 kind || payload
  jar 0: str location || opt<id> defaultK || opt<id> a
  sibling 1: str moduleName
  jrt 2: str jrtModule || id k || id a
SL value  = id k || id a
F value   = id κ || u64 size || i64 mtimeNanos || id sum || list<zstr> typeKeys
          || list<(lenBytes m || str reason)> faults
          || list<(zstr typeKey || u8 kind || zstr name || id sum)> T_reads
          || id ownR || list<(u8 form || zstr type || zstr name)> absences
```

Scope is u8 0 main or 1 test. F's path is in its key only, relative to project root. An empty fault m means the whole file. T kind is TYPE/FIELD/METHOD; TYPE requires empty name. Absence form 0 is exact D(type) with empty name; form 1 is N(owner,name). Expected zero is implicit for these absences; zero T sums are explicit in T_reads.

### B.4 Definer records

```text
DD|leafSetExt = Root(disjoint external tree)
DS|leafSetSib = Root(disjoint sibling tree)
DC|routeHash  = Root(conflict tree)
DF|leafSet    = Root(all definers) || Root(multiple definers) || Root(disjoint)
leaf-set entry: key = k, value = empty, h = Digest(k)
definer-state entry: key = zstr typeKey, value = sorted list<(id k || id oSum)>, h = Digest(key || value)
multiple-definer tree: the same entries, restricted to lists of size > 1
root         = id hash || id sum || u32 count || u8 level
disjoint entry: key = zstr typeKey, value = id k, h = Digest(key || oSum)
conflict entry: key = zstr typeKey, value = id firstK || list<id> allK,
                h = Digest(key || first.oSum)
```

Provider k is storage; oSum is the DD/DS/DC semantic projection. Exact hashes may differ while sums agree. DF roots preserve exact multimap content for later point updates; their sums are not new proof inputs. Use exact Diff.content for DC routing changes.

### B.5–B.6 Reserved legacy consumer/result codecs

Existing `C|κ|leafSetExt` and `RS|κ|leafSetExt` APIs are unused placeholders. C encodes a u32 list of `(u8 kind, id identity, kind-delimited key)`; RS encodes version, diagnostics, references with length-prefixed keys, and class-file content references. Old kinds 1–8 are not the current header proof grammar. They have no live reverse list and cold boot writes no entries. Stage 3's specification supersedes them; they must not be reused to restore broad package/type dependencies or routeHash-keyed proof caches.

### B.7 LOCAL root

```text
LROOT|project = str FORMAT || id local.hash || id local.sum || u32 count || u8 level
              || id machineRoot || id modelHash || id commitment
commitment = Digest(all preceding value bytes)
history key = LROOT|project|22|u32 n, counting from 1
sequence key = LSEQ|22|project, updated atomically with root/index publication
```

### B.8 FORMAT

```text
machine = layout=4;digest=<name>;jdk=<feature>;parser=6
local   = <machine>;local=22;javac=<full runtime version>;locale=root
```

Parser changes when identical class bytes would yield different retained facts/projections. LOCAL changes when its record layout or persisted proof meaning changes. Incompatible commitments cannot take a current-format skip.

### B.9 Header reverse keys

```text
key = ASCII "X|H22|" || u8 form || zstr type || u8 kind || zstr name || id project || zstr module || u8 scope || zstr path
value = empty
form 0: T(type, kind, name)
form 1: N(owner, TYPE, name)
form 2: D(type, TYPE, "")
```

TYPE is 0, FIELD 1, METHOD 2. Reverse form numbers differ from F's absence numbers; use the explicit mapping. The prefix through name identifies a dependency; suffix identifies a consumer. One reverse key is added to LOCAL for each distinct F dependency. Prefix readers use seek, not enumeration of all keys. The current raw index contains only published consumers; historical raw entries are neither retained nor scanned. There are no κ/leaf-set consumer lists to collapse paths or rewrite wholesale.

### B.10 Stub records

```text
S|k      = list<(str internalName || id stKey)>
ST|stKey = lenBytes classBytes
stKey    = Digest(zstr typeKey || id oSum || sorted direct-member-type encodings)
member encoding = zstr memberInternalName || opt<str> exactInnerName || u16 emittedInnerFlags
```

Sorting is unsigned byte order. A type without member types has the original Digest(typeKey || oSum) key. A child method change cannot affect an outer stub that only emits the child's name/nesting/flags. S/ST are shared derivable caches and need not be retained by project roots.

## Appendix C. Header compilation and SourceFacts

### C.1 javac phases/options

HeaderCompiler parses with the chosen supported source level, bound JDK, explicit jar/stub classpath, `-proc:none`, `-implicit:none` and no implicit source discovery. It filters compiler-output/classpath/processor options that would override those inputs. Preview uses the running feature version; too-old source levels normalize to javac's supported minimum. MOD records the effective release. Current classpath-mode `--add-exports` targets become ALL-UNNAMED. `--release` is not used to substitute ct.sym behind the bound JRT leaves.

Module-info units are parsed separately for descriptor facts and excluded from classpath Enter. Other units go through JavacTaskImpl.enter and recursive member completion. Fetch supertypes, type parameters, permitted subclasses and record components. Never call analyze/generate. The explicit empty source path prevents javac from silently entering dependency sources.

### C.2 Canonical declaration terms

Binary names come from Elements.getBinaryName, converted to internal names. Erasures supply descriptors; generic signatures follow javac's class-file grammar and declaration order. Inner constructors prepend the outer-instance descriptor where emitted; enum constructor descriptors include synthetic leading name/ordinal parameters. Flags match the binary masks. Nested type outer, exact simple inner name and nest host come from declaration structure.

Thrown types, bounds, interfaces, permitted subclasses and record components use their canonical declared order. VariableElement.getConstantValue supplies primitive/String constants, including eligible instance final fields. ExecutableElement.getDefaultValue supplies structural annotation defaults. Module directives encode the module fact, with required-module versions obtained from the bound view.

Retained annotation mirrors use the common Ann codec and resolved values, split by CLASS/RUNTIME retention. Type-use annotations use javac TypeCompound target positions, indices, bounds and paths for declarations, including receivers, generic bounds, arrays, formal parameters and records. SOURCE retention and body-local annotation positions are excluded from A. Full Deprecated/SafeVarargs remain in A; their minimal warning projection also enters res. Parameter names/flags follow emitted MethodParameters and the -parameters option, including mandated constructor parameters.

### C.3 Synthesized members

Match class-file facts for default constructors, enum constants/values/valueOf, record canonical constructors/accessors/toString/hashCode/equals, and nested constructor descriptors. Synthetic/bridge members and class initializers are excluded consistently. Record completion at this phase requires explicitly accounting for members javac materializes later; do not infer them from executable body attribution.

### C.4 Excluded types

Local and anonymous types have no source-level dependency API and are excluded on both sides using the binary/source enclosing information. Their bodies are not entered to manufacture facts. Lambdas and compiler-generated synthetic members likewise do not contribute header facts.

### C.5 Order

Declarations arrive by source file; emit canonical m then sort facts unsigned by m for the tree. Duplicate declarations become faults on the later source/declaration rather than an unhandled chunker failure.

### C.6 Faults

Parse errors fault a whole file. Erroneous declaration types/defaults fault the affected declaration. Preserve other usable declarations, including independently valid nested declarations. Bodies that fail to compile are outside this stage. Missing lookups must still contribute absences so later arrivals can invalidate retained faulted headers.

### C.7 Equality oracle

Compile fixtures and the real project with javac/Maven, run ClassFacts on their output and SourceFacts on the headers, compare exact T/k and the resolution roots. Compare A/EA/a separately under matching metadata options. This catches descriptor/signature/constant/warning differences and independently catches annotation data loss. Freeze source files throughout the real-project oracle so it compares one program version.

### C.8 Stub sufficiency

Emit this_class, flags, superclass/interfaces, signatures, permitted classes, nesting/member-type entries, record components and required annotation-type meta-annotations from T. Emit fields/methods with descriptors, signatures, exceptions, constants, defaults and warning attributes. Concrete methods have no Code; these are javac input stubs, never executable classes. Full metadata such as Deprecated.since is not needed for stub compilation and A is never opened. Test facts and relevant warnings against real classes, not only absence of errors.

## Appendix D. Consumers enabled by this foundation

API diff is Diff(T/O); metadata diff is equality of a then Diff(A/EA). Declaration upgrade impact is changed T/N/D prefixes → X → candidate F validation, including lookup absences. Stage 3 adds equivalent body-level proofs and executable results. Behavioral changes behind an equal API still require body/test analysis.

Negative lookups are persisted observations with reverse reachability. Kept roots support content-history comparison; historical values are resolved by their immutable BV entry hashes, never by current raw mutable bindings. Shared semantic nodes/leaves/definer/stub records can be transported and digest-verified by a future cache. Each application uses existing projections instead of adding broad identities.

## Appendix E. Reconciliation record

The PR #58 review introduced split definer parts, header proofs, module descriptor facts and shared cache reads. PR #60 and its follow-up replace the earlier whole-oSum header dependency and κ/leaf-set reverse lists with exact T/N/D observations and actual consumer paths; retain persistent counts and explicit route ancestry; preserve full warning metadata; and enforce format isolation.

Earlier statements that source k may differ because tail is inside T, source type annotations are deferred, all warning annotations are absent from A, nearest-state search is required, counts may be copied wholesale, incremental tree editing is future work, X is empty after cold boot, or memory is independent of project size are superseded. Historical acceptance of error-only stub tests does not exclude compiler warnings from the current resolution contract.

Current completion evidence belongs in the associated progress/measurement notes. Remaining Stage 3 processor replay, body attribution/results/driver and the thirty Stage 3 invariants are not claimed complete by this reconciliation.
### PR62 source-model and publication amendment (2026-10-07)

Current LOCAL layout is 22. Header reverse keys use X|H22| and the SourceUnit suffix (module, scope, path). Rooted record values are immutable BV|Digest(value) blobs; current raw keys remain bindings and cannot resolve historical snapshots. LROOT history uses a versioned LSEQ counter updated atomically with the root/current reverse index, without a history scan.

Source metadata uses PM trees of exact type/package keys to path plus PE declaration identity. PE nodes contain one declaration and ordered child IDs; nested declarations are shared, with lazy memoized reads. PB per scope indexes package presence/first metadata origin. Existing own/DD/DS/DC indexes select type definers, with the first occurrence of that k in the exact ordered origin binding selecting the source PM view. Equal T identities never identify source metadata globally. PB/PE roots are lookup/storage state and are not widened processor proof or ACI inputs.
