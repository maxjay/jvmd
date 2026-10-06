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


### PR #61 review closeout, 2026-10-06

The review at `66d27412dcb7ce559acda2e3f0a6e3a1352aad9b` exposed a real route-only missed-consumer bug. All seven findings are addressed in this follow-up: distinct semantic delta domains and exact provider Diff; current raw header keys atomically published with LROOT; lazy persisted DF multimap/projection roots; exact DC cache reuse; conflict apply from ordered-route ancestry; and persistent leaf-set trees. LOCAL is now 5. The earlier notes accepting stale raw prefix scans and temporary-only fold state are superseded.

The controlled regressions also found and fixed cross-worker node publication for Stage 2 without changing Stage 1 streaming. The final gate passed 292 tests in 3m00s, with all 501 Java sources frozen, exact source/class k in 26/26 scopes and no annotation differences/faults under both digests. Fresh repeated boots hit 22 DF and 22 DC records with zero fold O opens, leaf-set comparisons, conflict builds/applies/O opens/touched entries/node emissions. Reverse churn leaves one current prefix hit and zero LOCAL reads after 6,000 retired consumers in memory and Rocks.

See [the review closeout](stage2-pr61-review-closeout.md) and `docs/measurements/stage2-review61-*` for the contracts, command and measured limits. The Downloads Stage 2 document received the same scheduler changes while preserving its later parser-5 Java String amendment. The later Stage 3 branch's module descriptor, diagnostic and processor work remains unfinished and must retain its own subsequent format versions when these fixes are integrated. PR #61 merge still depends on hosted checks for the pushed fix commit.

