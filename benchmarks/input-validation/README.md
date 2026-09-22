# Input validation evidence

This component harness complements the existing workspace benchmark. It does not
compare JVMD counters with JDTLS, or equate component latency with editor latency.

Use Python 3.11+, JDK 25, the dependency JAR directory built for each revision,
and fresh output directories. `prepare.py` copies production sources into its
output and adds the same measurement hooks to both revisions. It does not edit
either checkout. Build manifests identify every original production source.

```sh
git worktree add "$BENCH_BASE" 9ac81c4bdded8de18537569debf02e789c24fe27
python benchmarks/input-validation/prepare.py --repo "$BENCH_BASE" \
  --dependencies "$JVMD_BASE_DEPENDENCIES" --java-home "$BENCH_JDK" --output "$INPUT_BASE"
python benchmarks/input-validation/prepare.py --repo "$PWD" \
  --dependencies "$JVMD_DEPENDENCIES" --java-home "$BENCH_JDK" --output "$INPUT_HEAD"
python benchmarks/input-validation/run.py "$INPUT_BASE/build.json" \
  "$INPUT_HEAD/build.json" "$INPUT_RESULTS" --runs 3
python benchmarks/input-validation/summarize.py "$INPUT_RESULTS" "$INPUT_SUMMARY"
```

The runner uses one identical 128-source fixture location, recreates it between
processes, fixes both heaps at 1 GiB, and alternates baseline/candidate order across
three serial repetitions. Cold setup is separate from 40 unchanged operations.
Body, API, membership, environment and reconciliation each have one observation
per process: their p50 and p95 therefore coincide and are not tail estimates.

`Validation` times complete input observation separately from navigation using
supplied detached facts; no javac runs in that loader. Work counts span both
operations. `Diagnostics` invokes real javac and checks expected errors, recording
analysed/reused files and query counts. Its time includes validation, compilation
when required, and result construction. Neither harness reports isolated javac
CPU time. ThreadMXBean allocation covers the owner thread only; it excludes other
threads and native allocations. Metadata counts cover instrumented Java Files
calls, not every syscall hidden inside a filesystem walk. Content hashing counts
file bytes; composition hashes and supplied-text checks are not file I/O hashes.

Reconciliation explicitly drops the candidate's disk observations. The baseline
has no equivalent operation and does nothing in this scenario. Report this as
the cost of the new conservative recovery operation, not an equivalent-work
speed comparison. Prepared language-service requests are measured separately by the
[workspace benchmark](../workspaces/README.md). Its ordinary comparison, attribution
and overhead runs use one LSP request path and their own correctness oracle. Component
work counts here remain separate from JVMD/JDTLS request timings.

Write raw records, profiles and logs to an ignored `target/` directory or an external
results directory. Archive the matching source revision and harness with evidence;
old report formats require their pinned historical verifier.

Each build manifest also records dependency hashes. The original before/after
measurements used identical dependency versions; future upgrades must build the
baseline with its own dependencies, not substitute candidate libraries.
