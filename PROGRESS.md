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
