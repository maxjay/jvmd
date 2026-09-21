# Indexed semantic state and read views

Baseline: main `563ecc473fe9c033ece775686645972baca8d41a`.
Implementation: `20ad41cd25c55a96aaa2e3733012e2290f2bc63b`, tree
`4a4250b3093036c205926ecd3213af4ab9e7448a` (identical to the locally tested tree).

## Ownership changes

`WorkspaceBindings` retains input identities, completeness/diagnostic metadata, and the existing
`FileSemanticContribution` summaries. Complete symbol, edge and occurrence facts live in a
session-owned keyed Rocks database. A read view pins a database revision; it does not reconstruct
an aggregate graph. Only changed/dependent files are replaced. Decoded records have an independently
bounded cache (0–16 MiB, selected by the existing budget). No aggregate JSON size estimate or
admission rejection path remains. `serialized_bytes` is removed from status.

Point symbol lookup, name search, adjacency and occurrence lookup select postings. Search stops
after its page and one lookahead match. Rename still explicitly materializes the facts it needs for
whole-workspace edits. Input/context validation and `SemanticUpdatePolicy` remain authoritative;
failed/focused results do not become reusable complete observations. Cold attribution still visits
the workspace. Outline-only cold symbol search still enumerates source declarations without forcing
full attribution.

`KeyedFacts` provides owner replacement and indexed record selection. Both live bindings and the
persisted source overlay use it. `FactCodec` encodes individual values with a version header, tagged
binary fields and per-record string references. `FileSemanticContribution` remains the summary;
it does not become the complete graph.

The persistent local-source overlay replaces per-file JSON and artifact-wide decoded lists with
file stamps, symbol records, outgoing/incoming relationships, and identity/name/gram postings.
Replacement deletes the old owner's records and postings in the same write batch that publishes
the new revision. A 4 MiB estimated decoded-record cache can be discarded independently. Unchanged
owners remain reusable. Incoming binary edges cannot reappear when a source replacement masks them.
Legacy JSON owners migrate atomically one file at a time; interrupted migration resumes on open.
SCIP identities and existing numeric handles are preserved. Immutable dependency artifacts are
unchanged; source edits do not write immutable artifact generations.

`SymbolReadView` gives live and persisted sources/dependencies common keyed lookup, search and
child-selection operations. `WorkspaceReadView` owns source precedence, deduplication, expansion
and pagination formerly embedded in `Application`'s RPC handler. Binding views pin revisions;
the persisted adapter retains the store's existing per-operation synchronization. Cursors do not
promise a transaction across separate RPC requests.

## Measurements

Three serial alternating baseline/revision processes, Temurin 25.0.4.1+1, Linux, `-Xmx1g`.
Cells are medians of per-process medians, not pooled samples. No OS cache flush; small repetition
count. Raw samples are in `semantic-state/`.

| Measurement | Main | Change |
| --- | ---: | ---: |
| 128-file unchanged warm lookup, zero decoded budget | 5.407 ms | 0.870 ms |
| File loads across 31 zero-budget requests | 3,968 | 128 |
| First workspace lookup, zero budget | 258.19 ms | 413.65 ms |
| Warm lookup, 16 MiB budget | 0.746 ms | 0.816 ms |
| First lookup, 16 MiB budget | 286.82 ms | 428.57 ms |
| Source identity lookup after reopen | 69.20 ms | 40.14 ms |
| Java allocation during that cold source lookup | 6,693,984 B | 1,944,944 B |
| Warm source identity lookup | 0.1045 ms | 0.0892 ms |
| Warm source numeric-handle lookup | 0.1359 ms | 0.0806 ms |
| Warm source prefix search | 3.4780 ms | 0.1849 ms |
| Warm source exact-name search | 3.6030 ms | 0.3196 ms |

Workspace measurements supply identical detached facts to isolate lifetime/query work; they do
**not** time javac or simulate editor latency. Source measurements use 256 files / 3,072 symbols,
100 checked queries per operation and a separate process for seeding. A full inventory check after
the timed queries verifies all 3,072 identities; reported cache status includes that inventory read.
Thread allocation excludes native allocation and is not a retained-heap measurement.

Costs are explicit: one fresh source publication sample is **176.99 → 486.92 ms**;
closed index directory allocation is **1,136 → 2,944 KiB**. Those are single seed/footprint samples,
not a repeated write-throughput benchmark. Index construction and disk space increased. Below the
old admission limit, warm workspace latency did not improve; the principal win is retaining useful
facts when decoded retention is constrained and selecting source records without scanning lists.
No universal speedup or JDTLS comparison is claimed.

## Verification and limits

42 selected tests pass, zero failures/errors/skips: cache validation, body/API dependencies,
zero-budget reuse, pinned old views, deletion, fallback retry, source record selection and exact
replacement, legacy migration and handle preservation, stale binary-edge masking, workspace/source
precedence, search/pagination, hierarchy/references, completion, semantic edits and static-import
rename. New integration regressions carry checkpoint phase tags.

The migration experiment also opens an actual baseline-written 3,072-symbol store and checks
identity, handle, exact and prefix queries plus its complete inventory. Published source contents
match the tested tree hash exactly. Full CI is separate from these local checks.

Session navigation facts are disposable on session close/restart; the persisted local-source
index survives restart. Per-file summary/input metadata stays in heap, while graph records do not.
Each session database has a 4 MiB block cache and at most two 4 MiB write buffers in addition to its
decoded cache. These are configured cache/buffer bounds, not a bound on all process memory or a
cold attribution/write-batch peak measurement. Read handles release native snapshots explicitly,
on collection, or at session close. Rolling back to an older binary does not understand the new
local-source records and requires regenerating that source overlay.

## Reproduction

Package main and the revision separately with JDK 25:

```sh
mvn -pl jvmd-dist -am -DskipTests package
```

Copy each revision's `jvmd-dist/target/*.jar` and `target/lib/*.jar` into separate directories.
Run the committed harness with absolute classpaths and a new output directory:

```sh
JAVA_HOME=/path/to/jdk25 benchmarks/semantic-state/run.sh \
  '/absolute/baseline/*' '/absolute/revision/*' /absolute/new-results
```

The script compiles one harness against the baseline and uses it for both versions, runs both
cache budgets, checks source results, and tests migration while preserving the original artifact
path. Test class names and raw aggregate counts are included in `semantic-state/summary.json` and
the implementation's regression tests.
