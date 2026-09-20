# Semantic state measurements — 2026-09-20

Status: correctness foundation implemented; overall performance acceptance remains open.
Baseline is `ae23fd1f44573bd3427c0e77f967150173669a2f` on `perf/compact-grams-merge`.
The first implementation is local `fb2ff0d`, remote `46b947ec`.
`profiled.json` is the subsequent working tree with phase counters, source-root normalization,
and contracts captured only for their owning fragment. The final metadata-context fallback
was added after that profile; this fixture has no package/module descriptors.

## Method

Pinned Temurin 25.0.4.1+1, direct javac compilation, production classes and previously packaged
runtime dependencies, `-Xmx1g`. Each JSON is one JVM running three fresh workspaces at each
of 128 and 512 files, with cold, warm, body-edit and API-edit requests in that order. The first
workspace also includes JVM warmup. Sources and state are temporary local directories.
The request is `symbol.references`; all expected incoming references and semantic tier are checked.
Fixtures have one API file and 16 consumers; remaining files are independent. No AOT or JFR.
Maven and modular/runtime validation are performed by the PR checkpoint workflow.

These are sequential synthetic runs, not paired randomized trials on a dedicated performance
host. Raw values are retained. Host/JIT variation is material: instrumentation's second run
is faster despite adding counters. Do not interpret that difference as a proven optimization
or extrapolate it to real workspaces. Warm requests here use the conservative coarse-root path;
they do not measure the precise-root watcher shortcut.

## End-to-end request medians, milliseconds

| Files | Request | Baseline | First implementation | Profiled follow-up |
| --- | --- | ---: | ---: | ---: |
| 128 | cold | 226.60 | 534.84 | 286.16 |
| 128 | warm | 2.34 | 4.42 | 3.75 |
| 128 | body | 25.59 | 32.79 | 13.70 |
| 128 | api | 59.59 | 70.21 | 42.43 |
| 512 | cold | 599.32 | 1216.97 | 682.60 |
| 512 | warm | 4.42 | 16.59 | 6.10 |
| 512 | body | 56.13 | 39.08 | 33.82 |
| 512 | api | 63.14 | 95.13 | 76.37 |

The first implementation improves the 512-file body-edit median but regresses cold, warm,
and API requests. The follow-up reduces those observed costs without establishing a controlled
cause. There is no overall speedup claim and no statistical tail-latency claim from three samples.

## Work actually reused

Both implementation runs report the same structural work:

- Warm requests: zero attribution, serialization or navigation replacement.
- Body edit: one attributed fragment, one replaced file contribution, 9,309 serialized bytes.
- API edit: 17 attributed fragments/contributions, 137,463 serialized bytes.
- After all phases: navigation replacements are 146 at 128 files and 530 at 512 files.
- Current estimated admission weights after the API phase: 3,319,868 and 12,701,756 bytes.

The baseline already reattributed few files, but then rebuilt the entire navigation aggregate
and serialized it. New counters distinguish that old global maintenance from fragment updates.
The admission numbers are estimates, not retained-heap measurements; no peak-RSS reduction
is claimed. Source enumeration and validation are still workspace-sized on this path.

## Profiled phase deltas at 512 files, medians in milliseconds

Counters are cumulative; the table subtracts the preceding request before taking medians.

| Phase | Enumeration | Input validation | Loader/attribution | Fragment JSON | API hashes | Navigation |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| cold | 3.74 | 10.25 | 568.39 | 30.35 | 7.46 | 15.56 |
| warm | 1.87 | 2.81 | 0.00 | 0.00 | 0.00 | 0.00 |
| body | 3.28 | 5.08 | 22.35 | 0.14 | 0.04 | 0.46 |
| api | 4.39 | 7.21 | 58.27 | 1.61 | 0.42 | 4.46 |

The loader dominates cold/API attribution; persistent posting updates are a smaller component.
One body edit spends well below 1 ms in navigation maintenance in this follow-up. Context setup,
text reads and response work are outside these counters. Medians of components need not sum
to the median of total latency.

## Correctness and remaining gates

The baseline reproduces `local_rename_api_equal=false` and
`publication_race_stale_reuse=true`. Both implementation runs reverse those results.
The standalone regression runner adds deterministic warm/peek races, persistent-map oracle
checks, old-reader isolation, interner collisions, complete/partial dependency capture,
classpath ordering, budgets, buffers, hierarchy, rename and clean-analysis agreement after
negative-lookup recovery. A package-annotation context regression was added after profiling.

Before calling this performance-ready: run paired before/after workloads on representative
projects, measure allocation and retained heap, separate source validation from compiler work,
and address cold/warm/API regressions or justify the measured tradeoff. Preserve correctness
checks and conservative fallback while doing that work.

Raw evidence: [baseline](before.json), [first implementation](after.json),
[profiled follow-up](profiled.json). Runner: [run.sh](../../../benchmarks/semantic-state/run.sh).

## Final inheritance-corrected implementation

The final capture includes private ancestors whose members escape through accessible subtypes,
with contract schema v2 and diagnostic schema v4. The same probe passes again; `final.json`
retains the new phase counters and all samples. The non-paired measurement limitations above
still apply. The final local regression runner passes 5,150 assertions, including actual
consumer invalidation after an inherited member changes return type.

| Files | Cold | Warm | Body edit | API edit |
| --- | ---: | ---: | ---: | ---: |
| 128 | 297.81 | 7.66 | 15.94 | 45.63 |
| 512 | 825.94 | 9.68 | 34.64 | 92.42 |

Raw final evidence: [final.json](final.json). Earlier measurements remain above as history.
The final run serialized 9,289 bytes for the body fragment and 137,178 for the API update.
These sizes include temporary absolute paths, so their small difference from earlier runs
does not establish reduced data-structure allocation.
