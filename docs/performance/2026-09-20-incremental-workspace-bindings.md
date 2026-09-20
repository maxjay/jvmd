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
