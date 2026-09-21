# Semantic Merkle — 32-file warm-navigation follow-up

Measured production: [`09326b20e0610000f4373cbc8ce7dd4e4434cd46`](https://github.com/maxjay/jvmd/commit/09326b20e0610000f4373cbc8ce7dd4e4434cd46).
Original baseline: `ae23fd1f44573bd3427c0e77f967150173669a2f`.
Preceding production: `ad9ce2ab6f08b72b2744884d08331928edc48953`.
[PR #8](https://github.com/maxjay/jvmd/pull/8) remains a draft and unmerged.

The user limited this closing change to 32-file warm navigation. Its response-budget check
now counts Jackson's UTF-8 output through an output stream, removing the temporary byte
array previously allocated solely to obtain its length. Jackson still performs encoding;
this removes buffering and copying, not the serialization traversal. Exact byte limits,
Unicode escaping, continuation behavior and filesystem validation are preserved. The
semantic Merkle graph and its future accumulator extension boundary remain intact.

## Latency first

Two separate ten-pair campaigns use the same unchanged 37-scenario harness, pinned JDK,
dependencies and fixtures. All forty independent workers passed their output assertions.
Workers run serially in alternating order, without profiling or forced GC. The 32-file
case measures the first five cached reference requests after one cold request, with the
same untimed occurrence/status checks between requests. It excludes network transport.

| Comparison / scenario | Before median ms | After median ms | Paired change [95% interval] |
| --- | ---: | ---: | ---: |
| Preceding production → fix, 32-file warm | 7.791 | 6.933 | −16.5% [−21.3, +20.4] |
| Original baseline → fix, 32-file warm | 7.212 | 7.389 | −3.2% [−19.1, +19.8] |
| Original baseline → fix, 512-file warm | 17.657 | 10.300 | −43.9% [−50.6, −32.4] |
| Original baseline → fix, 512-file body edit | 129.402 | 51.470 | −57.5% [−63.1, −51.3] |
| Original baseline → fix, 512-file API edit | 168.656 | 111.509 | −33.4% [−40.1, −25.2] |
| Original baseline → fix, 512-file cold | 2036.471 | 2017.013 | +1.2% [−4.7, +11.8] |

Paired change is the median of within-pair ratios. It can differ in sign from the ratio of
separately displayed medians. The 32-file median is 11.0% lower than preceding production
in its comparison and 2.5% above the original baseline in the independent comparison.
The previous 22% observed regression is no longer present at the point estimate. **The
wide intervals still allow a material regression: a statistically conclusive latency
improvement or non-regression is not established.** No extra repetitions were selected
for favourable results. The larger-workspace edit and warm gains remain clear.

All other scenarios remain visible in the full tables. In the comparison with preceding
production, 32-file rename has a +8.6% paired change [+0.4, +69.3]; it is not hidden or
claimed fixed. Other broad acceptance uncertainties remain in the historical report.

## Allocation, correctness and scope

| 32-file warm allocation | Before bytes/request | After bytes/request | Paired change |
| --- | ---: | ---: | ---: |
| Preceding production → fix | 318,728 | 273,200 | −14.3% |
| Original baseline → fix | 321,888 | 273,648 | −15.0% |

- Exact published build: 33/33 focused tests pass. Six response-budget/MCP tests, including
  the new 4096/4097-byte ASCII/Unicode/escape boundary test, also pass on the original baseline.
- All 104 production files and three compilation helpers match the recorded final build.
- [Checkpoints 35587932473](https://github.com/maxjay/jvmd/actions/runs/35587932473) and
  [Distributions 35587932467](https://github.com/maxjay/jvmd/actions/runs/35587932467) pass.
- [Dedicated benchmark CI 35587932643](https://github.com/maxjay/jvmd/actions/runs/35587932643)
  fails the existing timestamp-preserving source completion precheck (40/41 tests pass),
  before timing. Its failure extract is retained. Local paired campaigns completed separately.
- Same-formatter ResponseBudget count: 326 → 346 lines. The counter adds twenty readable
  lines and no persistent state. The original overall net-code-reduction goal remains unmet.

The requested implementation/benchmark pass is closed here. Completion validation, initial
heap attribution and broader code consolidation are deferred; the older global acceptance
gates remain open. No additional architectural change or merge is implied.

## Reproducible evidence

- [All 37 scenarios against preceding production](warm-navigation/previous-measurements.md)
- [All 37 scenarios against the original baseline](warm-navigation/original-measurements.md)
- [Raw campaigns, commands, logs, tests and diagnostics](warm-navigation/evidence.zip)
- [Published-source verification](warm-navigation/source-verification.json)
- [Evidence checksums](warm-navigation/SHA256SUMS.json)
- [Previous architecture and acceptance checkpoint](historical-ad9ce2a.md)

The archive also retains all eighteen pilot workers and diagnostic stage traces. Neither
is pooled into the acceptance campaigns. Each campaign contains immutable build manifests,
per-request samples, allocation, worker resources and harness hashes. Full tables include
sample p95 of worker medians, which is not a production request-tail estimate. The existing
`benchmarks/semantic-state/report.py` reproduces either table from its archived campaign.
