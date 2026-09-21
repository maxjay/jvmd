# Performance evidence suite

This directory owns every JVM performance measurement that was formerly a JUnit test under
`jvmd-tests/src/test/java`. The classes are deliberately outside Maven's normal test source
tree: ordinary builds and checkpoint jobs cannot discover or execute them.

The `benchmarks` Maven profile adds this directory as a test source only for the merge-review
evidence workflow. That workflow runs the exact benchmark methods with the `perf` tag, including
their functional invariants and existing latency targets, after assembling and AOT-training the
production image. Timing and memory targets are retained in the JSON as observations, not flaky pass/fail
assertions; functional regressions still fail the evidence job.

Run the same suite locally after assembling and training the distribution:

```bash
bash jvmd-dist/assemble.sh
bash jvmd-dist/train-aot.sh
bash jvmd-tests/smoke/run-resolver.sh
bash jvmd-tests/smoke/run-index.sh
mvn -B -pl jvmd-tests test -Pbenchmarks -Dgroups=perf -DexcludedGroups=corpus
```

Measurements are written to `jvmd-tests/target/*perf.json`, with Surefire XML reports in
`jvmd-tests/target/surefire-reports/`. The merge-review artifact preserves both.

## Explicit warm production paths

The user-facing warm paths do not call `Application`, `Analyzer`, or a dispatcher in-process.
They launch the assembled, strict-AOT JVMD image, connect through its production Unix-domain
socket, open one session, prime the relevant caches, and then measure requests against that same
long-lived daemon:

| Report | Real usage path | Warm measurement |
| --- | --- | --- |
| `workspace-bindings-warm-validation-perf.json` | Repeat references in plain and Maven workspaces containing 128 and 512 files | 20 post-prime samples per workspace; p50/p95 plus compiler, binding, validation, and cache deltas |
| `workspace-bindings-incremental-perf.json` | References and rename preview over 128 files | Explicit cold, unchanged-warm, body-edit, and API-edit phases with semantic-work deltas |
| `unimported-completion-perf.json` | Type `Sa`, `Sam`, and `Samp` using unsaved document changes and dependency-index completion | First cycle is labeled `prime`; four later cycles provide warm p50/p95 and suggestion recall |
| `diagnostics-perf.json` | Repeat diagnostics for an unchanged erroneous 40-file workspace, then paginate | Warm latency and proof of zero additional javac queries, reanalysis, or index writes |
| `phase-4-perf.json` | Resolve identifiers at distinct positions in a 2,000-line source file | 20 warm-ups followed by 50 measured production-socket requests |
| `phase-7-perf.json` | Resolve Spring `StringUtils.hasText` documentation and its JDK signature closure | 20 warm-ups followed by 50 measured production-socket requests |
| `hotswap-perf.json` / `phase-10-perf.json` | Attach a real debug process and redefine method bodies | Repeated complete production requests, including compile/redefine detail |

Cold startup, resolver startup, index construction, and parallel-analysis reports remain separate on
purpose: those measure lifecycle or component paths, whereas the table above represents an editor
attached to an already-running JVMD daemon.
