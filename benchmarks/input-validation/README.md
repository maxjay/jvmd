# Input validation evidence

This component harness complements the existing workspace benchmark. It does not
compare JVMD counters with JDTLS, or equate component latency with editor latency.
Input ownership is described in [storage.md](../../docs/storage.md).

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
speed comparison. Process restart is compared by the workspace suite.

Run that suite separately with uninstrumented builds:

```sh
python benchmarks/workspaces/compile.py --repo "$BENCH_BASE" \
  --dependencies "$JVMD_BASE_DEPENDENCIES" --java-home "$BENCH_JDK" --output "$EDITOR_BASE"
python benchmarks/workspaces/compile.py --repo "$PWD" \
  --dependencies "$JVMD_DEPENDENCIES" --java-home "$BENCH_JDK" --output "$EDITOR_HEAD"
python benchmarks/workspaces/fixture.py --build "$EDITOR_HEAD/build.json" \
  --dependencies "$JVMD_DEPENDENCIES" --root "$BENCH_FIXTURES"
python benchmarks/workspaces/matrix.py --repo "$PWD" \
  --before "$EDITOR_BASE/build.json" --after "$EDITOR_HEAD/build.json" \
  --java-home "$BENCH_JDK" --jdtls "$JDTLS_HOME" --resolvers "$JVMD_RESOLVERS" \
  --fixtures "$BENCH_FIXTURES" --root "$EDITOR_RESULTS" --runs 3 \
  --sources 32 --workspaces 1 --samples 3 --edits 2 --fixture-names real --modes main after
python benchmarks/workspaces/verify.py "$EDITOR_RESULTS" "$EDITOR_VERIFICATION"
python benchmarks/workspaces/summarize.py "$EDITOR_RESULTS" "$EDITOR_SUMMARY"
```

`main` and `after` here are baseline JVMD and candidate JVMD. Do not use the legacy
JDTLS-labelled comparison table for those modes. The PR merge-review workflow
keeps its separate JVMD/JDTLS comparison and attaches `--input-validation` to the
existing report/dashboard. JVMD-only work counts remain a separate component
section and do not contribute to JDTLS win counts.

Write raw records, profiles and logs to an ignored `target/` directory or an external
results directory. To independently verify an archived LSP run, extract it into a
new directory and pass it to `benchmarks/workspaces/verify.py`. Original paths in
manifests are provenance; the verifier relocates generated workspace sources.

Each build manifest also records dependency hashes. The original before/after
measurements used identical dependency versions; future upgrades must build the
baseline with its own dependencies, not substitute candidate libraries.
