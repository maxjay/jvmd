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

## After

Pending implementation. The benchmark above will be rerun unchanged.
