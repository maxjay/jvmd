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

Appendix A landed on main in PR #59, merge commit `20e825a3`, on 2026-10-05. The corrections below are in [PR #60](https://github.com/maxjay/jvmd/pull/60), awaiting review and CI. Appendix F, body attribution, the two-sided javac read-set oracle and all Stage 3 result/driver invariants remain outstanding. `warm/` still contains only its original `package-info.java`.


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

### CI compiler-boundary correction

The hosted run [37380049291](https://github.com/maxjay/jvmd/actions/runs/37380049291) compiled/assembled the runtime and passed the Phase 3 index gate, Rocks, compiler, processing and other executed feature gates. Its overall result failed: the older architecture guards allowed javac internals only in the analyzer and had not been updated for Stage 2's HeaderCompiler or Appendix A's Symbol/TypeCompound extraction in SourceFacts. The source and bytecode guards now share exact class grants for those two adapters (including their nested classes); no index/boot package-wide permission is granted. The boot descriptor expectation now includes its existing compiler dependencies. Positive/negative boundary assertions are included.

That early failure skipped the step creating `runtime-downloads`; later always-run JBR/HotswapAgent steps then failed writing their downloads. Each now creates its own destination directory. **36 tests passed**, no failures/errors/skips (`review-ci-adapters.log`, 21s), including both architecture guards, source annotation projection and header absences. No production code changed. A new hosted run is required before claiming full CI success.


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
