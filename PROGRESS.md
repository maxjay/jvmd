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
