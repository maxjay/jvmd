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


### Second review integration with Appendix F

PR #60 commit `22067f1c` is integrated locally by `a0a6f333` into `feat/jvmd-stage-3`; GitHub PR #60 remains open. The only merge conflict was the progress document, resolved by retaining both the F checkpoints and review evidence. **130 tests passed**, no failures/errors/skips, in 1m14s (`stage3-second-review-integration.log`), covering the processor host/model/diagnostics/empty-output/GEN/configuration suites, header environment and absences, stub and source projections, and architecture guards. No processor implementation changed during this integration. The unresolved processor-proof attribution/replay contract and all Stage 3 body/result/driver work remain pending.


### Second review hosted gate

At PR #60 head `22067f1cdb1d86dbc602a79e1295b0f9c22662a9`, the full [Tests workflow](https://github.com/maxjay/jvmd/actions/runs/37388071239) and [benchmark workflow](https://github.com/maxjay/jvmd/actions/runs/37388071837) passed, including cold-start and scenario statuses. The PR description now links these exact-head results. PR #60 remains open for Max's explicit approval; no GitHub merge was performed.

### Processor query replay checkpoint, 2026-10-06

ProcessorReads now retains an in-memory query-answer table separated by processing phase (init and individual rounds). A replay uses opaque model handles and frozen answer containers from the original invocation; it never falls back to querying the completed compiler. Both an unobserved query and differing answers to one query within one phase reject replay explicitly. Public visitor dispatch, javac list text, model exceptions and the existing header-read observations replay from captured answers too. Each replay numbers its own handles, so unrelated queries in the original batch do not become part of a replay's transcript. One captured answer can be consumed by several isolated invocations without consuming a global tape cursor.

The first regressions failed in four cases under the two digests: native compiler access after capture and later model answers replacing earlier-round answers (`stage3-model-replay-red.log`). The tests now close the header compiler, block further native Elements/Types calls, and reproduce the original model/visitor/annotation results and exact transcript. Additional cases pin phase separation, rejection of unknown/ambiguous queries, and equal origin-specific transcripts despite another origin having queried first.

A fresh-class-loader fixture then failed twice because replayed runtime annotation interfaces came from the original processor loader (`stage3-model-replay-loader-red.log`). Replayed annotation objects, annotationType() results and annotation arrays now use the requesting processor's annotation class. MirroredTypeException and MirroredTypesException still carry wrapped type mirrors. The complete **92-test** processor/configuration/output/environment/architecture gate passed with no failures/errors/skips in 56.6s (`stage3-model-replay-final.log`) after this repair.

This is an in-memory replay primitive, not a completed per-output proof. It is not yet wired into a per-origin processor executor or GEN manifest comparison, and it is not a persistent verifier across independent compiler invocations. The existing multi-origin modelProof rule therefore remains unresolved. The next integration must capture round inputs, run fresh processor instances per origin, compare each replay's exact output set with the native batch's outputs for that origin, and report an unprovable replay without substituting a wider identity. A replay may legitimately ask a query absent from the batch trace; this cannot be answered against the later completed model. Initialization, generated rounds, empty origins and cached answers need explicit treatment before claiming that integration complete. Appendix F's final input audit and measurements, and all Stage 3 body/result/driver invariants, remain pending.

### Third mathematical audit, 2026-10-06

The audit of PR #60 at 22067f1c found two immediate correctness defects and required a deeper Stage 2 reconciliation before PR B. Both immediate defects reproduced in `review-third-red.log`: six expected failures (two generation decisions and two member-lookup cases under both digests), no errors. The initial repair passed 31 focused tests; the expanded branch/import and architecture checks passed 32 tests in `review-third-branches.log`.

- MACHINE is now `layout=4;parser=3`, and LOCAL is `local=2`. A committed old parser-2 generation remains untouched while BootDecision cold-boots the distinct parser-3 directory; a local=1 root cannot take the current-format skip branch. No migration or legacy fact decoding was introduced.
- Qualified inherited member-type references, single static imports and static on-demand imports record exact N absences along every lookup branch before a declaration. Sub/Mid insertion tests cover own and route providers and assert unchanged containing-owner oSums. Hidden ancestors are excluded unless another branch reaches them; that second path can introduce ambiguity. A unit-local cache avoids repeated closure and named-member traversal. No new persisted fingerprint was added.
- The authoritative Stage 3 document and repository snapshot were amended together, still revision 123 plus the dated review amendment. Their SHA-256 is `27857293052495a85426595bc38aff56f58523f6cfcfb82029fc784722438f6b`. Explicit UTF-8 read/write preserves the original mathematical symbols.

The 244-test full gate (`review-third-final-gate.log`, 3m42s) passed 243 tests and failed only the existing MachineColdBootTest assertion hard-coding parser=2. That assertion now expects parser=3; its entire 27-test suite passed with no failures/errors/skips in `review-third-format-assertion.log` (38.4s). No production code changed after the full gate.

The own-project oracle covers 13 modules / 26 scopes / 492 sources, with 91 jars indexed on the spot. Both digests have exact source/class k equality in 26/26 scopes, no annotation identity differences and zero faults:

| Digest | Wall ms | Header ms | Facts ms | Definer ms | Nodes |
| --- | ---: | ---: | ---: | ---: | ---: |
| SHA-256 | 5,271 | 3,519 | 1,330 | 157 | 22,141 |
| SHA3-256 | 3,946 | 2,318 | 800 | 159 | 22,142 |

Maven model/build time (30.7/30.0s) is excluded. Sampled used heap above baseline was 843/1,341 MB, without controlling intervening GC. These sequential measurements are not a performance comparison. Raw reports: [SHA-256](measurements/layout4-review3-stage2-SHA-256.txt), [SHA3-256](measurements/layout4-review3-stage2-SHA3-256.txt).

Before PR B, the required Stage 2 work is:

1. Replace positive header oSum dependencies with actual T ranges, including exact constant fields, annotation declaration reads and headers along lookup paths. A Mid superclass edit must invalidate a Sub.Inner lookup even when the selected Base.Inner and every old N absence remain unchanged. Keep ownR and definer sums as shortcuts only.
2. Replace consumer lists with one empty reverse record per dependency/project/path, including expected-zero T/N/D reads. Prove delta-to-prefix-to-candidate fan-out without scanning F rows, and preserve distinct paths for identical source bytes.
3. Replace the full counts-map copy in DefinerIndex.fold with persistent state. Make distance non-allocating; eliminate exhaustive nearest-state search through route ancestry before claiming project-size-independent routing cost.
4. Retain full special annotations in A while keeping only the minimal warning bits in res; prove Deprecated.since changes A/a alone. Fact.byName precomputation remains an optional measured optimization, with N still a deterministic projection of m/res.
5. Rewrite the Stage 2 authority (`C:\Users\Max\Downloads\jvmd stage 2 LOCAL cold boot.md`) and reconcile Appendix A with those implemented proof/reverse/state rules. No RA, nSum, broad processor dependencies or storage-existence dedup reads.

The in-progress processor replay work remains preserved in the separate feat/jvmd-stage-3 tree. Appendix F, body attribution and the Stage 3 invariants are unfinished. PR #60 stays open pending explicit merge approval; warm remains untouched.


### Stage 2 persistent definer state, 2026-10-06

The counts-map copy reported in the third audit is removed. `DefinerCounts` is an immutable balanced map, built once for a cold fold and updated by copied search paths for changed type keys. A derived fold accumulates only the changed leaves' types and shares the remaining map entries and branches. Conflict enumeration skips singleton subtrees using their multiple-definer counts. Missing-key getOrDefault/containsKey are explicit point lookups too, avoiding AbstractMap's default full iteration. DD/DS/DC retain their existing ContentTree representations and sums; the map is boot-local working state, with no new stored identity or codec. Leaf-set distance now counts with two indices without allocating removed/added lists. Nearest-state selection itself still scans previous states and remains a separate cost issue.

The structural-sharing regression first failed under both digests: replacing a small leaf in a 32,768-type state shared zero old map entries (`stage2-definer-state-red.log`). The repair reads only the changed leaves, preserves old snapshots, shares all but bounded search paths, and gives the same disjoint/conflict roots as fresh folds. The additional 3,000-update history covers ordered growth/deletion, random conflict membership, branching from old snapshots, point misses and structural sharing. The initial focused gate passed 84 tests and the expanded history suite passed 6.

The final frozen gate passed **250 tests**, no failures/errors/skips, in 3m35s (`stage2-definer-state-frozen-gate.log`). An earlier run crossed the addition of the two point-lookup overrides during its first own-project measurement; its only failure was the resulting source-only pair of methods. That invalid sample is superseded by the complete frozen rerun. No Java sources changed during or after the final gate.

The project oracle covers 13 modules / 26 scopes / 494 sources, with 91 jars indexed on the spot. Both digests have exact source/class k equality in 26/26 scopes, no annotation differences and zero faults:

| Digest | Wall ms | Header ms | Facts ms | Definer ms | Nodes |
| --- | ---: | ---: | ---: | ---: | ---: |
| SHA-256 | 5,624 | 3,797 | 1,554 | 233 | 22,138 |
| SHA3-256 | 3,911 | 2,331 | 771 | 174 | 22,144 |

Maven model/build time (31.5/30.7s) is excluded. Sampled used heap above baseline was 488/881 MB, without controlling intervening GC. These sequential timings do not prove a speedup; the sharing/changed-leaf assertions and implementation's point-update paths establish the derivation cost change. Raw reports: [SHA-256](measurements/stage2-definer-state-SHA-256.txt), [SHA3-256](measurements/stage2-definer-state-SHA3-256.txt).

```powershell
mvn -B -pl jvmd-tests -am test '-Dtest=DefinerStateTest,MachineColdBootTest,ClassMemoTest,ContentTreeTest,ContentTreeEditTest,ZipReaderTest,RocksMachineBootTest,LocalColdBootTest,LocalCodecsTest,FactCodecsTest,RocksLocalBootTest,Layout4Test,HeaderAbsencesTest,SourceAnnotationProjectionTest,TailStrippingTest,StubProjectionTest,PersistedLeavesTest,MavenProjectTest,Stage2Measurement,ModuleArchitectureTest,ForbiddenIdentifiersTest' '-DexcludedGroups=none' '-Djvmd.stage2.workers=4' '-Dsurefire.failIfNoSpecifiedTests=false'
```

