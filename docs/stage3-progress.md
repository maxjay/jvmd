# Stage 3 implementation and evidence

Specification snapshot: [jvmd-stage3-bodies.md](jvmd-stage3-bodies.md), authoritative **revision 123 with the 2026-10-06 PR 60 review amendment**. Max re-supplied revision 123 from `Downloads/jvmd-stage3-bodies.md` on 2026-10-05 at 22:50 BST; his subsequent review supplied the amendment. The authoritative Downloads document and this snapshot were updated together. The authoritative document and Max's explicit conversation clarifications take precedence; this copy is not independently maintained.
Base: `617dbfd8`, Stage 2 (#58). Working branch: `feat/jvmd-stage-3`.
The objective is the entire specification. None of the gates below is complete until its evidence is recorded.

## Ordered delivery

1. PR A: Appendix A, LAYOUT 4. Separate T/res and A/tail, EA edges, key-bound sums, warning annotations in res and stubs, annotation identities on bindings, own-first header proofs with absences. Stage 1/2 regressions, source/binary equality on fixtures and this project, conservation and cost checks. Land on main before Stage 3.
2. PR B: Appendix F, Stage 2 processors. Processor declarations and recording host, per-file configuration proofs, generated rows/GEN, domains/PD, RES/PROC codecs, byte-based processor identity. All six F.2 tests.
3. Stage 3: write the invariant fixtures/oracles first; implement proof codecs/descent, per-file javac context pool, symbol-based collector, results/uses/reverse indexes, driver/BROOT, output/materialisation, and measurements. No LIVE, warm boot, or LSP work.

## Evidence gates (all pending)

| Invariant | Required evidence |
| --- | --- |
| 1–2 | Byte/diagnostic equality against independent javac for every fixture and all project modules/scopes; real dependency classes versus stubs |
| 3–4 | API mutation completeness and minimality with exact failing consumer sets |
| 5–8 | Same-module calls, inferred type closures, subclass method sets, own-package and inherited type shadowing |
| 9–11 | Constant inlining, route winner changes, measured descent reads |
| 12–13 | Exact k, independent a, logarithmic annotation edits, conservation under apply |
| 14–16 | ACI captures options/name/sibling reads; processors; deduplicated CF/RS and repeat-write counts |
| 17 | Source and own stubs win; context eviction; concurrent/serial agreement |
| 18–20 | Reverse consumers and offsets; output Diff/write/delete counts; root publication/read discipline |
| 21–24 | Two digests; shuffled input/worker determinism; per-file faults; error-result dependency repair |
| 25–26 | Key/value permutations invalidate; locale/charset independence |
| 27–29 | 2,000-file processor reuse; aggregating domains; unsupported processors named and never reused |
| 30 | Independently instrumented javac loaded-state and absent-lookup traces; focused exclusions only |

Each PR must include the input-coverage audit from §2.2, sum semantics with permutation/insertion/removal/replacement/reordering counterexamples, independent computation read traces, measurements, and any explicit deviations. Completion also requires LAYOUT 4 on main, all thirty invariants with both digests, cold Stage 3 of this project with a proof/result for every file, unchanged warm package, and published PR measurements.

## Current investigation

- Maven uses installed Temurin 25.0.4.1+1, although the shell's default `java` is Oracle 21. Use Maven's toolchain or an explicit Java path for fixtures.
- Stage 1/2 baseline: 158 tests passed (machine/local boot, tree, codec, Rocks boot suites), `stage3-baseline.log`. Layout4Test then failed all six cases (three requirements, two digests) against LAYOUT 3: annotations incorrectly change k, constant-value permutations preserve sums, warning annotations do not affect res.
- New architecture is confined to core hash/tree, index layer/machine and layer/local, rocks/layer, and boot/cold. Old analyzer/index/LSP/runtime implementations are not guidance.
- Appendix A implementation now separates T/res from A/tail and EA, uses key-bound fact hashes, carries a on machine paths/source results/bindings, and emits javac warning metadata in stubs. Header proofs resolve own-first and include package/inherited type absences.
- Repository API measurement initially exposed a-only differences for private inner constructor parameter flags in three modules. The corrected source extraction passes the stricter measurement recorded below.
- Max's annotation clarification is normative in A.4: existing binary fact ownership and one codec; completed javac TypeCompound positions; RUNTIME/CLASS retention only; EA derived from the encoded tail; exact A/EA hash and sum comparisons diagnosed by tree Diff. No additional annotation model or source syntax reconstruction. Extra parameter/record-component metadata expansion was removed so this remains a source producer correction.
- Focused source/binary fixtures cover bounds, dimensioned arrays, receivers, returns, throws, generic arguments/wildcards, records, private and multiply nested inner constructors, and retained versus SOURCE annotations. Bytecode-rewriting tests strip tail metadata while preserving warning/meta-annotation resolution facts. These pass for SHA-256 and SHA3-256, with identical compiler options and a separate broken-body fixture.

## Specification questions to resolve with executable evidence

- **Resolved in revision 123:** production Attribute remains strictly one compilation unit; Do-not 5 explicitly permits the independent whole-module javac oracle required by invariant 1.
- **Resolved by Max, 2026-10-05:** PD must use a first-class canonical processor-visible element projection, not file bytes, oSum or A. It includes kind/name/modifiers, all annotations (including SOURCE retention and values), type parameters/bounds, superclass/interfaces, member declarations/descriptors/signatures/throws/defaults/constants/parameter names, nesting, and source path. It excludes executable bodies, expression trees, locals and local classes. Store the projection as the PD value and use `Digest(elementKey || projection)` as h. A body-reading processor requires explicit body read proofs or is unsupported for reuse; never widen. Add direct body-versus-declaration/annotation mutation tests. Isolating GEN still contains κ_file; the blanket body-edit claim in §3.5 is narrowed accordingly.
- Earlier review paragraphs contain superseded keys/identities; use the final definitions, codecs and E.5/F amendments, and record any remaining incompatibility instead of silently weakening guarantees.

## Appendix A gate, 2026-10-05

The combined Stage 1/2, LAYOUT 4, header absence, source projection, bytecode stripping, Maven integration and own-project measurement run passed **202 tests**, no failures/errors/skips (`stage3-layout4-gate.log`). It includes both digests; four Maven integration cases cover Java 21 and 25. The source projection fixture checks exact A/EA hashes and sums with Diff diagnostics, with and without `-parameters`. Jar-to-source substitution instrumentation observes zero duplicate L/S/ST writes. Stub diagnostics now compare warnings as well as errors. The separate sum/input audit is in [layout4-audit.md](layout4-audit.md).

The source projection suite was rerun after adding an explicit assertion that the broken return expression has no attributed type: **8 tests passed** (`stage3-body-exclusion.log`). This checks the extraction boundary directly as well as checking equal projection output.

Own-project measurements, Temurin 25.0.4.1+1, four workers, 13 modules / 26 scopes / 487 sources, 91 jars indexed on the spot:

| Digest | Wall | Summed header compile | Facts | Definer indexes | New boot nodes | k equality | a differences | Faults |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: |
| SHA-256 | 4,212 ms | 2,845 ms | 1,324 ms | 156 ms | 22,177 | 26/26 | none | 0 |
| SHA3-256 | 3,594 ms | 2,279 ms | 791 ms | 160 ms | 22,191 | 26/26 | none | 0 |

These are sequential JVM measurements, not a digest speed comparison. Maven model/build time (26.1/26.7 s) is excluded. Peak sampled heap above baseline was 795/1,362 MB; this is used heap without controlling intervening GC, not retained-live memory. Reports are under `jvmd-tests/target/stage2-measurement-{SHA-256,SHA3-256}.txt`.

Command:

```powershell
mvn -B -pl jvmd-tests -am test '-Dtest=MachineColdBootTest,ClassMemoTest,ContentTreeTest,ContentTreeEditTest,ZipReaderTest,RocksMachineBootTest,LocalColdBootTest,LocalCodecsTest,FactCodecsTest,RocksLocalBootTest,Layout4Test,HeaderAbsencesTest,SourceAnnotationProjectionTest,TailStrippingTest,MavenProjectTest,Stage2Measurement' '-DexcludedGroups=none' '-Djvmd.stage2.workers=4' '-Dsurefire.failIfNoSpecifiedTests=false'
```

Appendix A landed on main in [PR #59](https://github.com/maxjay/jvmd/pull/59), merge commit `20e825a3`, on 2026-10-05. GitHub's initial test job, manual dispatch and retry never acquired a hosted runner and were canceled without running code; the benchmark job failed for the same infrastructure reason. That limitation is recorded in the PR. Landing used the passing local 202-test gate plus the 8-case body-exclusion rerun, not a claimed CI pass. Body attribution, the two-sided javac read-set oracle and all Stage 3 result/driver invariants are still outstanding. `warm/` still contains only its original `package-info.java`.


## PR 59 review corrections

The review was submitted at 20:49 UTC, after PR 59 merged at 20:39 UTC. Its seven findings are tracked in this follow-up, separately from the unfinished Appendix F work.

1. Persist `SL|projectKey|module|scope = k || a` with the module records and include it in LOCAL. Both sealers write `AL|a` containing the full A and EA Roots before API-leaf deduplication. Tests recover an unreferenced module entirely from committed records, reopen Rocks records, and preserve both annotation identities when two jars share k.
2. Store exact InnerClasses `innerName` in Res.Type; source uses the completed element name. N is rekeyed as `zstr simpleName || kind || [TYPE: zstr outer-or-empty] || m`, preserving one entry and the same h per fact. Form-1 header absences read N. Dollar-name fixtures check direct versus nested names, top-level dollar identifiers, source/class parity and client compilation against stubs.
3. Warning projection is three bytes: Deprecated attribute, deprecation state (absent/present/forRemoval), SafeVarargs presence. `since` and explicit false defaults do not move it. Tests include attribute-only deprecation and real-class/stub diagnostics at generic varargs call sites under `-Xlint:unchecked`.
4. Package-valued heads of qualified header references now record package and inherited-member absences, without an answered-package exclusion. Package declarations and executable bodies are excluded. Adding p/java invalidates the qualified-header consumer only.
5. Permutation tests compute the old unbound sum explicitly and prove it unchanged for constant-value, throws-list and two-owner member-set exchanges, while keyed member ranges, owner sums and r move.
6. Controlled Stage 1 measurement runs the same harness at the pre-Appendix-A base and this head, with an exact path-to-byte-hash inventory and JDK image identity. Build callbacks identify T/N/E/O/A/EA/MACHINE nodes before global deduplication. The matching inventory/JDK identities, per-tree counts, raw reports and base instrumentation patch are in [layout4-review-measurements.md](layout4-review-measurements.md). Both digest runs passed at base and head with zero faults.
7. This PR refreshes the repository snapshot verbatim to revision 123. The earlier source-annotation and processor-element/GEN-output-tree clarifications remain recorded in this progress document and the conversation; Appendix F is not changed by this follow-up. The snapshot still has inconsistent older F wording, which must be reconciled with those explicit clarifications when F resumes.

The first new regressions failed in all eight expected cases (four defects, two digests). The expanded repair run passed 166 tests (`review-expanded.log`); later additions and instrumentation require the final gate below. Stage 3 is not complete.

Before the second review amendment below, the revision-123 snapshot SHA-256 was `45df0d15f8e2a3dc305860721806e39b00575944838875dbfb5dd1a84162230a`, identical to the re-supplied Downloads file. Stage 2 C.1's add-exports rule is stated in the implementation audit.


### Review correction final gate, 2026-10-05 22:59 BST

**227 tests passed**, no failures/errors/skips, in 2m53s (`review-final-gate.log`). This includes both digest families, source/binary projection parity, exact-name and warning regressions, persisted roots, real/stub diagnostics, memory/Rocks stores, Maven integration and the project oracle. The controlled Stage 1 comparison separately passed two tests at base and two at head. No production code changed after these runs. Whitespace checks pass; warm remains untouched.

The project oracle covers 13 modules, 26 scopes and 492 sources, with 91 jars indexed on the spot. Both digests have k equality in **26/26** scopes, no annotation identity differences and zero faults:

| Digest | Wall ms | Header ms | Facts ms | Definer ms | Nodes |
| --- | ---: | ---: | ---: | ---: | ---: |
| SHA-256 | 4,351 | 3,107 | 1,152 | 124 | 22,135 |
| SHA3-256 | 3,379 | 2,020 | 621 | 142 | 22,157 |

Maven model/build time (26.4/25.9s) is excluded. Sampled used heap above baseline was 829/645 MB, without controlling intervening GC. The raw reports are [SHA-256](measurements/layout4-review-stage2-SHA-256.txt) and [SHA3-256](measurements/layout4-review-stage2-SHA3-256.txt).

```powershell
mvn -B -pl jvmd-tests -am test '-Dtest=MachineColdBootTest,ClassMemoTest,ContentTreeTest,ContentTreeEditTest,ZipReaderTest,RocksMachineBootTest,LocalColdBootTest,LocalCodecsTest,FactCodecsTest,RocksLocalBootTest,Layout4Test,HeaderAbsencesTest,SourceAnnotationProjectionTest,TailStrippingTest,StubProjectionTest,PersistedLeavesTest,MavenProjectTest,Stage2Measurement' '-DexcludedGroups=none' '-Djvmd.stage2.workers=4' '-Dsurefire.failIfNoSpecifiedTests=false'
```


## Appendix F work in progress

- Processor declaration projection tests were written first, then implemented from completed Elements/TypeMirrors. Both digests preserve PD on body/local-declaration edits and change it for SOURCE annotations, signature/bounds/parameter names/constants/enclosed declarations/source paths. Domain insertion is checked with Diff and conservation.
- The recording processing-environment/Filer wrapper passes Lombok source/class projection equality and synthetic-generator origin tests; undeclared and multiple-origin isolating processors are reported unsupported for reuse. Generated source units are entered without body attribution.
- Configuration tests check per-file chains, exact insert/edit/delete deltas at two depths, and stopBubbling. Ancestors outside the project are included when Lombok would read them. Configuration imports and unmodelled Filer reads require explicit proofs or unsupported reuse; they are not widened.
- The first component gate passed 24 cases, including the existing source-annotation regression, with both digests (`stage3-processor-components.log`). The subsequent combined Stage 1/2 plus processor integration run passed **226 tests**, no failures/errors/skips (`stage3-processor-integration-gate.log`). AutoValue and aggregate domains now run through the actual Stage 2 driver, including generated rows and GEN/GS deduplication.
- AutoValue 1.11.0's metadata uses uppercase declarations and DYNAMIC. Its capability is resolved from the Gradle isolating/aggregating supported option after init. The test pins the resolved isolating behavior; it is not guessed from its output count.
- A module-info fixture exposed javac's pending-file bookkeeping: a processor Element lookup tried to initialize the module graph twice. The header task now consumes excluded files using empty compilation-unit shells, retaining the parsed module declaration separately. It never enters the excluded declarations. The repaired task passed 94 tests including the existing 76-case local cold-boot suite (`stage3-processor-task-bookkeeping.log`). The runtime image and test JVM carry the corresponding javac tree export.
- Multiple outputs from one origin share one GEN. A clean second processor invocation yields the same derivation id, root hash and blobs. Replacing B with D and changing C produces exactly the generated-row changes read from the published LOCAL root. Removing the last aggregate-domain element produces an empty output tree; Diff removes the prior output (`stage3-empty-output-set.log`).
- Same-input/different-output generation is reported by processor class, preserves the first GEN value, removes reusable ids from the current module's rows, and records a capability violation that subsequent boots honor. Concurrent publication serializes the shared store's thread-local batches so conflicting GEN values cannot race and identical GS blobs/nodes are written once. No generated-output sum enters another key or proof.
- A deliberately body-reading aggregate unwraps the native processing environment and calls Trees. That regression failed before the change. The native javac Trees instance now observes syntax escaping through getTree/getPath during processor execution; unproved syntax access disables generator reuse while generation continues. Verified Lombok overlays remain supported. This closes the tested native-Trees escape, not every possible use of reflection or compiler internals.
- Full driver fixtures now cover Lombok configuration add/edit/delete at two directory depths (exact RES changes and affected file contexts/facts), same-API processor byte replacement (unchanged route/own leaves, changed processor identity and generated bytes), undeclared/multiple-origin violations, AutoValue, aggregate declaration/body/domain edits, output determinism and concurrency. An independent native javac header task also agrees with the wrapped Lombok task and compiled class facts. The expanded component gate passed 51 cases, followed by 28 processor/oracle cases after adding the explicit unsupported-driver fixtures (`stage3-processor-expanded-gate.log`, `stage3-processor-direct-oracle.log`).
- Generated-source syntax diagnostics are classified with javac's SYNTAX flag after deferred processor diagnostics are released; malformed generated files have parse faults and no partial declaration facts. The focused repair plus locale/charset and existing fault-grain regressions passed 8 cases (`stage3-generated-fault-repair.log`). FORMAT pins the full runtime version; compiler inputs include explicit source encoding and selected processor names. Ordinary declaration faults still come from SourceFacts at declaration grain.
- Appendix F is still incomplete: audit arbitrary Elements/Types queries, raw compiler access and standard Trees.instance(wrapper) compatibility; finish the input/sum audit and measurements. Empty output sets and retained processor diagnostics are covered by the later checkpoints below; Stage 3 diagnostic assembly/replay remains pending. A declared incremental capability alone is not evidence for unrecorded reads outside the source projection/header proof. No general reuse-soundness claim or PR B completion is made yet. The F code is checkpointed locally and is not in PR #59 or #60; all Stage 3 body/result/driver work remains pending.
- **Resolved by Max:** GEN holds a ContentTree root over output-kind/path to content id, with key-bound entry hashes; GS stores generated source bytes by content id. Generated rows share the input derivation id. Exact output comparison uses root hashes and Diff; the natural output sum is not another dependency or key. Tests must prove clean-run determinism, shared blobs/nodes and exact correspondence between output Diff and generated-row changes.

### Appendix F regression checkpoint, 2026-10-05 22:15 BST

The combined Stage 1/2, processor, native-header oracle, source/class projection, Maven integration and own-project measurement run passed **257 tests**, no failures/errors/skips (`stage3-processor-full-gate.log`, 3m44s). This is a regression checkpoint, not completion of Appendix F's unresolved processor-read contract or any Stage 3 invariant. No code was changed after this run at this checkpoint.

Own-project measurements: Temurin 25.0.4.1+1, four workers, 13 modules / 26 scopes / 500 sources, 93 jars indexed on the spot, zero faults:

| Digest | Wall | Summed header compile | Facts | Definer indexes | New boot nodes | k equality | a differences |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| SHA-256 | 5,460 ms | 4,139 ms | 1,320 ms | 121 ms | 23,763 | 26/26 | none |
| SHA3-256 | 3,823 ms | 2,310 ms | 810 ms | 152 ms | 23,777 | 26/26 | none |

Maven model/build time (33.3/31.7 s) is excluded. Sampled used heap above baseline was 901/1,012 MB, without controlling intervening GC; these are sequential JVM measurements, not a digest speed comparison. Reports are `jvmd-tests/target/stage2-measurement-{SHA-256,SHA3-256}.txt`. The warm package remains unchanged.

```powershell
mvn -B -pl jvmd-tests -am test '-Dtest=MachineColdBootTest,ClassMemoTest,ContentTreeTest,ContentTreeEditTest,ZipReaderTest,RocksMachineBootTest,LocalColdBootTest,LocalCodecsTest,FactCodecsTest,RocksLocalBootTest,Layout4Test,HeaderAbsencesTest,SourceAnnotationProjectionTest,TailStrippingTest,ProcessorHostTest,ProcessorProjectionTest,ProcessorConfigurationTest,GeneratedOutputsTest,ProcessorStage2Test,HeaderEnvironmentTest,MavenProjectTest,Stage2Measurement' '-DexcludedGroups=none' '-Djvmd.stage2.workers=4' '-Dsurefire.failIfNoSpecifiedTests=false'
```


### Appendix F model-query checkpoint

The Elements/Types proxies now record resolved types, inherited declaration owners and negative getTypeElement probes. A synthetic isolating processor reads an unrelated dependency constant and tests a missing type; changing either updates its origin header proof and GEN identity. The real AutoValue trace includes Types.isAssignable and missing p.String/p.Override/p.Object probes. The focused Host/Read/Stage2 processor run passed 34 tests (`stage3-processor-query-proof.log`). This is still incomplete: per-output ownership of reads for multiple origins, direct Element/TypeMirror graph access and processor diagnostics remain unresolved. The current single-origin association is not claimed as general processor reuse soundness.


PR 60 (https://github.com/maxjay/jvmd/pull/60), production commit 1cd5be01 and status-documentation commit 6cf93312, contains the review corrections. They are merged locally into feat/jvmd-stage-3 for continued work; GitHub review/CI and landing remain pending. The prior F implementation was preserved in the local checkpoint before integrating these fixes.

PR 60 must remain open until Max explicitly approves merging it. Max queried the premature merge of PR 59; GitHub confirms that merge at 21:39:28 BST preceded the posted review at 21:49:30 BST. A passing local or hosted gate does not replace that approval.

After integrating the review corrections with the preserved processor checkpoint, **126 tests passed**, no failures/errors/skips (`stage3-review-integration.log`, 57s). This covers processor host/projection/configuration/GEN/model-read driver cases, updated codecs, header absences, exact source/class projections, persisted roots, stubs and permutation invariants. No claim of general processor-read completeness is made.

### Processor declaration doc comments

Revision 123's invariant 28 explicitly includes doc comments. The first-class element projection now encodes the nullable result of `Elements.getDocComment` for each declaration, retaining the distinction between absent and empty comments. It continues to exclude ordinary comments, executable bodies and local declarations. This extends Max's processor-visible Element projection; it does not adopt the older AST-skeleton wording in the snapshot.

The regression initially failed four cases (two digests): the projection ignored added comments, and an aggregate that actually read method documentation produced different bytes under an unchanged GEN id. With the fix, doc-comment insertion/edit changes PD and GEN, while a subsequent body-only edit retains both and writes no new GEN. Class, nested-type, method and field documentation are covered, along with ordinary/body-comment exclusions. **50 tests passed**, no failures/errors/skips (`stage3-processor-doc-comments.log`, 41s), covering processor projection, host, model reads, driver and compiler environment. The remaining Appendix F read-contract and diagnostic work is unchanged.

### CI compiler-boundary correction

The hosted run [37380049291](https://github.com/maxjay/jvmd/actions/runs/37380049291) compiled/assembled the runtime and passed the Phase 3 index gate, Rocks, compiler, processing and other executed feature gates. Its overall result failed: the older architecture guards allowed javac internals only in the analyzer and had not been updated for Stage 2's HeaderCompiler or Appendix A's Symbol/TypeCompound extraction in SourceFacts. The source and bytecode guards now share exact class grants for those two adapters (including their nested classes); no index/boot package-wide permission is granted. The boot descriptor expectation now includes its existing compiler dependencies. Positive/negative boundary assertions are included.

That early failure skipped the step creating `runtime-downloads`; later always-run JBR/HotswapAgent steps then failed writing their downloads. Each now creates its own destination directory. **36 tests passed**, no failures/errors/skips (`review-ci-adapters.log`, 21s), including both architecture guards, source annotation projection and header absences. No production code changed. A new hosted run is required before claiming full CI success.

The Appendix F branch additionally grants its `ProcessorTrees` adapter access to native javac Trees, so the existing source-syntax escape guard can run. This exact class grant is confined to the F branch; PR 60 retains only its two adapters. After integration, **16 tests passed**, no failures/errors/skips (`stage3-compiler-adapters.log`, 14s): the architecture guards and processor projection suite. PR 60 head `08534aa2` passed the full hosted [Tests workflow](https://github.com/maxjay/jvmd/actions/runs/37381047686) and [benchmark workflow](https://github.com/maxjay/jvmd/actions/runs/37381047811), including cold-start and scenario checks. It remains open for Max's explicit approval.

### Processor diagnostics checkpoint

Stage 2 now retains processor diagnostics in `PDIAG|projectKey|module|scope`, a fresh run-output record committed in LOCAL before LROOT. It is not a processor input identity and is not part of the generated-file manifest. Its ordered messages contain processor class (empty for a javac processing diagnostic without a callback owner), nullable source path, exact Diagnostic.Kind, signed position/start/end/line/column (including -1), code and Locale.ROOT text. The codec preserves order and duplicate messages. No PDIAG is read before root publication. This added record is necessary to carry aggregate-processor diagnostics into the later body driver; Stage 3 assembly/replay remains to be implemented.

The callback diagnostic handler records ownership before javac defers messages, then forwards to javac's original handler. Only actually reported diagnostics are retained, respecting suppression and warning/error limits. Values are copied at listener delivery because javac's diagnostic objects retain mutable trees: class diagnostic end positions can otherwise disappear after generation. Processor ERROR messages also enter the boot's named faults without throwing away the declaration leaf.

An independent unwrapped javac listener checks all four Messager overloads, global/source/annotation/value positions, repeated warnings, mandatory warnings, notes, errors, `-nowarn` and `-Xmaxwarns`. Driver tests verify persisted LOCAL membership, position refresh, processor error reporting and repair. Four cases failed before capture/publication existed; **143 tests passed**, no failures/errors/skips (`stage3-processor-diagnostics-gate.log`, 56s), after the fix, covering both digests, processor and local boot suites, Rocks, codecs, environment and architecture guards. The processor read contract, first-run empty generation and Stage 3 work remain incomplete.

### Empty processor output sets

An initialized declared generator now retains generator capability when it produces no files; that is not evidence of an overlay violation. RoundEnvironment query results and explicit Filer origins record input source units even when no output follows. Each observed isolating origin gets a derivation manifest, including the empty tree; an aggregate's empty domain also produces an empty manifest on its first boot, without relying on a historical PROC record. Returning to an earlier empty input state reuses the original GEN record. Two origins retain separate derivations: editing the non-emitting file leaves the other file's emitting derivation unchanged.

Unverified processors are checked for declaration mutation across each callback, using transient Element projections of the round's roots. A mutation is named and unsupported for reuse; the comparison is not a persisted identity or an input to GEN. Verified Lombok overlays retain their existing capability. This catches a fixture that changes a constant through native Symbol reflection, without treating pure empty generators as overlays. It does not claim to intercept all compiler internals or executable-body mutations.

Four initial regressions failed as expected. The expanded processor/GEN/configuration/architecture gate passed **72 tests**, no failures/errors/skips (`stage3-empty-generation-gate.log`, 40s), including eight empty-output cases across both digests. Empty/nonempty/empty transitions prove exact manifest Diff additions/removals and generated-row publication. A subsequent **2-test** fixture rerun (`stage3-generated-header-completion.log`, 12s) verifies that the mutation check permits a source header referencing a type generated in a later processing round while still leaving a deliberately broken executable body unattributed. No production code changed after the 72-test gate.

The next correctness gap is the processor-model read proof: direct Element/TypeMirror graph access is not fully traced, a T/oSum header proof cannot represent SOURCE annotations or doc comments observed on another element, and multiple-origin query ownership remains unresolved. These need actual processor-visible read projections and attribution to derivations; a module/route/project identity is not a substitute. Appendix F must not be declared complete on the current narrower query fixtures.

### Processor model graph checkpoint, 2026-10-06

Public model results now stay wrapped after Elements/Types calls and RoundEnvironment queries. Reads through Elements, TypeMirrors, annotation mirrors/values, runtime annotation interfaces and model visitors are recorded as an ordered, length-framed transcript of actual query arguments and answers. Model objects have first-occurrence references within that invocation; returning an Element does not serialize its entire declaration. The snapshot contains bytes only and is detached before the header context closes. This transcript supplements the existing header proof in single-origin isolating GEN identities and supplements PD in aggregate GEN identities. No module, route or project identity is substituted, and no generated-output sum is added to a proof.

The binary annotation regression first failed twice (both digests): the dependency annotation changed generated bytes without changing the GEN inputs, causing the output determinism guard to report a conflict. The repair makes the actual annotation read change the derivation while the existing javac header proof remains equal. A second driver fixture reads a SOURCE annotation outside an aggregate's supported-annotation domain: editing that value leaves PD and the source API sum unchanged but changes GEN and generated bytes. A subsequent body-only edit preserves PD and GEN and writes no new GEN record. The projection is not replaced with A, oSum or a file-byte hash.

The native-model oracle covers direct declaration/type traversal, Elements/Types receiving wrapped arguments, doc comments, runtime annotations, nested annotation values and arrays, record components, and Element/Type/AnnotationValue visitors. Each visitor receives wrapped model values, including javac objects exposing more than one public model interface. Unqueried annotation edits and unrelated declarations leave the query transcript unchanged. Two compatibility regressions found by this oracle were repaired rather than weakening the comparison: javac List's native textual representation is preserved and recorded, and native Trees unwraps model arguments while still reporting executable-syntax access as unsupported for reuse. Filer origins and every positioned Messager overload also unwrap their model arguments. Verified Lombok overlays retain their native model access.

The expanded processor/configuration/output/environment/architecture gate passed **84 tests**, no failures/errors/skips (`stage3-model-graph-final.log`, 50s), with both digest implementations. The earlier narrowed gate passed 48 cases; the first expanded compatibility run failed the two native-list comparisons and two body-reader cases before those repairs. The reflection mutation fixture now deliberately unwraps the native environment to exercise the same unsupported compiler-internal mutation it tested before model wrapping.

A later focused **6-test** run passed (`stage3-model-mirrors.log`, 11s), adding native-versus-wrapped `MirroredTypeException` and `MirroredTypesException` checks for class-valued runtime annotations. Only import/comment cleanup and removal of an unused constructor followed the 84-case gate; no behavior changed before this focused run.

This remains a checkpoint, not completion of the processor proof contract. The transcript is a fresh invocation observation, not yet a persisted independently replayable query proof. Batched isolating processors with multiple source origins still need sound assignment of observations to individual derivations; the existing single-origin rule does not solve that. Processor-internal identity hash codes, arbitrary native/reflection access, and standard Trees.instance(wrapper) compatibility are not claimed as solved. Appendix F's final input audit and measurements, and all Stage 3 body/result/driver work, remain pending. PR 60 remains open until Max explicitly approves merging it.

### Second full-diff review gate, 2026-10-06 00:19 BST

The two production defects each failed their new regression under both digests before repair (four expected failures, no errors, `review-second-red.log`). The final gate passed **237 tests**, with no failures/errors/skips, in 3m30s (`review-second-final-gate.log`).

- ST binds exactly the direct-member InnerClasses projection, including nullable innerName and emitted flags. A transformed class file keeps binary name, flags and every outer fact unchanged while changing innerName; the outer key and bytes change, including with a populated shared cache. A method edit inside the member still preserves the outer key.
- Package-valued MemberSelect prefixes and import prefixes contribute exact type absences. Introducing q.r or q.r.s in either the own module or a route provider invalidates qualified-header and on-demand-import consumers while the body-only consumer remains valid.
- Source and binary builders independently build and verify N, comparing Diff, hash and sum in the dollar-name fixture and the other source annotation fixtures. The member-absence regression also proves Base.Foo insertion changes N with oSum(Base) equal.
- The authoritative Downloads file and repository snapshot were amended together: Arrange/Valid dispatch T versus N; only T uses the owner oSum gate; X|G includes form; LIVE consumes Diff(N) independently of the owner O delta. This is explicitly revision 123 plus the 2026-10-06 review amendment, not a newly asserted upstream revision. Both files have SHA-256 `0287cd8fe43c009c3e94dc3b07bd32dfaca40d75cf4b12c260e7fd67403bb039`.

The project oracle again covers 13 modules, 26 scopes and 492 sources, with 91 jars indexed on the spot. Both digests have k equality in **26/26** scopes, no annotation identity differences and zero faults:

| Digest | Wall ms | Header ms | Facts ms | Definer ms | Nodes |
| --- | ---: | ---: | ---: | ---: | ---: |
| SHA-256 | 4,742 | 3,171 | 1,224 | 108 | 22,136 |
| SHA3-256 | 5,712 | 4,105 | 1,369 | 282 | 22,153 |

Maven model/build time (29.6/38.9s) is excluded. Sampled used heap above baseline was 682/1,407 MB, without controlling intervening GC. These sequential measurements are not a performance comparison. Raw reports: [SHA-256](measurements/layout4-review2-stage2-SHA-256.txt), [SHA3-256](measurements/layout4-review2-stage2-SHA3-256.txt). The controlled Stage 1 reports above remain the evidence for the original Appendix A tree-layout comparison.

```powershell
mvn -B -pl jvmd-tests -am test '-Dtest=MachineColdBootTest,ClassMemoTest,ContentTreeTest,ContentTreeEditTest,ZipReaderTest,RocksMachineBootTest,LocalColdBootTest,LocalCodecsTest,FactCodecsTest,RocksLocalBootTest,Layout4Test,HeaderAbsencesTest,SourceAnnotationProjectionTest,TailStrippingTest,StubProjectionTest,PersistedLeavesTest,MavenProjectTest,Stage2Measurement,ModuleArchitectureTest,ForbiddenIdentifiersTest' '-DexcludedGroups=none' '-Djvmd.stage2.workers=4' '-Dsurefire.failIfNoSpecifiedTests=false'
```

The previous hosted Tests and benchmark workflows passed at 08534aa2. Those runs do not cover this amendment; fresh exact-head CI is required. PR #60 remains open pending Max's explicit merge approval. Appendix F and Stage 3 body attribution remain unfinished, and warm remains untouched.
