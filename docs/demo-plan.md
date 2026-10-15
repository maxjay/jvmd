# jvmd demo plan: the goal loop on a real project

Oct 15, 2026. This plan sets the order in which MACHINE, LOCAL and the [Stage 1/2 amendments](jvmd-stage1-2-amendments.md) are implemented. Work is driven by one demo on one real project. A piece of the amendments is built when the demo needs it, and not before.

## 1. The goal loop

The demo has five steps, run on a Maven monorepo that looks like the work code jvmd is meant for:

1. **Cold.** Index `~/.m2`, boot the project, compile it, and run the server. This happens once.
2. **Body edit.** Change one method body. Exactly one file is recompiled and hot-swapped into the running server.
3. **API edit.** Add a method to a widely used class. Only the files that read what changed are recompiled.
4. **Library edit.** Edit a library module deep in the chain (server → library → library). The dependents across modules are recompiled from source, with no `mvn install`.
5. **Warm restart.** Restart the daemon. It resumes from the committed roots and does not rebuild.

Byte equality with javac stays as a test oracle. It is not a merge gate.

## 2. The stand-in project

The stand-in is [Eclipse hawkBit 1.0.4](https://github.com/eclipse-hawkbit/hawkbit/tree/1.0.4):
- **Stack:** Spring Boot 3.5.15, Java 17 (`maven.compiler.release=17` from the Boot parent), `-parameters` and `-proc:full`.
- **Processors:** Lombok in 510 of 970 files, hibernate-jpamodelgen, and spring-boot-configuration-processor.
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
| Invariant 1: source leaf k against the k of the same module's Maven-built jar | **5 of 32 comparable modules equal**. Source has 5,400 facts against 9,254 in the jars. |

All of the invariant 1 gap is annotation processing. `HeaderCompiler` runs with `-proc:none`, so none of the Lombok-generated members exist in LOCAL:
- repository-api is missing 63 types; its jar has 65 Lombok builder classes.
- repository-jpa is missing 24 types; its jar has exactly 24 JPA metamodel classes.

No fault is raised, because headers do not reference generated members; only bodies do.

There is also no production provider. Nothing builds a `ProjectModel` from a Maven project, so LOCAL had only booted hand-written models. The evidence above was taken with a throwaway model built from `maven-compiler-plugin`'s `compilePath`. Note that `maven-dependency-plugin:build-classpath` silently leaves out reactor siblings.

## 5. Scope: which amendment sections, and when

| When | Amendment sections and additions |
|---|---|
| Now (demo steps 1–2) | §3.1 one malformed class faults that class only. §4.1 compile mode: `--release` passed to javac as a fixed input. §4.8 processors in header extraction (native, Lombok and jpamodelgen). §2.1 lossless strings. §4.7 no classpath sources. Additions A1–A6 below. |
| Next (steps 3–5) | §4.9 rooted immutable LOCAL records. §4.10–4.11 environment, answers and frontier, built together with the first production consumer (the incremental update after an edit). §3.2 META and `CLASS_INPUT` for the stubs a body compiles against. |
| Not now | §3.3–3.4 multi-release selection catalogues. Historical environments. §3.5 indexed ct.sym platform views (javac's own `--release` provider is used directly). Declaration-order ordinals and synthetic `Code` carriers. |

## 6. Additions to the amendments

These were found by auditing main and by the hawkBit run. They are not in the amendments document.

| | Addition |
|---|---|
| A1 | A store that fails to open is an error, never "no root". `Generation.create` deleted the directory of any store `hasRoot` could not open, which would erase MACHINE and every LOCAL root. |
| A2 | Source records are keyed by (module, scope, path). Path-only `F` and `X` keys let two modules that compile one source root overwrite each other. Overlapping roots are rejected or kept apart, never collapsed. |
| A3 | The LOCAL skip compares the model, MACHINE commit and compiler environment identities, not FORMAT alone. Until warm boot exists, a mismatch is a cold rebuild. |
| A4 | A real provider. The model comes from the compiler step: classpath, processor path, options and generated roots per module and scope. |
| A5 | The ordered list `Diff` replaces its O(n·m) LCS with a linear-space diff (F16–F18). |
| A6 | Spring Boot executable jars in `~/.m2` (`BOOT-INF/`) are recognised and not faulted class by class. |
| A7 (open) | Jars added after MACHINE commits are indexed by LOCAL on the spot. Where their records are rooted must be decided before §4.9. |
| A8 (open) | A project built by a JDK other than the pinned one without `--release` compiles against a different platform than its build. The model records the build JDK, and the mismatch is reported. |