At 38f890cb, hosted [Tests](https://github.com/maxjay/jvmd/actions/runs/37392009479) and [benchmarks](https://github.com/maxjay/jvmd/actions/runs/37392009061) both passed. Those runs predate the persistent-map change. PR #60 remains open; passing checks are not merge approval. Exact T/N/D header proofs, path-addressed reverse entries, full special annotations in A, and the Stage 2 specification rewrite remain required before PR B. The untouched Stage 2 authority is `C:\Users\Max\Downloads\jvmd stage 2 LOCAL cold boot.md`, SHA-256 `c7b6d3eefeee086e91f90d4da7d96b5b34d301dc62f7f1739051036bfe4bad19` at this checkpoint. Appendix F replay WIP is preserved in the separate Stage 3 tree; all body/result/driver work remains unfinished.


### Exact header ranges: Stage 2 reconciliation

`FileRow.Proof` now stores `(typeKey, kind, name, sum)` for an exact T prefix, including expected-zero field ranges. TYPE references consume the single header fact; constant reads consume the named FIELD range; annotation checking consumes the annotation method contract. `HeaderProof.valid` resolves each owner under the own-first binding and compares that range sum. It no longer compares an entire type's oSum as the actual dependency.

`ProofCollector` now collects both positive ranges and absence candidates after SourceFacts has completed declarations. The duplicate SourceFacts name scanner and containing-owner constant target list are removed. The collector retains final primitive/String initializer reads even before a field can fold, including private constants that feed exported declarations. It skips method bodies, initializer blocks and lambdas. Inherited field and member-type lookup record both the named empty ranges before a declaration and the intermediate type headers actually traversed. Static on-demand field lookup records competing imported ranges. Unresolved types and constant fields retain expected-zero observations.

Four regressions initially reproduced eight failures across both digests (`stage2-header-ranges-red.log`): unrelated method additions invalidated type-only/constant consumers; changing Mid's superclass left inherited Inner resolution falsely valid; and adding a missing type left a faulted header falsely valid. Additional red regressions exposed wildcard static-field shadowing (`stage2-header-lookup-red.log`) and a missing constant field becoming available (`stage2-header-missing-field-red.log`). All now pass. Annotation element/default changes invalidate checking, while unused annotation constant fields do not. The old invariant 18 test now requires an unrelated method addition to preserve validity and a real type-header edit to invalidate its consumer. A class-literal regression similarly requires reuse when only K's unread fields change.

Focused validation: **125 tests passed**, no failures/errors/skips, in `stage2-header-ranges-focused.log` (42 HeaderAbsences, 76 LocalColdBoot, 7 LocalCodecs). The subsequent full gate includes the source/class oracle with Java sources frozen throughout.

The new proof codec requires **LOCAL 3**; MACHINE remains `layout=4;parser=3`. Real Rocks tests require cold rebuilding both local=1 and local=2 roots, then permit the current-format skip. Revision 123's dated amendment and its Downloads authority now include A.5a's exact header ranges; both files have SHA-256 `1b390c87ee9fb40548b125b241edae53d29a6ad1af7a8d9ae9ac839acf3a503f`.

The reverse records still use the legacy positive type-to-consumer lists at this checkpoint. Replacing them with one empty dependency/project/path key for every T/N/D read, then proving Diff-to-prefix candidate discovery without scanning F rows, is the next required change. Route ancestry, complete special-annotation metadata in A, and the full Stage 2 specification rewrite also remain before PR B. The processor replay WIP in the separate Stage 3 tree is untouched; Appendix F and body/result/driver work remain unfinished. Max has now authorized merging PR #60 once the final checks pass and merge-blocking review items are resolved. The local and exact-head hosted verification must finish before using that approval.


Final frozen gate: **264 tests passed**, no failures/errors/skips, in `stage2-header-ranges-final-gate.log` (3m35s). It uses the same complete test selection shown above for the persistent-map gate, now including the added header regressions and both legacy LOCAL versions. The earlier full run had one stale kind-7-only reverse-key assertion; hierarchy observations can also use the legacy kind-8 category, and the final run includes that corrected assertion.

JVMD's 13 modules / 26 scopes / 494 sources retain exact source/class k equality in all scopes, no annotation differences and zero faults under both digests, with 91 jars indexed on the spot. The facts timing now includes the combined proof collector, so moving its work out of SourceFacts does not remove it from the measurement.

| Digest | Wall ms | Header ms | Facts + proofs ms | Definer ms | Nodes |
| --- | ---: | ---: | ---: | ---: | ---: |
| SHA-256 | 5,133 | 3,511 | 1,814 | 138 | 22,165 |
| SHA3-256 | 6,406 | 4,499 | 2,158 | 352 | 22,170 |

Maven time (29.7/30.9s) is excluded. Sampled heap above baseline was 822/758 MB with uncontrolled GC; these sequential measurements do not establish a speedup or regression. Raw reports: [SHA-256](measurements/stage2-header-ranges-SHA-256.txt), [SHA3-256](measurements/stage2-header-ranges-SHA3-256.txt). Hosted Tests and benchmarks passed at 40b432b1, before this exact-range change; the new pushed commit requires its own checks before merge.


### PR #60 merged; reverse-index follow-up in progress

Max authorized the merge after verification. PR #60 merged on 2026-10-06 at 00:56:49 UTC as `16f690706e66b0b98d8e7140dfbd7a95929d91c9`, with exact PR head `f6472306876a832004056907073a811c0d2f0801`. All required contexts were successful and the PR was CLEAN/MERGEABLE. Hosted [Tests](https://github.com/maxjay/jvmd/actions/runs/37395865290) and [benchmarks](https://github.com/maxjay/jvmd/actions/runs/37395864592) passed on that head, after the frozen 264-test local gate. No GitHub comments or review replies were posted.

The follow-up branch `codex/stage2-reconciliation` is separate from the merged PR. Its current uncommitted implementation replaces reverse consumer lists with empty keys:

`X|H|u8 form || zstr type || u8 kind || zstr name || id project || zstr path`

Form 0 is T, form 1 is N, form 2 is D. FileRow's exact positive and expected-zero dependencies all produce keys. `ReverseIndex.candidates` maps changed T/N/definer entries from Diff into dependency prefixes, seeks those prefixes and returns actual project/path consumers. It reads no F row. A reached consumer's key must belong to its committed LOCAL tree, so unreachable raw keys from an old cold generation do not become live consumers. Roots are cached per reached project during one lookup. Storage adds a prefix-seek operation implemented by RocksDB seek and a bounded in-memory tailMap walk; no record-universe scan is added. The LOCAL version advances to 4 for this new representation; the Stage 3 authority/snapshot still describe merged LOCAL 3 and must be amended when the follow-up is finalized.

Focused follow-up verification: `stage2-header-reverse-first.log` passed **134 tests** (HeaderAbsences, the initial 8 HeaderReverse cases, LocalCodecs, LocalColdBoot and RocksLocalBoot). Then `stage2-header-reverse-storage.log` passed **11 tests** after adding an expected-zero T-field delta and real RocksDB prefix reads (10 HeaderReverse and 1 RocksLocalBoot). The path test asserts identical source bytes and identical external leaf sets in two modules still produce two distinct consumers. Other cases cover N changes with owner oSum unchanged, missing D types, stale raw keys, exact candidate paths, and validation only after candidate discovery. LOCAL 1/2/3 all force cold rebuilding before the current-format skip. No full measurement gate has yet run on this follow-up.

Next: inspect and finish the reverse-index change, run its full frozen gate, preserve full Deprecated/SafeVarargs metadata in A with a since-only regression, replace exhaustive nearest-state selection, and reconcile the complete Stage 2 document before PR B. Appendix F replay WIP remains untouched in the separate feat/jvmd-stage-3 tree; Stage 3 bodies/results/driver and its full oracle remain unfinished. The active Stage 3 goal is not complete. The approval above was for PR #60; it does not authorize merging later PRs.


### Stage 2 reconciliation verified, 2026-10-06

The path-addressed header reverse keys are now covered by the full frozen gate. They preserve distinct consumers with identical bytes, index T/N/D zeros as well as positive reads, seek only changed prefixes, and use current LOCAL-tree membership to exclude raw keys left by earlier cold generations. Their reader is implemented for both memory and RocksDB; candidate discovery reads no F row.

Full retained Deprecated/SafeVarargs annotations now remain in A/EA in both ClassFacts and SourceFacts. Minimal warning state stays in res. Four red assertions across the two digests reproduced missing since/full-annotation metadata (`stage2-warning-metadata-red.log`); the focused repair gate passed 75 tests. The since-only regression asserts unchanged L/stub bytes and EA, changed a, exactly the three edited A entries, and recovered since values. Source/class A parity also covers full warning annotations. Parser advances to **4**; real Rocks tests leave committed parser-2 and parser-3 generations opaque and cold boot parser 4. LOCAL remains the follow-up's **4**, with legacy LOCAL 1/2/3 rebuilding.

IndexMemo no longer scans prior states for the nearest leaf set. The route plan supplies ancestry: own main for test, first declared sibling main for dependent main, and shared JDK external base for root modules. Exact leaf sets still fold once under concurrency. Persistent counts and incremental disjoint-tree apply consume the changed leaves/types. A read-count regression confirms java.base and inherited/shared fixture jar O roots open once under both one and eight workers; roots remain equal under shuffled modules. The focused route/persistent/reverse gate passed 94 tests. The chosen ancestor is not claimed globally nearest; unrelated roots can revisit overlapping jars, leaf-list comparison remains, and conflict construction still inspects overlaps.

Final frozen gate: **276 tests passed, zero failures/errors/skips**, 3m45s, `stage2-reconciliation-frozen-gate.log`. No Java source changed during the gate or after it before this checkpoint. The real Maven oracle covered **13 modules, 26 scopes, 495 sources**, with **26/26 exact source/class k matches, no annotation differences and zero faults** under each digest. It indexed 91 jars on the spot and used 22 distinct states including the common JDK base.

| Digest | Wall ms | Header ms | Facts + proofs ms | Definer ms | Nodes |
| --- | ---: | ---: | ---: | ---: | ---: |
| SHA-256 | 5,316 | 3,484 | 1,822 | 168 | 23,282 |
| SHA3-256 | 4,178 | 2,382 | 1,072 | 218 | 23,265 |

Maven model/build time (30.9/31.6s) is excluded. Sampled heap above baseline was 818/1,302 MB with uncontrolled GC; these sequential timings do not prove a speedup. Raw reports: [SHA-256](measurements/stage2-reconciliation-SHA-256.txt), [SHA3-256](measurements/stage2-reconciliation-SHA3-256.txt).

```powershell
mvn -B -pl jvmd-tests -am test '-Dtest=DefinerStateTest,MachineColdBootTest,ClassMemoTest,ContentTreeTest,ContentTreeEditTest,ZipReaderTest,RocksMachineBootTest,LocalColdBootTest,LocalCodecsTest,FactCodecsTest,RocksLocalBootTest,Layout4Test,HeaderAbsencesTest,HeaderReverseTest,SourceAnnotationProjectionTest,TailStrippingTest,StubProjectionTest,PersistedLeavesTest,MavenProjectTest,Stage2Measurement,ModuleArchitectureTest,ForbiddenIdentifiersTest' '-DexcludedGroups=none' '-Djvmd.stage2.workers=4' '-Dsurefire.failIfNoSpecifiedTests=false'
```

The full Stage 2 authority was reconciled, with a tracked snapshot at [jvmd-stage2-local-cold-boot.md](jvmd-stage2-local-cold-boot.md). It replaces stale FileMemo/whole-oSum/reverse-list/layout-3 prose, documents current codecs and 25 invariants, separates legacy C/RS placeholders from Stage 3, and states actual memory/route/history limits. Diff compares keys/h, so the document distinguishes semantic Diff from exact value deltas when an entry's h projects away storage values. No Diff implementation change is claimed. Stage 3 Appendix A agrees on full warning metadata, parser/LOCAL 4, exact header/reverse observations and route ancestry. The Downloads authorities and repo snapshots are byte-identical:

- Stage 2 SHA-256 `f60bccee3c347fb2dc2b1c8147e6c1e6d4740fefd7cab2c88e508449e0c4fab6`.
- Stage 3 SHA-256 `9c0a1ebedefcbd6575af5240e83e42ddc52fca94526ee6e46ec0ae0a0deb1f3b`.

The previous Downloads authorities are retained beside them with `.before-stage2-reconciliation.md` suffixes. The Stage 3 revision number remains 123 with a dated amendment; this is not a new upstream revision.

Post-merge PR #60 distribution run [37396638545](https://github.com/maxjay/jvmd/actions/runs/37396638545) failed on both Linux jobs during TemporaryDirectory cleanup (`OSError: Directory not empty`) after each job emitted passing smoke-check evidence. macOS and Windows jobs passed; publishing was skipped. The pre-merge Tests/benchmarks remain green. This later distribution cleanup failure is identified but not repaired by the Stage 2 changes.

This follow-up needs its own hosted checks and review. PR #60's merge approval does not authorize merging it. The separate feat/jvmd-stage-3 tree still preserves uncompiled ProcessorReplay/HeaderCompiler/ProcessorHost/ProcessorReadTest changes. Next integrate the reconciled foundation without losing that WIP, finish Appendix F's isolated replay proofs and reconcile F with the user's processorElementProjection/ContentTree overrides, then implement body attribution/results/driver and all thirty Stage 3 invariants. No Stage 3 completion or warm implementation is claimed.

### Stage 2 foundation integrated into the Stage 3 branch

The Stage 3 branch now combines Stage 2 reconciliation at `66d27412` with Appendix F's generated-file provenance, processor configuration and output manifests. FileRow retains its generated/origin/genId/processorContext fields and adopts exact T ranges plus N/D absences. The combined format is **LOCAL 5**; the standalone Stage 2 specification still documents its LOCAL 4 baseline. Isolating derivation header observations encode separate T/N/D namespaces and exact ranges rather than the removed whole-type proof representation.

`stage3-reconciliation-integration.log` passed **159 tests, zero failures/errors/skips**, including header ranges/reverse lookup, source/class annotation parity, processor model/projection/reads/diagnostics/configuration/empty-output cases, generated manifests, Rocks format migration and architecture checks. This gate predates restoration of the isolated ProcessorReplay work. ProcessorStage2Test was not included and still needs to run with that restoration. The four replay WIP files are retained in stash `ac0069884ded850b4a1d1f1dfe1400600dfcdd8b` until recovery and verification. Appendix F and the complete Stage 3 goal remain unfinished.

### Isolated processor replay restored and checked

The reconciliation merge is `c579534a`. All four saved replay files were recovered with stash apply; the stash is retained as a backup. A fresh processor classloader/instance now checks each originating file against phase-specific captured model answers. Round annotation names and source provenance are captured during the live round; replay does not recover them by querying the completed compiler. The guard regression rejects native annotation queries after capture and checks unknown annotation/provenance handles have explicit failures.

Each origin receives its own ordered model proof and observed header dependencies. The cached-read regression covers a processor whose static cache is filled while generating for the first of two origins: changing an external annotation changes both derivations while T remains equal, and a body edit in one origin leaves the other's derivation and GEN writes unchanged. A static counter whose output depends on earlier origins is explicitly unsupported; native outputs remain available with no reusable GEN ids. Nondeterministic isolating bytes are rejected on the first replay, while the separate aggregate regression still tests preservation of an already stored GEN on a later collision. The Lookup fixture now uses its supported annotation domain; its old root search depended on seeing another origin and correctly failed isolated validation when Optional was added.

`stage3-isolated-replay-first.log` recorded four expected-contract failures across both digests before the fixture/nondeterminism assertions were corrected. `stage3-isolated-replay-focused.log` then passed **30 tests**, and the expanded `stage3-isolated-replay-integration.log` passed **183 tests**, zero failures/errors/skips, including the actual ProcessorStage2Test and the new cross-origin rejection test. A broader frozen source/class oracle gate is running at this checkpoint.

Appendix F and section 3.5 now describe processorElementProjection from the Element model, exact generated-output ContentTree roots and GS blobs, independent query proofs, and LOCAL 5. They remove the AST skeleton, single-source GEN payload and false isolating body-invariance claims. Adjacent processor input tables and shortcut/compatibility claims agree. Cold processing still executes natively on each boot; replay is a per-origin check of that invocation, not a persisted verifier that permits skipping arbitrary processors. Remaining work includes the admitted-input audit, generated-round lineage/support boundaries, Attribute and its processor checks, result/proof/reverse/output codecs and driver, and the full Stage 3 invariants. Warm remains untouched; the active goal is unfinished.

PR #61 remains a draft at `66d27412`. Its hosted [Tests](https://github.com/maxjay/jvmd/actions/runs/37398435468) and [benchmarks](https://github.com/maxjay/jvmd/actions/runs/37398435592), including cold-start/scenarios contexts, are now successful. It has not been merged; PR #60's earlier approval was not extended to it. The post-merge Linux distribution cleanup failure remains separate and unresolved.

The final frozen gate passed **373 tests, zero failures/errors/skips**, in 4m32s (`stage3-replay-frozen-gate.log`). Java sources were frozen throughout and remain unchanged after that gate. The combined oracle covered **13 modules, 26 scopes, 515 sources**, indexing 93 jars on the spot. Both digests gave **26/26 exact source/class k matches, no annotation differences and zero boot faults**, using 22 distinct states including the JDK base. This validates the Stage 2/F integration, not the unfinished Stage 3 body/result invariants.

| Digest | Wall ms | Header ms | Facts + proofs ms | Definer ms | Nodes |
| --- | ---: | ---: | ---: | ---: | ---: |
| SHA-256 | 5,693 | 3,862 | 1,800 | 212 | 24,934 |
| SHA3-256 | 4,328 | 2,525 | 1,004 | 173 | 24,945 |

Maven model/build time (34.2/32.4s) is excluded. Sampled heap above baseline was 1,128/1,380 MB without controlled GC. These sequential timings are measurements, not a performance comparison. Raw reports: [SHA-256](measurements/stage3-isolated-replay-SHA-256.txt), [SHA3-256](measurements/stage3-isolated-replay-SHA3-256.txt).

```powershell
mvn -B -pl jvmd-tests -am test '-Dtest=DefinerStateTest,MachineColdBootTest,ClassMemoTest,ContentTreeTest,ContentTreeEditTest,ZipReaderTest,RocksMachineBootTest,LocalColdBootTest,LocalCodecsTest,FactCodecsTest,RocksLocalBootTest,Layout4Test,HeaderAbsencesTest,HeaderReverseTest,HeaderEnvironmentTest,SourceAnnotationProjectionTest,TailStrippingTest,StubProjectionTest,PersistedLeavesTest,MavenProjectTest,Stage2Measurement,GeneratedOutputsTest,ProcessorConfigurationTest,ProcessorDiagnosticsTest,ProcessorEmptyOutputTest,ProcessorHostTest,ProcessorModelTest,ProcessorProjectionTest,ProcessorReadTest,ProcessorReplayTest,ProcessorStage2Test,ModuleArchitectureTest,ForbiddenIdentifiersTest' '-DexcludedGroups=none' '-Djvmd.stage2.workers=4' '-Dsurefire.failIfNoSpecifiedTests=false'
```

The Stage 3 Downloads authority and tracked snapshot are byte-identical, SHA-256 `57be59903b1b70fc09c6f4b6f3b95582203d43238609c50fa2f4eca155e36aba`. The previous authority is retained at `C:\Users\Max\Downloads\jvmd-stage3-bodies.before-processor-replay.md`. Revision stays 123 with the dated amendment. The Stage 2 authority is unchanged. No future processor invocation skip or general processor input audit is claimed by the replay gate; those admission/reuse boundaries must remain explicit while completing the active goal.


### Body proof, result records and reverse lookup foundations

Stage 3 now has `Proof`/`Arrange` with symmetric processor-context validation, route/index shortcuts, per-type T descent and independent N-range validation. ACI binds the source basename, bytes, options, processor context and sorted exact T/N/D key/sum observations; it excludes the shortcut identities. Real Stage 2 fixtures prove unread-member stability, constant/overload invalidation, own-first shadowing, nested-type arrival despite unchanged outer oSum, and the three-root-read shortcut after jar repackaging. These tests supply collected ranges explicitly; they do not claim the body collector exists yet.

The unused legacy ConsumerRecord and kappa/leaf-set result keys are replaced on the Stage 3 branch by C/project/path, RS/ACI, CF/content, U/ACI and BROOT records. Diagnostics preserve ordering, repeats and NOPOS. A javac regression with a supplementary Unicode character established UTF-16 character offsets, correcting the specification's byte-offset wording. Uses retain separate T/N/D keys and canonical spans outside the bodies tree. Body reverse lookup seeks exact X prefixes and checks BROOT membership, including the stale bodies root after a LOCAL recommit; it never scans file/proof rows. Tests cover raw stale keys, two paths with shared observations, changed whole/named ranges, N/D arrivals and one root read per project.

Generated-source chains retain the immediate generated origin's derivation across rounds; four regression cases under both digests cover this and replay-only processor AssertionError. That error now rejects reusable GEN admission while retaining native generated rows. T/N/D namespaces in isolating input identities are canonicalized to 0/1/2. No processor invocation skipping or general external-input admission is claimed.

Focused gates passed 14, 142 (clean build), and 15 tests. The final frozen gate `stage3-body-foundation-frozen-gate.log` passed **399 tests, zero failures/errors/skips**, in 4m45s. Java sources were frozen throughout. The Maven oracle covered **13 modules, 26 scopes, 522 sources**, 93 on-the-spot jars and 22 distinct states; both digests gave **26/26 exact source/class k matches, no annotation differences and zero faults**.

| Digest | Wall ms | Header ms | Facts + proofs ms | Definer ms | Nodes |
| --- | ---: | ---: | ---: | ---: | ---: |
| SHA-256 | 5,603 | 3,820 | 1,776 | 159 | 24,947 |
| SHA3-256 | 6,502 | 4,112 | 1,945 | 305 | 24,973 |

Maven model/build time (34.0/33.2s) is excluded. Sampled heap above baseline was 710/1,961 MB with uncontrolled GC; these sequential timings are not a speed comparison. Raw reports: [SHA-256](measurements/stage3-body-foundation-SHA-256.txt), [SHA3-256](measurements/stage3-body-foundation-SHA3-256.txt).

The specification now states UTF-16/NOPOS positions, symmetric processor-context checking before shortcuts, exact U/ACI namespaces, BROOT membership for body reverse lookup, and bodies FORMAT derived from LOCAL plus `;bodies=1`. Downloads and the tracked snapshot are byte-identical, SHA-256 `c1c854a0f5eb77d081bebe2b8e819f502a3e5966a233c2e7818d298184f18965`. The prior Downloads copy is retained as `jvmd-stage3-bodies.before-body-records.md`; revision remains 123 with its dated amendment. The Stage 2 authority is unchanged.

PR #60 remains merged at f6472306 after its merge-critical fixes. PR #61 remains a separate draft at 66d27412 with successful Tests/benchmarks; no merge approval has been extended to it. The later PR #60 Linux distribution cleanup failure remains unresolved. Next: actual reusable javac pool, attributed-tree collector, per-file Attribute, output materialisation and Stage3 driver with the full invariants. Warm remains untouched and the active implementation goal is incomplete.


### Reusable per-file compiler pool and exact class output materialisation

Checkpoint `29682304` saved the 399-test proof/record/reverse foundation. The next additions are greenfield `stage3.Pool` and `stage3.Output`; they do not call the old analyzer and are not yet wired into the full Stage3 driver.

Pool has W separately borrowed JavacTaskPool(1) workers, each with one stable StandardJavaFileManager and memory-output wrapper. Own stubs precede the route, the source path is empty, each task receives exactly one source and Locale.ROOT, and a worker remains borrowed until its callback finishes. The configuration fixes the external/sibling/own identities and effective options for its scope; automatic key replacement will belong to the driver. Closing waits for active workers and closes file managers plus their created classloaders. Compiler internals are permitted only for this exact Pool adapter in the architecture test; boot adds the code-package export needed for symbol eviction.

The initial `stage3-body-pool-first.log` exposed that Symtab removal alone leaves package-cache state that makes later own-type lookup fail. Eviction now removes each loaded own symbol from its owner scope too, retains only its module/name, and re-seeds a lazy symbol from its known stub file at the next borrow, after native cleanup. It does not reset or scan whole packages. A regression checks that own symbols change identity while a loaded JDK dependency symbol remains identical. A deliberately mismatched source constant exposes source-to-stub leakage that equal APIs could mask. Another first-pass regression caught a locked dependency jar on Windows: the reusable compiler's close method does not close its processor/classpath loaders. The pool now owns and closes those returned through its file manager, and the real jar deletion test passes.

Pool tests use actual JVMD-generated stubs and compare F/G/F bytes with fresh javac and the complete two-source javac output, including nested/local/anonymous classes. Two workers match serial bytes. Error diagnostics clear between tasks, callback failure discards the bad context, Java 8 source/target mode reuses correctly, adjacent source files are never silently compiled, and closed pools reject new tasks. The task callback must detach its observations before returning; production body collection is still pending.

Output builds B.5's internal-name-to-CF ContentTree from clean results only. Materialisation applies Diff against the directory's canonical-path MAT root, reads only changed CF blobs, validates the delta before touching class paths, preserves unrelated files and unchanged timestamps, replaces class files through temporary files, and publishes MAT only after successful writes/deletes. Failed attribution contributes no output, so its prior nested/outer classes disappear. Duplicate output names, escaping paths and corrupt CF bytes are rejected. Tests show the old MAT and old outputs survive invalid content, repair succeeds on retry, two destination directories have independent MAT records, and identical output roots cause no node/blob reads. A class compiled through Pool is materialised and successfully executed by a fresh `java -cp` process under both digests.

`stage3-body-pool-restored.log` passed 8 tests; `stage3-body-pool-close.log` passed 9. The combined `stage3-pool-output-focused.log` passed 52 tests, then `stage3-pool-source8.log` passed all 7 pool cases after adding the Java 8 case. The clean full gate `stage3-pool-output-frozen-gate.log` is running with Java sources frozen. The specification now puts collection before generation and describes the exact own-symbol and resource lifecycle.

Next remains the attributed-tree collector, including external candidate/absence observations when a previous selected member belonged to the current source, and sound observations for failed lookups. Excluding the source's own facts must not erase external overload candidates. Then integrate actual Attribute (including admitted processors), current binding/options preparation, class/result persistence and the Stage3/BROOT driver, with all thirty invariants and measurement. Pool/output tests alone do not establish complete Stage 3 or all of invariant 17's semantic proof requirements. Warm remains untouched.

The Stage 3 Downloads authority and tracked specification are byte-identical, SHA-256 `fadf0782da1ba8850e441dea19cd555111d04d4eaa753d808f5b9e67cc77979a`. The prior authority is preserved as `jvmd-stage3-bodies.before-pool-output.md`. Revision remains 123 with the dated amendment, and Stage 2's authority is unchanged.


The clean frozen gate completed successfully: **416 tests, zero failures/errors/skips**, in 4m51s (`stage3-pool-output-frozen-gate.log`). No Java source changed during the run or afterwards before this checkpoint. Its Maven oracle covered **13 modules, 26 scopes, 526 sources**, 93 on-the-spot jars and 22 distinct states. Both digests produced **26/26 exact source/class k matches, no annotation differences and zero boot faults**.

| Digest | Wall ms | Header ms | Facts + proofs ms | Definer ms | Nodes |
| --- | ---: | ---: | ---: | ---: | ---: |
| SHA-256 | 5,544 | 3,681 | 1,671 | 167 | 24,965 |
| SHA3-256 | 4,465 | 2,597 | 1,108 | 180 | 25,004 |

Maven model/build time (34.4/33.9s) is excluded. Sampled heap above baseline was 665/797 MB with uncontrolled GC; these are sequential measurements, not a speed comparison. Raw reports: [SHA-256](measurements/stage3-pool-output-SHA-256.txt), [SHA3-256](measurements/stage3-pool-output-SHA3-256.txt).

```powershell
mvn -B -pl jvmd-tests -am clean test '-Dtest=DefinerStateTest,MachineColdBootTest,ClassMemoTest,ContentTreeTest,ContentTreeEditTest,ZipReaderTest,RocksMachineBootTest,LocalColdBootTest,LocalCodecsTest,FactCodecsTest,RocksLocalBootTest,Layout4Test,HeaderAbsencesTest,HeaderReverseTest,HeaderEnvironmentTest,SourceAnnotationProjectionTest,TailStrippingTest,StubProjectionTest,PersistedLeavesTest,MavenProjectTest,Stage2Measurement,GeneratedOutputsTest,ProcessorConfigurationTest,ProcessorDiagnosticsTest,ProcessorEmptyOutputTest,ProcessorHostTest,ProcessorModelTest,ProcessorProjectionTest,ProcessorReadTest,ProcessorReplayTest,ProcessorRoundsTest,ProcessorStage2Test,BodyProofTest,BodyRecordsTest,BodyReverseTest,BodyPoolTest,BodyOutputTest,ModuleArchitectureTest,ForbiddenIdentifiersTest' '-DexcludedGroups=none' '-Djvmd.stage2.workers=4' '-Dsurefire.failIfNoSpecifiedTests=false'
```

This checkpoint validates the new compiler pool and output components with the existing Stage 2/F/proof foundation. The full Stage 3 goal remains active and incomplete; it does not establish the pending body collector, processor-aware Attribute, driver/root publication, or full thirty-invariant gate. PR #61 stays a draft; no additional PR was merged.


### Attributed body collector and native hierarchy observations

The new `BodyCollector` runs over a real attributed unit before lowering, through `ProofCollector.bodies`. It builds task-local tree paths and own binary names, caches hierarchy closures, and returns detached T/N ranges, candidate type keys and UTF-16 spans. Source-owned facts are excluded while external candidates remain, including when an invocation previously selected an own member. The existing Stage 2 header traversal remains separate and still excludes executable bodies.

Mutation regressions cover constants, overloads, inferred expression types, inherited member types, array length, intersection bounds, functional interfaces, method references, enum exhaustiveness, declared thrown types, own-module type arrival, static import candidates and local/anonymous class contracts. The initial healthy-type regression exposed javac ClassType implementing ErrorType even for valid types; dispatch now uses TypeKind. Another regression showed that adding an inherited field can reclassify an expression head that previously resolved to a package or imported/member type. The collector records those FIELD ranges, while type-only declarations and class literals do not acquire them.

A failing `pick(null)` ambiguity regression proved that attributed syntax alone omits candidate hierarchy reads. Pool now installs a narrow native Types adapter before compiler initialization and observes supertype/interfaces queries for actual binary types. Both candidates are retained without walking every candidate signature: the wrong-arity regression excludes the unused parameter hierarchy and remains valid when that hierarchy changes. The native set resets per task; F/F/other/F tests retain identical reads on one cached context and show no cross-file leakage. Compiler-created pseudo-types are excluded by their lack of a class-file input, and native reads without source syntax have no invented U span.

Failed simple type resolution produces candidate keys, bound by Arrange against the same current own-first indexes as the proof. Missing candidates become D; present inaccessible or ambiguous candidates become exact T headers, with U keys converted and spans merged. Tests first demonstrated the stale-valid missing expression qualifier and the false-absence exception for an inaccessible type, then verified repair after class arrival/access changes. No containing-scope identity or silent proof fallback was added.

The focused gate `stage3-body-collector-focused.log` passed **83 tests**, including **40 body collector cases** under SHA-256 and SHA3-256, with zero failures/errors/skips. The clean full `stage3-body-collector-frozen-gate.log` is running with Java sources frozen. The tracked specification and Downloads authority were synchronized after preserving `jvmd-stage3-bodies.before-body-collector.md`; both hash to SHA-256 `75c0499096e77efc148a36abadcf6880650381b8fe1566cfa90bc9f4818d9b90`. Revision remains 123 with the dated amendment.

This is collector implementation progress, not a claim that the complete read-set theorem has been established. Remaining work includes the two-sided compiler read oracle over all fixtures and own modules, further language/error lookup coverage, processor-aware Attribute and admitted-input checks, source/options binding and result persistence, the Stage3 driver/BROOT publication, and all thirty invariants. Native observations must be retained through the phases Attribute proves, including any generation-only reads. Warm remains untouched. PR #61 remains a separate draft; no additional merge is authorized or performed. The earlier Linux distribution cleanup failure is unresolved.

The first frozen gate completed: **456 tests, zero failures/errors/skips**, in 5m00s. Its oracle covered 13 modules, 26 scopes and 528 source files, with **26/26 exact source/class k matches and no annotation differences under both digests**. After that freeze ended, a separate lazy-completion probe demonstrated one missing native observation: ClassSymbol.classfile can be null before a supertype query and become populated during that query. Recording now occurs after the native operation; a committed regression asserts that exact transition. The updated focused gate `stage3-body-collector-completion-verified.log` passes **84 tests**, including the new completion test, without adding compiler export grants. The final full gate will run over this updated source state before the checkpoint is reported as fully verified.

Checkpoint `5e61d426` contains the collector, binding and native-query changes and has been pushed to `feat/jvmd-stage-3`. The updated Java sources are frozen for `stage3-body-collector-final-gate.log`. Further collector work should explicitly test ambiguous inherited member-type lookup (a direct declaration currently ends that branch), invalid functional targets whose attributed lambda type may be erroneous, and generation-only observations before claiming the two-sided read oracle. These coverage questions remain part of the active Stage 3 goal, not reasons to widen proofs to a containing scope.


The final clean gate over the Java sources committed as `5e61d426` passed **457 tests, zero failures/errors/skips**, in 3m50s (`stage3-body-collector-final-gate.log`). Java sources were frozen throughout and remain unchanged. The oracle covered **13 modules, 26 scopes, 528 sources**, 93 on-the-spot jars and 22 distinct states. Both digests gave **26/26 exact source/class k matches, no annotation differences and zero boot faults**.

| Digest | Wall ms | Header ms | Facts + proofs ms | Definer ms | Nodes |
| --- | ---: | ---: | ---: | ---: | ---: |
| SHA-256 | 3,949 | 2,647 | 1,238 | 111 | 25,008 |
| SHA3-256 | 3,179 | 1,809 | 749 | 120 | 25,018 |

Maven model/build time (25.3/25.4s) is excluded. Sampled heap above baseline was 1,098/795 MB with uncontrolled GC. These sequential measurements are not a performance comparison. Raw reports: [SHA-256](measurements/stage3-body-collector-SHA-256.txt), [SHA3-256](measurements/stage3-body-collector-SHA3-256.txt).

This gate validates the collector checkpoint with the Stage 2/F and body proof/pool/output components. It does not establish the pending complete two-sided body read oracle, processor-aware Attribute, driver/root publication or all thirty Stage 3 invariants. The implementation goal remains active. PR #61 has not been merged, and the earlier post-merge Linux distribution cleanup failure remains unresolved.


### Error-path observations, ordered pool binding and source-byte input

The ambiguous inherited member-type and invalid functional-target regressions first failed under both digests (`stage3-body-error-contracts-red.log`, four stale-valid results). The collector now records the nonempty N range before stopping each direct-member branch. The native Types adapter retains functional-descriptor method contracts even when descriptor lookup fails; class targets rejected on their header do not gain an unused method-set dependency. The final focused error gate passed 54 tests (`stage3-body-error-contracts-final.log`).

Pool completion now returns detached native observations captured after generation. ProofCollector merges these with the earlier tree observations, excludes source-owned types and preserves existing U spans. A record/string-concatenation regression proves additional reads arise only during generation and are identical on a reused context. No compiler context or symbol is required during subsequent Arrange.

The route-order regression first failed under both digests because the unordered external/sibling leaf sets keyed the same pool for opposite conflicting jar orders (`stage3-body-pool-order-red.log`). Pool now uses the existing `(routeHash, own.k)` binding. The regression verifies equal leaf sets, distinct keys and byte equality to fresh javac in both orders; the chosen constant changes the output bytes. This changes compiler-resource ownership only; routeHash remains excluded from ACI, and no broad identity was added to a semantic proof. The combined order/generation gate passed 21 tests.

The pool's source-byte overload owns an immutable snapshot and decodes it with the task-bound native BaseFileManager. UTF-8, Latin-1, supplementary Unicode, malformed input and error-then-success on a reused context match native diagnostic kinds, codes, text, source URI and all positions. Deleting the source path and mutating the caller's array before parsing cannot change the snapshot. The oracle exposed that a separately supplied native file manager can report a decode error through its own Log while a task still emits bytes; the adapter binds decoding to the task Log and emits no output on that error. Successful outputs match fresh javac byte-for-byte. The file-package export is limited to the existing Pool adapter, and launcher/test grants are updated consistently. `stage3-body-byte-input.log` passes all 10 pool and 3 architecture tests.

The specification and Downloads authority are byte-identical at SHA-256 `025fc45213eede3ab1c21ca30fbacca25ab84dbab090ba8080abbf2db368ae01`, after preserving `jvmd-stage3-bodies.before-error-reads-byte-input.md`. Revision remains 123 with the dated amendment; the Stage 2 authority is unchanged. The clean combined gate `stage3-body-input-frozen-gate.log` completed with Java sources frozen; results follow.

This checkpoint supplies the source-byte input primitive, not a completed Attribute or driver. Next work must bind the snapshot digest to F, compute the effective attribution options, integrate processor admission/output checks, persist CF/RS/U and publish Stage3/BROOT. The complete two-sided body proof oracle and all thirty invariants remain required. Warm is unchanged, the goal remains active, PR #61 remains a separate draft, and the earlier Linux distribution cleanup failure remains unresolved.


The clean combined gate passed **467 tests, zero failures/errors/skips**, in 3m53s. All 533 tracked Java source files match the pre-run digest snapshot; no source changed during verification. The repository oracle covered **13 modules, 26 scopes, 528 sources**, 93 on-the-spot jars and 22 distinct states, and produced **26/26 exact source/class k matches, no annotation differences and zero boot faults under both digests**.

| Digest | Wall ms | Header ms | Facts + proofs ms | Definer ms | Nodes |
| --- | ---: | ---: | ---: | ---: | ---: |
| SHA-256 | 4,199 | 2,669 | 1,355 | 126 | 25,008 |
| SHA3-256 | 3,190 | 1,749 | 806 | 132 | 25,024 |

Maven model/build time (25.6/25.2s) is excluded. Sampled heap above baseline was 1,026/1,172 MB with uncontrolled GC; these sequential runs are not a performance comparison. Raw reports: [SHA-256](measurements/stage3-body-input-SHA-256.txt), [SHA3-256](measurements/stage3-body-input-SHA3-256.txt). The distribution assembly script also passes Bash syntax checking. These gates validate this checkpoint; the Attribute/processor/driver and complete Stage 3 proof obligations listed above remain outstanding.


### Per-file attribution executor and content publication

Attribute now binds one immutable source snapshot to F's kappa and basename, requires the current route/own-leaf pool key and the exact prepared charset/options, then performs parse/analyze, pre-lowering collection and in-memory generation. It merges the detached native observations, arranges the proof, computes ACI and persists CF/RS/U. Diagnostic values are snapshotted at emission; errors retain a result/proof but no class references. Existing content is checked before publication, and only the storage check/flush is serialized, so concurrent equal attributions write one copy of every record. C/reverse/OUT/BROOT remain driver-owned; Attribute leaves LROOT untouched.

JavacOptions contains the shared Stage 2/3 source normalization, charset, filtering and optionsHash policy; HeaderCompiler delegates to it without changing its option semantics. Attribute's unprocessed entry point rejects processor-bearing module/file inputs. This is an explicit implementation boundary, not a replacement for the required processor-aware path or an admission claim.

The first gate passed 15 tests. The combined `stage3-attribute-focused.log` passed 107 tests, including 14 new attribution cases under both digests. `stage3-attribute-input-errors.log` passes all 18 attribution cases after adding malformed-byte and locale regressions. Fresh whole-module javac matches the combined per-file class bytes, including records, local/anonymous classes, additional top-level declarations and debug/parameter metadata. Tests compare exact ordered warnings/errors and UTF-16 positions, exercise error-then-success context reuse, assert relocation with the same basename yields the same result/ACI without record writes, and prove concurrent duplication writes only one CF/RS/U set. Charset and debug changes move ACI appropriately; equal decoded characters under different encodings still produce equal class bytes. Stale snapshots, wrong basenames and mismatched pool keys/options fail before compilation/publication.

The clean full gate `stage3-attribute-frozen-gate.log` completed with all tracked and new Java source files frozen; results follow. The Stage 3 Downloads authority was synchronized after preserving `jvmd-stage3-bodies.before-attribute-executor.md`; both copies hash to SHA-256 `fa56e7c0e8d5331bab9f7b7ee923dc327efeeefea2188d3fa0d77c8a121fd0bb`. Revision remains 123 with its dated amendments; Stage 2's authority is unchanged.

Next: the processor-aware Attribute path must capture Filer output without compiling it, select admitted isolating/overlay processors while excluding aggregating processors, prove actual processor reads, and compare outputs with GEN. It must not publish reuse on unsupported inputs. Then complete current route/source/options preparation from LROOT, the Stage3/BROOT driver and all thirty invariants, including the full two-sided body-read oracle. The existing classpath-mode/JPMS and release-view boundaries of Stage 2 also need to be respected by the whole-project oracle. Warm remains untouched and the full goal remains active.


The clean gate passed **485 tests, zero failures/errors/skips**, in 3m57s. All **536 Java source files**, including the three new files, match the pre-run digest snapshot. The repository oracle covered **13 modules, 26 scopes and 531 sources**, 93 on-the-spot jars and 22 distinct states. Both digests produced **26/26 exact source/class k matches, no annotation differences and zero boot faults**.

| Digest | Wall ms | Header ms | Facts + proofs ms | Definer ms | Nodes |
| --- | ---: | ---: | ---: | ---: | ---: |
| SHA-256 | 4,216 | 2,763 | 1,278 | 137 | 25,011 |
| SHA3-256 | 3,206 | 1,783 | 799 | 131 | 25,040 |

Maven model/build time (25.3/26.1s) is excluded. Sampled heap above baseline was 1,054/1,170 MB with uncontrolled GC; these sequential measurements are not a performance comparison. Raw reports: [SHA-256](measurements/stage3-attribute-SHA-256.txt), [SHA3-256](measurements/stage3-attribute-SHA3-256.txt).

Invariant 20 remains a driver integration obligation: the executor currently checks the exact CF/RS/U keys it has just computed when deduplicating publication. The driver must provide the required cold-run memo/root-bounded store view and prove that previous result reuse is reached through BROOT, rather than treating unrooted records as reusable results. No exception to invariant 20 is established by these executor tests. Processor support and the other outstanding invariants above remain part of the active goal; PR #61 remains a separate draft.


### Body processor capture and reusable processing state

ProcessorHost now has a body-task mode that resolves Stage 2 capabilities by the full ordered processor-path byte hash, excludes aggregating processors before class loading (including dynamically declared aggregates), and installs the existing Trees observer before parsing. Missing Stage 2 capabilities fail preparation. A recorded unsupported isolating processor still executes freshly and remains unsupported; selection is not a proof of reusable results.

Filer source writes are captured in memory, without registering generated inputs with javac. Captures retain processor class, immediate origin, output kind/path and exact bytes. The shared task namespace rejects source/class duplicate names, output reopening, resource collisions and attempts to overwrite the explicit source. Writers use the prepared charset, streams preserve raw bytes, and only close publishes an output. Unclosed outputs, class/resource outputs and final-round generation cannot claim reusable derivations. The capture directory is only a logical name and is never created. Class/resource capture does not establish those outputs' reuse semantics or the required native fallback for arbitrary unsupported processors.

The new repeated-task tests initially reached a native javac assertion: JDK 25's JavacTaskPool clears compiler queues but retains JavaCompiler.procEnvImpl, causing the second task to close the previous processing environment instead of initializing its own diagnostic handler and processors. The Pool adapter now closes/detaches that environment after each task while retaining the compiler context. Access to the one private field is confined to Pool and tied to the full javac runtime version already in FORMAT; test and distribution runtime grants include the required main-package opening and processing-package export. Architecture guards still reject compiler internals outside the existing adapters. The distribution assembly script passes Bash syntax checking.

`stage3-body-processors-reset.log` passes the first six processor cases after that fix. `stage3-body-processors-capture.log` passes 35 tests. The final focused gate, `stage3-body-processors-focused.log`, passes **58 tests, zero failures/errors/skips**. Under both digests, fresh native javac agrees with AutoValue's captured source and the original file's class bytes, and with Lombok @Value/@Builder's original/nested class bytes over repeated borrows of one context. A three-output generated-source chain produces only each explicit origin's immediate outputs; both generated sources compile independently against Stage 2 stubs, including the empty derivation. A processor with an unproved Trees read still runs, reports its class as unsupported, and emits native class bytes; a processor diagnostic error does not leak into the next borrow. Additional tests cover UTF-8/Latin-1 writers, raw bytes, output-close behavior and duplicate/resource namespace rules.

This is an execution/capture component, not the completed processor-aware Attribute path. Attribute still exposes only its unprocessed persistence path. Required next steps remain: bind captured processor observations to the body result identity, validate actual model reads before reuse, reach each origin's expected GEN manifest (including empty sets), compare complete output maps, and handle unsupported native processing without publishing reusable RS/GEN. Then finish current inputs from LROOT, the Stage3/BROOT driver and all remaining invariants, including the two-sided body-read oracle and the 2,000-file Lombok reuse/configuration test. The invariant 20 storage boundary remains outstanding. No module/route/project-wide semantic fallback was introduced.

The clean combined gate `stage3-body-processors-frozen-gate.log` completed with **539 Java sources** frozen; results follow. Both specification authorities are unchanged in this checkpoint. Warm is untouched, the full goal stays active, PR #61 remains a separate draft, and no additional merge is authorized.


The clean combined gate passed **498 tests, zero failures/errors/skips**, in 4m00s. All 539 Java files match the pre-run digest snapshot. The repository oracle covered **13 modules, 26 scopes and 534 sources**, 93 on-the-spot jars and 22 distinct leaf sets. Both digests produced **26/26 exact source/class k matches, no annotation differences and zero boot faults**.

| Digest | Wall ms | Header ms | Facts + proofs ms | Definer ms | Nodes |
| --- | ---: | ---: | ---: | ---: | ---: |
| SHA-256 | 4,105 | 2,755 | 1,334 | 131 | 25,037 |
| SHA3-256 | 3,116 | 1,896 | 854 | 131 | 25,060 |

The measurement used the host default of **16 workers**: the invocation's `-Dworkers=4` was not the measurement's `jvmd.stage2.workers` property. Maven model/build time (25.9/25.6s) is excluded. Sampled heap above baseline was 1,322/1,271 MB with uncontrolled GC; neither the sequential digests nor earlier checkpoints are a controlled performance comparison. Raw reports: [SHA-256](measurements/stage3-body-processors-SHA-256.txt), [SHA3-256](measurements/stage3-body-processors-SHA3-256.txt).

Integration must also bind dynamic processor classification to the actual Stage 2 scope/options: the current global PROC key is only processor-path hash plus class, and a dynamic processor can resolve differently under different options. The capture fixtures pass the current invocation's capabilities; consulting a globally merged capability alone is not yet a proof that one scope may skip that processor. Processor-generated diagnostics and unsupported native-output handling also need the complete module oracle. These remain required work, not exceptions to the specification.


### Scope-specific processing plans and rooted generation references

LOCAL 6 adds PS records containing the full processor-path hash, effective options hash and ordered per-scope processor classifications. Dynamic declarations are now selected using the invocation that Stage 2 actually ran. The global PROC record remains observation/violation history with a commutative classification consensus: disagreement yields NONE, rather than whichever scope finishes last. ProcessorHost's body factory accepts the scoped plan and verifies its options and processor bytes before executing the selected set. Existing LOCAL 5 roots require a cold rebuild; the F row encoding is unchanged.

Stage 2 now also publishes PG references from each admitted processor/origin (or the empty aggregate origin) to its GEN id. References include empty output sets and generated origins. They are fresh members of the LOCAL tree and are not read before its commit. ProcessorPlan looks up PS, PG and GEN through that committed root, verifies each record value against its tree entry and rejects missing/tampered records. An absent PG remains distinct from an admitted empty manifest. Stale raw references left behind by prior generations cannot become current derivations.

GeneratedOutputs.matches compares the complete output map using the distinct kind/path set and exact byte digests. It rejects additions, removals, duplicates, renames, kind changes and byte changes without reading GS blobs or scanning records. These records and comparisons do not introduce a scope identity into ACI or substitute output equality for a model-read proof.

The first gate `stage3-processor-plan-initial.log` passed 24 tests. The expanded `stage3-processor-plan-focused.log` passed **153 tests, zero failures/errors/skips**. Eight new cases under both digests verify opposite dynamic classifications under different module options, equal publication with reversed module order and 1/4 workers, exact empty/nonempty references, body-only edits confined to their own isolating derivation, stale-reference exclusion after annotation removal, corruption rejection, and no GS reads or record scans during matching. An end-to-end component fixture starts from a fresh Stage 2 commit, loads its scoped plan, captures each body task's processor outputs, checks the rooted GEN manifest and matches fresh native javac class bytes across repeated borrows.

The Stage 3 authority and Downloads copy are synchronized at SHA-256 `dc24fd22d051b26ea6764892e4367793537405e43562e4b1bdc07543f5b15651`, after preserving `jvmd-stage3-bodies.before-scoped-processor-plan.md`. The Stage 2-only authority is unchanged. The clean combined gate runs with 541 Java sources frozen and the measurement property explicitly set to four workers; results follow.

The processor-aware Attribute persistence/admission path remains unfinished. Actual body processor model reads must still be part of the result identity and be verified before resolution shortcuts can serve a result; exact generated-output conservation alone is insufficient. Unsupported native processing and full diagnostic parity, current inputs from LROOT, Stage3/BROOT publication, invariant 20's storage boundary, the full two-sided body-read oracle and the remaining specification invariants stay required. Warm is untouched and the full goal remains active.


The initial clean gate passed 506 tests with all 541 Java sources unchanged during its run. Final review found a missing input guard when a processor path discovers zero processors: the per-processor capability callback never runs in that case. A new regression failed under both digests (`stage3-processor-plan-empty-red.log`), then passed after an unconditional path-hash check with host cleanup was added. `stage3-processor-plan-empty-green.log` passes all 20 plan/body-processor cases. The final clean gate `stage3-processor-plan-final-gate.log` repeats the combined verification with the updated 541-file freeze; its results and raw reports follow.


The final clean gate passed **508 tests, zero failures/errors/skips**, in 4m05s. All 541 Java files match the final pre-run digest snapshot. With four workers, the repository oracle covered **13 modules, 26 scopes and 536 sources**, 93 on-the-spot jars and 22 distinct leaf sets. Both digests produced **26/26 exact source/class k matches, no annotation differences and zero boot faults**.

| Digest | Wall ms | Header ms | Facts + proofs ms | Definer ms | Nodes |
| --- | ---: | ---: | ---: | ---: | ---: |
| SHA-256 | 4,095 | 2,692 | 1,300 | 146 | 25,055 |
| SHA3-256 | 3,353 | 1,914 | 800 | 133 | 25,065 |

Maven model/build time (25.9/25.6s) is excluded. Sampled heap above baseline was 821/921 MB with uncontrolled GC. The sequential digest runs and prior checkpoints are not controlled performance comparisons. Raw reports: [SHA-256](measurements/stage3-processor-plan-SHA-256.txt), [SHA3-256](measurements/stage3-processor-plan-SHA3-256.txt). These gates validate the scoped plans and generated-output conservation component; the processor-read/admission, Attribute, driver and oracle obligations above remain open. No PR was merged.


### Body processor observations and result identity

Body proofs now retain immutable, ordered processor observations: class, capability and detached model-query answer bytes. ProcessorHost finalizes these only after body-host closure, including late capture faults, and preserves them across repeated close calls. ACI includes the ordered class/answer inputs; capability is an admission condition, not an extra semantic identity. Proof validation checks current observations and admission before every resolution shortcut. The overload without observations rejects processor-bearing proofs, and ACI cannot be computed for missing/unsupported observations. Aggregating invocations cannot be admitted into body observations. Bodies FORMAT is now 2; LOCAL remains 6 and MACHINE remains 4.

The native regression changes a dependency annotation while keeping its T root, generated source map and original class bytes equal. Reading the changed annotation changes the processor diagnostic, body observations and ACI, and invalidates the proof even with equal resolution shortcut identities. Editing another annotation value that was returned only as an opaque handle and never queried preserves the observations and ACI. Repeated tasks verify AutoValue and Lombok observations and native output parity; codec, mutation protection, missing evidence, invocation order, unsupported capabilities and subsequent normal T-range descent are covered under both digests.

The initial gate exposed unstable AutoValue transcripts caused by extra native Object.equals calls from identity-hash-table collisions. Object's unchanged equals implementation now compares captured handles directly, without adding a model query. Overridden equality remains observable: a separate regression covers distinct, structurally equal array type handles and captured replay. The first version of that regression attempted two allocating factory queries in one phase; captured replay correctly refused the ambiguous handles, and the regression now places those calls in distinct phases. No native-model fallback was added.

`stage3-processor-observations-focused.log` passes 61 tests. `stage3-processor-observations-equality-green.log` passes 52 tests, including unprocessed Attribute, reverse records and architecture checks. The combined clean gate runs with 541 Java files frozen and the repository measurement explicitly using four workers; its final results follow.

The Stage 3 authority and Downloads copy are synchronized after preserving `jvmd-stage3-bodies.before-body-processor-observations.md`. Stage 2's authority is unchanged. This is the observation binding and validation API, not the complete processor-aware Attribute executor or a persisted model-query verifier. Query verification against the current environment, complete processor admission (including allocation-identity-sensitive behavior and unmodelled inputs), annotation-aware current model construction, scoped GEN conservation in Attribute, unsupported fresh results, the Stage3/BROOT driver and every remaining invariant stay required. The body observation argument cannot be obtained by copying the old proof. Warm remains untouched; the goal remains active, PR #61 remains separate and no merge is authorized.


The final combined gate `stage3-processor-observations-frozen-gate.log` passed **514 tests, zero failures/errors/skips**, in 4m07s. All 541 Java files match the pre-run digest snapshot. With four workers, the repository oracle covered **13 modules, 26 scopes and 536 sources**, 93 on-the-spot jars and 22 distinct leaf sets. Both digests produced **26/26 exact source/class k matches, no annotation differences and zero boot faults**.

| Digest | Wall ms | Header ms | Facts + proofs ms | Definer ms | Nodes |
| --- | ---: | ---: | ---: | ---: | ---: |
| SHA-256 | 3,946 | 2,559 | 1,332 | 119 | 25,049 |
| SHA3-256 | 3,532 | 2,022 | 911 | 147 | 25,073 |

Maven model/build time (26.1/25.2s) is excluded. Sampled heap above baseline was 841/1,161 MB with uncontrolled GC. These sequential runs and earlier checkpoints are not controlled performance comparisons. Raw reports: [SHA-256](measurements/stage3-processor-observations-SHA-256.txt), [SHA3-256](measurements/stage3-processor-observations-SHA3-256.txt). The synchronized Stage 3 authority hash is SHA-256 `51122a20768fe01fdddd01f60d42c58c15ec7d026212656058b46a81cf89ca84`. Stage 3 remains incomplete with the required work listed above; no PR was merged.


### Processor-aware Attribute and explicit fresh unsupported results

Attribute.processed now prepares options against the rooted PS invocation, checks the pool's exact options/charset/binding, validates F's source bytes, location and configuration chain, and invokes the selected processors through ProcessorHost capture. Processor code bytes are checked before class loading. Configuration is rechecked before publication. Finalized processor observations, scope admission and complete PG/GEN output conservation decide reuse. Captured generated output remains outside native source rounds and is never written to GEN/GS by Attribute. Aggregates remain excluded from body execution; their generated F rows compile separately against the committed stubs.

Supported fresh results publish CF/RS/U and return their proof/uses/ACI. Unsupported fresh results still return javac's classes and diagnostics and retain content-addressed CF blobs, but carry no reusable ACI and read/write no RS/U. Their proof explicitly rejects reuse. Bodies FORMAT 3 adds that rejection bit so scope admission can fail even for an empty invocation list, as with an unsupported aggregate. The rejection bit and capability flags control admission and are excluded from bodyInputs. LOCAL remains 6 and MACHINE remains 4. The driver must preserve fresh unsupported outputs and propagate newly discovered scope violations before serving any older results.

The initial unprocessed/plan/configuration gate passed 57 tests. The new native fixtures first passed 8 cases, then the expanded admission/proof gate passed 40 tests. `stage3-processed-attribute-focused.log` passed **70 tests, zero failures/errors/skips**, including 22 integrated cases under both digests. The fixtures compare every original/generated class byte and ordered processor diagnostics with fresh whole-module javac; cover AutoValue, Lombok rewrites and nested builders, multiple/empty outputs, unsupported declarations and two-origin output, aggregate exclusion, processor errors followed by healthy context reuse, and concurrent equal result publication. Concurrent body capture reads no GS blobs and rewrites no generated records. Source relocation, moved configuration, changed options and changed processor bytes fail before execution/publication.

A new fixture makes a generator observe a SOURCE annotation on another own source. Stage 2's native model sees it, while the current T-only own stub omits it. The body emits different captured output; conservation rejects reuse, preserves GEN and returns fresh native original class bytes without RS/U. This is evidence of the remaining processor source-model gap, not completion of that model. A complete declaration projection already exists as ProcessorElementProjection for PD/mutation checking, but it is not yet a persisted, queryable view of every own/sibling declaration for body processing. Annotation/doc-comment/enclosed-declaration behavior and all supported public model queries still need the current source-side view; neither A nor a whole-source/module identity can replace the actual reads.

The Stage 3 authority and Downloads copy are synchronized at SHA-256 `04a09bfd509f80c78b3c18d095f9bedcf4c42d2edeee942bb3ebf8e26b554efe`, after preserving `jvmd-stage3-bodies.before-processed-attribute.md`. Stage 2's authority is unchanged. The clean combined gate runs with 543 Java files frozen and the repository measurement explicitly using four workers; final results follow.

Remaining required work includes that source-side processor model and persisted query verifier, complete capability/input auditing, scope-wide handling of new violations, aggregate PDIAG integration by the driver, fresh unsupported result/root handling, invariant 20's storage boundary, Stage3/BROOT publication and all remaining invariants/full two-sided body-read oracles. Identity-sensitive processor behavior and metadata-only updates in long-lived pools also remain audit obligations. Warm is untouched, PR #61 remains a separate draft, no merge is authorized, and the full Stage 3 goal remains active.


The clean combined gate `stage3-processed-attribute-frozen-gate.log` passed **536 tests, zero failures/errors/skips**, in 4m19s. All 543 Java files match the pre-run digest snapshot. With four workers, the repository oracle covered **13 modules, 26 scopes and 538 sources**, 93 on-the-spot jars and 22 distinct leaf sets. Both digests produced **26/26 exact source/class k matches, no annotation differences and zero boot faults**.

| Digest | Wall ms | Header ms | Facts + proofs ms | Definer ms | Nodes |
| --- | ---: | ---: | ---: | ---: | ---: |
| SHA-256 | 4,217 | 2,746 | 1,339 | 132 | 25,063 |
| SHA3-256 | 3,378 | 1,882 | 822 | 133 | 25,087 |

Maven model/build time (26.1/26.3s) is excluded. Sampled heap above baseline was 955/2,017 MB with uncontrolled GC; these sequential runs and previous checkpoints are not controlled performance comparisons. Raw reports: [SHA-256](measurements/stage3-processed-attribute-SHA-256.txt), [SHA3-256](measurements/stage3-processed-attribute-SHA3-256.txt). The authority copies remain byte-identical at the hash above. No PR was merged, and the required source-model/query/driver and broader invariant work remains open.


### Persisted source declaration views

LOCAL 7 adds PM per module/scope, a ContentTree of exact named type keys to detached source declaration projections. Every scope publishes a root, including empty/non-processor scopes. Original, nested and generated source types are captured while Stage 2's completed model is live; no body attribution or source reread is added. Values retain source path, declaration structure/order, type-use and SOURCE annotations, bounds, receiver/parameter/record data, defaults/constants and doc comments. Exact nested keys avoid interpreting literal dollar signs as nesting. A failed extraction becomes an explicit unavailable entry plus a file fault. The reader verifies LROOT membership and record digests before reaching current entries; stale raw records cannot supply current declarations. PM never becomes a coarse semantic input to ACI or GEN. Its extraction/build time is included in the existing facts timing.

The decoder exposed an old projection ambiguity: javac's record-component symbol also implements VariableElement. Dispatch now handles RecordComponentElement explicitly; its element key is also distinct from its backing field, with a two-digest regression added after focused testing. Native annotation-value map order is preserved, and Java strings/doc comments use UTF-16 code units so an unpaired surrogate cannot collide with UTF-8 replacement text. Decoder lengths are checked before allocation. The first model gate produced no test result after this ambiguity caused malformed decoding and a Surefire heap error; its BUILD SUCCESS banner was not accepted as evidence. After correction, all 12 initial model cases passed. The expanded focused gate passed 85 tests, including 16 declaration cases under both digests, source removal after capture, body-only stability, metadata-only differences with equal T/A, module/worker order, stale/corrupt references, faults, nested annotation defaults, exact Java strings and malformed encoding. Existing integrated AutoValue/Lombok/generated-source cases also check their committed PM declarations.

This is required data preparation, not a completed processor adapter. Body processing still needs current source-backed query answers, package/module views and full public-model origin/implicit-state coverage, persisted query verification, and scope-wide unsupported handling. The earlier SOURCE-annotation gap fixture remains a reuse rejection until that adapter is wired in. Driver/BROOT, aggregate diagnostics, cold storage boundaries, whole-project class/diagnostic and two-sided read oracles, the 2,000-file Lombok case and all other outstanding Stage 3 invariants remain required. Warm is untouched; no PR merge is authorized. The clean combined gate will run with all Java sources frozen; its terminal results follow.

Read-only follow-up inspection reproduced a separate existing resolution-codec defect in `stage3-res-string-audit.log`: Res.Field(Constant(tag=8, text=U+D800)) round-trips to U+003F because Res still uses UTF-8 string encoding. Ann.Val.Str uses the same lossy path. This is not fixed by PM and remains required work: add native source/class/stub/body regressions, preserve exact Java strings in the shared resolution/annotation codecs, and update their MACHINE/parser/LOCAL format boundaries as necessary. The current repository equality oracle does not prove that edge case. Resolve it before treating general native class-byte parity as complete.


The clean combined gate `stage3-source-declarations-frozen-gate.log` passed **554 tests, zero failures/errors/skips**, in 4m17s. All **547 Java files** match the frozen pre-run snapshot after terminal completion. With four workers, the repository oracle covered **13 modules, 26 scopes and 542 sources**, 93 on-the-spot jars and 22 distinct leaf sets. Both digests produced **26/26 exact source/class k matches, no annotation differences and zero boot faults**.

| Digest | Wall ms | Header ms | Facts + proofs ms | Definer ms | Nodes |
| --- | ---: | ---: | ---: | ---: | ---: |
| SHA-256 | 4,307 | 2,710 | 1,600 | 134 | 25,158 |
| SHA3-256 | 3,465 | 1,784 | 1,100 | 126 | 25,194 |

The facts time now includes source declaration extraction and PM tree construction. Maven model/build time (25.9/24.8s) is excluded. Sampled heap above baseline was 820/1,819 MB with uncontrolled GC; these sequential measurements are not a performance comparison. Raw reports: [SHA-256](measurements/stage3-source-declarations-SHA-256.txt), [SHA3-256](measurements/stage3-source-declarations-SHA3-256.txt). The Stage 3 repository and Downloads authorities are byte-identical at SHA-256 `c83d1d4d9538694088bb7c5d55dccc6506b1f920e4061aaae664f284a2899a35`, after preserving `jvmd-stage3-bodies.before-source-declarations.md`. Stage 2's authority remains unchanged. The processor adapter/query/driver work and reproduced resolution-string codec defect remain open; the full goal stays active.


### Exact Java String values in facts, stubs and diagnostics

The resolution-string defect from the previous checkpoint is fixed. Codec now has a bounded UTF-16 code-unit encoding, separate from structural UTF-8 strings. Res ConstantValue strings and Ann string values use it, including nested annotations/arrays and annotation defaults. Native and header facts retain exact values and stubs emit them unchanged. PM reuses the shared encoding instead of its private duplicate; its declaration bytes remain unchanged. ResultRecord and processor PDIAG messages also retain exact Java text.

The representation is MACHINE layout 4 / parser 5, combined LOCAL 8 and bodies 4. Old MACHINE parser-2/3/4 generations remain in separate directories; no legacy fact decoding or in-place migration is added. LOCAL 8 identifies the changed PDIAG codec; bodies 4 identifies the changed RS message codec. The Stage 2-only foundation remains LOCAL 4 and now names parser 5, while the combined processor extensions use LOCAL 8. Both repository specifications and their Downloads authorities were backed up and synchronized.

`stage3-java-strings-red.log` failed 11 of 20 tests before the change: own-stub body bytes, binary stub constants/defaults, shared fact text and both persisted diagnostic paths lost unpaired surrogates. The corrected and expanded `stage3-java-strings-focused.log` passed **187 tests**, zero failures/errors/skips. Tests preserve all 65,536 code units, reject malformed lengths, compare native raw constant/default values and client class bytes against real dependencies and stubs, match source/class facts with nested/array/type-use annotation text, and retain native processor diagnostics across PDIAG and RS storage. A consumed string constant changes its body proof while an unread constant preserves it; metadata-only annotation strings move A/a without moving T. NUL, supplementary pairs, isolated high/low surrogates and ordinary Unicode are covered.

This closes the reproduced string-codec defect, not the remaining Stage 3 scope. Processor source-query adaptation and package/module/origin views, persisted query verification, scope-wide unsupported handling, aggregate diagnostics, the cold storage boundary, driver/BROOT publication and the remaining full-scope invariant/oracle work stay required. Warm is untouched. The clean combined gate will verify generation isolation and both repository projections with frozen Java sources; terminal results follow.


The clean combined gate `stage3-java-strings-frozen-gate.log` passed **568 tests, zero failures/errors/skips**, in 4m18s. All **547 Java files** match the pre-run freeze after terminal completion. The RocksDB test verifies parser-2/3/4 generations remain untouched and cannot skip the parser-5 cold boot. With four workers, the repository oracle covered **13 modules, 26 scopes and 542 sources**, 93 on-the-spot jars and 22 distinct leaf sets. Both digests produced **26/26 exact source/class k matches, no annotation differences and zero boot faults**.

| Digest | Wall ms | Header ms | Facts + proofs ms | Definer ms | Nodes |
| --- | ---: | ---: | ---: | ---: | ---: |
| SHA-256 | 4,165 | 2,498 | 1,584 | 127 | 25,202 |
| SHA3-256 | 3,642 | 1,880 | 1,218 | 126 | 25,189 |

Maven model/build time (25.5/25.2s) is excluded. Sampled heap above baseline was 972/1,727 MB with uncontrolled GC; sequential digest runs are not a performance comparison. Raw reports: [SHA-256](measurements/stage3-java-strings-SHA-256.txt), [SHA3-256](measurements/stage3-java-strings-SHA3-256.txt).

Stage 2 authority SHA-256 is `0eee5d39b6cbca93c21175adf8debac903644873f948f61b8ce499f0fc62fbaa`; Stage 3 is `296629a9e65ba2ea939a80c5b838743a878f4e225ed1c3668b7b797185f0287c`. Each repository file exactly matches its Downloads copy, with distinct `.before-java-strings.md` backups. The reproduced constant/annotation/diagnostic text loss is now closed. The source-model query adapter, full processor admission/verification and driver/oracle obligations remain open; the full goal stays active and no PR was merged.


### Source-backed type annotation and doc-comment queries

Body processor queries now reach type declaration annotations and doc comments through the current committed source view. ProcessorSources.bind reads rooted SL/RT/PM records and selects the first defining origin in own-first route order. Source scopes are not collapsed by equal T hashes, and a preceding jar prevents source metadata from a later sibling from leaking into the binary view. ProcessorPlan exposes this binding to each fresh body host; it is not an ACI input.

ProcessorSourceQueries is a narrowly admitted compiler adapter. It constructs detached native annotation compounds and values, retaining explicit/effective value maps, nested annotation defaults, native formatting, visitors and annotation proxies. It supports type getAnnotationMirrors/getAnnotation/getAnnotationsByType, Elements.getAllAnnotationMirrors and type getDocComment. Explicit source types keep their live annotations and comments; inherited queries can still traverse source-backed parents. Exact binary annotation names, including nested classes and literal dollar signs, resolve in the native package's module. No declaration metadata is installed in shared symbols. ProcessorReads records answers from this view using the existing actual-query protocol and retains captured-only replay.

The previous SOURCE-annotation output-mismatch fixture now succeeds with matching generated output and no GS reads or GEN rewrites. A separate deliberate output-drift fixture preserves the rejection guard; its injected property read is not admission evidence for external-input processors. Additional fixtures compare all original/generated class bytes and ordered diagnostics with fresh whole-module javac for type annotations/defaults/nested values, inherited/repeatable SOURCE annotations and mirrored class values. The first annotation visitor fixture accidentally asked for implementation class names; it was corrected to use the public visitor contracts. Allocation/class-identity-sensitive processor admission remains an explicit obligation.

The focused gate passed 75 tests with zero failures/errors/skips. A stronger mutation fixture now reuses one compiler context across committed metadata changes and deletes the metadata source before body attribution: unread annotation/body edits must preserve ACI, whereas a consumed doc-comment edit changes ACI. The full clean gate will hold Java sources fixed and run both repository projections; terminal evidence follows.

This checkpoint adapts type declaration metadata. Member annotations and doc comments, type-use/generic/receiver/parameter views, declaration ordering and parameter names, package/module/origin views, persisted query verification, complete processor admission, cold storage boundaries, the Stage3/BROOT driver and the remaining invariants are still required. The full goal stays active. Warm is untouched; no PR is merged. The Stage 3 authority and Downloads copy are synchronized, with the distinct before-type-source-queries backup; Stage 2 authority is unchanged.


The first frozen gate passed 578 tests and both 26-scope repository projections, with all 548 Java files unchanged. Review then identified a nested-default edge: using only an effective annotation value expands `@Nested` into `@Nested(value="default")`, changing its explicit map and native rendering. `stage3-source-queries-defaults-red.log` reproduced two diagnostic failures. The adapter now gets the exact explicit default from the annotation member's saved declaration, or the native default for a binary annotation type. Nested default arrays are covered as well. `stage3-source-queries-defaults-green.log` passes 62 tests. The hot-context mutation test also passes under both digests after the metadata source is deleted. Java sources are frozen again for the final clean gate.


The final clean gate `stage3-source-queries-frozen-gate.log` passed **578 tests, zero failures/errors/skips**, in 4m21s. The complete set of **548 Java files** and every source digest match the pre-run freeze. With four workers, the repository oracle covered **13 modules, 26 scopes and 543 sources**, 93 on-the-spot jars and 22 distinct leaf sets. Both digests produced **26/26 exact source/class k matches, no annotation differences and zero boot faults**.

| Digest | Wall ms | Header ms | Facts + proofs ms | Definer ms | Nodes |
| --- | ---: | ---: | ---: | ---: | ---: |
| SHA-256 | 4,205 | 2,688 | 1,609 | 127 | 25,205 |
| SHA3-256 | 3,503 | 1,811 | 1,127 | 123 | 25,203 |

Maven model/build time (26.2/25.3s) is excluded. Sampled heap above baseline was 904/1,561 MB with uncontrolled GC; these sequential runs are not a performance comparison. Raw reports: [SHA-256](measurements/stage3-source-queries-SHA-256.txt), [SHA3-256](measurements/stage3-source-queries-SHA3-256.txt).

Stage 3 authority SHA-256 is `d1bfa956f020830e983cb34d2b00f2ca5812076da40c69de78bba187052d4c2b`; unchanged Stage 2 authority is `0eee5d39b6cbca93c21175adf8debac903644873f948f61b8ce499f0fc62fbaa`. Repository authorities exactly match their Downloads copies. The type declaration annotation/doc-comment regression is closed; the broader member/type-use, full admission/verification, driver and oracle obligations listed above remain open. The goal stays active and no PR was merged.


### Source-backed member declarations and private handles

The body-task source adapter now matches native members to saved declarations by exact element key, preserving overload identity, declaration order, source parameter names, annotations, doc comments and deprecation. Each detached declaration carries its own immutable key; record components remain distinct from their backing fields. LOCAL is now 9, with MACHINE layout 4/parser 5 and bodies 4 unchanged. Rocks-backed skip tests reject every prior LOCAL layout (1 through 8) even when the remaining runtime/locale format fields match.

Private members absent from T receive detached native handles with their original class owner and reconstructed generic structural types, including enclosing type variables, arrays, wildcards, intersection bounds and throws. These handles are never inserted into cached compiler member scopes. getAllMembers builds a temporary scope, preserves javac ordering and uses native inheritance/override rules. Its regression exposed the distinction between record components in getEnclosedElements and backing fields in the actual member scope; the adapter now preserves that distinction. Source annotation defaults also retain the native handle identity shared with getDefaultValue. Doc-comment-only deprecation is saved explicitly rather than inferred from @Deprecated.

The new native oracle scans overloaded methods, private fields/methods, generic declarations, constructors, nested dollar names, records, enums, annotation members, inherited members and overrides. It compares exact class bytes and ordered diagnostics under both digests across repeated pool borrows. A second regression deletes the metadata source after three cold boots and reuses one compiler context: unread private signature, annotation and body edits leave the proof/result unchanged, while a consumed parameter-name edit changes ACI and the diagnostic. `stage3-member-queries-focused-final.log` passes **108 tests, zero failures/errors/skips**. The full combined clean gate follows with all Java inputs frozen.

Stage 3 authority SHA-256 is `7ecc1cf89518efbaec96b6fee5bae4846229d2d42b84a276d2a59360b7ede127`, exactly matching Downloads after preserving the distinct before-member-source-queries backup. The Stage 2-only authority remains unchanged and synchronized. Type-use annotation views, package/module/origin and implicit-state observations, complete processor admission, persisted query verification, cold storage boundaries, the Stage3/BROOT driver and the remaining full invariants stay open. Warm is untouched; the full goal remains active and no PR is merged.


The final clean gate `stage3-member-queries-frozen-gate.log` passed **582 tests, zero failures/errors/skips**, in 4m32s. The complete set of **549 Java files** and every source digest match the pre-run freeze. With four workers, the repository oracle covered **13 modules, 26 scopes and 544 sources**, 93 on-the-spot jars and 22 distinct leaf sets. Both digests produced **26/26 exact source/class k matches, no annotation differences and zero boot faults**.

| Digest | Wall ms | Header ms | Facts + proofs ms | Definer ms | Nodes |
| --- | ---: | ---: | ---: | ---: | ---: |
| SHA-256 | 4,721 | 2,964 | 1,819 | 137 | 25,202 |
| SHA3-256 | 3,507 | 1,804 | 1,126 | 124 | 25,193 |

Maven model/build time (29.9/25.0s) is excluded. Sampled heap above baseline was 961/1,146 MB with uncontrolled GC; these sequential runs are not a performance comparison. Raw reports: [SHA-256](measurements/stage3-member-queries-SHA-256.txt), [SHA3-256](measurements/stage3-member-queries-SHA3-256.txt). The source member declaration regression is closed; the broader type-use, admission/verification, driver and oracle obligations remain open. The goal stays active and no PR was merged.
