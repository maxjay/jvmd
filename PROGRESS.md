# Implementation progress

Revision 6, 2026-09-14. Passing prerequisite smoke results are in [SMOKE.md](SMOKE.md).

## Phase 1 — PASS

Implemented: Content-Length JSON-RPC over a Unix socket, private socket and daemon lock,
canonical workspace sessions with one platform executor each, virtual-thread connections,
mandatory envelopes, compiler fault isolation, idle shutdown, structured request timings,
javac tier-0 overview and syntax diagnostics, jlink assembly, AOT training and rejection status.

Validation completed locally:

- `mvn -B -DskipTests install`: PASS; all nine modules compile and package.
- jlink assembly and strict daemon AOT training/use: PASS.
- Envelope, session, percentile, parse, syntax, jlink and AOT checkpoint tests: PASS.
- Full phase-1 suite: 9 passing tests, 3 blocked by `java.net.SocketException: Operation not permitted`
  at `UnixDomainSockets.socket0`. This environment prohibits Unix socket creation.
- No startup or overview latency claim; the performance test is among the blocked tests.

[GitHub Actions run 34799455116](https://github.com/maxjay/jvmd/actions/runs/34799455116)
passes all 14 tests on Ubuntu, including real socket framing, idle shutdown, and strict AOT
performance assertions. Startup is below 600 ms and 2,000-line overview p95 is below 50 ms.
Exact measurements are retained in the run's `checkpoint-evidence` artifact.

All 11 phase-1 checkpoint test classes pass. WAL shutdown is exercised through the resource-close
hook; the real database checkpoint will be covered when the phase-3 index is integrated.

## Phase 2 — in progress

Spring PetClinic is pinned at `818c4136ea971c21674525f9053de0d9c7ad8cfe` (Spring Boot 4.1.0).
The second corpus is this user's multi-module `jvmd` repository unless `JVMD_CORPUS_REPO` is set.
The embedded offline smoke gate passes: 21 artifacts in 283.560 ms, including supplier bootstrap.
The first checkpoint test passes for Maven parent inheritance, interpolation, simple repository
reuse despite an unlisted `_remote.repositories` ID, and repeat-query cache reuse.

The Resolver 1.9 sources contain `RepositorySystemSupplier` but no `SessionBuilderSupplier`.
The implementation uses Maven 3's verified `MavenRepositorySystemUtils.newSession()` factory.
Maven 4 is explicitly refused by this Maven 3 build; a major mismatch never produces a graph.

One necessary option detail was confirmed experimentally: `AOTCacheOutput` and `AOTCache` cannot
both be present on a Java command. The jlink image bakes all compiler exports and `AOTMode=auto`;
training and runtime scripts supply their respective, mutually exclusive cache flag.

Phase-2 selected-Maven-3 gate: PASS in [CI run 34802235793](https://github.com/maxjay/jvmd/actions/runs/34802235793),
all eight checkpoint classes. Cold resolution 537.289 ms (budget 1,000 ms), warm p95
1.110 ms (budget 5 ms), exact selected-GAV agreement with Maven's Spring Boot starter tree.
Local equivalent: cold 488.933 ms, warm p95 1.155 ms. Parent/settings/root content edits,
including same-size and preserved-mtime edits, invalidate the cache. Conflict losers remain
visible without contaminating the selected classpath. POM edits expose the classpath diff.
This distribution selects Maven 3 per the observed build machine; Maven 4 requests explicitly
refuse instead of returning a mismatched graph. A separately selected Maven 4 bundle remains
an implementation item before the full design can be called complete.

Phase-1 repeat in that CI run: cold startup 244.855 ms, overview p95 19.430 ms.

## Phase 3 — prerequisites passed, implementation in progress

Smoke 8 selects Spring Core's Java 21 and 24 class entries on the pinned Java 25 runtime.
Smoke 9 joins 6,018 of 6,079 source signatures (98.99655%, above the 98% gate) against
Spring Core 7.0.8 bytecode without attribution. Unmatched source signatures are counted and
retain binary parameter names or argN fallbacks. The smoke includes Spring's repackaged bytecode
libraries as input data; none is put on the daemon classpath.

Phase-3 local gate: PASS, nine checkpoint classes (ten tests including the return-type overload
regression). Full repository: 456 artifacts, 798,816 symbols, 859,650 structural edges,
79,045 type names in 72,023.711 ms under a 1,024 MB heap cap. No index faults. All present sources
jars are parsed eagerly; Spring Core's unmatched source-member counter is 61. Search uses FTS5
trigrams, with escaped short-query fallback; duplicate-class warnings name all artifact paths.
WAL is truncated on close. Immutable jar stat reuse, unconditional SNAPSHOT hashing and SHA-1
rejection are tested.

The corpus caught two JVM return-type-only overload collisions in Spring's Kotlin-generated
classes. Both are preserved using return-type disambiguators only for colliding SCIP IDs. The
SCIP format for ordinary Java methods is unchanged. A JVM-generated fixture reproduces this
without adding a Kotlin dependency. A broad hierarchy join initially selected a quadratic plan;
explicit owner-first joins and a composite index removed it. The complete pass above includes
structural linking, with code-edge indexing still untouched until an explicit reference query.
AOT training now exercises a mid-sized Jackson index, Maven resolution, and twenty parse/search
requests; strict cache loading passes. Phase-3 CI confirmation is pending publication.

Phase-3 CI gate: PASS in [run 34804015960](https://github.com/maxjay/jvmd/actions/runs/34804015960),
all ten tests across nine checkpoint classes. CI's 202-artifact Maven repository indexes in
33,135 ms: 224,278 symbols and 223,374 structural edges, no faults. The first integration run
caught overview p95=81.618 ms during eager indexing. A bounded, content-keyed parse-result cache
fixed the real regression; unchanged source does not repay parsing, and edit invalidation is
regression-tested. Repeat CI: startup 309.406 ms, overview p95 11.438 ms; resolver cold 492.935 ms,
warm p95 1.648 ms. Budgets remain unchanged. Phases 1–3 now pass together.

## Phase 4 — prerequisite passed, implementation in progress

The tolerant compiler smoke confirms bindings survive mixed syntax/type errors and the indexed
file-manager fault boundary catches a class disappearing after enumeration. See SMOKE.md.

Phase-4 checkpoints through compiler settings pass in [CI run 34807688604](https://github.com/maxjay/jvmd/actions/runs/34807688604): indexed classpath bytes, tolerant FLOW attribution, pool recycling, tier-one outlines, focusing, fault degradation, lazy source invalidation, bound navigation/graphs, and a real Maven verification runner preserving javac diagnostic codes and locations. Verification timeouts terminate the child process tree.

The full corpus gate remains in progress. It caught stale package scopes in pooled compiler contexts, then distinct cached copies of the same source type. Focused attribution initially measured p95 67.362 ms, then 57.402 ms; source-layout and identifier-span caches reduced it to **43.341 ms p95** (20.672 ms median, 47.995 ms maximum) in [run 34808660727](https://github.com/maxjay/jvmd/actions/runs/34808660727). This test attributes 70 different members of a 2,002-line file: 70 compiler queries, no binding-cache hits; only the unchanged source layout is reused. Session open was 4.405 ms. The 50 ms limit has not changed.

That run's initial identifier sweep scored 28,275 / 30,816 (91.754%) against the committed 95% floor. The sweep exposed an outer-focus cache incorrectly covering erased nested method bodies; a dedicated regression and cache-coverage fix are committed. Corpus agreement and the sweep must both pass before phase 4 is marked complete. Measured compiler memory is conservatively guarded using process heap growth, not claimed as a precise retained-object-graph measurement.

Phase-4 exit: **PASS** in [run 34809147407](https://github.com/maxjay/jvmd/actions/runs/34809147407), commit `4bdb750a558728dece83ba8b31d64c26bce4c60c`. PetClinic and jvmd each return zero live diagnostics at tier 2 and zero verified diagnostics from real `mvn test-compile`. Identifier sweep: **30,337 / 31,145 = 97.405683%**, with the committed floor raised to 97%. Most remaining lexical misses are contextual `var`/`record` keywords; static imports of overloaded methods remain ambiguous. The test records every miss; it does not discard them from the denominator. All 20 non-performance tests, including corpus checks, pass; the independent performance run passes all 19 tests. Focused p95 **48.571 ms**, median 28.374 ms, maximum 61.075 ms; session open **5.539 ms**; startup **306.559 ms** and outline p95 **6.666 ms**. The 50 ms focused gate is a p95 assertion, not a claim about every individual request.

Workspace source takes precedence over compiled outputs so edits with preserved timestamps cannot be masked by old class files. Pooled package completion is reset for parsed source packages, keeping javac's jar symbol cache while preventing stale source identity. Nested focus snapshots explicitly exclude erased bodies. The compiler extensions remain confined to the analyzer and use only the original three javac exports. Full design work continues with phases 5–11 and the recorded Maven 4 bundle item.

## Phase 5 — in progress

Annotation processors will run only in external JVMs. The default Lombok policy is reduced fidelity with an explicit status warning; generated public APIs will be supplied by external compilation. No processor runs in the daemon.

 
Phase 5 implementation checkpoint (validation queued):
- Effective Maven processor paths (including transitive dependencies and exclusions), configured generated roots, and separate test configuration.
- Time-boxed external javac processing with bounded disk output, source/processor content hashes, cancellation, and per-module generation caching.
- Lombok reduced-fidelity status policy; externally compiled generated APIs used for implicit source lookup. Original source remains available for navigation and explicit analysis.
- Real MapStruct/Lombok integration fixtures plus child-exit and timeout containment tests.
- Repeat phase 4 corpus gate remained clean at e9f8de1; focused attribution repeated at p95 56.901 ms, above the unchanged 50 ms budget. Added query-local identity and source-location caches to remove repeated lookups; awaiting CI measurement.
- Local executor remains disconnected. Changes are committed through GitHub and validated by Actions; no local test result is claimed.

 
Phase 5 gate: PASS, CI run 34810626086 (2026-09-14).
- Real MapStruct and Lombok bindings, generated main/test source roots, processor timeout, and processor JVM termination containment all passed.
- The same run passed the unchanged focused-attribution budget after query-local identity caching.
- Full-corpus validation exposed a configuration risk before completion: a test-only Lombok dependency activated processing without an explicit Maven request. Corrected activation to follow JDK 25's explicit processor configuration, with a regression test.
- Blocking smoke 4 is queued before phase 6 implementation: source API substitution with an unbuilt WorkspaceReader and an older published artifact.


Phase 5 full regression: PASS, CI run 34811222662, commit b193a37.
- 33,386 / 34,297 identifiers: 97.34379% (floor 97%).
- PetClinic and jvmd: live=0, verified=0; real Maven test-compile.
- Startup 302.308 ms; focused p95 48.490 ms; session open 6.490 ms.
- Blocking smoke 4 PASS: unbuilt reader returns null and source API replaces the old published jar.
- Phase 6 implementation now includes ordered manifest roots, Maven reactor discovery, source-only dependencies, built-output substitution, version/cycle status, navigation and diagnostic origins. Its tests are queued.
- The source-lookup exit test also exercises the initial phase-10 JDI launch, line-breakpoint and frame implementation. Phase 10 is permitted after phase 4; memory inspection, eval, hotswap and its complete gate remain in progress.
- Processor content hashes now reuse Linux change-time/file stamps on unchanged inputs; a processor-bytecode replacement test preserves mtime to verify regeneration.


2026-09-14: Commit 158e639 passed independent checkpoint and full corpus jobs (run 34812834988). Phase 6 repository substitution, source invalidation, diagnostic origins and cross-root JDI breakpoint tests pass. Local module index artifacts remain open. The next optimization omits unrelated method answer DTOs from focused queries while retaining their signature dependencies; a regression covers overload ambiguity after an unselected parameter hierarchy changes. The 50 ms budget and corpus floor are unchanged.


2026-09-14: Focused materialization checkpoint d7c4954 passes its overload dependency regression and all checkpoint jobs (run 34813676063). Focused p95 37.772670 ms; p50 19.170712 ms; session open 7.033502 ms. The full corpus job also passed. Local module indexing now has migration 2 memberships preserving a single SCIP with per-artifact source data, asynchronous registration, strong source/output hashes with ctime-based hash reuse, stale binary suppression, source snapshot persistence, and tests covering equal coordinates, deletion, preserved mtimes, jar replacement, and schema migration. Phase 6 remains unchecked until these tests pass.


2026-09-14: Phase 6 tag is green at 2420b7b (run 34814392233): ten tests, including local artifact migration, preserved-mtime source changes, independent installed/check-out variants, removed symbols, cross-root diagnostics and breakpoint binding. Focused p95 33.936568 ms, p50 14.634629 ms, open 5.999761 ms. Source hashes include all source/output bytes; Linux ctime is only a memoization key, not artifact identity. Phases 7–9 may begin. Phase 7 implements recursive visited signature traversal and cursor pages, query-time inheritDoc, source-jar bodies, and JDK class/source joining; its exit benchmark remains to pass. Both independent CI jobs passed, including the full corpus regression.


2026-09-14: Phase 7 and the full corpus are green at a8ad2d1 (run 34815208610). Four documentation tests pass. Strict-AOT StringUtils/hasText(String) doc_depth=3 p95 18.290260 ms, p50 13.159404 ms (budget 50 ms); cursor pages include JDK source documentation. Phase 4 p95 in the same run is 46.120933 ms. Runtime work now includes bounded inspection with child/string continuations, TTL heap snapshots, explicit-max instances/referrers, histogram, primitive arithmetic/field/array/cast evaluation and selected JDI method invocation. New real-debuggee tests check frame expiry after invocation, retaining holders and collection after handle expiry. Phase 10 remains open: protocol wiring, production attach budget, stock hot swap/restart, and JFR wrappers are still outstanding; phase 11 has not started.


2026-09-14: Runtime inspection, expression evaluation and handle collection tests pass at 67e15d0 (run 34816000219); the full corpus job is also green. Focused p95 40.985171 ms and documentation p95 18.331502 ms. The next runtime checkpoint wires run.start/debug.op to session-owned runs, compiles unbuilt modules in dependency order, preserves per-module output boundaries during hot swap, returns restart_required for stock schema changes, and adds explicit JFR start/dump through debug histogram args.jfr without changing the frozen op enum. The new protocol and strict-AOT attach tests are pending. Phase 10 remains unchecked until its final gate and prohibition checks pass.


2026-09-14: The runtime protocol gate found an empty-local-table edge case after redefining an active static method. javac legitimately omits LocalVariableTable in a method with no locals even under -g; evaluation now distinguishes that from a declaring class with no variable information. Obsolete replaced frames explicitly require resume/step, and the protocol regression resumes onto the rebound breakpoint before evaluating the new body. A separate -g:lines,source regression preserves refusal when variable tables are actually absent. Strict-AOT attach passed at 21.785232 ms; the protocol/JFR remainder is being rerun.


2026-09-14: Phase 8 begins with dispatcher-wide 64 KiB response fragments, including warnings and verification-error tails. Continuations are scoped to the method and arguments, expire after 60 seconds, preserve native cursors, and replay cached output without repeating a mutation or verification process. Unicode splits preserve surrogate pairs and report UTF-16 offsets in _jvmd_segments. List limits now reject zero and non-integer values. New tests cover full row recovery, Unicode recovery, warning pages, cross-session rejection, and single-execution error pagination. Fourteen MCP tools, semantic edits, bytecode references and the stdio shim remain open.

The runtime protocol correction e473770 passed both CI jobs (run 34817902360). Seven phase-10 tests passed, including stock schema-change/restart, JFR output, absent-variable-table refusal and collection after expiry. Strict-AOT attach: 18.865003 ms; focused attribution p95: 41.697203 ms; documentation p95: 18.692029 ms. A supplementary real step-into/over/out regression accompanies the response-budget checkpoint; phase 10 will be ticked after that regression passes.


### Phase 8 checkpoint: name paths and dependency pages

- Added one grammar for source and indexed symbols. Erased JVM descriptors disambiguate qualified parameters; simple parameter names retain every matching overload.
- Workspace find now defaults to workspace scope and reports tier 1 for declaration-only results. Dependency graph continuation advances both node and edge pages.
- Added source and dependency overload tests covering nested owners, multidimensional arrays, and malformed references. CI evidence will be appended after this checkpoint passes.

- ResponseBudgetTest passed all three tests on de4b4ac: bounded Unicode/list/error pages and no repeated side effects. The supplementary step-out test required advancing past the caller's assignment before inspecting its new local; this corrects the test expectation, with production stepping unchanged.


### Phase 10 complete; phase 8 name paths passed

- All eight runtime tests passed on cce6db1: launch/attach, debug-info refusal, breakpoints and all three stepping modes, frames/locals, bounded inspection, interpreted evaluation, body hot swap, stock-JDK restart on schema changes, retained-object queries and real collection after handle expiry. JFR start and dump passed through the runtime protocol.
- All five current phase-8 tests passed: three response-budget tests and two name-path tests. Phase 8 remains open for semantic editing, code-edge traversal and the MCP shim.

### Phase 8 checkpoint: semantic edits

- Added checked UTF-16 edit plans with source-change validation before writes, atomic replacement of individual files and rollback on failures.
- Body replacement, declaration insertion and text edits return diagnostics scoped to touched declarations. Rename follows resolved occurrences and override families, and renames a top-level class's source file with its constructors.
- Added tests for identity-preserving edits, previews, member diagnostics, invalid ranges and stale plans. CI validation is pending this checkpoint.

- Passing cce6db1 measurements: strict-AOT startup 289.29 ms (600 ms budget), workspace open 9.85 ms (200), focused attribution p95 42.52 ms (50), Spring documentation depth 3 p95 10.72 ms (50), runtime attach 21.17 ms (500).


### Phase 8 checkpoint: fourteen MCP tools and stdio transport

- Added a Java-owned catalog with exactly fourteen tools and frozen argument schemas, plus strict schema validation and core-protocol mappings. The Java schema snapshot prevents accidental argument renames.
- Added a dependency-free TypeScript MCP stdio adapter, Content-Length Unix RPC transport, automatic daemon start and workspace reuse. Tool results retain tiers, provenance and continuations.
- The MCP adapter requests 30 KB core pages so escaped JSON text remains below the 64 KB tool-result limit. Budget continuations never re-run the underlying operation.
- CI now pins Node.js 24.21.0. Tests cover fragmented Unicode framing, concurrent RPC replies, MCP error continuations and a real strict-AOT stdio session that finds callers three deep, replaces a body and passes external javac verification.
- Package overview, lazy dependency code traversal and the full corpus agent-session exit remain open.

- SemanticEditsTest passed all four tests on d3170c3. The existing gates stayed green: startup 291.95 ms, workspace open 5.72 ms, focused attribution p95 35.21 ms, documentation depth 3 p95 8.48 ms, attach 16.71 ms.


### Phase 8 MCP checkpoint passed

- All thirteen current Java agent-surface tests and three Node transport tests passed on 1175aba. The strict-AOT stdio session completed caller traversal, editing and compiler verification; schema snapshots and bounded MCP error/operation continuations passed.
- Current measurements: startup 281.44 ms, workspace open 6.23 ms, focused attribution p95 34.96 ms, documentation depth 3 p95 9.66 ms, attach 19.00 ms.

### Phase 8 checkpoint: lazy dependency code references

- Added schema 3 with artifact-scoped code targets and a constant-pool class-reference catalog. The eager skeleton pass does not read method bodies; incoming queries use the catalog to identify candidate jars.
- Explicit references queries decode invoke, field access, allocation, cast/type-check and invokedynamic bootstrap handles. Source call edges remain javac-derived. Query results filter by workspace membership and follow the requested direction, kinds and depth.
- Bytecode scans persist their completed state per content-hashed artifact. Missing private caller declarations are added only when that artifact's code is requested.
- Tests cover call chains, field reads/writes, allocation, casts, method references, untouched artifacts and workspace isolation. Validation is pending.


### Phase 8 checkpoint: package overview and corpus agent session

- Package and directory overview use the same tiered, paged declaration model as file overview.
- Added a real PetClinic agent-session exit test: follow callers three deep through MCP mappings, replace Owner.getPets, run Maven verification, and restore the corpus source afterward. Every tool result is checked against the response budget.
- Last complete corpus run (1175aba): 52,320 / 53,866 identifiers resolved correctly (97.1299%, floor 97%); PetClinic and jvmd both had zero live diagnostics and zero verified diagnostics.

- LazyCodeReferencesTest passed, with all previous checkpoints green on c7ace16. Added scoped parameter/local name paths and dependency hierarchy traversal to close the remaining lookup cases.

- Completed find depth expansion, kind filtering before dependency page limits, and status index/capability reporting. Added dependency hierarchy coverage alongside package and scoped-name tests.


### Phase 8 exit: complete agent session on the corpus

Commit 0c89f926 passed both jobs in [run 34823237638](https://github.com/maxjay/jvmd/actions/runs/34823237638): seventeen phase-8 Java checks, three Node transport checks, and the actual PetClinic caller traversal, body replacement, and verified Maven build. The agent session restores the original source and records target/agent-session.json. The fourteen tool schemas are frozen.

Strict AOT measurements: startup 305.332047 ms / 600; session open 5.338127 ms / 200; focused attribution p95 37.166884 ms / 50; documentation closure p95 8.547171 ms / 50; runtime attach 19.986755 ms / 500. Corpus identifier sweep: 55,125 / 56,799 = 97.052765%, above the unchanged 97% floor. PetClinic and jvmd both report live=0 and verified=0 diagnostics.

### Phase 9 document synchronization checkpoint

Versioned editor buffers now feed focused queries, workspace traversal, rename plans, and javac's source-path lookup. Changes invalidate reverse dependencies without attributing them. Closing a buffer restores disk content; newly opened Java files need not exist on disk. Incremental edits use sequential UTF-16 ranges, preserve CRLF, reject split surrogate pairs and stale versions, and apply the notification atomically.

Editor-owned files use dry-run edit plans so the client applies its own changes. Verified builds and launches refuse dirty buffers because their compilers read saved files. Generated processor APIs are explicitly labeled when their saved inputs differ from the editor. Phase-9 synchronization tests have been added to CI; the phase is not yet marked complete.
