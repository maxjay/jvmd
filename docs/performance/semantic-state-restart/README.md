# Restart benchmark report — 2026-09-20

**Decision: do not retain the first candidate.** Production on `architecture/semantic-state`
is restored to `ae23fd1f44573bd3427c0e77f967150173669a2f`. The benchmark suite,
CI workflow, checklist and evidence remain. No production speedup or semantic Merkle
implementation is claimed for the restarted branch.

The candidate is preserved at [`2a3beb3`](https://github.com/maxjay/jvmd/commit/2a3beb3750b878264c76525f02bd9e35f157b5d0)
on `experiment/source-identity-ownership`. It removed one duplicate navigation hash cache
and ten net production lines, with readable names/formatting and existing safeguards intact.
It passed all 29 selected tests, including four new ownership/provider tests. The restored
baseline passes its 25 selected existing tests. Experimental code and its new tests remain
on the experiment branch together; they are not advertised as implemented on the working branch.

## Measurement and decision

Ten baseline JVMs were measured before production edits. A separate profile identified
duplicate hashing, and the [replacement criteria](ownership.md) were recorded in `7be9e84`.
The candidate was compared in ten alternating JVM pairs, then the one additional ten-pair
campaign permitted by the [benchmark contract](../../../benchmarks/semantic-state/README.md).
All 20 pairs are combined below. Every workload correctness assertion passed.

Cold-navigation allocation meets the primary target, with an approximately 19% reduction.
The latency acceptance gate remains unresolved for startup-workspace body/API edits,
128-file body/API edits, and real-module location edits: their paired intervals still allow
a material regression under the predeclared limits. This is not proof that each case is
slower; it is insufficient evidence to retain the change under those limits. No extra
sampling or relaxed thresholds were used to manufacture a pass.

## Latency — all scenarios

Each JVM contributes its median per scenario. The before/after columns are medians across
20 JVMs each. Percentage and interval summarize **paired ratios**, so they need not equal
the ratio of those two medians. Negative means lower elapsed time. Exact ranges, sample
p95 and allocation for every scenario are in [comparison-summary.json](comparison-summary.json).

| Scenario | Before ms | Candidate ms | Paired change | Paired 95% interval |
| --- | ---: | ---: | ---: | ---: |
| cold jvm 32/cold workspace | 749.059 | 756.274 | +1.1% | -2.6% to +5.0% |
| cold jvm 32/warm | 2.455 | 2.351 | -4.8% | -13.2% to +3.0% |
| cold jvm 32/body edit | 26.990 | 29.748 | +10.3% | -2.4% to +19.0% |
| cold jvm 32/api edit | 59.727 | 67.955 | +8.7% | +1.4% to +20.3% |
| cold jvm 32/rename preview | 5.321 | 6.220 | +9.5% | +4.2% to +16.7% |
| navigation 128/cold workspace | 292.041 | 268.684 | -7.2% | -15.6% to +6.3% |
| navigation 128/warm | 2.470 | 2.732 | +5.9% | -5.4% to +14.2% |
| navigation 128/body edit | 22.697 | 24.441 | +5.9% | -2.1% to +20.6% |
| navigation 128/api edit | 50.168 | 53.766 | +11.8% | -2.5% to +18.4% |
| navigation 128/rename preview | 2.346 | 2.788 | +22.7% | +6.1% to +33.7% |
| navigation 512/cold workspace | 636.691 | 614.781 | -3.6% | -7.5% to +2.6% |
| navigation 512/warm | 4.571 | 4.285 | -9.4% | -13.1% to -4.5% |
| navigation 512/body edit | 43.349 | 41.165 | -8.5% | -11.6% to +2.1% |
| navigation 512/api edit | 65.366 | 63.593 | -4.7% | -8.1% to +5.9% |
| navigation 512/rename preview | 4.098 | 4.375 | +6.1% | +3.1% to +20.0% |
| real core/cold workspace | 967.420 | 969.691 | +1.8% | -4.0% to +7.7% |
| real core/warm | 0.248 | 0.230 | +0.1% | -4.3% to +13.1% |
| real core/position edit | 43.953 | 47.569 | +3.7% | -8.2% to +17.7% |
| validation 512/precise epoch | 0.001 | 0.001 | +2.1% | -1.8% to +5.5% |
| validation 512/coarse disk | 1.141 | 1.321 | +11.3% | -8.5% to +25.7% |

The precise-epoch mechanism is around one microsecond; rounding in this table is not
a zero-cost claim. Its outcomes are intentionally detached test data. Other workloads
use production javac; the 128/512-file cases use the production dispatcher. The real
module is all 23 baseline JVMD core source files, not a large enterprise workspace.

## Allocation and work

| Cold navigation | Before MB | Candidate MB | Paired change | Paired 95% interval |
| --- | ---: | ---: | ---: | ---: |
| 128 files | 44.57 | 35.97 | -19.29% | -19.33% to -19.26% |
| 512 files | 183.20 | 148.14 | -18.99% | -19.06% to -18.95% |

MB means 1,000,000 bytes. Allocation is JVM-wide, including concurrent application
activity. This is not retained heap. The removed `WorkspaceBindings.hash` allocation
path disappears from the after-profile; the attribution files contain sampled weights,
not exact allocation totals. Compiler and global navigation aggregation work remain.

Both variants reanalyse one file for the body edit and 17 for the API edit. These counts
come from changes in cumulative `files_reanalysed`; verification requests reset the
`last_reanalysed_files` status field to zero. Source enumeration and full navigation
aggregation are still present. No semantic Merkle propagation benefit was implemented.

- Before: median peak RSS 470.25 MiB; median total reported GC time 95 ms per worker.
- Candidate: median peak RSS 485.98 MiB; median total reported GC time 92 ms per worker.

RSS includes all workloads, warmup, checks and native memory through the last workload.
The small GC/RSS differences do not establish a retained-memory improvement.

## Evidence and limits

- [Initial baseline: exact builds and all samples](baseline.json.gz), [baseline summary](baseline-summary.json).
- [All 40 comparison JVMs: exact inputs and samples](comparison.json.gz), [comparison summary](comparison-summary.json).
- [Before profile attribution](profile-before.json), [after profile attribution](profile-after.json).
- [Validation commands/results](validation.json), [candidate test output](candidate-tests-v3.log.gz), [restored-baseline test output](restored-tests.log.gz).

The campaign manifests preserve build hashes and the dirty source list used to compile
the candidate. Its two changed production files were verified against those recorded
hashes before being archived in `2a3beb3`. The fixture and third-party dependency bytes
are identical for both builds. Raw profiled timings do not enter the latency comparison.

This is one Linux container with a fixed Temurin 25.0.4.1+1 runtime and 1 GiB maximum
heap. There is no isolated-host, OS cold-cache, LSP transport, enterprise multi-module
or tail-latency claim. Synthetic startup behaviour and a 23-file real module do not
close broader architecture acceptance. The dedicated CI workflow records another host
campaign when run; its result must be reported separately, not pooled silently.

## Remaining work

Benchmark-led execution is now established. The first production candidate is unaccepted.
Keep S3 and E1–E5 open. The next replacement must address actual duplicate compiler/
publication work and have a workload that measures it before implementation. Semantic
Merkle ownership roots and consumer integration remain required; do not add root hashes
while preserving the scans, representations and invalidation paths they should replace.
