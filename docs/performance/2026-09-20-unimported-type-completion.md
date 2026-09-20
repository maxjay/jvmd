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

## After

Pending implementation. The same benchmark will be rerun unchanged.
