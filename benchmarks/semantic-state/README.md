# Semantic-state benchmark contract

Baseline: `ae23fd1f44573bd3427c0e77f967150173669a2f`. Establish the baseline before
production edits; compare it again with each candidate on the same machine. Keep
every successful sample and failed attempt. The prior three-run experiment is
archived and is not the baseline for this campaign.

## Reproduction

Prerequisites: JDK 25, Python 3.11+, Linux `/proc` memory accounting, and built JVMD runtime
dependencies. The builder excludes packaged `jvmd-*.jar` files so stale production
classes cannot fill gaps in either build. It reuses the repository's compilation
harness and records source, class, dependency and benchmark hashes. No AOT.

```sh
git worktree add --detach "$BASELINE_REPO" ae23fd1f44573bd3427c0e77f967150173669a2f
python benchmarks/semantic-state/suite.py build --repo "$BASELINE_REPO" \
  --java-home "$BENCH_JDK" --dependencies "$RUNTIME_JARS" --output "$BEFORE_BUILD"
python benchmarks/semantic-state/suite.py run --before "$BEFORE_BUILD/build.json" \
  --fixture "$BASELINE_REPO/jvmd-core/src/main/java" --pairs 10 --output "$BASELINE_RESULTS"
# Only after reviewing the baseline and recording the proposed replacement:
python benchmarks/semantic-state/suite.py build --repo "$PWD" \
  --java-home "$BENCH_JDK" --dependencies "$RUNTIME_JARS" --output "$AFTER_BUILD"
python benchmarks/semantic-state/suite.py run --before "$BEFORE_BUILD/build.json" \
  --after "$AFTER_BUILD/build.json" --fixture "$BASELINE_REPO/jvmd-core/src/main/java" \
  --pairs 10 --output "$COMPARISON_RESULTS"
```

Output directories must be new. Builds, fixtures, heap settings and harness must
match. Workers run serially: before/after, then after/before. Each worker starts a
fresh JVM and owns fresh source/state directories. The 32-file startup workload
runs first and supplies the same explicit warmup before the larger workspaces.
Cold workspace and cold JVM numbers are separate. File-system page caches are
not flushed; this is not a cold-storage benchmark.

## Workloads and measurement boundaries

| Workload | What is timed | Required output |
| --- | --- | --- |
| Cold JVM, 32 files | First dispatcher navigation request | All 16 callers |
| Navigation, 128 and 512 files | Cold workspace, five warm requests, three body/API edit cycles, rename previews | Exactly 16 callers, 17 current locations, 17 rename files |
| Real JVMD core sources | Production WorkspaceBindings + Analyzer cold/warm and three location edits | No compiler errors, type lookup, full graph agreement with a clean cache |
| 512-file validation mechanism | Twenty precise-epoch hits and twenty coarse disk validations | Zero enumerations for precise epochs; full fallback otherwise |

Navigation uses the production Application and Dispatcher; it does not include
socket/LSP transport or Maven resolution. Verification requests occur outside
timed regions and are identical between revisions; their cache hits are included
in cumulative status counters. The real module uses unchanged baseline sources
and real third-party dependencies, not generated one-line files. It exercises
cache/compiler integration without the dispatcher. The validation mechanism
uses detached empty outcomes and a controlled epoch; it does not simulate or
claim a working native watcher. Other workloads use actual javac.

Per-request elapsed time and JVM-wide allocated bytes are captured. Allocation
includes concurrent JVM/application thread activity. GC count/time and process
peak RSS through the end of the workloads are recorded per worker. Peak RSS includes warmup, correctness checks and
native memory; it is not retained semantic-graph heap. Forced-GC or allocation
profiling must be a separate campaign (`--profile --pairs 1`); profiled timings
cannot enter the latency comparison. JFR files may contain environment details;
publish only selected CPU/allocation event exports when needed.

## Rules fixed before production changes

- Use at least ten independent JVM pairs. Reduce repeated requests within each
  JVM to a median first; those requests are not independent statistical samples.
  Report per-scenario medians, min/max, descriptive sample p95, paired percentage
  changes and a deterministic bootstrap 95% interval over independent pairs.
  Ten samples do not establish production tail latency or cross-host performance.
- Choose and record the primary affected workload before implementation. A speed
  claim requires its paired interval below zero and at least 10% median improvement.
  An allocation claim requires at least 10% lower median allocated bytes with a
  paired interval below zero. Also identify the production work/code removed.
- A candidate must preserve all correctness assertions and must not have an
  unresolved material regression elsewhere. For this first comparison, a material
  latency regression is over 10% and over 1 ms per request; report smaller changes
  too. An interval overlapping that regression boundary is inconclusive, not a pass.
  Allow one additional ten-pair campaign to resolve a concrete uncertainty; retain
  and combine both campaigns. If still inconclusive, keep acceptance open.
- Allocation and process RSS increases over 10% require investigation; fewer source
  lines cannot justify them. No weaker diagnostics, stale answers, removed checks,
  compressed code or renamed variables count as an improvement.
- Time source observation, compiler work and publication separately when diagnosing
  a regression. Broad semantic Merkle adoption requires module/API propagation,
  context changes, negative lookup, reader isolation and retained-memory coverage
  in addition to this initial suite. Passing this suite alone cannot close E1–E5.

`campaign.json` retains exact inputs and every per-request sample. `summary.json`
contains the comparison. Reports must include slower/inconclusive cases as well
as gains. A failed correctness run is a failed candidate, never a timing sample
to silently discard.
