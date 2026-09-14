# Implementation progress

Revision 6, 2026-09-14. Passing prerequisite smoke results are in [SMOKE.md](SMOKE.md).

## Phase 1 — awaiting socket-capable CI

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

The GitHub Actions gate executes the same tests on Ubuntu. No phase-1 checkpoint is marked complete
until that gate passes. Phases 2–11 have not started, as required by section 12.6.

One necessary option detail was confirmed experimentally: `AOTCacheOutput` and `AOTCache` cannot
both be present on a Java command. The jlink image bakes all compiler exports and `AOTMode=auto`;
training and runtime scripts supply their respective, mutually exclusive cache flag.
