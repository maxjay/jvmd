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

## Next target

After source-key optimization, warm completion misses are dominated by the compiler query rather than cache validation, focusing, or docs. The next useful measurement is to split the normal javac query into parse / enter / analyze(FLOW) / callback/setup phases before attempting another compiler-path optimization.
