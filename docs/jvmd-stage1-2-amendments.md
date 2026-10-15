# JVMD — Stage 1 and Stage 2 amendments

**Normative V1 specification · 8 October 2026**  
**Starting point:** [`maxjay/jvmd` main at `33a9032`](https://github.com/maxjay/jvmd/tree/33a9032d0a22f87f2ee306f5e2d935190db6b691)  
**Compiler boundary:** one pinned JDK 25 `javac`; supported language and bytecode targets 8–25, subject to javac's actual option validation.

This amends the existing MACHINE and LOCAL cold-boot contracts. All original requirements not expressly amended here remain in force. The deliverable is **a correct, reusable compiler environment for a subsequent Stage 3**, not Stage 3 itself. No LIVE engine, warm scheduler, body-result cache, compiler instrumentation or derived-observation DAG is introduced.

## 1. Non-negotiable invariants

1. **One physical image, one extraction.** Distinct class-file bytes are decoded once per parser format, regardless of archive, release or project. Expected-entry-name validity and provenance remain per occurrence.
2. **Separate equivalences.** A physical image, its resolution projection `T`, its static compiler-reader metadata `META`, and its selected origin have separate identities. `k = root(T)` continues to determine the existing `L|k`, `N`, `E` and `O` resolution structures.
3. **No cross-product materialisation.** Java 8–25 variants are finite change points. A canonical release-selected leaf is materialised only when required. Unchanged projected facts reuse the existing Merkle nodes.
4. **One authoritative lookup policy.** The selected owner, error, absence and platform API match the actual compilation mode and provider order. A class that is selected but unreadable is not absent.
5. **Rooted immutable state.** Every published record is recoverable by its committed root. No mutable alias can change an older snapshot's meaning. Additive sums are equality stampers, never storage addresses.
6. **Precisely scoped dependency evidence.** Header proofs record positive and negative lookup outcomes. Stage 2 exposes a root-bound query and transition interface to Stage 3; a coarse compiler input remains available wherever a narrower observation has not been proved sufficient. No unproven semantic dependency is silently dropped.

### Persisted objects and their consumers

| Object | Consumer and purpose | Incremental/storage cost |
|---|---|---|
| Physical image facts and archive release catalogue | Construct the *correct* selected class for any target without reparsing the same bytes | One extraction per distinct image; sparse owner/version events |
| Independent `T` and `META` | Reuse resolution-equivalent APIs without losing javac-readable private/annotation information | Only changed projected facts acquire different roots |
| Derived release leaf cache | Supply Stage 2 with canonical full selected leaves when demanded | Only requested selection states; not authoritative MACHINE data |
| Version-aware `DF`/`DD`/`DS`/`DC` postings | Identify exact first providers and affected owners without scanning routes or projects | Changed-owner/overlap work; extends existing indexes |
| Fixed platform identity | Distinguish historical `--release` APIs from current/custom compiler platform inputs | One small binding; no eager eighteen-way JDK index |
| Root-selected immutable LOCAL records (including header `X`) | Recover historical routes, headers and absence consumers exactly | One immutable value per changed record; no separate `X` tree |
| Root-bound environment query/transition interface | Let a later Stage 3 evaluate *its* questions against old/current compiler inputs and select changed consumers without rescanning projects | Read-only facade over the existing catalogues, route and definer indexes; no new persisted index |

No other persistent index is required by this amendment. Winning sums and winner/absence conditions are **derived from existing postings** rather than materialised as independent databases.

## 2. Shared format and canonical structures

### 2.1 Canonical codec

Keep `Codec.Writer`, `Codec.Reader` and fixed-width, big-endian field encodings. Replace the string encoding with lossless modified UTF-8 over Java UTF-16 code units:

```text
str(s)  = u32(byteLength(MUTF8(s))) || MUTF8(s)
zstr(s) = MUTF8(s) || 0x00

writeString(s, terminated):
    for each Java char c in s:
        if c == 0:          emit C0 80
        else if c <= 0x7F:  emit one byte
        else if c <= 0x7FF: emit two bytes
        else:               emit three bytes
    if terminated: emit 00
```

Use the existing expandable writer, **not** `DataOutputStream.writeUTF`'s 65,535-byte frame. Read back the exact UTF-16 units; reject malformed/noncanonical encodings and invalid record framing. Update *all* persisted key writers and readers that carry JVM/class-file strings (`Keys`, edge parsers and raw-prefix consumers), not just `Codec.Reader`; audit any direct `getBytes(UTF_8)` / `new String(UTF_8)` boundary. The separately executed Java conformance fixture fixes the wire format; production Java regression tests protect its implementation.

### 2.2 Hashed identities

Keep `Digest.Hasher` streaming for large physical inputs. Distinct stored object families use canonically framed inputs and these **fixed one-byte domain identifiers** in the new format (declared once in a central enum, not reallocated by individual implementations):

```text
01 FACT          02 META           03 EDGE         04 NODE
05 CLASS_IMAGE   06 ARCHIVE        07 JDK_MODULE   08 VIEW
09 RECORD        0A META_LEAF      0B LOCAL_ENTRY  0C GENERATION
0D CATALOGUE     0E PLATFORM
```

`GENERATION` includes a record-kind discriminator for MACHINE versus LOCAL. In particular, a JDK-module identity cannot collide by *encoding* with an archive's byte identity. Do not create per-call ad hoc concatenations.

```text
factH    = H(FACT || len(m) || m || len(res) || res)
metaH    = H(META || len(siteKey) || siteKey || len(value) || value)
nodeID   = H(NODE || completeNodeEncoding)
imageID  = H(CLASS_IMAGE || classBytes)       // streamed
archiveID= H(ARCHIVE || archiveBytes)         // streamed
viewID   = H(VIEW || rendererVersion || targetViewFormat || orderedOwnerFacts)
recordID = H(RECORD || recordKind || len(bytes) || bytes)
```

The *same* `factH` contributes to `T`, `N` and `O`, preserving their additive equivalence. `Sum` remains modulo the existing prime. A fingerprint can suppress semantic work under the documented non-adversarial assumption, but cannot name a stored object or certify a physical provider. Existing unrelated hash call sites retain their old meaning only if their framing and namespace are already unambiguous; this coordinated format change audits the constructors before publication.

### 2.3 ContentTree

```text
B = 32
CAP = 512
```

Keep the existing `ContentTree`, `ContentList`, `Diff`, `apply` and semantic-sum interfaces. No new general-purpose tree is required. CAP bounds entry/child **count**, not encoded bytes; expected ordinary occupancy remains near 32 under the independent-boundary model. No deterministic logarithmic update guarantee is inferred: the forced-cut suffix adversary remains (F09, separately owned).

`apply(old, edits)` must yield exactly the same hash, sum, count, contents and tree shape as a fresh canonical build. Verify under SHA-256 and SHA3-256, including forced-CAP keys, and measure node **bytes** as well as node counts.

## 3. Stage 1 — physical images and versioned catalogues

### 3.1 Parse each physical class once

Stage 1 still scans the repository and constructs shared resolution leaves. In addition, an archive gets an immutable catalogue of *all* selected candidates, not one leaf chosen using the daemon's `jdkFeature`.

```text
IMAGE_FACTS[factsID] -> immutable canonical parsed T/META/edges record
ARCHIVE[archiveID]    -> root(owner -> release-change-point list
                               of (imageID, factsID, occurrenceStatus))
PATHS[location]      -> (archiveID, size, mtime, faults, catalogueID)

indexArchive(path):
    frozen = readAndHashArchive(path)
    cd, manifest = parseArchive(frozen)
    for entry in relevantClassEntries(cd):
        imageID = H(CLASS_IMAGE || entry.bytes)
        physical = parseCache.computeIfAbsent((imageID, parserFormat), parseClassBytes)
        factsID = H(RECORD || IMAGE_FACTS || canonical(physical, parserFormat))
        putIfAbsent(immutableFactsKey(factsID), canonical(physical, parserFormat))
        occurrence = validateExpectedOwner(physical, entry.path)
        collect owner, versionBoundary, imageID, factsID, occurrence.status
    for owner in sortedOwners:
        catalogue[owner] = compressSelections(owner, manifest.multiRelease)
    commit immutable archive catalogue, occurrence faults and path binding
```

The archive catalogue commits both `imageID` (identity of the physical bytes) and `factsID` (content address of the extracted record). Never use a mutable `IMAGE[imageID]` value as a historical fact authority: the catalogue root must transitively authenticate every selected fact and metadata record. The byte parser is shared; validation of `this_class` against each archive-entry name is not shared away. Buffer/validate all members of a class before sending them to a streaming `Chunker`: duplicate `(name,descriptor)` declarations fault that class without partially admitting its facts. Preserve relevant local/anonymous binary-reader facts even when they generate no source-level resolution facts.

`SELECTED_ERROR(imageID, origin, fault)` is a legitimate *selection state*. It retains precedence over a later valid class. Stage 1 records byte/structure faults and the preview/class-version properties; Stage 2 additionally evaluates **compiler-mode-dependent** rejections, which cannot be inferred from a static parse result alone. Unknown or stale physical bytes cannot be consumed under an old `archiveID`: before exposing a selected `JavaFileObject.openInputStream` to javac, obtain the complete selected class bytes and verify their `imageID`, or serve retained immutable bytes of that exact identity. A mutable path or once-checked archive header is insufficient; archive replacement under the same path is possible. On mismatch discard the attempted compile and re-plan, not silently mix generations. Historical environments must retain their *logical fact/answer* history even if original archive bytes have been replaced; a historical compiler view may be unavailable unless the old image bytes were retained.

### 3.2 Static resolution and metadata projections

Keep `T` focused on javac-relevant declaration resolution: type and member signatures, flags, constants, hierarchy, permits, records, module descriptors and relevant warning properties. `N`, `E` and `O` remain derived from the same `factH`. Do not put private-reader payload into `T` merely to avoid maintaining a separate projection.

`META` supersedes the old annotation-only `A`. Its owner/site records retain the information needed to reproduce supported *compiler-readable reference declarations*: private/bridge/synthetic members, ordered annotation values and sites (class, member, parameter, type, record component), relevant inner/enclosing information, parameter metadata, source-file and relevant module-reader metadata. When javac reads parameter-slot metadata under `Code`, retain only that relevant projection, not original method instructions or complete debugging tables. This includes the case where the library has `LocalVariableTable` from `-g:vars` without `MethodParameters`, but the consuming task enables `-parameters`. A rendered source reference view may need a **minimal synthetic `Code` carrier** to expose the projected slot metadata; "no source bodies" never means suppressing required class-file `Code`-nested metadata. Preserve the actual class-file minor/preview status where consumer-visible.

```text
siteKey = zstr(owner) || u8(siteKind) ||
          zstr(memberName) || zstr(descriptor) || u32(ordinal)
META[siteKey] = canonicalCompilerReaderData

a = H(META_LEAF || root(META) || root(staticMetadataEdges) || schemaVersion)
```

Site kinds are fixed: `0=class`, `1=field`, `2=method`, `3=parameter`, `4=record-component`, `5=inner/enclosing`, `6=package`, `7=module`, `8=parameter-slot metadata`. Preserve **original class-file declaration order across members**, not just order among annotations or overloads. Ordinals must reflect the corresponding original encounter positions even when records are sorted by key: javac can expose distinct `Elements.getEnclosedElements()` orders from classes with identical named public fields. Metadata edges to referenced annotation classes, enum constants and class literals are **static candidate references**; they are not an evaluated dependency DAG. Equal `k` does not imply equal `a`. Jar class bytes are passed through to javac unchanged; a source-derived reference view is a separate Stage 2 product.

The pinned JDK 25 reader-attribute inventory is the coverage checklist. Missing attributes are assigned to an existing projection (or explicitly outside javac's supported read surface), not discarded. Behavioral compiler equivalence is a separate gate; inventory coverage by itself is insufficient.

### 3.3 One 18-bit physical selection history per owner

Version eligibility is determined by `Multi-Release: true`, the entry path and the effective **target**, not source-language syntax. An absent base remains absent until an eligible versioned entry appears. Versioned candidates for unsupported releases do not affect the Java 8–25 selection history.

```text
ALL = (1 << 18) - 1              // target 8..25 maps to bit target-8
bit(target) = 1 << (target - 8)

compressSelections(candidates, multiRelease):
    active = ABSENT
    last = ABSENT
    for target in 8..25:
        if candidates.base exists: active = candidates.base
        if multiRelease:
            active = last candidate with boundary <= target, if one exists
        selected[target] = active
        if selected[target] != last:
            emit change(target, last, active)
        last = active
    group equal adjacent selections as disjoint masks/intervals
    return events and masks
```

Each selected interval points to its exact physical image and status. Independently collapse adjacent **resolution-equal** and **metadata-equal** selections; a physical image change need not move `k` or `a`. A catalogue stores owner event lists and sparse changed-owner postings at their release boundaries. No 18-way repetition of parsing or per-release materialisation occurs in MACHINE cold boot.

### 3.4 Demand-driven canonical semantic snapshots

A selected archive leaf is a derived immutable cache, **not** another authority. Its key includes archive catalogue identity, chosen selection state and format. It is built on demand after MACHINE publication and may be discarded without losing information.

```text
selectedLeaf(archiveID, target):
    state = catalogue.selectionState(target)
    if cached(state, format): return cachedResult
    base = chooseCachedStateMinimisingTouchedOwners(target) or EMPTY_AT_RELEASE_7
    changedOwners = unionOwnerEventsBetween(base.release, target)
    // Works forward or backward; final state is taken from the catalogue
    for owner in changedOwners:
        old = base.selected(owner)
        new = state.selected(owner)
        if old == new: continue              // changed then changed back
        remove old owner's T/N/E/O entries as required
        add new owner's entries as required
        update META selection independently
    next = applyCanonicalChanges(base.roots, groupedEdits)
    assert selected failures and origin table match catalogue
    putIfAbsent immutable derived leaf and selection record
    return next                         // same roots as fresh selected build
```

Coalesce intermediate events: for target 21 from cached target 8, an owner replaced at 11, 15 and 17 is changed **once**, directly to its final selected image. If API facts are equal, no T/N/E/O rewrite is required; if metadata is equal, no META rewrite. Identical physical selections share the same derived record; equal projections share their own roots even when origins differ. An initial full selected build is allowed when there is no useful cached state. The empty baseline has release 7 and treats all owners as absent; events at release 8 must therefore be included. All cache hits must be verifiable against the committed archive catalogue.

### 3.5 Platform selection: use javac's actual provider

The current JDK 25 platform can use Stage 1's already indexed JRT/module facts. Historical `--release R` uses **javac's `ct.sym` platform provider**, not the current JRT index. A fixed `platformID` commits to the pinned compiler build, the relevant platform image/signatures, `R` and selection mode. Stage 2 treats this as a stable compiler-environment input; it does **not** eagerly materialise eighteen duplicate platform-API trees merely to validate that identity.

```text
platformFor(options):
    if options.release and options.release < 25:
        return (JAVAC_CT_SYM, release, pinnedCompilerAndCtSymIdentity)
    if options.release == 25:
        return (JAVAC_RELEASE_CURRENT, 25, pinnedJrtIdentity, nativePolicy)
    if options.explicitSystemOrBootImage:
        return (EXPLICIT_IMAGE, selectedImageIdentity, nativePolicy)
    return (CURRENT_JDK, pinnedJrtIdentity, nativePolicy)
```

Use an indexed historical platform view only when an actual Stage 2 query needs historical platform facts; derive it from the exact compiler-selected signatures and cache it by `platformID`. Never substitute JRT symbols into an older `--release` query. Option validation remains javac's decision, not an approximate version-table reimplementation.

### 3.6 Archive semantics and MACHINE commitment

Retain the mapped ZIP reader for normal archives. Detect duplicate effective entries; delegate their selection to the pinned compiler's archive/file-manager behaviour instead of hard-coding a first/last rule. Do not replace the entire fast archive path unless measurements justify it. Exact in-generation node deduplication and the existing shared flush barrier remain; a positive `RocksDB.keyMayExist` is not proof of existence.

```text
MACHINE_COMMIT = {
    format,
    root(M),                 // k -> immutable L|k: already built resolution leaves
    root(ARCHIVES),          // archiveID -> full immutable variant catalogue
    root(PATHS),             // location -> immutable archive/status binding
    currentJdkImageIdentity,
    parserIdentity
}

publishMachine():
    flush and sync referenced nodes/catalogues/path-record bytes
    publish MACHINE_COMMIT last, atomically
```

MACHINE commits the facts and catalogues needed to derive every supported target. Derived snapshots built later are content-addressed caches and do not mutate `MACHINE_COMMIT`. Physical-path/timestamp changes may move `PATHS` without changing selected semantic projections. A cold boot creates authoritative state afresh; scanning/statting paths on warm boot remains separately owned.

## 4. Stage 2 — exact compilation binding and header state

### 4.1 Preserve compiler modes without reinterpretation

The same pinned compiler accepts multiple distinct combinations. These must **not** be conflated:

```text
compileMode(options):
    verify option compatibility using pinned javac's rules
    if --release R:
        source = R; target = R; platform = platformForRelease(R)
    else:
        source = explicitSourceOrJavacDefault
        target = explicitTargetOrJavacDefault
        platform = explicitPlatformOrCurrentJdk25
    variantTarget = target
    return (source, target, platform, exactRelevantOptions)
```

`--release 8` is not equivalent to `-source 8 -target 8`: the former uses the Java 8 documented platform API; the latter ordinarily uses the configured current platform unless separately overridden. A Java 8 library may be consumed from Java 17 source; the *consumer's target and compiler configuration* select the JAR variant. Dependency class-file major version and eventual target-runtime compatibility are distinct concerns. Reject unsupported combinations rather than silently clamping or rewriting the model. Preview, module options, charset, lint and processors belong to the fixed compiler environment.

### 4.2 Extend existing definer postings, do not create a second index

`DefinerCounts` currently deduplicates providers by resolution leaf `k`. That loses the distinction between two physical providers with the same API but different metadata or error origins. Preserve existing `DD` (external), `DS` (sibling), `DC` (conflicts) and exact per-owner postings, but distinguish **physical provider occurrence** from **semantic leaf identity**.

```text
OwnerPosting {
    ownerKey,
    providerID,                 // physical archive/sibling/platform origin
    selectedVariants: [(mask, imageID, status, ownerTSum, metaID)],
}
BoundRoute {
    ordered: [(coordinate, providerID, selectedLeaf, k, a, status)],
    compilerEnvironmentID,
}
```

Provider rank is assigned by the project's ordered route, not baked into a MACHINE posting. The semantic fold can still deduplicate equal `k` when legal; **selection and provenance must not deduplicate distinct physical providers first**. Single-provider owners use the disjoint fast path; only overlapping owner postings require route-order evaluation.

### 4.3 First-winner selection across all releases

For **ordinary classpath lookups only**, the earliest eligible provider wins independently for each target. A malformed selected class remains a selected error, shadowing later providers.

```text
selectOwnerAcrossTargets(owner, orderedRoute):
    unclaimed = ALL
    winners = []
    for provider in orderedRoute:
        defined = provider.definedMask(owner)      // includes selected errors
        wins = unclaimed & defined
        for variant in provider.variants(owner):
            if wins & variant.mask != 0:
                winners += (wins & variant.mask, provider, variant)
        unclaimed &= ~defined
        if unclaimed == 0: break
    if unclaimed != 0: winners += (unclaimed, ABSENT)
    return winners
```

The number of relevant postings/variants for this owner—not eighteen scans of the route—bounds the main selection work. For a *specific* compile target, select that target bit and use the appropriate physical image, metadata, origin and status. Own-source, sibling, module-path, patching, readability, export and ambiguity decisions remain **separate native lookup policies**, not an unconditional classpath fold.

### 4.4 Winner evidence and exact changed-owner frontier

An indexed winner is valid only when the selected provider defines the owner **and all earlier eligible providers do not**. This is a property of the rooted old/current owner postings, not a reason to persist a second certificate database.

```text
winnerAt(owner, targetBit, route):
    for provider in route order:
        if provider.definedMask(owner) & targetBit:
            return SELECTED(provider, variantAt(provider, owner, targetBit))
    return ABSENT

compareWinner(oldRoute, newRoute, owner, targetBit):
    before = winnerAt(owner, targetBit, oldRoute)
    after  = winnerAt(owner, targetBit, newRoute)
    return differences in: presence/status, T groups, independent N, META, origin, package membership
```

For a moved provider across a segment, *candidate* owners are exactly within the intersection of that provider's owners and the owners of the crossed providers; intersect their release masks before resolving. For archive updates and added/removed providers, use the changed owners of those catalogues. For source/sibling changes, use changed `O` entries. Re-evaluate old/current **effective answers** only for candidate owners and publish their changed question keys to the existing header reverse index.

```text
changedOwnerFrontier(edit):
    if move(A, crossed):
        candidates = Owners(A) intersect union(Owners(crossed))
    else:
        candidates = Owners(added/removed/changed providers)
    for owner in candidates:
        for releaseBit in relevantMask(owner, edit):
            delta = compareWinner(old, new, owner, releaseBit)
            if delta.hasRelevantDifference: emit (owner, releaseBit, changedProjectionFamilies(delta))
```

A new earlier definer changes the winner even if the previous winner's bytes stay identical. Conversely, a physical provider change that leaves the consumed semantic answer identical does not invalidate a resolution-only dependency. Origin- or metadata-sensitive consumers retain the relevant extra identity.

### 4.5 Winning semantic fingerprints: derive, don't store another index

For ordinary classpath partial maps, `A ▷ B` is first-wins composition. The existing `O` sums imply:

```text
S(A ▷ B) = S(A) + S(B) - S(B restricted to owners(A))  (mod p)

updateWinningSum(oldSum, changedOwners):
    sum = oldSum
    for owner in changedOwners:
        sum -= oldWinner.ownerTSum(owner) or ZERO
        sum += newWinner.ownerTSum(owner) or ZERO
    return sum
```

This equation justifies fast semantic-equality cutoffs **when a caller actually needs an effective resolution aggregate**. It does not require a new `WINNING_SUM` tree or an always-maintained global value. The aggregate covers valid selected resolution facts only; selected failures/statuses require a separate comparison. It says nothing about origin, metadata or JPMS policy and is never a persisted content address. `DD/DS/DC` remain the primary exact lookup structures.

### 4.6 Deterministic project substitution

Before starting any module worker, validate unique coordinates, compute every sibling replacement from the *complete* project model, fix compiler options/platform mode and construct the complete scope dependency DAG. The relative completion order of jobs must never determine which provider wins.

```text
plan(model, machineCommit):
    byCoordinate = validateUniqueCoordinates(model)
    order = topoSortOrFault(moduleDependencies(model))
    for scope in model.scopes:
        mode = compileMode(scope.options)
        route = bindEachCoordinateByModel(scope, byCoordinate, machineCommit, mode)
        save immutable planned route and origin-aware binding
    return planned DAG

run(plan):
    execute ready scopes in parallel
    publish only facts consistent with the precomputed bindings
```

Ordered route entries commit to their physical provider reference, `k`, `a`, status and selected platform/mode. `k`-only route hashes are insufficient for compiler-view identity, although semantic-equivalent leaves continue to share their `T` trees.

### 4.7 Native source-reference views and controlled file management

Stage 2 parses/enters source and extracts **declarations established without body compilation**. Render source-owned reference class files using the JDK Class-File API. Their cache identity is the complete ordered per-owner T/META facts plus renderer version and required class-file view format (including the effective target class-file version and applicable preview marker), never the owner's additive sum alone.

```text
renderSourceView(owner, sourceLeaf, compilerMode):
    t = selectedOwnerFacts(sourceLeaf.T, owner)
    m = selectedOwnerMetadata(sourceLeaf.META, owner)
    nested = sortedMemberTypeRoster(sourceLeaf, owner)  // child facts: binary name, inner name, flags
    key = H(VIEW || rendererVersion || viewFormat(compilerMode) || ordered(t, m, nested))
    return immutableCache.computeIfAbsent(key, emitReferenceClassFile(t, m, nested))
```

The member-type roster is a real cross-owner rendering input: adding `Outer.Inner` can change `Outer.class`'s `InnerClasses` attribute without changing `Outer`'s own named public declarations. Its derived view-ID change must also generate a `CLASS_INPUT(Outer)` candidate in the environment frontier, not only a child `T`/`N` change. Reuse the existing `Stubs.stKey` principle instead of invalidating whole modules. If projected parameter-slot metadata must be present, render a valid minimal `Code` carrier with its debug labels; never restore source executable bodies. Provide exact source views and selected real JAR bytes through a controlled `JavaFileManager`. Classpath `.java` entries are never supplied as implicit source, even if bundled in a JAR. Implement `list`, direct class lookup, binary-name inference, package coverage and relevant module locations consistently. Leave historical platform lookup with javac's own `--release` provider. Physical JAR byte reads must match the bound archive identity; otherwise re-plan instead of silently serving new bytes under old facts.

The parity contract is **consumer-visible reference behaviour**, not byte-for-byte equality between a header-only source view and a fully compiled source class (which can contain body-derived attributes). Consumer compilation against views must match supported independent native results in output class bytes and ordered diagnostics. Do not synthesise body-local or anonymous source classes.

### 4.8 Header proofs, diagnostics and processors

Retain the existing Stage 2 header question and `X` mechanisms, extending answers where necessary for effective **absence, selected-error, owner/member presence and origin-sensitive header behaviour**. Preserve zero/absence dependencies; a missing owner later introduced must select its earlier consumer. Do not invent Stage 3-specific compiler-operation questions in this amendment.

```text
recordHeaderQuestion(file, q, binding):
    answer = evaluateSupportedHeaderAnswer(q, binding)
    if answer is unsupported:
        record conservative immutable compiler input or mark nonReusable
    else:
        row.proof.add(q, answer)             // ABSENT is a value
        X.add(q, file)                      // even when answer is ABSENT

publishHeaderResults():
    store actual parse/enter/header diagnostics with stable order/code/location
    include X membership in the same LOCAL committed tree as file rows
```

`package-info.java` contributes package metadata without type declarations; `module-info.java` contributes supported descriptor/header facts. Header diagnostics use a fixed locale and compiler build; Stage 2 does not claim completed body diagnostics.

Without processors, extract the native header model. For the explicitly supported Lombok configurations, reflect Lombok-transformed declarations and bind processor/config inputs. For another processor, **never publish a `-proc:none` projection as authoritative**. The V1 correctness fallback is a fresh native javac run with that scope's actual processors enabled, retaining its authoritative generated declarations and diagnostics for header projection; non-Lombok processor outputs are not admitted as reusable Stage 3 body results. A cheaper `-proc:only` generation followed by header attribution is allowed **only after equivalence to that full native path is established for the supported processor/configuration** (a small JSR-269 generator confirms feasibility, not universality). If native javac itself cannot complete, report its real failure and do not publish incomplete dependent headers. No generic processor replay/proxy layer is built here.

### 4.9 Immutable LOCAL records and publication

The existing LOCAL tree already contains header `X` entries. **Do not create a second header-X root.** Store changed records immutably and put their content addresses in the rooted LOCAL map; raw aliases may cache recent values but never define historical state.

```text
putVersionedRecord(key, type, bytes):
    id = H(RECORD || type || len(bytes) || bytes)
    writeIfAbsent(immutableRecordKey(id), bytes)
    LOCAL[key] = Entry(key, id, H(LOCAL_ENTRY || key || id))

commitLocal():
    flush/sync all new nodes and immutable record values
    nextLocal = ContentTree.buildOrApply(records including current header X)
    compareAndPublish LROOT {
        format, root(nextLocal), MACHINE_COMMIT,
        projectModelID, compilerEnvironmentID, generationSequence
    }
```

Readers selected by an older `LROOT` must retrieve the immutable bytes it names, never a mutable newer `MOD|`, `RT|`, `SL|` or `F|` alias. A crash before the final root must expose the previous complete generation; after root publication, the new generation must be complete. A changed `PATHS` timestamp alone does not constitute changed semantic facts for every LOCAL consumer.

### 4.10 The Stage 1 → Stage 2 → Stage 3 handoff

**Separation of ownership.** Stage 1 owns immutable physical facts and release selection. Stage 2 owns the effective compiler environment: platform mode, source/sibling/physical binding, selected origins, reference views and indexed lookup outcomes. **Stage 3 owns the sufficiency of its per-file dependency certificate**, body compilation, body reverse consumers and result publication. Stage 1/2 must not invent a body proof, but must provide everything needed to *evaluate and compare* one.

**Stage 1 read surface** (pure functions of a committed MACHINE generation):

```text
MachineSnapshot = (machineCommitID, catalogueRoot, pathRoot, format)

machine.select(archiveID, binaryName, target, MachineSnapshot)
    -> ABSENT | SELECTED(imageID, physicalOccurrence, status,
                         T/ N/ O projection references, META reference)

machine.ownerFacts(imageID, projection, keyPrefix, MachineSnapshot)
    -> canonical facts or exact Merkle range answer

machine.ownerEvents(archiveID, releaseFrom, releaseTo, MachineSnapshot)
    -> streamed owner keys whose selected image/status can differ
```

`select` does not establish javac visibility: it describes one archive's version selection. The static metadata references are *candidate edges*, never certified compiler observations. All answers resolve immutable objects named by the supplied snapshot; no mutable `P|` alias may redefine a historical read. An image selected with a parse/compatibility fault returns `SELECTED_ERROR`, never `ABSENT`.

**Stage 2 environment handle.** One scope's bound view is a stable object, resolved entirely through its selected LOCAL root:

```text
EnvironmentRef {
    localRoot, machineCommitID, projectModelID,
    module, scope, compilerModeID, platformID,
    ownSourceLeafID, committedFileRows,
    orderedPhysicalRouteRoot, effectiveDefinerRoots,
    rendererFormatID
}

openEnvironment(localRoot, module, scope) -> EnvironmentRef
openHistoricalEnvironment(oldLocalRoot, module, scope) -> EnvironmentRef
```

`orderedPhysicalRouteRoot` commits to every selected physical provider occurrence, `k`, `a`, eligibility, status and precedence; it is **not** the legacy `k`-only `routeHash`. `committedFileRows` refers to the existing `F` rows selected by `localRoot`, not a new source index. `EnvironmentRef` names all data needed for reads; it is a small handle, not a flattened copy of the entire project. Its value need not be rehashed into every semantic question: an environment root is a *validation coordinate*, not automatically a dependency answer. Retained environments must remain openable for **logical queries** through immutable selected records after later publications. `compilerView(oldEnv)` is available only when the old physical binary bytes are retained; Stage 3 normally compiles only against the current verified environment. The absence of historical raw JAR bytes must not falsify a historical `answer(oldEnv,q)`; return an explicit unavailable status if an actual historical byte stream is requested. A missing/incompatible ancestor is a cold/reattribution condition, never an empty delta.

**Common typed question/answer interface.** These are low-level *answer primitives*, not Stage 3's final catalogue of compiler reads:

```text
ResolutionNamespace = OWN_SOURCE | SOURCE_SIBLING | CLASS_PATH |
                      MODULE_PATH | PATCH_MODULE | PLATFORM
Question = (namespace, operation, ownerOrPackage, kind, nameOrSite)
Answer   = (outcome, canonicalProjection)
Outcome  = PRESENT | ABSENT | SELECTED_ERROR | INACCESSIBLE |
           AMBIGUOUS | UNSUPPORTED | UNAVAILABLE
Compare  = SAME | CHANGED | UNKNOWN

answer(env: EnvironmentRef, q: Question) -> Answer
```

| Primitive / namespace | Canonical answer and meaning |
|---|---|
| `TYPE_PRESENCE(C)` | Effective presence, failure or visibility outcome. Includes earlier-provider absence in the selected route. |
| `T_HEADER(C)`, `T_NAME(C,k,n)`, `T_ALL(C,k)` | `T` range fingerprint of the *selected* definition, with explicit owner-presence/status. Empty member group on a present owner is distinct from absent owner. |
| `N_MEMBER(C,n)` | Independent member-type/name presence or range answer from `N`; never inferred from equality of `T` or `oSum`. |
| `META(C,site)` | Canonical stored metadata for the selected *physical* or rendered source class at that site; never an assertion that dependent annotation types have been resolved. |
| `CLASS_INPUT(C)` | Conservative selected compiler-input identity: exact binary/view image **and** physical/resource origin. An origin-sensitive diagnostic must not be reused from an image-only equality. |
| `RESOURCE(C)` / `ORIGIN(C)` | Separate exact image or origin answers only for consumers that have independently established that the other dimension is irrelevant. |
| `PACKAGE_NAME(p,n)` / `PACKAGE_LIST(p)` | Effective positive **or negative** name lookup, or a canonical complete ordered package listing under the exact location/module policy. |
| `PLATFORM` | Pinned compiler/platform identity for fixed inputs; changing it is not a per-member semantic `T` delta. |

Question keys include the resolution namespace/location and module context needed to disambiguate identical binary names; never silently collapse classpath, own-source and JPMS/module-path lookups. For `TYPE`/`T`/`N`, a different physical winner with the same required semantic answer may compare equal; a query that needs metadata or origin records that dimension separately. `Answer` uses canonical encoded bytes/fingerprints with explicit status, not a sum that conflates an absent owner with a present owner having an empty group. A selected error is *not* converted into fallback to a later archive. Unsupported resolution policies return `UNSUPPORTED`, not a guessed result. `UNAVAILABLE` means the selected immutable input cannot currently be opened or verified; it is distinct from a genuinely absent Java type, whose `ABSENT` answer is reusable when unchanged.

```text
answer(env, q):
    verify immutable environment and mode
    if q.operation == PLATFORM: return env.platformID
    if q.operation == PACKAGE_*: return resolvedPackageAnswer(env, q)
    selected = resolveEffective(env, q.namespace, q.ownerOrPackage)
    if selected is not available:
        return encodeStatus(selected.outcome)  // including genuine absence
    match q.operation:
        T_*:          return (PRESENT, selected.T.rangeSum(q.keyPrefix))
        N_MEMBER:      return (PRESENT, selected.N.rangeSum(q.keyPrefix))
        META:          return (PRESENT, selected.META.siteAnswer(q.site))
        CLASS_INPUT:   return (PRESENT, (selected.exactImageID, selected.originID))
        RESOURCE:      return (PRESENT, selected.exactImageID)
        ORIGIN:        return (PRESENT, selected.originID)
        TYPE_PRESENCE: return (PRESENT, ownerAvailable)
        otherwise:     return UNSUPPORTED
```

`answer` must be a pure read of the committed view. Its records, source views, physical class files and package listings must agree with what the compiler-facing file manager supplies. A `META` result covers *static metadata only*: a consumer that resolves `Mode.X` inside unchanged `Lib` must additionally depend on the selected `Mode` input (e.g. `CLASS_INPUT(Mode)` or a proven finer named-member answer), not merely `META(Lib)`. Static Stage 1 metadata edges can nominate a conservative cross-origin closure, but do not by themselves certify which javac operations ran. No Stage 1/2 query claims to encapsulate an unproved transitive javac operation.

**Compiler-facing view.** Stage 2 exposes one read-only, environment-bound file manager to a later per-file compiler task:

```text
compilerView(env, compilingFile, optionalObservationSink): JavaFileManager
    source views = ownSourceLeaf T/META/O with owners listed in compilingFile's committed F row excluded
    dependency classes = exactly the selected immutable image or checked archive bytes
    platform = env.compilerMode / env.platformID via javac's native provider
    lookup, package enumeration, binary-name inference and class-open
        all use the same environment resolution policy
```

`compilingFile` is excluded from the source-reference overlays because javac compiles its declarations from the supplied source, not a stale self-stub. The optional sink can report class-resource opens, direct/negative lookups and package enumerations with their canonical environment answers. A conservative class-read event records `CLASS_INPUT` (bytes **and** origin). **An event sink is not by itself a completeness proof of javac's semantic reads**, and it does not intercept the native historical platform provider. Stage 3 decides whether observed events plus supported semantic rules are sufficient; otherwise the result is non-reusable. Stage 1/2 do not persist the sink's Stage 3 observations.

### 4.11 Rooted change frontier: candidates first, actual answers second

A transition is always defined by **two immutable EnvironmentRefs**, not by an unchecked caller-provided list of changed files. A frontier names *possible* changed answer domains; it is not a claim that all consumers of those domains must recompile.

```text
frontier(oldEnv, newEnv) -> immutable/streaming EnvironmentFrontier
EnvironmentFrontier.forEachCandidate(consumer)
    -> (namespace, ownerOrPackage, affectedReleaseMask, possibleChangedFamilies)

compareAnswer(oldEnv, newEnv, q) -> Compare:
    a = answer(oldEnv, q); b = answer(newEnv, q)
    if a.status in {UNSUPPORTED, UNAVAILABLE} or b.status in {UNSUPPORTED, UNAVAILABLE}:
        return UNKNOWN                 // ABSENT is legitimate; unknown/unavailable is not
    return SAME if a == b else CHANGED

frontier(old, new):
    reject incompatible/unavailable roots as REBUILD_REQUIRED
    compare platform/compile mode/model and direct-input identities
    derive changed physical/catalogue selections (release-mask postings)
    diff own T, N and META projections independently
    include derived source-view identities of enclosing owners when member-type
        rosters change, even if the enclosing owner's T/META facts are equal
    diff origin-aware ordered routes using existing ContentList/Diff
    compare ordered package-list answers for touched packages on reorder,
        even when the moved providers share no binary names
    include owners added/removed, selected errors, moved-provider overlap,
            earlier-provider changes and source sibling substitutions
    include package name/listing changes from effective visibility
    group by owner and eligible release bits; deduplicate provider pairs
    emit conservative projection-family candidates (not body question keys)
```

The moved-provider **owner intersection** shortcut applies only to first-winner owner changes on ordinary classpaths; it is **not** sufficient for ordered package-list answers. Moving two providers containing distinct owners in the same package can change `JavaFileManager.list` order without changing either owner winner. Emit the relevant `PACKAGE` frontier independently. Other mode/module changes use their native selection policy or a conservative whole-domain frontier. `N` must remain independent of `T`; on disappearance, candidate **owner-wide prefixes** must be included even if every previously recorded member range had answer zero. The frontier must also include `CLASS_INPUT` whenever either selected image or origin changes; a physical resource change cannot be suppressed solely because `T`/`N` stayed equal. Package absences and package enumeration are separate candidates. A metadata/origin-only change must remain visible even with equal `k`. A platform/compiler-mode change is also reported as a **fixed-input change**; it cannot be safely dismissed by an unchanged jar `T` root.

The frontier is derived **once per pair of rooted environments**, reused by the consumer's stage, and must not scan unrelated file proofs. It exposes query-prefix families (`STATUS`, `T`, `N`, `META`, `CLASS_INPUT`, `RESOURCE`, `ORIGIN`, `PACKAGE`) plus namespace/owner or package prefixes using one stable key grammar. A future Stage 3 can index its own questions under those families without sharing the header `X` publication. For a compound Stage 3 question, that stage must index *every* underlying primitive family it depends on; the frontier cannot infer such edges. The existing header reverse index remains wholly within `LROOT`; a future body reverse index is owned/published with that stage's body root. In both cases, reuse is justified only when `compareAnswer(old, new, q) == SAME` for every relevant candidate question (never `UNKNOWN`), evaluated against the *actual* stored baseline root (which may predate the immediately preceding LOCAL generation).

```text
// Example Stage 3 consumption; illustrative, not implemented by Stage 1/2.
old = openHistoricalEnvironment(receipt.validatedLocalRoot, module, scope)
now = openEnvironment(selectedCurrentLocalRoot, module, scope)
f = frontier(old, now)
if receipt is absent or not admitted or fixedInputsChanged(receipt) or f.rebuildRequired:
    reattribute(file)
else:
    candidates = stage3ReverseIndex.intersect(f.possibleQuestionPrefixes)
    if file in candidates and any(compareAnswer(old, now, q) != SAME for q in receipt.questions):
        reattribute(file)
    else:
        reuse(file)  // only if Stage 3's original question set was sound/complete
```

**Consumer correctness rule:** if a reusable per-file result was produced with fixed inputs `I` and a *complete* certificate `P`, then unchanged `I` and unchanged `answer(env,q)` for every `q in P` are sufficient for reuse (subject to the fingerprint model). Stage 1/2 guarantee rooted answer consistency and a complete *conservative change frontier for their supported domains*; **only Stage 3 can establish that `P` covers javac's actual inputs**. Missing events, unknown processing/JPMS behaviour, incomplete compiler views, untracked processor configuration or unproved metadata reads must result in broader certified inputs or explicit non-reuse, never inferred equality.

## 5. Acceptance contracts

| Component | Required independent evidence |
|---|---|
| Codec / format | Production Java codec matches golden encodings and random UTF-16 round-trips; invalid encodings rejected; no unsafe hash-preimage overlap; both digests verify nodes and T/N/O sums. |
| Trees | CAP=512, `apply == fresh build`, canonical equality independent of insertion order; forced-CAP cost in nodes **and bytes**; no claim F09 is closed. |
| Physical facts | One parse per distinct class-image/parser identity; separate occurrence validation; changed private annotation can preserve `k` while moving `a`; malformed first definer remains selected failure. |
| Release selections | All targets 8–25 match pinned JDK 25 for base, overrides, missing base, malformed and duplicate-entry archives; each computed mask covers exactly its selected target bits. |
| Sparse snapshots | Random and adversarial event histories: lazy cached root equals independently built complete selected leaf for every release, including T/N/E/O/META, origins and faults. Demand only requested roots; changed owner visits measured. |
| Compile environment | Pinned JDK 25 source/target/`--release` matrix; correct `ct.sym`/current/custom platform distinction, emitted class versions, preview and module-option behaviour. Unsupported inputs reject; no silent fallback. |
| Provider selection | Independent full-route oracle for presence, selected error, equal-`k` different-`a`, precedence reversals, earlier-definer addition and masking; moved-route conflict frontier includes every effective change. |
| Stage 3 handoff | Historical/current `EnvironmentRef` open identically after reopen and concurrent publication; each typed answer matches full native effective lookup/reference view, including absences, zero member groups, standalone `N`, metadata, origin and selected failures. Frontier contains every changed answer on the supported-domain mutation matrix (no false negatives). Selective validation from a retained older root matches full question-by-question validation. A metadata-only edit with fixed `T`, original member declaration order changes at equal named facts, the PR #62 `Lib` → `Mode.X` cross-origin diagnostic change, a newly appearing earlier provider, an unsupported/unavailable question on either side and a missing owner becoming present are mandatory negative controls. A package-only order change with no overlapping owners must select `PACKAGE_LIST` but not falsely select unchanged owner queries. |
| Source views | Compiling consumers against source views equals supported whole-native reference in emitted bytes and ordered diagnostics; classpath-bundled source cannot leak into javac. Include Outer/Inner roster changes with unchanged outer declarations and parameter-name recovery from `-g:vars` under consuming `-parameters`, including the necessary minimal `Code` carrier. |
| LOCAL headers | Absent-to-present and status-change proofs reselect current consumers; all prior header-X records live under the appropriate selected `LROOT`; worker shuffle yields identical roots/faults. |
| Publication / processors | Crash cuts, concurrency and old-root reads never return overwritten records; archive-path replacement between selection and class-file opening either serves verified selected bytes or rejects/re-plans; an ordinary JSR-269 processor generating a type referenced in a header must be admitted by the native fallback (or produce the same native failure), not silently rejected solely because `-proc:none` lacks the type; unknown processors cannot introduce authoritative incomplete declarations. IMAGE_FACTS records remain provably reachable through immutable catalogue roots. |

Measure parse counts, distinct physical/API/META intervals, demanded snapshot roots, reused nodes/bytes, changed-owner candidates, indexed lookup counts, RocksDB reads/writes, allocation, peak memory and cold-boot time. The existing Java PoCs establish the release-mask algebra and route/winner frontier only on generated ordinary-classpath workloads; they do **not** establish JDK 25, JPMS or full JVMD/RocksDB correctness. Those are the integration gates above.

## 6. Implementation ownership and exclusions

| Piece | Primary existing code |
|---|---|
| Codec/identity/trees | `Codec`, `Keys`, `Fact`, `Digest`, `Node`, `ContentTree`, `Chunker`, `Edit`, `Diff` |
| Physical class catalogue and META | `Entries`, `ClassFacts`, `ArtifactJob`, `LeafBuilder`, `MachineLeaf`, `Seen`, `Leaves` |
| Immutable MACHINE selection | `Enumerate`, `Stage1`, `MachineTree`, `MachineStore` |
| Target-aware definer postings | `DefinerCounts`, `DefinerIndex`, `Defaults`, `Bind`, `Bound`, `ModuleJob` |
| Rooted environment/query/change interface (no new database) | Existing `MachineStore`, `LocalStore`, `LocalRoot`, `DefinerIndex.Reader`, `HeaderProof`, `ReverseIndex`, source view/file-manager code; expose one read-only facade over them |
| Source references and header proofs | `SourceFacts`, `Stubs`/reference renderer, `HeaderCompiler`, `HeaderProof`, `ReverseIndex` |
| LOCAL commit/recovery and processors | `Stage2`, `LocalStore`, processor integration |

Use one coordinated **incompatible format generation**; old roots are not reinterpreted under the new codec, identity or provider-binding contracts. The existing semantic indexes remain the authoritative exact-data structures. Do not add a second generic Merkle tree, unconditional cross-version materialisation, a separate global winning-sum index, a redundant header-X root, partial-order route-normalisation indexes, speculative signed-delta range trees, or the V2 derived-observation DAG. The new environment/question/frontier interface does **not** store body proofs, body reverse keys or Stage 3 outputs. No Stage 3 body compilation, LIVE, warm-boot scheduler, sources/Javadoc indexing or F09 redesign is part of this amendment.

**Relevant public references:** [JDK 25 javac](https://docs.oracle.com/en/java/javase/25/docs/specs/man/javac.html); [Java 25 class-file format](https://docs.oracle.com/javase/specs/jvms/se25/html/jvms-4.html); [JEP 238 — Multi-Release JARs](https://openjdk.org/jeps/238); [PR #62 reader and persistence findings](https://github.com/maxjay/jvmd/blob/262d5678d1fb26ed68942eb36486a01cfc2c0453/docs/pr62-review-reconciliation.md).
