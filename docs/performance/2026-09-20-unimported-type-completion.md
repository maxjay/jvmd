# Unimported type completion — before/after

Measured on GitHub Actions, Temurin 25.0.4.1+1, using the normal `Application -> symbol.completion` path against a resolved Maven dependency already present in the workspace index.

The fixture types `Sa -> Sam -> Samp` for an unimported public dependency type `lib.Sample`. Five cycles produce 15 completion requests. The benchmark is `UnimportedCompletionBenchmarkTest` and writes `jvmd-tests/target/unimported-completion-perf.json`.

## Baseline — 4c425931

| metric | before |
| --- | ---: |
| indexed target | `lib.Sample` |
| completion requests | 15 |
| target suggestions | 0 |
| unimported-type recall | **0%** |
| median request | **2.883 ms** |
| completion-triggered index queries | **0** |
| first cold request | **48.676 ms** |

Representative baseline requests:

- `Sa`: 1 unrelated in-scope candidate, no `Sample`.
- `Sam`: 0 candidates.
- `Samp`: 0 candidates.

The dependency type is independently verified through `symbol.find(scope=deps)` before the timed sequence, so this is a completion-surface gap rather than a missing index entry.

## After — a41e6b1

| metric | before | after | delta |
| --- | ---: | ---: | ---: |
| completion requests | 15 | 15 | — |
| target suggestions | 0 | 15 | **+15** |
| unimported-type recall | **0%** | **100%** | **+100 pp** |
| median request | **2.883 ms** | **2.969 ms** | **+0.086 ms / +3.0%** |
| completion-triggered index queries | **0** | **1** | **+1 total** |
| first cold request | **48.676 ms** | **55.579 ms** | **+6.903 ms / +14.2%** |

After the first prefix query, the completion type cache serves the narrower `Sam` / `Samp` requests without additional index queries. Across all 15 requests, only one completion-triggered index lookup was required.

Representative after requests:

- `Sa`: 2 candidates including `Sample`.
- `Sam`: 1 candidate, `Sample`.
- `Samp`: 1 candidate, `Sample`.

The implementation adds workspace-filtered type-prefix lookup for `class`, `interface`, `enum`, `record`, and `annotation` symbols, merges those with live javac completion results, and retains live-only behavior if the index lookup fails. The LSP layer exposes the indexed candidate with the required import edit.

### Result

The feature changes the measured gap from **0/15 to 15/15 successful unimported-type suggestions** while adding about **0.09 ms to the median request**. The one-time cold request cost increased by about **6.9 ms**, after which narrowing uses the cached indexed candidate set.
