# Live State Tree — Progress

Base: `4ac68eaf77a9c0ecb271a78366f442ec9fa6e2c5`
Branch: `refactor/live-state-tree`

## Architectural invariant

### Existing architecture

```text
REQUEST
  |
  v
reconstruct workspace state
  |
  +-- enumerate
  +-- observe
  +-- hash
  +-- sort
  |
  v
derive identity
```

### Target architecture

```text
MUTATION
  |
  v
live state tree
  |
  +-- Merkle
  +-- algebraic aggregates
  +-- semantic fingerprints
  +-- epoch
  |
  v
REQUEST
  |
  v
read identities
```

### Combined state node

```text
             NODE
    +----------------------+
    | Merkle               |
    | MembershipAggregate  |
    | ContentAggregate     |
    | ApiAggregate         |
    | NamespaceAggregate   |
    | Epoch                |
    +----------+-----------+
               |
         child nodes/leaves
```

### Mutation propagation

```text
A.java edit
    |
    v
new leaf contribution
    |
    +-- content changed
    +-- API maybe changed
    +-- namespace maybe changed
    |
    v
algebraic aggregates update
    |
    v
Merkle ancestors update
    |
    v
epoch increments
```

## Starting-point findings

The existing implementation already has pieces that must be consolidated rather than duplicated:

- `CompilerInputs.capture()` reconstructs source membership/content identities by inventorying source roots and observing source candidates.
- `CompilerPool.execute()` validates at the end of a compiler operation by comparing the starting snapshot with a newly captured snapshot.
- `Analyzer.completionKey()` starts with `Context.toString()`, then sorts and appends every other source path/hash.
- `FileSemanticContribution` and `SemanticUpdatePolicy` already define canonical source/API/dependency semantics and will remain the semantic source of truth.
- `RocksWorkspaceState` already implements persistent directory/module Merkle fingerprints, but its full update path still reconstructs source state through `CompilerInputs`. It must become persistence for the canonical live model, not a second live-state owner.
- `MavenResolver` only memoizes a Resolution within one `RequestScope`; unchanged later RPCs still invoke the resolver boundary.

## Baseline

Status: **complete**.

