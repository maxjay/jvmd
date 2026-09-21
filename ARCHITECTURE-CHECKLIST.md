# JVMD Architecture Checklist

This is the single active implementation checklist for PR #8 on `architecture/semantic-state`.

The objective is not to make one Corpus test green. The objective is to establish a bounded,
predictable semantic-state and source-index architecture whose warm-query work is independent of
workspace size except for the actual result set.

## Ground rules

- Benchmark before each architectural change; benchmark after; keep or revert based on evidence.
- Do not raise production heap, Rocks, or semantic-state limits as a fix.
- An unchanged semantic query must perform:
  - 0 javac analyses;
  - 0 workspace semantic rebuilds;
  - 0 unnecessary source/index writes.
- Persist canonical facts once. Compose/reuse Merkle identities. Treat navigation/postings/results
  as derived or cached state.
- Every new representation must retire or explicitly justify an old one.
- Keep PR #8 as the implementation vehicle. Do not merge until every acceptance phase below closes.

## Fixed comparison points

- Pre-semantic baseline: `ae23fd1f44573bd3427c0e77f967150173669a2f`
- Archived first semantic-state attempt: `9611f4589ed42edf1bd4071e5b104b4bd911867c`
- Candidate before memory investigation: `8c9ed2aa6cdebd9c3288dba2c23f48fe449f9324`

## Established evidence before implementation

Large-workspace isolated `IdentifierSweepTest`, Temurin 25.0.4.1+1, Ubuntu 24.04, `-Xmx1024m`:

- Baseline and candidate both eventually OOM; the catastrophic large-workspace failure predates the
  semantic-Merkle branch.
- Candidate workspace: 279 files.
- Candidate semantic retained-state estimate: ~327.5–328.0 MB (~312.4 MiB).
- Admission limit: 128 MiB.
- Every unchanged reference query is discarded for `over_budget`.
- Every unchanged reference query therefore reanalyses all 279 files.
- 10 unchanged candidate reference requests => 10 full builds, 0 cache hits, 2,790 file analyses,
  0 retained fragments.
- First large-workspace reference request is ~20+ seconds; subsequent supposedly warm requests are
  roughly 4–6 seconds because they are actually full-workspace semantic rebuilds.
- The source publisher subsequently skips 279 unchanged publications per rebuild, independently
  proving that the semantic work was redundant.
- The current 279-file retained-state estimate contains roughly:
  - 45,293 symbols;
  - 1,011,720 symbol-row fields;
  - 124,801 occurrences;
  - 78,838 edges;
  - 20,545,049 string characters;
  - 67,362 collection entries;
  - 2,363 API declarations;
  - 4,833 exported names;
  - 1,325 dependency entries.
- The local Rocks `SourceFile` cache remains below its 16 MiB cap for this workspace, so that cache
  is not the immediate 279-file rebuild trigger, though its whole-artifact materialisation design is
  still a scaling liability.
- Binary artifact queries already use compact direct records/postings and avoid whole-artifact
  materialisation. Local source indexing does not.

---

## Phase A — Make warm semantic state genuinely warm

### A0 — Measure real retained heap, not just the estimate

- [ ] Add a diagnostic-only admission override; production default remains unchanged.
- [ ] Build the 279-file workspace once.
- [ ] Retain the candidate semantic state.
- [ ] Force GC after the first build.
- [ ] Record actual live heap, committed heap, GC count/time and Rocks native usage.
- [ ] Run repeated unchanged `symbol.references` requests.
- [ ] Record per-request latency and thread allocation.
- [ ] Confirm whether the ~312 MiB estimator materially overstates or understates real retained cost.

**Checkpoint A0:** actual retained-state cost and true warm-query cost are known.

### A1 — Separate canonical incremental state from optional cache admission

- [ ] Define the minimum per-file state required to update/remove prior semantic contributions
      without re-running javac.
- [ ] Ensure crossing a memory budget cannot delete that canonical incremental state.
- [ ] Make optional/derived navigation/result state the first eviction target.
- [ ] Preserve correctness under source deletion, body edit, API edit and context change.
- [ ] Add regression: unchanged query after a budget-pressure build performs 0 javac analyses.
- [ ] Add regression: repeated unchanged queries do not increase retained heap without bound.

**Checkpoint A1:** a budget miss no longer converts warm queries into O(workspace) javac rebuilds.

### A2 — Benchmark Phase A before/after

Report at the real JVMD workspace and at smaller scaling points where practical:

- cold build latency/allocation;
- warm reference median/p95/allocation;
- files reanalysed;
- full/incremental builds;
- retained heap after forced GC;
- Rocks native usage;
- index/source writes.

**Checkpoint A2:** warm unchanged queries are genuinely warm and bounded.

---

## Phase B — Compact canonical in-memory semantic facts

### B0 — Establish bytes/fact baseline

- [ ] Measure actual retained bytes/file.
- [ ] Measure retained/estimated bytes/symbol, occurrence and edge.
- [ ] Attribute dominant heap classes with JFR/JOL/heap histogram where useful.
- [ ] Confirm which bytes are canonical payload versus duplicate navigation/index overhead.

### B1 — Replace JSON-shaped internal symbol storage

- [ ] Replace hot internal `Map<String,Object>` symbol rows with typed compact immutable records.
- [ ] Preserve map/JSON construction only at API/serialization boundaries.
- [ ] Introduce compact IDs/string tables where measured beneficial.
- [ ] Avoid duplicating identical SCIP/FQN/name/kind strings across thousands of records.
- [ ] Preserve exact external protocol/correctness.

