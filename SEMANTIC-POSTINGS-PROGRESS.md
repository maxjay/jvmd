# Incremental semantic postings

Source of truth: this branch and the current implementation task.

Base: `1b376f3c840d25d59604f3f2b76cf3149197e19e` (merged PR #9)
Branch: `perf/incremental-semantic-postings`

## Problem

The 1 GiB corpus gate fails after repeated `symbol.references` calls. Requests begin warm at a few
milliseconds, then degrade into multi-second rebuilds and eventually the test fork dies with
`Java heap space`.

The current persisted invalidation path still performs O(module) work for one-file changes:
- load every persisted `FileSemanticContribution` in the module;
- reconstruct a full in-memory module map;
- rebuild reverse dependency relationships from all contributions;
- scan unresolved-name sets against changed exports;
- rewrite/compare file state through the reconstructed map.

This task treats the memory ceiling as a correctness/performance constraint. Raising the heap is not
an acceptable fix.

## Objective

Make one-file semantic observation and deletion proportional to the changed contribution and its
affected postings, not the number of files in the module.

Persist and incrementally maintain the derived lookup structures needed for invalidation:
- dependency -> dependant files;
- unresolved name -> waiting files;
- per-file contribution needed for old/new diff;
- exported names used to wake unresolved waiters.

The canonical `FileSemanticContribution` from PR #9 remains the semantic authority. Postings are
derived acceleration structures, not a second semantic authority.

## Required invariants

- unchanged warm references: 0 javac queries / 0 files reanalysed;
- body-only edit invalidates only the changed file when API identity is unchanged;
- API edit traverses the actual reverse-dependency closure;
- deletion invalidates dependants correctly;
- newly resolvable exported names wake unresolved waiters conservatively;
- one-file observation does not scan/materialize every module contribution;
- no heap-limit increase;
- no compatibility layer left behind once the new postings path is proven;
- benchmark before production edits and after on the same focused and corpus workloads.

## Baseline

Pending baseline run from this branch before production edits.

Known merged-PR evidence immediately before merge:
- Checkpoints: green;
- Distributions: green;
- Corpus: repeated OOM under 1 GiB;
- references degraded from ~3–5 ms warm into repeated ~4–6 s calls before `Java heap space`.

## Planned checkpoints

- [ ] 0 — branch, clean progress record, baseline gates
- [ ] 1 — instrument semantic invalidation I/O/work counts if current counters are insufficient
- [ ] 2 — persist direct reverse-dependency postings
- [ ] 3 — persist unresolved-name waiters / changed-export wakeups
- [ ] 4 — one-file observe/delete no longer loads full module state
- [ ] 5 — delete old module-materialization/rebuild path
- [ ] 6 — focused before/after latency + allocation/heap evidence
- [ ] 7 — 1 GiB corpus passes without heap increase
- [ ] 8 — final LOC/complexity/deletion report and PR handoff
