# Stage 3 implementation and evidence

Specification: [jvmd-stage3-bodies.md](jvmd-stage3-bodies.md), supplied by Max on 2026-10-05.
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

- The independent whole-module javac oracle is explicitly required by invariant 1; the blanket ban on multi-source test tasks in §10 conflicts with that requirement. Production Attribute remains strictly one compilation unit; the independent oracle must use the required whole-module task.
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
- Appendix F is still incomplete: audit arbitrary Elements/Types queries, raw compiler access and standard Trees.instance(wrapper) compatibility; cover processors that produce no output on their first run and isolating derivations with zero outputs; retain processor-emitted diagnostics; finish the input/sum audit and measurements. A declared incremental capability alone is not evidence for unrecorded reads outside the source projection/header proof. No general reuse-soundness claim or PR B completion is made yet. The new F code is uncommitted and is not in PR #59; all Stage 3 body/result/driver work remains pending.
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
