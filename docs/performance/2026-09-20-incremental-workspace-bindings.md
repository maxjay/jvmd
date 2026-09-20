# Incremental WorkspaceBindings — before/after

Measured on GitHub Actions with Temurin 25.0.4.1+1 through the normal `Application` RPC path.

Fixture: 128 Java source files. `Root.java` exposes `Root/value()`; 16 source files depend on it and 111 are unrelated. The benchmark measures `symbol.references` and dry-run `edit.rename` at cold, unchanged warm, one-file body edit, and API edit states. It records request latency, binding computations, javac queries, WorkspaceBindings builds/cache hits, and cached-file count.

Benchmark: `WorkspaceBindingsIncrementalBenchmarkTest`
Artifact: `jvmd-tests/target/workspace-bindings-incremental-perf.json`

## Baseline — d0e01414

| operation | state | request ms | binding computations | javac queries |
| --- | --- | ---: | ---: | ---: |
| references | cold | 395.410 | 128 | 4 |
| references | warm | 9.234 | 0 | 0 |
| references | body edit | **289.559** | **128** | **4** |
| references | API edit | **245.228** | **128** | **4** |
| rename | cold | 466.339 | 128 | 132 |
| rename | warm | 6.752 | 0 | 0 |
| rename | body edit | **388.154** | **128** | **132** |
| rename | API edit | **454.485** | **128** | **132** |

Every content change invalidates the monolithic WorkspaceBindings snapshot, so both a body-only edit and an API edit reattribute all 128 source files. Rename also performs source-wide work in `describe()` before rebuilding WorkspaceBindings, producing 132 javac queries on a changed workspace.

## After — fa1f44fb

The same benchmark was rerun unchanged after introducing per-file binding fragments and reverse-dependency invalidation.

| operation | state | before ms | after ms | latency improvement | bindings before → after | javac queries before → after |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| references | cold | 395.410 | 344.738 | **12.8%** | 128 → 128 | 4 → 4 |
| references | warm | 9.234 | 5.889 | **36.2%** | 0 → 0 | 0 → 0 |
| references | body edit | **289.559** | **36.397** | **87.4% / 8.0× faster** | **128 → 1** | **4 → 1** |
| references | API edit | **245.228** | **55.668** | **77.3% / 4.4× faster** | **128 → 17** | **4 → 2** |
| rename | cold | 466.339 | 172.496 | **63.0% / 2.7× faster** | 128 → 128 | **132 → 4** |
| rename | warm | 6.752 | 5.060 | **25.1%** | 0 → 0 | 0 → 0 |
| rename | body edit | **388.154** | **22.131** | **94.3% / 17.5× faster** | **128 → 1** | **132 → 1** |
| rename | API edit | **454.485** | **40.290** | **91.1% / 11.3× faster** | **128 → 17** | **132 → 2** |

The fixture has 16 files depending on `Root.value()` and 111 unrelated files. The API-edit result therefore matches the intended invalidation set exactly: the changed `Root.java` fragment plus its 16 reverse dependants are reanalysed, while 111 unrelated fragments are reused.

### Implementation

`WorkspaceBindings` now stores a detached fragment per source file, keyed by its source hash and API fingerprint. On an unchanged request the aggregate snapshot is reused. On a source change:

- a body-only edit replaces only that file's fragment;
- an API fingerprint change replaces the changed fragment and follows the prior graph's transitive reverse-dependency closure;
- unresolved/error fragments are conservatively reconsidered after an API change, covering newly resolved symbols;
- source namespace, classpath, or workspace-generation changes still force a full rebuild;
- the aggregate symbol/edge/occurrence snapshot is rebuilt from detached fragments, so no javac-owned objects survive between requests.

Rename now resolves its target from the refreshed WorkspaceBindings snapshot instead of performing a separate source-wide `describe()` pass. This removes the previous 128 per-file describe queries on a changed workspace.

### Result

The main acceptance goal is achieved: a one-file body edit changes WorkspaceBindings work from **128 files to 1**, while an API edit in this fixture changes it from **128 files to the exact 17-file dependency closure**.

The remaining warm cost is mostly input validation and aggregate reconstruction. Source discovery/content validation still enumerates the workspace to identify dirty files. The existing Merkle/source-generation state is the natural next layer if we want unchanged validation to become independent of workspace size.


## Warm validation / Merkle follow-up

The incremental fragment change removed workspace-wide javac attribution after edits, but the first warm-validation benchmark still showed source-validation cost scaling with the number of files even when no compiler work ran:

| workspace | files | initial median | initial p95 | javac / bindings |
| --- | ---: | ---: | ---: | ---: |
| plain/coarse | 128 | 4.704 ms | 5.299 ms | 0 / 0 |
| plain/coarse | 512 | 15.730 ms | 19.067 ms | 0 / 0 |

JVMD already persisted deterministic per-file/directory/module Merkle state in RocksDB. Resolved module contexts also already had reliable source watcher epochs. WorkspaceBindings now combines the resolver generation, document generation, participating compiler source epochs, and persisted module Merkle roots into one detached validation token. The Merkle root may become available after the cold snapshot while the index finishes starting; WorkspaceBindings adopts that newly available root without rereading all sources when every already-authoritative live epoch is unchanged. Adoption is monotonic: any Merkle root that was previously present must remain present and identical, otherwise validation falls back conservatively.

The same 20-request benchmark after the Merkle validation path:

| workspace | files | median | p95 | fast validations | full validations | javac / bindings |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| resolved/Maven | 128 | **0.962 ms** | **1.767 ms** | **20/20** | **0** | **0 / 0** |
| resolved/Maven | 512 | **0.834 ms** | **0.942 ms** | **20/20** | **0** | **0 / 0** |
| plain/coarse | 128 | 1.281 ms | 2.207 ms | 0/20 | 20 | 0 / 0 |
| plain/coarse | 512 | 3.661 ms | 4.761 ms | 0/20 | 20 | 0 / 0 |

The resolved path is therefore effectively flat with workspace size for unchanged relationship queries. A separate cleanup also removed a duplicate validation inside name-path description: one references request now validates WorkspaceBindings once rather than validating again through `workspaceFind`.

Plain/coarse roots intentionally remain conservative. Their compiler root can contain arbitrary nested namespace layouts, and an external closed-file edit cannot be discovered from a stale Merkle root alone: some authoritative change signal still has to observe the filesystem. An earlier attempt to use the recursive source watcher for coarse roots was reverted because it weakened that correctness boundary. The next safe improvement for that path would require a race-safe change journal or equivalent filesystem generation source; it should not be achieved by treating persisted Merkle state as self-updating.

This end-to-end result is consistent with the earlier source-Merkle microbenchmark: a known-file update was about 0.56 ms at both 1,000 and 10,000 files, versus 18.5 ms and 108.7 ms for full reconciliation respectively. Merkle state is most valuable when JVMD already knows the changed leaf; it reduces the update to that leaf plus its ancestor chain rather than rescanning the tree.
