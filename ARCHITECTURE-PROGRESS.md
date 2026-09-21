# JVMD Architecture Progress

Append-only implementation log for `ARCHITECTURE-CHECKLIST.md`.
Do not use other checklist/progress files on this branch.

## 001 — Reset architecture tracking

**Current phase:** A0 — measure real retained heap and true warm-query cost.

**Tracking cleanup**
- Removed obsolete/competing trackers from this branch:
  - `DIAGNOSTICS-PROGRESS.md`
  - `INDEXING-PROGRESS.md`
  - `PROGRESS.md`
  - `SEMANTIC-MEMORY-CHECKLIST.md`
  - `SEMANTIC-STATE-CHECKLIST.md`
  - `SEMANTIC-STATE-PROGRESS.md`
- Historical contents remain recoverable from Git history.
- Retained architectural/evidence documents, especially `docs/semantic-memory-architecture.md`.

**Established baseline**
- Original baseline `ae23fd1f` and current candidate both reproduce the large-workspace failure
  under Temurin 25.0.4.1+1 / Ubuntu 24.04 / `-Xmx1024m`.
- Candidate at 279 files estimates ~327.5–328.0 MB retained semantic fragments against a
  134,217,728-byte admission limit.
- Admission reason is `over_budget` on every unchanged query.
- Ten unchanged candidate reference queries produce 10 full builds, 0 cache hits and 2,790 file
  analyses.
- Current semantic-shape census: 45,293 symbols; 1,011,720 row fields; 124,801 occurrences;
  78,838 edges; ~20.55M string characters; 67,362 collection entries; 2,363 API declarations;
  4,833 exported names; 1,325 dependencies.
- Local source cache is below its 16 MiB cap at this size, so it is not the direct cause of the
  279-file rebuild loop.
- Binary artifact indexing already demonstrates the intended direct-record/posting architecture;
  local source indexing still uses per-file JSON `SourceFile` blobs and artifact-wide
  materialisation in hot paths.

**Next**
- Add diagnostic-only semantic-state admission override.
- Build once, retain state, force GC, then measure actual live heap and genuine warm references.
- Do not modify production admission defaults until this measurement closes A0.


## 002 — Phase A0 diagnostic admission experiment started

**Commits**
- `018cb289` — diagnostic-only `jvmd.workspace_bindings.diagnostic_admission_mb` override. Production admission remains 128 MiB / caller budget when unset.
- `8ee2a337` — focused probe can force GC after the first semantic build before reporting heap.
- `9d31934a` — CI runs the focused probe with 512 MiB diagnostic admission, forced GC, 1 GiB JVM and three reference requests.

**Purpose**
- Distinguish a bad retained-size estimator/admission policy from a genuinely oversized canonical representation.
- Step 1 measures retained heap after the first admitted build and forced GC.
- Steps 2–3 measure genuinely warm unchanged references if admission succeeds.
- Baseline checkout ignores the candidate-only diagnostic admission property, preserving its original behaviour for comparison.

**Acceptance evidence to capture**
- actual heap used after forced GC;
- candidate `cached_files`, `fragment_files`, `cache_hits`, `files_reanalysed`;
- warm reference latency and thread allocation;
- Rocks native/source-cache state;
- whether the 1 GiB JVM remains stable while the 279-file state is retained.

**Decision after run**
- If actual retained heap is far below the ~312 MiB estimate, fix retained-size accounting/admission before representation surgery.
- If actual retained state is genuinely large, begin Phase B against the measured dominant fact classes rather than increasing production limits.


## 003 — A0 complete: admission estimate is the first architectural failure

**Run**
- Semantic memory diagnostics run: `35609895303`.
- Candidate job: `106366256667`.
- Baseline job: `106366256947`.
- Environment: Ubuntu 24.04, Temurin 25.0.4.1+1, `-Xmx1024m`.

**Candidate with diagnostic 512 MiB admission**
- First build/reference: 24,032.6 ms.
- Forced-GC live heap after first build: 405,227,064 bytes.
- Retained semantic estimate: 328,149,490 bytes.
- Cached files/fragments: 279 / 279.
- Discards: 0.
- Query 2: 13.512 ms, 53,688 request-thread allocated bytes, 1 cache hit, 0 files reanalysed.
- Query 3: 12.636 ms, 53,712 request-thread allocated bytes, 2 cache hits, 0 files reanalysed.
- Rocks native cache usage remains stable at ~49.0 MB with ~1.42 MB pinned.

**Baseline with original admission/discard behaviour**
- First build/reference: 24,026.4 ms.
- Forced-GC live heap after first query: 334,030,752 bytes.
- Cached files/fragments: 0 / 0.
- Query 2: 6,950.8 ms; second full build; 544 cumulative files reanalysed.
- Query 3: 5,735.3 ms; third full build; 816 cumulative files reanalysed.

**Interpretation**
- Retaining the candidate workspace adds roughly 71,196,312 bytes (~67.9 MiB) of live Java heap over the
  discarded-state baseline at the first forced-GC checkpoint, despite the candidate estimator claiming
  ~312.9 MiB. The revisions differ by seven source files and other branch changes, so this is an
  approximate retained-state delta rather than an exact object-graph retained size.
- The current estimate therefore overstates observed incremental live heap by roughly 4.6x at this
  workspace size.
- The 128 MiB admission policy is rejecting semantic state that fits comfortably inside the intended
  semantic-state budget in the observed 1 GiB process.
- That rejection changes unchanged reference latency from ~13 ms to multi-second full-workspace rebuilds.
  This is the first production architecture problem to fix.
- Thread allocation values measure the requesting thread only; they are useful for the warm request path
  but are not a complete measure of cross-thread javac rebuild allocation.

**Decision**
- A0 is complete.
- A1 begins with admission/accounting and canonical-state survival, not representation compaction.
- Phase B remains necessary after A1 because ~68 MiB for a 279-file semantic workspace is still material,
  but it is no longer the blocker for achieving real warm behaviour.


## 004 — A1 admission accounting implementation

**Before**
- Production retained-state estimate on the 279-file candidate: ~328.1 MB.
- Production admission limit: 128 MiB.
- Result: every unchanged query discarded the complete semantic workspace and reanalysed 279 files.
- Diagnostic admitted state proved true warm references at 13.512 ms / 12.636 ms with zero further file analysis.
- Approximate forced-GC incremental live heap versus the discarded baseline: ~67.9 MiB.

**Implementation**
- `6fd3d9aa` — replace the arbitrary retained-state weights with `hotspot-compressed-oops-v1`,
  an object-layout-based conservative model. The production 128 MiB limit is unchanged.
- Model explicitly prices edges, occurrences, diagnostics, dependencies, API declarations/exports,
  symbol-row containers/entries, strings, collections and other row values.
- The diagnostic admission override remains available only as an explicit system property; normal
  production behaviour does not use it.
- `680fb85b` — remove the 512 MiB diagnostic override from the focused CI probe. The next run tests
  the production admission path exactly.

**Acceptance**
- Query 1 may build the workspace.
- Query 2 and 3 must be cache hits at the normal 128 MiB limit.
- `files_reanalysed` must remain at 279 after query 1.
- No discard may occur.
- The new estimated retained bytes must remain below 128 MiB while preserving a meaningful safety
  margin over the ~67.9 MiB observed incremental live-heap delta.
- Checkpoints and semantic benchmark workflows must remain green.
