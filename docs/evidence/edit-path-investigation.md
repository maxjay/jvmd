# Hover-after-edit allocation and the cost of AttributedMemos identities

Investigation only: no production code changed. Every number names the run or command that produced it.

Sources:

| id | what | build(s) |
|---|---|---|
| **R33** | CI [run 36859611055](https://github.com/maxjay/jvmd/actions/runs/36859611055): `jvmd-benchmarks.yml` dispatch, runs 5, the 7 hover-after-edit cases, `--thread-allocation`, `--jfr` | PR head b6417ca |
| **R34** | CI [run 36859613837](https://github.com/maxjay/jvmd/actions/runs/36859613837): same, `revision=c8fcb9f` | main c8fcb9f |
| **R31** | CI [run 36859605101](https://github.com/maxjay/jvmd/actions/runs/36859605101): `jvmd-benchmarks.yml` dispatch, runs 5, full suite, no instrumentation | PR head b6417ca |
| **R32** | CI [run 36859607623](https://github.com/maxjay/jvmd/actions/runs/36859607623): same, `revision=c8fcb9f` | main c8fcb9f |
| **R29** | CI [run 36857728584](https://github.com/maxjay/jvmd/actions/runs/36857728584): `jvmd-benchmarks.yml` dispatched on `main` itself, runs 5 (main's own harness; its pooled median is only in the job summary) | main |
| **BA2** | CI [run 36856122444](https://github.com/maxjay/jvmd/actions/runs/36856122444): `jvmd-before-after.yml`, base vs PR on the same runners (comment `jvmd-before-after` on #55) | c8fcb9f vs dcd6e57 |
| **L-syn** | local: `node benchmarks/persistence.ts --suite synthetic --units 2000 --heap 2g --jfr true` | PR head |
| **L-real** | local: `node benchmarks/persistence.ts --suite real --heap 4g --jfr true` on ruoyi-vue-pro @ 1697112f (sessions cold, A9) | PR head |
| **T-pkg** | `mvn -pl jvmd-tests test -Dtest=PackageDeclarationCertificateTest` | PR head |

JFR attribution: `java benchmarks/harness/JfrWindows.java <windows> 10 <file.jfr>`, allocation samples
(`jdk.ObjectAllocationSample`, weights estimate bytes) and CPU samples (`jdk.ExecutionSample`) whose stack
contains the named frame; a sample counts for every cause on its stack.

## 1. Summary: the hover-after-edit "regression"

**It is the machine index's background ingest landing inside the measured request, because this PR's daemon
reports READY before the repository scan finishes. None of the four memo hypotheses contributes measurably.**

- The reported metric is the whole-daemon allocation between the start and end of the *one hover request
  that first answered correctly* after the edit (`ScenarioContext.query`), median over the run's ~7 samples.
  Whatever the JVM does in background during those ~50 ms is counted.
- main prints READY after the index is built: **9.8 s, index `ready` (80/95 artifacts)** (R34). This PR
  prints READY after **1.7 s, index `discovering` (0 artifacts)** (R33), so the first cases run while the
  ingest (~1.75 GB of allocation over ~7 s on both builds: 1742 MB R33, 1782 MB R34, whole-recording
  "machine index publish") is still running.
- Per sample (R33, PR): only run-1 samples are high. SES-01/persisted-reopen run 1 = 5.32 MB with 4.60 MB of
  index publishing in its window; DOC-01/edit run 1 = 20.92 MB with 8.13 MB index publishing; DOC-01/save
  run 1 = 26.23 MB, of which `jvmd-index-reader-N` 13.05 MB and `jvmd-index-scan` 1.23 MB by thread name.
  Runs 2–5 equal main per case to within 3%.
- With 1 run (the PR comment), 2–3 of ~7 samples are run-1 samples, so the median flips between ~1.3 MB and
  ~5–12 MB from run to run: 5.6 M in the last comment, 7.4 M in BA2 (5 runs pooled, but each shard is a
  fresh daemon so every shard's run 1 overlaps).
- DOC-01's ~12 MB per request is the same on both builds: javac's `--release` platform file manager
  (`JavacTool.getTask` → `ZipFileSystem.initCEN`), 6.6–7.0 MB per DOC-01 sample on both (R33, R34 rows).

### Hover after edit, request allocation, MB min / median / max (35 samples = 7 cases × 5 runs)

| run | build | instrumentation | min | median | max |
|---|---|---|---:|---:|---:|
| R33 | PR | per-thread + JFR | 0.64 | 1.35 | 26.23 |
| R34 | main | per-thread + JFR | 0.62 | 1.31 | 12.51 |
| R31 | PR | none, full suite | 0.63 | 1.33 | 12.50 |
| R32 | main | none, full suite | 0.62 | 1.29 | 12.40 |
| R29 | main (own harness) | none, full suite | pooled median only, in the run's job summary | | |

In the full suite (R31) the hover cases run long after the ingest, so only SES-01 run 1 overlaps (5.68 MB).

### By thread (R33 PR / R34 main; MB per sample, min / median / max)

| bucket | PR request | main request | PR whole edit window | main whole edit window |
|---|---|---|---|---|
| request thread (`jvmd-session-*`) | 0.61 / 1.32 / 12.69 | 0.60 / 1.28 / 12.46 | 0.61 / 1.32 / 12.69 | 0.60 / 1.28 / 12.46 |
| virtual-thread carriers (`ForkJoinPool-*`: index readers, memo writer, module dispatch) | 0.02 / 0.03 / **14.12** | 0.02 / 0.03 / 0.07 | 0.02 / 0.03 / **14.90** | 0.02 / 0.03 / 0.07 |
| analyzer owner (`jvmd-module-*`) | 0 | 0 | 0 | 0 |
| memo writer (by JFR thread name `jvmd-local-memo-writer`) | 0 | n/a | 0 | n/a |
| index (by JFR thread name `jvmd-index-*`) | 0 – 14.3 (run 1 only) | 0 | | |
| RocksDB | native threads: no Java heap | | | |
| harness probe | 0.01 / 0.03 / 0.03 | 0.01 / 0.03 / 0.03 | 0.04 / 0.08 / 0.09 | 0.04 / 0.08 / 0.08 |
| total | 0.67 / 1.38 / 26.32 | 0.65 / 1.34 / 12.54 | 0.72 / 1.46 / 27.35 | 0.70 / 1.39 / 12.57 |

ThreadMXBean cannot see virtual threads, so their bytes land on carriers; the JFR rows name them.

### JFR, PR build (R33): high sample DOC-01/save run 1 (26.2 MB, 131 ms) — top 10 stacks

| # | MB | stack (innermost first) |
|---:|---:|---|
| 1 | 1.93 | `byte[]` ← ZipFileSystem.initCEN ← JDKPlatformProvider.getFileManager ← Arguments.handleReleaseOptions ← JavacTool.getTask |
| 2 | 1.77 | `byte[]` ← ZipFileSystem$IndexNode.<init> ← ZipFileSystem.initCEN ← … ← JavacTool.getTask |
| 3 | 0.92 | `ZipFileSystem$ParentLookup` ← ZipFileSystem.makeParentDirs ← … ← JavacTool.getTask |
| 4 | 0.81 | `byte[]` ← InputStream.readAllBytes ← IndexService.indexSources ← IndexService.scan |
| 5 | 0.62 | `ClassSymbol` ← Symtab.defineClass ← Symtab.<init> ← Attr.<init> |
| 6 | 0.59 | `ZipFileSystem$IndexNode` ← ZipFileSystem.initCEN ← … ← JavacTool.getTask |
| 7 | 0.50 | `JCModifiers` ← JavacParser.modifiersOpt ← JavaCompiler.parse |
| 8 | 0.50 | `byte[]` ← SHA256 ← Hashing.sha256 ← ArtifactIndexFormat.documentationKey ← RocksArtifactRepository.publishDocumentation |
| 9 | 0.46 | `LinkedHashMap$Entry` ← JDKPlatformProvider.getFileManager ← … ← JavacTool.getTask |
| 10 | 0.45 | `String` ← Options.isLintExplicitlyDisabled ← Lint ← Preview ← Resolve.<init> |

By thread: `jvmd-index-reader-N` 13.05 MB (49%), `jvmd-session-s3` 12.33 MB (46%), `jvmd-index-scan` 1.23 MB.
By cause: javac task creation 7.02, attribution 6.88, memo capture/drain/write/S0/P_diag **0.00**.

### JFR, PR build (R33): low sample DOC-02/external-create run 5 (0.64 MB, 12 ms) — top 10 stacks

10 allocation samples, 0.7 MB sampled: `jvmd-session-s34` 0.64 MB (88%), the harness probe 0.09 MB (12%).

| # | MB | stack (innermost first) |
|---:|---:|---|
| 1 | 0.09 | `ReferencePipeline$3` ← ClassSymbol.getPermittedSubclasses ← ClassReader.readClass ← ClassFinder.fillIn |
| 2 | 0.09 | `UnixFileAttributes` ← UnixPath.toUri ← PathFileObject.toUri ← **Bindings.classDirectoryFile** ← Bindings$Capture.completion |
| 3 | 0.09 | `ArrayNode` ← JsonNodeDeserializer.deserialize (request decode) |
| 4 | 0.09 | `OutputStream$1` ← CanonicalDigestWriter.digest ← AlgebraicAccumulator.identity ← LiveStateTree$Aggregate.identity |
| 5 | 0.09 | javac `List` ← Modules.computeTransitiveClosure ← Modules.setupAllModules |
| 6 | 0.09 | `SortedOps$OfRef` ← Documents.liveState ← CompilerInputs.capture ← Analyzer.inputSnapshot |
| 7 | 0.09 | `UnixFileAttributes` ← Files.isRegularFile ← SymbolIdentity.sourceFile ← Bindings$Capture.dependencyType |
| 8 | 0.05 | `Thread[]` ← ThreadImpl.getAllThreadIds ← AllocationAgent.threads (the probe itself) |
| 9 | 0.04 | `byte[]` ← AbstractStringBuilder.append |
| 10 | 0.02 | `HashMap$Node` ← Modules.addVisiblePackages |

By cause: `Bindings.capture` 0.27 MB (36%); no memo, index or P_diag samples.

### The four hypotheses, with numbers

Whole PR recording of R33 (every case, every request and all background work: 3,860 MB sampled):

| hypothesis | in the 35 answering requests | in the whole run | verdict |
|---|---:|---:|---|
| memo capture on unsaved buffers (`AttributedMemos.memoize/capture`) | 0.00 MB | 1.41 MB | not the cause |
| the 2-second `drain` (Tarjan, on the owner thread) | 0.00 MB | 0.77 MB | not the cause |
| S0 package parses on the writer (`packageIdentity`, `SourceNamespaces`) | 0.13 MB | 0.45 MB | not the cause |
| P_diag recomputed for dependencies (`DiagnosticProjection.of`) | 0.00 MB | 0.22 MB | not the cause |
| (for scale) machine index ingest | 13.29 MB | 1,742 MB | **the cause** |
| (for scale) javac task creation | 101.5 MB (main: 100.2) | 309.6 MB | same on both |

These fixtures are a handful of files each; on larger projects the memo costs are not small — see §2.

## 2. Inventory: every identity AttributedMemos computes

Costs are JFR-sampled allocation (MB) and CPU samples (≈10 ms each) whose stack contains the frame, over a
whole session. Columns: **fixture** = R33's whole PR recording (7 LSP cases × 5 runs, a few files each);
**syn cold / syn A1** = L-syn, 2,000-unit random DAG, cold (12.4 GB sampled, 2,526 CPU samples) and no-change
restart (0.80 GB, 336 CPU); **real cold / real A9** = L-real, ruoyi-vue-pro 977 units, cold (105.8 GB, 19,960
CPU) and no-change restart (95.2 GB, 13,119 CPU).

| identity | computed where, how often | fixture | syn cold | syn A1 | real cold | real A9 | maintained structure that holds or could hold it | restart-stable today? |
|---|---|---:|---:|---:|---:|---:|---|---|
| **P_diag, own unit** | `Bindings.capture` (`DiagnosticProjection.of`) on **every full attribution** (each edit's diagnostics, each batch unit), whether or not memos are attached; kept in `AttributedMemos.projections` (file → hash, value) | 0.22 MB (own + deps) | 46 MB / 18 | 0 | 400 MB / 83 | 308 MB / 70 | Already a per-file leaf: `AttributedMemos.projections`, hash-gated like `LiveStateTree` `api`. Could move into `LiveStateTree.Leaf` as a 6th field set by `LiveSourceState.semantic(...)` | value: yes (path-free by construction). The map is in memory only; after restart it is filled by restores (`p_diag` field of the record) |
| **P_diag, each completed dependency** | same loop, once per completed source dependency **per dependant attribution** (`Bindings.java:343-349`) | (in row above) | (in row above) | 0 | (in row above) | (in row above) | Could be read from the dependency's own leaf when its hash equals the content javac read; computed only if absent | value yes; leaf absent after restart until the dependency is attributed or restored |
| **binary P_diag** (Lombok-hidden units, other modules' class dirs) | `BinaryProjections` per `logical-binary:`/`reactor-class:` entry at capture; per entry at restore (shared per epoch) | 0 | 0 | 0 | 723 MB / 138 | 796 MB / 128 | none; reader cached per (classpath, options, environment) | value yes (class-file derived); reader is per process |
| **package namespace — file list** | `packageFiles` → `members(root)` per capture × consulted package (own + star imports). Capture runs with `epoch=null`, so `members` rebuilds a TreeSet of every `.java` under the root **each time** | 0.34 MB (local) | **1,071 MB / 208** | 5 MB / 4 | 1,183 MB / 90 | 1,049 MB / 111 | `LiveSourceState.sourcesByPackage` (TreeMap, package → files, path-derived) already answers "files of package p" | keys are absolute paths; the file set is recomputable, not persisted |
| **package namespace — S0 types** | `packageIdentity` on the **writer thread** per written record × package; per file cached by content hash (`namespaceCache`, plus `SourceNamespaces` persisted memo); restore: once per package per epoch | 0.45 MB | 1,484 MB / 245 | 27 MB / 27 | 492 MB / 151 | 97 MB / 27 | none holds S0. `LiveStateTree.namespace` is exported-member names, attribution-gated, not package + top-level types | S0 per content hash: yes (persisted memo). `LiveStateTree` aggregates: no (absolute paths, see §3) |
| **negative resolutions + absence** | `negatives` per capture with unresolved names; `absenceIdentity` per name (existence flags from `members` + class dirs); restore per entry, shared per epoch | 0 | negatives 7 MB / 9; absence 0.3 MB | 0 | negatives 190 MB / 305 (absence inside it: 97 MB) | negatives 338 MB / 217; absence (mostly restore) 294 MB / 31 | none (absence reads `members()` inventory) | yes (existence flags only, v4) |
| **static-import bindings** | per capture whose unresolved simple names sit under static imports; `Files.isRegularFile` probes per dotted prefix | 0 | 0 | 0 | 66 MB / 6 | 242 MB / 34 | none | yes |
| **SCC membership** | `drain`: rebuilds the graph and runs Tarjan **over everything reachable from every pending unit**, every 256 captures or ≥2 s, plus close. Each node visit calls `foreignModule` → `LogicalSources.logical` → `Path.relativize`, and builds the `unknownSample` debug string for every unknown node | 0.77 MB | **6,444 MB / 872 (52%)** | 0 | 81 MB / 35 | 76 MB / 27 | file graph = `SemanticUpdatePolicy.Live` (dependencies/dependants), not `ProofDag` (symbol-level, rejects cycles, no SCCs) | `Live` is in memory, keyed by absolute path; repopulated by restores |
| **content hash, own text** | `capture` re-reads and re-hashes the text (`AttributedMemos.java:474-475`) after the caller already hashed it | ~0 | 1.5 MB / 0 | 0 | 0 / 1 | 0 / 1 | `LiveStateTree` leaf `content` (`liveSourceState.contentHash`), `contribution.sourceHash()` | value yes (bytes); journaled by `FileStateRegistry` |
| **content hash, dependencies / restore** | `documentsState().sourceHash(dep)` per dependency at capture; `currentHash` per `logical-source:` entry at restore (prefers live tree) | ~0 | 250 MB / 7 | 1 MB | 5 MB / 3 | 4 MB / 1 | `LiveStateTree` leaf `content` | yes |
| **static key** (`attributedStaticKey` + `StaticInputs`: options, processors, classpath slots, hidden units, lombok.config above root, platform, roots, context warnings) | per capture (uncached: `epoch=null`) and per restore (shared per epoch). `reactorRoot()` walks every coordinates entry with `Path.normalize` on each call | 0.93 MB | 51 MB / 27 | 28 MB / 7 | **6,802 MB / 833** | 4,894 MB / 637 | `ClasspathSequence` for archive slots (logical keys, content identities); reactor root and coordinates are configuration, fixed per `configure` | ClasspathSequence: yes, but different identities (resolution identity vs file content) and no reactor directories; reactor root: derivable once per configure |
| **processor resources** (`config:` lombok.config per directory, JPA XML) | per capture (`reactorRoot()` again); at restore **per `config:` entry, uncached `reactorRoot()`** (`AttributedMemos.java:663`) | 0 | 0 | 0 | 3,045 MB / 374 | **≈50,000 MB** (top 2 stacks 33,116 + 14,912 MB, both `reactorRoot` under `lookup`) | `configIdentity` is shared per epoch; the root is not | value yes |
| **memo lookup + decode** | `SemanticMemoStore.lookup` per restore (includes the per-entry identity callbacks above) | 0 | 17 MB | 182 MB / 95 | 3 MB | 67,955 MB / 7,666 (inclusive) | — | — |
| **early cutoff** (`currentProjection`) | per `logical-unit:` entry at restore: restore the dependency first, or attribute it | 0 | 0 | 151 MB / 77 | 0 | 36,867 MB / 4,968 (inclusive of nested restores) | — | — |

Notes:

- **The edit path on the real project:** memo capture is 11.9 GB and 1,647 CPU samples in the cold session over 977
  units (≈12 MB and ≈17 ms CPU per unit), and the same runs after every edit's full attribution. 9.8 GB of it
  is the static key and processor resources, almost all `reactorRoot()`.
- **Restart on the real project:** ≈50 GB of 95 GB sampled in A9 is `reactorRoot()` called from
  `config:` lookups; local whole-workspace diagnose took 239.5 s (L-real A9). This is the largest single
  cost found and is a plain missing cache, not a design issue.
- **Cold batches on synthetic:** BA2 diagnose-all PR vs main: hub 29.8 s vs 10.6 s, layered 24.4 vs 10.4,
  dag 38.7 vs 11.3; allocation 35.2 vs 8.3 GB, 23.8 vs 8.4, 59.8 vs 8.9. L-syn attributes it: drain 52%,
  package file listing 9%, S0 on the writer 12%.

## 3. Options

### A. Package namespace as a range aggregate over the path-ordered tree, instead of S0-parsing at write time

- **Feasible:** yes, but not with today's `LiveStateTree` as is.
- **What blocks it:**
  1. `LiveStateTree` has no range API and no direct-children aggregate. A directory node's accumulators cover
     its whole subtree, subpackages included, so `p`'s aggregate also changes when `p.sub` gains a type.
  2. No leaf holds S0. The `namespace` leaf is exported member names, set only on attribution, so a file never
     attributed has `UNKNOWN`.
  3. Aggregates and Merkle values hash absolute paths (`LiveStateTree.java:207,209,273`; root key
     `"R|"+absoluteRoot`), so they change with the checkout location. That breaks A7, the relocated checkout.
  4. A package spans several source roots; the certificate needs one value per (module, package).
- **Shape that works:** an S0 leaf per file, set lazily from the existing hash-keyed S0 memo; a per-directory
  direct-children aggregate over (root-relative name, S0); and the package value combined across this module's
  roots. Membership already exists (`LiveSourceState.sourcesByPackage`).
- **Size:** about 3 files, ~150 lines: `LiveStateTree` +~60, `LiveSourceState` +~40, `AttributedMemos` −~60/+~20.
- **Effect:**
  - Removes the capture-time file listing (syn cold 1,071 MB, real cold 1,183 MB) and writer S0 parsing
    (syn cold 1,484 MB, real cold 492 MB).
  - Restore reads one aggregate per package instead of listing and hashing (real A9: 1,049 + 97 MB).
  - Edit path: one S0 parse per changed file, and only when a package aggregate is asked for. That is cheaper
    than today, where every capture re-lists the root.
- **Soundness risk:**
  - The aggregate must include open-buffer overlays exactly as javac's source path sees them.
  - Laziness must not let a stale S0 leaf survive a content change; gate the leaf on content hash, as `api` is.
  - Medium; covered by the existing A5 tests.
- **Recommendation: do it, after B-fixes below**, with root-relative names so it stays restart- and
  relocation-stable.

### B. P_diag as a per-file leaf, like `api`, computed only when that file is attributed

- **Feasible:** mostly already the case. `AttributedMemos.projections` is that leaf, hash-gated. What is
  recomputed per attribution is the *dependencies'* P_diag (`Bindings.java:343-349`): every dependant
  recomputes P_diag for every completed source dependency.
- **What blocks reading the dependency leaf instead:**
  - The leaf exists only if the dependency was attributed or restored in this process and its hash equals the
    content javac read.
  - Otherwise it still has to be computed from this task, which is a second path. That is allowed only if it is
    the same function on the same input, with no flag.
- **Size:** 2 files, ~30 lines.
- **Effect:** small. All P_diag is 46 MB of 12.4 GB on syn cold (0.4%), 400 MB of 105.8 GB on real cold
  (0.4%), and 0.22 MB in the fixture. A bigger saving: compute P_diag only when memos are attached; today
  completion and other full attributions pay for it too.
- **Soundness risk:** low if gated on the exact content hash javac read. Wrong if gated on the current
  editor hash and the task read another version.
- **Recommendation:** low priority. The cost the option targets is not where the time goes.

### C. SCC and dependency order read from `ProofDag`, instead of a separate graph plus Tarjan in `drain`

- **I think this option targets the wrong structure.** `ProofDag` is a consumer → `QueryProof.Key` DAG at
  symbol level, and it **rejects cycles** (`SemanticUpdatePolicy.java:232-236, 432-440`), so it cannot hold an
  SCC. It is also not populated for restored units: `registerSourceProof` runs only after a fresh attribution.
  The file → file graph `drain` walks is `SemanticUpdatePolicy.Live` (`dependencies/dependants`), and `drain`
  already reads it through `analyzer.contribution`.
- **What the 52% actually is:** three things, none of them "a separate graph":
  1. Every drain re-runs Tarjan from scratch over everything reachable, including units written in earlier
     drains, so cold batches are quadratic.
  2. Each node visit calls `foreignModule` → `Path.relativize`.
  3. The `unknownSample` debug string is built for every unknown node.
- **Feasible fix:** keep the finality rule and make it incremental.
  - A component that has been written is final until one of its members is invalidated, so later drains treat
    it as a known sink and never revisit it.
  - `foreignModule` becomes a prefix check against this module's source roots.
  - The debug sample is built once.
  - Alternatively, maintain SCCs in `Live` (incremental Tarjan or Pearce–Kelly) if other consumers need them.
- **Size:** `AttributedMemos.drain` ~40 lines changed. SCCs in `Live` would be ~150 lines plus tests.
- **Effect:** syn cold drain is 6.4 of 12.4 GB (52%) and 872 of 2,526 CPU samples. Removing most of it should
  bring cold diagnose-all close to main's (BA2: 29.8 s vs 10.6 s hub). Real cold drain is only 81 MB, because
  there are fewer units per module.
- **Soundness risk:** a stale "final" mark must be cleared when a member's dependency set changes. Use the
  existing `knownDependencies` invalidation.
- **Recommendation: do the drain fix, not the ProofDag move.**

### D. No memo capture for unsaved buffers: write only when content matches disk, or on save, idle or shutdown

- **Feasible:** yes. `capture` knows the text and the disk hash (the `Documents` overlay versus
  `FileStateRegistry`).
- **What blocks "write on save":** saving does not re-attribute; the result is cached in `DiagnosticStore`, and
  the `Bindings.Snapshot` that capture needs is gone by then. The options are:
  - keep the `Pending` capture until disk matches, which still does the capture work on the edit path;
  - skip capture for overlays and let the unit be recaptured on its next attribution. After a restart, a file
    edited and saved last session then costs one recompilation.
- **Effect:**
  - Fixture: none measurable (1.41 MB over the whole R33 run).
  - Real project: about 12 MB and 17 ms CPU per edit, the capture cost above. It also stops writing records
    keyed by transient buffer hashes that are never restored.
  - Restore: none for files that were saved; one recompile per edited-and-saved file per restart otherwise.
- **Size:** 1–2 files, ~20 lines.
- **Soundness risk:** none, because it writes fewer records.
- **Recommendation:** yes, as "skip capture while the buffer differs from disk". But first fix
  `reactorRoot()` caching: that is 9.8 of the 11.9 GB that capture costs on the real project, and it applies
  to saved files too.

### E. Reuse the known source hash instead of re-hashing the text in `capture`

- **Feasible:** trivially. Compare `liveSourceState.contentHash(file)` or `contribution.sourceHash()` with
  `sourceHash`. The check guards against the text changing between attribution and capture, and the live
  content hash keeps that guarantee. The text is still needed for `consultedPackages` and `negatives`.
- **Size:** 1 file, ~3 lines.
- **Effect:** none measurable: 1.5 MB of 12.4 GB on syn cold, 0 on real, ~0 in the fixture.
- **Soundness risk:** none if the compared hash is the live content hash, not a cached one.
- **Recommendation:** fine as a cleanup, but it will not move any number.

### Not in the list, and larger than all five

- **Cache `reactorRoot()`, once per `configure`.** It is about 50 of 95 GB on the real-project restart (A9) and
  9.8 of 11.9 GB of real-project capture. 1 file, ~10 lines, no soundness risk: coordinates and source roots
  are fixed per context.
- **Re-enable the epoch cache for captures** (`memoize` sets `epoch=null`) for values that do not depend on
  the captured unit's own content: `members(root)`, the static key and `configIdentity`. This needs the same
  observation-epoch key as restore.
- **The benchmark itself:** the harness should wait for `daemon.status().index.phase == "ready"` (or report
  the first-run samples separately) so that the after-edit numbers compare like with like. Whether READY
  should wait for the scan is a product decision (`jvmd.ready.awaitRepositoryScan` exists and is off).

## 4. `consultedPackages` and a comment before the package declaration

`PackageDeclarationCertificateTest` (T-pkg) uses:

```java
/* Moved here from package com.old; see the migration notes. */
package p;
import other.*;
class A { int f(){ return new Thing().size(); } }
```

- **What A's certificate binds:**
  `NAMESPACE keys of A's record: [package:g:app:1|main|com.old, package:g:app:1|main|other]`. The comment's
  `com.old` is bound and the declared package `p` is not (`theCertificateBindsThePackageNamedInTheCommentNotTheDeclaredOne`).
- **Consequence:** adding `p/Thing.java` (`public class Thing { }`, which shadows the star-imported `other.Thing`)
  restores A's stale result without javac: `compiled=false diagnostics={diagnostics=[]}`. javac would report
  `cant.resolve` for `size()` (`todayATypeAddedToTheDeclaredPackageRestoresTheStaleResult`).
- **The fix is not made.** The corrected expectation is `aTypeAddedToTheDeclaredPackageInvalidatesTheRecord`,
  which is `@Disabled`. The same regex approach is used for star imports (`STAR_IMPORT`) and static imports
  (`STATIC_IMPORTED`), so a commented-out `import x.*;` is bound too. That over-binds rather than under-binds,
  so it costs reuse, not soundness. The attributed tree's package and imports are available at capture and
  are the right source for all three.
