# Storage ownership and compatibility

Rocks is the sole production index backend. `IndexStorage` owns one store and its shared native resources. It exposes separate artifact admission, scan inventory, and semantic-state services. It does not publish artifacts or answer queries.

`IndexService` parses inputs and submits each binary, code enrichment, local/JDK artifact, or documentation update once to `IndexStore`. `RocksIndexStore` writes immutable content through `RocksArtifactRepository`, then installs the corresponding manifest. A failed publication cannot activate a manifest pointing to its missing content. Documentation selection belongs to that same store; inventory no longer carries a second documentation pointer.

Module Merkle roots and source revisions belong to `IndexSemanticState`. Repository scan completion still validates and activates Rocks format generations through `RocksMigrationManager`. Existing validation, previous-generation recovery, generation pins, and pruning remain supported. Storage closes the authoritative store before shared native resources; bounded parsing and publication admission share one budget.

## Existing installations

Existing Rocks directories and manifests retain their paths and format. The conventional `index.db` constructor path selects its sibling `index-v2` directory, as before. SQLite databases are never opened, changed, or deleted. An installation containing only SQLite rebuilds its Rocks index from available source artifacts; there is no SQL data import or runtime rollback mode.

Remove old `jvmd.index.store.backend=sqlite`, `jvmd.index.read.backend=sqlite|shadow`, and `jvmd.index.generation.backend=none` settings. Unsupported values fail at startup with a rebuild instruction. Explicit `rocksdb-sst` settings and the former generation `auto` default remain accepted, always opening Rocks.

Status now reports resource, inventory, semantic-state, and admission metrics under `storage`, with publication counters under `storage.repository`. `generation_sink` and `shadow_validation` are removed. Numeric query handles remain opaque and are not transferable from SQLite; SCIP identities remain the semantic identity.

## Tests

The former SQLite implementation and schemas live only in `jvmd-tests/src/test`, with a test-scoped JDBC dependency. They are an unregistered historical reference for canonical query comparisons and SQL migration fixtures. Production search, workspace, restart, inventory, and publication tests exercise Rocks. Performance comparisons can also use a pinned historical checkout.
