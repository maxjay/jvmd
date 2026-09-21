# Completion phase profile — 2026-09-20

This note records the focused completion measurements used to choose the next editor-performance work on PR #7. These are small CI fixtures, not a replacement for the full JVMD/JDTLS matrix.

## Instrumentation

Completion now records per-request and cumulative time for:

- cache-key validation;
- source refresh;
- focus parsing;
- compiler query;
- candidate discovery;
- row materialization;
- documentation lookup;
- sorting;
- cache admission/serialization;
- final prefix filtering;
- total request time.

The prefix harness also records the completion profile per sample.

## Source-key baseline

Before replacing per-request source inventory/hash validation, the 128-source fixture measured:

| request | key ms | total ms |
| --- | ---: | ---: |
| initial `ge` miss | 11.754 | 42.742 |
| `get` narrowing hit | 3.376 | 3.431 |
| `getP` narrowing hit | 3.232 | 3.279 |
| `g` broadening miss | 1.978 | 8.085 |

The key dominated narrowing hits.

## Watch-generation source key

The normal path now uses a source-root watch generation plus bounded open-document hashes and the token-stripped active-source identity. If source watching is unavailable/unreliable, completion keeps the conservative exhaustive source validation path.

On the 128-source fixture after that change:

| request | key ms | total ms |
| --- | ---: | ---: |
| initial `ge` miss | 0.144 | 27.095 |
| `get` narrowing hit | 0.099 | 0.150 |
| `getP` narrowing hit | 0.080 | 0.124 |
| `g` broadening miss | 0.076 | 5.870 |

That is about a 97% reduction in key-validation cost on the narrowing hits and removes the previous >256-source cache cutoff on reliable watched source roots.

The 300-source fixture remained flat:

| request | key ms | total ms |
| --- | ---: | ---: |
| `get` narrowing hit | 0.117 | 0.171 |
| `getP` narrowing hit | 0.105 | 0.152 |
| `g` broadening miss | 0.061 | 7.507 |

Correctness regressions cover:

- other unsaved source changes;
- timestamp-preserving closed-source edits;
- new source files;
- non-source filesystem churn;
- conservative behavior for coarse/unreliable source roots;
- timestamp-preserving JAR replacement/deletion;
- completion reuse above 256 source files.

## Documentation

Documentation is not the current completion bottleneck. In the warm broadening misses it was roughly 0.03 ms in these fixtures. JVMD therefore keeps documentation in completion rows for agent-facing semantic richness.

## ATTR-only experiment

A completion-only path that stopped after javac attribution and skipped FLOW was implemented and tested. Completion rows matched the normal FLOW path across basic member access, pattern variables, definite-assignment-sensitive source, and switch patterns.

An isolated alternating A/B benchmark on the same code measured 20 timed samples after warmup:

- ATTR median: **0.811 ms**
- FLOW median: **0.845 ms**
- ATTR compiler time across 25 queries: **35.568 ms**
- FLOW compiler time across 25 queries: **35.745 ms**

The median difference was only about 4%, while aggregate compiler time was effectively identical. The ATTR path also required an additional `jdk.compiler/com.sun.tools.javac.comp` export and more javac-internal implementation coupling.

Decision: **keep the normal FLOW path**. The ATTR-only implementation and its extra export were removed.

## Normal javac phase split

Temporary probes split the normal completion query into release preparation, parse, ENTER, analyze(FLOW), callback, and task overhead. They were removed after collecting the measurements so the production path keeps only the lower-overhead completion timings above. The probe build also coincided with repeatable strict-AOT cache rejection in CI, so retaining those probes was not justified.

Warm broadening misses showed:

| sources | compiler query ms | parse ms | ENTER ms | analyze(FLOW) ms | callback ms | task overhead ms |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 24 | 5.101 | 0.457 | 2.166 | 0.749 | 1.057 | 0.450 |
| 128 | 5.837 | 0.343 | 3.220 | 0.631 | 1.034 | 0.387 |
| 300 | 7.777 | 0.533 | 4.976 | 0.702 | 0.926 | 0.380 |

The first miss in a fresh compiler context is more expensive, but has the same shape: ENTER and task setup dominate, while FLOW remains small.

## Source package catalog and package cardinality

The branch already has a watcher-backed source package catalog. On reliable precise source roots it inventories source paths once and serves later `SOURCE_PATH` package listings from that catalog; coarse/unreliable roots retain the standard-file-manager fallback. The completion fixture showed exactly one catalog build and zero watcher events across each four-request sequence.

The apparent ENTER scaling in the original fixture was largely a **package-cardinality** effect because every generated source lived in the default package:

| layout | sources | warm `g` compiler query ms | source entries returned per warm package listing |
| --- | ---: | ---: | ---: |
| default package | 24 | 3.266 | 24 |
| default package | 128 | 3.931 | 128 |
| default package | 300 | 5.672 | 300 |
| 31 packages | 300 | 2.973 | 2 |

The distributed 300-source fixture has the same total workspace size but only `Api` and `Use` in the target package. Its narrowing hits remained about 0.08–0.10 ms total and its warm broadening miss was about 3.14 ms total.

This means the remaining ENTER cost is not evidence of a workspace-wide filesystem scan. Javac still has to complete the relevant package and its source symbols, so unusually large packages naturally cost more.

The source-catalog refactor also exposed a correctness bug in the conservative path: open documents were overlaid only when the fast catalog was active. Coarse/plain workspaces therefore missed unsaved changes and new unsaved source files. The overlay is now applied after both the indexed and fallback listings, with a focused regression in `IndexedFileManagerTest`.

## Next target

Do **not** deepen javac internals just to optimize the synthetic 300-class default package. Warm completion in a normally partitioned 300-source workspace is already around 3 ms on a cache miss and under 0.1 ms on narrowing hits.

The next completion work should target a user-visible structural gap rather than another sub-millisecond compiler experiment: request cancellation/coalescing for stale keystrokes, followed by completion-list semantics such as unimported-type/index-backed suggestions. Revisit ENTER only if real-project traces show large-package completion dominating latency.
