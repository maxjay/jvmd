# Semantic memory architecture — acceptance checklist

This checklist supersedes “make Corpus green” as the active work order for PR #8.
Corpus is evidence, not the objective. The objective is a bounded, explainable semantic-state
memory model whose persistence, allocation, invalidation and lifetime properties scale.

## Fixed comparison points

- Original pre-semantic-state baseline: `ae23fd1f44573bd3427c0e77f967150173669a2f`.
- Archived first attempt: `9611f4589ed42edf1bd4071e5b104b4bd911867c`.
- Current candidate at investigation start: `8c9ed2aa6cdebd9c3288dba2c23f48fe449f9324`.
- Branch: `architecture/semantic-state`; PR #8 remains the implementation vehicle.
- Keep the existing 1 GiB Corpus heap and existing Rocks budget while diagnosing. Do not
  “fix” the problem by raising limits or weakening tests.

## Architectural acceptance principles

1. Persist facts and roots; derive projections.
2. Every semantic fact has one authoritative owner.
3. Merkle identity/change detection is separate from lookup acceleration.
4. Derived indexes and result caches are explicitly disposable/rebuildable.
5. An unchanged warm query performs zero workspace reconstruction.
6. Memory must not grow with the number of unchanged queries.
7. Body-edit work is proportional to changed semantic facts plus affected postings/ancestors,
   not workspace size.
8. API-edit propagation is proportional to the real affected dependency closure.
9. Old-reader isolation has a measured and bounded retention cost.
10. Memory pressure evicts derived/cached state before canonical incremental state.
11. Every new abstraction must retire or justify an old representation.
12. Corpus passing is necessary but not sufficient.

## Execution order

### [x] 0. Freeze and preserve the experiment

- Preserve all three comparison revisions above.
- Do not rewrite historical benchmark evidence.
- Keep current production changes on PR #8 rather than spawning another implementation branch.
- Treat the current representation as a candidate that may still be rejected.

### [ ] 1. Establish the real scaling/failure mechanism

Run identical large-workspace workloads on baseline and candidate under the same 1 GiB heap.
Measure at minimum:

- peak and forced-GC retained Java heap;
- allocation per request;
- GC count/time;
- Rocks cache usage and pinned bytes;
- workspace full builds, incremental builds, cache hits and discards;
- files reanalysed/reused;
- navigation rebuild/update counts;
- semantic/index writes;
- repeated `symbol.references` latency over the run.

Use scaling points where practical: 32, 128, 512, ~1k, ~2k and real JVMD.
Do not infer an architecture from one OOM.

**Exit:** quantitative explanation of where retained memory/allocation grows and whether the
candidate changed the slope or merely exposed an existing limit.

### [ ] 2. Build the complete state ownership and multiplicity ledger

For every live/persisted fact record:

- authoritative owner;
- consumers;
- lifetime;
- cardinality;
- number of resident/persisted representations;
- whether it is canonical, Merkle identity, derived index, or result cache;
- correctness reason for every duplicate representation.

Cover source identity, workspace roots, API contracts/roots, dependencies, exports, unresolved
targets, symbols, edges, occurrences, navigation postings, diagnostics, revisions and Rocks facts.

**Exit:** exactly one authority for every fact; every duplicate has a measured justification.

### [ ] 3. Persistence/derivability audit

Audit, field by field:

- `RocksWorkspaceState` F/C/D/M records;
- `RocksSemanticInvalidation` content/API/dependencies/exports/unresolved/revisions;
- diagnostic snapshot source/API/dependency identity;
- source-index symbols/edges/source facts;
- any in-memory equivalent of persisted facts.

For each item answer:

1. Is information lost if this record is removed?
2. Can an existing canonical node/root derive it?
3. What would reconstruction cost?
4. Is it authoritative or only an acceleration cache?
5. Should it be retained, made rebuildable, or deleted?

**Exit:** proposed minimal persisted model plus an explicit delete/retain/rebuild list.

### [ ] 4. Formalise the semantic identity algebra

Define separate canonical identities for the inputs/results that actually differ:

- source content;
- file API;
- dependency/reference contribution;
- compiler/context;
- module;
- workspace.

Specify change algebra for body edits, API edits, positions/docs, additions/deletions,
classpath/JDK/options and negative lookup resolution. Reuse existing roots rather than creating
another unrelated global root.

**Exit:** every consumer can name the narrowest identity needed to validate its result.

### [ ] 5. Define the canonical in-memory representation

Specify the minimum retained semantic representation after analysis, expected to centre on
per-file canonical contributions composed into module/workspace ownership roots.

Explicitly mark navigation, reverse dependency, name/incoming/outgoing postings and diagnostics
as canonical or derived. Persistent structures are not mandatory for secondary indexes.