### B2 — Compact occurrences and edges

- [ ] Replace repeated string-heavy occurrence identity with compact IDs/offsets where safe.
- [ ] Replace edge string triples with compact source/target/kind representation where safe.
- [ ] Share immutable payloads between canonical per-file contributions and derived navigation
      indexes rather than copying equivalent data.

### B3 — Reassess `NavigationIndex`

For `owners/symbols/declarations/names/edges/outgoing/incoming/references/occurrences`:

- [ ] Identify canonical payload versus derived posting.
- [ ] Quantify objects/bytes per semantic fact.
- [ ] Compare persistent maps with compact mutable/packed derived indexes.
- [ ] Keep persistence only where old-reader semantics require it.
- [ ] Ensure every retained secondary index has a measured lookup benefit.

**Checkpoint B:** materially lower retained heap and edit allocation while preserving semantic-Merkle
latency gains and old-reader correctness.

---

## Phase C — Replace local source JSON materialisation with direct indexed facts

### C0 — Benchmark current local-source query scaling

At 32 / 128 / ~279 / 512 / 1k files where feasible, measure:

- SCIP lookup;
- binary-key lookup;
- symbol find;
- outgoing references;
- incoming references;
- references pagination;
- allocation and source bytes decoded per query.

### C1 — Reuse the compact artifact-index model

Target direct local-source access:

- [ ] SCIP -> compact source symbol record.
- [ ] binary key -> source symbol ID(s).
- [ ] file -> bounded symbol/occurrence/relationship ranges or postings.
- [ ] source symbol -> outgoing relationships.
- [ ] target -> incoming relationships.
- [ ] name/path -> direct postings where useful.
- [ ] Normal queries must not call an artifact-wide `sources()` materialisation.

Prefer reusing `ArtifactIndexFormat` / `RocksArtifactRepository` concepts and codecs rather than
creating another independent index architecture.

### C2 — Retire whole-artifact source caching as an architectural dependency

- [ ] Remove hot-path dependence on `List<SourceFile> sources(artifact)`.
- [ ] Make any remaining source cache record/block acceleration only.
- [ ] Prove query allocation scales with result/candidate postings, not total local workspace facts.

**Checkpoint C:** local-source querying has the same point-addressable scaling model as binary
artifact querying.

---

## Phase D — Consolidate persistence and Merkle ownership

### D0 — Workspace identity

- [ ] Replace overlapping F/C/D/M authority with content-addressed canonical leaf/node payloads plus
      a small mutable module-root pointer/context descriptor.
- [ ] Update a known file through O(path depth) new nodes rather than a full module materialisation.
- [ ] Keep full filesystem walk only as cold/unknown-history reconciliation.

### D1 — Canonical file semantic contribution

Define one per-file canonical contribution with independent concern identities:

- [ ] source identity;
- [ ] API identity;
- [ ] dependency identity;
- [ ] unresolved/negative-read identity;
- [ ] reference/occurrence identity.

Consumers validate against the narrowest identity they actually need.

### D2 — Make invalidation an algorithm over canonical facts

- [ ] Remove duplicated content/API identity from semantic invalidation records.
- [ ] Persist dependencies/negative reads once.
- [ ] Derive reverse-dependency and unresolved-name waiter indexes.
- [ ] Classify body/API changes by old/new canonical identities.
- [ ] Eliminate whole-module semantic-record loading for one-file updates.
- [ ] Demote monotonic semantic revisions to optional wakeup metadata, not semantic truth.

### D3 — Make diagnostics true result caches

- [ ] Key diagnostics from canonical source/context/relevant-dependency identities.
- [ ] Stop persisting duplicate source/API/dependency identity as separate semantic authority.
- [ ] Keep diagnostic payload disposable and independently evictable.

**Checkpoint D:** persistence contains canonical facts/root pointers once; projections are
algorithmically derived/rebuildable.

---

## Phase E — Lifetime, memory budgets and scaling guarantees

### E0 — Snapshot lifetime/reclamation

- [ ] Measure no-reader repeated edits.
- [ ] Measure one long-lived old reader.
- [ ] Measure overlapping readers.
- [ ] Release readers + forced GC and prove return to a stable plateau.
- [ ] Eliminate accidental old-root retention.

### E1 — One coherent memory policy

Account together for:

- Java canonical semantic state;
- javac/compiler working state;
- query temporaries;
- derived indexes/result caches;
- Rocks block cache/memtables.

- [ ] Define degradation/eviction order.
- [ ] Remove unexplained threshold interactions.
- [ ] Validate under 512 MiB and 1 GiB heaps without changing correctness semantics.

### E2 — Permanent scaling gate

CI must report/fail on:

- retained heap slope;
- allocation/query;
- files reanalysed;
- semantic rebuild count;
- index writes;
- Rocks native usage;
- cold/warm latency;
- body/API edit latency/allocation;
- old-reader amplification.

---

## Phase F — Final acceptance

- [ ] Re-run Corpus at the original 1 GiB heap.
- [ ] No Rocks cache collapse.
- [ ] No OOM.
- [ ] Warm requests remain warm throughout the sweep.
- [ ] Existing correctness floors unchanged.
- [ ] Re-run full semantic benchmark suite against `ae23fd1`.
- [ ] Run JVMD-vs-JDTLS benchmark suite for supported comparable operations.
- [ ] Decide keep/archive PR #8 based on measured memory, latency, allocation, correctness and
      maintainability—not sunk cost.

PR #8 is mergeable only after the architecture passes these gates.
