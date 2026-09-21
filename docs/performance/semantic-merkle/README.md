# Semantic Merkle checkpoint — implemented, not accepted

Baseline: `ae23fd1f44573bd3427c0e77f967150173669a2f`.
Current measured production: `ad9ce2ab6f08b72b2744884d08331928edc48953`.
[Draft PR #8](https://github.com/maxjay/jvmd/pull/8) remains unmerged.

The latest change consolidates overview into Bindings' declaration capture, removes its
second compiler-tree traversal, and stops constructing an API Merkle graph that overview
immediately discarded. Reference lookup now filters before sorting and avoids a temporary
result/copy. It preserves unresolved declarations, depth, sparse protocol fields, current
positions and documentation. These are shared-code changes, not shortened formatting.

The existing semantic ownership graph and accumulator extension boundary remain intact.
Declaration/type/file/module/workspace identities compose through immutable keyed nodes;
API-equal edits still refresh references, diagnostics, positions and documentation. Consumers
use the captured API identity. Ordered classpaths and other ordered semantic fields remain
ordered. `SemanticNode.withChild` exposes old/new keyed membership; a future accumulator can
replace aggregate calculation there with a distinct algorithm/schema identity. There is no
new parser, global interner, accumulator cryptography or Merkle database in this change.

## Before/after evidence

Twenty alternating independent JVM pairs (initial ten plus predeclared confirmation), all
forty workers passing output assertions, cover 37 scenarios. Pinned Temurin 25.0.4.1+1,
identical dependencies, fixtures and harness, serial execution, no AOT. The recorded build
was made in a dirty worktree; every one of its 104 production source hashes was subsequently
verified against `ad9ce2a`. That verification is retained with the raw manifests.

These are fresh measurements on the recovered host. They cannot be combined with the prior
host's rounded observations. Changes below are medians of paired ratios, not ratios of the
separately displayed medians. Intervals are 95% paired-bootstrap intervals.

| Workload | Baseline ms | Current ms | Paired change [95% interval] |
| --- | ---: | ---: | ---: |
| 512-file body edit | 137.455 | 53.439 | −61.7% [−64.5, −56.6] |
| 512-file API edit | 193.681 | 116.808 | −37.9% [−44.9, −32.9] |
| 512-file warm navigation | 20.001 | 10.852 | −46.2% [−51.2, −35.7] |
| Real core source position edit | 123.350 | 20.699 | −84.1% [−85.1, −82.2] |
| Overview cold, after preceding suite work | 106.547 | 97.506 | −12.9% [−23.2, −2.6] |
| **32-file warm navigation** | **6.898** | **8.160** | **+22.0% [−7.6, +36.4]** |
| **512-file cold workspace** | **2035.591** | **2178.496** | **+4.7% [+0.7, +14.7]** |

The primary latency/allocation targets pass. Overall no-regression acceptance remains open:
small-query and several lifecycle intervals still permit material regressions. Confirmation
is complete; additional repetitions are not used to search for favourable results.

[All 37 latency and allocation scenarios, sample p95 and worker resources](ad9ce2a-measurements.md)
are reported without omitting slower or inconclusive cases. Sample p95 describes worker
medians, not production request-tail latency. Socket/LSP transport, Maven resolution and
JDTLS comparisons are outside these timings.

512-file body-edit allocation is **18.358 → 7.741 MB (−57.7%)**; API-edit allocation is
**25.019 → 14.294 MB (−42.9%)**. Overview position-edit allocation versus the original baseline
is **7.968 → 5.101 MB (−36.0%)**. These are decimal MB allocated per request, not retained heap.

The separate overview-only campaign compares the preceding published production with this
consolidation: edit allocation **10.272 → 5.197 MB (−49.4%)**, but first-five cached latency
**1.281 → 2.048 ms**, paired **+69.4% [+36.4, +115.6]**. That startup-sequence regression is
retained explicitly. Its cold/edit latency intervals overlap no change. These samples are
not pooled with the full suite, whose overview workload occurs after other requests.

## Retention and code complexity

Three separate forced-GC pairs use 256 files, twelve edits and thirteen reader snapshots:

| Whole-JVM live heap | Baseline MiB | Current MiB |
| --- | ---: | ---: |
| After initial snapshot | 25.690 | 30.249 |
| With thirteen readers | 20.162 | 18.583 |
| After releasing old readers | 17.947 | 18.582 |

**Initial heap increases 17.7% and requires attribution.** These are not object-exact graph
sizes. Other compiler/runtime state can be collected between phases. Lower heap with old
readers does not waive the initial increase or establish a universal memory improvement.

The same Google Java Format 1.30.0 audit counts all affected handwritten production sources,
including helpers and deleted classes: original baseline **5,404**, previous candidate
**6,304**, current **6,234**. This consolidation removes **70** readable lines, but the overall
change remains **+830 lines (+15.4%)**. The user's net code-reduction requirement is unmet.

## Correctness and the next blocking work

The focused local selection passes **35/35**. The two new overview contract tests also pass
against the untouched baseline. The broader local selection passes **24/29**: four failures
are missing runtime/output/network test setup, and one is stale completion after a preserved
mtime edit. All failure logs remain available.

For `ad9ce2a`, [Checkpoints](https://github.com/maxjay/jvmd/actions/runs/35583766479) and
[Distributions](https://github.com/maxjay/jvmd/actions/runs/35583766468) passed. The
[dedicated benchmark workflow](https://github.com/maxjay/jvmd/actions/runs/35583766553)
failed `CompletionPrefixCacheTest.changedReleaseAndNewSourceNamesCannotReuseOldCandidates:85`;
its forty other selected tests passed. That failed precheck prevented CI timing measurements.
A passing run of the intermittent test elsewhere does not establish a fix.

`DelayedCompletionProbe` deliberately withholds watcher delivery after the first query.
Both the baseline and current production reproduce two distinct failures:

- A changed dependency returns the old completion through a cache hit.
- A newly created source remains missing even after completion recomputes; its source
  catalogue is stale. Refusing empty completion-cache admission alone cannot fix this.

This controlled probe establishes a validation failure mechanism, not a latency result or
proof that every historical failure has one cause. A watcher epoch only describes delivered
notifications. A semantic root only describes observed semantic inputs. Neither establishes
that the filesystem has not changed. The next correctness change needs a completion baseline
and explicit source/namespace input validation, including negative lookups, while preserving
prefix-reuse latency. No watcher sleeps, retries-until-green or weaker assertions are a fix.

## Reproduction and retained artifacts

Use [the benchmark contract](../../../benchmarks/semantic-state/README.md). Combine the two
unchanged campaigns with:

```sh
python benchmarks/semantic-state/report.py \
  "$INITIAL/campaign.json" "$CONFIRMATION/campaign.json" --output "$REPORT"
```

The report command rejects incompatible builds/harnesses/fixtures and incomplete pairs.
[recovery/manifest.json](recovery/manifest.json) records SHA-256 and size for every evidence
file. The archive includes combined raw samples/builds, worker commands/logs, retained-heap
samples, per-scenario summaries, source verification, same-formatter counts, correctness
failures and selected diagnostic outputs. Full JFR recordings are not published.

[Historical candidate 227598c](historical-227598c.md) preserves the previous host's rounded
observations and its interrupted raw-evidence publication. Newly regenerated campaigns are
labelled separately; the original missing raw files have not been recovered.