**Exit:** no two whole-workspace structures independently claim semantic authority.

### [ ] 6. Measure and redesign derived-index density

Quantify bytes/objects per symbol, edge and occurrence for the current `NavigationIndex`,
including multiplicity across owners/symbols/declarations/names/edges/outgoing/incoming/
references/occurrences.

Compare candidates such as:

- current persistent trees;
- compact mutable indexes updated from immutable file deltas;
- packed/local-ID postings;
- lazy indexes for uncommon queries.

Keep only the measured winner that preserves old-reader/correctness requirements.

**Exit:** chosen representation has measured bytes/fact, edit allocation and query latency.

### [ ] 7. Formalise and test allocation complexity

Executable invariants:

- retained semantic memory is approximately O(files + symbols + edges + occurrences);
- unchanged query allocation is O(result), not O(workspace);
- unchanged query performs zero semantic reconstruction;
- body edit allocation is changed facts + affected postings + ownership-path updates;
- API edit adds only the true affected closure;
- repeated unchanged queries reach a stable memory plateau.

Publish measured bytes/file, bytes/symbol, bytes/edge and bytes/occurrence.

**Exit:** scaling curves are linear/explainable within declared tolerances.

### [ ] 8. Formalise snapshot lifetime and reclamation

Model old readers explicitly:

- what pins a root;
- which paths are shared;
- when obsolete generations become unreachable;
- cache references that can accidentally retain old roots;
- concurrent-generation limits/behaviour.

Benchmark repeated edits with zero readers, one long-lived reader, overlapping readers, and
post-release forced GC.

**Exit:** measured bounded retention amplification with recovery to a stable plateau.

### [ ] 9. Define one coherent memory budget

Model Java semantic state, compiler working state, query temporaries and Rocks native memory
together. Define priority/degradation order. Optional derived/cache state must be evicted before
canonical incremental state.

Do not use the current 128 MiB semantic admission and 64 MiB Rocks cache as unexplained magic
numbers.

**Exit:** predictable behaviour under constrained heaps, including 512 MiB and 1 GiB.

### [ ] 10. Redesign persistence around canonical facts and roots

Using phases 2–9:

- remove duplicated persisted identities;
- update changed Merkle paths rather than loading whole modules;
- eliminate full-module materialisation for one-file semantic invalidation;
- persist dependency/reference facts once;
- derive invalidation closures algorithmically from canonical relationships/indexes;
- key diagnostics/results by canonical identities instead of repeating identity state.

**Exit:** one-file updates do work proportional to changed state and affected closure.

### [ ] 11. Implement as isolated replacements

For each production commit record:

- old state/work removed;
- new authority;
- expected asymptotic effect;
- expected allocation effect;
- invariant preserved;
- before measurement;
- after measurement;
- keep/revert decision.

No giant rewrite and no benchmark result without an immutable before point.

### [ ] 12. Make semantic-memory scaling a permanent benchmark gate

Cover cold/warm navigation, body/API edits, rename, diagnostics, repeated references, repeated
edits, old readers, restart/persistence and a large real workspace.

Report latency, descriptive p95, allocation, retained/peak heap, GC, Rocks usage, rebuilds,
files analysed, index writes and scaling slope.

**Exit:** future memory regressions fail CI before merge.

### [ ] 13. Re-run Corpus as an architectural validation

Keep the original 1 GiB constraint. Require:

- no OOM;
- no Rocks cache collapse;
- stable references latency through the run;
- no correctness regression;
- warm semantic state actually reused.

A green Corpus run alone does not close the architecture.

### [ ] 14. Keep or reject the candidate implementation

Keep the current semantic-state implementation only if it demonstrates:

- bounded linear retained-memory scaling;
- zero reconstruction on unchanged queries;
- materially lower incremental allocation than baseline;
- preserved latency gains;
- fewer duplicated semantic authorities;
- justified production complexity;
- Corpus success within the original memory envelope.

Otherwise archive this attempt, preserve all evidence/specification, return to `ae23fd1`, and
implement the proven canonical-state design cleanly.

## Current known evidence

- Current 512-file candidate has strong measured incremental gains versus `ae23fd1`, including
  warm navigation, body edits and API edits.
- Earlier retained-heap evidence already showed an unexplained initial-heap increase, so memory
  acceptance was never complete.
- Corpus exposed a reproducible large-workspace OOM in `IdentifierSweepTest` with repeated
  `RocksDBException: Insert failed due to LRU cache being full` and rapidly degrading
  `symbol.references` latency.
- This is treated as evidence of an architectural allocation/retention problem until the scaling
  experiment proves the exact mechanism.
- `8c9ed2aa` bulk-builds the initial navigation state; it may reduce construction allocation but
  does not by itself establish an acceptable retained-memory architecture.
