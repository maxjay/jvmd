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

Status: **not started**.

Baseline evidence: pending baseline above.
Change: pending.
Tests: pending.
PR-local measurement: not applicable; no consumers change in Phase 1.
Remaining discrepancy: all request consumers still use the old state path until Phases 3–5.
Commit: pending.

## Phase 2 — live source state

Status: **not started**.

Baseline evidence: pending.
Change: pending.
Tests: pending.
PR-local measurement: pending mutation-cost evidence.
Remaining discrepancy: pending.
Commit: pending.

## Phase 3 — CompilerInputs cutover

Status: **not started**.

Baseline evidence: pending.
Change: pending.
Tests: pending.
PR-local measurement: pending.
Remaining discrepancy: pending.
Commit: pending.

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
