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

Merged main `1b376f3c` has Git tree `a52d020822c0c6be3d878fa831372a413fd7bf94`, exactly
the same tree measured on PR #9 final head `8003c6c6`. No production edits have been made on this
branch at baseline capture.

Validation on that exact tree:
- Checkpoints run `35615794730`: **success**.
- Distributions run `35615794619`: **success**.
- Corpus run `35615841190`, attempt 2: **failure — Java heap space** at `-Xmx1024m`.

Focused 128-file baseline from the same production code:
- references warm: 2.335 ms, 0 binding computations, 0 javac queries;
- references body edit: 20.861 ms, 1 binding computation, 1 javac query;
- references API edit: 39.672 ms, 17 binding computations, 2 javac queries;
- rename warm: 2.697 ms, 0 binding computations, 0 javac queries;
- rename body edit: 27.861 ms, 1 binding computation, 1 javac query;
- rename API edit: 31.352 ms, 17 binding computations, 2 javac queries.

Corpus failure shape on the exact base tree:
- early repeated `symbol.references`: commonly ~3–5 ms;
- degradation then includes 1.4 s, 16.0 s, and repeated ~4–6 s references;
- request faults begin;
- Surefire fork terminates with `Java heap space`.

A duplicate branch-baseline Checkpoints/Corpus run was also launched before production edits
(`35619139713` / `35619139734`) for independent confirmation.

## Planned checkpoints

- [x] 0 — branch, clean progress record, baseline gates
- [ ] 1 — instrument semantic invalidation I/O/work counts if current counters are insufficient
- [ ] 2 — persist direct reverse-dependency postings
- [ ] 3 — persist unresolved-name waiters / changed-export wakeups
- [ ] 4 — one-file observe/delete no longer loads full module state
- [ ] 5 — delete old module-materialization/rebuild path
- [ ] 6 — focused before/after latency + allocation/heap evidence
- [ ] 7 — 1 GiB corpus passes without heap increase
- [ ] 8 — final LOC/complexity/deletion report and PR handoff
