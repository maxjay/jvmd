# LIVE and the stage 3 core: design note

Oct 15, 2026. Draft for review. No stage 3 or LIVE code is written until this is agreed.

This note sets what demo steps 2–5 build ([demo plan](demo-plan.md) §1) and how that fits the [stage 3 spec](jvmd-stage3-bodies.md) and the [amendments](jvmd-stage1-2-amendments.md). The stage 3 spec is the engine, and its Appendix D is the outline of LIVE. Where the two documents disagree, the amendments are later, and §10 lists every such point as a decision. This note adds what the demo on hawkBit showed: builds that rewrite classes after javac, and a running server to hot swap into.

## 1. Where develop stands

- **LOCAL.** MACHINE and LOCAL cold-boot in the daemon (#71).
  - LOCAL commits under `LROOT`: per-file rows with header facts and header proofs, the header reverse index `X|H5`, routes and definer indexes.
  - Stubs and leaf nodes are content-addressed outside that tree and reached through `SL|`.
  - A reopen with nothing changed skips the boot (#69): stamps, with a content hash where a stamp cannot vouch. **Any change cold-boots the whole LOCAL again**, because nothing updates LOCAL incrementally.
- **The header side of the change loop exists but has no caller.** `ReverseIndex.candidates` takes the `T`, `N`, `O` and `DC` diffs and returns the files whose header proofs to recheck. Only tests call it.
- **Develop already has `C|` and `RS|` keys, keyed by `(κ, leafSetExt)`** (`LocalStore`). The stage 3 spec forbids that key (§10 Do-not 7), so the first stage 3 PR replaces them.
- **Nothing compiles bodies against LOCAL.** `run.start` and hot swap run javac against the run's class path, outside the index. On develop they still write into `target/classes`. #80 (open) moves them to jvmd's own folders.
- **The missing piece #80 exposed.** A build can rewrite classes after javac (EclipseLink weaving in hawkBit's `repository-jpa`), and jvmd can only tell that per module, from the POM. So it refuses every hot swap in that module.
  - On hawkBit the weaver changes 25 of the module's 175 classes, and all 25 are entity, mapped-superclass or embeddable types (woven compared with unwoven output).
  - `JpaTargetManagement` is not one of them, and is still refused.

## 2. Ownership

| Layer | Owns | Persisted |
|---|---|---|
| MACHINE (stage 1) | Facts of jars and the JDK | Yes, shared per machine |
| LOCAL (stage 2) | Per project: header facts, leaves, stubs, routes, definer indexes, header proofs, `X\|H5` | Yes, under `LROOT` |
| Bodies (stage 3) | Per file: `Attribute(f)`, the proof `C\|`, the result `RS\|ACI`, class files `CF\|`, the body reverse index `X\|G` and `X\|D`, output trees `OUT\|` | Yes, under `BROOT` |
| LIVE | What changed and what to redo: watching inputs, the change loop, per-class build-output verdicts, the run view, hot swap | Verdicts and the run view are persisted per machine, outside the rooted trees, like stage 3's `MAT\|`. The rest is in memory. |

The rule of stage 3 §8 holds. If it needs a body attributed, it belongs to stage 3. If it needs to know whether something changed, it belongs to LIVE.

## 3. What LIVE watches

| Input | Changed by | Detection | Handled by |
|---|---|---|---|
| Source files, including added and deleted ones | Edits, branch switches | A watcher on the source roots | The loop (§4) |
| Processor configuration (`lombok.config`; hawkBit has one at the root) | Edits | The same watcher | The loop: `configProof(f)` for Lombok scopes |
| Build output (`target/classes`) | Maven, an IDE build | A watcher on the class output directories | Verdicts and the run view (§6) |
| Running apps | `run.start`, hot swap, exit | The JDI session | Hot swap (§7) |
| The project model (POMs) | Edits | Re-resolution | A cold LOCAL boot, for now |
| Jars changed in place (SNAPSHOTs), the JDK | Builds, upgrades | Stamps, at start | Not compared yet (demo plan A3). A cold boot, for now. |
| Unsaved buffers | The editor | | Deferred (LSP) |

Detection is per event. After the daemon was down there are no events, so the start scans stamps, as #69 does: one `stat` per file, and a read only where a stamp moved.

## 4. The loop

```
on a batch of events (debounced):
  edited = source rows added, deleted, or whose κ changed     // content check, as #69

  // 1. Headers, to a fixpoint, in module order
  recheck = edited
  while recheck is not empty:
      for f in recheck: Header(f)          // one javac task over f; the view hides the owners of f's committed row (§4.10 compilerView)
      apply the new facts to each touched leaf (T, N, O, A); regenerate the changed stubs
      delta   = Diff(old leaf, new leaf), and for dependents the route deltas
      recheck = files whose header proof fails, among ReverseIndex.candidates(delta)

  // 2. Bodies (stubs are current by now)
  for f in edited: Attribute(f)            // Valid never reads κ, so an edited file is always attributed
  for f in X|G and X|D prefix reads over every delta (own module and every dependent), minus edited:
      if not Valid(C|f) against the new roots: Attribute(f)

  // 3. Publish
  apply every record; write LROOT and BROOT together

  // 4. Effects
  Diff(OUT| old, new) → the run view (§6) → hot swap into running VMs (§7)
```

The cost is the size of the semantic change times its fan-out, never the size of the project.

- **Step 2 (body edit).** One `Header(f)` gives equal facts, so there is no delta. Then one `Attribute(f)`.
- **Step 3 (add a method to a widely used `K`).** `T` gains `(K, METHOD, bar)`. The candidates are:
  - the files that recorded a range over `bar`;
  - the files that recorded `K`'s whole method set (its subclasses);
  - the files that hold only a per-type entry for `K` (§5.3).
- **Step 4 (library edit).** The same, with route deltas crossing modules. Each dependent's javac pool is reopened once.
- **Step 5 (warm restart).** The same loop, with the stamp scan as the event source (stage 3 Appendix D, "Warm boot").

Three points in this loop:
- **`Header(f)` is new.** Stage 2 compiles headers once per module scope (`HeaderCompiler`). LIVE needs the same compile over one unit. Stage 2's header proofs and `X|H5` are already per file, so only the compile changes grain.
  - The view hides the owners listed in `f`'s committed row, as amendments §4.10's `compilerView` says. Otherwise a type deleted from `f` still resolves from its old stub.
  - Its test: per-file facts equal per-scope facts on every fixture and on hawkBit.
- **The route delta is a subset of §4.11's frontier.** `ReverseIndex.Delta` covers `T`, `N`, `O` and `DC`. The META, CLASS_INPUT and PACKAGE families and the fixed inputs (options, processor path, platform) are not built yet. They are needed before step 4, and are built with it (§9).
- **Publishing needs §4.9.** LIVE republishes `LROOT` after every batch. Today the file rows `F|project|path` and routes `RT|project|module|scope` are keyed by name and overwritten in place, so an older root cannot be read back. Demo plan A7 (where jars indexed on the spot are rooted) is also open. Both must be settled before LIVE publishes anything.

## 5. The stage 3 core

### 5.1 Built, as the spec says

- `Attribute(f)` as in 2.2 and C.1: one unit, the own stubs first, then the route; `-implicit:none`; `Locale.ROOT`; an explicit encoding; output in memory.
- The pool (C.2), with its key extended by the own leaf's `a`. Since #68, stubs carry annotations and are keyed `S|Digest(R || k || a)`. A key without `a` keeps stale stubs loaded after an annotation-only edit.
- The collector (5.2, C.4, C.6), from javac's symbols only.
- `Valid` by descent (5.3).
- The records `C|`, `RS|ACI`, `CF|`, `X|G`, `X|D`, `OUT|` and `BROOT` (2.4, appendix B). They replace develop's `(κ, leafSetExt)` keys.
- The cold driver, after LOCAL, in the daemon (section 4 of the spec).
- Materialise, only into jvmd's folders (§10 Do-not 10, the same rule as #80).

### 5.2 Processors follow amendments §4.8, not stage 3 §3.5

hawkBit has three processors, and each declares itself in its jar (checked):

| Processor | Where | Declares | Under §4.8 V1 |
|---|---|---|---|
| Lombok (2 classes) | All 40 modules | isolating | Supported: runs inside `Attribute(f)`, with its processor and config inputs bound |
| Hibernate `HibernateProcessor` (jpamodelgen) | `repository-jpa`, `-jpa-eclipselink`, `-jpa-hibernate` | isolating | A fresh native javac run of the scope with its processors regenerates the outputs, which are not reusable stage 3 results |
| Spring Boot `ConfigurationMetadataAnnotationProcessor` | All 40 modules | aggregating | The same. It writes only `META-INF/spring-configuration-metadata.json`, which nothing on hawkBit's runtime class path reads. |

§4.8 says that "non-Lombok processor outputs are not admitted as reusable Stage 3 body results" and that "no generic processor replay/proxy layer is built". So the core builds none of stage 3's wrapper, `GEN|`, `PD|`, `RES|` or `PROC|`.

How I read §4.8 V1 for LIVE:
- **When the rerun happens.** A batch that touches a scope with such a processor reruns that scope's native processing.
- **What is not reused.** The generated files' results.
- **What is still reused.** Hand-written bodies in the scope are ordinary stage 3 results, with proofs over the generated types' facts.

Two things that reading needs:
- **Generated sources must be kept.** Today their rows have no `κ`, and their bytes are deleted with the boot's scratch directory, so `Attribute` cannot compile `JpaTarget_` yet.
- **jpamodelgen's reads beyond the processing API are only covered by the rerun.** It reads `META-INF/persistence.xml` and `orm.xml` through `Filer.getResource`.

### 5.3 The proof standard

This is the demo plan's coarser standard (§5): completeness is a gate, minimality is measured.

- **Entries are at the spec's grain wherever C.6 has a row and the collector has a test for it:**
  - member groups for field lookups, method lookups and constructors;
  - type headers for closures;
  - whole method sets for the supertypes of declared types.
- **Any other read of a type becomes a per-type entry:** the type's whole `T` range. That is complete for the type, and any API change to the type re-attributes the file.
- **Absences have no per-type form.** `N` is keyed by name first, so a type's member types are not one range, and package heads, `Q.Id` branches and static on-demand imports name no single type. Absences are always recorded at C.6 grain. A construct whose absences the collector cannot record makes the file non-reusable.
- **The gate:**
  - the read-set oracle (invariant 30) on the fixtures and on hawkBit;
  - mutation completeness (invariants 3, 5, 6, 8, 9 and 10);
  - the must-fail halves of invariants 4 and 7.
- **Measured, reported in each PR, not gated:**
  - the must-not-fail halves of invariants 4 and 7;
  - invariant 11;
  - the number of per-type entries.

## 6. Build output and the run view

This replaces the interim POM rule of #80.

For each module scope, LIVE keeps `BO|`, the build output tree. It maps each internal name to the digest of its class file in `target/classes`, and is updated from watcher events.

### 6.1 Verdicts

A verdict is per class: `(ACI, build digest, plain | rewritten)`.

**When a verdict is recorded.** The build's class is tied to the current `ACI` of the file that declares it when the class file is newer than:
- the file itself, and
- every input its proof names: the source files and jars that define the types in its entries.

This is Maven's staleness rule, made per class and extended across modules. A class file is not current just because its own source did not change. Another file's constant, overload or supertype may have changed, and that changes the `ACI`. The rule has Maven's own race: an edit made while Maven is compiling.

**The verdict's value.** `plain` if the build's bytes equal stage 3's `CF` for that `ACI`, `rewritten` if they differ. Byte equality is the safe test: a javac or options mismatch on the Maven side gives a false `rewritten`, which refuses more often but never runs the wrong code.

**How the run uses it:**

| State of the build's class | The run uses |
|---|---|
| Verdict for the current `ACI`, digest unchanged | The build's bytes, `plain` or `rewritten` |
| Stale, `plain`, and `plain` carries over (below) | The `CF` |
| Stale, `rewritten` | Refused. The message names the module's post-compile goals. With none, it says the build's bytes differ from javac's. |
| No verdict, no post-compile goals | The `CF` (as #80) |
| No verdict, post-compile goals | Refused (as #80) |

**Carrying `plain` over an edit.** A verdict for an old `ACI` says nothing about whether the rewriter would rewrite the new code. Adding `@Entity` makes a class woven. Hibernate's extended enhancement and AspectJ rewrite call sites inside bodies. The proposal (decision 3):
- **Known rewriters that select classes by their declarations** (EclipseLink and OpenJPA weaving, Hibernate enhancement without extended enhancement) keep `plain` over an edit that leaves the class's own `T` and `A` unchanged, which covers every body edit. On hawkBit this is what the woven-against-unwoven comparison shows.
- **Any other post-compile goal** never carries `plain` over. An edited class in such a module waits for the build, as all of them do with #80.

### 6.2 The run view

The run view is one jvmd folder per module scope. It holds every class of the module, chosen by the table above, and it is written by `Diff` of a `RUN|` tree, so the cost is O(changed). Resources and classes jvmd does not compile come from the build.

A full view, rather than #80's overlay in front of `target/classes`, is needed for deleted classes. With an overlay, a class removed from the source stays visible from `target/classes` until the build runs again, and Spring's component scan would still load it.

## 7. Hot swap

LIVE decides; JDI applies.

- **Candidates:** after the loop, the classes in the run view that changed and that a running VM has loaded.
- **Legal when the member schema is unchanged** against the loaded class (stage 3 Appendix D), for every class of the file. The schema covers:
  - names, descriptors and access flags of all members, private ones included;
  - the superclass and interfaces;
  - `NestHost`, `NestMembers`, `Record` and `PermittedSubclasses`.

  Equal `oSum`s are not enough. Inserting an anonymous class renumbers `Foo$1` while every `oSum` stays equal.
- **A class the run view takes from the build** is swapped with the build's bytes. A stale `rewritten` class is refused, as in §6.1. On hawkBit, an edit to `JpaTargetManagement` swaps. An edit to the entity `JpaTarget` asks for `mvn process-classes` and a restart.
- **`HotSwap` stops calling javac itself.** The bytes are stage 3's.

## 8. Relation to `feat/jvmd-stage-3`

That branch is 76 commits (69 not merges) and about 34,000 lines beyond develop. It ends at parser 6 and LOCAL 23, values develop deliberately skips. It aims at the full proof standard, with a javac reader agent (`NativeReaderAgent`) and admission gates. Its own progress document reports invariant 30 as incomplete, with 21,886 uncovered observations on its strict oracle.

Proposal: leave it unmerged and build the core fresh on develop. Read it for:
- `Pool`, `Attribute` and `Output`;
- the oracle harness: `ReadOracleAgent` with its javaagent jar, and the tap `ReadOracleTrace`. It may port as the harness for invariant 30.

## 9. PR order

1. **Stage 3 cold.** It needs decisions 1, 2 and 5 first.
   - Builds: the records (replacing develop's `C|` and `RS|` keys), `Attribute`, the pool, the collector, `Valid`, the cold driver after LOCAL in the daemon, and materialise.
   - Tests: invariants 1, 2, 3, 5, 6, 8, 9, 10, 16, 17, 19 and 23; the must-fail halves of 4 and 7; and the oracle (30) on the fixtures. Invariants 15 and 29 are restated for §4.8 V1.
   - Measured: cold numbers on hawkBit.
2. **Incremental LOCAL.** §4.9 rooted records with A7 decided, `Header(f)` with the `compilerView` exclusion, leaf `apply`, and publishing.
3. **LIVE within one module (demo steps 2 and 3).** The watcher, the loop, kept generated sources, the run view from `OUT|`, and hot swap through LIVE. This is the first production consumer of the §4.10 answers, as demo plan §5 asks.
4. **LIVE across modules (demo step 4).** The full §4.11 frontier: META, CLASS_INPUT, PACKAGE and fixed inputs.
5. **Build output.** `BO|`, verdicts, and the run view's choice per class. The POM rule stays only as the fallback when there is no verdict.
6. **Warm restart from the stamp scan (demo step 5).**

## 10. Decisions for you

1. **Statuses in proof entries.** The spec stores proof identities as sums. An absent owner already fails `Valid` (5.3), but there is no way to say `SELECTED_ERROR`, `INACCESSIBLE` or `UNAVAILABLE`, which amendments §4.10 require. §4.11 makes `UNKNOWN` mean re-attribute. Proposal: each entry stores `(outcome, sum)`, and the descent shortcuts stay.
2. **Annotations (A10).**
   - What bodies don't need: an annotation type's `@Target`, `@Retention` and `@Repeatable` are already in `res`, so a type-header entry covers what javac reads for a body.
   - What does read `A`: processors. jpamodelgen reads `@MappedSuperclass` and the like from sibling stubs. That affects header facts in its scopes, not only bodies, so the A10 gap is in header proofs too.
   - Proposal: in a scope that runs a non-Lombok processor, the route binds each sibling's `a` as well as its `k` (A10's second option), so an annotation-only sibling edit reruns that scope's processing. Other scopes stay at `k`.
3. **Carrying `plain` over an edit (§6.1).** Proposal: only for known rewriters that select classes by their declarations, and only over edits that leave the class's `T` and `A` unchanged.
4. **The run view.** A full view per module (§6.2), or #80's overlay in front of `target/classes`? The overlay is cheaper to build, but it keeps deleted classes visible. Proposal: the full view.
5. **The own stubs while compiling `f`.** Stage 3 C.3 keeps `f`'s own stubs on the class path, which keeps the pool's class path constant. Amendments §4.10's `compilerView` hides them. Proposal:
   - `Header(f)` uses the `compilerView` exclusion, because it compiles new source against stubs that are still old.
   - `Attribute(f)` keeps C.3, because by then the stubs are current.
6. **The reading of §4.8 V1 in §5.2.** Generated outputs are rerun and never reused, while hand-written bodies in those scopes are reused. Is that the intent?
7. **The old branch.** Leave it unmerged and build fresh (§8).
