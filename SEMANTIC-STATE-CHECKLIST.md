# Semantic state implementation checklist

Baseline: `ae23fd1f44573bd3427c0e77f967150173669a2f` on `perf/compact-grams-merge`.
Implementation branch: `architecture/semantic-state`.
Evidence and decisions: append to `SEMANTIC-STATE-PROGRESS.md`; never rewrite old entries.

## Objective

One explicit model for source revisions and semantic result validity, with typed declaration
contracts and incremental navigation indexes. Preserve javac as the semantic authority and
the existing RocksDB artifact backend. Keep protocols compatible.

## Good rules — required invariants

- A cached result records the input revision actually read. Check revision fences before
  publication; never attach a newer epoch to an older result.
- Keep semantic contracts separate from documentation, display names and source locations.
- Include accessible declarations, constants, bounds, annotations, inheritance and defaults
  in contract identity; exclude local/private implementation details deliberately.
- Preserve ordered classpath, compiler/JDK, module visibility and processor context identity.
- Share detached immutable data only. Keep javac-owned objects confined to compiler tasks.
- Complete dependency capture replaces old edges; partial capture may conservatively merge.
- Keep negative/name lookup invalidation conservative wherever javac capture is incomplete.
- A body edit still updates references, locations and documentation as appropriate.
- Observe buffers and external files through a documented revision boundary. A watcher is
  evidence about observed changes, not a filesystem transaction or proof of no pending events.
- Unknown/overflowed change history falls back to full validation. Bounded history/cache
  eviction may reduce reuse but must never change answers.
- Persistent updates preserve old reader snapshots; update only affected navigation postings.
- Measure hashing, enumeration, attribution and index maintenance separately. Record baseline
  and after results for the same fixture, plus correctness agreement and memory bounds.
- Tick a gate only after its stated validation passes. Commit coherent steps and update the log.

## Bad rules — prohibited shortcuts

- Do not use the entire workspace root as every semantic query's key.
- Do not hash presentation maps and call that a declaration contract.
- Do not classify every API-equal edit as an unchanged call/reference graph.
- Do not use unordered hashes for Java classpath resolution.
- Do not recursively hash cyclic symbol relationships as ownership children.
- Do not retain mutable javac trees/elements in a shared node cache.
- Do not retain obsolete dependency edges forever after complete analysis.
- Do not call a global reaggregation or serialization step an incremental update.
- Do not turn off conservative validation or weaken tests to obtain better timings.
- Do not replace the parser/database, add native watchers, or implement cryptographic
  accumulators without a measured need and a separately reviewed design.
- Do not claim broad JDTLS superiority or extrapolate small synthetic timings to real projects.

## Implementation gates

### A. Identity and publication foundations

- [x] A0. Pin the baseline; create this checklist and the append-only progress log.
- [x] A1. Restore the pinned JDK/build environment; record a reproducible baseline.
- [x] A2. Introduce a typed immutable declaration-contract model captured from elements.
  Use it for shared API fingerprints; version persisted identities affected by the change.
- [x] A3. Prove local rename, local/anonymous/private nested implementation changes and
  position shifts do not change exported contract identity; prove externally relevant
  changes still invalidate consumers. Keep navigation/documentation outputs current.
- [x] A4. Add revision-fenced WorkspaceBindings publication and deterministic interleaving
  tests, including fast-token adoption and changes during full validation.

### B. Source revisions and dependencies

- [x] B1. Consolidate workspace source identity/inventory snapshots and changed-file deltas
  behind one revision owner, reusing Documents/FileStateRegistry rather than duplicate stamps.
- [x] B2. Preserve buffers, additions/deletions, timestamp-preserving external edits and
  conservative coarse-root behavior; state precise watcher consistency limits.
- [x] B3. Give complete and partial dependency recording different contracts; replace old
  reverse edges after full analysis and reuse the shared graph operation in navigation.
- [x] B4. Validate source/namespace/context changes and negative-lookup fallback against
  clean analysis. Do not claim member-level lookup precision until captured explicitly.

### C. Incremental navigation snapshots

- [x] C1. Add immutable persistent maps/postings with bounded structural sharing and stable
  symbol ownership. Test replacement/removal and old-reader isolation.
- [x] C2. Update WorkspaceBindings by fragment deltas: symbols, declaration precedence,
  name lookup, edge directions, occurrences, diagnostics and warnings.
- [x] C3. Replace whole-aggregate JSON sizing with per-fragment retained accounting;
  expose maintenance counters and keep the configured memory admission limit.
- [x] C4. Run references/hierarchy/rename regressions and before/after benchmarks. Body
  edits must avoid rebuilding unrelated postings; API edits must preserve correctness.

### D. Consolidation and delivery

- [x] D1. Apply bounded canonical reuse to typed contracts; collision-safe structural
  equality, context/owner separation and eviction must preserve correctness.
- [ ] D2. Document publication/persistence boundaries and schema versions. Reconcile the
  obsolete SQLite-only design with the shipped artifact backend and new semantic model.
- [ ] D3. Run focused tests and relevant compiler/navigation/index gates; record actual
  results, costs, failures, open risks and any remaining work without silently closing it.
- [ ] D4. Commit and push the work; open a draft PR against the optimisation branch and
  record its validation state. Do not merge automatically.

## Deliberately separate research decisions

Green/incremental parsing, replacement authenticated storage engines, algebraic accumulators,
and content-defined chunking are deferred pending workload evidence. Durable cross-store
workspace snapshots require an explicit reader/reclamation protocol before advertising restart
reuse of a complete live workspace. This checklist does not relabel existing stores as that protocol.
