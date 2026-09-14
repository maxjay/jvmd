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
