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
