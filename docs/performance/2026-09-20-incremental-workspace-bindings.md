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


## Warm validation / Merkle follow-up — 3b and 3c complete

The incremental fragment change removed workspace-wide javac attribution after edits, but the first warm-validation benchmark still showed source-validation cost scaling with the number of files even when no compiler work ran:

| workspace | files | initial median | initial p95 | javac / bindings |
| --- | ---: | ---: | ---: | ---: |
| plain/coarse | 128 | 4.704 ms | 5.299 ms | 0 / 0 |
| plain/coarse | 512 | 15.730 ms | 19.067 ms | 0 / 0 |

### 3b — resolved/Maven workspaces

JVMD already persisted deterministic per-file/directory/module Merkle state in RocksDB, while resolved analyzer contexts exposed authoritative source-change epochs. WorkspaceBindings combines resolver generation, document generation, participating compiler source epochs, and persisted module Merkle roots into one detached validation token.

A warm request can therefore prove that the entire prior semantic snapshot is still valid without rereading source files. The Merkle root may appear after the cold snapshot while indexing finishes; WorkspaceBindings adopts that newly available root only when every already-authoritative live epoch is unchanged. Once present, a root may not disappear or change without forcing conservative validation.

The relationship path was also tightened so one request reuses a single validated WorkspaceBindings snapshot rather than validating it again through name-path resolution.

### 3c — plain/coarse workspaces

Plain workspaces do not have a resolver/module source topology that can safely drive the analyzer watcher. Treating Java `WatchService` delivery as authoritative was experimentally rejected: an immediate timestamp-preserving closed-file edit could race the asynchronous delivery path and serve stale semantics.

On Linux, plain workspaces now establish a native inotify journal **before** their first full WorkspaceBindings snapshot. Validation synchronously drains the kernel queue itself, so it does not depend on a background watcher thread having processed an event. Java source create/delete/move/write/attribute events advance a detached generation. Queue overflow, native faults, or unsupported operating systems mark the journal unreliable and fall back to the existing exhaustive validation path.

This gives Merkle-style validation the missing authoritative external-change signal without weakening the preserved-mtime/edit correctness boundary.

### Final warm-validation benchmark — eb2bd852

The same 20-request benchmark on the final safe implementation:

| workspace | files | median | p95 | fast validations | full validations | javac / bindings |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| plain/coarse | 128 | **0.636 ms** | **1.135 ms** | **20/20** | **0** | **0 / 0** |
| plain/coarse | 512 | **0.599 ms** | **1.008 ms** | **20/20** | **0** | **0 / 0** |
| resolved/Maven | 128 | **1.793 ms** | **2.510 ms** | **20/20** | **0** | **0 / 0** |
| resolved/Maven | 512 | **1.574 ms** | **2.314 ms** | **20/20** | **0** | **0 / 0** |

Compared with the original plain-workspace baseline, unchanged validation improved from **4.704 ms to 0.636 ms at 128 files (86.5%)** and from **15.730 ms to 0.599 ms at 512 files (96.2%)**. More importantly, the warm path no longer grows with the fixture size: all four final scenarios use 20/20 fast validations, zero full source validations, zero javac queries, and zero binding computations.

The 128-file incremental benchmark on the same final head still preserves the fragment-level edit behavior:

| operation | state | final request ms | binding computations | javac queries |
| --- | --- | ---: | ---: | ---: |
| references | warm | **1.563** | 0 | 0 |
| references | body edit | **32.676** | **1** | **1** |
| references | API edit | **64.347** | **17** | **2** |
| rename | warm | **1.547** | 0 | 0 |
| rename | body edit | **24.446** | **1** | **1** |
| rename | API edit | **48.463** | **17** | **2** |

This completes the WorkspaceBindings checklist:

- **3:** per-file incremental semantic fragments — complete;
- **3b:** authoritative Merkle/source-generation validation for resolved workspaces — complete;
- **3c:** race-safe external-change generation for plain/coarse workspaces on Linux, with conservative fallback elsewhere — complete.

The remaining limitation is deliberately platform-specific rather than semantic: non-Linux plain/coarse workspaces fall back to exhaustive validation until an equally authoritative native journal backend is implemented. Correctness is preserved on every platform.
