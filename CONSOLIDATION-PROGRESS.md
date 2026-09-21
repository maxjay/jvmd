# File semantic contribution consolidation

Source of truth: the current implementation task requested in chat. Older architecture/checklist documents are historical context only and do not define this branch's scope.

Base: `03591aeef674b67f131065048a50543d78aca855` (current `main` at branch creation)
Branch: `refactor/file-semantic-contribution`
Validated production head: `c3e23e9ee4c0a4b19e3ce1a58e40600b9e63373d`

## Objective

Consolidate the duplicated per-file persistence/invalidation payload behind one authoritative typed
`FileSemanticContribution`, and delete the parallel semantic representations rather than layering
another model on top.

The canonical contribution owns:
- normalized source file identity;
- source/content identity;
- API identity;
- dependencies;
- exported names;
- unresolved names.

This phase deliberately does not redesign navigation postings or the physical source index. Those are
separate changes with separate benchmarks.

## Result

The production path now constructs one `FileSemanticContribution` in the analyzer and carries that
same value through asynchronous source publication into Rocks persistence/invalidation.

Deleted ownership:
- `RocksSemanticInvalidation.FileInput`;
- private `RocksSemanticInvalidation.Stored`;
- Delta -> FileInput conversion;
- Stored -> FileInput conversion;
- the public full-module semantic mutation surface used only by the old representation/tests;
- the partial/index-only semantic publication mode and blank sentinel state.

The Rocks codec serializes/deserializes the canonical contribution directly. Incremental
`observeFile` also loads the module semantic map once rather than loading it and then immediately
loading it a second time through the old bulk-update path.

One remaining limitation is explicit: invalidation still materializes the module's persisted
contributions once to rebuild reverse dependency/unresolved views. This phase removes duplicate
ownership and the duplicate load; replacing the remaining O(module) derived-view rebuild is a
separate storage/index change, not silently bundled into this consolidation.

## Production size

Exact same counting rule on `src/main/java/**/*.java`:

| revision | files | physical LOC |
| --- | ---: | ---: |
| base `03591aee` | 107 | 12,048 |
| consolidated code head `c3e23e9e` | 108 | 12,044 |

Net production change: **-4 physical lines** despite adding the named canonical type.

The more important structural count for this payload is:

```
before: SourceIndexPublisher.Delta fields
        + RocksSemanticInvalidation.FileInput
        + RocksSemanticInvalidation.Stored

after:  FileSemanticContribution
```

Authoritative semantic payload representations: **3 -> 1**.

## Before benchmark

The branch was opened production-identical to main (only this progress document differed), so the
normal PR Checkpoints suite established the before measurement before any production edits.

Baseline Checkpoints: run `35612641135`, **success**.

128-file `WorkspaceBindingsIncrementalBenchmarkTest`:

| operation | phase | request ms | binding computations | javac queries |
| --- | --- | ---: | ---: | ---: |
| references | cold | 329.643 | 128 | 4 |
| references | warm | 3.735 | 0 | 0 |
| references | body edit | 29.782 | 1 | 1 |
| references | API edit | 70.984 | 17 | 2 |
| rename | cold | 201.330 | 128 | 4 |
| rename | warm | 3.595 | 0 | 0 |
| rename | body edit | 24.151 | 1 | 1 |
| rename | API edit | 56.948 | 17 | 2 |

## After benchmark and correctness

Code-head Checkpoints: run `35614081275`, **success**.
Code-head Distributions: run `35614081317`, **success**.

The same 128-file benchmark on the consolidated code:

| operation | phase | request ms | binding computations | javac queries |
| --- | --- | ---: | ---: | ---: |
| references | cold | 595.759 | 128 | 4 |
| references | warm | 2.335 | 0 | 0 |
| references | body edit | 20.861 | 1 | 1 |
| references | API edit | 39.672 | 17 | 2 |
| rename | cold | 129.660 | 128 | 4 |
| rename | warm | 2.697 | 0 | 0 |
| rename | body edit | 27.861 | 1 | 1 |
| rename | API edit | 31.352 | 17 | 2 |

The deterministic work invariants are unchanged:
- unchanged warm query: **0 binding computations, 0 javac queries**;
- body edit: **1 file, 1 javac query**;
- API edit: **17-file dependency closure, 2 javac queries**;
- both references and rename preserve those counts.

The latency samples are single CI observations and move strongly in both directions (for example,
references cold is slower while rename cold is much faster). They are recorded, not treated as a
statistically supported speedup/regression claim. This phase is accepted for ownership/complexity
consolidation, not a latency claim.

## Commits

- `5c04273d` — define this consolidation scope from current main.
- `ccde6696` — introduce the canonical contribution and remove Rocks duplicate payload types.
- `9b2af2fb` — move focused invalidation tests onto the canonical contribution.
- `dad30cc8` — remove duplicate semantic mutation/compatibility surfaces.
- `c3e23e9e` — require complete semantic publications and remove partial sentinel state.

## Acceptance

- [x] Started from exact current main.
- [x] Benchmark captured before production edits.
- [x] One authoritative per-file semantic payload replaces parallel representations.
- [x] Focused invalidation semantics preserved.
- [x] Warm/body/API compiler-work invariants preserved.
- [x] Full Checkpoints pass on the production code head.
- [x] Cross-platform Distributions pass on the production code head.
- [x] Production Java LOC decreased.
- [x] Remaining module-wide derived-index rebuild is explicitly left for a separately benchmarked task.
