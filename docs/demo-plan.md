# jvmd demo plan: the goal loop on a real project

Oct 15, 2026. This plan sets the order in which MACHINE, LOCAL and the [Stage 1/2 amendments](jvmd-stage1-2-amendments.md) are implemented. Work is driven by one demo on one real project. A piece of the amendments is built when the demo needs it, and not before.

## 1. The goal loop

The demo has five steps, run on a Maven monorepo that looks like the work code jvmd is meant for:

1. **Cold.** Index `~/.m2`, boot the project, compile it, and run the server. This happens once.
2. **Body edit.** Change one method body. Exactly one file is recompiled and hot-swapped into the running server.
3. **API edit.** Add a method to a widely used class. Only the files that read what changed are recompiled.
4. **Library edit.** Edit a library module deep in the chain (server → library → library). The dependents across modules are recompiled from source, with no `mvn install`.
5. **Warm restart.** Restart the daemon. It resumes from the committed roots and does not rebuild.

Byte equality with javac is a test oracle, not a merge gate for `develop` PRs. The amendments' §5 acceptance contracts still decide when a section is complete.

## 2. The stand-in project

The stand-in is [Eclipse hawkBit 1.0.4](https://github.com/eclipse-hawkbit/hawkbit/tree/1.0.4):
- **Stack:** Spring Boot 3.5.15, Java 17 (`maven.compiler.release=17` from the Boot parent), `-parameters` and `-proc:full`.
- **Processors:** Lombok in 478 of 728 main sources (65%), hibernate-jpamodelgen, and spring-boot-configuration-processor.
- **Size:** 728 main sources in 39 jar modules.
- **Chain:** update-server → mgmt/ddi → repository-jpa → repository-api → core.
- **Runtime:** it runs on embedded H2.
- **Build:** use `-pl '!hawkbit-ui'`, because that module needs a Vaadin/Node frontend.

## 3. Maven baseline

Warm `~/.m2`, offline, main sources, Temurin 25.0.4.1, Maven 3.9.16.

| Scenario | Maven | Files compiled |
|---|---|---|
| `clean compile` | 23.8 s | 728 |
| No-op `compile` | 5.8 s | 0 |
| One-line body edit in `JpaTargetManagement` | 12.3 s | 148: the whole module, plus 9 downstream modules "because of changed dependency" |
| New unused `public static` method on `AccessContext` (core) | 18.5 s | 533 |

## 4. Where main stands (33a9032)

| Step | Result |
|---|---|
| MACHINE over 804 MB of `~/.m2` plus JDK 25 | 8.5 s, peak RSS 3.2 GB. Spring Boot executable jars fault on `BOOT-INF/classes`. |
| LOCAL over 39 modules / 728 files | 13.8 s, 0 faults |
| Stage 2 invariant 1 ([stage 2 §7.3](jvmd-stage2-local-cold-boot.md)): source leaf k against the k of the same module's Maven-built jar | **5 of 32 comparable modules equal**. Source has 5,400 facts against 9,254 in the jars. 7 modules are not comparable: 6 Spring Boot executable jars and 1 uber jar. |

Most of the gap is annotation processing. `HeaderCompiler` runs with `-proc:none`, so LOCAL contains no processor-generated member or type:
- repository-api is missing 63 types; its jar has 65 Lombok builder classes.
- repository-jpa is missing 24 types; its jar has exactly 24 JPA metamodel classes (hibernate-jpamodelgen).

A few modules differ the other way, and those residuals get investigated once processors run:
- mgmt-resource, ddi-resource and repository-jpa-api have more facts in source than in the jar.
- rest-core and sdk-mgmt differ with equal counts.

No fault is raised. Stage 2 faults parse errors and duplicate declarations, not unresolved header symbols, so the gap is silent.

There is also no production provider. Nothing builds a `ProjectModel` from a Maven project, so LOCAL had only been booted from models built in tests (`MavenModelHelper` builds them from real Maven projects). The evidence above was taken with a throwaway model built from `maven-compiler-plugin`'s `compilePath`. Note that `maven-dependency-plugin:build-classpath` silently leaves out reactor siblings.

## 5. Scope: which amendment sections, and when

| When | Amendment sections and additions |
|---|---|
| Now (demo steps 1–2) | §3.1, the per-class fault rule only. §4.1 compile mode, with §3.5's `platformID` as a fixed input: `--release` goes to javac's own ct.sym provider. §4.8 processors in header extraction (native, Lombok and jpamodelgen). §2.1 lossless strings. §4.7, the controlled file manager only (no classpath sources). Additions A1–A6 below. |
| Next (steps 3–5) | §4.9 rooted immutable LOCAL records. §4.10–4.11 environment, answers (including `CLASS_INPUT`) and frontier. These include opening a stored baseline root for logical answers, and are built together with their first production consumer, the incremental update after an edit. §3.2 META for the stubs a body compiles against. |
| Not now | §3.3–3.4 multi-release selection catalogues. Historical *compiler views*, meaning compiling against retained old bytes; logical answers from an older root are in scope above. §3.5 indexed historical platform views (`platformID` itself is in scope now). Declaration-order ordinals and synthetic `Code` carriers. |

### Beyond the amendments

Demo steps 2–5 also need body compilation, hot swap, an edit-driven update and a warm restart. The amendments exclude all of these (preamble and §6), so this work comes from elsewhere:
- **Body compilation** comes from the stage 3 spec ([`jvmd-stage3-bodies.md`](jvmd-stage3-bodies.md)), core only: per-file attribution against stubs, content-addressed results, proofs, the body reverse index and materialisation.
- **The LIVE loop and warm boot** come from its Appendix D.
- **The proof standard is coarser than the original stage 3.** Completeness is a hard gate, minimality is measured, and conservative per-type dependencies are allowed wherever a finer one has not been proved.

## 6. Additions to the amendments

These were found by auditing main and by the hawkBit run. They are not in the amendments document.

| | Addition |
|---|---|
| A1 | A store that fails to open is an error, never "no root". On main, `Generation.create` deletes the directory of any store `hasRoot` cannot open, which erases MACHINE and every LOCAL root (fixed by #65). |
| A2 | A source file belongs to one module and scope. Path-only `F` and `X` keys let two modules that compile one source root overwrite each other, so overlapping roots are rejected or kept apart, never collapsed. Done by rejection (#72): an existing root at or under another module's or scope's root, as written or as a real path, is a model fault, checked before a committed root is kept. The provider no longer gives main a generated directory the tests add. Keying `F`/`X` by (module, scope, path) stays open, if a real project needs one file in two compilations. |
| A3 | The LOCAL skip compares the model, the MACHINE commit, the source files and the jars that bound to nothing, not FORMAT alone (#69, stage 2 §5.7). Until warm boot exists, a mismatch is a cold rebuild. Not compared yet, since they need warm boot's bindings: jars and the JDK changed in place (MACHINE is never re-committed), jars indexed on the spot (A7), and processor paths and configuration (A9). This overlaps §4.9, whose `LROOT` carries those identities; A3 is the decision that uses them, and it is needed before §4.9 lands. |
| A4 | A real provider. The model comes from the compiler step: classpath, processor path, options and generated roots per module and scope. |
| A5 | The ordered list `Diff` replaces its O(n·m) LCS with a linear-space diff. This is PR #62 review finding F18; F16–F17 are the bounded tree reads and decoding fixed with it. All three were fixed only on `feat/jvmd-stage-3` (0caa85a4); ported to develop with their regression test (#73). |
| A6 | Spring Boot executable jars in `~/.m2` (`BOOT-INF/`) are recognised and not faulted class by class. |
| A7 (open) | LOCAL already indexes jars that MACHINE lacks on the spot (stage 2 invariant 16). Those records are not under the MACHINE commit, and §4.9 requires every published record to be rooted, so where they are rooted must be decided before §4.9. |
| A8 (open) | A project built by a JDK other than the pinned one without `--release` compiles against a different platform than its build. The model records the build JDK, and the mismatch is reported. |
| A9 | Header compilation runs the scope's annotation processors (§4.8, #67). An unset `proc` is decided by the build JDK, which the model does not record: javac before 23 processes implicitly, 23 and later does not. A module whose release is 23 or later gets no processing. Below that it gets `full`, which is what projects built for 17 or 21 rely on, and the provider reports the choice. A processor error, or processing javac skipped over an error of its own, fails the scope rather than publishing incomplete headers. A file that does not parse is a parse fault and is kept out of the processing task. A class file a processor writes is a fault. Not yet bound to any record: the processor path and its configuration files (`lombok.config`), which warm reuse will need. |
| A10 (open) | Sibling stubs carry the annotations `A` retains (#68), so a dependent's header facts now depend on a sibling's `a` as well as its `k`. But `RouteEntry.Sibling`, `routeHash`, `R` and header proofs bind only `k`, so an annotation-only edit to a sibling cannot invalidate a dependent that is reused. Cold boot is unaffected. Before any reuse (warm boot, LIVE, stage 3), the dependency must be recorded: per-type `META`/`CLASS_INPUT` answers (§4.10), or the sibling's `a` in the route. |
| A11 (open) | LOCAL header compilation reads the platform from `--system <jdk_home>`, and runs annotation processors in the daemon's own runtime (#71). The daemon's default `jdk_home` is its jlink image, which holds only the modules jvmd needs. `java.desktop` was added for hawkBit, because spring-boot-configuration-processor loads `java.beans`. A project that compiles against another platform module, or a processor that loads one, faults or fails. §4.1's platform (ct.sym for `--release`, `platformID`) removes the compile half. The runtime half needs processors run in a JVM of `jdk_home`, or a full image. |