Evidence: PR-local **Live State Tree Proof** run [35804584433](https://github.com/maxjay/jvmd/actions/runs/35804584433), measured at `db3166f18cfd50c92d4950f9d16ad0a4029434ad`. The proof reuses the pinned Apache Maven source files from `CMP-01`; the temporary workflow/script/counters remain only until the final after-capture in Phase 6.

| Metric | Before | After |
| --- | ---: | ---: |
| warm completion latency | 794.375 ms | pending |
| warm completion allocation | 185,434,528 B inclusive thread allocation | pending |
| metadata checks/request | 41,408 | pending |
| source entries visited/request | 2,400 | pending |
| files hashed/request | 0 | pending |
| bytes hashed/request | 0 | pending |
| source inventory calls/request | 218 | pending |
| directories enumerated/request | 2,172 | pending |
| completion-key entries visited/sorted | 1,200 / 1,200 | pending |
| completion-key material bytes | 496,150 B | pending |
| Maven resolver calls on unchanged `deps.graph` | 1 | pending |
| project-model inputs validated/request | 195 | pending |
| project-model bytes hashed/request | 241,632 B | pending |
| Resolution JSON response bytes/request | 1,260,384 B | pending |
| relevant API edit latency | 741.699 ms | pending |
| body-only edit reuse | **no** — recomputed | pending |

Additional baseline facts:

- Warm completion calls `CompilerInputs.capture()` twice, inspects 3,774 environment candidates, and performs one full completion computation.
- The completion request itself crossed the Maven resolver boundary twice and serialized 2,520,768 response bytes.
- A relevant unsaved API edit did **not** expose `benchmarkAddedMethod`; the Apache Maven completion result was empty. This is recorded as a pre-existing correctness failure, not hidden or treated as acceptable final behavior.
- The unrelated body-edit probe also recomputed completion instead of reusing it.
- A POM edit cost 3,416.834 ms in this run; the next unchanged project-model request still validated 195 inputs and re-hashed 241,678 bytes.
- Run-wide peak heap observed was 499,862,944 B. Peak process-tree RSS was 1,158,774,784 B (server peak 1,054,912,512 B).

Baseline change: temporary counters plus disposable proof workflow/script only; no production ownership changed.
Tests: repository Tests and ordinary Benchmarks passed on the baseline branch.
PR-local measurement: complete; evidence artifact retained by GitHub Actions for the PR evidence window.
Remaining discrepancy: the request path still reconstructs source/environment identity and the Apache Maven completion correctness mismatch remains to be fixed by the semantic completion cutover.
Commit: `bb2ffe2` introduced the measurement mechanism; `db3166f` is the accepted baseline evidence revision.

## Phase 1 — canonical state primitives

Status: **complete**.

Baseline evidence: baseline above, captured before production changes.
Change: added `LiveStateTree` as the canonical source-state primitive. Each source leaf is path-bound and carries independent content/API/namespace identity. Package/root/workspace nodes maintain a cryptographic Merkle identity plus membership/content/API/namespace algebraic aggregates and a monotonic epoch. The algebraic accumulator is a domain-separated SHA-256 contribution summed modulo the secp256k1 field prime with cardinality; it is explicitly not raw XOR, and the Merkle identity remains the independent strong structural identity. Aggregate identities are refreshed on mutation, not derived by reads.
Tests: `LiveStateTreeTest` permanently covers body-only edits, API vs namespace independence, add/remove/rename path binding, A→B→A epoch detection, unaffected subtree identity, deterministic insertion order, and canonical exported-name sets. GitHub Tests run 35805400523 completed the Phase 1 deterministic checkpoint successfully.
PR-local measurement: not applicable; Phase 1 intentionally changes no consumers.
Remaining discrepancy: `CompilerInputs`, completion and Maven still use their old request-time ownership paths; Phase 2 must feed actual source mutations into this canonical state before any read-path cutover.
Commit: `5048399` establishes primitives; `00b6503` places their regression tests in the repository's deterministic Phase 1 group.

## Phase 2 — live source state

Status: **complete**.

Baseline evidence: baseline above remains the request-path reference; Phase 2 intentionally adds mutation-side maintenance before cutting over readers.
Change: `81ccee5` adds session-owned `LiveSourceState`: editor open/change/close updates effective content synchronously, filesystem events update disk membership/content through a recursive watcher, semantic attribution feeds the existing `FileSemanticContribution` API/exported-name identities into the same tree, stale semantic results are rejected by source hash, and watcher uncertainty advances the transition epoch before an explicit reconciliation. `230f59e` removes Rocks' duplicate source discovery/directory-Merkle ownership; Rocks now persists the canonical `LiveStateTree.State` plus membership needed to remove persisted semantic contributions. `b826943` fixes a lifecycle issue found by the full suite so live source state is created only from the shared session `Documents`, never an analyzer-private temporary document store.
Tests: permanent `LiveSourceStateTest` covers editor mutation, semantic-content fencing, preserved-mtime disk edits, and uncertainty/reconciliation. Existing `LiveStateTreeTest` proves independent information-domain updates and unaffected subtree identity. Tests run 35807087303 passed the full repository gate after the ownership fix; Benchmarks run 35807087310 and Live State Tree Proof run 35807087312 also passed.
PR-local measurement: proof remained green after the mutation-side and Rocks ownership cutover. Request-time costs are expected to remain until Phases 3–5, so no latency win is claimed for Phase 2.
Remaining discrepancy: `CompilerInputs.capture()`, compiler end-of-query validation, `IndexedFileManager` source inventory, completion-key all-source construction, and Maven model validation still use request-time work. Phase 3 now cuts compiler/source validation over to the maintained state.
Commit: `81ccee5` live ingestion, `230f59e` canonical Rocks projection, `b826943` session-ownership repair.

## Phase 3 — CompilerInputs cutover

Status: **complete**.

Baseline evidence: accepted baseline proof run 35804584433 at `db3166f`. The post-cutover proof is run 35849676844 at `2917a17`; ordinary prepared-workspace Benchmarks run 35849676837 passed, and the repository Phase-4 compiler checkpoint in Tests run 35849676843 passed.

Change: `CompilerInputs.Snapshot` now captures the canonical `LiveSourceState` identities and source-input epoch instead of a request-built `Map<Path,String>`. `CompilerPool` fences javac transactions on the captured source epoch/membership/content identities, validates implicit source bytes as they are actually read, and synchronously observes only explicit compilation units at transaction boundaries. `IndexedFileManager` reads package/source membership from the mutation-maintained live state. `WorkspaceBindings` updates its retained file facts from the live changed-path journal and only performs explicit owner enumeration at compatibility/cold ownership boundaries.

Tests: permanent live-state, source-transition, preserved-mtime, A→B→A, implicit-read, editor-overlay, cross-root, retry and compiler lifecycle tests all pass through the Phase-4 compiler checkpoint. Two correctness repairs were required by the gate: explicit focused/completion buffers are immutable task inputs and must not be compared to the original file hash; direct request/retry boundaries synchronously observe their bounded relevant source set so correctness does not depend on WatchService scheduling.

PR-local measurement: on Apache Maven warm unchanged proof, compiler source reconstruction fell from 2 captures / 2,400 source entries inspected / 218 source inventory calls to 1 capture / **0 source candidates inspected / 0 source inventory calls**. Directory enumerations in the proof interval fell from 2,172 to 1,155 and metadata checks from 41,408 to 17,044. Warm inclusive thread allocation fell from 185,434,528 B to 143,360,416 B. The proof latency moved from 794.375 ms to 531.788 ms, but this number is retained only as computational-shape evidence because the disposable Apache completion probe itself still returns an empty completion result and is not a correctness oracle. The normal prepared-workspace benchmark is the correctness oracle and is green.

Remaining discrepancy: environment identity is still reconstructed/validated on requests (3,774 environment candidates in the warm proof), completion still sorts/visits 1,200 source entries and materializes ~496 KB of key material, and unchanged Maven project-model requests still cross the resolver boundary and validate 195 inputs / hash ~241 KB / serialize ~1.26 MB. Those are Phase 4 and Phase 5 ownership paths, not source-state work.

Commits: `e861b6e` through `098e750` perform the source-state cutover; `34a5946`, `de5056f`, `6ddc700`, `e14b9d3`, and `2917a17` close deterministic transaction/retry/completion-boundary correctness gaps without restoring workspace scans.

## Phase 4 — completion identity cutover

Status: **not started**.

Baseline evidence: pending.
Change: pending.
Tests: pending.
PR-local measurement: pending.
Remaining discrepancy: pending.
Commit: pending.

## Phase 5 — project-model identity

Status: **not started**.

Baseline evidence: pending.
Change: pending.
Tests: pending.
PR-local measurement: pending.
Remaining discrepancy: pending.
Commit: pending.

## Phase 6 — delete superseded machinery

Status: **not started**.

Baseline evidence: n/a.
Change: pending.
Tests: full regression suite pending.
PR-local measurement: final rerun pending.
Remaining discrepancy: pending.
Commit: pending.

## Remaining complexity

To be filled from the implemented code, not inferred from percentage changes:

- O(workspace): pending
- O(module): pending
- O(dependency closure): pending
- O(result size): pending
