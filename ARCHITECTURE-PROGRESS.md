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
