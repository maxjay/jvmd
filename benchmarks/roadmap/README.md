# JVMD roadmap

JVMD is meant to serve an IDE the way JDTLS serves VS Code. This suite runs the same
editor scenarios against both servers and answers three questions:

1. **What does JVMD support yet?** All 28 scenario families from the workbook
   (`JDTLS_API_and_Test_Scenarios.xlsx`), covering 106 APIs: standard LSP plus the
   `java.*` API that vscode-java calls.
2. **Are its answers right?** Every answer is checked against a small Java fixture:
   the exact symbol, range, edit or diagnostic, and whether it reflects the latest edit.
3. **How fast, and how much does it allocate?** Per request, next to JDTLS.

## Run it

```sh
mvn -B -DskipTests install && bash jvmd-dist/assemble.sh
node benchmarks/roadmap/run.ts --output /tmp/roadmap --java-home "$JAVA_HOME" --jdtls-home "$JDTLS_HOME"
open /tmp/roadmap/report.md
```

Useful options: `--servers jvmd` (skip JDTLS), `--only CMP-01,NAV-02/references-true`
(families or cases), `--runs 3` (repeat everything for steadier numbers), `--list`.
A few cases need extra tools: `--alternate-java-home` (a JDK 17, for JDK switching),
and `--gradle-home`, `--protoc` and `--protobuf-java` (for protobuf generation).

`node --test benchmarks/roadmap/test/*.test.ts` runs the harness tests. They need no
servers, because a scripted fake server drives the real runner.

## The report

- **Scenario families**: one row per workbook family, per server: ✅ done, 🟡 partial,
  ⬜ not implemented, or a problem marker (see below). This is the roadmap.
- **Not implemented in JVMD**: each missing endpoint, with the cases waiting on it.
  JVMD's own evidence decides this: its startup capabilities, or its "method not found"
  reply to a `java/...` request. As soon as JVMD implements an endpoint, those cases
  start checking its answers, with no change to the suite.
- **Cases to look at**: anything that isn't a pass or a missing endpoint. ❌ means a
  wrong or failed answer. 🕒 stale means the first answer after an edit was out of date
  and a later retry was right. ❔ unverified means a legal answer the harness can't
  check, such as diagnostics sent without a document version. ⚠️ means a harness
  problem, such as a missing tool.
- **Latency and allocation**: for each endpoint, first use, repeat, and after a change
  (edit to first correct answer). Median and p95 latency, and median bytes allocated.

`results.json` has every request, and `cases/*/result.json` holds each case's raw
responses for debugging.

## How it measures

- Every case starts a fresh server on a fresh copy of its fixture, so cases can't
  affect each other. Setup requests aren't timed.
- Both servers run on the same JDK with a 1 GB heap. JVMD runs its daemon on the full
  JDK rather than the packaged image, because the image leaves out `jdk.management`,
  which provides the allocation counters.
- Allocation comes from a small Java agent (`harness/agent`) loaded into each server
  JVM. It reports the JVM's total allocated bytes before and after each request. That
  total covers the whole JVM, including background work. For JVMD it is the daemon only;
  child `javac` or resolver processes aren't included.
- A slow or unclean server exit is noted, but doesn't discard answers already checked.

The run exits nonzero when a JVMD case is wrong, stale or hits a harness error.
Missing endpoints and unverifiable answers don't fail it.
