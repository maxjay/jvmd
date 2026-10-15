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

### Where develop stands (Oct 15, develop at the merge of #74)

The indexing half of demo step 1 runs in the product. A daemon started from the image boots MACHINE before READY. Opening hawkBit then boots its LOCAL layer in the background from the session's own resolution, and `daemon.status` reports it under `layered_index`. Compiling bodies and running the server are not part of it yet: nothing reads LOCAL so far.

| Step | Result on hawkBit 1.0.4 (image, default config) |
|---|---|
| MACHINE (image JDK plus `~/.m2`) | about 7.5 s before READY, over 821 jar locations on the first run (855 later the same day, because `~/.m2` grew). 16 class faults, all `BOOT-INF/classes` in hawkBit's own executable jars: A6 is not done. |
| `session.open` | 7–10 s, the existing Maven resolution |
| LOCAL cold boot in the daemon | about 34 s: 40 modules, 939 files, 2 faults (the `rest-core:tests` test-jar, which the model cannot express). §4's 13.8 s covered the main scope only, with `-proc:none`. This boot includes test scopes and annotation processors, and uses half the cores (8 of 16). |
| Daemon restart | `session.open` re-resolves (7–10 s). MACHINE is then skipped without checking `~/.m2`, because warm boot is not implemented (A3). LOCAL is `current` 0.4 s later (#69). |
| SIGTERM during a LOCAL boot | exits in 3.3 s when sent 8 s into the boot: running module jobs finish their scope, and closing waits at most 60 s. No root is written, and the next start cold boots. |
| Invariant 1, main scope, 32 of the 39 jar modules | 30 equal the Maven-built jars; the 2 EclipseLink-woven ones equal an unwoven `mvn compile` (#67, #68; re-checked on develop at #73). `hawkbit-ui` has no jar, and test scopes are not compared. |

What changed since main, in PR-number order (#63 made CI manual-only; #64 added the amendments and this plan):
- #65: MACHINE never deletes a store it cannot open (A1).
- #66: the Maven provider (A4).
- #67: processors in header compilation (§4.8, A9).
- #68: annotated sibling stubs (A10 opened).
- #69: the skip rule (A3).
- #70: no classpath sources (§4.7).
- #71: the daemon wiring. Processors are isolated from the daemon's class path, and a boot can be cancelled.
- #72: one module and scope per source file (A2).
- #73: bounded tree reads and the linear-space ordered diff (A5).
- #74: lossless modified UTF-8 for every persisted string and key (§2.1). MACHINE parser 7, LOCAL 26. Presence-byte strictness is left.

Still open from "Now" in §5: §4.1 (the platform from ct.sym; see A11) and A6.

Next is step 2: body compilation (stage 3 core). Hot swap already exists (`run.start` / `debug.op hotswap`), but it covers method bodies only and compiles outside the layered index against the run's class path. What is missing is choosing the files from LOCAL and compiling them against its stubs.

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
| A6 (open) | Spring Boot executable jars in `~/.m2` (`BOOT-INF/`) are recognised and not faulted class by class. |
| A7 (open) | LOCAL already indexes jars that MACHINE lacks on the spot (stage 2 invariant 16). Those records are not under the MACHINE commit, and §4.9 requires every published record to be rooted, so where they are rooted must be decided before §4.9. |
| A8 (open) | A project built by a JDK other than the pinned one without `--release` compiles against a different platform than its build. The model records the build JDK, and the mismatch is reported. |
| A9 | Header compilation runs the scope's annotation processors (§4.8, #67). An unset `proc` is decided by the build JDK, which the model does not record: javac before 23 processes implicitly, 23 and later does not. A module whose release is 23 or later gets no processing. Below that it gets `full`, which is what projects built for 17 or 21 rely on, and the provider reports the choice. A processor error, or processing javac skipped over an error of its own, fails the scope rather than publishing incomplete headers. A file that does not parse is a parse fault and is kept out of the processing task. A class file a processor writes is a fault. Not yet bound to any record: the processor path and its configuration files (`lombok.config`), which warm reuse will need. |
| A10 (open) | Sibling stubs carry the annotations `A` retains (#68), so a dependent's header facts now depend on a sibling's `a` as well as its `k`. But `RouteEntry.Sibling`, `routeHash`, `R` and header proofs bind only `k`, so an annotation-only edit to a sibling cannot invalidate a dependent that is reused. Cold boot is unaffected. Before any reuse (warm boot, LIVE, stage 3), the dependency must be recorded: per-type `META`/`CLASS_INPUT` answers (§4.10), or the sibling's `a` in the route. |
| A11 (open) | LOCAL header compilation reads the platform from `--system <jdk_home>`, and runs annotation processors inside the daemon (#71). Processors there see only their own path and the daemon runtime's JDK modules (its jlink image, not `jdk_home`), never the daemon's class path. The image holds only the modules jvmd needs. `java.desktop` was added for hawkBit, because spring-boot-configuration-processor loads `java.beans`; that grows the image from 204 MB to 231 MB, with no measurable MACHINE slowdown. A project that compiles against another platform module, or a processor that loads one, faults or fails. Two changes close this: §4.1's platform (ct.sym for `--release`, `platformID`) for compilation, and running LOCAL in a child JVM of `jdk_home` (or a full image) for processors. A child JVM would also give the boot its own heap; today it shares the daemon's (no `-Xmx`, and `heap_ceiling_mb` only budgets caches). |

## 7. Follow-ups (not exhaustive)

From the reviews of #69–#74, left open:
- **`OrderedDiff` budget:** it is O((n+m)·D) with no budget, about 1.3–2.4 s for two disjoint 16k runs, depending on machine and keys (#73). This is latent: the only caller, `DefinerIndex.conflicts`, diffs lists of hundreds. A comparison budget would fall back to remove-all/add-all for that run.
- **`Node` strictness:** `Node.entries` rejects trailing bytes; `Node.elements`/`children` should too (#73).
- **AotDaemon READY timeout:** already on main (33a9032), the daemon indexes the real `~/.m2` as MACHINE before READY, about 7–10 s here. The AotDaemon tests wait 10 s for READY and do not set `m2_repo`, so they are at the edge. They should use a fixture repository.
- **Failed LOCAL boots:** every reopen retries one, with no memory of the failed model and stamps (#71).
- **Re-triggering LOCAL:** a resolution refreshed after `session.open` does not re-trigger the LOCAL boot; reopening does (#71).
- **Shared state dir:** two daemons with different sockets share one state dir, and the second one's LOCAL boot fails on the RocksDB lock (#71).
- **The kill switch:** `-Djvmd.layered.local=false` should become a `config.json` key with the next change to `Config` (#71).
- **Source path vs Maven:** jvmd differs from Maven on resource reads from the source path, and on implicit compilation of sources excluded with `<excludes>` (#70).
- **Case aliasing:** on case-insensitive file systems, case-only aliasing between source roots is not detected (#72).
- **§2.1 remainder:** `optStr`/`optId` presence bytes other than 0 and 1 are not rejected yet (#74). F-row T_reads/absences are ordered by String, not specified by the appendix (#74).
